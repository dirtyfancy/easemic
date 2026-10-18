package com.easemic.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.easemic.app.ui.components.PclTextButton
import com.easemic.app.ui.theme.quietShelfPalette
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr

/** Which backup action is waiting for a password. */
enum class BackupPassphraseAction { EXPORT_STATE, IMPORT_STATE, EXPORT_MEDIA, IMPORT_MEDIA }

data class BackupPassphrasePromptState(
    val action: BackupPassphraseAction,
    val password: String = "",
    val confirm: String = "",
    val errorMessage: String? = null,
) {
    /** Export prompts require typing the password twice; import asks once. */
    val requiresConfirm: Boolean
        get() = action == BackupPassphraseAction.EXPORT_STATE ||
            action == BackupPassphraseAction.EXPORT_MEDIA
}

object BackupPassphrasePromptCopy {
    val TITLE_EXPORT: String get() = AppStrings.setBackupPassword
    val TITLE_IMPORT: String get() = AppStrings.enterBackupPassword
    val HELPER_EXPORT: String get() = tr("This password encrypts the file. If you forget it, the backup cannot be opened.", "此密码用于加密文件。忘记密码后将无法打开备份。", "此密碼用於加密檔案。忘記後將無法開啟備份。", "このパスワードでファイルを暗号化します。忘れるとバックアップを開けません。")
    val PASSWORD_LABEL: String get() = tr("Password", "密码", "密碼", "パスワード")
    val CONFIRM_LABEL: String get() = AppStrings.confirmPassword
    val EMPTY_ERROR: String get() = AppStrings.enterPassword
    val MISMATCH_ERROR: String get() = AppStrings.passwordsDoNotMatch
    val CONTINUE: String get() = tr("Continue", "继续", "繼續", "続行")
    val CANCEL: String get() = AppStrings.cancel
}

/** Null when invalid (sets errorMessage via [validated]); the password otherwise. */
fun BackupPassphrasePromptState.validated(): BackupPassphrasePromptState =
    when {
        password.isEmpty() -> copy(errorMessage = BackupPassphrasePromptCopy.EMPTY_ERROR)
        requiresConfirm && password != confirm ->
            copy(errorMessage = BackupPassphrasePromptCopy.MISMATCH_ERROR)
        else -> copy(errorMessage = null)
    }

fun BackupPassphrasePromptState.isValid(): Boolean = validated().errorMessage == null && password.isNotEmpty()

@Composable
fun BackupPassphraseDialog(
    state: BackupPassphrasePromptState,
    onPasswordChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = quietShelfPalette()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (state.requiresConfirm) {
                    BackupPassphrasePromptCopy.TITLE_EXPORT
                } else {
                    BackupPassphrasePromptCopy.TITLE_IMPORT
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.requiresConfirm) {
                    Text(
                        BackupPassphrasePromptCopy.HELPER_EXPORT,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                }

                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = { Text(BackupPassphrasePromptCopy.PASSWORD_LABEL) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = state.errorMessage != null,
                    modifier = Modifier.testTag("backup-passphrase-field"),
                )

                if (state.requiresConfirm) {
                    OutlinedTextField(
                        value = state.confirm,
                        onValueChange = onConfirmChange,
                        label = { Text(BackupPassphrasePromptCopy.CONFIRM_LABEL) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = state.errorMessage != null,
                        modifier = Modifier.testTag("backup-passphrase-confirm"),
                    )
                }

                if (state.errorMessage != null) {
                    Text(
                        state.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("backup-passphrase-error"),
                    )
                }
            }
        },
        confirmButton = {
            PclTextButton(
                onClick = onSubmit,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("backup-passphrase-continue"),
            ) {
                Text(BackupPassphrasePromptCopy.CONTINUE)
            }
        },
        dismissButton = {
            PclTextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("backup-passphrase-cancel"),
            ) {
                Text(BackupPassphrasePromptCopy.CANCEL)
            }
        },
    )
}
