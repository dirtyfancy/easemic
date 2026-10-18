package com.easemic.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.easemic.app.feature.settings.backup.ArchiveRestorer
import com.easemic.app.feature.settings.backup.BackupCopy
import com.easemic.app.feature.settings.backup.BackupUiState
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.ui.components.PclButton
import com.easemic.app.ui.components.PclOutlinedButton
import com.easemic.app.ui.components.PclTextButton
import com.easemic.app.ui.theme.quietShelfPalette

/** Copy the password dialog and the page lead still share. */
object OpenCoreSealedPackageCopy {
    val intro: String get() = tr("One file, locked with a password you choose. Sign-ins stay on this phone.", "一个文件，用你选择的密码锁定。登录信息只保存在本机。", "一個檔案，使用你選擇的密碼鎖定。登入資訊只會保存在本機。", "1つのファイルを選んだパスワードでロックします。サインイン情報はこの端末に残ります。")
    val PASSWORD_TITLE: String get() = AppStrings.setPasswordForFile
    val PASSWORD_HELP: String get() = AppStrings.backupPasswordIrrecoverable
}

/**
 * #D9 Move to a new phone. [onBack] stays so the host compiles; system back already
 * returns to Settings, and this page draws no back control.
 */
@Composable
fun BackupRestoreScreen(
    state: BackupUiState,
    onExport: (includeDownloads: Boolean, password: String, confirm: String) -> Unit,
    onRestore: () -> Unit,
    onConfirmRestore: () -> Unit,
    onDismissPreview: () -> Unit,
    onBack: () -> Unit,
    onCancelWork: () -> Unit = {},
    /** An older state-only `.pclbackup` (#D7 spec §7): asks its password, then picks the file. */
    onRestoreLegacy: () -> Unit = {},
) {
    var exportIncludesDownloads by remember { mutableStateOf<Boolean?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .semantics { contentDescription = AppStrings.backupScroll },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(AppStrings.moveToNewPhone, style = MaterialTheme.typography.titleLarge)
        when (state) {
            BackupUiState.Sizing -> Text(AppStrings.measuring, color = quietShelfPalette().muted)
            is BackupUiState.Working -> WorkingContent(state, onCancelWork)
            is BackupUiState.Preview -> PreviewContent(state, onConfirmRestore, onDismissPreview)
            is BackupUiState.Ready -> ReadyContent(
                state = state,
                onExport = { exportIncludesDownloads = it },
                onRestore = onRestore,
                onRestoreLegacy = onRestoreLegacy,
            )
        }
    }
    exportIncludesDownloads?.let { include ->
        ExportPasswordDialog(
            onContinue = { pw, confirm ->
                exportIncludesDownloads = null
                onExport(include, pw, confirm)
            },
            onDismiss = { exportIncludesDownloads = null },
        )
    }
}

