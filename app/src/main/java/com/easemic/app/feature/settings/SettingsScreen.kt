package com.easemic.app.feature.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.easemic.app.feature.bridge.BoundKind
import com.easemic.app.feature.bridge.BridgeR2StorageSubtitle
import com.easemic.app.feature.browse.SourceLogoTile
import com.easemic.app.feature.browse.SourceGlyphBlock
import com.easemic.app.feature.update.AppRelease
import com.easemic.app.feature.update.UpdateAvailableRow
import com.easemic.app.feature.update.UpdateCopy
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.providers.ExhentaiAccessVerdict
import com.easemic.app.feature.providers.ExhentaiEnableStore
import com.easemic.app.feature.providers.ExhentaiFavoritesSyncStore
import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.feature.providers.JmEnableStore
import com.easemic.app.feature.providers.OpdsLibrarySettingsCopy
import com.easemic.app.i18n.AppI18n
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.LanguagePreference
import com.easemic.app.storage.CacheBudget
import com.easemic.app.sync.adfilter.TypesafeApiKeyStore
import com.easemic.app.ui.LibraryViewMode
import com.easemic.app.ui.components.PclOutlinedButton
import com.easemic.app.ui.components.QuietProgressBar
import com.easemic.app.ui.library.lucideBook
import com.easemic.app.ui.library.lucideCloud
import com.easemic.app.ui.library.lucideCompass
import com.easemic.app.ui.library.lucideFolder
import com.easemic.app.ui.theme.quietShelfPalette
import com.easemic.app.work.appNotificationSettingsIntent
import com.easemic.app.work.batteryOptimizationIntent
import com.easemic.app.work.currentBackgroundHealth

/**
 * Value text for the Reading group's "Library layout" row.
 */
object LibraryLayoutSubtitle {
    fun format(mode: LibraryViewMode): String =
        when (mode) {
            LibraryViewMode.GRID -> AppStrings.layoutGrid
            LibraryViewMode.LIST -> AppStrings.layoutList
        }
}

/**
 * Settings: cloud library, then Sources, storage, reading, downloads, and this phone.
 */
