package com.easemic.app.feature.bridge

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.easemic.app.i18n.tr
import com.easemic.app.ui.components.PclOutlinedButton
import com.easemic.app.ui.theme.QuietShelfPalette
import com.easemic.app.ui.theme.quietShelfPalette

/** #C50 + #E121 + #E124: copy for the unbound Cloud storage picker. */
object CloudStoragePickerCopy {
    val HEADLINE: String
        get() = tr("Your own cloud.\nNot ours.", "属于你自己的云库。\n而非我们的。", "屬於你自己的雲端書庫。\n而非我們的。", "あなた自身のクラウド。\n私たちのサーバーではありません。")
    val LEDE: String
        get() = tr("Pick where your comics live. One place per library.", "选择你的漫画存储位置。每个书库绑定一处云端。", "選擇你的漫畫儲存位置。每個書庫綁定一處雲端。", "マンガの保存先を選択してください。1つのライブラリにつき1か所です。")
    val FAQ_TRIGGER: String
        get() = tr("Which storage should I choose?", "我该怎么选云存储？", "我該怎麼選雲端儲存？", "どのストレージを選ぶべきですか？")
    val FAQ_TITLE: String
        get() = tr("Choosing a storage place", "如何选择云存储", "如何選擇雲端儲存", "ストレージの選び方")
    val FAQ_S3_HEADING: String
        get() = tr("Cloudflare R2, Bunny.net & Backblaze B2", "Cloudflare R2、Bunny.net 与 Backblaze B2", "Cloudflare R2、Bunny.net 與 Backblaze B2", "Cloudflare R2、Bunny.net、Backblaze B2")
    val FAQ_S3_BODY: String
        get() = tr(
            "If you don't already pay for a cloud drive subscription, dedicated object storage is a great fit: pages open noticeably faster and pay-as-you-go storage typically costs from $0 (free 10 GB tiers on R2 and Backblaze B2) to a few cents a month.",
            "如果你平时没有开通网盘会员，非常推荐使用对象存储：翻页加载明显更快，而且按量计费极其便宜（Cloudflare R2 和 Backblaze B2 均有 10 GB 免费额度，超出后每月通常也只需几美分）。",
            "如果你平時沒有開通網盤會員，非常推薦使用物件儲存：翻頁載入明顯更快，而且按量計費極其便宜（Cloudflare R2 與 Backblaze B2 均有 10 GB 免費額度，超出後每月通常也只需幾美分）。",
            "クラウドドライブの有料プランを契約していない場合は、オブジェクトストレージが最適です。ページの表示が圧倒的に速く、従量課金も月0ドル（R2とBackblaze B2は10GBまで無料）から数セント程度と非常に安価です。",
        )
    val FAQ_DRIVE_HEADING: String
        get() = tr("Google Drive, OneDrive & WebDAV", "Google Drive、OneDrive 与 WebDAV", "Google Drive、OneDrive 與 WebDAV", "Google Drive、OneDrive、WebDAV")
    val FAQ_DRIVE_BODY: String
        get() = tr(
            "If you already have a Google Drive, Microsoft 365, or WebDAV subscription with spare space, using what you already have works well too — page loads are just a bit slower than dedicated object storage.",
            "如果你已经订阅了 Google Drive、Microsoft 365 或 123云盘/坚果云等 WebDAV 网盘且有富余空间，直接用现成的网盘也很划算——只是翻页加载会比对象存储稍慢一些。",
            "如果你已經訂閱了 Google Drive、Microsoft 365 或 123雲盤/堅果雲等 WebDAV 網盤且有富餘空間，直接用現成的網盤也很划算——只是翻頁載入會比物件儲存稍慢一些。",
            "すでに Google Drive、Microsoft 365、または WebDAV 対応クラウドの空き容量がある場合は、そのまま活用するのも良い選択です（ページの読み込みはオブジェクトストレージより少しだけ遅くなります）。",
        )

