package com.easemic.app.feature.bridge

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easemic.app.ui.components.PclOutlinedButton
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.ui.theme.QuietShelfPalette
import com.easemic.app.ui.theme.quietShelfPalette
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/** #C50: OneDrive entry copy (mock approved 2026-09-26). */
object OneDriveScreenCopy {
    val HEADLINE: String get() = tr("Your OneDrive.\nYour lock.", "你的 OneDrive。\n你的锁。", "你的 OneDrive。\n你的鎖。", "あなたの OneDrive。\nあなたのロック。")
    val LEDE: String get() = tr("Comics live in your OneDrive's Apps folder, encrypted before they leave this phone.", "漫画存储在 OneDrive 的 Apps 文件夹中，离开本机前会先加密。", "漫畫儲存在 OneDrive 的 Apps 資料夾中，離開本機前會先加密。", "マンガは OneDrive の Apps フォルダに保存され、端末を離れる前に暗号化されます。")
    val STEP_1_TITLE: String get() = tr("Sign in with Microsoft", "使用 Microsoft 登录", "使用 Microsoft 登入", "Microsoft でサインイン")
    val STEP_1_HELPER: String get() = tr("In your browser. We never see your password.", "在浏览器中完成。我们不会看到你的密码。", "在瀏覽器中完成。我們不會看到你的密碼。", "ブラウザで行います。パスワードを見ることはありません。")
    val STEP_2_TITLE: String get() = tr("Set a library password", "设置书库密码", "設定書庫密碼", "ライブラリのパスワードを設定")
    val STEP_2_HELPER: String get() = tr("Only you can open the files.", "只有你能打开这些文件。", "只有你能開啟這些檔案。", "ファイルを開けるのはあなた自身だけです。")
    val STEP_3_TITLE: String get() = tr("Collect comics to it", "将漫画收藏到这里", "將漫畫收藏到這裡", "ここにマンガをコレクション")
    val STEP_3_HELPER: String get() = tr("Read them from any connected device.", "在任意已连接的设备上阅读。", "在任何已連接的裝置上閱讀。", "接続したどの端末からでも読めます。")
    val PICKER_SUBTITLE: String get() = tr("Sign in with Microsoft.", "使用 Microsoft 登录。", "使用 Microsoft 登入。", "Microsoft でサインインします。")
}

object MicrosoftBrand {
    val Blue = Color(0xFF0078D4)
    val ButtonFill = Color(0xFF2F2F2F)
    val FieldTopLight = Color(0xFFE3EEF9)
    val FieldTopDark = Color(0xFF16202C)
    val Red = Color(0xFFF25022)
    val Green = Color(0xFF7FBA00)
    val Cyan = Color(0xFF00A4EF)
    val Yellow = Color(0xFFFFB900)
}

/** The OneDrive cloud shape, drawn so no bitmap asset ships (nominative use, like the Cloudflare mark). */
fun oneDriveCloudMark(): ImageVector = ImageVector.Builder(
    name = "OneDriveCloudMark", defaultWidth = 32.dp, defaultHeight = 20.dp, viewportWidth = 32f, viewportHeight = 20f,
).apply {
    path(fill = SolidColor(MicrosoftBrand.Blue)) {
        moveTo(12f, 19f); lineTo(28f, 19f)
        arcToRelative(4f, 4f, 0f, false, false, 0.6f, -8f)
        arcTo(8f, 8f, 0f, false, false, 13.4f, 8f)
        arcTo(6f, 6f, 0f, false, false, 5f, 14f)
        arcToRelative(5f, 5f, 0f, false, false, 7f, 5f)
        close()
    }
}.build()

/** Four-square Microsoft logo for the sign-in button (brand guidance: logo + "Sign in with Microsoft"). */
fun microsoftLogo(): ImageVector = ImageVector.Builder(
    name = "MicrosoftLogo", defaultWidth = 16.dp, defaultHeight = 16.dp, viewportWidth = 16f, viewportHeight = 16f,
).apply {
    listOf(Triple(0f, 0f, MicrosoftBrand.Red), Triple(8.6f, 0f, MicrosoftBrand.Green), Triple(0f, 8.6f, MicrosoftBrand.Cyan), Triple(8.6f, 8.6f, MicrosoftBrand.Yellow))
        .forEach { (x, y, c) -> path(fill = SolidColor(c)) { moveTo(x, y); lineTo(x + 7.4f, y); lineTo(x + 7.4f, y + 7.4f); lineTo(x, y + 7.4f); close() } }
}.build()

