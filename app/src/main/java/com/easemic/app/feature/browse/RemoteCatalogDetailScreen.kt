package com.easemic.app.feature.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.easemic.app.feature.download.DetailDownloadControl
import com.easemic.app.i18n.AppStrings
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.SerializedDownloadChapterRequest
import com.easemic.app.feature.download.DetailDownloadPrimaryAction
import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.SourceDelistedCopy
import com.easemic.app.feature.download.downloadJobOwnsLocalPages
import com.easemic.app.feature.download.isCatalogDownloadRow
import com.easemic.app.feature.detail.DetailAccessLostBlock
import com.easemic.app.feature.detail.DetailControls
import com.easemic.app.feature.detail.DetailFilesGoneBlock
import com.easemic.app.feature.detail.DetailImage
import com.easemic.app.feature.detail.DetailIncompleteBlock
import com.easemic.app.feature.detail.DetailSubject
import com.easemic.app.feature.detail.DetailTruncatedSealBlock
import com.easemic.app.feature.detail.localComicDetail
import com.easemic.app.feature.detail.model.DetailCount
import com.easemic.app.feature.detail.ui.DetailActionRow
import com.easemic.app.feature.detail.ui.DetailChapters
import com.easemic.app.feature.detail.ui.DetailHelperSheet
import com.easemic.app.feature.detail.ui.DownloadChapterSheet
import com.easemic.app.feature.detail.ui.DetailNotices
import com.easemic.app.feature.detail.ui.DetailPages
import com.easemic.app.feature.detail.ui.DetailPreviewGrid
import com.easemic.app.feature.detail.ui.DetailSkeleton
import com.easemic.app.feature.detail.ui.DetailTopBar
import com.easemic.app.feature.detail.ui.SerializedChapterGridLoading
import com.easemic.app.feature.library.ChapterShelfRow
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.ComicSource
import com.easemic.app.feature.source.ComicSourceRegistry
import com.easemic.app.feature.source.DetailField
import com.easemic.app.feature.source.SeriesCountInput
import com.easemic.app.feature.source.SeriesDetailMetadata
import com.easemic.app.feature.source.SeriesDetailSnapshot
import com.easemic.app.feature.source.SourceDetailRegistry
import com.easemic.app.feature.source.over
import com.easemic.app.feature.source.valueOrNull
import com.easemic.app.storage.buildPageImageRequest
import com.easemic.app.storage.isReadableCacheUri
import com.easemic.app.ui.components.BrokenImageGlyph
import com.easemic.app.ui.SharedPreferencesMap
import com.easemic.app.ui.theme.QuietShelfPalette
import com.easemic.app.ui.theme.quietShelfPalette

/** Confirmation shown before a collected serialized series queues more than 50 chapters. */
@Composable
fun SerializedCollectConfirmationDialog(
    chapterCount: Int,
    estimatedBytes: Long?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Collect $chapterCount chapters?") },
        text = {
            Column {
                Text("All $chapterCount readable chapters will be saved to this phone.")
                estimatedBytes?.let { Text("About ${formatSerializedBytes(it)}") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("serialized-collect-confirm"),
            ) {
                Text("Collect all")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag("serialized-collect-cancel"),
            ) {
                Text("Cancel")
            }
        },
    )
}

private fun formatSerializedBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    return when {
        safeBytes < 1_024L -> "$safeBytes B"
        safeBytes < 1_024L * 1_024L -> "${safeBytes / 1_024L} KB"
        safeBytes < 1_024L * 1_024L * 1_024L -> "${safeBytes / (1_024L * 1_024L)} MB"
        else -> "${safeBytes / (1_024L * 1_024L * 1_024L)} GB"
    }
}

internal fun serializedDeleteSavedChaptersMessage(cloudCopyExists: Boolean): String =
    if (cloudCopyExists) {
        "Removes pages from this device. Bookmarks and cloud copy stay."
    } else {
        "The saved pages of every chapter in this series will be deleted from this phone. " +
            "You will keep following it; new chapters still download. To stop following, use Uncollect."
    }

/**
 * The Part 2 adapters live behind [SourceDetailRegistry], but this screen must
 * not resolve sources (no fetching, no app graph): display names reach it as
 * today's `serializedSourceName`, and Part 5's loader swaps the producer. An
 * empty lookup therefore degrades namespace resolution to the raw provider id,
 * which is exactly what every source's own adapter entry is keyed on.
 */
private object DetailOnlySourceRegistry : ComicSourceRegistry {
    override fun source(namespace: String): ComicSource? = null
    override fun all(): List<ComicSource> = emptyList()
}

/**
 * A32 §1 — the single entry point both Detail routes use. A catalog row
 * ([DetailSubject.Remote]) renders exactly what it always did; an imported
 * folder or a finished catalog download ([DetailSubject.Local]) renders
 * through the same `DetailSkeleton`, which is the whole point: the owner sees
 * one product, not two.
 */