    val S3_GROUP: String
        get() = tr("S3 storage", "S3 对象存储", "S3 物件儲存", "S3 ストレージ")
    const val S3_TITLE: String = "Cloudflare R2"
    val S3_SUBTITLE: String
        get() = tr(
            "Sign in with Cloudflare to connect or create easemic-comics.",
            "使用 Cloudflare 账号登录，自动连接或创建 easemic-comics 存储桶。",
            "使用 Cloudflare 帳號登入，自動連接或建立 easemic-comics 儲存桶。",
            "Cloudflare でサインインして easemic-comics バケットを接続または自動作成します。",
        )
    val S3_API_KEY_TITLE: String
        get() = tr("Quick setup with API key", "使用单 API Key 快速配置", "使用單 API Key 快速設定", "APIキーでかんたん設定")
    val S3_API_KEY_SUBTITLE: String
        get() = tr(
            "Paste one key — Easemic detects the provider and finds or creates easemic-comics.",
            "只需粘贴一个 API Key — 自动识别服务商并查找或创建 easemic-comics。",
            "只需貼上一個 API Key — 自動識別服務商並尋找或建立 easemic-comics。",
            "キーを1つ貼り付けるだけで、プロバイダーを自動判別し easemic-comics を接続・作成します。",
        )
    val S3_CUSTOM_TITLE: String
        get() = tr("Custom S3 credentials", "自定义 S3 凭证", "自訂 S3 憑證", "カスタム S3 認証情報")
    val S3_CUSTOM_SUBTITLE: String
        get() = tr(
            "Endpoint, access key, secret, and bucket for any S3-compatible storage.",
            "手动填写任意 S3 兼容存储的 Endpoint、Access Key、Secret 与存储桶名。",
            "手動填寫任意 S3 相容儲存的 Endpoint、Access Key、Secret 與儲存桶名。",
            "任意のS3互換ストレージのエンドポイント、アクセスキー、シークレット、バケット名を入力します。",
        )

    val DRIVES_GROUP: String
        get() = tr("Cloud drives", "云端网盘", "雲端網盤", "クラウドドライブ")
    val GOOGLE_DRIVE_SUBTITLE: String
        get() = tr("Sign in with Google.", "使用 Google 账号登录。", "使用 Google 帳號登入。", "Google でサインインします。")
    const val ONEDRIVE_TITLE: String = "OneDrive"
    const val WEBDAV_TITLE: String = "WebDAV"
    val WEBDAV_SUBTITLE: String
        get() = tr(
            "Nutstore, 123pan, a NAS — server and login.",
            "坚果云、123云盘、群晖 NAS 等 — 填写服务器地址与账号。",
            "堅果雲、123雲盤、群暉 NAS 等 — 填寫伺服器位址與帳號。",
            "Nextcloud、123pan、NAS など — サーバーURLとログイン情報。",
        )
    val DRIVES_FOOTNOTE: String
        get() = tr(
            "Drive libraries are always encrypted with a password you set.",
            "网盘书库始终使用你设置的密码进行端到端加密。",
            "網盤書庫始終使用你設定的密碼進行端到端加密。",
            "クラウドドライブ上のライブラリは常に設定したパスワードで暗号化されます。",
        )
}

/** The places the picker can open; a drive appears here only once its sign-in works in this build. */
enum class CloudStoragePlace { S3, S3_CUSTOM, WEBDAV, ONEDRIVE, GOOGLE_DRIVE }

/**
 * Whether the chosen place's screen may show while a library is bound. #C50: OneDrive and Google Drive sign in again.
 * #D16: WebDAV only ever shows the renew form.
 */
fun cloudPlaceReauthing(cloudPlace: String?, unbound: Boolean, webDavRenewing: Boolean): Boolean =
    !unbound && when (cloudPlace) {
        CloudStoragePlace.ONEDRIVE.name, CloudStoragePlace.GOOGLE_DRIVE.name -> true
        CloudStoragePlace.WEBDAV.name -> webDavRenewing
        else -> false
    }

// Built once per process; ImageVector is immutable and safe to share.
private val pickerCloudMark: ImageVector by lazy { cloudflareCloudMark() }
private val pickerBunnyMark: ImageVector by lazy { bunnyNetMark() }
private val pickerBackblazeMark: ImageVector by lazy { backblazeB2Mark() }
private val pickerOneDriveMark: ImageVector by lazy { oneDriveCloudMark() }
private val pickerGoogleDriveMark: ImageVector by lazy { googleDriveMark() }

