package com.easemic.app.feature.bridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.sync.DiagLog
import com.easemic.app.sync.R2BucketUsage
import com.easemic.app.sync.R2BucketUsageCopy
import com.easemic.app.ui.components.PclButton
import com.easemic.app.ui.components.PclOutlinedButton
import com.easemic.app.ui.components.PclTextButton
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.ui.theme.QuietShelfPalette
import com.easemic.app.ui.theme.quietShelfPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Built once per process; ImageVector is immutable and safe to share.
private val cloudMark: ImageVector by lazy { cloudflareCloudMark() }

/**
 * Cloud storage bind screen, "Orange Field" redesign. Unbound: brand hero +
 * three setup steps + a single Connect CTA with the paste form behind a
 * disclosure. Bound (#D8b): identity card, stats, and management rows.
 * Keys stay in the device store; nothing here ever renders a secret.
 */
@Composable
fun BridgeR2BindScreen(
    state: BridgeR2BindUiState,
    onEndpointChange: (String) -> Unit,
    onAccessKeyChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onBucketChange: (String) -> Unit,
    onSave: () -> Unit,
    onConnectCloudflare: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onBack: () -> Unit,
    onPasteFormToggle: () -> Unit = {},
    onRegionChange: (String) -> Unit = {},
    usage: R2BucketUsage? = null,
    usageMeasuring: Boolean = false,
    usageError: String? = null,
    lock: LibraryLockUiState = LibraryLockUiState(),
    onLockEvent: (LibraryLockEvent) -> Unit = {},
    /**
     * Task 14 (browse-remaining §4): the Recently deleted card. [recentlyDeletedRows]
     * is the live R2 trash listing (mapped by LibraryRecycleFeedMapper); when
     * [recentlyDeletedUnreachable] is true the card collapses to one honest
     * status line — no counts, no rows. Only rendered in the bound layout.
     */
    recentlyDeletedRows: List<BrowseFeedItem> = emptyList(),
    recentlyDeletedUnreachable: Boolean = false,
    onRestoreRecentlyDeleted: (BrowseFeedItem) -> Unit = {},
    onDeleteRecentlyDeletedForever: (BrowseFeedItem) -> Unit = {},
    // A16: ids with an in-flight Restore/Delete-forever, forwarded to
    // RecentlyDeletedCard so its buttons disable while busy.
    recentlyDeletedBusyIds: Set<String> = emptySet(),
    /** #C50: the bound drive's sign-in was revoked; the screen offers "sign in again" (no password, no rebuild). */
    driveSignInExpired: Boolean = false,
    onDriveSignInAgain: () -> Unit = {},
    /** #D16: a bound WebDAV drive refused the saved password; the card offers entering the new one in place. */
    onWebDavNewPassword: () -> Unit = {},
    /** Null until the cloud shelf has loaded; then the number of comics on it. */
    cloudComicCount: Int? = null,
    /** Null until the trash listing has returned for this library; then the number of recently deleted comics. */
    recentlyDeletedCount: Int? = recentlyDeletedRows.size,
) {
    val palette = quietShelfPalette()
    val enabled = !state.busy
    val bound = state.boundBucketLabel != null
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("bridge-r2-bind-screen")
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!bound) {
            if (!state.pasteFormExpanded) {
                OrangeFieldHero(palette)
                SetupSteps(palette)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ConnectCloudflareButton(
                        onClick = onConnectCloudflare,
                        enabled = enabled,
                        busy = state.busy,
                    )
                    StatusMessages(state, palette)
                }
            } else {
                CustomS3FieldHero(palette)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    R2CredentialPasteFields(
                        state = state,
                        enabled = enabled,
                        onEndpointChange = onEndpointChange,
                        onRegionChange = onRegionChange,
                        onAccessKeyChange = onAccessKeyChange,
                        onSecretChange = onSecretChange,
                        onBucketChange = onBucketChange,
                    )
                    PclButton(
                        onClick = onSave,
                        enabled = enabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("bridge-r2-save"),
                    ) {
                        Text(if (state.busy) tr("Saving…", "正在保存…", "正在儲存…", "保存中…") else AppStrings.save)
                    }
                    StatusMessages(state, palette)
                }
            }
        } else {
            val label = checkNotNull(state.boundBucketLabel)
            val kind = state.boundKind
            val provider = CloudProviderName.of(kind, label)
            val drive = kind == BoundKind.WEBDAV ||
                kind == BoundKind.ONEDRIVE ||
                kind == BoundKind.GOOGLE_DRIVE
            // #D16: a WebDAV drive that refuses the saved password gets #C50's "sign-in expired" layout.
            val passwordRejected = kind == BoundKind.WEBDAV && lock.signInRejected
            val expired = (driveSignInExpired && drive) || passwordRejected
            // #D16: any other storage's refused sign-in has no card of its own — one verdict sentence instead.
            val signInLine = lock.signInRejected && !expired
            val showEncrypted = lock.mode == LibraryLockMode.ENCRYPTED || drive
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    OrangeFieldCopy.BOUND_TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.ink,
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bridge-identity-card"),
                    shape = RoundedCornerShape(14.dp),
                    color = palette.card,
                    border = BorderStroke(1.dp, palette.line),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Image(
                                imageVector = when (kind) {
                                    BoundKind.ONEDRIVE -> oneDriveCloudMark()
                                    BoundKind.GOOGLE_DRIVE -> googleDriveMark()
                                    BoundKind.WEBDAV -> webDavServerMark(palette.ink)
                                    else -> cloudMark
                                },
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    provider,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = palette.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (expired) checkNotNull(CloudSignInCopy.status(kind)) else label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (expired) palette.statusWarn else palette.muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (showEncrypted) {
                                Text(
                                    tr("Encrypted", "已加密", "已加密", "暗号化済み"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.ink,
                                    modifier = Modifier
                                        .border(1.dp, palette.line, RoundedCornerShape(999.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                        if (expired) {
                            Text(
                                if (passwordRejected) CloudSignInCopy.CARD_BODY else DriveExpiredCopy.BODY,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.muted,
                            )
                            if (passwordRejected && WebDavBindCopy.isPan123(label)) {
                                Text(
                                    CloudSignInCopy.PAN123_STEPS,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.muted,
                                    modifier = Modifier.testTag("webdav-pan123-steps"),
                                )
                            }
                            PclButton(
                                onClick = if (passwordRejected) onWebDavNewPassword else onDriveSignInAgain,
                                enabled = enabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag(if (passwordRejected) "webdav-enter-new-password" else "drive-sign-in-again"),
                            ) {
                                Text(
                                    when {
                                        passwordRejected -> CloudSignInCopy.RENEW_ACTION
                                        kind == BoundKind.GOOGLE_DRIVE -> DriveExpiredCopy.GOOGLE
                                        else -> DriveExpiredCopy.MICROSOFT
                                    },
                                )
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            BoundStat(palette, tr("comics", "部漫画", "部漫畫", "冊")) {
                                if (cloudComicCount == null) {
                                    StatPlaceholder(palette, "bridge-stat-comics-placeholder")
                                } else {
                                    StatNumber(cloudComicCount.toString(), palette, "bridge-stat-comics")
                                }
                            }
                            BoundStat(palette, tr("stored", "已用", "已用", "使用中")) {
                                if (usage == null) {
                                    StatPlaceholder(palette, "bridge-stat-stored-placeholder")
                                } else {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        StatNumber(
                                            R2BucketUsageCopy.statValue(usage.totalBytes),
                                            palette,
                                            "bridge-r2-stat-value",
                                        )
                                        Text(
                                            R2BucketUsageCopy.statUnit(usage.totalBytes),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.muted,
                                            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                                        )
                                    }
                                }
                            }
                            BoundStat(palette, tr("trash", "回收站", "資源回收筒", "ゴミ箱")) {
                                when {
                                    recentlyDeletedUnreachable || expired ->
                                        StatNumber(OrangeFieldCopy.STAT_EMPTY, palette, "bridge-stat-trash")
                                    recentlyDeletedCount == null ->
                                        StatPlaceholder(palette, "bridge-stat-trash-placeholder")
                                    else ->
                                        StatNumber(recentlyDeletedCount.toString(), palette, "bridge-stat-trash")
                                }
                            }
                        }
                    }
                }
                if (!drive) {
                    Text(
                        R2BucketUsageCopy.HELPER,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                        modifier = Modifier.testTag("bridge-r2-usage-helper"),
                    )
                }
                // #D16: the generic list-failure line is not shown under the verdict.
                usageError?.takeUnless { expired || signInLine }?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.statusBad,
                        modifier = Modifier.testTag("bridge-r2-usage-error"),
                    )
                }
                if (signInLine) {
                    Text(
                        CloudSignInCopy.sentence(kind),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.statusBad,
                        modifier = Modifier.testTag("bridge-signin-rejected-line"),
                    )
                }
                RecentlyDeletedCard(
                    rows = recentlyDeletedRows,
                    cloudUnreachable = recentlyDeletedUnreachable || expired,
                    onRestore = onRestoreRecentlyDeleted,
                    onDeleteForever = onDeleteRecentlyDeletedForever,
                    busyIds = recentlyDeletedBusyIds,
                    unavailableLine = if (expired) CloudSignInCopy.RECENTLY_DELETED else RecentlyDeletedCardCopy.UNREACHABLE,
                )
                LibraryLockSections(lock, onLockEvent, enabled, palette)
                if (lock.mode == LibraryLockMode.ENCRYPTED) {
                    BorderedGroup {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("bridge-lock-change")
                                .clickable(enabled = enabled) {
                                    onLockEvent(LibraryLockEvent.ChangeExpandToggled)
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                tr("Change library password", "修改书库密码", "變更書庫密碼", "ライブラリのパスワードを変更"),
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.ink,
                                modifier = Modifier.weight(1f),
                            )
                            Text("›", style = MaterialTheme.typography.titleMedium, color = palette.muted)
                        }
                    }
                    if (lock.changeExpanded) {
                        LockPasswordField(
                            value = lock.oldPassword,
                            label = "Current password",
                            tag = "bridge-lock-old",
                            enabled = enabled && !lock.busy,
                        ) { onLockEvent(LibraryLockEvent.OldPasswordChanged(it)) }
                        LockPasswordField(
                            value = lock.newPassword,
                            label = "New password",
                            tag = "bridge-lock-new",
                            enabled = enabled && !lock.busy,
                        ) { onLockEvent(LibraryLockEvent.NewPasswordChanged(it)) }
                        LockPasswordField(
                            value = lock.newConfirm,
                            label = "Confirm new password",
                            tag = "bridge-lock-new-confirm",
                            enabled = enabled && !lock.busy,
                        ) { onLockEvent(LibraryLockEvent.NewConfirmChanged(it)) }
                        PclOutlinedButton(
                            onClick = { onLockEvent(LibraryLockEvent.ChangeTapped) },
                            enabled = enabled && !lock.busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("bridge-lock-change-submit"),
                        ) {
                            Text(LibraryLockCopy.CHANGE_SUBMIT)
                        }
                    }
                }
                // A44/A45: a bound bucket's only action is Disconnect. There is
                // deliberately no replace-credentials affordance here — rebinding
                // goes through Disconnect and then a fresh bind.
                BorderedGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("bridge-r2-disconnect")
                            .clickable(enabled = enabled, onClick = onDisconnect)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                tr("Disconnect this library", "断开此书库", "中斷此書庫連線", "このライブラリの接続を解除"),
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.statusBad,
                            )
                            Text(
                                tr("Your comics stay on $provider", "漫画仍保存在 $provider", "漫畫仍保存在 $provider", "マンガは $provider に残ります"),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.muted,
                            )
                        }
                    }
                }
                StatusMessages(state, palette)
            }
        }
        if (!bound) {
            PclOutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text(AppStrings.back)
            }
        }
    }
}