@Composable
fun ComicDetailScreen(
    subject: DetailSubject,
    /** See the same parameter on [RemoteCatalogDetailScreen] (E92 Part 4 E2). */
    detailRegistry: com.easemic.app.feature.source.SourceDetailRegistry? = null,
    busy: Boolean = false,
    metadataLoading: Boolean = false,
    statusMessage: String? = null,
    presence: ResourcePresence = ResourcePresence.REMOTE,
    cloudBound: Boolean = false,
    cloudQueued: Boolean = false,
    sourceFamily: DetailSourceFamily = DetailSourceFamily.FRONT_INDEX,
    onBack: () -> Unit,
    onCollect: () -> Unit = {},
    onRead: (() -> Unit)? = null,
    onReadOnline: () -> Unit = {},
    onChapterRead: (String) -> Unit = {},
    onUncollect: () -> Unit = {},
    onPreviewPageClick: (pageIndexZeroBased: Int) -> Unit = {},
    onPreviewWarmThrough: (endExclusive: Int) -> Unit = {},
    onTagClick: ((String) -> Unit)? = null,
    localDownloadAvailable: Boolean = true,
    localDownloadState: DownloadJobState? = null,
    /**
     * A36 review M2: whether that job's pages are on THIS phone
     * ([downloadJobOwnsLocalPages]) — a truncated seal owns a readable
     * INCOMPLETE row, and `state == FINISH` alone cannot see it. Defaults to
     * what the state proves on its own, so a caller that does not know the
     * job's intent gets exactly the old behaviour.
     */
    localDownloadOwnsLocalPages: Boolean = downloadJobOwnsLocalPages(localDownloadState),
    /** C9: `DownloadJob.isDelistedAtSource()` on the live job; see [RemoteCatalogDetailScreen]. */
    localDownloadDelistedAtSource: Boolean = localDownloadState == DownloadJobState.GONE,
    localDownloadUnavailableReason: String? = null,
    onLocalDownload: () -> Unit = {},
    onLocalDownloadCancel: () -> Unit = {},
    onLocalDownloadRetry: () -> Unit = {},
    onLocalDownloadDelete: () -> Unit = {},
    /** #C60: the Remote subject's Read label (`detailReadLabel`); "Read" when the caller has no progress. */
    readLabel: String = "Read",
    /** Local only: open the reader at this 0-based page. */
    onReadFrom: (startOrdinal: Int) -> Unit = {},
    /**
     * Local only: the one destination the access-lost and incomplete blocks point at —
     * the Folders screen (A30 §2.2). There is no second recovery path; a SAF re-pick is
     * something the user starts there, not from here.
     */
    onOpenSources: () -> Unit = {},
    /**
     * Local only: null ⇒ no ··· at all. An imported folder is never deletable —
     * the app did not create those files and must not offer to remove them.
     */
    onDeleteDownload: (() -> Unit)? = null,
    /**
     * Local only (J1): whether the route resolved a real collect target for this
     * row. False ⇒ no Collect switch, because a switch that writes a bookmark no
     * other screen can match is worse than no switch.
     */
    collectTargetResolved: Boolean = true,
    /** Known before chapter metadata loads; serialized detail shows Continue + (#E73) Download + Collect. */
    serializedDetail: Boolean = false,
    /** Serialized sources pass their fetched chapter list; gallery detail leaves this null. */
    serializedChapters: ChapterListResult? = null,
    /**
     * #E21 tail 3: one line shown in place of the chapter list while a cloud
     * series' summary has never hydrated; null keeps the list-only rendering.
     */
    serializedEmptyLine: String? = null,
    /** Persisted local chapter rows; empty keeps uncollected detail source-only. */
    serializedChapterShelfRows: List<ChapterShelfRow> = emptyList(),
    /** Live per-chapter jobs for the serialized series. */
    serializedChapterJobs: List<DownloadJob> = emptyList(),
    /** Hydrated format-2 chapters; these are Saved even when no local row exists. */
    serializedCloudChapterRows: List<CloudChapterRow> = emptyList(),
    /** Local chapters take precedence over the online callback when supplied. */
    onChapterLocalRead: ((ChapterRowPresentation) -> Unit)? = null,
    /** Deletes every saved chapter for this serialized series while keeping its follow. */
    onDeleteSavedSerializedChapters: (() -> Unit)? = null,
    /** Retries only the failed chapter job whose badge was tapped. */
    onRetryChapter: (String) -> Unit = {},
    /** #E103: chapter ids whose cloud upload rows are stranded (retry budget spent). */
    serializedCloudStrandedChapterIds: Set<String> = emptySet(),
    onRetryCloudChapter: (String) -> Unit = {},
    /**
     * #E73: the download picker's enqueue. Non-null gives the serialized detail
     * its Download switch (opens [DownloadChapterSheet]); null passes no
     * Download switch — never an inert one.
     */
    onDownloadChapters: ((List<SerializedDownloadChapterRequest>) -> Unit)? = null,
    /** Latest persisted per-chapter reading progress; drives the sheet's Unread preset (spec §3). */
    serializedRemoteProgress: Map<String, RemoteReadingProgress> = emptyMap(),
    /** Pending transfers for the resolved provider series, computed by the app root. */
    serializedTransferPending: Boolean = false,
    /** Structured serialized-series facts retained across source, local and cloud detail. */
    serializedSnapshot: SeriesDetailSnapshot? = null,
    serializedSourceName: String? = null,
    serializedContinue: SerializedContinueText? = null,
    serializedIdentity: SeriesIdentityV2? = null,
    serializedOrderStore: SerializedChapterOrderStore? = null,
    keepOnlyCloud: Boolean = false,
    onChapterReadOnline: (String) -> Unit = {},
    onDeleteSavedChapter: (String) -> Unit = {},
    onAuthorClick: ((String) -> Unit)? = null,
    zaimanhuaSignedIn: Boolean = false,
    onZaimanhuaSignIn: () -> Unit = {},
) {
    when (subject) {
        is DetailSubject.Remote -> RemoteCatalogDetailScreen(
            item = subject.item,
            detailRegistry = detailRegistry,
            busy = busy,
            metadataLoading = metadataLoading,
            statusMessage = statusMessage,
            presence = presence,
            cloudBound = cloudBound,
            cloudQueued = cloudQueued,
            sourceFamily = sourceFamily,
            onBack = onBack,
            onCollect = onCollect,
            onRead = onRead,
            onReadOnline = onReadOnline,
            onChapterRead = onChapterRead,
            onUncollect = onUncollect,
            onPreviewPageClick = onPreviewPageClick,
            onPreviewWarmThrough = onPreviewWarmThrough,
            onTagClick = onTagClick,
            localDownloadAvailable = localDownloadAvailable,
            localDownloadState = localDownloadState,
            localDownloadOwnsLocalPages = localDownloadOwnsLocalPages,
            localDownloadDelistedAtSource = localDownloadDelistedAtSource,
            localDownloadUnavailableReason = localDownloadUnavailableReason,
            onLocalDownload = onLocalDownload,
            onLocalDownloadCancel = onLocalDownloadCancel,
            onLocalDownloadRetry = onLocalDownloadRetry,
            onLocalDownloadDelete = onLocalDownloadDelete,
            readLabel = readLabel,
            serializedDetail = serializedDetail,
            serializedChapters = serializedChapters,
            serializedEmptyLine = serializedEmptyLine,
            serializedChapterShelfRows = serializedChapterShelfRows,
            serializedChapterJobs = serializedChapterJobs,
            serializedCloudChapterRows = serializedCloudChapterRows,
            serializedTransferPending = serializedTransferPending,
            serializedSnapshot = serializedSnapshot,
            serializedSourceName = serializedSourceName,
            serializedContinue = serializedContinue,
            serializedIdentity = serializedIdentity,
            serializedOrderStore = serializedOrderStore,
            keepOnlyCloud = keepOnlyCloud,
            onChapterReadOnline = onChapterReadOnline,
            onDeleteSavedChapter = onDeleteSavedChapter,
            onAuthorClick = onAuthorClick,
            onChapterLocalRead = onChapterLocalRead,
            onDeleteSavedSerializedChapters = onDeleteSavedSerializedChapters,
            onRetryChapter = onRetryChapter,
            serializedCloudStrandedChapterIds = serializedCloudStrandedChapterIds,
            onRetryCloudChapter = onRetryCloudChapter,
            onDownloadChapters = onDownloadChapters,
            serializedRemoteProgress = serializedRemoteProgress,
            zaimanhuaSignedIn = zaimanhuaSignedIn,
            onZaimanhuaSignIn = onZaimanhuaSignIn,
        )
        is DetailSubject.Local -> LocalDetailBody(
            subject = subject,
            detailRegistry = detailRegistry,
            statusMessage = statusMessage,
            cloudBound = cloudBound,
            cloudQueued = cloudQueued,
            onBack = onBack,
            onReadFrom = onReadFrom,
            onPreviewPageClick = onPreviewPageClick,
            onOpenFolders = onOpenSources,
            onCollect = onCollect,
            onUncollect = onUncollect,
            onDeleteDownload = onDeleteDownload,
            onTagClick = onTagClick,
            collectTargetResolved = collectTargetResolved,
        )
    }
}

