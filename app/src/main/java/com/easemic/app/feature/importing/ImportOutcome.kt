package com.easemic.app.feature.importing

import com.easemic.app.i18n.tr

import com.easemic.app.model.AuthorizedSource
import com.easemic.app.model.ScanRun
import com.easemic.app.model.ScanRunId
import com.easemic.app.model.ScanRunState
import com.easemic.app.model.SourceId

/** One finished scan, ready to be stated once on the shelf. */
data class ImportOutcome(
    val sourceId: SourceId,
    val runId: ScanRunId,
    val folderName: String,
    /** What counts as the line's headline: everything an import put on the shelf, or a rescan's new finds. */
    val committedCount: Int,
    /**
     * A53: candidates that were found but failed to commit (RunSummary.failed).
     * Zero-committed lines distinguish "nothing found" (0) from "found but
     * failed" (>0) — the old single line claimed an empty folder for both.
     */
    val failedCount: Int = 0,
    /** True only for a folder the user just imported: rescans never offer Undo. */
    val undoable: Boolean = committedCount > 0,
)

/**
 * A30 §2.1: turns the durable per-source run stream into at most one unreported
 * outcome. The first observation primes the seen-set instead of reporting, so a
 * cold start never re-announces imports the user already saw.
 *
 * What counts as a first import (S4b/G4): the picker said so. [markImported] is called by
 * [ImportAuthorizationCoordinator] for folders the user chose in the file picker -- new
 * rows and revived tombstoned rows alike, because re-importing a folder you stopped
 * watching is a first import from where the user sits. It is deliberately NOT called for
 * "Choose folder again" (a repair, not an import) nor for Undo of Stop watching (the
 * earlier "reappeared in observeSources" heuristic could not tell those apart and produced
 * a false "{n} comics added · Undo" line after an Undo).
 *
 * What a rescan may say: only what is genuinely new ([RunSummary.newlyAdded], the run's
 * candidates that committed as NEW). Re-finding twelve unchanged comics is not news, and
 * "12 comics added" for them was a lie. A first import states its whole committed count --
 * a stopped folder's comics were all off the shelf, so all of them did arrive.
 */
class ImportOutcomeTracker {
    private val reportedRunIds = mutableSetOf<String>()
    private val importedSourceIds = mutableSetOf<SourceId>()
    private var primed = false

    /** The user picked this folder in the file picker; its next committing scan is fresh. */
    fun markImported(sourceId: SourceId) {
        importedSourceIds += sourceId
    }

    fun accept(
        sources: List<AuthorizedSource>,
        latestRunsBySource: Map<SourceId, ScanRun>,
    ): ImportOutcome? {
        val finished = latestRunsBySource.filterValues { run ->
            run.state == ScanRunState.COMPLETE || run.state == ScanRunState.PARTIAL
        }
        if (!primed) {
            primed = true
            finished.values.forEach { reportedRunIds += it.id.value }
            return null
        }
        var newest: ImportOutcome? = null
        finished.forEach { (sourceId, run) ->
            if (!reportedRunIds.add(run.id.value)) return@forEach
            val source = sources.firstOrNull { it.id == sourceId } ?: return@forEach
            val isImport = run.generation <= 1L || sourceId in importedSourceIds
            val count = if (isImport) run.summary.committed else run.summary.newlyAdded
            if (!isImport && count <= 0) return@forEach
            importedSourceIds -= sourceId
            newest = ImportOutcome(
                sourceId = sourceId,
                runId = run.id,
                folderName = source.displayLabel,
                committedCount = count,
                failedCount = if (isImport) run.summary.failed else 0,
                undoable = isImport && count > 0,
            )
        }
        return newest
    }
}

/** Locked copy (spec §4). No celebration, no rounding, no exclamation marks. */
object ImportOutcomeCopy {
    const val UNDO = "Undo"

    fun line(outcome: ImportOutcome): String =
        if (outcome.committedCount > 0) {
            val noun = if (outcome.committedCount == 1) "comic" else "comics"
            tr(
                "${outcome.committedCount} $noun added from ${outcome.folderName}",
                "已从 ${outcome.folderName} 添加 ${outcome.committedCount} 部漫画",
                "已從 ${outcome.folderName} 新增 ${outcome.committedCount} 部漫畫",
                "${outcome.folderName} から ${outcome.committedCount} 件追加しました",
            )
        } else if (outcome.failedCount > 0) {
            // A53: candidates existed but none committed — the folder was NOT
            // empty, so "no image folders found" would be a lie.
            "Nothing was imported from ${outcome.folderName} — " +
                "${outcome.failedCount} candidates failed."
        } else {
            "Nothing to import in ${outcome.folderName} — " +
                "no image folders or CBZ files found."
        }

    /** Undo means "stop watching that folder"; only a first-run addition offers it. */
    fun actionLabel(outcome: ImportOutcome): String? =
        UNDO.takeIf { outcome.undoable }
}