@Composable
fun BridgeR2BindScreen(
    presenter: BridgeR2BindPresenter,
    onBack: () -> Unit,
    onDisconnect: () -> Unit = {},
    onBoundChanged: (String?) -> Unit = {},
    listUsage: (() -> R2BucketUsage?)? = null,
    usageGeneration: Any? = null,
    lock: LibraryLockUiState = LibraryLockUiState(),
    onLockEvent: (LibraryLockEvent) -> Unit = {},
    recentlyDeletedRows: List<BrowseFeedItem> = emptyList(),
    recentlyDeletedUnreachable: Boolean = false,
    onRestoreRecentlyDeleted: (BrowseFeedItem) -> Unit = {},
    onDeleteRecentlyDeletedForever: (BrowseFeedItem) -> Unit = {},
    // A16: ids with an in-flight Restore/Delete-forever, forwarded to
    // RecentlyDeletedCard so its buttons disable while busy.
    recentlyDeletedBusyIds: Set<String> = emptySet(),
    /** #C50: the bound drive's sign-in was revoked; the screen offers "sign in again" (no password, no rebuild). */
    driveSignInExpired: Boolean = false,
    onDriveSignInAgain: () -> Unit = {},
    /** #D16: a bound WebDAV drive refused the saved password; the card offers entering the new one in place. */
    onWebDavNewPassword: () -> Unit = {},
    /** Null until the cloud shelf has loaded; then the number of comics on it. */
    cloudComicCount: Int? = null,
    /** Null until the trash listing has returned for this library; then the number of recently deleted comics. */
    recentlyDeletedCount: Int? = recentlyDeletedRows.size,
) {
    val state by presenter.states.collectAsState()
    val scope = rememberCoroutineScope()
    // Keyed on the bind generation so a successful re-bind (even to a bucket
    // with the same name) drops the stale stat and error and re-measures.
    var usage by remember(state.boundBucketLabel, state.bindGeneration) {
        mutableStateOf<R2BucketUsage?>(null)
    }
    var usageMeasuring by remember(state.boundBucketLabel, state.bindGeneration) {
        mutableStateOf(false)
    }
    var usageError by remember(state.boundBucketLabel, state.bindGeneration) {
        mutableStateOf<String?>(null)
    }
    val currentListUsage by rememberUpdatedState(listUsage)
    LaunchedEffect(Unit) {
        presenter.refreshBoundLabel()
        onBoundChanged(presenter.state.boundBucketLabel)
    }
    LaunchedEffect(presenter) {
        OAuthRedirectBus.setListener { result ->
            when (result) {
                is CloudflareOAuthCallbackResult.Success -> scope.launch {
                    presenter.onOAuthRedirect(result.code, result.state)
                    onBoundChanged(presenter.state.boundBucketLabel)
                }
                is CloudflareOAuthCallbackResult.Failure -> {
                    presenter.onOAuthFailure(result.error, result.description)
                }
            }
        }
        try {
            awaitCancellation()
        } finally {
            OAuthRedirectBus.setListener(null)
        }
    }
    LaunchedEffect(
        listUsage != null,
        state.boundBucketLabel,
        state.bindGeneration,
        usageGeneration,
    ) {
        val list = currentListUsage
        if (state.boundBucketLabel == null || list == null) {
            usage = null
            usageMeasuring = false
            usageError = null
            return@LaunchedEffect
        }
        usageMeasuring = true
        usageError = null
        val measured = withContext(Dispatchers.IO) { runCatching { list() } }
        measured.exceptionOrNull()?.let {
            DiagLog.w("CloudSync", "usage list failed", it)
        }
        usageMeasuring = false
        val value = measured.getOrNull()
        when {
            measured.isSuccess && value != null -> {
                usage = value
                usageError = null
            }
            measured.isSuccess -> Unit // listUsage unavailable this pass; keep last stat.
            else -> usageError = R2BucketUsageCopy.LIST_FAILED
        }
    }
    BridgeR2BindScreen(
        state = state,
        usage = usage,
        usageMeasuring = usageMeasuring,
        usageError = usageError,
        onEndpointChange = presenter::onEndpointChange,
        onRegionChange = presenter::onRegionChange,
        onAccessKeyChange = presenter::onAccessKeyChange,
        onSecretChange = presenter::onSecretChange,
        onBucketChange = presenter::onBucketChange,
        onSave = {
            scope.launch {
                presenter.onSave()
                onBoundChanged(presenter.state.boundBucketLabel)
            }
        },
        onConnectCloudflare = presenter::onConnectCloudflare,
        onDisconnect = onDisconnect,
        onBack = onBack,
        onPasteFormToggle = presenter::onPasteFormToggle,
        lock = lock,
        onLockEvent = onLockEvent,
        recentlyDeletedRows = recentlyDeletedRows,
        recentlyDeletedUnreachable = recentlyDeletedUnreachable,
        onRestoreRecentlyDeleted = onRestoreRecentlyDeleted,
        onDeleteRecentlyDeletedForever = onDeleteRecentlyDeletedForever,
        recentlyDeletedBusyIds = recentlyDeletedBusyIds,
        driveSignInExpired = driveSignInExpired,
        onDriveSignInAgain = onDriveSignInAgain,
        onWebDavNewPassword = onWebDavNewPassword,
        cloudComicCount = cloudComicCount,
        recentlyDeletedCount = recentlyDeletedCount,
    )
}