@Composable
fun SettingsScreen(
    onOpenLibrarySources: () -> Unit,
    onOpenOpdsCatalogs: () -> Unit = {},
    onOpenBackupRestore: () -> Unit = {},
    onOpenBridgeR2: () -> Unit = {},
    bridgeR2BucketLabel: String? = null,
    /** #C50: what the bound label names, so the subtitle can say "WebDAV · host · Encrypted". */
    bridgeBoundKind: BoundKind? = null,
    /** #D16: the bound drive refused the saved sign-in (the shelf's cloud check says so). */
    bridgeSignInRejected: Boolean = false,
    cloudReleaseAfterUploadEnabled: Boolean = false,
    onCloudReleaseAfterUploadChange: (Boolean) -> Unit = {},
    cloudReleaseCatchUpSummary: String? = null,
    typesafeApiKey: String? = null,
    onTypesafeApiKeyChange: (String?) -> Unit = {},
    librarySourceCount: Int = 0,
    libraryComicCount: Int = 0,
    opdsCatalogCount: Int = 0,
    ehentaiEnabled: Boolean = false,
    onEhentaiEnabledChange: (Boolean) -> Unit = {},
    /**
     * W7: the ExHentai sibling row follows the session verdict. Not Granted
     * renders a nav row (chevron) whose subtitle is the verdict's own honest
     * copy — tap launches the WebView sign-in (which is also the re-probe:
     * the sign-in flow's mint step IS the access check). Granted renders a
     * switch row, the same shape as its E-Hentai / nhentai siblings.
     */
    exhentaiAccessVerdict: ExhentaiAccessVerdict = ExhentaiAccessVerdict.SignedOut,
    exhentaiEnabled: Boolean = false,
    onExhentaiEnabledChange: (Boolean) -> Unit = {},
    onOpenExhentaiSignIn: () -> Unit = {},
    exhentaiFavoritesSyncEnabled: Boolean = true,
    onExhentaiFavoritesSyncEnabledChange: (Boolean) -> Unit = {},
    exhentaiFavoritesImporting: Boolean = false,
    exhentaiFavoritesStatusText: String? = null,
    onImportExhentaiFavorites: () -> Unit = {},
    zaimanhuaSignedIn: Boolean = false,
    zaimanhuaExpiryText: String = "",
    onZaimanhuaSignIn: () -> Unit = {},
    onZaimanhuaSignOut: () -> Unit = {},
    nhentaiEnabled: Boolean = false,
    onNhentaiEnabledChange: (Boolean) -> Unit = {},
    jmEnabled: Boolean = false,
    onJmEnabledChange: (Boolean) -> Unit = {},
    keepScreenOnEnabled: Boolean = true,
    onKeepScreenOnEnabledChange: (Boolean) -> Unit = {},
    volumeKeyPagingEnabled: Boolean = true,
    onVolumeKeyPagingEnabledChange: (Boolean) -> Unit = {},
    rtlPagingEnabled: Boolean = false,
    onRtlPagingEnabledChange: (Boolean) -> Unit = {},
    fitWidthEnabled: Boolean = false,
    onFitWidthEnabledChange: (Boolean) -> Unit = {},
    libraryViewMode: LibraryViewMode = LibraryViewMode.GRID,
    onLibraryViewModeChange: (LibraryViewMode) -> Unit = {},
    /** S6-A (owner bug A7): the shared download/upload slot cap, 1..5. */
    maxConcurrentTransfers: Int = MaxConcurrentTransfersStore.DEFAULT,
    onMaxConcurrentTransfersChange: (Int) -> Unit = {},
    /**
     * Owner bug A14 ("后台下载/上云不稳定"): `null` (every production call
     * site, including [com.easemic.app.ui.PrivateComicLibraryApp])
     * means "read the real device state" — see the `remember`/`DisposableEffect`
     * pair below, which reads it on first composition and again on every
     * `ON_RESUME` (e.g. after the user comes back from a system settings
     * screen this row deep-linked to). A non-null value is a test-only
     * override so [SettingsScreenTest] can render a deterministic advice list
     * without touching real `NotificationManagerCompat`/`PowerManager` state.
     */
    backgroundHealth: BackgroundHealth? = null,
    cacheBudgetMb: Int = CacheBudget.DEFAULT_MB,
    cacheUsageBytes: Long? = null,
    onCacheBudgetMbChange: (Int) -> Unit = {},
    onClearCache: () -> Unit = {},
    /** #E1: a newer release the update check found; null hides the row. */
    availableUpdate: AppRelease? = null,
    onOpenAvailableUpdate: () -> Unit = {},
    updateCheckEnabled: Boolean = true,
    onUpdateCheckEnabledChange: (Boolean) -> Unit = {},
    backgroundRefreshEnabled: Boolean = true,
    onBackgroundRefreshEnabledChange: (Boolean) -> Unit = {},
    backgroundWifiOnly: Boolean = true,
    onBackgroundWifiOnlyChange: (Boolean) -> Unit = {},
    languagePreference: LanguagePreference = AppI18n.currentPreference,
    onLanguagePreferenceChange: (LanguagePreference) -> Unit = { pref ->
        AppI18n.applyPreference(pref)
    },
) {
    val palette = quietShelfPalette()
    val context = LocalContext.current
    var liveBackgroundHealth by remember { mutableStateOf(currentBackgroundHealth(context)) }
    if (backgroundHealth == null) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    liveBackgroundHealth = currentBackgroundHealth(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    val effectiveBackgroundHealth = backgroundHealth ?: liveBackgroundHealth
    var transfersOpen by remember { mutableStateOf(false) }
    var comicSourcesPageOpen by rememberSaveable { mutableStateOf(false) }
    var typesafeDialogOpen by remember { mutableStateOf(false) }
    var languageDialogOpen by remember { mutableStateOf(false) }
    var typesafeDraftKey by remember(typesafeApiKey) { mutableStateOf(typesafeApiKey.orEmpty()) }
    val bound = bridgeR2BucketLabel != null
    val configuredTypesafeKey = typesafeApiKey?.trim()?.takeIf { it.isNotEmpty() }

    if (comicSourcesPageOpen) {
        BackHandler { comicSourcesPageOpen = false }
        ComicSourcesSettingsSubScreen(
            onBack = { comicSourcesPageOpen = false },
            ehentaiEnabled = ehentaiEnabled,
            onEhentaiEnabledChange = onEhentaiEnabledChange,
            exhentaiAccessVerdict = exhentaiAccessVerdict,
            exhentaiEnabled = exhentaiEnabled,
            onExhentaiEnabledChange = onExhentaiEnabledChange,
            onOpenExhentaiSignIn = onOpenExhentaiSignIn,
            exhentaiFavoritesSyncEnabled = exhentaiFavoritesSyncEnabled,
            onExhentaiFavoritesSyncEnabledChange = onExhentaiFavoritesSyncEnabledChange,
            exhentaiFavoritesImporting = exhentaiFavoritesImporting,
            exhentaiFavoritesStatusText = exhentaiFavoritesStatusText,
            onImportExhentaiFavorites = onImportExhentaiFavorites,
            zaimanhuaSignedIn = zaimanhuaSignedIn,
            zaimanhuaExpiryText = zaimanhuaExpiryText,
            onZaimanhuaSignIn = onZaimanhuaSignIn,
            onZaimanhuaSignOut = onZaimanhuaSignOut,
            nhentaiEnabled = nhentaiEnabled,
            onNhentaiEnabledChange = onNhentaiEnabledChange,
            jmEnabled = jmEnabled,
            onJmEnabledChange = onJmEnabledChange,
        )
        return
    }

    val activeSourcesCount = remember(ehentaiEnabled, exhentaiAccessVerdict, exhentaiEnabled, nhentaiEnabled, jmEnabled) {
        // Zaimanhua is always listed in Browse (#E24); count optional sources on top of it.
        var count = 1
        if (ehentaiEnabled) count += 1
        if (exhentaiAccessVerdict == ExhentaiAccessVerdict.Granted && exhentaiEnabled) count += 1
        if (nhentaiEnabled) count += 1
        if (jmEnabled) count += 1
        count
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(AppStrings.settingsTitle, style = MaterialTheme.typography.titleLarge)
        if (availableUpdate != null) {
            SettingsGroup { UpdateAvailableRow(availableUpdate, onOpen = onOpenAvailableUpdate) }
        }
        SettingsGroup {
            SettingsNavRow(
                title = AppStrings.cloudLibraryTitle,
                subtitle = BridgeR2StorageSubtitle.format(
                    bucketLabel = bridgeR2BucketLabel,
                    kind = bridgeBoundKind,
                    signInRejected = bridgeSignInRejected,
                ),
                leading = { CloudLibraryMark(bound) },
                onClick = onOpenBridgeR2,
                testTag = "settings-bridge-r2",
                showDivider = true,
            )
            if (bound) {
                SettingsSwitchRow(
                    title = CloudReleaseAfterUploadCopy.TITLE,
                    subtitle = CloudReleaseAfterUploadCopy.SUBTITLE,
                    checked = cloudReleaseAfterUploadEnabled,
                    onCheckedChange = onCloudReleaseAfterUploadChange,
                    testTag = "settings-cloud-release-after-upload",
                    showDivider = true,
                )
                if (cloudReleaseCatchUpSummary != null) {
                    Text(
                        cloudReleaseCatchUpSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
            }
            SettingsNavRow(
                title = AppStrings.typesafeTitle,
                subtitle = if (configuredTypesafeKey != null) {
                    TypesafeApiKeyStore.maskedSubtitle(configuredTypesafeKey)
                } else {
                    AppStrings.typesafeSubtitleUnconfigured
                },
                value = if (configuredTypesafeKey != null) AppStrings.edit else AppStrings.setKey,
                valueColor = if (configuredTypesafeKey != null) palette.statusOk else palette.muted,
                valueTag = "settings-typesafe-api-key-action",
                onClick = {
                    typesafeDraftKey = configuredTypesafeKey.orEmpty()
                    typesafeDialogOpen = true
                },
                testTag = "settings-typesafe-api-key-row",
                showDivider = false,
            )
        }
        SettingsGroupLabel(AppStrings.sourcesGroupLabel)
        SettingsGroup {
            SettingsNavRow(
                title = ComicSourcesSettingsCopy.TITLE,
                subtitle = ComicSourcesSettingsCopy.SUBTITLE,
                value = AppStrings.activeSourcesCountLabel(activeSourcesCount),
                valueTag = "settings-comic-sources-count",
                leading = { MutedGlyph { lucideCompass() } },
                onClick = { comicSourcesPageOpen = true },
                testTag = "settings-comic-sources",
                showDivider = true,
            )
            SettingsNavRow(
                title = AppStrings.opdsLibrariesTitle,
                value = countOrNone(opdsCatalogCount),
                leading = { MutedGlyph { lucideBook(filled = false) } },
                onClick = onOpenOpdsCatalogs,
                showDivider = true,
            )
            SettingsNavRow(
                title = AppStrings.foldersOnPhoneTitle,
                value = countOrNone(librarySourceCount),
                leading = { MutedGlyph { lucideFolder(filled = false) } },
                onClick = onOpenLibrarySources,
                showDivider = false,
            )
        }
        SettingsGroupLabel(AppStrings.storageOnPhoneGroupLabel)
        SettingsGroup {
            CacheBudgetSettingsPanel(
                budgetMb = cacheBudgetMb,
                usageBytes = cacheUsageBytes,
                onBudgetChange = onCacheBudgetMbChange,
                onClear = onClearCache,
            )
        }
        SettingsGroupLabel(AppStrings.readingGroupLabel)
        SettingsGroup {
            SettingsNavRow(
                title = AppStrings.libraryLayoutTitle,
                value = LibraryLayoutSubtitle.format(libraryViewMode),
                onClick = {
                    onLibraryViewModeChange(
                        if (libraryViewMode == LibraryViewMode.GRID) {
                            LibraryViewMode.LIST
                        } else {
                            LibraryViewMode.GRID
                        },
                    )
                },
                testTag = "settings-library-layout",
                showDivider = true,
            )
            SettingsSwitchRow(
                title = AppStrings.keepScreenOnTitle,
                checked = keepScreenOnEnabled,
                onCheckedChange = onKeepScreenOnEnabledChange,
                testTag = "settings-keep-screen-on",
                showDivider = true,
            )
            SettingsSwitchRow(
                title = AppStrings.volumeKeysTurnPagesTitle,
                checked = volumeKeyPagingEnabled,
                onCheckedChange = onVolumeKeyPagingEnabledChange,
                testTag = "settings-volume-key-paging",
                showDivider = true,
            )
            SettingsSwitchRow(
                title = AppStrings.rtlPagingTitle,
                checked = rtlPagingEnabled,
                onCheckedChange = onRtlPagingEnabledChange,
                testTag = "settings-rtl-paging",
                showDivider = false,
            )
            // #D6 (owner 2026-09-27): no "Fit width" row — the page always fits the screen. The
            // store and the reader parameter stay; the host no longer passes a stored value.
        }
        SettingsGroupLabel(AppStrings.downloadsGroupLabel)
        SettingsGroup {
            SettingsNavRow(
                title = MaxConcurrentTransfersCopy.TITLE,
                value = maxConcurrentTransfers.toString(),
                onClick = { transfersOpen = !transfersOpen },
                testTag = "settings-max-transfers",
                showDivider = true,
            )
            if (transfersOpen) {
                SettingsStepperControls(
                    title = MaxConcurrentTransfersCopy.TITLE,
                    value = maxConcurrentTransfers,
                    min = MaxConcurrentTransfersStore.MIN,
                    max = MaxConcurrentTransfersStore.MAX,
                    onValueChange = onMaxConcurrentTransfersChange,
                    testTag = "settings-max-transfers",
                )
            }
            SettingsSwitchRow(
                title = AppStrings.checkFollowedSeriesInBgTitle,
                checked = backgroundRefreshEnabled,
                onCheckedChange = onBackgroundRefreshEnabledChange,
                testTag = "settings-background-refresh",
                showDivider = true,
            )
            SettingsSwitchRow(
                title = AppStrings.onlyOnWifiTitle,
                checked = backgroundWifiOnly,
                onCheckedChange = onBackgroundWifiOnlyChange,
                enabled = backgroundRefreshEnabled,
                testTag = "settings-background-wifi-only",
                showDivider = true,
            )
            SettingsBackgroundReliability(
                health = effectiveBackgroundHealth,
                onGrantNotifications = {
                    context.launchIfResolvable(appNotificationSettingsIntent(context))
                },
                onIgnoreBatteryOptimizations = {
                    context.launchIfResolvable(batteryOptimizationIntent(context))
                },
            )
        }
        SettingsGroupLabel(AppStrings.thisPhoneGroupLabel)
        SettingsGroup {
            SettingsNavRow(
                title = AppStrings.appLanguageLabel,
                value = AppStrings.languageOptionLabel(languagePreference),
                valueTag = "settings-language-value",
                onClick = { languageDialogOpen = true },
                testTag = "settings-language",
                showDivider = true,
            )
            SettingsNavRow(
                title = AppStrings.moveToNewPhoneTitle,
                onClick = onOpenBackupRestore,
                showDivider = true,
            )
            SettingsSwitchRow(
                title = UpdateCopy.SWITCH_TITLE,
                subtitle = UpdateCopy.SWITCH_SUBTITLE,
                checked = updateCheckEnabled,
                onCheckedChange = onUpdateCheckEnabledChange,
                testTag = "settings-update-check-switch",
                showDivider = false,
            )
        }
    }
    if (languageDialogOpen) {
        AlertDialog(
            onDismissRequest = { languageDialogOpen = false },
            containerColor = palette.card,
            titleContentColor = palette.ink,
            textContentColor = palette.ink,
            title = { Text(AppStrings.appLanguageLabel) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LanguagePreference.entries.forEach { option ->
                        val isSelected = option == languagePreference
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) palette.statusOk.copy(alpha = 0.12f) else Color.Transparent,
                                )
                                .selectable(
                                    selected = isSelected,
                                    onClick = {
                                        onLanguagePreferenceChange(option)
                                        languageDialogOpen = false
                                    },
                                    role = Role.RadioButton,
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("settings-language-option-${option.storageValue}"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = AppStrings.languageOptionLabel(option),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                                color = if (isSelected) palette.statusOk else palette.ink,
                            )
                            if (isSelected) {
                                Text(
                                    text = "✓",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = palette.statusOk,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { languageDialogOpen = false },
                    modifier = Modifier.testTag("settings-language-cancel"),
                ) {
                    Text(AppStrings.cancel, color = palette.muted)
                }
            },
        )
    }
    if (typesafeDialogOpen) {
        AlertDialog(
            onDismissRequest = { typesafeDialogOpen = false },
            title = { Text(AppStrings.typesafeTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        AppStrings.typesafeDialogBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                    OutlinedTextField(
                        value = typesafeDraftKey,
                        onValueChange = { typesafeDraftKey = it },
                        label = { Text(AppStrings.apiKeyLabel) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings-typesafe-api-key-input"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onTypesafeApiKeyChange(typesafeDraftKey.trim().takeIf { it.isNotEmpty() })
                        typesafeDialogOpen = false
                    },
                    modifier = Modifier.testTag("settings-typesafe-api-key-save"),
                ) {
                    Text(AppStrings.save)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (configuredTypesafeKey != null) {
                        TextButton(
                            onClick = {
                                typesafeDraftKey = ""
                                onTypesafeApiKeyChange(null)
                                typesafeDialogOpen = false
                            },
                            modifier = Modifier.testTag("settings-typesafe-api-key-clear"),
                        ) {
                            Text(AppStrings.clear)
                        }
                    }
                    TextButton(
                        onClick = { typesafeDialogOpen = false },
                        modifier = Modifier.testTag("settings-typesafe-api-key-cancel"),
                    ) {
                        Text(AppStrings.cancel)
                    }
                }
            },
        )
    }
}

object ComicSourcesSettingsCopy {
    val TITLE: String get() = AppStrings.comicSourcesTitle
    const val SUBTITLE = "E-Hentai, ExHentai, nhentai, 禁漫天堂, 再漫画"
}

@Composable
private fun ComicSourcesSettingsSubScreen(
    onBack: () -> Unit,
    ehentaiEnabled: Boolean,
    onEhentaiEnabledChange: (Boolean) -> Unit,
    exhentaiAccessVerdict: ExhentaiAccessVerdict,
    exhentaiEnabled: Boolean,
    onExhentaiEnabledChange: (Boolean) -> Unit,
    onOpenExhentaiSignIn: () -> Unit,
    exhentaiFavoritesSyncEnabled: Boolean,
    onExhentaiFavoritesSyncEnabledChange: (Boolean) -> Unit,
    exhentaiFavoritesImporting: Boolean,
    exhentaiFavoritesStatusText: String?,
    onImportExhentaiFavorites: () -> Unit,
    zaimanhuaSignedIn: Boolean,
    zaimanhuaExpiryText: String,
    onZaimanhuaSignIn: () -> Unit,
    onZaimanhuaSignOut: () -> Unit,
    nhentaiEnabled: Boolean,
    onNhentaiEnabledChange: (Boolean) -> Unit,
    jmEnabled: Boolean,
    onJmEnabledChange: (Boolean) -> Unit,
) {
    val palette = quietShelfPalette()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.ink.copy(alpha = 0.06f))
                    .clickable(onClick = onBack)
                    .testTag("settings-comic-sources-back"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "←",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.ink,
                )
            }
            Text(
                ComicSourcesSettingsCopy.TITLE,
                style = MaterialTheme.typography.titleLarge,
                color = palette.ink,
            )
        }

        SettingsGroupLabel(AppStrings.serializedMangaGroupLabel)
        SettingsGroup {
            SettingsAccountRow(
                title = "再漫画",
                subtitle = if (zaimanhuaSignedIn) {
                    if (zaimanhuaExpiryText.isBlank()) AppStrings.zaimanhuaSignedIn else AppStrings.zaimanhuaSignedInWithExpiry(zaimanhuaExpiryText)
                } else {
                    AppStrings.zaimanhuaSignedOutSubtitle
                },
                actionLabel = if (zaimanhuaSignedIn) AppStrings.signOut else AppStrings.signIn,
                onAction = if (zaimanhuaSignedIn) onZaimanhuaSignOut else onZaimanhuaSignIn,
                onRowClick = onZaimanhuaSignIn.takeUnless { zaimanhuaSignedIn },
                leading = {
                    SourceGlyphBlock("zaimanhua", "再漫画", size = 28.dp, tagPrefix = "settings")
                },
                rowTag = "settings-zaimanhua",
                actionTag = "settings-zaimanhua-action",
                showDivider = true,
            )
            SettingsSwitchRow(
                title = JmEnableStore.DISPLAY_NAME,
                subtitle = AppStrings.jmBrowseSubtitle,
                checked = jmEnabled,
                onCheckedChange = onJmEnabledChange,
                leading = {
                    SourceGlyphBlock(JmEnableStore.SOURCE_ID, JmEnableStore.DISPLAY_NAME, size = 28.dp, tagPrefix = "settings")
                },
                testTag = "settings-jm-browse",
                showDivider = false,
            )
        }

        SettingsGroupLabel("E-Hentai & ExHentai")
        SettingsGroup {
            SettingsSwitchRow(
                title = EhentaiEnableStore.DISPLAY_NAME,
                checked = ehentaiEnabled,
                onCheckedChange = onEhentaiEnabledChange,
                leading = { SettingsLeadingLogo(EhentaiEnableStore.SOURCE_ID) },
                showDivider = true,
            )
            if (exhentaiAccessVerdict == ExhentaiAccessVerdict.Granted) {
                SettingsSwitchRow(
                    title = ExhentaiEnableStore.DISPLAY_NAME,
                    checked = exhentaiEnabled,
                    onCheckedChange = onExhentaiEnabledChange,
                    leading = { SettingsLeadingLogo(ExhentaiEnableStore.SOURCE_ID) },
                    showDivider = true,
                )
                SettingsSwitchRow(
                    title = ExhentaiFavoritesSyncStore.TITLE,
                    subtitle = ExhentaiFavoritesSyncStore.SUBTITLE,
                    checked = exhentaiFavoritesSyncEnabled,
                    onCheckedChange = onExhentaiFavoritesSyncEnabledChange,
                    testTag = "settings-exhentai-favorites-sync",
                    showDivider = true,
                )
                SettingsAccountRow(
                    title = ExhentaiFavoritesSyncStore.IMPORT_TITLE,
                    subtitle = exhentaiFavoritesStatusText ?: ExhentaiFavoritesSyncStore.IMPORT_SUBTITLE_IDLE,
                    actionLabel = if (exhentaiFavoritesImporting) "Importing…" else ExhentaiFavoritesSyncStore.IMPORT_BUTTON_LABEL,
                    actionEnabled = !exhentaiFavoritesImporting,
                    onAction = onImportExhentaiFavorites,
                    onRowClick = if (!exhentaiFavoritesImporting) onImportExhentaiFavorites else null,
                    rowTag = "settings-exhentai-favorites-import-row",
                    actionTag = "settings-exhentai-favorites-import-action",
                    showDivider = false,
                )
            } else {
                SettingsNavRow(
                    title = ExhentaiEnableStore.DISPLAY_NAME,
                    subtitle = exhentaiAccessVerdict.statusMessage(),
                    leading = { SettingsLeadingLogo(ExhentaiEnableStore.SOURCE_ID) },
                    onClick = onOpenExhentaiSignIn,
                    testTag = "settings-exhentai-sign-in",
                    showDivider = false,
                )
            }
        }

        SettingsGroupLabel(AppStrings.otherCatalogsGroupLabel)
        SettingsGroup {
            SettingsSwitchRow(
                title = NhentaiEnableStore.DISPLAY_NAME,
                checked = nhentaiEnabled,
                onCheckedChange = onNhentaiEnabledChange,
                leading = { SettingsLeadingLogo(NhentaiEnableStore.SOURCE_ID) },
                showDivider = false,
            )
        }
    }
}

@Composable
private fun CacheBudgetSettingsPanel(
    budgetMb: Int,
    usageBytes: Long?,
    onBudgetChange: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val palette = quietShelfPalette()
    var sizeChanged by remember { mutableStateOf(false) }
    val selectedMb = CacheBudgetPanel.nearestOptionMb(budgetMb)
    val options = CacheBudget.OPTIONS_MB.map { it.toInt() }
    Column(
        modifier = Modifier
            .testTag("settings-cache-budget")
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (usageBytes == null) {
                    Box(
                        modifier = Modifier
                            .size(width = 96.dp, height = 32.dp)
                            .background(palette.line, RoundedCornerShape(8.dp))
                            .testTag("settings-cache-usage-placeholder"),
                    )
                } else {
                    Text(
                        CacheBudgetCopy.usageLabel(usageBytes),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = palette.ink,
                        modifier = Modifier.testTag("settings-cache-usage"),
                    )
                }
                Text(
                    CacheBudgetCopy.ofCache(budgetMb),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(palette.statusOk.copy(alpha = 0.11f))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 11.dp, vertical = 5.dp)
                    .testTag("settings-cache-clear"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    CacheBudgetCopy.CLEAR,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.statusOk,
                )
            }
        }
        if (usageBytes != null) {
            val budgetBytes = budgetMb.coerceAtLeast(1).toLong() * CacheBudget.BYTES_PER_MB
            val fraction = (usageBytes.toFloat() / budgetBytes.toFloat()).coerceIn(0f, 1f)
            QuietProgressBar(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = palette.statusOk,
                trackColor = palette.ink.copy(alpha = 0.09f),
                height = 5.dp,
            )
        }
        // Apple Books / iOS style inset sliding-pill segmented control
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.ink.copy(alpha = 0.06f))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selectedMb
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 34.dp)
                        .clip(RoundedCornerShape(7.5.dp))
                        .background(if (isSelected) palette.card else Color.Transparent)
                        .then(
                            if (isSelected) {
                                Modifier.border(0.5.dp, palette.line, RoundedCornerShape(7.5.dp))
                            } else {
                                Modifier
                            },
                        )
                        .selectable(
                            selected = isSelected,
                            onClick = {
                                if (option != budgetMb) sizeChanged = true
                                onBudgetChange(option)
                            },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 6.dp, horizontal = 2.dp)
                        .testTag("settings-cache-preset-$option"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        cachePresetLabel(option),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = if (isSelected) palette.ink else palette.muted,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (sizeChanged) {
            Text(
                CacheBudgetCopy.RESTART_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.testTag("settings-cache-restart-note"),
            )
        }
    }
}

