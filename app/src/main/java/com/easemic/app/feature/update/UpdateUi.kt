package com.easemic.app.feature.update

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.easemic.app.i18n.AppStrings
import com.easemic.app.ui.theme.quietShelfPalette
import java.util.Locale
import kotlin.math.roundToInt

/** #E123: Phase of the in-app update dialog. */
sealed interface AppUpdateDialogPhase {
    data class Ready(val alreadyDownloaded: Boolean = false) : AppUpdateDialogPhase
    data class Downloading(val bytesReceived: Long, val totalBytes: Long) : AppUpdateDialogPhase
    data object Verifying : AppUpdateDialogPhase
    data class PermissionRequired(val apkPath: String) : AppUpdateDialogPhase
    data class Error(val reason: Reason) : AppUpdateDialogPhase {
        enum class Reason {
            DOWNLOAD_FAILED,
            VERIFICATION_FAILED,
        }
    }
}

/** #E1 / #E123 / #E124: the words of the update dialog, the More row and the Settings switch. */
object UpdateCopy {
    fun title(release: AppRelease) = AppStrings.updateAvailableTitle(release.versionName)
    val UPDATE: String get() = AppStrings.updateAction
    val INSTALL_NOW: String get() = AppStrings.installNowAction
    val RETRY: String get() = AppStrings.retry
    val LATER: String get() = AppStrings.laterAction
    val CANCEL: String get() = AppStrings.cancel
    val OPEN_SETTINGS: String get() = AppStrings.allowInSettingsAction
    val BROWSER_DOWNLOAD: String get() = AppStrings.browserDownloadAction
    val VERIFYING: String get() = AppStrings.verifyingUpdatePackage
    val PERMISSION_MESSAGE: String get() = AppStrings.updatePermissionMessage
    val DOWNLOAD_FAILED: String get() = AppStrings.updateDownloadFailed
    val VERIFICATION_FAILED: String get() = AppStrings.updateVerificationFailed
    val ROW_SUBTITLE: String get() = AppStrings.updateRowSubtitle
    val SWITCH_TITLE: String get() = AppStrings.checkForUpdatesSwitchTitle
    val SWITCH_SUBTITLE: String get() = AppStrings.checkForUpdatesSwitchSubtitle

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun downloadingLabel(bytesReceived: Long, totalBytes: Long): String {
        return if (totalBytes > 0L) {
            val pct = ((bytesReceived.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0) * 100.0).roundToInt()
            AppStrings.downloadingProgressLabel(pct, formatBytes(bytesReceived), formatBytes(totalBytes))
        } else {
            AppStrings.downloadingIndeterminateLabel(formatBytes(bytesReceived))
        }
    }
}

/**
 * #E1 / #E123: In-app update dialog supporting streaming APK download with live
 * progress, SHA-256 + archive verification, unknown-sources permission handoff,
 * cached APK instant install, and browser download fallback.
 */
