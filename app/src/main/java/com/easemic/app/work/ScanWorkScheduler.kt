package com.easemic.app.work

import androidx.concurrent.futures.await
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.easemic.app.data.ScanRunRepository
import com.easemic.app.model.ScanRunId
import com.easemic.app.model.ScanRunState
import com.easemic.app.model.WorkRequestId
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import kotlinx.coroutines.delay

internal suspend fun <T> awaitWorkManagerFuture(future: ListenableFuture<T>): T =
    future.await()

interface ScanWorkScheduler {
    suspend fun enqueue(runId: ScanRunId, userInitiated: Boolean): WorkRequestId
    suspend fun requestPause(runId: ScanRunId): Boolean
    suspend fun resume(runId: ScanRunId): WorkRequestId
    suspend fun cancelIfCurrent(runId: ScanRunId, requestId: WorkRequestId): Boolean
}

class WorkManagerScanWorkScheduler(
    private val workManager: WorkManager,
    private val runs: ScanRunRepository,
    private val afterPriorTerminal: suspend (WorkRequestId, WorkInfo?) -> Unit = { _, _ -> },
    private val beforeReplacementReserved: suspend (ScanRunId) -> Unit = {},
    private val afterSchedulePreflight: suspend () -> Unit = {},
) : ScanWorkScheduler {
    override suspend fun enqueue(runId: ScanRunId, userInitiated: Boolean): WorkRequestId {
        requireNonTerminal(runId)
        val current = runs.currentWorkRequest(runId)
        val winner = unfinishedWinner(runId)
        afterSchedulePreflight()
        if (winner != null) {
            return repairAndVerify(runId, current, winner)
        }
        if (current != null) {
            val info = workInfo(current)
            if (info == null) {
                return submitAndConverge(runId, current, userInitiated)
            }
            if (!info.state.isFinished) {
                return verifyBoth(runId, current)
            }
        }
        val reserved = newRequestId()
        if (!runs.associateWorkRequest(runId, current, reserved)) {
            return convergeAfterReservationLoss(runId)
        }
        return submitAndConverge(runId, reserved, userInitiated)
    }

    override suspend fun requestPause(runId: ScanRunId): Boolean {
        val requestId = runs.currentWorkRequest(runId) ?: return false
        if (!runs.requestPauseIfCurrent(runId, requestId)) return false
        await(workManager.cancelWorkById(UUID.fromString(requestId.value)))
        return true
    }

    override suspend fun resume(runId: ScanRunId): WorkRequestId {
        val run = requireNotNull(runs.run(runId))
        check(run.state in RESUMABLE) {
            "Only a paused or pause-requested run can be resumed."
        }
        var prior = runs.currentWorkRequest(runId)
        unfinishedWinner(runId)?.let { winner ->
            if (winner != prior) {
                prior = repairAndVerify(runId, prior, winner)
            }
        }
        if (prior != null) {
            await(workManager.cancelWorkById(UUID.fromString(requireNotNull(prior).value)))
            val terminalInfo = workInfo(requireNotNull(prior))
            check(terminalInfo?.state?.isFinished != false) {
                "The prior request must be terminal before reserving its replacement."
            }
            afterPriorTerminal(requireNotNull(prior), terminalInfo)
        }
        beforeReplacementReserved(runId)
        val replacement = newRequestId()
        if (!runs.resumeWorkRequest(runId, prior, replacement)) {
            return convergeAfterReservationLoss(runId)
        }
        return submitAndConverge(runId, replacement, userInitiated = true)
    }

    override suspend fun cancelIfCurrent(runId: ScanRunId, requestId: WorkRequestId): Boolean {
        if (runs.currentWorkRequest(runId) != requestId) return false
        await(workManager.cancelWorkById(UUID.fromString(requestId.value)))
        return true
    }

    suspend fun reconcile(runId: ScanRunId): WorkRequestId = enqueue(runId, false)

    private suspend fun submitAndConverge(
        runId: ScanRunId,
        reserved: WorkRequestId,
        userInitiated: Boolean,
    ): WorkRequestId {
        await(
            workManager.enqueueUniqueWork(
                workName(runId),
                ExistingWorkPolicy.KEEP,
                buildRequest(runId, reserved, userInitiated),
            ),
        )
        val winner = unfinishedWinner(runId)
            ?: workInfo(reserved)?.takeUnless { it.state.isFinished }?.let { reserved }
            ?: error("WorkManager accepted no unfinished request for ${runId.value}.")
        return repairAndVerify(runId, reserved, winner)
    }

    internal fun buildRequest(
        runId: ScanRunId,
        requestId: WorkRequestId,
        userInitiated: Boolean,
    ) = OneTimeWorkRequest.Builder(ScanWorker::class.java)
        .setId(UUID.fromString(requestId.value))
        .setInputData(
            workDataOf(
                KEY_RUN_ID to runId.value,
                KEY_USER_INITIATED to userInitiated,
            ),
        )
        .addTag(COMMON_SCAN_TAG)
        .addTag(workName(runId))
        .apply {
            if (userInitiated) {
                setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
        }
        .build()

    private suspend fun repairAndVerify(
        runId: ScanRunId,
        expected: WorkRequestId?,
        winner: WorkRequestId,
    ): WorkRequestId {
        if (expected != winner && !runs.associateWorkRequest(runId, expected, winner)) {
            val observed = runs.currentWorkRequest(runId)
            if (observed != winner) {
                return verifyBoth(runId, requireNotNull(observed))
            }
        }
        return verifyBoth(runId, winner)
    }

    private suspend fun verifyBoth(
        runId: ScanRunId,
        requestId: WorkRequestId,
    ): WorkRequestId {
        check(runs.currentWorkRequest(runId) == requestId) {
            "Request ${requestId.value} is not current in Room."
        }
        check(workInfo(requestId)?.state?.isFinished == false) {
            "Request ${requestId.value} is not unfinished in WorkManager."
        }
        return requestId
    }

    private suspend fun convergeAfterReservationLoss(runId: ScanRunId): WorkRequestId {
        repeat(CONVERGENCE_ATTEMPTS) {
            val current = runs.currentWorkRequest(runId)
            val winner = unfinishedWinner(runId)
            if (winner != null) {
                return repairAndVerify(runId, current, winner)
            }
            if (current != null && workInfo(current)?.state?.isFinished == false) {
                return verifyBoth(runId, current)
            }
            delay(CONVERGENCE_DELAY_MILLIS)
        }
        error("A concurrent scheduler did not produce a Room/WorkManager winner.")
    }

    private suspend fun requireNonTerminal(runId: ScanRunId) {
        val run = requireNotNull(runs.run(runId))
        check(run.state !in TERMINAL) { "Cannot schedule terminal run ${runId.value}." }
    }

    private suspend fun workInfo(requestId: WorkRequestId): WorkInfo? =
        awaitWorkManagerFuture(
            workManager.getWorkInfoById(UUID.fromString(requestId.value)),
        )

    private suspend fun await(operation: Operation) {
        awaitWorkManagerFuture(operation.result)
    }

    private suspend fun unfinishedWinner(runId: ScanRunId): WorkRequestId? =
        awaitWorkManagerFuture(
            workManager.getWorkInfosForUniqueWork(workName(runId)),
        ).firstOrNull { !it.state.isFinished }
            ?.id?.toString()?.let(::WorkRequestId)

    private fun newRequestId() = WorkRequestId(UUID.randomUUID().toString())

    companion object {
        const val KEY_RUN_ID = "run_id"
        const val KEY_USER_INITIATED = "user_initiated"
        const val COMMON_SCAN_TAG = "private-scan-work"
        fun workName(runId: ScanRunId) = "scan-run-${runId.value}"
        private val TERMINAL =
            setOf(ScanRunState.COMPLETE, ScanRunState.PARTIAL, ScanRunState.FAILED)
        private val RESUMABLE =
            setOf(ScanRunState.PAUSED, ScanRunState.PAUSE_REQUESTED)
        private const val CONVERGENCE_ATTEMPTS = 100
        private const val CONVERGENCE_DELAY_MILLIS = 10L
    }
}