@Composable
private fun OrangeFieldHero(palette: QuietShelfPalette) {
    val dark = isSystemInDarkTheme()
    val fieldBrush = Brush.verticalGradient(
        colors = if (dark) {
            listOf(CloudflareBrand.FieldTopDark, CloudflareBrand.FieldBottomDark)
        } else {
            listOf(CloudflareBrand.FieldTopLight, CloudflareBrand.FieldBottomLight)
        },
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(fieldBrush),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(
                imageVector = cloudMark,
                contentDescription = null,
                modifier = Modifier.width(84.dp),
            )
            Text(
                OrangeFieldCopy.HEADLINE,
                style = MaterialTheme.typography.headlineLarge,
                color = palette.ink,
            )
            Text(
                OrangeFieldCopy.LEDE,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
            )
        }
    }
}

@Composable
private fun CustomS3FieldHero(palette: QuietShelfPalette) {
    val dark = isSystemInDarkTheme()
    val s3Mark = remember(palette.ink) { s3CubeMark(palette.ink) }
    val fieldBrush = Brush.verticalGradient(
        colors = if (dark) {
            listOf(CloudflareBrand.FieldTopDark, CloudflareBrand.FieldBottomDark)
        } else {
            listOf(CloudflareBrand.FieldTopLight, CloudflareBrand.FieldBottomLight)
        },
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(fieldBrush),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                imageVector = s3Mark,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
            Text(
                OrangeFieldCopy.CUSTOM_S3_HEADLINE,
                style = MaterialTheme.typography.headlineMedium,
                color = palette.ink,
            )
            Text(
                OrangeFieldCopy.CUSTOM_S3_LEDE,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
            )
        }
    }
}