/**
 * Remote catalog detail (reverse-proxy actions, spec 2026-09-03): one verb (Read) plus two
 * state switches — collect and download. Read routes per Task D4; here it prefers the
 * unified [onRead] callback when provided, else falls back to [onReadOnline]. Collected /
 * downloaded / in-flight switch taps open a [DetailActionConfirmDialog]; confirming
 * triggers the matching cancel/delete callback. Opening this screen does not write
 * Library by itself. The download switch must not invoke Collect / entitlement /
 * cloud mutation.
 */
@Composable
fun RemoteCatalogDetailScreen(
    item: BrowseFeedItem,
    busy: Boolean = false,
    metadataLoading: Boolean = false,
    statusMessage: String? = null,
    presence: ResourcePresence = ResourcePresence.REMOTE,
    /** Whether an R2 bucket is bound (`productObjectStore()` ready) — flips collect icon heart↔cloud. */
    cloudBound: Boolean = false,
    /** Whether this acquisition has an in-flight (WAITING/FETCHING/UPLOADING) row in the cloud sync queue. */
    cloudQueued: Boolean = false,
    sourceFamily: DetailSourceFamily = DetailSourceFamily.FRONT_INDEX,
    onBack: () -> Unit,
    onCollect: () -> Unit,
    onRead: (() -> Unit)? = null,
    onReadOnline: () -> Unit = {},
    onChapterRead: (String) -> Unit = {},
    onUncollect: () -> Unit = {},
    onPreviewPageClick: (pageIndexZeroBased: Int) -> Unit = {},
    onPreviewWarmThrough: (endExclusive: Int) -> Unit = {},
    /** Tag chip tap. Receives the full raw tag string (e.g. "parody:alpha-tag"). */
    onTagClick: ((String) -> Unit)? = null,
    /** When false, local Download is unavailable (e.g. unresolvable Cloud card). */
    localDownloadAvailable: Boolean = true,
    localDownloadState: DownloadJobState? = null,
    /** A36 review M2: see the same parameter on [ComicDetailScreen]. */
    localDownloadOwnsLocalPages: Boolean = downloadJobOwnsLocalPages(localDownloadState),
    /**
     * C9: the delisted verdict — a zero-page GONE seal OR a truncated seal
     * under the GONE verdict. The default reproduces the pre-C9 reading (GONE
     * only) so every existing caller/test is unchanged; production passes the
     * predicate.
     */
    localDownloadDelistedAtSource: Boolean = localDownloadState == DownloadJobState.GONE,
    localDownloadUnavailableReason: String? = null,
    onLocalDownload: () -> Unit = {},
    onLocalDownloadCancel: () -> Unit = {},
    onLocalDownloadRetry: () -> Unit = {},
    onLocalDownloadDelete: () -> Unit = {},
    /** #C60: see [ComicDetailScreen]; the Read button's text. */
    readLabel: String = "Read",
    /** Known before chapter metadata loads; serialized detail shows Continue + (#E73) Download + Collect. */
    serializedDetail: Boolean = false,
    /** Serialized sources pass their fetched chapter list; gallery detail leaves this null. */
    serializedChapters: ChapterListResult? = null,
    /** #E21 tail 3: one line in place of the chapter list while the series summary never hydrated. */
    serializedEmptyLine: String? = null,
    /** Persisted local chapter rows; empty keeps uncollected detail source-only. */
    serializedChapterShelfRows: List<ChapterShelfRow> = emptyList(),
    /** Live per-chapter jobs for the serialized series. */
    serializedChapterJobs: List<DownloadJob> = emptyList(),
    /** Hydrated format-2 chapters; these are Saved even when no local row exists. */
    serializedCloudChapterRows: List<CloudChapterRow> = emptyList(),
    /** Local chapters take precedence over the online callback when supplied. */
    onChapterLocalRead: ((ChapterRowPresentation) -> Unit)? = null,
    /** Deletes every saved chapter for this serialized series while keeping its follow. */
    onDeleteSavedSerializedChapters: (() -> Unit)? = null,
    /** Retries only the failed chapter job whose badge was tapped. */
    onRetryChapter: (String) -> Unit = {},
    /**
     * #E103: chapter ids of this series whose cloud upload rows are stranded
     * (retry budget spent). Their cells say "Cloud copy failed · tap to retry"
     * and [onRetryCloudChapter] revives the row.
     */
    serializedCloudStrandedChapterIds: Set<String> = emptySet(),
    onRetryCloudChapter: (String) -> Unit = {},
    /**
     * #E73: the download picker's enqueue (spec §4). Non-null gives the
     * serialized detail its Download switch — tap opens [DownloadChapterSheet];
     * null passes no Download switch, never an inert one.
     */
    onDownloadChapters: ((List<SerializedDownloadChapterRequest>) -> Unit)? = null,
    /** Latest persisted per-chapter reading progress; drives the sheet's Unread preset (spec §3). */
    serializedRemoteProgress: Map<String, RemoteReadingProgress> = emptyMap(),
    /** Pending transfers for the resolved provider series, computed by the app root. */
    serializedTransferPending: Boolean = false,
    /** Structured serialized-series facts retained across source, local and cloud detail. */
    serializedSnapshot: SeriesDetailSnapshot? = null,
    serializedSourceName: String? = null,
    /**
     * #E92 Part 4 E2: the registry the detail table resolves source display
     * names and profiles through (the app root passes
     * `AppGraph.sourceDetailRegistry`). Null falls back to an empty lookup for
     * standalone mounts — §2.1.2's facts line then simply omits the source part,
     * and a provider id is never shown as a name.
     */
    detailRegistry: com.easemic.app.feature.source.SourceDetailRegistry? = null,
    serializedContinue: SerializedContinueText? = null,
    serializedIdentity: SeriesIdentityV2? = null,
    serializedOrderStore: SerializedChapterOrderStore? = null,
    keepOnlyCloud: Boolean = false,
    onChapterReadOnline: (String) -> Unit = {},
    onDeleteSavedChapter: (String) -> Unit = {},
    onAuthorClick: ((String) -> Unit)? = null,
    zaimanhuaSignedIn: Boolean = false,
    onZaimanhuaSignIn: () -> Unit = {},
) {
    val helper = if (serializedDetail) {
        RemoteDetailHelperCopy.serialized(presence, cloudBound, keepOnlyCloud)
    } else DetailActionMatrix.present(
        DetailActionFacts(presence = presence, sourceFamily = sourceFamily),
    ).helper
    val switchState = DetailSwitchState.from(
        DetailSwitchFacts(
            cloudBound = cloudBound,
            collected = presence == ResourcePresence.BOOKMARKED ||
                presence == ResourcePresence.CLOUD,
            uploaded = presence == ResourcePresence.CLOUD && (!serializedDetail || !serializedTransferPending),
            downloadState = localDownloadState,
            cloudQueued = cloudQueued,
            delistedAtSource = localDownloadDelistedAtSource,
            downloadOwnsLocalPages = localDownloadOwnsLocalPages,
        ),
    )
    val resolvedSerializedIdentity = serializedIdentity ?: seriesIdentityFor(item)
    val preferenceContext = LocalContext.current
    val serializedOrder = serializedOrderStore ?: remember(resolvedSerializedIdentity) {
        SerializedChapterOrderStore(SharedPreferencesMap.from(preferenceContext))
    }
    val serializedGroups = serializedChapters?.let {
        serializedChapterGroups(
            it,
            serializedChapterShelfRows,
            serializedChapterJobs,
            serializedCloudChapterRows,
            signedIn = zaimanhuaSignedIn,
            cloudStrandedChapterIds = serializedCloudStrandedChapterIds,
        )
    }
    val savedIds = buildSet {
        serializedChapterShelfRows.filter { it.pageCount > 0 }.forEach { add(it.sourceChapterId) }
        serializedCloudChapterRows.filter { it.pageCount > 0 }.forEach { add(it.chapterId) }
    }
    val removedIds = buildSet {
        serializedChapterShelfRows.filter { it.sourceState == "SOURCE_REMOVED" }.forEach { add(it.sourceChapterId) }
        serializedCloudChapterRows.filter { it.sourceState == "SOURCE_REMOVED" }.forEach { add(it.chapterId) }
    }
    val serializedFacts = if (serializedDetail) serializedDetailFacts(
        sourceName = serializedSourceName ?: item.providerId,
        snapshot = serializedSnapshot,
        source = serializedChapters,
        sourceUnavailable = serializedChapters == null,
        savedIds = savedIds,
        removedIds = removedIds,
        zone = java.time.ZoneId.systemDefault(),
        locale = java.util.Locale.ENGLISH,
    ) else null
    val previewSlotCount = if (serializedDetail) 0 else maxOf(item.pageCount ?: 0, item.previewUrls.size)
    val downloadUi = DetailDownloadControl.present(
        available = localDownloadAvailable,
        state = localDownloadState,
        unavailableReason = localDownloadUnavailableReason,
        ownsLocalPages = localDownloadOwnsLocalPages,
    )
    // C9: with nothing on the device and no cloud copy, the only Read route is
    // the source that just said no — greying Read (and the preview taps) is the
    // honest rendering. A local or cloud copy keeps reading.
    val readBlocked = localDownloadDelistedAtSource && switchState.readRoute == ReadRoute.ONLINE
    // C9 audit H1: the inert, dimmed key is the zero-page GONE case only. A
    // truncated seal keeps a live key even with nothing on the shelf — a tap
    // may adopt pages still on disk (A36-O1) or re-check a source that came
    // back.
    val downloadDelisted = localDownloadState == DownloadJobState.GONE
    // C9 audit H1: the GONE arm already supplies the statement; a greyed Read
    // must never stand unexplained, so it also appears whenever Read is
    // blocked (e.g. a truncated seal without local pages).
    val delistedHint = downloadUi.statusHint ?: SourceDelistedCopy.SENTENCE.takeIf { readBlocked }
    var confirmKind by remember { mutableStateOf<DetailActionConfirmKind?>(null) }
    var confirmDeleteSavedSerialized by remember { mutableStateOf(false) }
    var confirmDeleteChapterId by remember { mutableStateOf<String?>(null) }
    var signInChapterId by remember { mutableStateOf<String?>(null) }
    val hasSavedSerializedChapters = serializedChapterShelfRows.any { it.pageCount > 0 }
    val palette = quietShelfPalette()
    // DetailReadLabelTest pins "primaryLabel = readLabel": the pill label is the
    // caller's readLabel, never a hardcoded "Read". A serialized Continue row
    // additionally carries its own label (today's SerializedContinueLabel text)
    // in [serializedContinue].
    val primaryLabel = readLabel
    val readButtonLabel = serializedContinue?.text ?: primaryLabel
    val metadataActions = SerializedDetailMetadataActions(
        onTag = if (sourceFamily != DetailSourceFamily.OPDS) onTagClick else null,
        onAuthor = if (sourceFamily != DetailSourceFamily.OPDS) onAuthorClick else null,
    )
    // Part 2 adapters at the call site (the loader is Part 5): the one detail
    // table is built from today's inputs — serialized through
    // SeriesComicDetailAdapter with the fetched snapshot + saved ids, gallery
    // through the registry's feed()/gallery-adapter path with the feed item.
    // The registry's source lookup stays empty: display names reach this screen
    // exactly as today, through serializedSourceName (Part 5 swaps the producer).
    val resolvedDetailRegistry = detailRegistry ?: remember { SourceDetailRegistry(DetailOnlySourceRegistry) }
    val seriesSeed = (item.seriesMetadata ?: SeriesDetailMetadata()).over(
        SeriesDetailMetadata(
            title = DetailField.Value(item.title),
            cover = item.coverUrl?.let { DetailField.Value(it) } ?: DetailField.Omitted,
        ),
    )
    val detail = if (serializedDetail) {
        resolvedDetailRegistry.series(
            namespace = item.providerId,
            snapshot = serializedSnapshot,
            seed = seriesSeed,
            counts = SeriesCountInput(
                loadedChapterIds = serializedChapters?.chapters
                    ?.map { it.identity.canonicalChapterId }?.toSet().orEmpty(),
                savedChapterIds = savedIds,
                sourceUnavailable = serializedChapters == null,
            ),
            storedSourceName = serializedSourceName ?: item.providerId,
        )
    } else {
        resolvedDetailRegistry.feed(item)
    }
    val profile = resolvedDetailRegistry.profiles.profile(item.providerId)
    val memoryKey = if (serializedDetail) {
        "${resolvedSerializedIdentity.providerNamespace}:${resolvedSerializedIdentity.canonicalSeriesId}"
    } else {
        "${item.providerId}:${item.id}"
    }
    // §2.1.4: the helper link shows while a Collect or Download switch shows —
    // every state this screen renders shows one — and names the switches it has.
    val helperLink = if (serializedDetail) AppStrings.whatCollectDoes else AppStrings.whatCollectAndDownloadDo
    var helperSheetOpen by remember { mutableStateOf(false) }
    // #17 v2 (owner design): the preview box scrolls INTERNALLY, so the sections
    // above never leave the viewport while the owner browses previews; the outer
    // verticalScroll stays for the case the box cannot absorb (DetailSkin's
    // wiring kept identical around DetailSkeleton).
    val scrollState = rememberScrollState()
    var viewportTopPx by remember { mutableIntStateOf(0) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val coverImage = item.coverUrl
        ?.takeIf { it.isNotBlank() && it != CloudDetailPreviewProgress.MISSING_URI }
        ?.let { DetailImage.Url(it) }
    val previewImages = item.previewUrls.map { url ->
        when {
            url == CloudDetailPreviewProgress.MISSING_URI -> DetailImage.Missing
            url.isNotBlank() && isReadableCacheUri(url) -> DetailImage.Url(url)
            else -> null
        }
    }
    val primaryRead: () -> Unit = {
        // The chapter Read leads to needs a sign-in: ask, exactly as tapping that chapter does.
        val signInFirst = serializedContinue?.signInChapterId
        if (signInFirst != null) {
            signInChapterId = signInFirst
        } else {
            onRead?.invoke() ?: onReadOnline()
        }
    }
    // #E73 spec §3/§5: the picker's inputs — which chapters have local or
    // remote reading progress (Unread), and where Continue would land (Next-K's
    // anchor, the same `serializedDetailReadButtonTarget` the pill uses).
    val serializedProgressIds = if (serializedDetail) {
        downloadPickerProgressIds(
            rows = serializedGroups.orEmpty().flatMap { it.rows },
            shelfRows = serializedChapterShelfRows,
            remoteProgress = serializedRemoteProgress,
            series = resolvedSerializedIdentity,
        )
    } else {
        emptySet()
    }
    val serializedContinueTarget = if (serializedDetail) {
        serializedDetailReadButtonTarget(
            series = resolvedSerializedIdentity,
            result = serializedChapters,
            shelfRows = serializedChapterShelfRows,
            jobs = serializedChapterJobs,
            cloudRows = serializedCloudChapterRows,
            remoteProgress = serializedRemoteProgress,
            signedIn = zaimanhuaSignedIn,
        )?.chapterId
    } else {
        null
    }
    var downloadSheetOpen by remember { mutableStateOf(false) }
    val switches: @Composable () -> Unit = {
        val onDownloadClick: () -> Unit = {
            when (switchState.downloadIcon) {
                DownloadIcon.HOLLOW -> {
                    when (downloadUi.primaryAction) {
                        DetailDownloadPrimaryAction.ENQUEUE -> onLocalDownload()
                        DetailDownloadPrimaryAction.RETRY -> onLocalDownloadRetry()
                        DetailDownloadPrimaryAction.CONFIRM_DELETE ->
                            confirmKind = DetailActionConfirmKind.DELETE_DOWNLOAD
                        DetailDownloadPrimaryAction.NONE,
                        DetailDownloadPrimaryAction.UNAVAILABLE,
                        -> Unit
                    }
                }
                DownloadIcon.IN_FLIGHT ->
                    confirmKind = DetailActionConfirmKind.CANCEL_DOWNLOAD
                DownloadIcon.SOLID ->
                    confirmKind = DetailActionConfirmKind.DELETE_DOWNLOAD
            }
        }
        val onCollectClick: () -> Unit = {
            when (presence) {
                ResourcePresence.BOOKMARKED, ResourcePresence.CLOUD ->
                    confirmKind = DetailActionConfirmKind.CANCEL_COLLECTION
                ResourcePresence.REMOTE, ResourcePresence.TOMBSTONED -> onCollect()
            }
        }
        if (serializedDetail) {
            // #E73 spec §2: Download LEFT of Collect. The serialized switch is
            // the picker's entry — hollow, busy (ring) while any of the series'
            // chapters downloads — and it never cancels or deletes, so there is
            // no confirm dialog on this path.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onDownloadChapters != null) {
                    DownloadSwitch(
                        icon = if (serializedChapterJobs.any {
                                it.state == DownloadJobState.WAIT || it.state == DownloadJobState.DOWNLOAD
                            }
                        ) {
                            DownloadIcon.IN_FLIGHT
                        } else {
                            DownloadIcon.HOLLOW
                        },
                        onClick = { downloadSheetOpen = true },
                        tag = "serialized-download",
                    )
                }
                CollectSwitch(icon = switchState.collectIcon, onClick = onCollectClick)
            }
        } else {
            DetailActionSwitches(
                collectIcon = switchState.collectIcon,
                downloadIcon = switchState.downloadIcon,
                onCollectClick = onCollectClick,
                onDownloadClick = onDownloadClick,
                downloadDelisted = downloadDelisted,
                downloadLocked = switchState.downloadLocked,
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                viewportTopPx = coords.positionInWindow().y.roundToInt()
                viewportHeightPx = coords.size.height
            }
            .verticalScroll(scrollState)
            .testTag("remote-catalog-detail"),
    ) {
        DetailSkeleton(
            detail = detail,
            profile = profile,
            palette = palette,
            memoryKey = memoryKey,
            cover = {
                DetailCoverFrame(image = coverImage, contentDescription = item.title, palette = palette)
            },
            topBar = {
                DetailTopBar(
                    palette = palette,
                    onBack = onBack,
                    moreActions = if (hasSavedSerializedChapters && onDeleteSavedSerializedChapters != null) {
                        {
                            TextButton(
                                onClick = { confirmDeleteSavedSerialized = true },
                                modifier = Modifier.testTag("delete-saved-serialized-chapters"),
                            ) {
                                Text("Delete saved chapters")
                            }
                        }
                    } else {
                        null
                    },
                )
            },
            actions = {
                DetailActionRow(
                    palette = palette,
                    readLabel = readButtonLabel,
                    readEnabled = !readBlocked,
                    onRead = primaryRead,
                    switches = switches,
                    serializedCrowded = serializedContinue != null,
                )
            },
            notices = {
                DetailNotices(
                    palette = palette,
                    delistedSentence = delistedHint,
                    statusMessage = statusMessage,
                    removedChaptersLine = serializedFacts?.removedLine,
                    helperLink = helperLink,
                    onHelperClick = { helperSheetOpen = true },
                )
            },
            peopleClickable = if (serializedDetail) {
                metadataActions.onAuthor != null
            } else {
                metadataActions.onTag != null
            },
            onPersonClick = { person ->
                if (serializedDetail) metadataActions.onAuthor?.invoke(person.name)
                else metadataActions.onTag?.invoke(person.rawSearchValue)
            },
            tagsClickable = metadataActions.onTag != null,
            onTagClick = { tag -> metadataActions.onTag?.invoke(tag.rawSourceValue) },
            content = {
                if (serializedEmptyLine != null) {
                    Text(
                        serializedEmptyLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.muted,
                        modifier = Modifier.testTag("serialized-series-info-missing"),
                    )
                }
                if (serializedDetail && metadataLoading && serializedGroups.orEmpty().all { it.rows.isEmpty() } &&
                    serializedEmptyLine == null
                ) {
                    SerializedChapterGridLoading(chapterCount = serializedSnapshot?.sourceChapterCount)
                } else {
                    serializedGroups?.let { groups ->
                        if (serializedDetail) {
                            DetailChapters(
                                series = resolvedSerializedIdentity,
                                groups = groups,
                                orders = serializedOrder,
                                onRead = { row ->
                                    if (row.localReadable && onChapterLocalRead != null) onChapterLocalRead(row)
                                    else onChapterRead(row.chapterId)
                                },
                                onReadOnline = onChapterReadOnline,
                                onRetry = onRetryChapter,
                                onDeleteSavedCopy = onDeleteSavedChapter,
                                onSignIn = { chapterId -> signInChapterId = chapterId },
                                onSignInHint = onZaimanhuaSignIn,
                                chapterCount = serializedSnapshot?.sourceChapterCount,
                                onRetryCloudUpload = onRetryCloudChapter,
                            )
                        }
                    }
                }
                if (previewSlotCount > 0) {
                    DetailPages(
                        count = (detail.count.valueOrNull() as? DetailCount.Pages)?.total ?: previewSlotCount,
                        previewImages = previewImages,
                        slotCount = previewSlotCount,
                        scrollState = scrollState,
                        viewportTopPx = viewportTopPx,
                        viewportHeightPx = viewportHeightPx,
                        interactive = !readBlocked,
                        onPreviewPageClick = onPreviewPageClick,
                        onPreviewWarmThrough = onPreviewWarmThrough,
                    )
                }
            },
            tagsLoading = metadataLoading,
        )
    }
    if (helperSheetOpen) {
        DetailHelperSheet(palette = palette, body = helper, onDismiss = { helperSheetOpen = false })
    }
    if (downloadSheetOpen && onDownloadChapters != null && serializedGroups != null) {
        DownloadChapterSheet(
            palette = palette,
            groups = serializedGroups,
            progressChapterIds = serializedProgressIds,
            continueChapterId = serializedContinueTarget,
            onConfirm = { ids ->
                downloadSheetOpen = false
                val rowById = serializedGroups.flatMap { it.rows }.associateBy(ChapterRowPresentation::chapterId)
                onDownloadChapters(
                    ids.mapNotNull { id -> rowById[id]?.let { SerializedDownloadChapterRequest(id, it.label) } },
                )
            },
            onDismiss = { downloadSheetOpen = false },
        )
    }
    confirmKind?.let { kind ->
        DetailActionConfirmDialog(
            title = kind.title,
            // F4: only the un-collect line is cloud-copy conditional; keep the
            // other kinds' fixed copy.
            message = if (kind == DetailActionConfirmKind.CANCEL_COLLECTION) {
                cancelCollectionConfirmMessage(cloudBound)
            } else {
                kind.message
            },
            confirmLabel = "Confirm",
            dismissLabel = "Cancel",
            onConfirm = {
                confirmKind = null
                when (kind) {
                    DetailActionConfirmKind.CANCEL_COLLECTION -> onUncollect()
                    DetailActionConfirmKind.CANCEL_DOWNLOAD -> onLocalDownloadCancel()
                    DetailActionConfirmKind.DELETE_DOWNLOAD -> onLocalDownloadDelete()
                }
            },
            onDismiss = { confirmKind = null },
        )
    }
    if (confirmDeleteChapterId != null) {
        DetailActionConfirmDialog(
            title = "Delete saved copy?",
            message = "Removes this chapter's pages from this phone. Your cloud copy, bookmark and reading progress stay.",
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            onConfirm = {
                val chapterId = confirmDeleteChapterId
                confirmDeleteChapterId = null
                if (chapterId != null) onDeleteSavedChapter(chapterId)
            },
            onDismiss = { confirmDeleteChapterId = null },
        )
    }
    if (confirmDeleteSavedSerialized && onDeleteSavedSerializedChapters != null) {
        DetailActionConfirmDialog(
            title = "Delete saved chapters?",
            message = serializedDeleteSavedChaptersMessage(serializedCloudChapterRows.isNotEmpty()),
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            onConfirm = {
                confirmDeleteSavedSerialized = false
                onDeleteSavedSerializedChapters()
            },
            onDismiss = { confirmDeleteSavedSerialized = false },
        )
    }
    signInChapterId?.let {
        DetailActionConfirmDialog(
            title = "Sign in to read this chapter",
            message = "再漫画 only shows this chapter to signed-in readers.",
            confirmLabel = "Sign in",
            dismissLabel = "Not now",
            onConfirm = {
                signInChapterId = null
                onZaimanhuaSignIn()
            },
            onDismiss = { signInChapterId = null },
        )
    }
}

