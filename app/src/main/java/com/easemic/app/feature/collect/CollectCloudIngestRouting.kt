package com.easemic.app.feature.collect

import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.DownloadLocator
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.GalleryIdentityV2
import com.easemic.app.feature.download.isSerializedChapter
import com.easemic.app.feature.download.serializedSeriesLedgerKey
import com.easemic.app.feature.library.ChapterShelfRow

/**
 * Where a collect's cloud sync should go (spec §1.1 decouple-fetch-from-upload).
 *
 * A resolvable [GalleryIdentityV1] routes to the fetch pipeline: a cloud-intent
 * download job is enqueued so [com.easemic.app.feature.download.DownloadWorkerLoop]
 * (with its symptom routing + resume) owns page acquisition, and the upload
 * only starts once that job reaches SEALED (the `onCloudHandoff` callback
 * wired in AppGraph). An acquisition that cannot resolve to a fetchable
 * identity (e.g. an OPDS shape [OpdsCloudIngestGuard] already lets through but
 * [com.easemic.app.feature.download.BrowseGalleryIdentity]
 * cannot map) keeps today's legacy direct-to-R2 queue row unchanged.
 */
sealed interface CollectCloudIngestRoute {
    data class EnqueueFetchJob(val identity: GalleryIdentityV1) : CollectCloudIngestRoute
    data object LegacyDirectUpload : CollectCloudIngestRoute

    /**
     * #C22 audit HIGH-1: the identity resolved and the job is already terminal
     * (FINISH/SEALED), but its pages cannot be handed to the upload (a hole, a
     * null page count, a released tree). The pump never revisits a terminal job
     * and the cold-start repair applies the same gate, so nothing downstream
     * will ever act on it — the caller must leave a visible trace.
     */
    data class TerminalJobNotResolvable(val identity: GalleryIdentityV1, val job: DownloadJob) : CollectCloudIngestRoute
}

/**
 * The ledger question the release sweep and the upload pickup both ask:
 * this chapter, under this series key, in this bucket. A blank chapter or the
 * gallery sentinel is never a series hit, so one synced chapter cannot stand
 * in for a sibling.
 */
suspend fun isSerializedChapterSyncedToBucket(
    providerNamespace: String,
    canonicalItemId: String,
    chapterId: String,
    bucketFingerprint: String,
    isSynced: suspend (bucketFingerprint: String, seriesKey: String, chapterId: String) -> Boolean,
): Boolean {
    if (chapterId == GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID) return false
    if (providerNamespace.isBlank() || canonicalItemId.isBlank() || chapterId.isBlank() || bucketFingerprint.isBlank()) {
        return false
    }
    return isSynced(
        bucketFingerprint,
        serializedSeriesLedgerKey(providerNamespace, canonicalItemId),
        chapterId,
    )
}

/** One identity-aware ledger predicate shared by handoff and release paths. */
suspend fun isSerializedJobSyncedToBucket(
    job: DownloadJob,
    bucketFingerprint: String,
    isSynced: suspend (bucketFingerprint: String, seriesKey: String, chapterId: String) -> Boolean,
): Boolean = isSerializedChapterSyncedToBucket(
    providerNamespace = job.identity.providerNamespace,
    canonicalItemId = job.identity.canonicalItemId,
    chapterId = job.chapterIdentity,
    bucketFingerprint = bucketFingerprint,
    isSynced = isSynced,
)

data class SerializedChapterCloudFacts(
    val label: String,
    val group: String?,
    val readingOrder: Int,
    val sourceState: String,
)

/** Chapter facts are required to publish a truthful series summary. */
fun serializedChapterCloudFactsOrNull(chapter: ChapterShelfRow?): SerializedChapterCloudFacts? {
    if (chapter == null || chapter.label.isBlank() || chapter.sourceState.isBlank()) return null
    return SerializedChapterCloudFacts(
        label = chapter.label,
        group = chapter.sourceGroup,
        readingOrder = chapter.ordinal,
        sourceState = chapter.sourceState,
    )
}

