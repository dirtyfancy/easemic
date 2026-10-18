package com.easemic.app.work

import com.easemic.app.data.SourceRepository
import com.easemic.app.importing.CandidateRecognitionService
import com.easemic.app.importing.source.AdapterLookup
import com.easemic.app.importing.source.SourceAdapterRegistry
import com.easemic.app.model.AdapterEnumeration
import com.easemic.app.model.CandidateFailure
import com.easemic.app.model.CandidateRecognition
import com.easemic.app.model.DiscoveredCandidate
import com.easemic.app.model.RecoveryAction
import com.easemic.app.model.RunCheckpoint
import com.easemic.app.model.RunFailure
import com.easemic.app.model.RunProblem
import com.easemic.app.model.ScanPhase
import com.easemic.app.model.ScanRun
import com.easemic.app.model.ScanRunId
import com.easemic.app.model.ScanRunState
import com.easemic.app.model.SourceFailure
import com.easemic.app.model.SourceGrantState
import com.easemic.app.model.WorkRequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun interface ScanRunReader {
    suspend fun run(runId: ScanRunId): ScanRun?

    suspend fun nextSelectedNonTerminalCandidate(runId: ScanRunId): com.easemic.app.model.CandidateId? = null

    suspend fun persistedProviderDocumentIds(
        runId: ScanRunId,
        attemptId: com.easemic.app.model.EnumerationAttemptId,
    ): Set<String> = emptySet()
}

sealed interface EngineResult {
    data object WaitingForReview : EngineResult
    data object Paused : EngineResult
    data object Stale : EngineResult
    data object Complete : EngineResult
    data object Partial : EngineResult
    data object Retry : EngineResult
}

fun interface ScanEngineRunner {
    suspend fun advance(
        runId: ScanRunId,
        requestId: WorkRequestId,
        onProgress: suspend (ImportWorkProgress) -> Unit,
    ): EngineResult
}

