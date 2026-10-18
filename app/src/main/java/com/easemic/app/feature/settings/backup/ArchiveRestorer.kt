package com.easemic.app.feature.settings.backup

import com.easemic.app.feature.collect.CollectBookmarkStore
import com.easemic.app.feature.collect.isSerializedBookmark
import com.easemic.app.feature.collect.CollectedBookmark
import com.easemic.app.feature.browse.RemoteReadingProgressStore
import com.easemic.app.feature.download.DownloadCommitCompleteness
import com.easemic.app.feature.download.DownloadCommitMetadata
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.materializeCatalogDownloadPages
import com.easemic.app.feature.history.BrowseHistoryStore
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.feature.providers.OpdsProviderConfig
import com.easemic.app.feature.providers.OpdsProviderStore
import com.easemic.app.feature.reader.ReaderKeepScreenOnStore
import com.easemic.app.feature.reader.ReaderRtlPagingStore
import com.easemic.app.feature.reader.ReaderVolumeKeyPagingStore
import com.easemic.app.feature.settings.CacheBudgetStore
import com.easemic.app.feature.settings.CloudReleaseAfterUploadStore
import com.easemic.app.feature.settings.LibraryLayoutStore
import com.easemic.app.feature.settings.MaxConcurrentTransfersStore
import com.easemic.app.model.DocumentRef
import com.easemic.app.model.LibraryItemId
import com.easemic.app.model.ReadingUnitId
import com.easemic.app.sync.archive.ArchiveComic
import com.easemic.app.sync.archive.ArchiveChapter
import com.easemic.app.sync.archive.ArchiveFormatException
import com.easemic.app.sync.archive.ArchiveReader
import com.easemic.app.sync.archive.ArchiveSeries
import com.easemic.app.sync.archive.ArchiveState
import com.easemic.app.ui.LibraryViewMode
import com.easemic.app.ui.PreferenceMap
import java.io.File
import java.io.InputStream
import java.util.UUID
import org.json.JSONArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Stages restored archive pages under the bare ordinal names used by catalog downloads. */
internal fun stageSeriesChapterPages(
    directory: File,
    pages: Sequence<Pair<String, InputStream>>,
): List<File> {
    directory.mkdirs()
    return pages.mapIndexed { pageIndex, (_, input) ->
        File(directory, pageIndex.toString()).also { file ->
            file.outputStream().use { output -> input.use { it.copyTo(output, 1 shl 16) } }
        }
    }.toList()
}

/**
 * #D7 restore (spec §6): adds, never replaces or deletes.
 *
 * - A comic this phone already has downloaded is skipped. Any other comic's pages are staged, and only once every
 *   page is on disk are they placed with [materializeCatalogDownloadPages] and committed through [commit] — the one
 *   path a finished download uses (`commitDownloadLocalAvailability`), so the shelf, the reader, delete and Collect
 *   treat it as an ordinary download. A comic that fails mid-way leaves no row and no staging dir.
 * - Bookmarks merge one per gallery ([CollectBookmarkStore.adoptAll]); history merges through the store's own
 *   collapse (newest visit wins); settings apply; remote progress adds only missing keys; an OPDS catalog is added
 *   only when no catalog has its URL.
 * - Progress applies by gallery key, falling back to title + page count, after the comics are in.
 */