@Composable
fun UpdateAvailableDialog(
    release: AppRelease,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    phase: AppUpdateDialogPhase = AppUpdateDialogPhase.Ready(alreadyDownloaded = false),
    onCancelDownload: () -> Unit = onLater,
    onOpenUnknownSources: () -> Unit = {},
    onOpenBrowser: (() -> Unit)? = null,
) {
    val palette = quietShelfPalette()
    val isDownloading = phase is AppUpdateDialogPhase.Downloading
    val isVerifying = phase is AppUpdateDialogPhase.Verifying
    val isPermission = phase is AppUpdateDialogPhase.PermissionRequired
    val isError = phase is AppUpdateDialogPhase.Error

    AlertDialog(
        onDismissRequest = {
            when {
                isVerifying -> Unit
                isDownloading -> onCancelDownload()
                else -> onLater()
            }
        },
        containerColor = palette.card,
        titleContentColor = palette.ink,
        textContentColor = palette.ink,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = UpdateCopy.title(release),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.ink,
                    modifier = Modifier.weight(1f),
                )
                val sizeBytes = release.sizeBytes
                if (sizeBytes != null && sizeBytes > 0L) {
                    Text(
                        text = UpdateCopy.formatBytes(sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .testTag("update-dialog-size"),
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (release.notes.isNotBlank()) {
                    Text(
                        text = release.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.ink,
                    )
                }
                when (phase) {
                    is AppUpdateDialogPhase.Ready -> Unit
                    is AppUpdateDialogPhase.Downloading -> {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("update-dialog-progress"),
                        ) {
                            if (phase.totalBytes > 0L) {
                                val progress = (phase.bytesReceived.toFloat() / phase.totalBytes.toFloat())
                                    .coerceIn(0f, 1f)
                                LinearProgressIndicator(
                                    progress = { progress },
                                    color = palette.statusOk,
                                    trackColor = palette.line,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(
                                    color = palette.statusOk,
                                    trackColor = palette.line,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Text(
                                text = UpdateCopy.downloadingLabel(phase.bytesReceived, phase.totalBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.muted,
                                modifier = Modifier.testTag("update-dialog-progress-label"),
                            )
                        }
                    }
                    is AppUpdateDialogPhase.Verifying -> {
                        Text(
                            text = UpdateCopy.VERIFYING,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            modifier = Modifier.testTag("update-dialog-verifying"),
                        )
                    }
                    is AppUpdateDialogPhase.PermissionRequired -> {
                        Text(
                            text = UpdateCopy.PERMISSION_MESSAGE,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            modifier = Modifier.testTag("update-dialog-permission"),
                        )
                    }
                    is AppUpdateDialogPhase.Error -> {
                        val message = when (phase.reason) {
                            AppUpdateDialogPhase.Error.Reason.VERIFICATION_FAILED ->
                                UpdateCopy.VERIFICATION_FAILED
                            AppUpdateDialogPhase.Error.Reason.DOWNLOAD_FAILED ->
                                UpdateCopy.DOWNLOAD_FAILED
                        }
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.statusWarn,
                            modifier = Modifier.testTag("update-dialog-error"),
                        )
                    }
                }
                if (!isDownloading && !isVerifying && onOpenBrowser != null) {
                    Text(
                        text = UpdateCopy.BROWSER_DOWNLOAD,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = palette.muted,
                        modifier = Modifier
                            .testTag("update-dialog-browser")
                            .clickable(onClick = onOpenBrowser)
                            .padding(vertical = 2.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (!isDownloading && !isVerifying) {
                if (isPermission) {
                    TextButton(
                        onClick = onOpenUnknownSources,
                        modifier = Modifier.testTag("update-dialog-update"),
                    ) {
                        Text(UpdateCopy.OPEN_SETTINGS, color = palette.statusOk)
                    }
                } else {
                    val label = when {
                        isError -> UpdateCopy.RETRY
                        phase is AppUpdateDialogPhase.Ready && phase.alreadyDownloaded -> UpdateCopy.INSTALL_NOW
                        else -> UpdateCopy.UPDATE
                    }
                    TextButton(
                        onClick = onUpdate,
                        modifier = Modifier.testTag("update-dialog-update"),
                    ) {
                        Text(label, color = palette.statusOk)
                    }
                }
            }
        },
        dismissButton = {
            if (isDownloading) {
                TextButton(
                    onClick = onCancelDownload,
                    modifier = Modifier.testTag("update-dialog-cancel"),
                ) {
                    Text(UpdateCopy.CANCEL, color = palette.muted)
                }
            } else {
                TextButton(
                    onClick = onLater,
                    enabled = !isVerifying,
                    modifier = Modifier.testTag("update-dialog-later"),
                ) {
                    Text(UpdateCopy.LATER, color = palette.muted)
                }
            }
        },
    )
}

/** #E1: the More row that stays until the user updates; tapping it reopens the dialog. */
@Composable
fun UpdateAvailableRow(release: AppRelease, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val palette = quietShelfPalette()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("update-row")
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(UpdateCopy.title(release), style = MaterialTheme.typography.titleMedium, color = palette.ink)
        Text(UpdateCopy.ROW_SUBTITLE, style = MaterialTheme.typography.bodySmall, color = palette.muted)
    }
}