@Composable
private fun SetupSteps(palette: QuietShelfPalette) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SetupStep(
            numeral = OrangeFieldCopy.STEP_1_NUMERAL,
            title = OrangeFieldCopy.STEP_1_TITLE,
            helper = OrangeFieldCopy.STEP_1_HELPER,
            palette = palette,
        ) {
            PclTextButton(
                onClick = { uriHandler.openUri(BridgeR2BindCopy.R2_DASHBOARD) },
                modifier = Modifier.testTag("bridge-r2-dashboard-link"),
            ) {
                Text(OrangeFieldCopy.STEP_1_LINK)
            }
        }
        SetupStep(
            numeral = OrangeFieldCopy.STEP_2_NUMERAL,
            title = OrangeFieldCopy.STEP_2_TITLE,
            helper = OrangeFieldCopy.STEP_2_HELPER,
            palette = palette,
        )
        SetupStep(
            numeral = OrangeFieldCopy.STEP_3_NUMERAL,
            title = OrangeFieldCopy.STEP_3_TITLE,
            helper = OrangeFieldCopy.STEP_3_HELPER,
            palette = palette,
        )
    }
}

@Composable
private fun SetupStep(
    numeral: String,
    title: String,
    helper: String,
    palette: QuietShelfPalette,
    link: (@Composable () -> Unit)? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            numeral,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = CloudflareBrand.Orange,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.ink,
            )
            Text(
                helper,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
            link?.invoke()
        }
    }
}