@Composable
private fun ReadyContent(
    state: BackupUiState.Ready,
    onExport: (includeDownloads: Boolean) -> Unit,
    onRestore: () -> Unit,
    onRestoreLegacy: () -> Unit,
) {
    val palette = quietShelfPalette()
    var everythingSelected by remember(state.everythingComics) {
        mutableStateOf(state.everythingComics > 0)
    }
    if (state.message != null || state.error != null) {
        BackupGroup("backup-result") {
            state.message?.let { Text(it, color = palette.ink) }
            state.error?.let { Text(it, color = palette.statusBad) }
        }
    }
    Text(OpenCoreSealedPackageCopy.intro, style = MaterialTheme.typography.bodySmall, color = palette.muted)
    BackupGroup("backup-save-card") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(AppStrings.onThisPhone, style = MaterialTheme.typography.labelLarge, color = palette.muted)
            Text(AppStrings.saveBackup, style = MaterialTheme.typography.titleMedium, color = palette.ink)
        }
        BackupChoiceRow(
            tag = "backup-choice-everything",
            title = tr("Everything", "全部内容", "全部內容", "すべて"),
            subtitle = everythingChoiceSubtitle(state.everythingComics, state.everythingBytes),
            selected = everythingSelected,
            onClick = { everythingSelected = true },
        )
        BackupChoiceRow(
            tag = "backup-choice-collection",
            title = AppStrings.collectionAndProgressOnly,
            subtitle = AppStrings.backupSmallHint,
            selected = !everythingSelected,
            onClick = { everythingSelected = false },
        )
        PclButton(
            onClick = { onExport(everythingSelected) },
            modifier = Modifier.fillMaxWidth().testTag("backup-export"),
        ) {
            Text(AppStrings.chooseWhereToSave, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    BackupGroup("backup-restore-card") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(AppStrings.onNewPhone, style = MaterialTheme.typography.labelLarge, color = palette.muted)
            Text(AppStrings.restoreFromBackup, style = MaterialTheme.typography.titleMedium, color = palette.ink)
            Text(
                tr("Adds to what's here. Nothing is replaced or deleted.", "添加到现有内容，不会替换或删除任何内容。", "加入目前內容，不會取代或刪除任何內容。", "現在の内容に追加します。置き換えたり削除したりしません。"),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
        PclOutlinedButton(
            onClick = onRestore,
            modifier = Modifier.fillMaxWidth().testTag("backup-restore"),
        ) {
            Text(AppStrings.chooseBackupFile, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PclTextButton(
            onClick = onRestoreLegacy,
            modifier = Modifier.fillMaxWidth().testTag("backup-restore-legacy"),
        ) {
            Text(
                tr("Have an older .pclbackup? Restore it here", "有较旧的 .pclbackup？在这里恢复", "有較舊的 .pclbackup？在這裡還原", "古い .pclbackup がありますか？ここから復元できます"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PreviewContent(
    state: BackupUiState.Preview,
    onConfirmRestore: () -> Unit,
    onDismissPreview: () -> Unit,
) {
    val palette = quietShelfPalette()
    val preview = state.preview
    Column(
        modifier = Modifier.fillMaxWidth().testTag("backup-preview"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BackupSteps()
        state.fileName?.let { name ->
            Text(
                name,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = palette.ink,
            )
        }
        Text(AppStrings.thisBackupHas, style = MaterialTheme.typography.titleMedium, color = palette.ink)
        previewLines(preview).forEach { line ->
            Text(line, color = palette.ink)
        }
        Text(
            BackupCopy.spaceLine(preview.pageBytes, preview.freeBytes),
            color = if (preview.enoughSpace) palette.ink else palette.statusBad,
            modifier = Modifier.testTag("backup-space-line"),
        )
        state.error?.let { Text(it, color = palette.statusBad) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PclOutlinedButton(
                onClick = onDismissPreview,
                modifier = Modifier.weight(1f).testTag("backup-preview-cancel"),
            ) {
                Text(AppStrings.cancel, maxLines = 1)
            }
            PclButton(
                onClick = onConfirmRestore,
                enabled = preview.enoughSpace,
                modifier = Modifier.weight(1f).testTag("backup-preview-confirm"),
            ) {
                Text(AppStrings.restoreAction, maxLines = 1)
            }
        }
    }
}

@Composable
private fun WorkingContent(state: BackupUiState.Working, onCancelWork: () -> Unit) {
    val palette = quietShelfPalette()
    val fraction = state.fraction
    Column(
        modifier = Modifier.fillMaxWidth().testTag("backup-working"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(state.line, color = palette.ink)
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (state.cancellable) {
            PclOutlinedButton(onClick = onCancelWork, modifier = Modifier.fillMaxWidth()) {
                Text(AppStrings.cancel, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BackupSteps() {
    Row(
        modifier = Modifier
            .testTag("backup-steps")
            .semantics { contentDescription = AppStrings.fileCheckRestore },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StepLabel(tr("File", "文件", "檔案", "ファイル"), active = true)
        Text("·", color = quietShelfPalette().muted)
        StepLabel(tr("Check", "检查", "檢查", "確認"), active = true)
        Text("·", color = quietShelfPalette().muted)
        StepLabel(tr("Restore", "恢复", "還原", "復元"), active = false)
    }
}

@Composable
private fun StepLabel(text: String, active: Boolean) {
    val palette = quietShelfPalette()
    val color = if (active) palette.ink else palette.muted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (active) palette.ink else palette.line),
        )
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun BackupChoiceRow(
    tag: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = quietShelfPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) palette.ink else palette.line,
                shape = RoundedCornerShape(12.dp),
            )
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = palette.ink,
                unselectedColor = palette.muted,
            ),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = palette.ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.muted)
        }
    }
}

@Composable
private fun BackupGroup(
    testTag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = quietShelfPalette()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .border(1.dp, palette.line, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun ExportPasswordDialog(onContinue: (String, String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(OpenCoreSealedPackageCopy.PASSWORD_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(OpenCoreSealedPackageCopy.PASSWORD_HELP, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(tr("Password", "密码", "密碼", "パスワード")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("backup-password"),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(AppStrings.confirmPassword) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("backup-password-confirm"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onContinue(password, confirm) }, modifier = Modifier.testTag("backup-password-continue")) {
                Text(tr("Continue", "继续", "繼續", "続行"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(AppStrings.cancel) } },
    )
}

private fun everythingChoiceSubtitle(comics: Int, bytes: Long): String {
    if (comics <= 0) return AppStrings.noDownloadedComics
    val size = BackupCopy.everythingSize(comics, bytes).substringAfter("·").trim()
    val count = if (comics == 1) "1 downloaded comic" else "$comics downloaded comics"
    return tr("$count · $size, plus collection and progress", "$count · $size，另含收藏和进度", "$count · $size，另含收藏與進度", "$count · $size、コレクションと進捗を含む")
}

private fun previewLines(preview: ArchiveRestorer.Preview): List<String> = buildList {
    if (preview.comics > 0) add(BackupCopy.everythingSize(preview.comics, preview.pageBytes))
    if (preview.comicsAlreadyHere > 0) {
        add("${preview.comicsAlreadyHere} are already on this phone — skipped")
    }
    add("${preview.bookmarks} collected")
    add("Reading progress, history, settings")
}