/**
 * 96×128 hero cover. `null` keeps today's grey block (a blank catalog
 * `coverUrl`, or a local row with no pages) — never a "cover unavailable"
 * label. The `detail-hero-cover` tag is carried over from the retired local
 * skin so cover coverage survives the merge.
 */
@Composable
private fun DetailCoverFrame(
    image: DetailImage?,
    contentDescription: String,
    palette: QuietShelfPalette,
) {
    val frame = Modifier
        .size(width = 96.dp, height = 128.dp)
        .clip(RoundedCornerShape(10.dp))
        .testTag("detail-hero-cover")
    if (image == null) {
        Box(modifier = frame.background(palette.line))
    } else {
        DetailAsyncImage(
            image = image,
            contentDescription = contentDescription,
            modifier = frame,
        )
    }
}

/**
 * The one place a Detail image is resolved. [DetailImage.Url] is the catalog
 * path, unchanged. [DetailImage.Page] goes through `buildPageImageRequest`,
 * which keeps Coil's memory cache off for covers so a redisplay always
 * reopens through `PageContentOpener` (grant-loss detection).
 */
@Composable
internal fun DetailAsyncImage(
    image: DetailImage,
    contentDescription: String?,
    modifier: Modifier,
) {
    when (image) {
        is DetailImage.Url -> AsyncImage(
            model = image.url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        is DetailImage.Page -> {
            val context = LocalContext.current
            val request = remember(image.page) { buildPageImageRequest(context, image.page) }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = modifier,
            )
        }
        is DetailImage.Missing -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            BrokenImageGlyph(
                size = 32.dp,
                contentDescription = contentDescription.orEmpty(),
            )
        }
    }
}