@Composable
private fun ConnectCloudflareButton(
    onClick: () -> Unit,
    enabled: Boolean,
    busy: Boolean,
) {
    // Spec §3: label stays "Connect Cloudflare" even while busy — the button
    // just disables. No spinner theater.
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        colors = ButtonDefaults.buttonColors(
            containerColor = CloudflareBrand.Orange,
            contentColor = CloudflareBrand.CtaInk,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("bridge-r2-connect-cloudflare"),
    ) {
        Image(
            imageVector = cloudMark,
            contentDescription = null,
            // Monochrome ink silhouette: the vector's own orange fills
            // vanish on the orange CTA fill.
            colorFilter = ColorFilter.tint(CloudflareBrand.CtaInk),
            modifier = Modifier.width(24.dp),
        )
        Text(
            BridgeR2BindCopy.CONNECT_CLOUDFLARE,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun RowScope.BoundStat(
    palette: QuietShelfPalette,
    label: String,
    value: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        value()
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted,
        )
    }
}

@Composable
private fun StatNumber(value: String, palette: QuietShelfPalette, tag: String) {
    Text(
        value,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.W700,
        color = palette.ink,
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun StatPlaceholder(palette: QuietShelfPalette, tag: String) {
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 24.dp)
            .background(palette.line, RoundedCornerShape(8.dp))
            .testTag(tag),
    )
}

@Composable
private fun BorderedGroup(content: @Composable () -> Unit) {
    val palette = quietShelfPalette()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .border(1.dp, palette.line, RoundedCornerShape(14.dp)),
    ) {
        content()
    }
}