class ScanEngine(
    private val sources: SourceRepository,
    private val runReads: ScanRunReader,
    private val runMutations: FencedScanRunMutations,
    private val adapters: SourceAdapterRegistry,
    private val recognizer: CandidateRecognitionService,
    private val catalog: FencedCatalogMutations,
) : ScanEngineRunner {
    suspend fun advance(runId: ScanRunId, requestId: WorkRequestId): EngineResult =
        advance(runId, requestId) {}

    override suspend fun advance(
        runId: ScanRunId,
        requestId: WorkRequestId,
        onProgress: suspend (ImportWorkProgress) -> Unit,
    ): EngineResult {
        val run = runReads.run(runId) ?: return EngineResult.Stale
        if (run.state in TERMINAL) return if (run.state == ScanRunState.PARTIAL) EngineResult.Partial else EngineResult.Complete
        pauseBoundary(runId, requestId)?.let { return it }
        val source = sources.source(run.sourceId) ?: return pause(run, requestId, grantLost())
        if (source.grantState != SourceGrantState.READY) return pause(run, requestId, grantLost())
        if (run.state == ScanRunState.WAITING_FOR_REVIEW) {
            return autoCommit(run, requestId, onProgress)
        }
        if (run.state == ScanRunState.IMPORTING) {
            return commitSelected(run, requestId, onProgress)
        }

        if (!runMutations.transition(runId, requestId, setOf(ScanRunState.ENQUEUED, ScanRunState.RUNNING), ScanRunState.RUNNING, ScanPhase.ENUMERATE)) {
            return fencedFailure(runId, requestId)
        }
        val adapter = (adapters.adapter(source.adapterKind) as? AdapterLookup.Found)?.adapter
            ?: return pause(run, requestId, providerFailure())
        // Resume an incomplete ENUMERATE attempt in place: do not mint a new attemptId
        // (that would orphan checkpointed candidates and force full re-recognition).
        val enumerating = runReads.run(runId) ?: return EngineResult.Stale
        val resumeAttempt = enumerating.enumerationAttemptId?.takeIf { !enumerating.enumerationComplete }
        val attempt = resumeAttempt
            ?: runMutations.beginEnumerationAttempt(runId, requestId)
            ?: return fencedFailure(runId, requestId)
        val alreadyPersisted = if (resumeAttempt != null) {
            runReads.persistedProviderDocumentIds(runId, attempt)
        } else {
            emptySet()
        }
        val enumeration = try {
            adapter.enumerate(source)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return pause(requireNotNull(runReads.run(runId)), requestId, providerFailure())
        }
        workBoundary(runId, requestId).stopResult()?.let { return it }
        if (enumeration is AdapterEnumeration.Failed) {
            // A30 (S4b/B3): "the folder cannot be read" is ambiguous at the adapter
            // boundary -- a revoked grant and a provider hiccup both surface as
            // `Unavailable`. Ask the persisted-permission list which one it is: a lost
            // grant marks the source NEEDS_REAUTHORIZATION so the Folders row states
            // "Access lost · Choose folder again" instead of "0 comics"; a transient
            // failure keeps today's parked run. Neither path reconciles locations --
            // nothing is ever marked MISSING because of an access problem.
            val grantLost = enumeration.reason == SourceFailure.Unavailable &&
                grantState(run.sourceId) == GrantCheck.LOST
            return pause(
                requireNotNull(runReads.run(runId)),
                requestId,
                if (grantLost) grantLost() else providerFailure(),
            )
        }
        val seeds = (enumeration as AdapterEnumeration.Complete).seeds
        var processed = 0
        for (seed in seeds) {
            currentCoroutineContext().ensureActive()
            val boundary = workBoundary(runId, requestId)
            boundary.stopResult()?.let { return it }
            if (seed.providerDocumentId in alreadyPersisted) {
                processed += 1
                onProgress(ImportWorkProgress(completed = processed, total = null))
                continue
            }
            val fresh = (boundary as WorkBoundary.Ready).source
            val recognition = try {
                recognizer.recognize(fresh, seed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return pause(requireNotNull(runReads.run(runId)), requestId, providerFailure())
            }
            workBoundary(runId, requestId).stopResult()?.let { return it }
            val candidate = runMutations.persistCandidate(runId, requestId, attempt, DiscoveredCandidate(seed, recognition))
                ?: return fencedFailure(runId, requestId)
            processed += 1
            if (!runMutations.checkpoint(runId, requestId, RunCheckpoint(candidate, processed))) {
                return fencedFailure(runId, requestId)
            }
            onProgress(ImportWorkProgress(completed = processed, total = null))
            if (recognition is CandidateRecognition.Retryable && recognition.reason == CandidateFailure.InsufficientSpace) {
                val fresh = runReads.run(runId) ?: return EngineResult.Stale
                return pause(
                    fresh,
                    requestId,
                    RunFailure(RunProblem.INSUFFICIENT_SPACE, RecoveryAction.FREE_SPACE),
                )
            }
            workBoundary(runId, requestId).stopResult()?.let { return it }
        }
        workBoundary(runId, requestId).stopResult()?.let { return it }
        if (!runMutations.retainAttemptCandidates(
                runId,
                requestId,
                attempt,
                seeds.map { it.providerDocumentId }.toSet(),
            )
        ) {
            return workBoundary(runId, requestId).stopResult() ?: EngineResult.Stale
        }
        if (!runMutations.markEnumerationComplete(runId, requestId, attempt)) {
            return workBoundary(runId, requestId).stopResult() ?: EngineResult.Stale
        }
        if (!runMutations.checkpoint(runId, requestId, RunCheckpoint(null, processed))) {
            return fencedFailure(runId, requestId)
        }
        val enumerated = runReads.run(runId) ?: return EngineResult.Stale
        return autoCommit(enumerated, requestId, onProgress)
    }

    /**
     * A30: enumeration no longer parks the run for a human. Every RECOVERABLE
     * candidate of the review attempt is selected and committed inside the same
     * run. `WAITING_FOR_REVIEW` remains the durable state written by
     * `markEnumerationComplete`, so a run interrupted between the two steps --
     * including a legacy run parked by the old build -- resumes right here.
     */
    private suspend fun autoCommit(
        run: ScanRun,
        requestId: WorkRequestId,
        onProgress: suspend (ImportWorkProgress) -> Unit,
    ): EngineResult {
        if (!runMutations.selectAllRecoverableCandidates(run.id, requestId)) {
            return fencedFailure(run.id, requestId)
        }
        if (
            !runMutations.transition(
                run.id,
                requestId,
                setOf(ScanRunState.WAITING_FOR_REVIEW),
                ScanRunState.IMPORTING,
                ScanPhase.COMMIT,
            )
        ) {
            return fencedFailure(run.id, requestId)
        }
        val committing = runReads.run(run.id) ?: return EngineResult.Stale
        return commitSelected(committing, requestId, onProgress)
    }

    private suspend fun commitSelected(
        run: ScanRun,
        requestId: WorkRequestId,
        onProgress: suspend (ImportWorkProgress) -> Unit,
    ): EngineResult {
        val selectedTotal = run.summary.selected.takeIf { it > 0 }
        // Resume-safe: start from durable terminal outcomes, then count each candidate we finish.
        var completed = run.summary.committed + run.summary.failed
        while (true) {
            currentCoroutineContext().ensureActive()
            workBoundary(run.id, requestId).stopResult()?.let { return it }
            val candidate = runReads.nextSelectedNonTerminalCandidate(run.id) ?: break
            workBoundary(run.id, requestId).stopResult()?.let { return it }
            try {
                catalog.commit(run.id, requestId, run.sourceId, run.generation, candidate)
                    ?: return workBoundary(run.id, requestId).stopResult() ?: EngineResult.Stale
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                workBoundary(run.id, requestId).stopResult()?.let { return it }
                if (!runMutations.markCandidateOutcome(
                    run.id,
                    requestId,
                    candidate,
                    com.easemic.app.model.CandidateCommitResult(
                        null,
                        com.easemic.app.model.CandidateOutcome.FAILED,
                    ),
                )) {
                    // The catalog committer may already have compensated the failed graph
                    // transaction in a separate durable transaction. Only stop when this
                    // candidate remains eligible; otherwise its terminal outcome is the
                    // durable acknowledgement and later candidates must still run.
                    if (runReads.nextSelectedNonTerminalCandidate(run.id) == candidate) {
                        return fencedFailure(run.id, requestId)
                    }
                }
            }
            completed += 1
            onProgress(ImportWorkProgress(completed = completed, total = selectedTotal))
            workBoundary(run.id, requestId).stopResult()?.let { return it }
        }
        workBoundary(run.id, requestId).stopResult()?.let { return it }
        // A30 (S4b/B3): the last gate before anything is marked MISSING. An enumeration that
        // came back empty because the tree is no longer readable must never be mistaken for
        // "the user deleted every comic in this folder" -- re-check the persisted grant, and
        // park instead of reconciling. S4b/G5: an *unanswerable* check (the permission list
        // itself threw) parks too, as a transient failure -- reconciling on a shrug would
        // mark comics MISSING on the strength of nothing.
        when (grantState(run.sourceId)) {
            GrantCheck.LOST -> {
                val fresh = runReads.run(run.id) ?: return EngineResult.Stale
                return pause(fresh, requestId, grantLost())
            }
            GrantCheck.UNKNOWN -> {
                val fresh = runReads.run(run.id) ?: return EngineResult.Stale
                return pause(fresh, requestId, providerFailure())
            }
            GrantCheck.HELD -> Unit
        }
        workBoundary(run.id, requestId).stopResult()?.let { return it }
        if (!catalog.reconcileMissing(run.id, requestId, run.sourceId, run.generation)) {
            return fencedFailure(run.id, requestId)
        }
        workBoundary(run.id, requestId).stopResult()?.let { return it }
        val fresh = runReads.run(run.id) ?: return EngineResult.Stale
        val terminal = if (fresh.summary.failed > 0) ScanRunState.PARTIAL else ScanRunState.COMPLETE
        if (!runMutations.finishAndClearCurrentRequest(run.id, requestId, terminal)) {
            return fencedFailure(run.id, requestId)
        }
        return if (terminal == ScanRunState.PARTIAL) EngineResult.Partial else EngineResult.Complete
    }

    private suspend fun workBoundary(
        runId: ScanRunId,
        requestId: WorkRequestId,
    ): WorkBoundary {
        currentCoroutineContext().ensureActive()
        pauseBoundary(runId, requestId)?.let { return WorkBoundary.Stop(it) }
        val run = runReads.run(runId) ?: return WorkBoundary.Stop(EngineResult.Stale)
        val source = sources.source(run.sourceId)
        if (source == null || source.grantState != SourceGrantState.READY) {
            return WorkBoundary.Stop(pauseAtBoundary(run, requestId, grantLost()))
        }
        return WorkBoundary.Ready(source)
    }

    private suspend fun pauseBoundary(
        runId: ScanRunId,
        requestId: WorkRequestId,
    ): EngineResult? {
        val run = runReads.run(runId) ?: return EngineResult.Stale
        if (run.state == ScanRunState.PAUSED) return EngineResult.Paused
        if (!run.pauseRequested && run.state != ScanRunState.PAUSE_REQUESTED) return null
        if (
            runMutations.transition(
                run.id,
                requestId,
                setOf(ScanRunState.PAUSE_REQUESTED),
                ScanRunState.PAUSED,
                run.phase,
            )
        ) {
            return EngineResult.Paused
        }
        val observed = runReads.run(runId) ?: return EngineResult.Stale
        return if (
            observed.pauseRequested ||
            observed.state in setOf(ScanRunState.PAUSE_REQUESTED, ScanRunState.PAUSED)
        ) {
            EngineResult.Paused
        } else {
            EngineResult.Stale
        }
    }

    private suspend fun pause(run: ScanRun, requestId: WorkRequestId, failure: RunFailure): EngineResult {
        return if (
            runMutations.transition(
                run.id,
                requestId,
                setOf(run.state, ScanRunState.RUNNING),
                ScanRunState.PAUSED,
                run.phase,
                failure,
            )
        ) {
            EngineResult.Retry
        } else {
            EngineResult.Stale
        }
    }

    private suspend fun pauseAtBoundary(
        run: ScanRun,
        requestId: WorkRequestId,
        failure: RunFailure,
    ): EngineResult = if (
        runMutations.transition(
            run.id,
            requestId,
            setOf(run.state, ScanRunState.RUNNING),
            ScanRunState.PAUSED,
            run.phase,
            failure,
        )
    ) {
        EngineResult.Paused
    } else {
        EngineResult.Stale
    }

    private suspend fun fencedFailure(
        runId: ScanRunId,
        requestId: WorkRequestId,
    ): EngineResult = workBoundary(runId, requestId).stopResult() ?: EngineResult.Stale

    /** S4b/G5: three answers, because "I could not check" is not "the grant is fine". */
    private enum class GrantCheck { HELD, LOST, UNKNOWN }

    /**
     * A30 (S4b/B3): re-reads the persisted read grants. [GrantCheck.LOST] leaves the source
     * `NEEDS_REAUTHORIZATION`, so the Folders row states the loss once. Location state is
     * never touched here: an access problem is not evidence that a file moved or vanished.
     */
    private suspend fun grantState(
        sourceId: com.easemic.app.model.SourceId,
    ): GrantCheck = try {
        val lost = sources.revalidateReadyGrants()
        when {
            sourceId in lost -> GrantCheck.LOST
            sources.source(sourceId)?.grantState != SourceGrantState.READY -> GrantCheck.LOST
            else -> GrantCheck.HELD
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        GrantCheck.UNKNOWN
    }

    private fun grantLost() = RunFailure(RunProblem.GRANT_LOST, RecoveryAction.REAUTHORIZE)
    private fun providerFailure() = RunFailure(RunProblem.PROVIDER_UNAVAILABLE, RecoveryAction.RETRY)

    private companion object {
        val TERMINAL = setOf(ScanRunState.COMPLETE, ScanRunState.PARTIAL, ScanRunState.FAILED)
    }

    private sealed interface WorkBoundary {
        data class Ready(val source: com.easemic.app.model.AuthorizedSource) : WorkBoundary
        data class Stop(val result: EngineResult) : WorkBoundary
    }

    private fun WorkBoundary.stopResult() = (this as? WorkBoundary.Stop)?.result
}