/**
 * Injectable orchestration for the collect-time half of the SEALED→upload
 * handoff. Kept free of `AppGraph`/Compose so [handle] can run under a plain
 * JVM test with fake lambdas standing in for the real
 * `downloadLocatorStore`/`downloadJobStore`/`DownloadPumpWorker` calls.
 */
object CollectCloudIngestHandoff {
    /**
     * Resolves the route for [identity]. When it resolves, stores [locator]
     * (so the fetch pump can find the acquisition/title/cover for the new
     * job), enqueues a cloud-intent download job via [upsertEnqueue], and
     * calls [kickDownloadPump]. Spec §1.1 FIX3: [upsertEnqueue]'s DAO
     * OR-merges intentCloud onto any existing non-NONE row *in its current
     * state* rather than reviving it to WAIT — so a comic already downloaded
     * to FINISH/SEALED before being collected would sit there forever,
     * never re-entering [com.easemic.app.feature.download.DownloadWorkerLoop]'s
     * consumption queue, and the pump-driven `onCloudHandoff` would never
     * fire. [isResolvableForCloudHandoff] checks the just-returned job for
     * exactly that shape, and [fireCloudHandoffDirectly] fires the handoff
     * immediately instead of waiting on a pump pass that will never come.
     * Returns the decided route so the caller can fall back to the legacy
     * direct-upload queue row when unresolved.
     */
    suspend fun handle(
        identity: GalleryIdentityV1?,
        locator: DownloadLocator,
        expectedPageCount: Int?,
        putLocator: (GalleryIdentityV1, DownloadLocator) -> Unit,
        upsertEnqueue: suspend (
            identity: GalleryIdentityV1,
            expectedPageCount: Int?,
            intentCloud: Boolean,
            intentLocal: Boolean,
        ) -> DownloadJob,
        kickDownloadPump: () -> Unit,
        isResolvableForCloudHandoff: (DownloadJob) -> Boolean,
        fireCloudHandoffDirectly: suspend (DownloadJob) -> Unit,
    ): CollectCloudIngestRoute {
        val route = route(identity)
        if (route is CollectCloudIngestRoute.EnqueueFetchJob) {
            putLocator(route.identity, locator)
            // Collect is a local-first operation: fetching pages to satisfy the
            // cloud upload also creates the device copy.  Keep that intent so
            // the default-off release setting preserves it; the explicit
            // cloud-only setting may later release this dual-intent job.
            val job = upsertEnqueue(route.identity, expectedPageCount, true, true)
            if (isResolvableForCloudHandoff(job)) {
                fireCloudHandoffDirectly(job)
            } else if (job.state == DownloadJobState.FINISH || job.state == DownloadJobState.SEALED) {
                // #C22 audit HIGH-1: a terminal job the handoff cannot resolve is
                // nobody's work — the pump never revisits it and the cold-start
                // repair applies the same gate. Report it so the caller can leave
                // a visible attention row instead of a silent accepted tap.
                return CollectCloudIngestRoute.TerminalJobNotResolvable(route.identity, job)
            }
            kickDownloadPump()
        }
        return route
    }

    fun route(identity: GalleryIdentityV1?): CollectCloudIngestRoute =
        if (identity != null) {
            CollectCloudIngestRoute.EnqueueFetchJob(identity)
        } else {
            CollectCloudIngestRoute.LegacyDirectUpload
        }
}