private fun cachePresetLabel(optionMb: Int): String = when (optionMb) {
    1024 -> "1 GB"
    2048 -> "2 GB"
    else -> "$optionMb MB"
}

/**
 * Downloads group: one reliability row. Empty advice shows "All good" and does not
 * open. Otherwise the first advice title is the subtitle, and a tap reveals the
 * explanation plus the existing advice rows.
 */
@Composable
private fun SettingsBackgroundReliability(
    health: BackgroundHealth,
    onGrantNotifications: () -> Unit,
    onIgnoreBatteryOptimizations: () -> Unit,
) {
    val advice = backgroundHealthAdvice(health)
    var open by remember(advice.isEmpty()) { mutableStateOf(false) }
    val palette = quietShelfPalette()
    if (advice.isEmpty()) {
        SettingsNavRow(
            title = AppStrings.backgroundReliabilityTitle,
            value = BackgroundTasksCopy.ALL_GOOD,
            valueTag = "settings-background-all-good",
            onClick = {},
            testTag = "settings-background-reliability",
            showDivider = false,
            clickable = false,
        )
        return
    }
    val firstTitle = when (advice.first()) {
        BackgroundAdvice.GrantNotifications -> BackgroundTasksCopy.GRANT_NOTIFICATIONS_TITLE
        BackgroundAdvice.IgnoreBatteryOptimizations -> BackgroundTasksCopy.BATTERY_TITLE
    }
    SettingsNavRow(
        title = AppStrings.backgroundReliabilityTitle,
        subtitle = firstTitle,
        subtitleColor = palette.statusWarn,
        onClick = { open = !open },
        testTag = "settings-background-reliability",
        showDivider = open,
    )
    if (open) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                BackgroundTasksCopy.EXPLANATION,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
        SettingsHairline()
        advice.forEachIndexed { index, item ->
            val showDivider = index != advice.lastIndex
            when (item) {
                BackgroundAdvice.GrantNotifications -> SettingsBackgroundAdviceRow(
                    title = BackgroundTasksCopy.GRANT_NOTIFICATIONS_TITLE,
                    subtitle = BackgroundTasksCopy.GRANT_NOTIFICATIONS_SUBTITLE,
                    actionLabel = BackgroundTasksCopy.TURN_ON,
                    onClick = onGrantNotifications,
                    testTag = "settings-background-grant-notifications",
                    showDivider = showDivider,
                )
                BackgroundAdvice.IgnoreBatteryOptimizations -> SettingsBackgroundAdviceRow(
                    title = BackgroundTasksCopy.BATTERY_TITLE,
                    subtitle = BackgroundTasksCopy.BATTERY_SUBTITLE,
                    actionLabel = BackgroundTasksCopy.ALLOW,
                    onClick = onIgnoreBatteryOptimizations,
                    testTag = "settings-background-battery-optimization",
                    showDivider = showDivider,
                )
            }
        }
    }
}

