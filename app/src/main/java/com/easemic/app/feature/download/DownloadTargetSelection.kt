package com.easemic.app.feature.download

/**
 * S6-A Task 3 (owner setting "max concurrent transfers"): picks which rows
 * of an already-FIFO-sorted, already-filtered [consumptionQueue] (see
 * [DownloadCore.consumptionQueue] / [filterConsumptionQueueFifo] — exactly
 * [DownloadJobState.WAIT] + [DownloadJobState.DOWNLOAD], resume-window
 * already applied) a [DownloadWorkerLoop.tick] pass should fan out to this
 * pass, given [slots] concurrent worker slots.
 *
 * Mixed-fill, not state-exclusive: a clean row already mid-fetch
 * ([DownloadJobState.DOWNLOAD] with no recorded failure) keeps its slot —
 * fetches are never pre-empted. Remaining slots are filled by
 * [DownloadJobState.WAIT] rows in FIFO order, and only then by parked/retry
 * DOWNLOAD rows. This prevents one repeatedly failing gallery from reclaiming
 * the only slot ahead of ordinary waiting work on every tick. `slots <= 0` is
 * clamped to 1 so a bad setting still makes progress.
 *
 * Defensively re-filters by exact state (rather than trusting the caller's
 * queue is already scoped to WAIT/DOWNLOAD) so a row in any terminal state
 * (FINISH/FAILED/SEALED/GONE/NONE) can never be selected even if it leaks
 * into [consumptionQueue].
 */
fun selectDownloadTargets(consumptionQueue: List<DownloadJob>, slots: Int): List<DownloadJob> {
    val cap = slots.coerceAtLeast(1)
    val downloading = consumptionQueue.filter {
        it.state == DownloadJobState.DOWNLOAD && it.failureClass == null
    }
    val waiting = consumptionQueue.filter { it.state == DownloadJobState.WAIT }
    val retries = consumptionQueue.filter {
        it.state == DownloadJobState.DOWNLOAD && it.failureClass != null
    }
    return (downloading + waiting + retries).take(cap)
}
