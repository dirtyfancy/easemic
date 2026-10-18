package com.easemic.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.easemic.app.model.ScanRunId
import com.easemic.app.model.WorkRequestId
import kotlinx.coroutines.delay

class Api35TimeoutWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val runId = inputData.getString(WorkManagerScanWorkScheduler.KEY_RUN_ID)?.let(::ScanRunId)
            ?: return Result.failure()
        setForeground(ImportForegroundInfo(applicationContext).create(runId, WorkRequestId(id.toString()), 0))
        while (!isStopped) {
            delay(1_000)
        }
        return Result.failure()
    }
}