@Composable
private fun SettingsBackgroundAdviceRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    onClick: () -> Unit,
    testTag: String,
    showDivider: Boolean,
) {
    val palette = quietShelfPalette()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(testTag)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = palette.ink)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.muted)
            }
            PclOutlinedButton(onClick = onClick) {
                Text(actionLabel)
            }
        }
        if (showDivider) SettingsHairline()
    }
}

@Composable
private fun SettingsAccountRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    onRowClick: (() -> Unit)?,
    rowTag: String,
    actionTag: String,
    showDivider: Boolean,
    leading: (@Composable () -> Unit)? = null,
    actionEnabled: Boolean = true,
) {
    val palette = quietShelfPalette()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(rowTag)
                .then(onRowClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leading != null) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    leading()
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = palette.ink)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.muted)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        if (actionEnabled) {
                            palette.statusOk.copy(alpha = 0.11f)
                        } else {
                            palette.ink.copy(alpha = 0.05f)
                        },
                    )
                    .then(
                        if (actionEnabled) {
                            Modifier.clickable(onClick = onAction)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag(actionTag),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (actionEnabled) palette.statusOk else palette.muted,
                )
            }
        }
        if (showDivider) SettingsHairline()
    }
}

/** Owner bug A14: guards every deep link this section launches. */
private fun Context.launchIfResolvable(intent: Intent) {
    if (intent.resolveActivity(packageManager) != null) {
        startActivity(intent)
    }
}