class ArchiveRestorer(
    private val prefs: PreferenceMap,
    private val filesDir: File,
    private val alreadyDownloaded: suspend (GalleryIdentityV1) -> Boolean,
    private val commit: suspend (
        identity: GalleryIdentityV1,
        downloadLocationUri: String,
        title: String,
        pages: List<DocumentRef>,
        metadata: DownloadCommitMetadata,
        completeness: DownloadCommitCompleteness,
    ) -> LibraryItemId,
    /** Every Library row a saved position could land on, read after the comics are committed. */
    private val progressTargets: suspend () -> List<ProgressTarget>,
    private val setProgress: suspend (ReadingUnitId, Int) -> Unit,
    private val freeBytes: () -> Long,
    private val alreadySeries: suspend (ArchiveSeries) -> Boolean = { false },
    private val restoreSeries: suspend (ArchiveSeries, File?, List<SeriesChapter>, Boolean) -> Boolean = { _, _, _, _ -> false },
    /**
     * #E18: records a restored gallery as a finished download right after its commit, so a later Collect merges its
     * cloud intent onto that FINISH row instead of enqueueing a fresh fetch job that re-downloads every restored
     * page. Production wires this in AppGraph.archiveRestorer() to
     * RoomDownloadJobStore.insertRestoredFinish(intentCloud = false) — a restore must not start uploads by itself.
     */
    private val recordRestoredGalleryJob: suspend (GalleryIdentityV1, pageCount: Int) -> Unit = { _, _ -> },
) {
    data class ProgressTarget(
        val key: String,
        val title: String,
        val pageCount: Int,
        val readingUnitId: ReadingUnitId,
        val currentProgressOrdinal: Int = 0,
    )
    data class SeriesChapter(val chapter: ArchiveChapter, val pageFiles: List<File>)

    data class Preview(
        val comics: Int,
        val comicsAlreadyHere: Int,
        val bookmarks: Int,
        val pageBytes: Long,
        val enoughSpace: Boolean,
        /** Bytes free on this phone when the preview was built. */
        val freeBytes: Long = 0,
    )

    data class Result(
        val restored: Int,
        val skipped: Int,
        val failed: Int,
        val bookmarksAdded: Int,
        val progressApplied: Int,
        /** Display names of key-bearing OPDS catalogs the new phone has to add again. */
        val addAgain: List<String> = emptyList(),
    )

    /** Reads only what [ArchiveReader.open] already read (manifest + state): cheap on a multi-GB file. */
    suspend fun preview(reader: ArchiveReader): Preview {
        val here = reader.manifest.comicIds.count { alreadyDownloaded(GalleryIdentityV1(it.providerNamespace, it.canonicalItemId)) } +
            reader.manifest.seriesIds.count { alreadySeries(ArchiveSeries(it.providerId, it.providerSeriesId, "", null, null)) }
        val bytes = reader.manifest.pageBytes
        val free = freeBytes()
        return Preview(
            comics = reader.manifest.comicCount + reader.manifest.seriesCount,
            comicsAlreadyHere = here,
            bookmarks = bookmarksIn(reader.state).size,
            pageBytes = bytes,
            // Each comic is staged, then copied into its download tree, then its staging is removed: the peak is
            // every page once plus one comic twice. A fixed margin covers the second copy of any single comic.
            enoughSpace = free > bytes + SPACE_MARGIN_BYTES,
            freeBytes = free,
        )
    }

    suspend fun restore(
        reader: ArchiveReader,
        onComic: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val staging = File(filesDir, STAGING_DIR)
        staging.deleteRecursively()
        var restored = 0
        var skipped = 0
        var failed = 0
        var index = 0
        val total = reader.manifest.comicCount + reader.manifest.seriesCount
        var cancellation: CancellationException? = null
        try {
            reader.forEachComic { comic, pages ->
                val identity = identityOf(comic)
                val outcome = if (alreadyDownloaded(identity)) {
                    Outcome.SKIPPED
                } else {
                    restoreComic(comic, identity, pages, File(staging, UUID.randomUUID().toString()))
                }
                when (outcome) {
                    Outcome.RESTORED -> restored++
                    Outcome.SKIPPED -> skipped++
                    Outcome.FAILED -> failed++
                }
                index += 1
                onComic(index, total)
            }
            if (reader.manifest.seriesCount > 0) {
                reader.forEachSeries { entry ->
                    val series = entry.series
                    if (alreadySeries(series)) {
                        skipped++
                    } else {
                        val seriesStage = File(staging, "series-${UUID.randomUUID()}").apply { mkdirs() }
                        try {
                            val cover = entry.cover?.let { (name, input) ->
                                File(seriesStage, name).also { file -> file.outputStream().use { input.copyTo(it, 1 shl 16) } }
                            }
                            val chapters = entry.chapters.mapIndexed { chapterIndex, chapterEntry ->
                                val chapter = chapterEntry.chapter
                                val chapterStage = File(seriesStage, "chapter-$chapterIndex").apply { mkdirs() }
                                val files = stageSeriesChapterPages(chapterStage, chapterEntry.pages)
                                if (files.size != chapter.pageFiles.size) throw ArchiveFormatException("series chapter pages disagree")
                                SeriesChapter(chapter, files)
                            }.toList()
                            if (!restoreSeries(series, cover, chapters, isFollowed(series, reader.state))) {
                                throw ArchiveFormatException("series restore failed")
                            }
                            restored++
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            failed++
                        } finally {
                            seriesStage.deleteRecursively()
                        }
                    }
                    index += 1
                    onComic(index, total)
                }
            }
        } catch (e: CancellationException) {
            cancellation = e
        } catch (e: Exception) {
            // The archive itself broke (a damaged chunk, a cut file): what is committed stays; the rest counts as failed.
            failed += (total - index).coerceAtLeast(0)
        } finally {
            staging.deleteRecursively()
        }
        cancellation?.let { cancelled ->
            withContext(NonCancellable) {
                applyState(reader.state)
                applyProgress(reader.state)
                RemoteReadingProgressStore(prefs).mergeMissingRaw(reader.state.remoteProgressJson)
                prefs.putStringSync(RESTORED_AT_KEY, System.currentTimeMillis().toString())
            }
            throw cancelled
        }
        val bookmarksAdded = applyState(reader.state)
        // Device-local online/cloud positions are separate from Room library
        // progress. Merge only keys this phone does not already know.
        val progressApplied = applyProgress(reader.state) +
            RemoteReadingProgressStore(prefs).mergeMissingRaw(reader.state.remoteProgressJson)
        // The stores write with apply() (async). "Restored" must mean restored: one synchronous commit flushes every
        // pending write, so a force-stop right after the result line keeps them (the device leg lost its bookmarks).
        prefs.putStringSync(RESTORED_AT_KEY, System.currentTimeMillis().toString())
        return Result(restored, skipped, failed, bookmarksAdded, progressApplied, reader.state.opdsLeftBehind)
    }

    private enum class Outcome { RESTORED, SKIPPED, FAILED }

    private suspend fun restoreComic(
        comic: ArchiveComic,
        identity: GalleryIdentityV1,
        pages: Sequence<Pair<String, java.io.InputStream>>,
        stageDir: File,
    ): Outcome {
        stageDir.mkdirs()
        var location: String? = null
        var committed = false
        try {
            // #E18: stage under bare ordinals like the downloader does (the archive's `N.ext` names are never stored),
            // so the committed tree is the one shape `resolveLocalCatalogPagesForIngest` reads back for cloud ingest.
            val staged = stageSeriesChapterPages(stageDir, pages)
            if (staged.size != comic.pageFiles.size || staged.isEmpty()) return Outcome.FAILED
            val (materializedLocation, documents) = materializeCatalogDownloadPages(
                filesDir = filesDir,
                identity = identity,
                downloadRevision = RESTORED_REVISION,
                readyPagePaths = staged.map { it.toPath() },
            )
            location = materializedLocation
            commit(
                identity,
                materializedLocation,
                comic.title.ifBlank { identity.canonicalItemId },
                documents,
                DownloadCommitMetadata(tags = comic.tags, language = comic.language, category = comic.category),
                comic.expectedPageCount?.let { DownloadCommitCompleteness.Truncated(it) } ?: DownloadCommitCompleteness.Complete,
            )
            committed = true
            // #E18: a restore that leaves no download-job row makes Collect enqueue a fresh fetch job — every page
            // re-downloaded from the source, overwriting the bytes just restored. Record the present pages as a
            // finished download (the truncation badge lives in the commit metadata); Collect merges its cloud intent
            // onto this row. intentCloud stays off: a restore must not start uploads by itself.
            recordRestoredGalleryJob(identity, staged.size)
            return Outcome.RESTORED
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (!committed) {
                location?.let { locationUri ->
                    runCatching { File(java.net.URI(locationUri)).deleteRecursively() }
                }
            }
            return Outcome.FAILED
        } finally {
            stageDir.deleteRecursively()
        }
    }

    private fun bookmarksIn(state: ArchiveState): List<CollectedBookmark> {
        val array = runCatching { JSONArray(state.bookmarksJson) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                runCatching {
                    val o = array.getJSONObject(i)
                    CollectedBookmark(
                        id = o.getString("id"),
                        title = o.optString("title"),
                        acquisitionUrl = o.getString("acquisitionUrl"),
                        providerId = o.optString("providerId"),
                        coverUrl = o.optString("coverUrl").takeIf { it.isNotBlank() },
                        feedUrl = o.optString("feedUrl").takeIf { it.isNotBlank() },
                        serialized = o.optBoolean("serialized", false),
                    )
                }.getOrNull()?.let(::add)
            }
        }
    }

    /** Returns how many bookmarks were added. */
    private fun applyState(state: ArchiveState): Int {
        val added = CollectBookmarkStore(prefs).adoptAll(bookmarksIn(state))
        mergeHistory(state.historyJson)
        val s = state.settings
        ReaderKeepScreenOnStore(prefs).write(s.keepScreenOn)
        ReaderVolumeKeyPagingStore(prefs).write(s.volumeKeys)
        ReaderRtlPagingStore(prefs).write(s.rtl)
        LibraryLayoutStore(prefs).write(if (s.libraryLayout == LibraryViewMode.LIST.name) LibraryViewMode.LIST else LibraryViewMode.GRID)
        EhentaiEnableStore(prefs).setEnabled(s.ehentaiBrowse)
        NhentaiEnableStore(prefs).setEnabled(s.nhentaiBrowse)
        com.easemic.app.feature.providers.JmEnableStore(prefs).setEnabled(s.jmBrowse)
        if (s.cacheMb > 0) CacheBudgetStore(prefs).writeMb(s.cacheMb)
        CloudReleaseAfterUploadStore(prefs).write(s.keepOnlyCloudCopy)
        if (s.maxTransfers > 0) MaxConcurrentTransfersStore(prefs).write(s.maxTransfers)
        val catalogs = OpdsProviderStore(prefs)
        val existing = catalogs.readAll()
        val known = existing.map { it.catalogUrl.trimEnd('/') }.toSet()
        val fresh = state.opdsCatalogs
            .filter { it.url.isNotBlank() && it.url.trimEnd('/') !in known }
            .map { OpdsProviderConfig(id = "opds-${UUID.randomUUID()}", displayName = it.name, catalogUrl = it.url) }
        if (fresh.isNotEmpty()) catalogs.writeAll(existing + fresh)
        return added
    }

    /** Both lists are the store's own JSON, newest first; the store's read() collapses one row per gallery. */
    private fun mergeHistory(incomingJson: String) {
        val store = BrowseHistoryStore(prefs)
        val mine = runCatching { JSONArray(store.exportRaw()) }.getOrDefault(JSONArray())
        val theirs = runCatching { JSONArray(incomingJson) }.getOrDefault(JSONArray())
        if (theirs.length() == 0) return
        val rows = (0 until mine.length()).map { mine.getJSONObject(it) } + (0 until theirs.length()).map { theirs.getJSONObject(it) }
        val merged = JSONArray(rows.sortedByDescending { it.optLong("visitedAt", 0L) })
        store.importRaw(merged.toString())
    }

    private suspend fun applyProgress(state: ArchiveState): Int {
        val targets = progressTargets()
        val byKey = targets.filter { it.key.isNotEmpty() }.associateBy { it.key }
        val byTitle = targets.groupBy { it.title to it.pageCount }
            .filterValues { it.size == 1 }
            .mapValues { it.value.single() }
        var applied = 0
        for (saved in state.progress) {
            if (saved.ordinal <= 0) continue
            val target = (if (saved.key.isNotEmpty()) byKey[saved.key] else byTitle[saved.title to saved.pageCount])
                ?: continue
            if (target.currentProgressOrdinal > 0) continue
            setProgress(target.readingUnitId, saved.ordinal)
            applied++
        }
        return applied
    }

    private fun identityOf(comic: ArchiveComic) =
        GalleryIdentityV1(providerNamespace = comic.providerNamespace, canonicalItemId = comic.canonicalItemId)

    private fun isFollowed(series: ArchiveSeries, state: ArchiveState): Boolean =
        (bookmarksIn(state) + CollectBookmarkStore(prefs).readAll()).any { bookmark ->
            isSerializedBookmark(bookmark) &&
                bookmark.providerId == series.providerId &&
                bookmark.id == series.providerSeriesId
        }

    companion object {
        const val STAGING_DIR = "archive-restore-staging"
        private const val RESTORED_AT_KEY = "archive_restored_at_ms"
        /** A restored comic is revision 1 of its download tree, as a first download would be. */
        const val RESTORED_REVISION = 1
        private const val SPACE_MARGIN_BYTES = 200L * 1024 * 1024
    }
}
