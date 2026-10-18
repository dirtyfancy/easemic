package com.easemic.app.work

import androidx.concurrent.futures.await
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.easemic.app.data.ScanRunRepository
import com.easemic.app.data.SourceRepository
import com.easemic.app.model.ScanRun
import com.easemic.app.model.ScanRunState
import com.easemic.app.model.SourceGrantState
import com.easemic.app.model.WorkRequestId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Rebuilds the Room/WorkManager relationship after application-process
 * recreation. Each run is isolated so one malformed or unavailable row cannot
 * prevent recovery of the remaining durable runs.
 */
class StartupRunRecoveryCoordinator(
    private val workManager: WorkManager,
    private val runs: ScanRunRepository,
    private val sources: SourceRepository,
    mutations: FencedScanRunMutations,
    private val scheduler: WorkManagerScanWorkScheduler,
    private val onRunFailure: (ScanRun, Throwable) -> Unit = { _, _ -> },
) {
    private val recovery = RunRecovery(runs, sources, mutations, scheduler)
    private val reconciliation = Mutex()

    suspend fun reconcileAll() = reconciliation.withLock {
        reconcileRuns()
    }

    suspend fun observeWorkChanges() {
        reconcileAll()
        workManager.getWorkInfosByTagFlow(WorkManagerScanWorkScheduler.COMMON_SCAN_TAG)
            .collect { workInfos ->
                reconciliation.withLock {
                    reconcileRuns(workInfos.associateBy { it.id })
                }
            }
    }

    private suspend fun reconcileRuns(
        observedWorkInfos: Map<UUID, WorkInfo>? = null,
    ) {
        runs.nonTerminalRuns().forEach { run ->
            try {
                reconcile(run, observedWorkInfos)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onRunFailure(run, failure)
            }
        }
    }

    private suspend fun reconcile(
        run: ScanRun,
        observedWorkInfos: Map<UUID, WorkInfo>?,
    ) {
        if (sources.source(run.sourceId)?.grantState != SourceGrantState.READY) return
        val requestId = runs.currentWorkRequest(run.id)
        val info = requestId?.let { request ->
            val uuid = UUID.fromString(request.value)
            observedWorkInfos?.get(uuid) ?: workInfo(request)
        }
        if (run.state == ScanRunState.PAUSE_REQUESTED) {
            if (requestId == null) return
            if (info == null || isStopped(info)) {
                requestId?.let {
                    recovery.recoverStopped(run.id, it, PlatformStopKind.CANCELLED_BY_APP)
                }
            } else {
                scheduler.cancelIfCurrent(run.id, requestId)
                val stopped = workInfo(requestId)
                if (stopped == null || isStopped(stopped)) {
                    recovery.recoverStopped(
                        run.id,
                        requestId,
                        PlatformStopKind.CANCELLED_BY_APP,
                    )
                }
            }
            return
        }
        if (RunRecoveryPolicy.isIntentionallyIdle(run.state)) return
        if (requestId == null) {
            recovery.reconcile(run.id)
            return
        }
        if (info == null) {
            recovery.reconcile(run.id)
            return
        }
        if (!isStopped(info)) {
            recovery.reconcile(run.id)
            return
        }
        recovery.recoverStopped(
            run.id,
            requestId,
            mapStopReason(
                workInfoPresent = true,
                state = info.state,
                stopReason = info.stopReason,
            ),
        )
    }

    private fun isStopped(info: WorkInfo): Boolean =
        info.state.isFinished || info.stopReason != WorkInfo.STOP_REASON_NOT_STOPPED

    private suspend fun workInfo(requestId: WorkRequestId): WorkInfo? =
        withTimeout(WORK_INFO_TIMEOUT_MILLIS) {
            workManager.getWorkInfoById(UUID.fromString(requestId.value)).await()
        }

    private companion object {
        const val WORK_INFO_TIMEOUT_MILLIS = 5_000L
    }
}
