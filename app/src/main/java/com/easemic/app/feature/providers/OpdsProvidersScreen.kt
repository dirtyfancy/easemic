package com.easemic.app.feature.providers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.easemic.app.importing.opds.OpdsCatalogClient
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.ui.theme.quietShelfPalette
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

@Composable
fun OpdsProvidersScreen(
    providers: List<OpdsProviderConfig>,
    onConnect: suspend (name: String, url: String, username: String, password: String) -> OpdsConnectResult,
    onDisconnect: (id: String) -> Unit,
    onBack: () -> Unit,
    /** In-place, save-without-verify edit path. Not trailing so the legacy
     *  4-argument call shape still compiles, picking up this safe no-op. */
    onEditSave: (id: String, name: String, url: String, username: String, password: String) -> OpdsCatalogConnectEditResult =
        { _, _, _, _, _ -> OpdsCatalogConnectEditResult.NotFound },
) {
    val palette = quietShelfPalette()
    val scope = rememberCoroutineScope()
    var draftName by remember { mutableStateOf("") }
    var draftUrl by remember { mutableStateOf("") }
    var draftUsername by remember { mutableStateOf("") }
    var draftPassword by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            OpdsLibrarySettingsCopy.title,
            style = MaterialTheme.typography.titleLarge,
            color = palette.ink,
        )
        Text(
            tr("Paste a catalog URL. Credentials stay on this device.", "粘贴目录地址。凭据只保存在本机。", "貼上目錄位址。憑證只會儲存在本機。", "カタログ URL を貼り付けます。認証情報はこの端末に保存されます。"),
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
        )
        OpdsSectionCard(
            testTag = "opds-section-saved",
            title = tr("Connected libraries", "已连接的书库", "已連接的書庫", "接続済みライブラリ"),
            subtitle = tr("These appear as chips in Library.", "它们会显示为书库中的标签。", "它們會顯示為書庫中的標籤。", "ライブラリにチップとして表示されます。"),
        ) {
            if (providers.isEmpty()) {
                Text(
                    OpdsLibrarySettingsCopy.libraryCount(0),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
            } else {
                providers.forEach { provider ->
                    val editing = editingId == provider.id
                    if (editing) {
                        EditSurface(
                            provider = provider,
                            onSave = { name, url, username, password ->
                                when (onEditSave(provider.id, name, url, username, password)) {
                                    is OpdsCatalogConnectEditResult.Edited -> {
                                        editingId = null
                                        errorMessage = null
                                    }
                                    OpdsCatalogConnectEditResult.NotFound -> {
                                        editingId = null
                                    }
                                }
                            },
                            onDelete = { deleteConfirmId = provider.id },
                            onCancel = { editingId = null },
                        )
                    } else {
                        ProviderRow(
                            provider = provider,
                            connecting = connecting,
                            onClick = { editingId = provider.id },
                            onDisconnect = { onDisconnect(provider.id) },
                        )
                    }
                }
            }
        }
        OpdsSectionCard(
            testTag = "opds-section-add",
            title = tr("Add catalog", "添加目录", "新增目錄", "カタログを追加"),
            subtitle = tr("Kavita Auth-Key URLs or Komga username/password (HTTP Basic).", "Kavita 使用 Auth-Key 地址，Komga 使用用户名和密码（HTTP Basic）。", "Kavita 使用 Auth-Key 位址，Komga 使用使用者名稱與密碼（HTTP Basic）。", "Kavita は Auth-Key URL、Komga はユーザー名とパスワード（HTTP Basic）を使用します。"),
        ) {
            OutlinedTextField(
                value = draftName,
                onValueChange = { draftName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("Display name", "显示名称", "顯示名稱", "表示名")) },
                singleLine = true,
                enabled = !connecting,
            )
            OutlinedTextField(
                value = draftUrl,
                onValueChange = { draftUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("OPDS URL", "OPDS 地址", "OPDS 位址", "OPDS URL")) },
                singleLine = true,
                enabled = !connecting,
            )
            OutlinedTextField(
                value = draftUsername,
                onValueChange = { draftUsername = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("Username (optional)", "用户名（可选）", "使用者名稱（選填）", "ユーザー名（任意）")) },
                singleLine = true,
                enabled = !connecting,
            )
            OutlinedTextField(
                value = draftPassword,
                onValueChange = { draftPassword = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("Password (optional)", "密码（可选）", "密碼（選填）", "パスワード（任意）")) },
                singleLine = true,
                enabled = !connecting,
            )
            errorMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    val url = draftUrl.trim()
                    if (url.isEmpty() || connecting) return@Button
                    val name = draftName.trim()
                    val username = draftUsername.trim()
                    val password = draftPassword
                    connecting = true
                    errorMessage = null
                    scope.launch {
                        try {
                            when (val result = onConnect(name, url, username, password)) {
                                is OpdsConnectResult.Connected -> {
                                    draftName = ""
                                    draftUrl = ""
                                    draftUsername = ""
                                    draftPassword = ""
                                    errorMessage = null
                                }
                                OpdsConnectResult.AlreadyConnected -> {
                                    errorMessage = OpdsLibrarySettingsCopy.alreadyConnected
                                }
                                is OpdsConnectResult.Failure -> {
                                    errorMessage = OpdsLibrarySettingsCopy.connectFailure(result)
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            errorMessage = OpdsLibrarySettingsCopy.couldNotConnect
                        } finally {
                            connecting = false
                        }
                    }
                },
                enabled = !connecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(OpdsLibrarySettingsCopy.connect)
            }
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Text(AppStrings.back)
        }
    }

    val pendingDelete = deleteConfirmId
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            title = { Text(tr("Delete this library?", "删除此书库？", "刪除此書庫？", "このライブラリを削除しますか？")) },
            text = {
                Text(
                    tr("The binding is removed from this device. Content already in your library is kept.", "本机将移除此绑定，书库中已有内容会保留。", "本機將移除此連結，書庫中已有內容會保留。", "この端末からバインドを削除します。ライブラリ内のコンテンツは保持されます。"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmId = null
                        editingId = null
                        onDisconnect(pendingDelete)
                    },
                ) {
                    Text(tr("Delete library", "删除书库", "刪除書庫", "ライブラリを削除"))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmId = null }) {
                    Text(tr("Keep library", "保留书库", "保留書庫", "ライブラリを保持"))
                }
            },
        )
    }
}

