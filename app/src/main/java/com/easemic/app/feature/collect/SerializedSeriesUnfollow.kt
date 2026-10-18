package com.easemic.app.feature.collect

import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.isSerializedChapter
import com.easemic.app.feature.download.serializedSeriesLedgerKey
import kotlinx.coroutines.CancellationException

/** Result of stopping a serialized-series follow. */
sealed interface SerializedSeriesUnfollowResult {
    data object Unfollowed : SerializedSeriesUnfollowResult
    data class CloudFailed(val kind: UncollectCloudFailureKind) : SerializedSeriesUnfollowResult
    data class Failed(val kind: UncollectCloudFailureKind = UncollectCloudFailureKind.UNREACHABLE) : SerializedSeriesUnfollowResult
}

/**
 * Coordinates the whole-series half of serialized uncollect.
 *
 * The caller removes the bookmark before starting this operation, as the
 * gallery path already does. A bucket failure restores the captured rows and
 * exits before touching jobs, queue state, refresh state, or the +N mark.
 * The same holds while the mirror's series identity is still hydrating: no
 * card can be matched yet, so the unfollow reports SYNCING instead of
 * tombstoning nothing and calling it done.
 * Every identity lookup is provider/series based; acquisition URLs are used
 * only as queue-row payloads during cleanup.
 */
