package com.easemic.app.feature.settings.backup

import com.easemic.app.sync.EncryptedBackupException
import com.easemic.app.sync.archive.ArchiveFormatException
import com.easemic.app.sync.archive.ArchiveReader
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr

/** What the Backup & restore screen shows (#D7). */
sealed interface BackupUiState {
    data object Sizing : BackupUiState

    /** Idle: both export choices sized; [message] reports the last success, [error] the last refusal. */
    data class Ready(
        val everythingComics: Int = 0,
        val everythingBytes: Long = 0,
        val message: String? = null,
        val error: String? = null,
    ) : BackupUiState

    /** Export or restore running. [fraction] is bytes or comics done over the total, null when that total is 0. */
    data class Working(val line: String, val fraction: Float? = null, val cancellable: Boolean = false) : BackupUiState

    /** A restore file is open and checked; nothing is written until the user confirms. */
    data class Preview(
        val preview: ArchiveRestorer.Preview,
        val error: String? = null,
        val fileName: String? = null,
    ) : BackupUiState
}

object BackupCopy {
    val PASSWORDS_DIFFER: String get() = AppStrings.passwordsDoNotMatch
    val PASSWORD_TOO_SHORT: String get() = AppStrings.useAtLeastSix
    val WRONG_PASSWORD: String get() = AppStrings.wrongBackupPassword
    val DAMAGED: String get() = AppStrings.backupDamaged
    val NEWER_APP: String get() = AppStrings.backupNewerApp
    val NOT_ENOUGH_SPACE: String get() = AppStrings.notEnoughFreeSpace
    val CANCELLED: String get() = tr("Cancelled.", "已取消。", "已取消。", "キャンセルしました。")
    val EXPORT_FAILED: String get() = AppStrings.couldNotWriteBackup

    fun exported(comics: Int): String =
        if (comics == 0) "Backup saved: collection, progress and settings." else "Backup saved with ${plural(comics, "comic")}."

    fun restored(result: ArchiveRestorer.Result): String = buildString {
        append("Restored ").append(plural(result.restored, "comic"))
        val parts = buildList {
            if (result.skipped > 0) add("skipped ${result.skipped} already here")
            if (result.bookmarksAdded > 0) add("${result.bookmarksAdded} collected")
            if (result.progressApplied > 0) add("progress for ${result.progressApplied}")
        }
        if (parts.isNotEmpty()) append(", ").append(parts.joinToString(", "))
        append('.')
        if (result.failed > 0) append(" ${result.failed} could not be read — the file may be damaged.")
        if (result.addAgain.isNotEmpty()) append(" Add again: ").append(result.addAgain.joinToString(", ")).append('.')
    }

    fun spaceLine(needBytes: Long, freeBytes: Long): String =
        "Needs ${size(needBytes)} · ${size(freeBytes)} free on this phone"

    fun everythingSize(comics: Int, bytes: Long): String = "${plural(comics, "comic")} · ${size(bytes)}"

    fun exporting(comic: Int, total: Int, bytes: Long, totalBytes: Long): String =
        if (total == 0) "Saving…" else "Comic ${comic + 1} of $total · ${size(bytes)} of ${size(totalBytes)}"

    fun restoring(done: Int, total: Int): String = if (total == 0) "Restoring…" else "Restoring comic $done of $total…"

