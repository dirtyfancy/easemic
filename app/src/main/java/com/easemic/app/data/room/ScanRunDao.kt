package com.easemic.app.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanRunDao {
    @Query("SELECT requestId FROM scan_work_requests WHERE runId = :runId")
    suspend fun currentWorkRequestId(runId: String): String?

    @Query(
        """
        SELECT COUNT(*) FROM scan_runs
        JOIN scan_work_requests ON scan_work_requests.runId = scan_runs.id
        WHERE scan_runs.id = :runId
          AND scan_work_requests.requestId = :requestId
          AND scan_runs.state IN (:eligibleStates)
          AND scan_runs.pauseRequested = 0
        """,
    )
    suspend fun isCurrentWorkRequestEligible(
        runId: String,
        requestId: String,
        eligibleStates: List<String>,
    ): Int

    @Query(
        """
        SELECT COUNT(*) FROM scan_runs
        JOIN scan_work_requests ON scan_work_requests.runId = scan_runs.id
        WHERE scan_runs.id = :runId
          AND scan_work_requests.requestId = :requestId
          AND scan_runs.state = 'RUNNING'
          AND scan_runs.pauseRequested = 0
          AND scan_runs.enumerationAttemptId = :attemptId
        """,
    )
    suspend fun isCurrentWorkRequestAttemptEligible(
        runId: String,
        requestId: String,
        attemptId: String,
    ): Int

    @Insert
    suspend fun insertWorkRequest(request: ScanWorkRequestEntity)

    @Query(
        """
        UPDATE scan_work_requests
        SET requestId = :newRequestId, updatedAtMillis = :updatedAtMillis
        WHERE runId = :runId AND requestId = :expectedRequestId
        """,
    )
    suspend fun replaceWorkRequest(
        runId: String,
        expectedRequestId: String,
        newRequestId: String,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE scan_runs
        SET state = CASE
              WHEN phase = 'REVIEW' THEN 'WAITING_FOR_REVIEW'
              WHEN phase IN ('COMMIT', 'RECONCILE') THEN 'IMPORTING'
              ELSE 'ENQUEUED'
            END,
            pauseRequested = 0,
            problem = NULL,
            recoveryAction = 'NONE',
            updatedAtMillis = :updatedAtMillis
        WHERE id = :runId
          AND state IN ('PAUSED', 'PAUSE_REQUESTED')
          AND EXISTS (
            SELECT 1 FROM scan_work_requests
            WHERE scan_work_requests.runId = scan_runs.id
              AND scan_work_requests.requestId = :requestId
          )
        """,
    )
    suspend fun reactivateForResume(
        runId: String,
        requestId: String,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE scan_runs SET state = 'PAUSE_REQUESTED', pauseRequested = 1,
          updatedAtMillis = :updatedAtMillis
        WHERE id = :runId
          AND state NOT IN ('PAUSE_REQUESTED', 'PAUSED', 'COMPLETE', 'PARTIAL', 'FAILED')
          AND EXISTS (
            SELECT 1 FROM scan_work_requests
            WHERE scan_work_requests.runId = scan_runs.id
              AND scan_work_requests.requestId = :requestId
          )
        """,
    )
    suspend fun requestPauseIfCurrent(
        runId: String,
        requestId: String,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE scan_runs SET state = :state, phase = :phase, problem = :problem,
          recoveryAction = :recoveryAction, updatedAtMillis = :updatedAtMillis
        WHERE id = :runId
          AND state IN (:expectedStates)
          AND pauseRequested = 0
          AND EXISTS (
            SELECT 1 FROM scan_work_requests
            WHERE scan_work_requests.runId = scan_runs.id
              AND scan_work_requests.requestId = :requestId
          )
        """,
    )
    suspend fun fencedTransition(runId: String, requestId: String, expectedStates: List<String>, state: String, phase: String, problem: String?, recoveryAction: String, updatedAtMillis: Long): Int

    @Query(
        """
        UPDATE scan_runs SET state = 'PAUSED', pauseRequested = 0, phase = :phase,
          problem = :problem, recoveryAction = :recoveryAction,
          updatedAtMillis = :updatedAtMillis
        WHERE id = :runId
          AND state = 'PAUSE_REQUESTED'
          AND pauseRequested = 1
          AND EXISTS (
            SELECT 1 FROM scan_work_requests
            WHERE scan_work_requests.runId = scan_runs.id
              AND scan_work_requests.requestId = :requestId
          )
        """,
    )
    suspend fun fencedAcknowledgePause(
        runId: String,
        requestId: String,
        phase: String,
        problem: String?,
        recoveryAction: String,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE scan_runs SET checkpointLastCandidateId = :lastCandidateId,
          checkpointProcessedCount = :processedCount, updatedAtMillis = :updatedAtMillis
        WHERE id = :runId
          AND EXISTS (
            SELECT 1 FROM scan_work_requests
            WHERE scan_work_requests.runId = scan_runs.id
              AND scan_work_requests.requestId = :requestId
          )
        """,
    )
    suspend fun fencedCheckpoint(runId: String, requestId: String, lastCandidateId: Long?, processedCount: Int, updatedAtMillis: Long): Int

    @Query(
        """
        UPDATE scan_runs SET state = :terminalState, problem = :problem,
          recoveryAction = :recoveryAction, updatedAtMillis = :updatedAtMillis
        WHERE id = :runId
          AND state NOT IN ('COMPLETE', 'PARTIAL', 'FAILED')
          AND state NOT IN ('PAUSE_REQUESTED', 'PAUSED')
          AND pauseRequested = 0
          AND EXISTS (
            SELECT 1 FROM scan_work_requests
            WHERE scan_work_requests.runId = scan_runs.id
              AND scan_work_requests.requestId = :requestId
          )
        """,
    )
    suspend fun fencedTerminalize(runId: String, requestId: String, terminalState: String, problem: String?, recoveryAction: String, updatedAtMillis: Long): Int

    @Query("DELETE FROM scan_work_requests WHERE runId = :runId AND requestId = :requestId")
    suspend fun clearWorkRequest(runId: String, requestId: String): Int

    @Query("DELETE FROM scan_work_requests WHERE runId = :runId")
    suspend fun clearAnyWorkRequest(runId: String): Int

    @Query("SELECT * FROM scan_runs ORDER BY updatedAtMillis, id")
    fun observeRunsForSourceProjection(): Flow<List<ScanRunEntity>>

    @Query("SELECT * FROM scan_runs WHERE sourceId = :sourceId ORDER BY generation DESC, id DESC LIMIT 1")
    suspend fun latestRunForSource(sourceId: Long): ScanRunEntity?

    @Query("SELECT active_scan_runs.runId FROM active_scan_runs WHERE sourceId = :sourceId")
    suspend fun activeRunId(sourceId: Long): String?

    @Query("SELECT COUNT(*) FROM active_scan_runs WHERE sourceId = :sourceId")
    suspend fun activeRunCount(sourceId: Long): Int

    @Query("SELECT COUNT(*) FROM scan_runs WHERE sourceId = :sourceId AND state NOT IN ('COMPLETE', 'PARTIAL', 'FAILED')")
    suspend fun nonTerminalRunCount(sourceId: Long): Int

    @Query("UPDATE scan_runs SET state = 'PAUSE_REQUESTED', pauseRequested = 1, updatedAtMillis = :updatedAtMillis WHERE id = :runId AND state IN ('ENQUEUED', 'RUNNING', 'IMPORTING', 'WAITING_FOR_REVIEW') AND pauseRequested = 0")
    suspend fun requestPauseIfActive(runId: String, updatedAtMillis: Long): Int

    @androidx.room.Insert
    suspend fun insertRun(run: ScanRunEntity)

    @androidx.room.Insert
    suspend fun setActiveRun(active: ActiveScanRunEntity)

    @Query("UPDATE scan_runs SET state = 'COMPLETE', updatedAtMillis = :updatedAtMillis WHERE id = :runId")
    suspend fun markComplete(runId: String, updatedAtMillis: Long)

    @Query(
        """
        UPDATE scan_runs SET state = 'COMPLETE', updatedAtMillis = :updatedAtMillis
        WHERE id = :runId
          AND enumerationComplete = 1
          AND reviewAttemptId IS NOT NULL
          AND NOT EXISTS (
            SELECT 1 FROM scan_candidates
            WHERE scan_candidates.runId = scan_runs.id
              AND scan_candidates.enumerationAttemptId = scan_runs.reviewAttemptId
              AND scan_candidates.selected = 1
              AND scan_candidates.outcome IN ('PENDING', 'LOW_CONFIDENCE')
          )
        """,
    )
    suspend fun markCompleteIfEligible(runId: String, updatedAtMillis: Long): Int

    @Query("SELECT * FROM scan_runs WHERE state NOT IN ('COMPLETE', 'PARTIAL', 'FAILED')")
    suspend fun nonTerminalRuns(): List<ScanRunEntity>

    @Query("UPDATE scan_runs SET state = 'PAUSED', pauseRequested = 0, problem = 'PLATFORM_STOPPED', recoveryAction = 'RETRY', updatedAtMillis = :updatedAtMillis WHERE id IN (:runIds) AND state NOT IN ('COMPLETE', 'PARTIAL', 'FAILED')")
    suspend fun pauseMissingWorkRuns(runIds: List<String>, updatedAtMillis: Long)

    @Query("DELETE FROM active_scan_runs WHERE sourceId = :sourceId AND runId = :runId")
    suspend fun clearActiveRun(sourceId: Long, runId: String)

    @Query(
        """
        UPDATE scan_runs
        SET state = 'FAILED',
            pauseRequested = 0,
            problem = 'SOURCE_CHANGED',
            recoveryAction = 'RETRY',
            updatedAtMillis = :updatedAtMillis
        WHERE id = :runId
          AND sourceId = :sourceId
          AND state NOT IN ('COMPLETE', 'PARTIAL', 'FAILED')
          AND EXISTS (
            SELECT 1 FROM active_scan_runs
            WHERE active_scan_runs.sourceId = :sourceId
              AND active_scan_runs.runId = :runId
          )
        """,
    )
    suspend fun failActiveRunForAdapterChange(
        sourceId: Long,
        runId: String,
        updatedAtMillis: Long,
    ): Int

    @Query("SELECT * FROM scan_runs WHERE id = :runId")
    fun observeRun(runId: String): Flow<ScanRunEntity?>

    @Query(
        """
        SELECT scan_candidates.* FROM scan_candidates
        JOIN scan_runs ON scan_runs.id = scan_candidates.runId
        WHERE scan_candidates.runId = :runId
          AND scan_candidates.enumerationAttemptId =
            COALESCE(scan_runs.reviewAttemptId, scan_runs.enumerationAttemptId)
        ORDER BY scan_candidates.id
        """,
    )
    suspend fun previewCandidates(runId: String): List<ScanCandidateEntity>

    @Query("SELECT * FROM scan_runs WHERE id = :runId")
    suspend fun run(runId: String): ScanRunEntity?

    @Query("SELECT * FROM scan_runs WHERE sourceId = :sourceId AND generation = :generation LIMIT 1")
    suspend fun runForGeneration(sourceId: Long, generation: Long): ScanRunEntity?

    @Query(
        """
        SELECT scan_candidates.* FROM scan_candidates
        JOIN scan_runs ON scan_runs.id = scan_candidates.runId
        WHERE scan_candidates.runId = :runId
          AND scan_candidates.enumerationAttemptId =
            COALESCE(scan_runs.reviewAttemptId, scan_runs.enumerationAttemptId)
        ORDER BY scan_candidates.id
        """,
    )
    fun observePreviewCandidates(runId: String): Flow<List<ScanCandidateEntity>>

    @Query(
        """
        UPDATE scan_runs SET enumerationAttemptId = :attemptId, enumerationComplete = 0,
          phase = 'ENUMERATE', state = 'RUNNING', updatedAtMillis = :updatedAtMillis
        WHERE id = :runId
          AND state NOT IN ('COMPLETE', 'PARTIAL', 'FAILED')
          AND state NOT IN ('PAUSE_REQUESTED', 'PAUSED')
          AND pauseRequested = 0
          AND EXISTS (
            SELECT 1 FROM active_scan_runs
            WHERE active_scan_runs.sourceId = scan_runs.sourceId
              AND active_scan_runs.runId = scan_runs.id
          )
        """,
    )
    suspend fun beginEnumerationAttempt(runId: String, attemptId: String, updatedAtMillis: Long): Int

    @Query("UPDATE scan_runs SET checkpointLastCandidateId = :lastCandidateId, checkpointProcessedCount = :processedCount, updatedAtMillis = :updatedAtMillis WHERE id = :runId")
    suspend fun updateCheckpoint(runId: String, lastCandidateId: Long?, processedCount: Int, updatedAtMillis: Long)

    @Query("UPDATE scan_runs SET enumerationComplete = 1, reviewAttemptId = :attemptId, phase = 'REVIEW', state = 'WAITING_FOR_REVIEW', updatedAtMillis = :updatedAtMillis WHERE id = :runId AND enumerationAttemptId = :attemptId")
    suspend fun markEnumerationComplete(runId: String, attemptId: String, updatedAtMillis: Long): Int

    @Query("SELECT providerDocumentId FROM scan_candidates WHERE runId = :runId AND enumerationAttemptId = :attemptId")
    suspend fun providerDocumentIdsForAttempt(runId: String, attemptId: String): List<String>

    @Query("DELETE FROM scan_candidates WHERE runId = :runId AND enumerationAttemptId = :attemptId")
    suspend fun deleteAllAttemptCandidates(runId: String, attemptId: String)

    @Query(
        """
        DELETE FROM scan_candidates
        WHERE runId = :runId
          AND enumerationAttemptId = :attemptId
          AND providerDocumentId NOT IN (:keepIds)
        """,
    )
    suspend fun deleteAttemptCandidatesNotIn(runId: String, attemptId: String, keepIds: List<String>)

    @Query("DELETE FROM scan_candidates WHERE runId = :runId AND enumerationAttemptId != :attemptId")
    suspend fun pruneCandidatesNotSeenInAttempt(runId: String, attemptId: String)

    @Query("SELECT COUNT(*) FROM scan_runs WHERE id = :runId AND enumerationAttemptId = :attemptId AND state = 'RUNNING'")
    suspend fun isCurrentRunningAttempt(runId: String, attemptId: String): Int

    @Query("UPDATE scan_runs SET state = :newState, phase = :phase, problem = :problem, recoveryAction = :recoveryAction, updatedAtMillis = :updatedAtMillis WHERE id = :runId AND state IN (:expectedStates)")
    suspend fun transitionState(runId: String, expectedStates: List<String>, newState: String, phase: String, problem: String?, recoveryAction: String, updatedAtMillis: Long): Int

    @Insert
    suspend fun insertCandidate(candidate: ScanCandidateEntity): Long

    @Query("SELECT * FROM scan_candidates WHERE runId = :runId AND enumerationAttemptId = :attemptId AND providerDocumentId = :providerDocumentId")
    suspend fun candidateByProviderDocumentId(runId: String, attemptId: String, providerDocumentId: String): ScanCandidateEntity?

    @androidx.room.Update
    suspend fun updateCandidate(candidate: ScanCandidateEntity)

    @Insert
    suspend fun insertCandidatePages(pages: List<ScanCandidatePageEntity>)

    @Query("DELETE FROM scan_candidate_pages WHERE candidateId = :candidateId")
    suspend fun deleteCandidatePages(candidateId: Long)

    @Query("SELECT * FROM scan_candidate_pages WHERE candidateId = :candidateId ORDER BY ordinal")
    suspend fun candidatePages(candidateId: Long): List<ScanCandidatePageEntity>

    @Insert
    suspend fun insertCandidateClaims(claims: List<ScanCandidateClaimEntity>)

    @Query("DELETE FROM scan_candidate_claims WHERE candidateId = :candidateId")
    suspend fun deleteCandidateClaims(candidateId: Long)

    @Query("SELECT * FROM scan_candidate_claims WHERE candidateId = :candidateId ORDER BY id")
    suspend fun candidateClaims(candidateId: Long): List<ScanCandidateClaimEntity>

    /**
     * A47: sub-comics of a container candidate, ordered by their stable ordinal. Empty for
     * the single-comic shape (and for every candidate persisted before v13).
     */
    @Insert
    suspend fun insertCandidateSubComics(subComics: List<ScanCandidateSubComicEntity>)

    @Query("DELETE FROM scan_candidate_sub_comics WHERE candidateId = :candidateId")
    suspend fun deleteCandidateSubComics(candidateId: Long)

    @Query("SELECT * FROM scan_candidate_sub_comics WHERE candidateId = :candidateId ORDER BY ordinal")
    suspend fun candidateSubComics(candidateId: Long): List<ScanCandidateSubComicEntity>

    @Query("SELECT * FROM scan_candidates WHERE id = :candidateId")
    suspend fun candidate(candidateId: Long): ScanCandidateEntity?

    @Query(
        """
        SELECT COUNT(*) FROM scan_candidates
        WHERE runId = :runId
          AND enumerationAttemptId = (SELECT reviewAttemptId FROM scan_runs WHERE id = :runId)
          AND id IN (:candidateIds)
        """,
    )
    suspend fun reviewCandidateCount(runId: String, candidateIds: List<Long>): Int

    @Query(
        """
        SELECT scan_candidates.id FROM scan_candidates
        JOIN scan_runs ON scan_runs.id = scan_candidates.runId
        WHERE scan_candidates.runId = :runId
          AND scan_candidates.enumerationAttemptId = scan_runs.reviewAttemptId
          AND scan_candidates.selected = 1
          AND scan_candidates.outcome IN ('PENDING', 'LOW_CONFIDENCE')
        ORDER BY scan_candidates.id LIMIT 1
        """,
    )
    suspend fun nextSelectedNonTerminalCandidate(runId: String): Long?

    @Query(
        """
        UPDATE scan_candidates SET selected = 0
        WHERE runId = :runId
          AND enumerationAttemptId = (SELECT reviewAttemptId FROM scan_runs WHERE id = :runId)
        """,
    )
    suspend fun clearSelection(runId: String)

    @Query(
        """
        UPDATE scan_candidates SET selected = 1
        WHERE runId = :runId
          AND enumerationAttemptId = (SELECT reviewAttemptId FROM scan_runs WHERE id = :runId)
          AND id IN (:candidateIds)
          AND recognitionKind = 'RECOVERABLE'
          AND outcome IN ('PENDING', 'LOW_CONFIDENCE')
        """,
    )
    suspend fun selectRecoverableCandidates(runId: String, candidateIds: List<Long>)

    /**
     * A30 auto-commit: select the entire review attempt. Same guards as
     * [selectRecoverableCandidates] (review attempt only, RECOVERABLE only,
     * never re-selects a candidate that already has a terminal outcome).
     */
    @Query(
        """
        UPDATE scan_candidates SET selected = 1
        WHERE runId = :runId
          AND enumerationAttemptId = (SELECT reviewAttemptId FROM scan_runs WHERE id = :runId)
          AND recognitionKind = 'RECOVERABLE'
          AND outcome IN ('PENDING', 'LOW_CONFIDENCE')
        """,
    )
    suspend fun selectAllRecoverableCandidates(runId: String): Int

    @Query(
        """
        UPDATE scan_candidates SET outcome = :outcome, selected = 0
        WHERE runId = :runId
          AND enumerationAttemptId = (SELECT reviewAttemptId FROM scan_runs WHERE id = :runId)
          AND id = :candidateId
          AND recognitionKind = 'RECOVERABLE'
          AND outcome IN ('PENDING', 'LOW_CONFIDENCE')
        """,
    )
    suspend fun markInitialRecoverableCandidateOutcome(runId: String, candidateId: Long, outcome: String): Int

    @Query(
        """
        UPDATE scan_candidates SET outcome = 'FAILED', selected = 0
        WHERE id = :candidateId
          AND enumerationAttemptId = (SELECT reviewAttemptId FROM scan_runs WHERE id = scan_candidates.runId)
          AND outcome IN ('PENDING', 'LOW_CONFIDENCE')
        """,
    )
    suspend fun markPendingCandidateFailed(candidateId: Long): Int

    @Query(
        """
        UPDATE scan_runs SET
          discoveredCount = (SELECT COUNT(*) FROM scan_candidates WHERE runId = :runId AND enumerationAttemptId = COALESCE(scan_runs.reviewAttemptId, scan_runs.enumerationAttemptId)),
          selectedCount = (SELECT COUNT(*) FROM scan_candidates WHERE runId = :runId AND enumerationAttemptId = COALESCE(scan_runs.reviewAttemptId, scan_runs.enumerationAttemptId) AND selected = 1),
          highConfidenceCount = (SELECT COUNT(*) FROM scan_candidates WHERE runId = :runId AND enumerationAttemptId = COALESCE(scan_runs.reviewAttemptId, scan_runs.enumerationAttemptId) AND confidence = 'HIGH'),
          lowConfidenceCount = (SELECT COUNT(*) FROM scan_candidates WHERE runId = :runId AND enumerationAttemptId = COALESCE(scan_runs.reviewAttemptId, scan_runs.enumerationAttemptId) AND confidence = 'LOW'),
          incompleteCount = (SELECT COUNT(*) FROM scan_candidates WHERE runId = :runId AND enumerationAttemptId = COALESCE(scan_runs.reviewAttemptId, scan_runs.enumerationAttemptId) AND completeness = 'INCOMPLETE'),
          rejectedCount = (SELECT COUNT(*) FROM scan_candidates WHERE runId = :runId AND enumerationAttemptId = COALESCE(scan_runs.reviewAttemptId, scan_runs.enumerationAttemptId) AND outcome = 'REJECTED'),
          committedCount = (SELECT COUNT(*) FROM scan_candidates WHERE runId = :runId AND enumerationAttemptId = COALESCE(scan_runs.reviewAttemptId, scan_runs.enumerationAttemptId) AND outcome IN ('NEW', 'UPDATED', 'UNCHANGED', 'INCOMPLETE', 'CONFLICT')),
          failedCount = (SELECT COUNT(*) FROM scan_candidates WHERE runId = :runId AND enumerationAttemptId = COALESCE(scan_runs.reviewAttemptId, scan_runs.enumerationAttemptId) AND outcome = 'FAILED'),
          updatedAtMillis = :updatedAtMillis
        WHERE id = :runId
        """,
    )
    suspend fun refreshPreviewCounts(runId: String, updatedAtMillis: Long)

    /**
     * A30 §2.1 (S4b/G4): how many comics this run actually ADDED, as opposed to re-found.
     * Read-side only -- `scan_runs` has no such column and A30 allows no schema change --
     * and scoped to the same attempt `refreshPreviewCounts` uses, so it counts the run's
     * final enumeration, not an abandoned one.
     */
    @Query(
        """
        SELECT COUNT(*) FROM scan_candidates
        WHERE runId = :runId
          AND enumerationAttemptId = (
            SELECT COALESCE(reviewAttemptId, enumerationAttemptId) FROM scan_runs WHERE id = :runId
          )
          AND outcome = 'NEW'
        """,
    )
    suspend fun newlyAddedCount(runId: String): Int

    /** A30 §2.2/§5: last completed scan per folder, for the Folders row status line. */
    @Query(
        """
        SELECT sourceId AS sourceId, MAX(updatedAtMillis) AS completedAtMillis FROM scan_runs
        WHERE state IN ('COMPLETE', 'PARTIAL')
        GROUP BY sourceId
        """,
    )
    fun observeLastCompletedScans(): Flow<List<SourceScanCompletionRow>>
}
