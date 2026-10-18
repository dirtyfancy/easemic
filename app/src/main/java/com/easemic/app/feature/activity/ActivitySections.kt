package com.easemic.app.feature.activity

import com.easemic.app.feature.collect.CloudSyncFailurePolicy
import com.easemic.app.feature.collect.CloudSyncIngestAutoRetry
import com.easemic.app.feature.collect.CloudSyncQueueItem
import com.easemic.app.feature.collect.CloudSyncStage
import com.easemic.app.feature.collect.shouldAutoResumeFailedCloudSync
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.DownloadQueueRow
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.downloadRowWillAutoRevive

/**
 * A29 (owner bug backlog; EhViewer-informed): the Activity feed used to lie
 * about two kinds of FAILED rows — a download the bounded auto-revive sweep
 * will never touch again (capped, or local-only intent) still counted under
 * "Downloading · N", and a FAILED sync row always claimed "retrying ·
 * resumes automatically" even after the sync auto-resume had given up.
 * EhViewer's own queue never makes that promise for a dead row: a failed
 * item just sits there and the user taps ▶.
 *
 * The rule this file encodes: a row is "needs attention" when retry or repair
 * requires user action — i.e. it is FAILED and nothing in the app will ever
 * automatically re-drive it again. Everything else — WAIT/DOWNLOAD (including
 * a throttle-parked download), WAITING/FETCHING/UPLOADING sync, and any
 * FAILED row some sweep or backoff will still pick back up — keeps today's
 * "resumes automatically" framing under the ordinary Downloading/Syncing
 * sections, because for those rows that promise is still true.
 */
data class ActivitySections(
    val attentionDownloads: List<DownloadQueueRow>,
    val attentionSyncs: List<CloudSyncQueueItem>,
    val downloading: List<DownloadQueueRow>,
    val syncing: List<CloudSyncQueueItem>,
    val serializedAttention: List<SerializedActivitySummary> = emptyList(),
    val serializedDownloading: List<SerializedActivitySummary> = emptyList(),
    val serializedSyncAttention: List<SerializedActivitySummary> = emptyList(),
    val serializedSyncing: List<SerializedActivitySummary> = emptyList(),
) {
    /**
     * Serialized GONE rows are intentionally omitted in 4b: there is no
     * series Dismiss action yet, so they contribute to neither serialized list
     * nor [attentionCount] (and never fall through to the gallery path).
     */
    val attentionCount: Int
        get() = attentionDownloads.size + attentionSyncs.size + serializedAttention.size + serializedSyncAttention.size
}

/**
 * A FAILED download the auto-revive sweep ([downloadRowWillAutoRevive]) will
 * not pick back up on its own — capped past [com.easemic.app.feature.download.AUTO_RETRY_MAX_ATTEMPTS]
 * (and still within its cooldown), or a local-only intent that the sweep
 * never touches at all. #C9: and a GONE row — the source delisted it, nothing
 * in the app will ever drive it again; only Dismiss moves it.
 */
internal fun downloadRowNeedsAttention(row: DownloadQueueRow, nowMs: Long): Boolean =
    row.state == DownloadJobState.GONE ||
        (row.state == DownloadJobState.FAILED && !downloadRowWillAutoRevive(row, nowMs))

/**
 * Shared with [ActivitySyncRow] (in ActivityScreen.kt) so the row's own
 * "failed · tap Retry" vs "retrying · resumes automatically" copy/tag choice
 * can never drift out of sync with which section the row was partitioned
 * into.
 *
 * A FAILED sync row needs attention when its input needs repair, a cloud action
 * is required, or it has exhausted its retry budget ([CloudSyncIngestAutoRetry.MAX_ATTEMPTS]).
 * [shouldAutoResumeFailedCloudSync] also returns false for a row that is
 * merely waiting out its T3c backoff window — that row WILL still be picked
 * up automatically once the window elapses, exactly like a throttle-parked
 * download stays "paused · resumes automatically" rather than becoming an
 * attention row — so this deliberately does not thread the row's real
 * [CloudSyncQueueItem.failedAtEpochMs] through: a null anchor makes
 * [shouldAutoResumeFailedCloudSync] answer the permanent question ("has the
 * attempt cap been hit?") without the transient elapsed-time branch, so a
 * row inside its backoff window is not mistaken for one the system has
 * given up on.
 */