/** #C50 (mock "sign-in expired"). */
object DriveExpiredCopy {
    val BODY: String get() = tr("Uploads and cloud reading are paused. Your comics and password are untouched.", "上传和云端阅读已暂停。漫画和密码未受影响。", "上傳與雲端閱讀已暫停。漫畫與密碼未受影響。", "アップロードとクラウド閲覧を一時停止しています。マンガとパスワードは変更されていません。")
    val MICROSOFT: String get() = tr("Sign in with Microsoft again", "再次使用 Microsoft 登录", "再次使用 Microsoft 登入", "Microsoft に再度サインイン")
    val GOOGLE: String get() = tr("Sign in with Google again", "再次使用 Google 登录", "再次使用 Google 登入", "Google に再度サインイン")
    fun status(kind: BoundKind): String = tr(if (kind == BoundKind.GOOGLE_DRIVE) "Google Drive · sign-in expired" else "OneDrive · sign-in expired", if (kind == BoundKind.GOOGLE_DRIVE) "Google Drive · 登录已过期" else "OneDrive · 登录已过期", if (kind == BoundKind.GOOGLE_DRIVE) "Google Drive · 登入已過期" else "OneDrive · 登入已過期", if (kind == BoundKind.GOOGLE_DRIVE) "Google Drive · サインイン期限切れ" else "OneDrive · サインイン期限切れ")
}

