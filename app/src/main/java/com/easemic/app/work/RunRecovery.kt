package com.easemic.app.work

import com.easemic.app.data.ScanRunRepository
import com.easemic.app.data.SourceRepository
import com.easemic.app.model.RecoveryAction
import com.easemic.app.model.RunFailure
import com.easemic.app.model.RunProblem
import com.easemic.app.model.ScanPhase
import com.easemic.app.model.ScanRunId
import com.easemic.app.model.ScanRunState
import com.easemic.app.model.SourceGrantState
import com.easemic.app.model.WorkRequestId

class RunRecovery(
    private val runs: ScanRunRepository,
    private val sources: SourceRepository,
    private val mutations: FencedScanRunMutations,
    private val scheduler: WorkManagerScanWorkScheduler,
) {
    suspend fun reconcile(runId: ScanRunId) {
        val run = runs.run(runId) ?: return
        if (RunRecoveryPolicy.isTerminal(run.state)) return
        if (RunRecoveryPolicy.isIntentionallyIdle(run.state)) return
        if (sources.source(run.sourceId)?.grantState != SourceGrantState.READY) return
        scheduler.reconcile(runId)
    }

    suspend fun recoverStopped(
        runId: ScanRunId,
        requestId: WorkRequestId,
        stop: PlatformStopKind,
    ): Boolean {
        val failure = if (stop == PlatformStopKind.CANCELLED_BY_APP) {
            null
        } else {
            RunFailure(RunProblem.PLATFORM_STOPPED, RecoveryAction.RETRY)
        }
        return mutations.transition(
            runId,
            requestId,
            setOf(ScanRunState.ENQUEUED, ScanRunState.RUNNING, ScanRunState.IMPORTING, ScanRunState.PAUSE_REQUESTED),
            ScanRunState.PAUSED,
            runs.run(runId)?.phase ?: ScanPhase.VALIDATE,
            failure,
        )
    }
}
