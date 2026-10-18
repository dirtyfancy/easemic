package com.easemic.app.work

import androidx.room.withTransaction
import com.easemic.app.data.room.AppDatabase
import com.easemic.app.data.room.RoomScanRunRepository
import com.easemic.app.model.CandidateCommitResult
import com.easemic.app.model.CandidateId
import com.easemic.app.model.DiscoveredCandidate
import com.easemic.app.model.EnumerationAttemptId
import com.easemic.app.model.RunCheckpoint
import com.easemic.app.model.RunFailure
import com.easemic.app.model.ScanPhase
import com.easemic.app.model.ScanRunId
import com.easemic.app.model.ScanRunState
import com.easemic.app.model.WorkRequestId

interface FencedScanRunMutations {
    suspend fun beginEnumerationAttempt(runId: ScanRunId, requestId: WorkRequestId): EnumerationAttemptId?
    suspend fun persistCandidate(runId: ScanRunId, requestId: WorkRequestId, attemptId: EnumerationAttemptId, candidate: DiscoveredCandidate): CandidateId?
    suspend fun checkpoint(runId: ScanRunId, requestId: WorkRequestId, checkpoint: RunCheckpoint): Boolean
    suspend fun transition(runId: ScanRunId, requestId: WorkRequestId, expectedStates: Set<ScanRunState>, newState: ScanRunState, phase: ScanPhase, failure: RunFailure? = null): Boolean
    suspend fun markCandidateOutcome(runId: ScanRunId, requestId: WorkRequestId, candidateId: CandidateId, result: CandidateCommitResult): Boolean
    suspend fun retainAttemptCandidates(
        runId: ScanRunId,
        requestId: WorkRequestId,
        attemptId: EnumerationAttemptId,
        keepProviderDocumentIds: Set<String>,
    ): Boolean
    suspend fun markEnumerationComplete(runId: ScanRunId, requestId: WorkRequestId, attemptId: EnumerationAttemptId): Boolean

    /**
     * A30: commit-everything replaces the human review gate. Defaulted to `true`
     * so existing test doubles that implement this interface directly keep
     * compiling; every production path overrides it.
     */
    suspend fun selectAllRecoverableCandidates(runId: ScanRunId, requestId: WorkRequestId): Boolean = true
    suspend fun finishAndClearCurrentRequest(runId: ScanRunId, requestId: WorkRequestId, terminalState: ScanRunState, failure: RunFailure? = null): Boolean
}