fun syncRowNeedsAttention(row: CloudSyncQueueItem): Boolean =
    row.stage == CloudSyncStage.FAILED &&
        (!CloudSyncFailurePolicy.canRetry(row.errorMessage) || !shouldAutoResumeFailedCloudSync(
            stage = row.stage,
            failedAttempts = row.failedAttempts,
            // Deliberately null (see above): with no time anchor the decision is
            // purely "has the attempt cap been hit?", so no clock is needed here.
            failedAtEpochMs = null,
        ))

/**
 * Partitions the two Activity queues into gallery lists and one summary per
 * serialized series. [List.partition] is stable, so each gallery output list
 * preserves the relative order of [downloadRows]/[syncRows] — the existing
 * `"dl-${jobId}"` / `"sync-${id}"` row keys stay unique because a row is
 * placed into exactly one of its two possible sections. Serialized GONE rows
 * are removed before grouping in 4b; there is no series Dismiss action yet.
 */
fun activitySections(
    downloadRows: List<DownloadQueueRow>,
    syncRows: List<CloudSyncQueueItem>,
    nowMs: Long,
    cloudBound: Boolean = true,
    serializedPopulations: Map<SeriesIdentityV2, SerializedChapterPopulation> = emptyMap(),
    seriesTitles: Map<Pair<String, String>, String> = emptyMap(),
): ActivitySections {
    // Serialized failures and current chapters are independent: one series
    // may appear once in each section. Galleries keep their partition and order.
    val serializedRows = downloadRows.filter { it.seriesCanonicalId != null && it.chapterId != null }
    val galleryRows = downloadRows.filter { it.seriesCanonicalId == null || it.chapterId == null }
    val serializedSummaries = serializedActivitySummaries(
        rows = serializedRows,
        populations = serializedPopulations,
        seriesTitles = seriesTitles,
        nowMs = nowMs,
    )
    val serializedAttention = serializedSummaries.filter { it.failedJobIds.isNotEmpty() }
    val serializedDownloading = serializedSummaries.filter {
        it.currentChapterLabel != null
    }
    val (attentionDownloads, downloading) = galleryRows.partition { downloadRowNeedsAttention(it, nowMs) }
    // A27 (owner bug backlog, ledger 2026-09-05): with no cloud storage
    // bound at all, every gallery sync row belongs under "Needs attention" --
    // nothing in the app will drive any of them again until the user binds
    // a bucket (the pump itself stops claiming while unbound; see
    // CloudIngestPumpLogicTest). Serialized follows carry cloud intent
    // automatically, so only their failed rows need attention while unbound.
    val serializedSyncRows = syncRows.filter {
        !it.providerId.isNullOrBlank() &&
            !it.canonicalItemId.isNullOrBlank() &&
            it.chapterIdentity != com.easemic.app.feature.download.GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID
    }
    val gallerySyncRows = syncRows.filterNot { it in serializedSyncRows }
    val serializedSyncSummaries = serializedCloudActivitySummaries(
        rows = serializedSyncRows,
        populations = serializedPopulations,
        seriesTitles = seriesTitles,
    )
    val serializedSyncAttention = serializedSyncSummaries.filter { it.failedJobIds.isNotEmpty() }
    val serializedSyncing = if (cloudBound) {
        serializedSyncSummaries.filter { it.currentChapterLabel != null }
    } else {
        emptyList()
    }
    val (attentionSyncs, syncing) = if (cloudBound) {
        gallerySyncRows.partition { syncRowNeedsAttention(it) }
    } else {
        gallerySyncRows to emptyList()
    }
    return ActivitySections(
        attentionDownloads = attentionDownloads,
        attentionSyncs = attentionSyncs,
        downloading = downloading,
        syncing = syncing,
        serializedAttention = serializedAttention,
        serializedDownloading = serializedDownloading,
        serializedSyncAttention = serializedSyncAttention,
        serializedSyncing = serializedSyncing,
    )
}