@Composable
private fun ProviderRow(
    provider: OpdsProviderConfig,
    connecting: Boolean,
    onClick: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val palette = quietShelfPalette()
    val unreachable = provider.unreachableSinceEpochMs != null
    val rowInk = if (unreachable) palette.muted else palette.ink
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (unreachable) palette.card else palette.card,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("opds-provider-row-${provider.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = rowInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    OpdsCatalogClient.redactUrl(provider.catalogUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (unreachable) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    palette.ink
                                },
                                shape = CircleShape,
                            ),
                    )
                    Text(
                        OpdsLibrarySettingsCopy.healthLine(provider, System.currentTimeMillis()),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            OutlinedButton(
                onClick = onDisconnect,
                enabled = !connecting,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("opds-disconnect-${provider.id}"),
            ) {
                Text(OpdsLibrarySettingsCopy.disconnect)
            }
        }
    }
}

@Composable
private fun EditSurface(
    provider: OpdsProviderConfig,
    onSave: (name: String, url: String, username: String, password: String) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = quietShelfPalette()
    var name by remember(provider.id) { mutableStateOf(provider.displayName) }
    var url by remember(provider.id) { mutableStateOf(provider.catalogUrl) }
    var username by remember(provider.id) { mutableStateOf(provider.username) }
    var password by remember(provider.id) { mutableStateOf(provider.password) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = palette.card,
        border = BorderStroke(1.dp, palette.line),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("opds-edit-${provider.id}"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                tr("Edit library", "编辑书库", "編輯書庫", "ライブラリを編集"),
                style = MaterialTheme.typography.titleMedium,
                color = palette.ink,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("opds-edit-name"),
                label = { Text(tr("Display name", "显示名称", "顯示名稱", "表示名")) },
                singleLine = true,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("opds-edit-url"),
                label = { Text(tr("OPDS URL", "OPDS 地址", "OPDS 位址", "OPDS URL")) },
                singleLine = true,
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("opds-edit-username"),
                label = { Text(tr("Username (optional)", "用户名（可选）", "使用者名稱（選填）", "ユーザー名（任意）")) },
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("opds-edit-password"),
                label = { Text(tr("Password (optional)", "密码（可选）", "密碼（選填）", "パスワード（任意）")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(AppStrings.cancel)
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(AppStrings.delete, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = { onSave(name, url, username, password) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(AppStrings.save)
                }
            }
        }
    }
}

@Composable
private fun OpdsSectionCard(
    testTag: String,
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = quietShelfPalette()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = palette.card,
        border = BorderStroke(1.dp, palette.line),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = palette.ink)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                }
            }
            content()
        }
    }
}
