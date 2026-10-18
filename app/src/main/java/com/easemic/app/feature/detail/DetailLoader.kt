package com.easemic.app.feature.detail

import com.easemic.app.AppGraph
import com.easemic.app.data.room.CloudMirrorChapterEntity
import com.easemic.app.feature.bridge.ByoCloudIoResult
import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.CloudChapterRow
import com.easemic.app.feature.browse.DetailEntryOrigin
import com.easemic.app.feature.browse.RemoteProgressPolicy
import com.easemic.app.feature.browse.RemoteReadingProgressStore
import com.easemic.app.feature.browse.SerializedDetailOpenState
import com.easemic.app.feature.browse.SerializedDetailOpenToken
import com.easemic.app.feature.browse.completeSerializedDetail
import com.easemic.app.feature.browse.loadSerializedDetailChapters
import com.easemic.app.feature.browse.sameRemoteItem
import com.easemic.app.feature.browse.seriesDetailEmptyLine
import com.easemic.app.feature.collect.RefreshDecision
import com.easemic.app.feature.collect.isSerializedBookmark
import com.easemic.app.feature.download.ChapterIdentityV2
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.chapterNoun
import com.easemic.app.feature.history.HistoryReopenTarget
import com.easemic.app.feature.providers.ZaimanhuaAccount
import com.easemic.app.feature.source.ChapterListCompleteness
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.ChapterSummary
import com.easemic.app.feature.source.ComicSource
import com.easemic.app.feature.source.SeriesDetailMetadata
import com.easemic.app.feature.source.SeriesDetailSnapshot
import com.easemic.app.feature.source.SeriesPublicationStatus
import com.easemic.app.feature.source.preferNewerSeriesSnapshot
import com.easemic.app.sync.CloudCardLayout
import com.easemic.app.sync.CloudCollectionCard
import com.easemic.app.sync.CloudCollectionKeys
import com.easemic.app.sync.CloudCollectionStore
import com.easemic.app.sync.CloudSeriesChapterDescriptor
import com.easemic.app.sync.CloudSeriesManifest
import com.easemic.app.i18n.AppStrings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app-root state a [DetailLoader] reads its open guard from and publishes
 * its stages into. Function-typed on purpose: the app root binds its remembered
 * state vars and local functions here without an adapter class per screen, and
 * the loader never sees Compose state.
 */
class DetailOpenStage(
    /** The item the open detail is for (the app root's remoteDetailItem). */
    val openItem: () -> BrowseFeedItem?,
    /**
     * The open generation of the detail currently on screen. READ-ONLY here:
     * the tag-search pushes keep invalidating it from outside; the loader only
     * captures its own open's value and rechecks it before every publish.
     */
    val openGeneration: () -> Long,
    val cloudBucketFingerprint: () -> String?,
    val libraryCloudCards: () -> List<CloudCollectionCard>,
    val snapshot: () -> SeriesDetailSnapshot?,
    val setSnapshot: (SeriesDetailSnapshot?) -> Unit,
    val chapters: () -> ChapterListResult?,
    val setChapters: (ChapterListResult?) -> Unit,
    val metadataLoading: () -> Boolean,
    val setMetadataLoading: (Boolean) -> Unit,
    val chaptersUnavailable: () -> Boolean,
    val setChaptersUnavailable: (Boolean) -> Unit,
    val seriesManifest: () -> CloudSeriesManifest?,
    val setSeriesManifest: (CloudSeriesManifest?) -> Unit,
    val cloudChapterRows: () -> List<CloudChapterRow>,
    val setCloudChapterRows: (List<CloudChapterRow>) -> Unit,
    val sayRemote: (message: String?, acquisition: String?) -> Unit,
    val readSerializedChapter: (item: BrowseFeedItem, chapterId: String, initialOrdinal: Int?) -> Unit,
    val applyZaimanhuaAccount: (ZaimanhuaAccount) -> Unit,
    val setPendingSerializedConfirmation: (item: BrowseFeedItem, chapterCount: Int) -> Unit,
    // F4b — the cloud-card open's own publications (the app root's
    // remoteDetailItem / remoteDetailSeriesEmptyLine / remoteDetailOrigin* /
    // remoteDetailCloudStore / cloudPreviewInitialBusy vars):
    val setOpenItem: (BrowseFeedItem?) -> Unit,
    val setSeriesEmptyLine: (String?) -> Unit,
    val setOriginAcquisition: (String?) -> Unit,
    val setOriginProviderId: (String?) -> Unit,
    val setCloudStore: (CloudCollectionStore?) -> Unit,
    val setCloudPreviewInitialBusy: (Boolean) -> Unit,
    /**
     * The app root's gated content-store resolution (`productObjectStore()` —
     * generation-before-binding wrap included). Threaded as a function so the
     * loader resolves the SAME instance the app root does; the open publishes
     * it into [setCloudStore] for the reader.
     */
    val productObjectStore: () -> ByoCloudIoResult,
)