@Composable
private fun StatusMessages(state: BridgeR2BindUiState, palette: QuietShelfPalette) {
    state.errorMessage?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = palette.statusBad,
            modifier = Modifier.testTag("bridge-r2-error"),
        )
    }
    state.infoMessage?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = palette.ink,
            modifier = Modifier.testTag("bridge-r2-info"),
        )
    }
}

@Composable
private fun R2CredentialPasteFields(
    state: BridgeR2BindUiState,
    enabled: Boolean,
    onEndpointChange: (String) -> Unit,
    onRegionChange: (String) -> Unit,
    onAccessKeyChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onBucketChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = state.endpoint,
        onValueChange = onEndpointChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bridge-r2-endpoint")
            .semantics { contentDescription = AppStrings.r2Endpoint },
        label = { Text(tr("Endpoint", "端点", "端點", "エンドポイント")) },
        singleLine = true,
        enabled = enabled,
    )
    OutlinedTextField(
        value = state.region,
        onValueChange = onRegionChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bridge-r2-region")
            .semantics { contentDescription = AppStrings.s3Region },
        label = { Text(tr("Region", "区域", "區域", "リージョン")) },
        supportingText = {
            Text(AppStrings.useAutoR2Hint)
        },
        singleLine = true,
        enabled = enabled,
    )
    OutlinedTextField(
        value = state.accessKeyId,
        onValueChange = onAccessKeyChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bridge-r2-access-key")
            .semantics { contentDescription = AppStrings.r2AccessKeyDescription },
        label = { Text(AppStrings.accessKey) },
        singleLine = true,
        enabled = enabled,
    )
    OutlinedTextField(
        value = state.secretAccessKey,
        onValueChange = onSecretChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bridge-r2-secret")
            .semantics { contentDescription = AppStrings.r2Secret },
        label = { Text(tr("Secret", "密钥", "密鑰", "シークレット")) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    OutlinedTextField(
        value = state.bucket,
        onValueChange = onBucketChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bridge-r2-bucket")
            .semantics { contentDescription = AppStrings.r2Bucket },
        label = { Text(tr("Bucket", "存储桶", "儲存桶", "バケット")) },
        singleLine = true,
        enabled = enabled,
    )
}

@Composable
private fun LibraryLockSections(
    lock: LibraryLockUiState,
    onEvent: (LibraryLockEvent) -> Unit,
    enabled: Boolean,
    palette: QuietShelfPalette,
) {
    when (lock.mode) {
        LibraryLockMode.LOCKED -> {
            Text(
                LibraryLockCopy.LOCKED_PROMPT,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.ink,
                modifier = Modifier.testTag("bridge-lock-prompt"),
            )
            LockPasswordField(
                value = lock.unlockPassword,
                label = "Library password",
                tag = "bridge-lock-unlock-password",
                enabled = enabled && !lock.busy,
            ) { onEvent(LibraryLockEvent.UnlockPasswordChanged(it)) }
            PclButton(
                onClick = { onEvent(LibraryLockEvent.UnlockTapped) },
                enabled = enabled && !lock.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("bridge-lock-unlock"),
            ) {
                Text(if (lock.busy) tr("Unlocking…", "正在解锁…", "正在解鎖…", "ロック解除中…") else LibraryLockCopy.UNLOCK_ACTION)
            }
        }
        else -> Unit
    }
    lock.errorMessage?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = palette.statusBad,
            modifier = Modifier.testTag("bridge-lock-error"),
        )
    }
    lock.infoMessage?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = palette.ink,
            modifier = Modifier.testTag("bridge-lock-info"),
        )
    }
}

@Composable
private fun LockPasswordField(
    value: String,
    label: String,
    tag: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
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
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
