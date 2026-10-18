package com.easemic.app.feature.activity

import com.easemic.app.model.AuthorizedSource
import com.easemic.app.model.RunProblem
import com.easemic.app.model.ScanRun
import com.easemic.app.model.ScanRunState
import com.easemic.app.model.SourceId

/**
 * A52 (owner bug backlog, ledger 2026-09-04): imports must be visible in
 * Activity tab. A live row (RUNNING) shows progress; a done row (COMPLETE/
 * PARTIAL) stays visible for the whole Activity visit and clears when the
 * next visit begins; a failed row goes under Needs attention with "Choose
 * folder again." A run the engine parked with a failure (PAUSED +
 * non-null failure — grant lost, provider unavailable, platform stop)
 * counts as failed here too.
 */
sealed interface ImportActivityRow {
    val sourceId: SourceId
    val folderName: String

    data class Live(
        override val sourceId: SourceId,
        override val folderName: String,
        val discovered: Int,
        val committed: Int,
    ) : ImportActivityRow

    data class Done(
        override val sourceId: SourceId,
        override val folderName: String,
        val committed: Int,
    ) : ImportActivityRow

    data class Failed(
        override val sourceId: SourceId,
        override val folderName: String,
        val problem: RunProblem,
    ) : ImportActivityRow
}

/**
 * A52 (owner bug backlog, ledger 2026-09-04): turns the per-source latest-run
 * map into Activity rows. Live runs (RUNNING/IMPORTING) appear while active.
 * Finished runs (COMPLETE/PARTIAL) become Done receipts that stay visible for
 * the whole Activity visit and clear when the next visit begins —
 * [beginVisit] marks that boundary. Runs parked by a failure — terminal
 * FAILED, or PAUSED with a recorded failure (the engine parks grant loss,
 * provider failure and platform stops as PAUSED, see ScanEngine's
 * grantLost/providerFailure and RunRecovery.recoverStopped) — become
 * attention rows with the recorded problem, so the A52 ruling's "access lost
 * / provider failures under Needs attention with Choose folder again" holds
 * for the parking path too, not just the rare terminal FAILED.
 *
 * One-visit receipts (2026-09-08 audit, finding 2): a finished run's Done row
 * is kept in [pendingDone] and returned by EVERY accept with the same
 * emission pair, so re-running accept (a sibling import's sources
 * re-projection, or an aborted composition re-executing the remember block)
 * cannot eat a row that already rendered. [beginVisit] is what clears them.
 *
 * Cold-start staleness (2026-09-08 audit, finding 3): a finished run whose
 * [ScanRun.updatedAtMillis] predates [processStartMs] is a receipt from a
 * previous session; it is marked seen without ever emitting a Done row.
 */
class ImportActivityTracker(
    // A52 (finding 3): wall-clock of this process's start, passed by the app
    // root. A COMPLETE/PARTIAL run whose updatedAtMillis predates it is a
    // receipt from a previous session — marked seen without ever rendering a
    // Done row (the cold-start resurrection hole). 0 disables the gate.
    private val processStartMs: Long = 0L,
) {
    private val seenDoneRunIds = mutableSetOf<String>()

    // A52 (finding 2): the current visit's Done receipts. accept appends to it
    // (idempotently — seenDoneRunIds still dedups) and returns its full
    // contents every call, so a rendered row survives every accept re-run
    // within the visit; beginVisit clears it for the next one.
    private val pendingDone = mutableListOf<ImportActivityRow.Done>()

    /**
     * Marks the start of a new Activity visit: the previous visit's Done
     * receipts expire here. The Activity route's visit-boundary remember
     * calculation (see rememberActivityImportRows) calls this on re-entering
     * composition, before the visit's first accept.
     */
    fun beginVisit() {
        pendingDone.clear()
    }

    fun accept(
        sources: List<AuthorizedSource>,
        latestRunsBySource: Map<SourceId, ScanRun>,
    ): List<ImportActivityRow> {
        val rows = mutableListOf<ImportActivityRow>()

        latestRunsBySource.forEach { (sourceId, run) ->
            val source = sources.firstOrNull { it.id == sourceId } ?: return@forEach
            val folderName = source.displayLabel
            when (run.state) {
                ScanRunState.RUNNING, ScanRunState.IMPORTING -> {
                    rows += ImportActivityRow.Live(
                        sourceId = sourceId,
                        folderName = folderName,
                        discovered = run.summary.discovered,
                        committed = run.summary.committed,
                    )
                }
                ScanRunState.COMPLETE, ScanRunState.PARTIAL -> {
                    if (seenDoneRunIds.add(run.id.value)) {
                        // A52 (finding 3): a run that finished before this
                        // process started is a receipt from a previous
                        // session — the user already saw it there. Marked
                        // seen (the add above) without emitting a row.
                        if (processStartMs > 0L && run.updatedAtMillis < processStartMs) {
                            return@forEach
                        }
                        // A52 re-audit: `committed` counts every re-found
                        // comic (NEW+UPDATED+UNCHANGED+…), so a manual rescan
                        // of an unchanged folder must not receipt "Added 12
                        // comics" — the same lie ImportOutcome fixes for the
                        // shelf line. First imports (generation <= 1) receipt
                        // the full committed count; rescans receipt only
                        // what genuinely arrived (newlyAdded), and a rescan
                        // that added nothing is not news at all.
                        val isImport = run.generation <= 1L
                        val count = if (isImport) run.summary.committed else run.summary.newlyAdded
                        if (isImport || count > 0) {
                            // A52 (finding 2): a fresh receipt — keep it for the
                            // whole visit, not just this emission.
                            pendingDone += ImportActivityRow.Done(
                                sourceId = sourceId,
                                folderName = folderName,
                                committed = count,
                            )
                        }
                    }
                }
                ScanRunState.FAILED -> {
                    val problem = run.failure?.problem ?: RunProblem.UNKNOWN
                    rows += ImportActivityRow.Failed(
                        sourceId = sourceId,
                        folderName = folderName,
                        problem = problem,
                    )
                }
                ScanRunState.PAUSED -> {
                    // A52 (finding 1): the engine parks failures as PAUSED
                    // with a non-null failure (grantLost/providerFailure in
                    // ScanEngine, RunRecovery.recoverStopped) — the dominant
                    // failure path. Those must reach Needs attention with
                    // "Choose folder again"; PAUSED without a recorded
                    // failure (user pause via requestPause) stays hidden as
                    // before.
                    run.failure?.let { failure ->
                        rows += ImportActivityRow.Failed(
                            sourceId = sourceId,
                            folderName = folderName,
                            problem = failure.problem,
                        )
                    }
                }
                else -> {
                    // ENQUEUED, WAITING_FOR_REVIEW, PAUSE_REQUESTED — not visible in Activity.
                }
            }
        }
        // A52 (finding 2): every accept returns the visit's full receipt set
        // plus this emission's Live/Failed rows — idempotent for a repeated
        // (sources, latestRuns) pair, so an aborted composition re-running
        // the remember block cannot eat a rendered Done row.
        rows += pendingDone
        return rows
    }
}