private val oneDriveMark: ImageVector by lazy { oneDriveCloudMark() }
private val msLogo: ImageVector by lazy { microsoftLogo() }

@Composable
fun OneDriveBindScreen(
    presenter: OAuthDriveBindPresenter<*>,
    onBound: () -> Unit,
    onBack: () -> Unit,
    kind: DriveKind = DriveKind.ONEDRIVE,
) {
    val state by presenter.states.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(presenter) {
        val handle = DriveRedirectBus.setListener(kind) { redirect ->
            when (val r = redirect.result) {
                is CloudflareOAuthCallbackResult.Success -> scope.launch { presenter.onRedirect(r.code, r.state) }
                is CloudflareOAuthCallbackResult.Failure -> presenter.onRedirectFailure(r.error)
            }
        }
        try {
            awaitCancellation()
        } finally {
            DriveRedirectBus.clearListener(handle)
        }
    }
    // Any path that ends bound (a new library, an unlock, or a "sign in again" that only renewed
    // the token) leaves this screen; before, only the password step's submit did.
    LaunchedEffect(state.step) {
        if (state.step == DriveBindStep.BOUND) {
            presenter.reset()
            onBound()
        }
    }
    when (state.step) {
        DriveBindStep.FORM, DriveBindStep.BOUND -> if (kind == DriveKind.GOOGLE_DRIVE) {
            GoogleDriveEntry(state, onSignIn = presenter::onSignIn, onBack = onBack)
        } else {
            OneDriveEntry(state, onSignIn = presenter::onSignIn, onBack = onBack)
        }
        DriveBindStep.CREATE_PASSWORD, DriveBindStep.UNLOCK_PASSWORD -> DriveLibraryPasswordStep(
            place = "Signed in as ${state.account.orEmpty()}",
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
                }
            },
            onCancel = presenter::onCancelPasswordStep,
        )
    }
}

@Composable
private fun OneDriveEntry(state: OneDriveBindUiState, onSignIn: () -> Unit, onBack: () -> Unit) {
    val palette = quietShelfPalette()
    val top = if (isSystemInDarkTheme()) MicrosoftBrand.FieldTopDark else MicrosoftBrand.FieldTopLight
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("onedrive-bind")
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(top, palette.surface)))) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(oneDriveMark, contentDescription = null, modifier = Modifier.width(72.dp))
                Text(OneDriveScreenCopy.HEADLINE, style = MaterialTheme.typography.headlineLarge, color = palette.ink)
                Text(OneDriveScreenCopy.LEDE, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
            }
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Step("01", OneDriveScreenCopy.STEP_1_TITLE, OneDriveScreenCopy.STEP_1_HELPER, palette)
            Step("02", OneDriveScreenCopy.STEP_2_TITLE, OneDriveScreenCopy.STEP_2_HELPER, palette)
            Step("03", OneDriveScreenCopy.STEP_3_TITLE, OneDriveScreenCopy.STEP_3_HELPER, palette)
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = palette.statusBad, modifier = Modifier.testTag("onedrive-error"))
            }
            Button(
                onClick = onSignIn,
                enabled = !state.busy,
                colors = ButtonDefaults.buttonColors(containerColor = MicrosoftBrand.ButtonFill, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("onedrive-sign-in"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(msLogo, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(if (state.busy) tr("Signing in…", "正在登录…", "正在登入…", "サインイン中…") else OneDriveBindCopy.SIGN_IN)
                }
            }
            PclOutlinedButton(onClick = onBack, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(AppStrings.back)
            }
        }
    }
}

@Composable
private fun Step(numeral: String, title: String, helper: String, palette: QuietShelfPalette) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(numeral, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MicrosoftBrand.Blue)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = palette.ink)
            Text(helper, style = MaterialTheme.typography.bodySmall, color = palette.muted)
        }
    }
}

/** #C50: Google Drive entry copy (mock approved 2026-09-26). */
object GoogleDriveScreenCopy {
    val HEADLINE: String get() = tr("Your Drive.\nYour lock.", "你的云端硬盘。\n你的锁。", "你的雲端硬碟。\n你的鎖。", "あなたのドライブ。\nあなたのロック。")
    val LEDE: String get() = tr("Comics live in a library folder in your Drive, encrypted before they leave this phone.", "漫画存储在 Drive 的书库文件夹中，离开本机前会先加密。", "漫畫儲存在 Drive 的書庫資料夾中，離開本機前會先加密。", "マンガは Drive のライブラリフォルダに保存され、端末を離れる前に暗号化されます。")
    val CAVEAT: String get() = tr("Needs a network that can reach Google.", "需要能够访问 Google 的网络。", "需要能連線 Google 的網路。", "Google に接続できるネットワークが必要です。")
    val SIGN_IN: String get() = tr("Sign in with Google", "使用 Google 登录", "使用 Google 登入", "Google でサインイン")
    val PICKER_TITLE: String get() = "Google Drive"
}