class SerializedSeriesUnfollow(
    private val jobsForSeries: suspend (GalleryIdentityV1) -> List<DownloadJob>,
    private val currentBucketFingerprint: () -> String?,
    private val resolveCloudComicId: suspend (String, SeriesIdentityV2) -> String?,
    /** True when the active mirror still holds a SERIES card whose provider identity has not hydrated yet. */
    private val hasUnhydratedSeriesCards: suspend (String) -> Boolean,
    private val tombstoneCloud: suspend (String) -> UncollectCloudOutcome,
    private val markMirrorTrashed: suspend (String, String, Long) -> Unit,
    private val cancelJob: suspend (String) -> Unit,
    private val dismissFailedJob: suspend (String) -> Unit,
    private val dropCloudIntent: suspend (String) -> Unit,
    private val discardCloudOnlyJob: suspend (String) -> Unit,
    private val purgeJob: suspend (String) -> Unit,
    /** True when Room still exposes an on-device Library association for this job's series. */
    private val hasLibraryAssociation: suspend (DownloadJob) -> Boolean = { false },
    /** Removes a FAILED chapter's partial catalog revision and READY files before its row is purged. */
    private val deleteFailedRevision: suspend (DownloadJob) -> Unit = {},
    private val queueRows: () -> List<CloudSyncQueueItem>,
    private val removeQueueRow: (id: String, chapterIdentity: String) -> Unit,
    private val restoreDismissal: (id: String, chapterIdentity: String) -> Unit,
    private val clearSeriesLedger: suspend (String, String) -> Unit,
    private val restoreBookmarks: (List<CollectedBookmark>) -> Unit,
    private val clearRefreshState: (SeriesIdentityV2) -> Unit,
    private val clearNewChapterMark: (SeriesIdentityV2) -> Unit,
    private val deleteUpdatesForSeries: suspend (String, String) -> Unit = { _, _ -> },
    private val removeSourceCover: suspend (SeriesIdentityV2) -> Unit = {},
) {
    suspend fun run(
        series: SeriesIdentityV2,
        removedBookmarks: List<CollectedBookmark>,
    ): SerializedSeriesUnfollowResult {
        val fingerprint = currentBucketFingerprint()
        var cloudStepCompleted = fingerprint == null
        try {
            if (fingerprint != null) {
                val cloudComicId = resolveCloudComicId(fingerprint, series)
                if (cloudComicId != null) {
                    when (val outcome = tombstoneCloud(cloudComicId)) {
                        is UncollectCloudOutcome.Tombstoned -> {
                            cloudStepCompleted = true
                            markMirrorTrashed(fingerprint, outcome.cloudComicId, outcome.tombstonedAtMs)
                        }
                        is UncollectCloudOutcome.NotInIndex -> {
                            cloudStepCompleted = true
                            markMirrorTrashed(fingerprint, outcome.cloudComicId, outcome.atMs)
                        }
                        UncollectCloudOutcome.NotFound -> {
                            cloudStepCompleted = true
                        }
                        is UncollectCloudOutcome.Failed -> {
                            restoreBookmarks(removedBookmarks)
                            return SerializedSeriesUnfollowResult.CloudFailed(outcome.kind)
                        }
                    }
                } else if (hasUnhydratedSeriesCards(fingerprint)) {
                    // No card matched, but the mirror still has SERIES cards
                    // whose series.json identity never arrived (a second
                    // device mid-hydration, for example). One of them could
                    // be this series, so "nothing to tombstone" is not yet a
                    // verdict: hold the follow instead of silently leaving an
                    // ACTIVE cloud card that adoptAll would re-follow.
                    restoreBookmarks(removedBookmarks)
                    return SerializedSeriesUnfollowResult.CloudFailed(UncollectCloudFailureKind.SYNCING)
                } else {
                    cloudStepCompleted = true
                }
            }

            val localSeries = GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId)
            jobsForSeries(localSeries)
                .filter { it.isSerializedChapter }
                .forEach { job ->
                    when (job.state) {
                        DownloadJobState.WAIT,
                        DownloadJobState.DOWNLOAD -> {
                            cancelJob(job.id)
                            purgeJob(job.id)
                        }
                        DownloadJobState.FAILED -> {
                            // Dismiss + purge remains the visible failed-row
                            // behavior, but purgeJob only removes Room rows.
                            // Delete the partial catalog revision first so a
                            // failed chapter cannot strand files on disk.
                            deleteFailedRevision(job)
                            dismissFailedJob(job.id)
                            purgeJob(job.id)
                        }
                        DownloadJobState.FINISH -> dropCloudIntent(job.id)
                        DownloadJobState.SEALED -> {
                            // Reuse the gallery uncollect decision: a
                            // dual-intent seal can be the truncated
                            // source-removal wind-down and owns the user's
                            // only local copy. Only a genuinely cloud-only
                            // seal may discard its files and row.
                            when (uncollectJobOutcome(job)) {
                                UncollectJobOutcome.DROP_CLOUD_INTENT -> dropCloudIntent(job.id)
                                UncollectJobOutcome.DISCARD -> {
                                    discardCloudOnlyJob(job.id)
                                    purgeJob(job.id)
                                }
                                UncollectJobOutcome.NONE -> Unit
                            }
                        }
                        DownloadJobState.NONE -> purgeJob(job.id)
                        // A GONE row is no longer actionable. Remove its
                        // Activity row while leaving a saved copy alone. A
                        // ready page or Library association is the same
                        // ownership signal used by the delete path.
                        DownloadJobState.GONE -> {
                            dismissFailedJob(job.id)
                            dropCloudIntent(job.id)
                            if (job.readyPageCount == 0 && !hasLibraryAssociation(job)) {
                                purgeJob(job.id)
                            }
                        }
                    }
                }

            queueRows()
                .filter { row ->
                    row.providerId == series.providerNamespace &&
                        row.canonicalItemId == series.canonicalSeriesId &&
                        row.chapterIdentity != com.easemic.app.feature.download.GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID
                }
                .forEach { row ->
                    restoreDismissal(row.id, row.chapterIdentity)
                    removeQueueRow(row.id, row.chapterIdentity)
                }

            if (fingerprint != null) {
                clearSeriesLedger(fingerprint, serializedSeriesLedgerKey(series))
            }
            clearRefreshState(series)
            clearNewChapterMark(series)
            deleteUpdatesForSeries(series.providerNamespace, series.canonicalSeriesId)
            removeSourceCover(series)
            return SerializedSeriesUnfollowResult.Unfollowed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The UI launches this from rememberCoroutineScope; never let a
            // bucket/Room/file error become an uncaught scope exception. Once
            // the bucket move succeeded, leave the bookmark removed rather than
            // resurrecting a follow whose cloud card is already in trash.
            if (!cloudStepCompleted) restoreBookmarks(removedBookmarks)
            return SerializedSeriesUnfollowResult.Failed()
        }
    }
}