/**
 * #17 v2 — the constant the owner asked for: the clear space kept between the
 * bottom of the preview box and the bottom tab bar, on every phone.
 *
 * `DetailSkeleton`'s outer Column measures its height inside the Scaffold
 * content, so that height already stops at the bottom bar's slot. This 12.dp
 * value is therefore the constant gap from the preview box to that slot (the floating
 * pill itself sits a further 10.dp inside its slot, see `AppBottomBar`) — the
 * same constant in both branches: the grow branch's `bottomGapPx` and the
 * outer Column's bottom padding.
 */
internal val PREVIEW_BOTTOM_PADDING = DetailPreviewBoxHeight.DEFAULT_BOTTOM_GAP_DP.dp

/**
 * A local subject through the one skeleton (spec §2.1 order, Part 4 chunk E3).
 * Page 1 on disk is the cover (spec §2: offline, already granted, and already
 * how the shelf renders), every local page is a preview cell, and there is
 * nothing to warm off disk.
 *
 * A32 §3 fills the switch from [DetailControls]: an import gets neither (no
 * upstream to collect, no download the app owns), while a catalog download
 * gets a live collect switch and a solid download action that opens the
 * delete confirmation.
 */
@Composable
private fun LocalDetailBody(
    subject: DetailSubject.Local,
    /** E92 Part 4 E2: see the same parameter on [ComicDetailScreen]. */
    detailRegistry: com.easemic.app.feature.source.SourceDetailRegistry? = null,
    statusMessage: String?,
    cloudBound: Boolean,
    /**
     * C4 §2.1: this comic's upload is in flight. The Local route used to
     * hardcode false through [DetailControls.of], so the one destructive verb
     * on this screen stayed live while a Collect-driven upload was reading the
     * very pages it deletes.
     */
    cloudQueued: Boolean,
    onBack: () -> Unit,
    onReadFrom: (Int) -> Unit,
    onPreviewPageClick: (Int) -> Unit,
    onOpenFolders: () -> Unit,
    onCollect: () -> Unit,
    onUncollect: () -> Unit,
    onDeleteDownload: (() -> Unit)?,
    onTagClick: ((String) -> Unit)?,
    collectTargetResolved: Boolean,
) {
    val item = subject.item
    val controls = DetailControls.of(
        subject,
        cloudBound = cloudBound,
        collectTargetResolved = collectTargetResolved,
        cloudQueued = cloudQueued,
    )
    var confirmUncollect by remember { mutableStateOf(false) }
    var confirmDeleteDownload by remember { mutableStateOf(false) }
    var helperSheetOpen by remember { mutableStateOf(false) }
    val palette = quietShelfPalette()
    // Part 2 adapters at the call site (the loader is Part 5): the one detail
    // table is the local adapter's projection of this row. The provenance
    // namespace of the acquisition key picks the profile — "a local or cloud
    // copy of a catalog comic uses the profile of the source it came from"
    // (spec §2.2) — and an unknown namespace (an import) falls back to the
    // default profile exactly as `SourceDetailProfiles.profile` does.
    val resolvedDetailRegistry = detailRegistry ?: remember { SourceDetailRegistry(DetailOnlySourceRegistry) }
    val detailContext = resolvedDetailRegistry.context(namespace = subject.acquisitionNamespace)
    val detail = localComicDetail(subject, detailContext).let { base ->
        // Today's meta line never invented a count the row does not have:
        // `toFeedItem` drops a zero page count (`takeIf { it > 0 }`), and
        // ComicDetailScreenTest pins "0 pages" absent for an empty row. Keep
        // that rule on the skeleton's count.
        if (item.pageCount > 0) base else base.copy(count = DetailField.Omitted)
    }
    val profile = detailContext.profile
    // §2.1.3 expansion keying: "<provenance namespace>:<item id>". An import
    // carries no namespace, so it uses the adapter's own fallback identity
    // ("On this phone", LocalComicDetailAdapter.kt:16) — the same one its
    // facts line shows.
    val memoryKey = "${subject.acquisitionNamespace ?: "On this phone"}:${item.id.value}"
    val previewImages = item.pages.map { DetailImage.Page(it) }
    // §2.1.4: the helper link shows while a Collect or Download switch shows
    // and names the switches it has; a state with no explanation
    // (`DetailControls.helper`, A30 §2.4) shows no link. The sheet body is the
    // DetailControls-derived copy, never the cloud explanation bodies.
    val helperLink = when {
        controls.helper == null -> null
        controls.collectIcon == null && controls.downloadIcon == null -> null
        controls.downloadIcon != null -> "What Collect and Download do ›"
        else -> "What Collect does ›"
    }
    val switches: (@Composable () -> Unit)? =
        if (controls.collectIcon == null && controls.downloadIcon == null) {
            null
        } else {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // #E73 (owner 2026-10-12): Download LEFT of Collect on every
                    // detail — order only; the icons and both confirm dialogs are
                    // untouched.
                    controls.downloadIcon?.let { icon ->
                        DownloadSwitch(
                            icon = icon,
                            onClick = if (controls.downloadInteractive && onDeleteDownload != null) {
                                { confirmDeleteDownload = true }
                            } else {
                                null
                            },
                            locked = controls.downloadLocked,
                        )
                    }
                    controls.collectIcon?.let { icon ->
                        CollectSwitch(
                            icon = icon,
                            onClick = {
                                if (subject.collected || subject.inCloud) {
                                    confirmUncollect = true
                                } else {
                                    onCollect()
                                }
                            },
                        )
                    }
                }
            }
        }
    // #17 v2 (owner design): the preview box scrolls INTERNALLY, so the
    // sections above never leave the viewport while the owner browses previews;
    // the outer verticalScroll stays for the case the box cannot absorb
    // (DetailSkin's wiring kept identical around the skeleton).
    val scrollState = rememberScrollState()
    var viewportTopPx by remember { mutableIntStateOf(0) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                viewportTopPx = coords.positionInWindow().y.roundToInt()
                viewportHeightPx = coords.size.height
            }
            .verticalScroll(scrollState)
            .testTag("remote-catalog-detail"),
    ) {
        DetailSkeleton(
            detail = detail,
            profile = profile,
            palette = palette,
            memoryKey = memoryKey,
            cover = {
                DetailCoverFrame(
                    image = item.pages.firstOrNull()?.let { DetailImage.Page(it) },
                    contentDescription = subject.metadata.title,
                    palette = palette,
                )
            },
            topBar = {
                // Local has no More actions today — an imported folder is never
                // deletable and the download's one verb lives in the switches.
                DetailTopBar(palette = palette, onBack = onBack, moreActions = null)
            },
            actions = {
                DetailActionRow(
                    palette = palette,
                    readLabel = controls.primaryLabel,
                    readEnabled = controls.primaryEnabled,
                    onRead = { onReadFrom(controls.startOrdinal) },
                    switches = switches,
                    serializedCrowded = false,
                )
            },
            notices = {
                Column {
                    LocalDetailStatus(
                        subject = subject,
                        truncationNote = controls.truncationNote,
                        onOpenFolders = onOpenFolders,
                    )
                    DetailNotices(
                        palette = palette,
                        statusMessage = statusMessage,
                        // #E105 (owner): the helper copy lives behind the link's
                        // sheet only — never a static line on the page. A30 §2.4
                        // still holds through the link: no helper → no link.
                        helperLink = helperLink,
                        onHelperClick = { helperSheetOpen = true },
                    )
                }
            },
            peopleClickable = onTagClick != null && subject.isCatalogDownload,
            onPersonClick = { person -> onTagClick?.invoke(person.rawSearchValue) },
            tagsClickable = onTagClick != null && subject.isCatalogDownload,
            onTagClick = { tag -> onTagClick?.invoke(tag.rawSourceValue) },
            content = {
                if (item.pages.isNotEmpty()) {
                    DetailPages(
                        count = (detail.count.valueOrNull() as? DetailCount.Pages)?.total ?: item.pages.size,
                        previewImages = previewImages,
                        slotCount = item.pages.size,
                        scrollState = scrollState,
                        viewportTopPx = viewportTopPx,
                        viewportHeightPx = viewportHeightPx,
                        // Read is disabled for an unreachable row; a preview tap
                        // must not be a way around it.
                        interactive = controls.primaryEnabled,
                        onPreviewPageClick = onPreviewPageClick,
                        onPreviewWarmThrough = {},
                    )
                }
            },
            tagsLoading = false,
        )
    }
    if (helperSheetOpen) {
        DetailHelperSheet(
            palette = palette,
            body = controls.helper.orEmpty(),
            onDismiss = { helperSheetOpen = false },
        )
    }
    if (confirmUncollect) {
        DetailActionConfirmDialog(
            title = DetailActionConfirmKind.CANCEL_COLLECTION.title,
            // F4: no bound bucket means no cloud copy, so drop the recycle-bin sentence.
            message = cancelCollectionConfirmMessage(cloudBound),
            confirmLabel = "Confirm",
            dismissLabel = "Cancel",
            onConfirm = {
                confirmUncollect = false
                onUncollect()
            },
            onDismiss = { confirmUncollect = false },
        )
    }
    if (confirmDeleteDownload && onDeleteDownload != null) {
        DetailActionConfirmDialog(
            title = DetailActionConfirmKind.DELETE_DOWNLOAD.title,
            message = DetailActionConfirmKind.DELETE_DOWNLOAD.message,
            confirmLabel = "Confirm",
            dismissLabel = "Cancel",
            onConfirm = {
                confirmDeleteDownload = false
                onDeleteDownload()
            },
            onDismiss = { confirmDeleteDownload = false },
        )
    }
}

