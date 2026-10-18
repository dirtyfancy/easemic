package com.easemic.app.feature.activity

import com.easemic.app.feature.bridge.ByoCloudIoCopy
import com.easemic.app.feature.collect.CloudSyncFailurePolicy
import com.easemic.app.feature.collect.CloudSyncQueue
import com.easemic.app.feature.collect.CloudSyncQueueItem
import com.easemic.app.feature.collect.CloudSyncStage
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.DownloadQueueRow
import com.easemic.app.feature.download.downloadRowParked

/**
 * A45 (owner bug backlog, ledger 2026-09-06): which "Needs attention" rows get
 * a Dismiss button.
 *
 * Before A45 a stuck row offered only Retry, so a permanently failed one (the
 * source is gone, the gallery was deleted, the credentials are dead) could
 * never be cleared and sat in Needs attention forever — which is exactly what
 * made the section stop meaning anything.
 *
 * Dismiss appears on **exactly** the rows that already offer Retry, and these
 * two predicates are that rule, extracted to the JVM so the button's presence
 * can be pinned without a layout and so the UI cannot quietly drift away from
 * the Retry condition it is supposed to mirror (same reasoning as
 * [com.easemic.app.feature.download.autoRetryBudgetRemains]).
 *
 * Dismiss is destructive-but-cheap and takes no confirmation dialog: it clears
 * the queue/attention row only, and never the comic, the downloaded pages, or
 * anything in the bucket.
 */
fun downloadRowDismissible(row: DownloadQueueRow, nowMs: Long): Boolean =
    row.state == DownloadJobState.FAILED || downloadRowParked(row, nowMs)

/**
 * Sync rows mirror Retry's `cloudBound` gate as well: with no bucket bound at
 * all, A27 renders every row amber with no Retry, because binding a bucket —
 * not any per-row action — is the only thing that helps. Offering Dismiss
 * there would invite the user to throw away a backlog that is about to drain
 * on its own the moment [com.easemic.app.AppGraph]'s
 * `onBucketBound` fires.
 */
fun syncRowDismissible(row: CloudSyncQueueItem, cloudBound: Boolean): Boolean =
    cloudBound && row.stage == CloudSyncStage.FAILED

/**
 * C37-g: Retry's own gate, and the one place the two actions come apart. A
 * row failed with [ByoCloudIoCopy.RECONNECT] carries a revoked OAuth grant:
 * every retry would resolve no store and park it again, so the button is a
 * lie. Dismiss stays (the user may still want the row gone), and reconnecting
 * cloud storage revives the row through
 * [com.easemic.app.AppGraph]'s `onBucketBound` anyway.
 */
fun syncRowRetryable(row: CloudSyncQueueItem, cloudBound: Boolean): Boolean =
    cloudBound && CloudSyncFailurePolicy.canRetry(row.errorMessage)

/** Re-read at the root's Retry boundary; stale UI rows must not revive a different chapter or a running upload. */
internal fun retryCloudSeriesRows(queue: CloudSyncQueue, targets: List<CloudSyncQueueItem>, cloudBound: Boolean): Boolean {
    var retried = false
    targets.forEach { target ->
        val row = queue.get(target.id, target.chapterIdentity) ?: return@forEach
        if (row.stage != CloudSyncStage.FAILED || !syncRowRetryable(row, cloudBound)) return@forEach
        queue.upsert(row.copy(stage = CloudSyncStage.WAITING, errorMessage = null, preflightPending = false))
        retried = true
    }
    return retried
}