class RoomFencedScanRunMutations(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val beforeTransaction: suspend () -> Unit = {},
) : FencedScanRunMutations {
    private val dao = database.scanRunDao()
    private val runs = RoomScanRunRepository(database, clock)

    private suspend fun isCurrent(runId: ScanRunId, requestId: WorkRequestId) =
        dao.currentWorkRequestId(runId.value) == requestId.value

    override suspend fun beginEnumerationAttempt(runId: ScanRunId, requestId: WorkRequestId): EnumerationAttemptId? {
        beforeTransaction()
        return database.withTransaction {
            if (
                dao.isCurrentWorkRequestEligible(
                    runId.value,
                    requestId.value,
                    listOf(ScanRunState.ENQUEUED.name, ScanRunState.RUNNING.name),
                ) == 0
            ) {
                return@withTransaction null
            }
            val existing = dao.run(runId.value)
            if (
                existing != null &&
                existing.enumerationAttemptId != null &&
                !existing.enumerationComplete
            ) {
                return@withTransaction EnumerationAttemptId(existing.enumerationAttemptId)
            }
            runs.beginEnumerationAttempt(runId)
        }
    }

    override suspend fun persistCandidate(runId: ScanRunId, requestId: WorkRequestId, attemptId: EnumerationAttemptId, candidate: DiscoveredCandidate): CandidateId? {
        beforeTransaction()
        return database.withTransaction {
            if (
                dao.isCurrentWorkRequestAttemptEligible(
                    runId.value,
                    requestId.value,
                    attemptId.value,
                ) > 0
            ) {
                runs.persistCandidate(runId, attemptId, candidate)
            } else {
                null
            }
        }
    }

    override suspend fun checkpoint(runId: ScanRunId, requestId: WorkRequestId, checkpoint: RunCheckpoint) =
        dao.fencedCheckpoint(runId.value, requestId.value, checkpoint.lastCandidateId?.value, checkpoint.processedCount, clock()) > 0

    override suspend fun transition(runId: ScanRunId, requestId: WorkRequestId, expectedStates: Set<ScanRunState>, newState: ScanRunState, phase: ScanPhase, failure: RunFailure?): Boolean {
        require(newState !in TERMINAL)
        if (
            newState == ScanRunState.PAUSED &&
            ScanRunState.PAUSE_REQUESTED in expectedStates &&
            dao.fencedAcknowledgePause(
                runId.value,
                requestId.value,
                phase.name,
                failure?.problem?.name,
                failure?.recoveryAction?.name ?: "NONE",
                clock(),
            ) > 0
        ) {
            return true
        }
        return dao.fencedTransition(runId.value, requestId.value, expectedStates.map(ScanRunState::name), newState.name, phase.name, failure?.problem?.name, failure?.recoveryAction?.name ?: "NONE", clock()) > 0
    }

    override suspend fun markCandidateOutcome(runId: ScanRunId, requestId: WorkRequestId, candidateId: CandidateId, result: CandidateCommitResult): Boolean {
        beforeTransaction()
        return database.withTransaction {
            if (!isCurrent(runId, requestId)) return@withTransaction false
            if (
                dao.markInitialRecoverableCandidateOutcome(
                    runId.value,
                    candidateId.value,
                    result.outcome.name,
                ) == 0
            ) {
                return@withTransaction false
            }
            dao.refreshPreviewCounts(runId.value, clock())
            true
        }
    }

    override suspend fun retainAttemptCandidates(
        runId: ScanRunId,
        requestId: WorkRequestId,
        attemptId: EnumerationAttemptId,
        keepProviderDocumentIds: Set<String>,
    ): Boolean {
        beforeTransaction()
        return database.withTransaction {
            if (
                dao.isCurrentWorkRequestAttemptEligible(
                    runId.value,
                    requestId.value,
                    attemptId.value,
                ) == 0
            ) {
                return@withTransaction false
            }
            runs.retainAttemptCandidates(runId, attemptId, keepProviderDocumentIds)
            true
        }
    }

    override suspend fun markEnumerationComplete(runId: ScanRunId, requestId: WorkRequestId, attemptId: EnumerationAttemptId): Boolean {
        beforeTransaction()
        return database.withTransaction {
            isCurrent(runId, requestId) && runs.markEnumerationComplete(runId, attemptId)
        }
    }

    override suspend fun selectAllRecoverableCandidates(
        runId: ScanRunId,
        requestId: WorkRequestId,
    ): Boolean {
        beforeTransaction()
        return database.withTransaction {
            if (!isCurrent(runId, requestId)) return@withTransaction false
            dao.selectAllRecoverableCandidates(runId.value)
            dao.refreshPreviewCounts(runId.value, clock())
            true
        }
    }

    override suspend fun finishAndClearCurrentRequest(runId: ScanRunId, requestId: WorkRequestId, terminalState: ScanRunState, failure: RunFailure?): Boolean {
        beforeTransaction()
        return database.withTransaction {
            require(terminalState in TERMINAL)
            if (dao.fencedTerminalize(runId.value, requestId.value, terminalState.name, failure?.problem?.name, failure?.recoveryAction?.name ?: "NONE", clock()) == 0) return@withTransaction false
            dao.clearWorkRequest(runId.value, requestId.value)
            dao.run(runId.value)?.let { dao.clearActiveRun(it.sourceId, it.id) }
            true
        }
    }

    private companion object {
        val TERMINAL = setOf(ScanRunState.COMPLETE, ScanRunState.PARTIAL, ScanRunState.FAILED)
    }
}