/**
 * Emits nothing when the row is fine — no "everything is OK" banner.
 *
 * A30 §2.2/§2.4 (S4b/I2): `isAvailable` is false for two reasons and they get two
 * different statements. Only the lost grant may promise a way back.
 *
 * P-a: the truncated-seal note is a *different* axis (how much of the download
 * ever arrived), so it is additive rather than part of that precedence chain.
 */
@Composable
private fun LocalDetailStatus(
    subject: DetailSubject.Local,
    truncationNote: String?,
    onOpenFolders: () -> Unit,
) {
    val item = subject.item
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            !item.isAvailable && subject.sourceAccessLost -> DetailAccessLostBlock(
                grantKind = item.sourceGrantKind,
                onOpenFolders = onOpenFolders,
            )
            !item.isAvailable -> DetailFilesGoneBlock()
            // A36: a catalog download has no folder to rescan — the source
            // withdrew the pages. The block still names the state; the
            // truncation note right below gives the reason.
            //
            // Review N3: decided by [isCatalogDownloadRow] — where the FILES
            // are — and not by `subject.isCatalogDownload` (which asks whether
            // an upstream acquisition exists). The reader asks the same
            // question of the same row, so the two surfaces cannot end up
            // giving one comic two different accounts of being short.
            item.isIncomplete -> DetailIncompleteBlock(
                onOpenFolders = onOpenFolders.takeUnless { item.isCatalogDownloadRow() },
            )
            else -> Unit
        }
        truncationNote?.let { DetailTruncatedSealBlock(note = it) }
    }
}
