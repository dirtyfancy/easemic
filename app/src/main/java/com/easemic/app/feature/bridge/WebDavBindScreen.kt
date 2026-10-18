package com.easemic.app.feature.bridge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.easemic.app.ui.components.PclButton
import com.easemic.app.ui.components.PclOutlinedButton
import com.easemic.app.ui.components.PclTextButton
import com.easemic.app.i18n.tr
import com.easemic.app.i18n.AppStrings
import com.easemic.app.ui.theme.QuietShelfPalette
import com.easemic.app.ui.theme.quietShelfPalette
import kotlinx.coroutines.launch

/** #C50: copy for the WebDAV screens (mock approved 2026-09-26). */
object WebDavScreenCopy {
    val HEADLINE: String get() = tr("Any WebDAV drive.", "任意 WebDAV 网盘。", "任意 WebDAV 網盤。", "任意の WebDAV ドライブ。")
    val LEDE: String get() = tr("Comics live in a library folder on the server, encrypted before they leave this phone.", "漫画存储在服务器的书库文件夹中，离开本机前会先加密。", "漫畫儲存在伺服器的書庫資料夾中，離開本機前會先加密。", "マンガはサーバー上のライブラリフォルダに保存され、端末を離れる前に暗号化されます。")
    val SERVER: String get() = tr("Server", "服务器", "伺服器", "サーバー")
    val USERNAME: String get() = tr("Username", "用户名", "使用者名稱", "ユーザー名")
    val PASSWORD: String get() = tr("Password", "密码", "密碼", "パスワード")
    val FOLDER: String get() = tr("Folder (optional)", "文件夹（可选）", "資料夾（選填）", "フォルダ（任意）")
    val CONNECT: String get() = tr("Connect", "连接", "連接", "接続")
    val CONNECTING: String get() = tr("Connecting…", "正在连接…", "正在連接…", "接続中…")
    val CREATE_TITLE: String get() = tr("Set a library password", "设置书库密码", "設定書庫密碼", "ライブラリのパスワードを設定")
    val CREATE_BODY: String get() = tr("Every page is encrypted with it before upload. No one can reset it — not us, not your drive. Keep it somewhere safe.", "每页上传前都会使用此密码加密。任何人都无法重置，包括我们和网盘服务商。请妥善保存。", "每頁上傳前都會使用此密碼加密。任何人都無法重設，包括我們和雲端硬碟服務商。請妥善保存。", "アップロード前に各ページを暗号化します。私たちにもドライブにもリセットできません。安全な場所に保管してください。")
    val UNLOCK_TITLE: String get() = tr("This drive has a library", "此网盘已有书库", "此雲端硬碟已有書庫", "このドライブにはライブラリがあります")
    val UNLOCK_BODY: String get() = tr("Enter its password to open it on this phone. It stays saved here after that.", "输入密码即可在本机打开。之后密码会保存在本机。", "輸入密碼即可在本機開啟。之後密碼會儲存在本機。", "パスワードを入力するとこの端末で開けます。入力後は端末に保存されます。")
    val LIBRARY_PASSWORD: String get() = tr("Library password", "书库密码", "書庫密碼", "ライブラリのパスワード")
    val CONFIRM_PASSWORD: String get() = tr("Confirm password", "确认密码", "確認密碼", "パスワードを確認")
    val CREATE_ACTION: String get() = tr("Create library", "创建书库", "建立書庫", "ライブラリを作成")
    val UNLOCK_ACTION: String get() = tr("Unlock", "解锁", "解鎖", "ロックを解除")
    val WORKING: String get() = tr("Working…", "处理中…", "處理中…", "処理中…")
    val CANCEL: String get() = AppStrings.cancel
}

private val DavFieldTop = Color(0xFFECE7DC)
private val DavFieldTopDark = Color(0xFF1B1D24)

/** #C50: the WebDAV entry (credentials form) and the always-encrypted password steps that follow it. */
@Composable
fun WebDavBindScreen(
    presenter: WebDavBindPresenter,
    onBound: () -> Unit,
    onBack: () -> Unit,
) {
    val state by presenter.states.collectAsState()
    val scope = rememberCoroutineScope()
    when (state.step) {
        DriveBindStep.FORM, DriveBindStep.BOUND -> WebDavForm(
            state = state,
            onPreset = presenter::onPreset,
            onServerChange = presenter::onServerChange,
            onUsernameChange = presenter::onUsernameChange,
            onPasswordChange = presenter::onPasswordChange,
            onFolderChange = presenter::onFolderChange,
            onConnect = {
                scope.launch {
                    presenter.onConnect()
                    // #D16: a renewal goes straight to BOUND from the form.
                    if (presenter.state.step == DriveBindStep.BOUND) {
                        presenter.reset()
                        onBound()
                    }
                }
            },
            onBack = onBack,
        )
        DriveBindStep.CREATE_PASSWORD, DriveBindStep.UNLOCK_PASSWORD -> DriveLibraryPasswordStep(
            place = WebDavBoundLabel.format(com.easemic.app.sync.WebDavBinding(state.server, state.username, "", state.folder)),
            libraryPassword = state.libraryPassword,
            libraryConfirm = state.libraryConfirm,
            passwordError = state.passwordError,
            busy = state.busy,
            create = state.step == DriveBindStep.CREATE_PASSWORD,
            onPasswordChange = presenter::onLibraryPasswordChange,
            onConfirmChange = presenter::onLibraryConfirmChange,
            onSubmit = {
                scope.launch {
                    if (state.step == DriveBindStep.CREATE_PASSWORD) presenter.onCreateLibrary() else presenter.onUnlockLibrary()
                    if (presenter.state.step == DriveBindStep.BOUND) {
                        presenter.reset()
                        onBound()
                    }
                }
            },
            onCancel = presenter::onCancelPasswordStep,
        )
    }
}