/**
 * A31 (owner bug backlog): what Uncollect must do to [job]'s underlying fetch.
 * A cloud-only job (spec §1.1's decoupled fetch-then-upload) exists solely to
 * feed the R2 upload, so there is nothing left to keep it for once its
 * bookmark is gone — [DISCARD] applies **regardless of state**, including a
 * TERMINAL one (FAILED/SEALED/GONE). Before this fix, a cloud-only job that
 * reached a terminal state before being uncollected was left alive: the old
 * caller-side state gate only cancelled WAIT/DOWNLOAD, so an auto-revive
 * sweep or a manual Retry could bring it back from FAILED with no bookmark
 * and no way to ever upload — stuck SEALED forever, silently. A dual-intent
 * job (collect *and* local download) keeps running — the user still wants
 * the local copy — and only its cloud intent is dropped ([DROP_CLOUD_INTENT]),
 * which also makes [cloudUploadRowForJob]'s `isAcquisitionCollected` guard
 * (kept as defense in depth) unreachable for it going forward. [NONE] is a
 * job with no cloud intent at all — Uncollect has nothing to do to it.
 * Controller review HIGH 1 (data loss): state == FINISH is ALSO treated as
 * local intent regardless of [DownloadJob.intentLocal] — a cloud-only job
 * never reaches FINISH (it seals), so FINISH is proof of a real Library
 * download and must never be [DISCARD]ed.
 */
enum class UncollectJobOutcome {
    DROP_CLOUD_INTENT,
    DISCARD,
    NONE,
}

/** Pure decision behind [UncollectJobOutcome] — see its doc for the "why". */
fun uncollectJobOutcome(job: DownloadJob): UncollectJobOutcome = when {
    !job.intentCloud -> UncollectJobOutcome.NONE
    // Controller review HIGH 1 (data loss): a cloud-only job never reaches
    // FINISH — it seals — so state == FINISH is proof of a real local
    // download even if intentLocal was somehow left false (e.g. the Detail
    // "download" handler once omitted it). Treat that the same as
    // intentLocal to never DISCARD (and delete) a real download.
    job.intentLocal || job.state == DownloadJobState.FINISH -> UncollectJobOutcome.DROP_CLOUD_INTENT
    else -> UncollectJobOutcome.DISCARD
}

/**
 * Dispatches [uncollectJobOutcome] for [job] against the real stores. Reuses
 * [cancelActiveJob] — [com.easemic.app.feature.download.RoomDownloadJobStore.cancel],
 * the pre-existing WAIT/DOWNLOAD cancel path — unchanged whenever [job] is
 * still active. [cancelActiveJob] itself refuses a TERMINAL job
 * (FAILED/SEALED/FINISH/GONE) — the A31 case this fix targets — so for those
 * states [DISCARD] is applied via [discardTerminalJob] —
 * [com.easemic.app.feature.download.RoomDownloadJobStore.discardCloudOnlyJob],
 * which mirrors `releaseLocalPagesAfterCloud`'s full local-pages release
 * (READY files, READY rows, Library local-availability, caller-owned
 * catalog tree) instead of leaving orphan pages on disk (controller review
 * follow-up: a bare state flip left 22 orphan pages on Kyouei). Never
 * touches intents/bookmarks/ledger. Returns the outcome that was applied so
 * callers can log it.
 */
suspend fun applyUncollectJobOutcome(
    job: DownloadJob,
    dropCloudIntent: suspend (jobId: String) -> Unit,
    cancelActiveJob: suspend (jobId: String) -> DownloadJob,
    discardTerminalJob: suspend (jobId: String) -> DownloadJob,
): UncollectJobOutcome {
    val outcome = uncollectJobOutcome(job)
    when (outcome) {
        UncollectJobOutcome.DROP_CLOUD_INTENT -> dropCloudIntent(job.id)
        UncollectJobOutcome.DISCARD -> {
            when (job.state) {
                DownloadJobState.WAIT, DownloadJobState.DOWNLOAD -> cancelActiveJob(job.id)
                DownloadJobState.NONE -> Unit
                else -> discardTerminalJob(job.id)
            }
        }
        UncollectJobOutcome.NONE -> Unit
    }
    return outcome
}

/** Why [cloudUploadRowForJob] returned null — logged so a skip is never silent (A31). */
enum class CloudUploadRowSkipReason(val description: String) {
    NO_LOCATOR("no locator"),
    BLANK_ACQUISITION("blank acquisition"),
    /** #E73 spec §4: a local-only job (`intentCloud = false`) must never get a cloud upload row. */
    NO_CLOUD_INTENT("no cloud intent"),
    NOT_COLLECTED("not collected"),
}