/**
 * The one detail loader (spec §3.1 item 4). F4a owns the serialized series
 * open; every stage publishes through [DetailOpenStage] under the open guard —
 * identity + the captured open generation — so a late answer for another comic
 * is dropped. The guard semantics are the ones `openRemoteDetail`'s serialized
 * branch ran inline before this extraction: same checks, same order, same
 * early returns.
 */
class DetailLoader(
    private val appGraph: AppGraph,
    private val stage: DetailOpenStage,
    private val coroutineScope: CoroutineScope,
    private val remoteProgressStore: RemoteReadingProgressStore,
) {
    /** The serialized-series detail open. Gallery and cloud opens follow in F4b. */
    fun openSerialized(
        item: BrowseFeedItem,
        source: ComicSource,
        serializedResume: HistoryReopenTarget?,
        entryOrigin: DetailEntryOrigin,
    ) {
        // The generation of THIS open, captured once; every publish below
        // rechecks it. The tag-search pushes keep invalidating the shared
        // counter from outside — this class only reads it.
        val openGeneration = stage.openGeneration()
        val identity = DetailIdentity.fromBrowseFeedItem(item, DetailContentKind.SERIES)
        // A feed entry always resolves a canonical series id — the same one
        // seriesIdentityFor hands Room, mirrors and progress today.
        val series = checkNotNull(identity.series) {
            "a feed entry always carries a source identity"
        }
        // Start the existing request independently of Room/Main continuations.
        // The UI coroutine publishes saved text before awaiting its result.
        val chapterRequest = coroutineScope.async(Dispatchers.IO) {
            try {
                if (entryOrigin == DetailEntryOrigin.SOURCE) {
                    loadSerializedDetailChapters(source, item)
                } else {
                    source.chapters(series)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        coroutineScope.launch {
            val persisted = withContext(Dispatchers.IO) {
                appGraph.repository.seriesDetailSnapshot(series)
            }
            if (sameRemoteItem(stage.openItem(), item) && stage.openGeneration() == openGeneration) {
                stage.setSnapshot(preferNewerSeriesSnapshot(stage.snapshot(), persisted))
            } else return@launch
            val mirroredSeriesCard = stage.libraryCloudCards().firstOrNull { card ->
                card.layoutKind == CloudCardLayout.SERIES &&
                    card.providerId == item.providerId &&
                    card.providerSeriesId == item.id
            }
            if (mirroredSeriesCard != null) {
                val fp = stage.cloudBucketFingerprint()
                val mirroredManifest = if (fp == null) {
                    null
                } else {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            cloudSeriesManifestFromMirror(
                                mirroredSeriesCard,
                                appGraph.cloudMirrorStore.chapterRows(fp, mirroredSeriesCard.cloudComicId),
                                appGraph.cloudMirrorStore.seriesDetailSnapshot(fp, mirroredSeriesCard.cloudComicId),
                            )
                        }.getOrNull()
                    }
                }
                if (!sameRemoteItem(stage.openItem(), item) || stage.openGeneration() != openGeneration) return@launch
                if (mirroredManifest != null) {
                    stage.setSnapshot(
                        preferNewerSeriesSnapshot(stage.snapshot(), mirroredManifest.detailSnapshot),
                    )
                    stage.setSeriesManifest(mirroredManifest)
                    stage.setCloudChapterRows(mirroredManifest.toCloudChapterRows())
                }
            }
            if (entryOrigin == DetailEntryOrigin.SAVED) {
                stage.setChapters(ChapterListResult(emptyList(), 0L, ChapterListCompleteness.UNKNOWN))
            }
            // Detail-open follow refresh runs on every entry. Reuse this one
            // response for text, chapters and follow reconciliation below.
            val chapters = chapterRequest.await()
            val token = SerializedDetailOpenToken(
                series = series,
                generation = openGeneration,
            )
            if (sameRemoteItem(stage.openItem(), item) && stage.openGeneration() == openGeneration) {
                val current = SerializedDetailOpenState(
                    token = token,
                    snapshot = stage.snapshot(),
                    chapters = stage.chapters(),
                    loading = true,
                    sourceUnavailable = stage.chaptersUnavailable(),
                )
                val complete = completeSerializedDetail(
                    current, token, chapters,
                )
                stage.setSnapshot(complete.snapshot)
                stage.setChapters(
                    complete.chapters ?: if (entryOrigin == DetailEntryOrigin.SAVED) {
                        ChapterListResult(emptyList(), 0L, ChapterListCompleteness.UNKNOWN)
                    } else null,
                )
                stage.setMetadataLoading(complete.loading)
                stage.setChaptersUnavailable(chapters == null)
                if (item.providerId == GalleryIdentityV1.ZAIMANHUA) {
                    stage.applyZaimanhuaAccount(withContext(Dispatchers.IO) { appGraph.zaimanhuaAccountStore.read() })
                }
                if (chapters == null) {
                    stage.sayRemote(
                        AppStrings.chapterStateText("Source unavailable · showing saved chapters"),
                        item.acquisitionUrl,
                    )
                }
                chapters?.let { fetched ->
                    // F3 (a) series-info write-back (spec §3.3):
                    // fire-and-forget — never blocks or fails this open.
                    coroutineScope.launch(Dispatchers.IO) {
                        appGraph.writeBackSeriesInfoAfterLiveStage(item, fetched)
                    }
                    coroutineScope.launch(Dispatchers.IO) {
                        val refresh = appGraph.refreshSerializedSeriesOnDetailOpen(item, fetched)
                        if (
                            refresh != null &&
                            refresh.decision == RefreshDecision.NEEDS_CONFIRMATION &&
                            refresh.confirmationChapterCount > 0
                        ) {
                            withContext(Dispatchers.Main) {
                                if (sameRemoteItem(stage.openItem(), item) && stage.openGeneration() == openGeneration) {
                                    stage.setPendingSerializedConfirmation(item, refresh.confirmationChapterCount)
                                }
                            }
                        }
                    }
                }
                serializedResume?.let { resume ->
                    val chapterId = resume.chapterId
                    if (chapterId != null && sameRemoteItem(resume.item, item)) {
                        val currentProgress = remoteProgressStore.get(
                            RemoteProgressPolicy.keyForChapter(series, chapterId),
                        )
                        stage.readSerializedChapter(
                            item, chapterId, currentProgress?.ordinal ?: resume.initialOrdinal,
                        )
                    }
                }
            }
        }
    }

    /**
     * The cloud-card SERIES detail open — the #E77 staged merge. F4b: moved
     * from `openRemoteDetail`'s cloud branch; same publications, same order,
     * same guards, same early returns. The meta.json single-chapter path and
     * the gallery fallthrough stay in the app root.
     */
    fun openCloud(
        item: BrowseFeedItem,
        cloudCardForDetail: CloudCollectionCard,
    ) {
        // The generation of THIS open, captured once; every publish below
        // rechecks it. The tag-search pushes keep invalidating the shared
        // counter from outside — this class only reads it.
        val openGeneration = stage.openGeneration()
        coroutineScope.launch {
            var store: CloudCollectionStore? = null
            try {
                // #E77: the staged text merge (spec §3.1-4 stored
                // stage): the seed above, then this phone's Room, the
                // cloud mirror and the bucket manifest, each step
                // prefer-newer-merged and published under the open
                // guard — instead of the manifest-or-nothing read that
                // left a collected series' detail with no text.
                // Identity comes from the SERIES card's own fields,
                // never seriesIdentityFor(item): a cloud-card item can
                // carry the cloud collection's own provider id (#E97
                // class), which matches no Room row, mirror row or
                // bookmark.
                val sourceIdentity = DetailIdentity.fromCloudCard(cloudCardForDetail).series
                if (sourceIdentity != null) {
                    val persisted = withContext(Dispatchers.IO) {
                        runCatching {
                            appGraph.repository.seriesDetailSnapshot(sourceIdentity)
                        }.getOrNull()
                    }
                    if (sameRemoteItem(stage.openItem(), item) &&
                        stage.openGeneration() == openGeneration
                    ) {
                        stage.setSnapshot(preferNewerSeriesSnapshot(stage.snapshot(), persisted))
                    } else return@launch
                    val mirroredSnapshot = withContext(Dispatchers.IO) {
                        val fp = stage.cloudBucketFingerprint()
                        if (fp == null) {
                            null
                        } else {
                            runCatching {
                                appGraph.cloudMirrorStore.seriesDetailSnapshot(fp, cloudCardForDetail.cloudComicId)
                            }.getOrNull()
                        }
                    }
                    if (sameRemoteItem(stage.openItem(), item) &&
                        stage.openGeneration() == openGeneration
                    ) {
                        stage.setSnapshot(preferNewerSeriesSnapshot(stage.snapshot(), mirroredSnapshot))
                    } else return@launch
                }
                val remoteSeries = withContext(Dispatchers.IO) {
                    val io = stage.productObjectStore()
                    val ready = (io as? ByoCloudIoResult.Ready)?.store
                        ?: return@withContext null
                    val resolved = CloudCollectionStore(ready)
                    store = resolved
                    val key = cloudCardForDetail.seriesMetaObjectKey
                        ?: CloudCollectionKeys.seriesManifestKey(cloudCardForDetail.cloudComicId)
                    runCatching { resolved.downloadSeriesManifest(key) }.getOrNull()
                }
                val mirrorRows = withContext(Dispatchers.IO) {
                    val fp = stage.cloudBucketFingerprint()
                    if (fp == null) {
                        emptyList()
                    } else {
                        runCatching {
                            appGraph.cloudMirrorStore.chapterRows(fp, cloudCardForDetail.cloudComicId)
                        }.getOrNull().orEmpty()
                    }
                }
                val series = if (remoteSeries != null) {
                    val mirroredManifest = cloudSeriesManifestFromMirror(cloudCardForDetail, mirrorRows)
                    if (mirroredManifest == null) {
                        remoteSeries
                    } else {
                        val remoteIds = remoteSeries.chapters.mapTo(mutableSetOf()) { it.providerChapterId }
                        val extraMirrored = mirroredManifest.chapters.filterNot { it.providerChapterId in remoteIds }
                        if (extraMirrored.isEmpty()) {
                            remoteSeries
                        } else {
                            // Self-heal the bucket's series.json in the background when this phone's
                            // mirror/ledger has chapters whose tail write was deferred (#E111).
                            val healStore = store
                            if (healStore != null && sourceIdentity != null) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    runCatching {
                                        val fp = stage.cloudBucketFingerprint()
                                        if (fp != null) {
                                            val key = com.easemic.app.feature.download.serializedSeriesLedgerKey(sourceIdentity)
                                            val syncedIds = appGraph.cloudSyncLedgerStore.syncedChapterIds(fp, key)
                                            val missingMirrored = extraMirrored.filter { it.providerChapterId in syncedIds }
                                            if (missingMirrored.isNotEmpty()) {
                                                healStore.healSeriesManifestFromChapterMetas(
                                                    seriesCloudId = cloudCardForDetail.cloudComicId,
                                                    syncedChapterKeys = mirroredManifest.chapters
                                                        .filter { it.providerChapterId in syncedIds }
                                                        .associate { it.providerChapterId to it.chapterKey },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            remoteSeries.copy(
                                chapters = (remoteSeries.chapters + extraMirrored).sortedBy { it.readingOrder },
                            )
                        }
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        val fp = stage.cloudBucketFingerprint()
                        cloudSeriesManifestFromMirror(
                            cloudCardForDetail, mirrorRows,
                            fp?.let { appGraph.cloudMirrorStore.seriesDetailSnapshot(fp, cloudCardForDetail.cloudComicId) },
                        )
                    }
                }
                if (series == null) {
                    // #E21 tail 3: say why the chapter list is blank
                    // instead of returning silently. The hydration
                    // retry already exists, so no button here.
                    if (sameRemoteItem(stage.openItem(), item) &&
                        stage.openGeneration() == openGeneration
                    ) {
                        stage.setSeriesEmptyLine(seriesDetailEmptyLine(
                            manifestMissing = remoteSeries == null,
                            hasMirrorRows = mirrorRows.isNotEmpty(),
                        ))
                        // #E77 offline: stored text still renders —
                        // only the chapter list is explained away.
                        if (stage.snapshot() != null) {
                            stage.setMetadataLoading(false)
                        }
                    }
                    // A bare return is only for truly nothing stored.
                    if (stage.snapshot() == null) return@launch
                } else {
                    if (!sameRemoteItem(stage.openItem(), item) || stage.openGeneration() != openGeneration) return@launch
                    val chapterResult = series.toChapterListResult()
                    val activeChapterCount = series.chapters.count { it.sourceState == "ACTIVE" }
                    stage.setOpenItem(item.copy(
                        title = series.title,
                        secondaryLine = "$activeChapterCount / ${series.chapters.size} ${chapterNoun(series.chapters.size)}",
                        upstreamAcquisitionUrl = series.seriesAcquisitionRef,
                    ))
                    stage.setOriginAcquisition(series.seriesAcquisitionRef)
                    stage.setOriginProviderId(series.providerId)
                    stage.setChapters(chapterResult)
                    stage.setSnapshot(preferNewerSeriesSnapshot(
                        stage.snapshot(),
                        series.detailSnapshot,
                    ))
                    stage.setCloudChapterRows(series.toCloudChapterRows())
                    stage.setSeriesManifest(series)
                    stage.setChaptersUnavailable(false)
                    stage.setMetadataLoading(false)
                    stage.setCloudStore(store)
                }
                // §3.3 first bullet: the MERGED table is what goes back
                // to Room. restoreSeriesDetailSnapshot self-gates on the
                // download-ownership claim and staleness.
                val merged = stage.snapshot()
                if (sourceIdentity != null && merged != null) {
                    val fallbackTitle = series?.title ?: item.title
                    val fallbackCover = item.coverUrl
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            appGraph.repository.restoreSeriesDetailSnapshot(
                                sourceIdentity,
                                merged,
                                fallbackTitle,
                                fallbackCover,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Fire-and-forget: the next open retries.
                        }
                    }
                }
                // Spec §3.1-4 live stage from every entry: a collected
                // series runs the same detail-open source refresh the
                // serialized branch runs (one source request, reused for
                // text, chapters and follow reconciliation). A failed or
                // text-less answer NEVER clears stored text (e45 §5.3).
                if (sourceIdentity != null) {
                    val liveSource = appGraph.comicSourceRegistry
                        .source(sourceIdentity.providerNamespace)
                        ?.takeIf { it.descriptor.serialized }
                    val collectedBookmark = if (liveSource == null) {
                        null
                    } else {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                appGraph.collectBookmarkStore.readAll().firstOrNull {
                                    isSerializedBookmark(it) &&
                                        it.providerId == sourceIdentity.providerNamespace &&
                                        it.id == sourceIdentity.canonicalSeriesId
                                }
                            }.getOrNull()
                        }
                    }
                    if (liveSource != null && collectedBookmark != null) {
                        // TRAP: refreshSerializedSeriesOnDetailOpen
                        // matches the bookmark through seriesIdentityFor,
                        // so hand it an item that names the SOURCE
                        // series, not the cloud card's identity (#E97).
                        val liveItem = item.copy(
                            providerId = sourceIdentity.providerNamespace,
                            id = sourceIdentity.canonicalSeriesId,
                        )
                        val liveChapters = withContext(Dispatchers.IO) {
                            try {
                                liveSource.chapters(sourceIdentity)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                        if (sameRemoteItem(stage.openItem(), item) &&
                            stage.openGeneration() == openGeneration
                        ) {
                            val token = SerializedDetailOpenToken(
                                series = sourceIdentity,
                                generation = openGeneration,
                            )
                            val current = SerializedDetailOpenState(
                                token = token,
                                snapshot = stage.snapshot(),
                                chapters = stage.chapters(),
                                loading = true,
                                sourceUnavailable = stage.chaptersUnavailable(),
                            )
                            val complete = completeSerializedDetail(
                                current, token, liveChapters,
                            )
                            stage.setSnapshot(complete.snapshot)
                            complete.chapters?.let { stage.setChapters(it) }
                            stage.setMetadataLoading(complete.loading)
                            if (liveChapters == null) {
                                stage.setChaptersUnavailable(true)
                                if (stage.snapshot() != null) {
                                    stage.sayRemote(
                                        AppStrings.chapterStateText("Source unavailable · showing saved chapters"),
                                        item.acquisitionUrl,
                                    )
                                }
                            } else {
                                stage.setChaptersUnavailable(false)
                                // F3 (a) series-info write-back (spec
                                // §3.3): fire-and-forget like the
                                // serialized branch's fold.
                                coroutineScope.launch(Dispatchers.IO) {
                                    appGraph.writeBackSeriesInfoAfterLiveStage(liveItem, liveChapters)
                                }
                                coroutineScope.launch(Dispatchers.IO) {
                                    val refresh = appGraph.refreshSerializedSeriesOnDetailOpen(liveItem, liveChapters)
                                    if (
                                        refresh != null &&
                                        refresh.decision == RefreshDecision.NEEDS_CONFIRMATION &&
                                        refresh.confirmationChapterCount > 0
                                    ) {
                                        withContext(Dispatchers.Main) {
                                            if (sameRemoteItem(stage.openItem(), item) &&
                                                stage.openGeneration() == openGeneration
                                            ) {
                                                stage.setPendingSerializedConfirmation(
                                                    liveItem, refresh.confirmationChapterCount,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                return@launch
            } finally {
                // The branch's every exit (including its early returns) ran
                // through this when it sat inside openRemoteDetail's shared
                // try — notably the bare return above, where this is the only
                // thing that stops the spinner.
                stage.setCloudPreviewInitialBusy(false)
                if (sameRemoteItem(stage.openItem(), item)) {
                    stage.setMetadataLoading(false)
                }
            }
        }
    }
}

/**
 * Projects one series card's stored mirror rows into the manifest the detail
 * stages publish. Moved from `ui/PrivateComicLibraryApp.kt` (F4a) beside the
 * loader that consumes it; the cloud branch keeps calling it from there.
 */
internal fun cloudSeriesManifestFromMirror(
    card: CloudCollectionCard,
    rows: List<CloudMirrorChapterEntity>,
    detailSnapshot: SeriesDetailSnapshot? = null,
): CloudSeriesManifest? {
    if (rows.isEmpty()) return null
    val chapters = rows.mapNotNull { row ->
        val chapterKey = row.chapterKey ?: return@mapNotNull null
        CloudSeriesChapterDescriptor(
            providerChapterId = row.chapterIdentity,
            chapterKey = chapterKey,
            label = row.label.orEmpty(),
            group = row.groupLabel,
            readingOrder = row.ordinal,
            sourceUpdatedAtEpochMs = null,
            sourceState = row.sourceState,
            removedAtEpochMs = row.trashedAtEpochMs,
            pageCount = row.pageCount,
            coverObjectKey = row.coverObjectKey,
        )
    }
    if (chapters.isEmpty()) return null
    return CloudSeriesManifest(
        seriesCloudId = card.cloudComicId,
        providerId = card.providerId.orEmpty(),
        providerSeriesId = card.providerSeriesId,
        seriesAcquisitionRef = card.acquisitionRef.orEmpty(),
        title = card.title,
        coverObjectKey = card.coverThumbObjectKey,
        generation = card.seriesGeneration,
        chapters = chapters,
        detailSnapshot = detailSnapshot,
    )
}

/**
 * The chapter-row projection of a manifest for the detail chapter grid.
 * Moved from `ui/PrivateComicLibraryApp.kt` (F4a) beside the loader that
 * consumes it; the cloud branch keeps calling it from there.
 */
internal fun CloudSeriesManifest.toCloudChapterRows(): List<CloudChapterRow> {
    val groups = chapters.map { it.group }.distinct().withIndex().associate { (index, group) -> group to index }
    return chapters.map { descriptor ->
        CloudChapterRow(
            chapterId = descriptor.providerChapterId,
            label = descriptor.label,
            group = descriptor.group,
            groupOrder = groups.getValue(descriptor.group),
            readingOrderInGroup = descriptor.readingOrder,
            pageCount = descriptor.pageCount ?: 0,
            sourceState = descriptor.sourceState,
        )
    }
}

/**
 * The chapter-list projection of a manifest for the detail chapter grid.
 * Moved from `ui/PrivateComicLibraryApp.kt` (F4b) beside the loader that
 * consumes it; only the cloud branch's fold calls it.
 */
private fun CloudSeriesManifest.toChapterListResult(): ChapterListResult {
    val series = SeriesIdentityV2(
        providerNamespace = providerId,
        canonicalSeriesId = providerSeriesId ?: seriesAcquisitionRef,
    )
    val groups = chapters.map { it.group }.distinct().withIndex().associate { (index, group) -> group to index }
    return ChapterListResult(
        chapters = chapters.map { descriptor ->
            ChapterSummary(
                identity = ChapterIdentityV2(series, descriptor.providerChapterId),
                label = descriptor.label,
                group = descriptor.group,
                groupOrder = groups.getValue(descriptor.group),
                readingOrderInGroup = descriptor.readingOrder,
                sourceUpdatedAtMillis = descriptor.sourceUpdatedAtEpochMs,
                canRead = descriptor.sourceState == "ACTIVE" && (descriptor.pageCount ?: 0) > 0,
                isFee = false,
            )
        },
        fetchedAtMillis = System.currentTimeMillis(),
        completeness = ChapterListCompleteness.UNKNOWN,
        seriesStatus = detailSnapshot?.status ?: SeriesPublicationStatus.UNKNOWN,
        metadata = detailSnapshot?.metadata ?: SeriesDetailMetadata(),
        statusSupplied = detailSnapshot != null,
    )
}