    fun size(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1000 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
        return if (unit == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
}

/**
 * #D7: the Backup & restore screen's state machine. Export sizes both choices first, checks the password, then writes;
 * restore opens and checks the file, shows a preview, and writes only after the user confirms. Callers run these
 * suspend functions off the main thread; the seams keep it free of Android so it is JVM-tested.
 */
/** A place the user picked — a document URI in the app, a temp file in tests. */
interface Destination

class BackupPresenter(
    private val planFor: suspend (includeDownloads: Boolean) -> ArchiveExporter.ExportPlan,
    /** Writes the plan to the export destination the user picked ([Destination] is opaque here). */
    private val export: (Destination, CharArray, ArchiveExporter.ExportPlan, (Int, Long) -> Unit, () -> Boolean) -> Unit,
    /** Opens the backup the user picked. Called twice for a restore: once to preview, once to restore. */
    private val openArchive: (Destination, CharArray) -> ArchiveReader,
    private val preview: suspend (ArchiveReader) -> ArchiveRestorer.Preview,
    private val restore: suspend (ArchiveReader, (Int, Int) -> Unit) -> ArchiveRestorer.Result,
) {
    private val _state = MutableStateFlow<BackupUiState>(BackupUiState.Sizing)
    val state: StateFlow<BackupUiState> = _state

    @Volatile private var cancelRequested = false
    private var everything: ArchiveExporter.ExportPlan? = null
    private var pending: Pair<Destination, CharArray>? = null

    suspend fun open() {
        _state.value = BackupUiState.Sizing
        try {
            val plan = planFor(true)
            everything = plan
            _state.value = BackupUiState.Ready(everythingComics = itemCount(plan), everythingBytes = plan.pageBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = ready(error = refusal(e))
        }
    }

    private fun ready(message: String? = null, error: String? = null): BackupUiState.Ready {
        val plan = everything
        return BackupUiState.Ready(plan?.let { itemCount(it) } ?: 0, plan?.pageBytes ?: 0, message, error)
    }

    /** Checks the password pair; returns the refusal to show, or null when it is usable. */
    fun passwordProblem(password: String, confirm: String): String? = when {
        password.length < MIN_PASSWORD -> BackupCopy.PASSWORD_TOO_SHORT
        password != confirm -> BackupCopy.PASSWORDS_DIFFER
        else -> null
    }

    suspend fun export(includeDownloads: Boolean, password: String, confirm: String, out: Destination) {
        passwordProblem(password, confirm)?.let {
            _state.value = ready(error = it)
            return
        }
        cancelRequested = false
        try {
            val plan = if (includeDownloads) planFor(true) else planFor(false)
            if (includeDownloads) everything = plan
            val totalItems = itemCount(plan)
            val totalBytes = plan.pageBytes
            _state.value = exportWorking(BackupCopy.exporting(0, totalItems, 0, totalBytes), 0, totalBytes)
            export(out, password.toCharArray(), plan, { comic, bytes ->
                _state.value = exportWorking(BackupCopy.exporting(comic, totalItems, bytes, totalBytes), bytes, totalBytes)
            }, { cancelRequested })
            _state.value = ready(message = BackupCopy.exported(totalItems))
        } catch (e: CancellationException) {
            if (cancelRequested) {
                _state.value = ready(error = BackupCopy.CANCELLED)
            } else {
                throw e
            }
        } catch (e: Exception) {
            _state.value = ready(error = BackupCopy.EXPORT_FAILED)
        }
    }

    fun cancel() {
        cancelRequested = true
    }

    suspend fun pickRestore(file: Destination, password: String, fileName: String? = null) {
        _state.value = restoreWorking(BackupCopy.restoring(0, 0), 0, 0)
        val reader = try {
            openArchive(file, password.toCharArray())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = ready(error = refusal(e))
            return
        }
        try {
            _state.value = BackupUiState.Preview(preview(reader), fileName = fileName)
            pending = file to password.toCharArray()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = ready(error = refusal(e))
        } finally {
            reader.close()
        }
    }

    suspend fun confirmRestore(onRestored: suspend () -> Unit = {}) {
        val shown = _state.value as? BackupUiState.Preview ?: return
        if (!shown.preview.enoughSpace) {
            _state.value = shown.copy(error = BackupCopy.NOT_ENOUGH_SPACE)
            return
        }
        val (file, password) = pending ?: return
        _state.value = restoreWorking(BackupCopy.restoring(0, shown.preview.comics), 0, shown.preview.comics)
        val result = try {
            openArchive(file, password).use { reader ->
                restore(reader) { done, total -> _state.value = restoreWorking(BackupCopy.restoring(done, total), done, total) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = ready(error = refusal(e))
            return
        } finally {
            pending = null
        }
        try {
            onRestored()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The restore itself has already completed; a best-effort completion hook must not relabel it as damaged.
        }
        _state.value = ready(message = BackupCopy.restored(result))
    }

    fun dismissPreview() {
        pending = null
        _state.value = ready()
    }

    private fun exportWorking(line: String, bytes: Long, totalBytes: Long) =
        BackupUiState.Working(line, fraction = fraction(bytes, totalBytes), cancellable = true)

    private fun restoreWorking(line: String, done: Int, total: Int) =
        BackupUiState.Working(line, fraction = fraction(done.toLong(), total.toLong()), cancellable = false)

    private fun fraction(done: Long, total: Long): Float? =
        if (total == 0L) null else (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)

    private fun itemCount(plan: ArchiveExporter.ExportPlan): Int = plan.comics.size + plan.series.size

    private fun refusal(e: Exception): String = when (e) {
        is EncryptedBackupException ->
            if (e.message == "BACKUP_DAMAGED" || e.message == "BACKUP_TRUNCATED") BackupCopy.DAMAGED else BackupCopy.WRONG_PASSWORD
        is ArchiveFormatException ->
            if (e.message?.contains("newer") == true) BackupCopy.NEWER_APP else BackupCopy.DAMAGED
        else -> BackupCopy.DAMAGED
    }

    companion object {
        const val MIN_PASSWORD = 6
    }
}
