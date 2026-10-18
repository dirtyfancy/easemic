package com.easemic.app.work

import com.easemic.app.data.room.AppDatabase
import com.easemic.app.data.room.CatalogCandidateCommitter
import com.easemic.app.model.CandidateCommitResult
import com.easemic.app.model.CandidateId
import com.easemic.app.model.ScanRunId
import com.easemic.app.model.ScanRunState
import com.easemic.app.model.SourceId
import com.easemic.app.model.WorkRequestId

interface FencedCatalogMutations {
    suspend fun commit(runId: ScanRunId, requestId: WorkRequestId, sourceId: SourceId, generation: Long, candidateId: CandidateId): CandidateCommitResult?
    suspend fun reconcileMissing(runId: ScanRunId, requestId: WorkRequestId, sourceId: SourceId, completedGeneration: Long): Boolean
}

class RoomFencedCatalogMutations(
    private val database: AppDatabase,
    beforeTransaction: suspend () -> Unit = {},
) : FencedCatalogMutations {
    private val dao = database.scanRunDao()
    private val delegate = CatalogCandidateCommitter(
        database,
        beforeTransaction = beforeTransaction,
    )
    private val beforeTransaction = beforeTransaction

    override suspend fun commit(runId: ScanRunId, requestId: WorkRequestId, sourceId: SourceId, generation: Long, candidateId: CandidateId): CandidateCommitResult? =
        delegate.commitIfCurrent(sourceId, generation, candidateId) {
            dao.isCurrentWorkRequestEligible(
                runId.value,
                requestId.value,
                listOf(ScanRunState.IMPORTING.name),
            ) > 0
        }

    override suspend fun reconcileMissing(
        runId: ScanRunId,
        requestId: WorkRequestId,
        sourceId: SourceId,
        completedGeneration: Long,
    ): Boolean {
        beforeTransaction()
        return delegate.reconcileMissingIfCurrent(sourceId, completedGeneration) {
            dao.isCurrentWorkRequestEligible(
                runId.value,
                requestId.value,
                listOf(ScanRunState.IMPORTING.name),
            ) > 0
        }
    }
}