/** Pure decision behind [cloudUploadRowForJob]'s null cases — see [CloudUploadRowSkipReason]. */
fun cloudUploadRowSkipReason(
    locator: DownloadLocator?,
    isAcquisitionCollected: (String) -> Boolean,
): CloudUploadRowSkipReason? {
    if (locator == null) return CloudUploadRowSkipReason.NO_LOCATOR
    if (locator.acquisitionUrl.isBlank()) return CloudUploadRowSkipReason.BLANK_ACQUISITION
    if (!isAcquisitionCollected(locator.acquisitionUrl)) return CloudUploadRowSkipReason.NOT_COLLECTED
    return null
}

/**
 * Chapter-aware bookmark gate; serialized locators are not bookmark acquisitions.
 *
 * #E73 spec §4 ("cloud handoff must not enqueue rows for `intentCloud=false`
 * jobs"): the handoff predicate leads with the job's own intent, so a
 * local-only download — the download picker's enqueue — can never mint an
 * upload row even when its series is collected.
 */
fun cloudUploadRowSkipReason(
    job: DownloadJob,
    locator: DownloadLocator?,
    isAcquisitionCollected: (String) -> Boolean,
    isSerializedSeriesCollected: (GalleryIdentityV1) -> Boolean,
): CloudUploadRowSkipReason? {
    if (!job.intentCloud) return CloudUploadRowSkipReason.NO_CLOUD_INTENT
    if (locator == null) return CloudUploadRowSkipReason.NO_LOCATOR
    if (locator.acquisitionUrl.isBlank()) return CloudUploadRowSkipReason.BLANK_ACQUISITION
    if (job.isSerializedChapter) {
        return if (cloudSyncIngestAdmits(job, isSerializedSeriesCollected)) null
        else CloudUploadRowSkipReason.NOT_COLLECTED
    }
    return cloudUploadRowSkipReason(locator, isAcquisitionCollected)
}

/**
 * Builds the [CloudSyncQueueItem] AppGraph's SEALED/FINISH `onCloudHandoff`
 * should enqueue for [job], or null when the upload must not happen — see
 * [cloudUploadRowSkipReason] for why.
 *
 * Mirrors the previous unconditional
 * [com.easemic.app.AppGraph.enqueueCloudUploadForJob] body,
 * plus the whole-slice review GAP 2 guard: [isAcquisitionCollected] is
 * consulted so a dual-intent job that finished fetching *after* its bookmark
 * was uncollected — left running deliberately, see
 * [uncollectJobOutcome] — does not still upload a comic the user no
 * longer wants in the cloud collection.
 */
fun cloudUploadRowForJob(
    job: DownloadJob,
    locator: DownloadLocator?,
    isAcquisitionCollected: (String) -> Boolean,
    isSerializedSeriesCollected: (GalleryIdentityV1) -> Boolean = { false },
): CloudSyncQueueItem? {
    val resolvedLocator = locator ?: return null
    if (cloudUploadRowSkipReason(
            job = job,
            locator = resolvedLocator,
            isAcquisitionCollected = isAcquisitionCollected,
            isSerializedSeriesCollected = isSerializedSeriesCollected,
        ) != null
    ) return null
    val acquisitionUrl = resolvedLocator.acquisitionUrl
    return CloudSyncQueueItem(
        id = acquisitionUrl,
        title = resolvedLocator.title?.takeIf { it.isNotBlank() } ?: acquisitionUrl,
        coverUrl = resolvedLocator.coverUrl,
        stage = CloudSyncStage.WAITING,
        readyPages = 0,
        expectedPages = job.sealedTruncatedAt ?: job.expectedPageCount,
        providerId = job.identity.providerNamespace,
        canonicalItemId = job.identity.canonicalItemId,
        chapterIdentity = job.chapterIdentity,
    )
}
