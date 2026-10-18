package com.easemic.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.easemic.app.feature.collect.RefreshResult
import com.easemic.app.feature.download.BoundedPassResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plain background check. No foreground service, no [DownloadPumpWorker], no permission prompt.
 * Cloud upload is not part of this run. A cloud-intent chapter that finishes here records its
 * upload row and waits for the cloud pump at the next foreground.
 * A run that reaches the end of [doWork] records the finished timestamp, including a source
 * failure. A stop or cancellation records nothing.
 */
class CollectedSeriesRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
    private val deps: Deps,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        try {
            runCollectedSeriesRefresh(deps)
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    class Deps(
        val shouldRun: () -> Boolean,
        val refresh: suspend (deadline: Long, monotonicClock: () -> Long) -> List<RefreshResult>,
        val recordMarks: (List<RefreshResult>) -> Unit,
        val jobIdsFor: suspend (List<RefreshResult>) -> List<String>,
        val boundedPass: suspend (jobIds: List<String>, deadline: Long) -> BoundedPassResult,
        val summaryChapters: suspend (List<RefreshResult>, BoundedPassResult) -> List<SummaryChapter>,
        val notificationsEnabled: () -> Boolean,
        val postSummary: (UpdatesSummary) -> Unit,
        val recordFinished: (nowMillis: Long, foundCount: Int, savedCount: Int) -> Unit,
        val monotonicNow: () -> Long,
        val wallNow: () -> Long,
    )

    companion object {
        const val BOUND_MS = 8L * 60 * 1000
    }
}

internal suspend fun runCollectedSeriesRefresh(deps: CollectedSeriesRefreshWorker.Deps) {
    if (!deps.shouldRun()) {
        deps.recordFinished(deps.wallNow(), 0, 0)
        return
    }
    val deadline = deps.monotonicNow() + CollectedSeriesRefreshWorker.BOUND_MS
    val results = deps.refresh(deadline, deps.monotonicNow)
    deps.recordMarks(results)
    val remaining = deadline - deps.monotonicNow()
    val jobIds = if (remaining > 0L) {
        withTimeoutOrNull(remaining) { deps.jobIdsFor(results) }.orEmpty()
    } else {
        emptyList()
    }
    val bounded = if (jobIds.isEmpty() || deps.monotonicNow() >= deadline) {
        BoundedPassResult(finishedJobs = 0, remainingJobIds = jobIds)
    } else {
        deps.boundedPass(jobIds, deadline)
    }
    val chapters = deps.summaryChapters(results, bounded)
    val summary = updatesSummary(chapters)
    if (summary != null && deps.notificationsEnabled()) {
        deps.postSummary(summary)
    }
    deps.recordFinished(
        deps.wallNow(),
        chapters.size,
        chapters.count { it.saved },
    )
}
