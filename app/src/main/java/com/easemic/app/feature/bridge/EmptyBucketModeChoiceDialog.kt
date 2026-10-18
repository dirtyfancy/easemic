package com.easemic.app.feature.bridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.window.DialogProperties
import com.easemic.app.ui.components.PclTextButton
import com.easemic.app.ui.theme.quietShelfPalette

/**
 * #C57: the mandatory "encrypt or not" question for a bind to an EMPTY bucket.
 * Outside taps do nothing; Back is Cancel. The bucket is not enabled until one
 * of the two choices commits.
 */
@Composable
fun EmptyBucketModeChoiceDialog(
    state: ModeChoiceUiState,
    onEncryptSelected: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onTurnOnEncryption: () -> Unit,
    onDontEncrypt: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = quietShelfPalette()
    val enabled = !state.busy
    AlertDialog(
        onDismissRequest = { if (enabled) onCancel() },
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        modifier = Modifier.testTag("mode-choice-dialog"),
        title = { Text(ModeChoiceCopy.TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ModeChoiceCopy.BODY, style = MaterialTheme.typography.bodyMedium, color = palette.ink)
                if (state.encryptSelected) {
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.testTag("mode-choice-password"),
                    )
                    OutlinedTextField(
                        value = state.confirm,
                        onValueChange = onConfirmChange,
                        label = { Text("Confirm password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.testTag("mode-choice-confirm"),
                    )
                    Text(
                        LibraryLockCopy.FORGET_WARNING,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.statusWarn,
                        modifier = Modifier.testTag("mode-choice-warning"),
                    )
                }
                val error = state.errorMessage
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("mode-choice-error"),
                    )
                }
            }
        },
        confirmButton = {
            if (state.encryptSelected) {
                PclTextButton(
                    onClick = onTurnOnEncryption,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("mode-choice-create"),
                ) { Text(LibraryLockCopy.CREATE_ACTION) }
            } else {
                PclTextButton(
                    onClick = onEncryptSelected,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("mode-choice-encrypt"),
                ) { Text(ModeChoiceCopy.ENCRYPT_OPTION) }
            }
        },
        dismissButton = {
            Row {
                PclTextButton(
                    onClick = onDontEncrypt,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("mode-choice-plain"),
                ) { Text(ModeChoiceCopy.DONT_ENCRYPT) }
                PclTextButton(
                    onClick = onCancel,
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("mode-choice-cancel"),
                ) { Text(ModeChoiceCopy.CANCEL) }
            }
        },
    )
}