object GoogleBrand {
    val Blue = Color(0xFF4285F4)
    val Green = Color(0xFF34A853)
    val Yellow = Color(0xFFFBBC04)
    val Red = Color(0xFFEA4335)
    val ButtonFill = Color(0xFF131314)
    val FieldTopLight = Color(0xFFE8EEF8)
    val FieldTopDark = Color(0xFF151B26)
}

/** The Drive triangle, simplified; drawn so no bitmap ships (nominative use). */
fun googleDriveMark(): ImageVector = ImageVector.Builder(
    name = "GoogleDriveMark", defaultWidth = 24.dp, defaultHeight = 21.dp, viewportWidth = 87f, viewportHeight = 78f,
).apply {
    path(fill = SolidColor(GoogleBrand.Green)) { moveTo(43.7f, 25f); lineTo(29.9f, 1.1f); lineTo(0f, 53f); lineTo(27.5f, 53f); close() }
    path(fill = SolidColor(GoogleBrand.Yellow)) { moveTo(43.7f, 25f); lineTo(57.5f, 1.1f); lineTo(29.9f, 1.1f); close() }
    path(fill = SolidColor(GoogleBrand.Yellow)) { moveTo(57.5f, 1.1f); lineTo(87f, 53f); lineTo(59.8f, 53f); lineTo(43.7f, 25f); close() }
    path(fill = SolidColor(GoogleBrand.Blue)) { moveTo(27.5f, 53f); lineTo(59.8f, 53f); lineTo(73.6f, 77f); lineTo(13.7f, 77f); close() }
    path(fill = SolidColor(GoogleBrand.Red)) { moveTo(59.8f, 53f); lineTo(87f, 53f); lineTo(73.6f, 77f); close() }
}.build()

private val gdriveMark: ImageVector by lazy { googleDriveMark() }

@Composable
private fun GoogleDriveEntry(state: OneDriveBindUiState, onSignIn: () -> Unit, onBack: () -> Unit) {
    val palette = quietShelfPalette()
    val top = if (isSystemInDarkTheme()) GoogleBrand.FieldTopDark else GoogleBrand.FieldTopLight
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("gdrive-bind").padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(top, palette.surface)))) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(gdriveMark, contentDescription = null, modifier = Modifier.width(56.dp))
                Text(GoogleDriveScreenCopy.HEADLINE, style = MaterialTheme.typography.headlineLarge, color = palette.ink)
                Text(GoogleDriveScreenCopy.LEDE, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                Text(GoogleDriveScreenCopy.CAVEAT, style = MaterialTheme.typography.bodySmall, color = palette.statusWarn)
            }
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GStep("01", GoogleDriveScreenCopy.SIGN_IN, OneDriveScreenCopy.STEP_1_HELPER, palette)
            GStep("02", OneDriveScreenCopy.STEP_2_TITLE, OneDriveScreenCopy.STEP_2_HELPER, palette)
            GStep("03", OneDriveScreenCopy.STEP_3_TITLE, OneDriveScreenCopy.STEP_3_HELPER, palette)
        }
        Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = palette.statusBad, modifier = Modifier.testTag("gdrive-error")) }
            Button(
                onClick = onSignIn,
                enabled = !state.busy,
                colors = ButtonDefaults.buttonColors(containerColor = GoogleBrand.ButtonFill, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gdrive-sign-in"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(gdriveMark, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (state.busy) tr("Signing in…", "正在登录…", "正在登入…", "サインイン中…") else GoogleDriveScreenCopy.SIGN_IN)
                }
            }
            PclOutlinedButton(onClick = onBack, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(AppStrings.back) }
        }
    }
}

@Composable
private fun GStep(numeral: String, title: String, helper: String, palette: QuietShelfPalette) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(numeral, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = GoogleBrand.Blue)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = palette.ink)
            Text(helper, style = MaterialTheme.typography.bodySmall, color = palette.muted)
        }
    }
}