/**
 * Owner bug A14 copy, kept alongside [LibraryLayoutSubtitle] rather than in
 * its own file — same precedent this file already sets for small, screen-local
 * copy objects.
 */
object BackgroundTasksCopy {
    val EXPLANATION: String get() = AppStrings.backgroundExplanation
    val GRANT_NOTIFICATIONS_TITLE: String get() = AppStrings.grantNotificationsTitle
    val GRANT_NOTIFICATIONS_SUBTITLE: String get() = AppStrings.grantNotificationsSubtitle
    val BATTERY_TITLE: String get() = AppStrings.batteryOptimizationTitle
    val BATTERY_SUBTITLE: String get() = AppStrings.batteryOptimizationSubtitle
    val TURN_ON: String get() = AppStrings.turnOnAction
    val ALLOW: String get() = AppStrings.allowAction
    val ALL_GOOD: String get() = AppStrings.backgroundAllGood
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    val palette = quietShelfPalette()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .border(1.dp, palette.line, RoundedCornerShape(14.dp)),
        content = content,
    )
}

@Composable
private fun SettingsGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = quietShelfPalette().muted,
    )
}

@Composable
private fun SettingsNavRow(
    title: String,
    onClick: () -> Unit,
    showDivider: Boolean,
    subtitle: String? = null,
    subtitleColor: Color? = null,
    value: String? = null,
    valueColor: Color? = null,
    valueTag: String? = null,
    leading: (@Composable () -> Unit)? = null,
    testTag: String? = null,
    clickable: Boolean = true,
) {
    val palette = quietShelfPalette()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leading != null) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    leading()
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = palette.ink)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor ?: palette.muted,
                    )
                }
            }
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor ?: palette.muted,
                    modifier = if (valueTag != null) Modifier.testTag(valueTag) else Modifier,
                )
            }
            if (clickable) {
                Text("›", style = MaterialTheme.typography.titleMedium, color = palette.muted)
            }
        }
        if (showDivider) SettingsHairline()
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    testTag: String? = null,
    enabled: Boolean = true,
) {
    val palette = quietShelfPalette()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leading != null) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    leading()
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) palette.ink else palette.muted,
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = palette.muted)
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = title },
            )
        }
        if (showDivider) SettingsHairline()
    }
}

