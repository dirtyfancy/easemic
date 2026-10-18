package com.easemic.app.feature.importing

import com.easemic.app.i18n.AppStrings

import com.easemic.app.model.ScanRun
import com.easemic.app.model.SourceState

/**
 * Honest import progress copy for Sources + notifications.
 * Never invents totals or percents when the durable run has no denominator.
 */
object ImportProgressStatus {
    fun subtitle(state: SourceState, run: ScanRun?): String = when (state) {
        SourceState.DISCOVERING -> discovering(run)
        SourceState.IMPORTING -> importing(run)
        SourceState.NEEDS_VALIDATION -> AppStrings.importState("Checking access")
        SourceState.READY -> AppStrings.importState("Ready")
        SourceState.READY_FOR_REVIEW -> AppStrings.importState("Ready for review")
        SourceState.PAUSED -> AppStrings.importState("Paused")
        SourceState.COMPLETE -> AppStrings.importState("Complete")
        SourceState.PARTIAL -> AppStrings.importState("Partially successful")
        SourceState.NEEDS_REAUTHORIZATION -> AppStrings.importState("Needs reauthorization")
        SourceState.FAILED -> AppStrings.importState("Failed")
        SourceState.REMOVED -> AppStrings.importState("Removed")
    }

    fun discovering(run: ScanRun?): String {
        val processed = run?.checkpoint?.processedCount ?: 0
        return if (processed > 0) {
            AppStrings.discovering(processed)
        } else {
            AppStrings.discovering(null)
        }
    }

    fun importing(run: ScanRun?): String {
        val summary = run?.summary
        val selected = summary?.selected ?: 0
        val completed = (summary?.committed ?: 0) + (summary?.failed ?: 0)
        return when {
            selected > 0 -> AppStrings.importingStatus(completed, selected)
            completed > 0 -> AppStrings.importingStatus(completed, null, processed = true)
            else -> AppStrings.importingStatus(0, null)
        }
    }

    fun percent(completed: Int, total: Int?): Int? {
        if (total == null || total <= 0) return null
        val safeCompleted = completed.coerceAtLeast(0)
        return ((safeCompleted * 100L) / total).toInt().coerceIn(0, 100)
    }

    fun notificationText(completed: Int, total: Int?): String {
        val safeCompleted = completed.coerceAtLeast(0)
        return if (total != null && total > 0) {
            AppStrings.itemsProcessed(safeCompleted, total)
        } else {
            AppStrings.itemsProcessed(safeCompleted, null)
        }
    }
}