/**
 * #C50 + #E121: Settings → Cloud storage while nothing is bound.
 * Splits S3 into 3 clear rows:
 * 1) Cloudflare R2 sign-in
 * 2) Quick setup with API key (Bunny.net, Backblaze B2, Cloudflare R2 Token — auto-detected & auto-creates easemic-comics)
 * 3) Custom S3 credentials (5-field form)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudStoragePickerScreen(
    onPick: (CloudStoragePlace) -> Unit,
    onBack: () -> Unit,
    apiKeyBusy: Boolean = false,
    apiKeyError: String? = null,
    onClearApiKeyError: () -> Unit = {},
    onConnectWithApiKey: (String, S3ApiKeyProvider?) -> Unit = { _, _ -> },
) {
    val palette = quietShelfPalette()
    var showFaqDialog by rememberSaveable { mutableStateOf(false) }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var initialProvider by rememberSaveable { mutableStateOf<S3ApiKeyProvider?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("cloud-storage-picker")
            .padding(bottom = 16.dp),
    ) {
        PickerHero(
            palette = palette,
            onOpenFaq = { showFaqDialog = true },
        )
        GroupLabel(CloudStoragePickerCopy.S3_GROUP, palette)
        PickerGroup(palette) {
            PickerRow(
                title = CloudStoragePickerCopy.S3_TITLE,
                subtitle = CloudStoragePickerCopy.S3_SUBTITLE,
                mark = { Image(pickerCloudMark, contentDescription = null, modifier = Modifier.width(26.dp)) },
                onClick = { onPick(CloudStoragePlace.S3) },
                tag = "cloud-storage-pick-s3",
                palette = palette,
                divider = true,
            )
            S3ApiKeyPickerRow(
                palette = palette,
                onOpenDialog = { preselected ->
                    onClearApiKeyError()
                    initialProvider = preselected
                    showApiKeyDialog = true
                },
                divider = true,
            )
            PickerRow(
                title = CloudStoragePickerCopy.S3_CUSTOM_TITLE,
                subtitle = CloudStoragePickerCopy.S3_CUSTOM_SUBTITLE,
                mark = {
                    Image(
                        remember(palette.ink) { s3CubeMark(palette.ink) },
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = { onPick(CloudStoragePlace.S3_CUSTOM) },
                tag = "cloud-storage-pick-s3-custom",
                palette = palette,
            )
        }
        GroupLabel(CloudStoragePickerCopy.DRIVES_GROUP, palette)
        PickerGroup(palette) {
            PickerRow(
                title = GoogleDriveScreenCopy.PICKER_TITLE,
                subtitle = CloudStoragePickerCopy.GOOGLE_DRIVE_SUBTITLE,
                mark = { Image(pickerGoogleDriveMark, contentDescription = null, modifier = Modifier.width(24.dp)) },
                onClick = { onPick(CloudStoragePlace.GOOGLE_DRIVE) },
                tag = "cloud-storage-pick-gdrive",
                palette = palette,
                divider = true,
            )
            PickerRow(
                title = CloudStoragePickerCopy.ONEDRIVE_TITLE,
                subtitle = OneDriveScreenCopy.PICKER_SUBTITLE,
                mark = { Image(pickerOneDriveMark, contentDescription = null, modifier = Modifier.width(26.dp)) },
                onClick = { onPick(CloudStoragePlace.ONEDRIVE) },
                tag = "cloud-storage-pick-onedrive",
                palette = palette,
                divider = true,
            )
            PickerRow(
                title = CloudStoragePickerCopy.WEBDAV_TITLE,
                subtitle = CloudStoragePickerCopy.WEBDAV_SUBTITLE,
                mark = {
                    Image(
                        remember(palette.ink) { webDavServerMark(palette.ink) },
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = { onPick(CloudStoragePlace.WEBDAV) },
                tag = "cloud-storage-pick-webdav",
                palette = palette,
            )
        }
        Text(
            CloudStoragePickerCopy.DRIVES_FOOTNOTE,
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
        )
        PclOutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 24.dp)
                .heightIn(min = 48.dp),
        ) {
            Text("Back")
        }
    }

    if (showFaqDialog) {
        StorageAdviceFaqDialog(
            palette = palette,
            onDismiss = { showFaqDialog = false },
        )
    }

    if (showApiKeyDialog) {
        S3ApiKeyQuickSetupDialog(
            palette = palette,
            initialProvider = initialProvider,
            busy = apiKeyBusy,
            errorMessage = apiKeyError,
            onClearError = onClearApiKeyError,
            onDismiss = {
                if (!apiKeyBusy) {
                    showApiKeyDialog = false
                }
            },
            onConnect = { key, provider ->
                onConnectWithApiKey(key, provider)
            },
        )
    }
}

@Composable
private fun PickerHero(
    palette: QuietShelfPalette,
    onOpenFaq: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val brush = Brush.verticalGradient(
        colors = if (dark) {
            listOf(CloudflareBrand.FieldTopDark, palette.surface)
        } else {
            listOf(CloudflareBrand.FieldTopLight, palette.surface)
        },
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(brush),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(pickerCloudMark, contentDescription = null, modifier = Modifier.width(64.dp))
            Text(CloudStoragePickerCopy.HEADLINE, style = MaterialTheme.typography.headlineLarge, color = palette.ink)
            Text(CloudStoragePickerCopy.LEDE, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
            Row(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.statusOk.copy(alpha = 0.11f))
                    .clickable(onClick = onOpenFaq)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("cloud-storage-faq-button"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Image(
                    remember(palette.statusOk) { storageHelpCircleMark(palette.statusOk) },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    CloudStoragePickerCopy.FAQ_TRIGGER,
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.statusOk,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun S3ApiKeyPickerRow(
    palette: QuietShelfPalette,
    onOpenDialog: (S3ApiKeyProvider?) -> Unit,
    divider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cloud-storage-pick-s3-apikey")
                .clickable { onOpenDialog(null) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.statusOk.copy(alpha = 0.11f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(pickerBunnyMark, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    CloudStoragePickerCopy.S3_API_KEY_TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.ink,
                )
                Text(
                    CloudStoragePickerCopy.S3_API_KEY_SUBTITLE,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    ProviderMiniBadge(
                        label = S3ApiKeyProvider.BUNNY.shortBadge,
                        mark = pickerBunnyMark,
                        palette = palette,
                        tag = "cloud-storage-badge-bunny",
                        onClick = { onOpenDialog(S3ApiKeyProvider.BUNNY) },
                    )
                    ProviderMiniBadge(
                        label = S3ApiKeyProvider.BACKBLAZE_B2.shortBadge,
                        mark = pickerBackblazeMark,
                        palette = palette,
                        tag = "cloud-storage-badge-b2",
                        onClick = { onOpenDialog(S3ApiKeyProvider.BACKBLAZE_B2) },
                    )
                    ProviderMiniBadge(
                        label = S3ApiKeyProvider.CLOUDFLARE_R2.shortBadge,
                        mark = pickerCloudMark,
                        palette = palette,
                        tag = "cloud-storage-badge-r2",
                        onClick = { onOpenDialog(S3ApiKeyProvider.CLOUDFLARE_R2) },
                    )
                }
            }
            Text("›", style = MaterialTheme.typography.titleMedium, color = palette.muted)
        }
        if (divider) HorizontalDivider(color = palette.line)
    }
}

@Composable
private fun ProviderMiniBadge(
    label: String,
    mark: ImageVector,
    palette: QuietShelfPalette,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(palette.ink.copy(alpha = 0.05f))
            .border(1.dp, palette.line, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Image(mark, contentDescription = null, modifier = Modifier.size(13.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.ink,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StorageAdviceFaqDialog(
    palette: QuietShelfPalette,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("cloud-storage-faq-dialog"),
        title = {
            Text(
                CloudStoragePickerCopy.FAQ_TITLE,
                style = MaterialTheme.typography.titleLarge,
                color = palette.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Image(pickerCloudMark, contentDescription = null, modifier = Modifier.size(16.dp))
                        Image(pickerBunnyMark, contentDescription = null, modifier = Modifier.size(15.dp))
                        Image(pickerBackblazeMark, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(
                            CloudStoragePickerCopy.FAQ_S3_HEADING,
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.ink,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        CloudStoragePickerCopy.FAQ_S3_BODY,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                }
                HorizontalDivider(color = palette.line)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Image(pickerGoogleDriveMark, contentDescription = null, modifier = Modifier.size(14.dp))
                        Image(pickerOneDriveMark, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(
                            CloudStoragePickerCopy.FAQ_DRIVE_HEADING,
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.ink,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        CloudStoragePickerCopy.FAQ_DRIVE_BODY,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cloud-storage-faq-close"),
            ) {
                Text("Got it", color = palette.statusOk, fontWeight = FontWeight.SemiBold)
            }
        },
    )
}

@Composable
internal fun S3ApiKeyQuickSetupDialog(
    palette: QuietShelfPalette,
    initialProvider: S3ApiKeyProvider?,
    busy: Boolean,
    errorMessage: String?,
    onClearError: () -> Unit,
    onDismiss: () -> Unit,
    onConnect: (String, S3ApiKeyProvider?) -> Unit,
) {
    var keyInput by rememberSaveable { mutableStateOf("") }
    var selectedTab by rememberSaveable(initialProvider) {
        mutableStateOf(initialProvider ?: S3ApiKeyProvider.BUNNY)
    }
    val detectedProvider = remember(keyInput) { S3ApiKeyAutoProvisioner.detectProvider(keyInput) }
    val activeProvider = detectedProvider ?: selectedTab

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("s3-apikey-dialog"),
        title = {
            Text(
                S3ApiKeyCopy.DIALOG_TITLE,
                style = MaterialTheme.typography.titleLarge,
                color = palette.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    S3ApiKeyCopy.DIALOG_SUBTITLE,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    S3ApiKeyProvider.entries.forEach { provider ->
                        val active = activeProvider == provider
                        val mark = when (provider) {
                            S3ApiKeyProvider.BUNNY -> pickerBunnyMark
                            S3ApiKeyProvider.BACKBLAZE_B2 -> pickerBackblazeMark
                            S3ApiKeyProvider.CLOUDFLARE_R2 -> pickerCloudMark
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (active) palette.statusOk.copy(alpha = 0.12f)
                                    else palette.ink.copy(alpha = 0.04f),
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (active) palette.statusOk else palette.line,
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable(enabled = !busy) {
                                    selectedTab = provider
                                    onClearError()
                                }
                                .padding(horizontal = 6.dp, vertical = 7.dp)
                                .testTag("s3-apikey-tab-${provider.name.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        ) {
                            Image(mark, contentDescription = null, modifier = Modifier.size(13.dp))
                            Text(
                                provider.shortBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) palette.statusOk else palette.ink,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = {
                        keyInput = it
                        onClearError()
                    },
                    label = { Text(S3ApiKeyCopy.INPUT_LABEL) },
                    placeholder = { Text(S3ApiKeyCopy.INPUT_PLACEHOLDER) },
                    enabled = !busy,
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("s3-apikey-input"),
                )
                if (detectedProvider != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.statusOk.copy(alpha = 0.11f))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("s3-apikey-detected-banner"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "✓ Detected: ${detectedProvider.hintTitle}",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.statusOk,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.ink.copy(alpha = 0.04f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        activeProvider.hintTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.ink,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        activeProvider.hintSteps,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                }
                errorMessage?.let { err ->
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.statusBad,
                        modifier = Modifier.testTag("s3-apikey-error"),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy,
                modifier = Modifier.testTag("s3-apikey-cancel"),
            ) {
                Text("Cancel", color = palette.muted)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConnect(keyInput, activeProvider) },
                enabled = !busy && keyInput.isNotBlank(),
                modifier = Modifier.testTag("s3-apikey-connect"),
            ) {
                Text(
                    if (busy) S3ApiKeyCopy.CONNECTING_CTA else S3ApiKeyCopy.CONNECT_CTA,
                    color = palette.statusOk,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    )
}

@Composable
private fun GroupLabel(text: String, palette: QuietShelfPalette) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = palette.muted,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun PickerGroup(palette: QuietShelfPalette, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .border(1.dp, palette.line, RoundedCornerShape(14.dp)),
    ) {
        content()
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    mark: @Composable () -> Unit,
    onClick: () -> Unit,
    tag: String,
    palette: QuietShelfPalette,
    subtitleColor: Color = palette.muted,
    divider: Boolean = false,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) { mark() }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = palette.ink)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
            }
            Text("›", style = MaterialTheme.typography.titleMedium, color = palette.muted)
        }
        if (divider) HorizontalDivider(color = palette.line)
    }
}

/** Two stacked server units, drawn so the row needs no bitmap asset. Always pass the theme's ink. */
fun webDavServerMark(ink: Color): ImageVector =
    ImageVector.Builder(
        name = "WebDavServerMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        val stroke = SolidColor(ink)
        for (top in listOf(4f, 14f)) {
            path(stroke = stroke, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round) {
                moveTo(4.5f, top)
                lineTo(19.5f, top)
                arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f)
                lineTo(21f, top + 4.5f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f)
                lineTo(4.5f, top + 6f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, -1.5f)
                lineTo(3f, top + 1.5f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f)
                close()
            }
            path(fill = stroke) {
                moveTo(7.9f, top + 3f)
                arcToRelative(0.9f, 0.9f, 0f, true, true, -1.8f, 0f)
                arcToRelative(0.9f, 0.9f, 0f, true, true, 1.8f, 0f)
                close()
            }
        }
    }.build()
