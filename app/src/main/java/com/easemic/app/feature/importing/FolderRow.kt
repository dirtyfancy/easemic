package com.easemic.app.feature.importing

import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.model.AuthorizedSource
import com.easemic.app.model.RunProblem
import com.easemic.app.model.ScanRun
import com.easemic.app.model.ScanRunState
import com.easemic.app.model.SourceGrantState
import com.easemic.app.model.SourceId

/** One watched folder, as the Folders screen states it (A30 §2.2). */
data class FolderRow(
    val sourceId: SourceId,
    val displayName: String,
    val status: FolderRowStatus,
) {
    val needsPicker: Boolean get() = status == FolderRowStatus.AccessLost
}

sealed interface FolderRowStatus {
    /**
     * SEND-BACK #2 item J: [lastCompletedAtMillis] is a raw epoch, not a
     * pre-baked duration -- the row text is now recomputed against a live
     * clock inside FoldersScreen (via produceState) instead of freezing at
     * whatever "ago" was true when this row was last emitted.
     */
    data class Scanned(val comicCount: Int, val lastCompletedAtMillis: Long?) : FolderRowStatus
    data class Scanning(val foundCount: Int) : FolderRowStatus
    data object AccessLost : FolderRowStatus
    data object WaitingForStorage : FolderRowStatus
}

object FolderRowMapper {
    private val PARKED = setOf(
        ScanRunState.PAUSED,
        ScanRunState.PAUSE_REQUESTED,
    )

    private val SCANNING = setOf(
        ScanRunState.ENQUEUED,
        ScanRunState.RUNNING,
        ScanRunState.IMPORTING,
        ScanRunState.WAITING_FOR_REVIEW,
    )

    fun rows(
        sources: List<AuthorizedSource>,
        latestRunsBySource: Map<SourceId, ScanRun>,
        comicCountsBySource: Map<SourceId, Int>,
        lastCompletedAtMillis: Map<SourceId, Long>,
    ): List<FolderRow> = sources.map { source ->
        val run = latestRunsBySource[source.id]
        val status = when {
            source.grantState == SourceGrantState.NEEDS_REAUTHORIZATION ->
                FolderRowStatus.AccessLost
            source.grantState == SourceGrantState.NEEDS_VALIDATION ->
                FolderRowStatus.WaitingForStorage
            run != null && run.state in SCANNING ->
                FolderRowStatus.Scanning(run.checkpoint.processedCount)
            // A30 §2.2 (S4b/G6): a run the engine parked because storage did not answer is
            // "Waiting for storage", not a healthy "{n} comics · scanned {time}" line. Grant
            // loss is not routed here: that is a source state (NEEDS_REAUTHORIZATION above),
            // so the row says "Access lost · Choose folder again" instead.
            run != null && run.state in PARKED &&
                run.failure?.problem == RunProblem.PROVIDER_UNAVAILABLE ->
                FolderRowStatus.WaitingForStorage
            else -> FolderRowStatus.Scanned(
                comicCount = comicCountsBySource[source.id] ?: 0,
                lastCompletedAtMillis = lastCompletedAtMillis[source.id],
            )
        }
        FolderRow(source.id, source.displayLabel, status)
    }
}

/** Locked copy (spec §4). One status line per folder, never per tile. */
object FolderRowCopy {
    val TITLE: String get() = AppStrings.foldersOnThisPhone
    val SUBTITLE: String get() = AppStrings.foldersSubtitle
    val EMPTY_TITLE: String get() = AppStrings.noFoldersYet
    val EMPTY_BODY: String get() = AppStrings.importFolderWatch
    val IMPORT_FOLDER: String get() = AppStrings.importFolder
    val RESCAN_NOW: String get() = AppStrings.rescanNow
    val STOP_WATCHING: String get() = AppStrings.stopWatching
    val CHOOSE_FOLDER_AGAIN: String get() = AppStrings.chooseFolderAgain
    val UNDO: String get() = tr("Undo", "撤销", "復原", "元に戻す")
    val DISMISS: String get() = AppStrings.dismiss

    fun status(status: FolderRowStatus, nowMillis: Long): String = when (status) {
        is FolderRowStatus.Scanned ->
            "${comicsCount(status.comicCount)} · ${scannedSuffix(status.lastCompletedAtMillis, nowMillis)}"
        is FolderRowStatus.Scanning -> AppStrings.scanningFound(status.foundCount)
        FolderRowStatus.AccessLost -> AppStrings.accessLostChooseFolder
        FolderRowStatus.WaitingForStorage -> AppStrings.folderWaitingStorage
    }

    fun stoppedLine(folderName: String): String = AppStrings.stoppedWatching(folderName)

    // Review LOW-4: singular forms ("1 comic", "1 hour ago", "1 day ago").
    private fun comicsCount(count: Int): String =
        AppStrings.comicCount(count)

    fun relativeTime(agoMillis: Long): String = when {
        agoMillis < 60_000L -> AppStrings.justNow
        agoMillis < 3_600_000L -> AppStrings.minutesAgo(agoMillis / 60_000L)
        agoMillis < 86_400_000L -> {
            val hours = agoMillis / 3_600_000L
            AppStrings.hoursAgo(hours)
        }
        else -> {
            val days = agoMillis / 86_400_000L
            AppStrings.daysAgo(days)
        }
    }

    private fun scannedSuffix(lastCompletedAtMillis: Long?, nowMillis: Long): String =
        lastCompletedAtMillis?.let { AppStrings.scannedAt(relativeTime((nowMillis - it).coerceAtLeast(0L))) }
            ?: AppStrings.notScannedYet
}