/**
 * S6-A (owner bug A7): −/+ stepper for a small bounded Int, shown under the
 * "At the same time" row. Buttons disable at the bounds; the value is hoisted.
 */
@Composable
private fun SettingsStepperControls(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    testTag: String,
) {
    val palette = quietShelfPalette()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            PclOutlinedButton(
                onClick = { onValueChange((value - 1).coerceIn(min, max)) },
                enabled = value > min,
                modifier = Modifier
                    .testTag("$testTag-decrement")
                    .semantics { contentDescription = "$title decrease" },
            ) {
                Text(MaxConcurrentTransfersCopy.DECREMENT)
            }
            Text(
                value.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = palette.ink,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .testTag("$testTag-value"),
            )
            PclOutlinedButton(
                onClick = { onValueChange((value + 1).coerceIn(min, max)) },
                enabled = value < max,
                modifier = Modifier
                    .testTag("$testTag-increment")
                    .semantics { contentDescription = "$title increase" },
            ) {
                Text(MaxConcurrentTransfersCopy.INCREMENT)
            }
        }
        SettingsHairline()
    }
}

@Composable
private fun SettingsLeadingLogo(sourceId: String) {
    SourceLogoTile(sourceId, size = 28.dp, testTag = "settings-source-logo-$sourceId")
}

@Composable
private fun CloudLibraryMark(bound: Boolean) {
    val palette = quietShelfPalette()
    val image = remember(bound) { lucideCloud(filled = bound) }
    Icon(
        imageVector = image,
        contentDescription = null,
        tint = if (bound) palette.cloudUploaded else palette.muted,
        modifier = Modifier.size(24.dp),
    )
}

@Composable
private fun MutedGlyph(image: () -> ImageVector) {
    val vector = remember { image() }
    Icon(
        imageVector = vector,
        contentDescription = null,
        tint = quietShelfPalette().muted,
        modifier = Modifier.size(24.dp),
    )
}

private fun countOrNone(count: Int): String = if (count == 0) AppStrings.none else count.toString()

@Composable
private fun SettingsHairline() {
    val palette = quietShelfPalette()
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        thickness = 1.dp,
        color = palette.line,
    )
}