@Composable
private fun WebDavForm(
    state: WebDavBindUiState,
    onPreset: (WebDavPreset) -> Unit,
    onServerChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onFolderChange: (String) -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit,
) {
    val palette = quietShelfPalette()
    val enabled = !state.busy
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("webdav-bind-form")
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DavHero(palette)
        // #D16: renewing asks only for the new password — the 123pan steps say where to mint it.
        if (state.renewing) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(CloudSignInCopy.RENEW_ACTION, style = MaterialTheme.typography.titleMedium, color = palette.ink)
                if (WebDavBindCopy.isPan123(state.server)) {
                    Text(
                        CloudSignInCopy.PAN123_STEPS,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.muted,
                        modifier = Modifier.testTag("webdav-renew-pan123-steps"),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.renewing) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WebDavPreset.entries.forEach { preset ->
                        PresetChip(preset.label, enabled, palette, "webdav-preset-${preset.name.lowercase()}") { onPreset(preset) }
                    }
                }
            }
            DavField(
                value = state.server,
                label = WebDavScreenCopy.SERVER,
                tag = "webdav-server",
                enabled = enabled && !state.renewing,
                error = state.formError.takeIf { state.errorField == WebDavField.SERVER },
                keyboard = KeyboardType.Uri,
                onChange = onServerChange,
            )
            DavField(
                value = state.username,
                label = WebDavScreenCopy.USERNAME,
                tag = "webdav-username",
                enabled = enabled && !state.renewing,
                error = state.formError.takeIf { state.errorField == WebDavField.USERNAME },
                keyboard = KeyboardType.Email,
                onChange = onUsernameChange,
            )
            DavField(
                value = state.password,
                label = WebDavScreenCopy.PASSWORD,
                tag = "webdav-password",
                enabled = enabled,
                error = state.formError.takeIf { state.errorField == WebDavField.PASSWORD },
                help = WebDavBindCopy.APP_PASSWORD_HELP,
                secret = true,
                onChange = onPasswordChange,
            )
            DavField(
                value = state.folder,
                label = WebDavScreenCopy.FOLDER,
                tag = "webdav-folder",
                enabled = enabled && !state.renewing,
                help = WebDavBindCopy.FOLDER_HELP,
                onChange = onFolderChange,
            )
            // Errors that belong to no single field (missing fields, a foreign folder).
            if (state.formError != null && state.errorField == null) {
                Text(
                    state.formError,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.statusBad,
                    modifier = Modifier.testTag("webdav-error"),
                )
            }
            PclButton(
                onClick = onConnect,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("webdav-connect"),
            ) {
                Text(if (state.busy) WebDavScreenCopy.CONNECTING else WebDavScreenCopy.CONNECT)
            }
            PclOutlinedButton(
                onClick = onBack,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(AppStrings.back)
            }
        }
    }
}

/** #C50: the always-encrypted library password step, shared by every drive. [place] = host or account. */
@Composable
fun DriveLibraryPasswordStep(
    place: String,
    libraryPassword: String,
    libraryConfirm: String,
    passwordError: String?,
    busy: Boolean,
    create: Boolean,
    onPasswordChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = quietShelfPalette()
    val enabled = !busy
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag(if (create) "webdav-create-password" else "webdav-unlock-password")
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(place, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
        Text(
            if (create) WebDavScreenCopy.CREATE_TITLE else WebDavScreenCopy.UNLOCK_TITLE,
            style = MaterialTheme.typography.headlineMedium,
            color = palette.ink,
        )
        Text(
            if (create) WebDavScreenCopy.CREATE_BODY else WebDavScreenCopy.UNLOCK_BODY,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.muted,
        )
        DavField(
            value = libraryPassword,
            label = WebDavScreenCopy.LIBRARY_PASSWORD,
            tag = "webdav-library-password",
            enabled = enabled,
            secret = true,
            error = passwordError.takeIf { !create },
            onChange = onPasswordChange,
        )
        if (create) {
            DavField(
                value = libraryConfirm,
                label = WebDavScreenCopy.CONFIRM_PASSWORD,
                tag = "webdav-library-confirm",
                enabled = enabled,
                secret = true,
                error = passwordError,
                onChange = onConfirmChange,
            )
        }
        PclButton(
            onClick = onSubmit,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("webdav-library-submit"),
        ) {
            Text(
                when {
                    busy -> WebDavScreenCopy.WORKING
                    create -> WebDavScreenCopy.CREATE_ACTION
                    else -> WebDavScreenCopy.UNLOCK_ACTION
                },
            )
        }
        PclTextButton(
            onClick = onCancel,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("webdav-library-cancel"),
        ) {
            Text(WebDavScreenCopy.CANCEL)
        }
    }
}

@Composable
private fun DavHero(palette: QuietShelfPalette) {
    val top = if (isSystemInDarkTheme()) DavFieldTopDark else DavFieldTop
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(top, palette.surface))),
    ) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(WebDavScreenCopy.HEADLINE, style = MaterialTheme.typography.headlineLarge, color = palette.ink)
            Text(WebDavScreenCopy.LEDE, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
        }
    }
}

@Composable
private fun PresetChip(label: String, enabled: Boolean, palette: QuietShelfPalette, tag: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = palette.ink,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(1.dp, palette.line, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun DavField(
    value: String,
    label: String,
    tag: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    error: String? = null,
    help: String? = null,
    secret: Boolean = false,
    keyboard: KeyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = error != null,
        supportingText = (error ?: help)?.let { text -> { Text(text) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    )
}
