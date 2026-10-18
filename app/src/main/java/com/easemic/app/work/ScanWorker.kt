package com.easemic.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.easemic.app.model.ScanRunId
import com.easemic.app.model.WorkRequestId
import kotlinx.coroutines.CancellationException

class ScanWorker(
    appContext: Context,
    params: WorkerParameters,
    private val engine: ScanEngineRunner?,
    private val foregroundInfoFactory: ((ScanRunId, WorkRequestId, ImportWorkProgress) -> ForegroundInfo)?,
    private val recoverForegroundFailure: (suspend (ScanRunId, WorkRequestId) -> Boolean)? = null,
    private val foregroundSetter: (suspend (ForegroundInfo) -> Unit)? = null,
) : CoroutineWorker(appContext, params) {
    constructor(appContext: Context, params: WorkerParameters) :
        this(appContext, params, null, null)

    override suspend fun doWork(): Result {
        val runId = inputData.getString(WorkManagerScanWorkScheduler.KEY_RUN_ID)?.let(::ScanRunId)
            ?: return Result.failure()
        val requestId = WorkRequestId(id.toString())
        val userInitiated = inputData.getBoolean(
            WorkManagerScanWorkScheduler.KEY_USER_INITIATED,
            false,
        )
        try {
            if (userInitiated) {
                foregroundInfoFactory?.invoke(
                    runId,
                    requestId,
                    ImportWorkProgress(completed = 0, total = null),
                )?.let { info ->
                    foregroundSetter?.invoke(info) ?: setForeground(info)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return if (recoverForegroundFailure?.invoke(runId, requestId) == true) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
        val engineResult = try {
            engine?.advance(runId, requestId) { progress ->
                if (!userInitiated) return@advance
                try {
                    foregroundInfoFactory?.invoke(runId, requestId, progress)?.let { info ->
                        foregroundSetter?.invoke(info) ?: setForeground(info)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    throw ForegroundProgressUpdateException(failure)
                }
            } ?: EngineResult.Retry
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ForegroundProgressUpdateException) {
            return if (recoverForegroundFailure?.invoke(runId, requestId) == true) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
        return when (engineResult) {
            EngineResult.Complete, EngineResult.Partial, EngineResult.WaitingForReview -> Result.success()
            EngineResult.Paused, EngineResult.Stale -> Result.failure()
            EngineResult.Retry -> Result.retry()
        }
    }

    private class ForegroundProgressUpdateException(cause: Exception) : Exception(cause)
}
