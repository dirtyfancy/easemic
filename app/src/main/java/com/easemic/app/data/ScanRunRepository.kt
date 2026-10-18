package com.easemic.app.data

import com.easemic.app.model.CandidateCommitResult
import com.easemic.app.model.CandidateId
import com.easemic.app.model.DiscoveredCandidate
import com.easemic.app.model.EnumerationAttemptId
import com.easemic.app.model.RunCheckpoint
import com.easemic.app.model.RunFailure
import com.easemic.app.model.RunPreview
import com.easemic.app.model.ScanPhase
import com.easemic.app.model.ScanRun
import com.easemic.app.model.ScanRunId
import com.easemic.app.model.ScanRunState
import com.easemic.app.model.SourceId
import com.easemic.app.model.WorkRequestId
import kotlinx.coroutines.flow.Flow

interface ScanRunRepository {
    fun observeRun(runId: ScanRunId): Flow<ScanRun>
    fun observePreview(runId: ScanRunId): Flow<RunPreview>
    fun observeLatestRunsBySource(): Flow<Map<SourceId, ScanRun>>
    suspend fun run(runId: ScanRunId): ScanRun?
    suspend fun nonTerminalRuns(): List<ScanRun> = emptyList()
    suspend fun createOrResume(sourceId: SourceId): ScanRunId
    suspend fun beginEnumerationAttempt(runId: ScanRunId): EnumerationAttemptId
    suspend fun persistCandidate(
        runId: ScanRunId,
        attemptId: EnumerationAttemptId,
        candidate: DiscoveredCandidate,
    ): CandidateId
    suspend fun candidate(candidateId: CandidateId): DiscoveredCandidate?
    suspend fun nextSelectedNonTerminalCandidate(runId: ScanRunId): CandidateId?
    suspend fun setSelection(runId: ScanRunId, selected: Set<CandidateId>)
    suspend fun requestPause(runId: ScanRunId): Boolean
    suspend fun requestPauseIfCurrent(runId: ScanRunId, requestId: WorkRequestId): Boolean
    suspend fun currentWorkRequest(runId: ScanRunId): WorkRequestId?
    suspend fun associateWorkRequest(
        runId: ScanRunId,
        expectedRequestId: WorkRequestId?,
        newRequestId: WorkRequestId,
    ): Boolean
    suspend fun resumeWorkRequest(
        runId: ScanRunId,
        expectedRequestId: WorkRequestId?,
        newRequestId: WorkRequestId,
    ): Boolean = associateWorkRequest(runId, expectedRequestId, newRequestId)
    suspend fun checkpoint(runId: ScanRunId, checkpoint: RunCheckpoint)
    suspend fun transition(
        runId: ScanRunId,
        expectedStates: Set<ScanRunState>,
        newState: ScanRunState,
        phase: ScanPhase,
        failure: RunFailure? = null,
    ): Boolean
    suspend fun markCandidateOutcome(
        runId: ScanRunId,
        candidateId: CandidateId,
        result: CandidateCommitResult,
    )
    suspend fun markEnumerationComplete(
        runId: ScanRunId,
        attemptId: EnumerationAttemptId,
    ): Boolean
    suspend fun finish(runId: ScanRunId)
    suspend fun recoverAbandonedRuns(activeWorkNames: Set<String>)
}

interface CatalogCandidateCommitter {
    suspend fun commit(
        sourceId: SourceId,
        generation: Long,
        candidateId: CandidateId,
    ): CandidateCommitResult

    suspend fun reconcileMissing(sourceId: SourceId, completedGeneration: Long)
}
