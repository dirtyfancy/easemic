package com.easemic.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easemic.app.AppGraph
import com.easemic.app.BuildConfig
import com.easemic.app.SerializedDeleteResult
import com.easemic.app.data.DownloadLibraryAvailability
import com.easemic.app.data.LibraryRepository
import com.easemic.app.feature.activity.ActivityScreen
import com.easemic.app.feature.activity.ImportActivityRow
import com.easemic.app.feature.activity.ImportActivityTracker
import com.easemic.app.feature.activity.SerializedChapterPopulation
import com.easemic.app.feature.activity.SerializedActivitySummary
import com.easemic.app.feature.activity.activityNavItemId
import com.easemic.app.feature.bridge.BoundKind
import com.easemic.app.feature.bridge.BridgeDeviceR2SecretStore
import com.easemic.app.feature.bridge.BridgeOAuthDeviceSecretStore
import com.easemic.app.feature.bridge.BridgeOAuthPkceSessionStore
import com.easemic.app.feature.bridge.BridgeR2BindPresenter
import com.easemic.app.feature.bridge.BridgeR2BindScreen
import com.easemic.app.feature.bridge.ByoCloudIo
import com.easemic.app.feature.bridge.ByoCloudIoResult
import com.easemic.app.feature.bridge.CloudBucketProbe
import com.easemic.app.feature.bridge.CloudProbeGrace
import com.easemic.app.feature.bridge.CloudProbeKeys
import com.easemic.app.feature.bridge.CloudSignInCopy
import com.easemic.app.feature.bridge.CloudStoragePickerScreen
import com.easemic.app.feature.bridge.CloudStoragePlace
import com.easemic.app.feature.bridge.CloudflareAuthorizeOpener
import com.easemic.app.feature.bridge.CloudflareOAuthConfig
import com.easemic.app.feature.bridge.CloudflarePkceClient
import com.easemic.app.feature.bridge.CloudflareR2AdminClient
import com.easemic.app.feature.bridge.DriveBindStep
import com.easemic.app.feature.bridge.DriveKind
import com.easemic.app.feature.bridge.EmptyBucketModeChoiceDialog
import com.easemic.app.feature.bridge.FastPathCatchUpPlan
import com.easemic.app.feature.bridge.GraceState
import com.easemic.app.feature.bridge.LibraryCloudReadiness
import com.easemic.app.feature.bridge.LibraryLockEvent
import com.easemic.app.feature.bridge.LibraryLockPresenter
import com.easemic.app.feature.bridge.OAuthDriveBindPresenter
import com.easemic.app.feature.bridge.OAuthDrives
import com.easemic.app.feature.bridge.OauthRestStoreRefresh
import com.easemic.app.feature.bridge.OauthRestStoreRefreshResult
import com.easemic.app.feature.bridge.OneDriveBindPresenter
import com.easemic.app.feature.bridge.OneDriveBindScreen
import com.easemic.app.feature.bridge.ProbeCause
import com.easemic.app.feature.bridge.ProbeOutcome
import com.easemic.app.feature.bridge.R2LocationHint
import com.easemic.app.feature.bridge.WebDavBindPresenter
import com.easemic.app.feature.bridge.WebDavBindScreen
import com.easemic.app.feature.bridge.applyBindCommit
import com.easemic.app.feature.bridge.catchUpPlanFor
import com.easemic.app.feature.bridge.cloudPlaceReauthing
import com.easemic.app.feature.bridge.graceOutcomeForBlockedStore
import com.easemic.app.feature.bridge.graceStateAfter
import com.easemic.app.feature.bridge.shouldReprobe
import com.easemic.app.feature.browse.BrowseFeedAppend
import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.BrowseFeedLanding
import com.easemic.app.feature.browse.BrowseFeedRepository
import com.easemic.app.feature.browse.BrowseFeedResult
import com.easemic.app.feature.browse.BrowseFeedNotice
import com.easemic.app.feature.browse.BrowseFeedSession
import com.easemic.app.feature.browse.BrowseFeedSessionKey
import com.easemic.app.feature.browse.BrowsePrefs
import com.easemic.app.feature.browse.BrowsePrefsStore
import com.easemic.app.feature.browse.BrowseScreen
import com.easemic.app.feature.browse.BrowseSourceList
import com.easemic.app.feature.browse.CloudCollectionFeedMapper
import com.easemic.app.feature.browse.CloudDetailPreviewProgress
import com.easemic.app.feature.browse.CloudPageFileLoader
import com.easemic.app.feature.browse.CloudReaderPageFiles
import com.easemic.app.feature.browse.CloudPresenceSets
import com.easemic.app.feature.browse.CloudReaderScreen
import com.easemic.app.feature.browse.CloudReaderSession
import com.easemic.app.feature.browse.CloudReaderChapterResolver
import com.easemic.app.feature.browse.CloudReaderTransitionPresentation
import com.easemic.app.feature.browse.CloudChapterRow
import com.easemic.app.feature.browse.CloudReaderStoreChoice
import com.easemic.app.feature.browse.CloudThumbCache
import com.easemic.app.feature.browse.ComicDetailScreen
import com.easemic.app.feature.browse.DetailDownloadIdentity
import com.easemic.app.feature.browse.DetailSourceFamily
import com.easemic.app.feature.browse.DetailSourceFamilyResolver
import com.easemic.app.feature.browse.DetailSwitchFacts
import com.easemic.app.feature.browse.DetailSwitchState
import com.easemic.app.feature.browse.EhentaiDetailEnrichment
import com.easemic.app.feature.browse.FrontOnlineReadPrep
import com.easemic.app.feature.browse.NhentaiDetailEnrichment
import com.easemic.app.feature.browse.OnlineReaderScreen
import com.easemic.app.feature.browse.OnlineReaderSession
import com.easemic.app.feature.browse.onlineChapterTransitionPresentation
import com.easemic.app.feature.browse.PreferenceMapSourceFilterStore
import com.easemic.app.feature.browse.PreviewPrefetchWindow
import com.easemic.app.feature.browse.ReadRouteResolver
import com.easemic.app.feature.browse.RemoteProgressPolicy
import com.easemic.app.feature.browse.RemoteReadingProgressRecorder
import com.easemic.app.feature.browse.ResourcePresence
import com.easemic.app.feature.browse.ResourcePresenceResolver
import com.easemic.app.feature.browse.SerializedReadRoute
import com.easemic.app.feature.browse.SerializedCollectConfirmationDialog
import com.easemic.app.feature.browse.serializedChapterResultForDetail
import com.easemic.app.feature.browse.serializedDetailReadButtonTarget
import com.easemic.app.feature.browse.serializedNothingToReadLine
import com.easemic.app.feature.browse.serializedDetailReadLabel
import com.easemic.app.feature.browse.serializedContinueText
import com.easemic.app.feature.browse.serializedChapterGroups
import com.easemic.app.feature.browse.serializedDetailSeries
import com.easemic.app.feature.browse.serializedCloudReadItem
import com.easemic.app.feature.browse.onlineReadItemFor
import com.easemic.app.feature.browse.sameRemoteItem
import com.easemic.app.feature.browse.cloudItemMatches
import com.easemic.app.feature.browse.cloudUploadRowStranded
import com.easemic.app.feature.browse.serializedSeriesTransferPending
import com.easemic.app.feature.browse.SourceFilterState
import com.easemic.app.feature.browse.SourceFilterStore
import com.easemic.app.feature.browse.browseSourceLabel
import com.easemic.app.feature.browse.browseSourceListItems
import com.easemic.app.feature.browse.browseSourceOptions
import com.easemic.app.feature.browse.cloudPreviewBytes
import com.easemic.app.feature.browse.locatorCatalogTags
import com.easemic.app.feature.browse.normalizeBrowseSourceId
import com.easemic.app.feature.browse.DetailEntryOrigin
import com.easemic.app.feature.browse.seriesIdentityFor
import com.easemic.app.feature.browse.recentlyDeletedButtonEnabled
import com.easemic.app.feature.browse.resolveCloudDetailMetadata
import com.easemic.app.feature.browse.resolveCloudDetailPreviewResource
import com.easemic.app.feature.browse.sourceFilterSheetShapeFor
import com.easemic.app.feature.browse.tagSearchTarget
import com.easemic.app.feature.browse.TagSearchTarget
import com.easemic.app.feature.browse.DetailSearchKind
import com.easemic.app.feature.browse.JmSearchSession
import com.easemic.app.feature.browse.JmSearchScope
import com.easemic.app.feature.browse.filtersAfterTagSearch
import com.easemic.app.feature.source.valueOrNull
import com.easemic.app.feature.browse.detailSearchProviderId
import com.easemic.app.feature.browse.toDownloadLocator
import com.easemic.app.feature.browse.usesCloudCollection
import com.easemic.app.feature.browse.withOpeningPageStarted
import com.easemic.app.feature.collect.CloudCatchUpSweepAction
import com.easemic.app.feature.collect.serializedChapterCatchUpAction
import com.easemic.app.feature.collect.CloudCollectPreflightDecision
import com.easemic.app.feature.collect.CloudIngestOutcome
import com.easemic.app.feature.collect.CloudSyncQueueItem
import com.easemic.app.feature.collect.CloudSyncQueueScreen
import com.easemic.app.feature.collect.CloudSyncStage
import com.easemic.app.feature.collect.CollectBookmarkStore
import com.easemic.app.feature.collect.CollectCloudIngestHandoff
import com.easemic.app.feature.collect.CollectCloudIngestRoute
import com.easemic.app.feature.collect.CollectPreflightStatus
import com.easemic.app.feature.collect.CollectedBookmark
import com.easemic.app.feature.collect.ExhentaiFavoritesImporter
import com.easemic.app.feature.collect.ExhentaiFavoritesImportResult
import com.easemic.app.feature.collect.SerializedCollectResult
import com.easemic.app.feature.collect.SerializedSeriesUnfollowResult
import com.easemic.app.feature.collect.CoverSource
import com.easemic.app.feature.collect.SyncRetryAction
import com.easemic.app.feature.collect.UncollectCloudFailureKind
import com.easemic.app.feature.collect.UncollectCloudOutcome
import com.easemic.app.feature.collect.applyUncollectJobOutcome
import com.easemic.app.feature.collect.bookmarksMatchingUncollect
import com.easemic.app.feature.collect.bookmarksForItem
import com.easemic.app.feature.collect.isSerializedBookmark
import com.easemic.app.feature.collect.catchUpSweepAction
import com.easemic.app.feature.collect.cloudUploadInFlight
import com.easemic.app.feature.collect.resolveCoverSource
import com.easemic.app.feature.collect.tombstoneForUncollect
import com.easemic.app.feature.collect.shouldRemoveSerializedBookmarkOnConfirmationCancel
import com.easemic.app.feature.detail.DetailDownloadDeleteCopy
import com.easemic.app.feature.detail.DetailLoader
import com.easemic.app.feature.detail.DetailOpenStage
import com.easemic.app.feature.detail.DetailItemGoneScreen
import com.easemic.app.feature.detail.DetailLoadingScreen
import com.easemic.app.feature.detail.DetailSubject
import com.easemic.app.feature.detail.DetailTruncatedSeal
import com.easemic.app.feature.detail.DetailUiState
import com.easemic.app.feature.detail.DetailViewModel
import com.easemic.app.feature.detail.LocalCloudPresence
import com.easemic.app.feature.detail.detailCollectFeedItem
import com.easemic.app.feature.detail.detailReadLabel
import com.easemic.app.feature.detail.detailStatusForOwner
import com.easemic.app.feature.detail.detailTruncatedSeal
import com.easemic.app.feature.detail.detailUncollectFeedItem
import com.easemic.app.feature.detail.cloudSeriesManifestFromMirror
import com.easemic.app.feature.download.BrowseGalleryIdentity
import com.easemic.app.feature.download.BrowseGalleryIdentityResult
import com.easemic.app.feature.download.CloudLocalDownloadIdentity
import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.DownloadLocator
import com.easemic.app.feature.download.serializedChapterAdapterIdentity
import com.easemic.app.feature.download.DownloadPageResolveException
import com.easemic.app.feature.download.DownloadQueueRow
import com.easemic.app.feature.download.DownloadQueueScreen
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.serializedSeriesLedgerKey
import com.easemic.app.feature.library.UpdateDayGroup
import com.easemic.app.feature.library.UpdateRowPresentation
import com.easemic.app.feature.library.parseUpdateStableKey
import com.easemic.app.feature.download.serializedChapterLocatorKey
import com.easemic.app.feature.download.ChapterIdentityV2
import com.easemic.app.feature.download.acquisitionKey
import com.easemic.app.feature.download.cfChallengeBannerSourceLabel
import com.easemic.app.feature.download.cfChallengeJob
import com.easemic.app.feature.download.delistedShelfKeys
import com.easemic.app.feature.download.downloadQueueRows
import com.easemic.app.feature.download.isDelistedAtSource
import com.easemic.app.feature.download.jobOwnsLocalPages
import com.easemic.app.feature.download.putKeepingCatalogFacts
import com.easemic.app.feature.download.storageIdentity
import com.easemic.app.feature.history.BrowseHistoryStore
import com.easemic.app.feature.history.HistoryEntry
import com.easemic.app.feature.history.HistoryReopenTarget
import com.easemic.app.feature.history.HistoryScreen
import com.easemic.app.feature.history.cloudDurableCoverPath
import com.easemic.app.feature.history.cloudThumbCachePath
import com.easemic.app.feature.history.historyRowCoverUrl
import com.easemic.app.feature.history.historySourceNameFor
import com.easemic.app.feature.history.resolveHistoryOpenTarget
import com.easemic.app.feature.importing.FoldersScreen
import com.easemic.app.feature.importing.ImportOutcomeCopy
import com.easemic.app.feature.importing.ImportUiState
import com.easemic.app.feature.importing.ImportViewModel
import com.easemic.app.feature.importing.PickerRequest
import com.easemic.app.feature.library.CloudImportProgressPresentation
import com.easemic.app.feature.library.ChapterShelfRow
import com.easemic.app.feature.library.LibraryCloudSegmentCopy
import com.easemic.app.feature.library.LibraryOpdsShelfCopy
import com.easemic.app.feature.library.LibraryRecycleFeedMapper
import com.easemic.app.feature.library.LibraryScreen
import com.easemic.app.feature.library.LibraryStateFilter
import com.easemic.app.feature.library.LibraryUiState
import com.easemic.app.feature.library.ShelfLine
import com.easemic.app.feature.library.serializedOpenTarget
import com.easemic.app.feature.library.localSerializedDetailItem
import com.easemic.app.feature.library.serializedSourceCoverIdentities
import com.easemic.app.feature.library.serializedSourceCoverUris
import com.easemic.app.feature.library.galleryDedupKey
import com.easemic.app.feature.library.seedCloudShelfCards
import com.easemic.app.feature.providers.CloudCollectionSource
import com.easemic.app.feature.providers.EhentaiAccountStore
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.providers.ExhentaiEnableStore
import com.easemic.app.feature.providers.ExhentaiFavoritesSyncStore
import com.easemic.app.feature.providers.ZaimanhuaAccount
import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.feature.providers.JmEnableStore
import com.easemic.app.feature.update.AppUpdateDialogPhase
import com.easemic.app.feature.update.AppUpdateHelper
import com.easemic.app.feature.update.AppUpdateInstaller
import com.easemic.app.feature.update.UpdateAvailableDialog
import com.easemic.app.feature.update.UpdateCheckClient
import com.easemic.app.feature.update.UpdateChecker
import com.easemic.app.feature.update.UpdateOffer
import com.easemic.app.feature.update.UpdatePrefsStore
import com.easemic.app.i18n.AppI18n
import com.easemic.app.i18n.AppLanguageStore
import com.easemic.app.i18n.LanguagePreference
import com.easemic.app.feature.providers.OpdsCatalogConnect
import com.easemic.app.feature.providers.OpdsCatalogConnectEditResult
import com.easemic.app.feature.providers.OpdsConnectResult
import com.easemic.app.feature.providers.OpdsProviderConfig
import com.easemic.app.feature.providers.OpdsProviderStore
import com.easemic.app.feature.providers.OpdsProvidersScreen
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.SeriesDetailSnapshot
import com.easemic.app.feature.source.snapshotFromListMetadata
import com.easemic.app.feature.source.ChapterListCompleteness
import com.easemic.app.feature.source.zaimanhua.ZAIMANHUA_LISTED_IN_BROWSE
import com.easemic.app.feature.reader.ReaderFitWidthStore
import com.easemic.app.feature.reader.ReaderKeepScreenOnStore
import com.easemic.app.feature.reader.ReaderLayoutModeStore
import com.easemic.app.feature.reader.ReaderRtlPagingStore
import com.easemic.app.feature.reader.ReaderScreen
import com.easemic.app.feature.reader.ReaderUiState
import com.easemic.app.feature.reader.ReaderViewModel
import com.easemic.app.feature.reader.ReaderVolumeKeyPagingStore
import com.easemic.app.feature.reader.ReaderChapterBoundary
import com.easemic.app.feature.reader.ReaderChapterContext
import com.easemic.app.feature.reader.ChapterReaderCoordinator
import com.easemic.app.feature.reader.ReaderChapter
import com.easemic.app.feature.reader.LAST_PAGE
import com.easemic.app.feature.reader.localChapterTransitionPresentation
import com.easemic.app.feature.reader.SerializedChapterRoute
import com.easemic.app.feature.settings.BackupPassphraseAction
import com.easemic.app.feature.settings.BackupPassphraseDialog
import com.easemic.app.feature.settings.BackupPassphrasePromptState
import com.easemic.app.feature.settings.BackupRestoreScreen
import com.easemic.app.feature.settings.CloudReleaseAfterUploadCopy
import com.easemic.app.feature.settings.CloudReleaseAfterUploadStore
import com.easemic.app.feature.settings.backgroundRefreshHintLine
import com.easemic.app.feature.settings.launchBackgroundRefreshOpen
import com.easemic.app.feature.settings.LibraryLayoutStore
import com.easemic.app.feature.settings.SettingsScreen
import com.easemic.app.feature.settings.backup.BackupFileNames
import com.easemic.app.feature.settings.backup.BackupPresenter
import com.easemic.app.feature.settings.backup.Destination
import com.easemic.app.feature.settings.isValid
import com.easemic.app.feature.settings.validated
import com.easemic.app.importing.CatalogRequestGate
import com.easemic.app.importing.ehentai.EhentaiClient
import com.easemic.app.importing.ehentai.EhentaiLazyGalleryPlan
import com.easemic.app.importing.ehentai.EhentaiPreviewMaterializer
import com.easemic.app.importing.nhentai.NhentaiClient
import com.easemic.app.importing.nhentai.NhentaiGalleryPlan
import com.easemic.app.importing.opds.OpdsCatalogClient
import com.easemic.app.importing.opds.OpdsHttpGet
import com.easemic.app.importing.opds.OpdsPsePageFetch
import com.easemic.app.importing.opds.OpdsPsePages
import com.easemic.app.importing.opds.OpdsPseReadAuth
import com.easemic.app.model.LibraryItemId
import com.easemic.app.model.ReadingUnitId
import com.easemic.app.model.SourceId
import com.easemic.app.storage.CreateDocumentInDownloads
import com.easemic.app.storage.OpenDocumentInDownloads
import com.easemic.app.storage.isReadableCacheUri
import com.easemic.app.sync.CloudCollectAction
import com.easemic.app.sync.CloudCollectLifecycle
import com.easemic.app.sync.CloudCollectionCard
import com.easemic.app.sync.CloudCollectionStore
import com.easemic.app.sync.GalleryCloudObjectLayout
import com.easemic.app.sync.CloudSeriesManifest
import com.easemic.app.sync.ChapterCloudObjectLayout
import com.easemic.app.sync.CloudCardLayout
import com.easemic.app.sync.CloudOpenTrace
import com.easemic.app.sync.CloudPageDiskCache
import com.easemic.app.sync.CloudSignIn
import com.easemic.app.sync.DamagedLibraryLockException
import com.easemic.app.sync.DiagLog
import com.easemic.app.sync.EncryptedBackupCodec
import com.easemic.app.sync.EncryptedBackupException
import com.easemic.app.sync.LibraryLockedException
import com.easemic.app.sync.LibraryStateBackup
import com.easemic.app.sync.LibraryStateSnapshot
import com.easemic.app.sync.NewerCloudFormatException
import com.easemic.app.sync.ObjectStore
import com.easemic.app.sync.OpdsCloudIngestGuard
import com.easemic.app.sync.R2ObjectStore
import com.easemic.app.sync.ReadingProgressBackup
import com.easemic.app.sync.ReadingProgressBackupEntry
import com.easemic.app.sync.UnreadableCloudLibraryException
import com.easemic.app.sync.archive.ArchiveReader
import com.easemic.app.sync.redactAcquisition
import com.easemic.app.sync.redactIdentity
import com.easemic.app.ui.components.PclOutlinedButton
import com.easemic.app.ui.theme.PclSpacing
import com.easemic.app.ui.theme.PrivateComicLibraryTheme
import com.easemic.app.work.CloudIngestWorker
import com.easemic.app.work.DownloadPumpWorker
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
// A40: AppRoute, SourcesReturnTo and toBottomBarDestination live in
// AppBottomBarRouting.kt (same package, `internal`) so the route→tab mapping is
// unit-testable. The saver stays internal for the route compatibility tests.

internal val AppRouteSaver = listSaver<AppRoute, String>(
    save = { route ->
        when (route) {
            AppRoute.Library -> listOf("library")
            is AppRoute.Sources -> listOf("sources", route.returnTo.name)
            AppRoute.Browse -> listOf("browse")
            AppRoute.Activity -> listOf("activity")
            AppRoute.History -> listOf("history")
            AppRoute.Settings -> listOf("settings")
            AppRoute.OpdsCatalogs -> listOf("opds-catalogs")
            AppRoute.BackupRestore -> listOf("backup-restore")
            AppRoute.DownloadQueue -> listOf("download-queue")
            AppRoute.CloudSyncQueue -> listOf("cloud-sync-queue")
            AppRoute.BridgeR2Bind -> listOf("bridge-r2-bind")
            is AppRoute.Detail -> listOf("detail", route.itemId.toString())
            is AppRoute.Reader -> listOf(
                "reader",
                route.itemId.toString(),
                "",
            ) + when {
                route.readingUnitId != null -> listOfNotNull(
                    route.readingUnitId.toString(),
                    route.chapterId,
                )
                route.chapterId != null -> listOf("", route.chapterId)
                else -> emptyList()
            }
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            "sources" -> AppRoute.Sources(
                returnTo = saved.getOrNull(1)
                    ?.let { runCatching { SourcesReturnTo.valueOf(it) }.getOrNull() }
                    ?: SourcesReturnTo.SETTINGS,
            )
            "browse" -> AppRoute.Browse
            "activity" -> AppRoute.Activity
            "history" -> AppRoute.History
            // "more" is accepted for saved-state compatibility with pre-IA-shell instances.
            "settings", "more" -> AppRoute.Settings
            "opds-catalogs" -> AppRoute.OpdsCatalogs
            "backup-restore" -> AppRoute.BackupRestore
            "download-queue" -> AppRoute.DownloadQueue
            "cloud-sync-queue" -> AppRoute.CloudSyncQueue
            "bridge-r2-bind" -> AppRoute.BridgeR2Bind
            "detail" -> saved.getOrNull(1)?.toLongOrNull()?.let(AppRoute::Detail)
            "reader" -> saved.getOrNull(1)?.toLongOrNull()?.let { itemId ->
                AppRoute.Reader(
                    itemId = itemId,
                    initialOrdinal = saved.getOrNull(2)?.toIntOrNull(),
                    readingUnitId = saved.getOrNull(3)?.toLongOrNull(),
                    chapterId = saved.getOrNull(4)?.takeIf { it.isNotEmpty() },
                )
            }
            else -> AppRoute.Library
        } ?: AppRoute.Library
    },
)

/** One serialized detail's local values, tagged with the series that emitted them. */
internal data class SerializedLocalState(
    val series: GalleryIdentityV1,
    val itemId: LibraryItemId?,
    val rows: List<ChapterShelfRow>,
    val jobs: List<DownloadJob>,
)

internal data class PendingConfirmation(
    val item: BrowseFeedItem,
    val chapterCount: Int,
    val fromCollectTap: Boolean,
)

/** Drops a late emission that belongs to the detail previously on screen. */
internal fun localRowsFor(
    openSeries: GalleryIdentityV1,
    emitted: SerializedLocalState,
): SerializedLocalState = emitted.takeIf { it.series == openSeries }
    ?: SerializedLocalState(
        series = openSeries,
        itemId = null,
        rows = emptyList(),
        jobs = emptyList(),
    )

private fun AppBottomBarDestination.toRoute(): AppRoute = when (this) {
    AppBottomBarDestination.LIBRARY -> AppRoute.Library
    AppBottomBarDestination.BROWSE -> AppRoute.Browse
    AppBottomBarDestination.ACTIVITY -> AppRoute.Activity
    AppBottomBarDestination.HISTORY -> AppRoute.History
    AppBottomBarDestination.MORE -> AppRoute.Settings
}

data class ImportUiActions(
    val onPickerRequest: (PickerRequest) -> Unit = {},
    val onRescanSource: (SourceId) -> Unit = {},
    val onRescanAllFolders: () -> Unit = {},
    val onReauthorizeSource: (SourceId) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onStopWatching: (SourceId) -> Unit = {},
    val onUndoStopWatching: (SourceId) -> Unit = {},
    /** Clears the current shelf line's outcome immediately (review MEDIUM-2). */
    val onDismissImportOutcome: () -> Unit = {},
)

@Composable
fun PrivateComicLibraryApp(
    state: LibraryUiState,
    onOpenItem: (Long) -> Unit,
    onDismissNotice: () -> Unit,
    onImportFolder: () -> Unit,
    importState: ImportUiState = ImportUiState(),
    importActions: ImportUiActions = ImportUiActions(),
    onCoverOpenFailed: (Long) -> Unit = {},
    repository: LibraryRepository? = null,
    appGraphOverride: AppGraph? = null,
    bindLiveDeviceSession: Boolean = true,
    /**
     * Task 5 (quiet-shelf): in-place Library search. Handed the
     * [LibraryViewModel] shelf callbacks by MainActivity; defaults no-op so
     * previews/tests keep compiling.
     */
    onEnterLibrarySearch: () -> Unit = {},
    onExitLibrarySearch: () -> Unit = {},
    onLibrarySearchQueryChange: (String) -> Unit = {},
    /**
     * Task 6 (quiet-shelf): the Library filter sheet streams its multi-select
     * [LibraryStateFilter] changes here — production hands it to
     * [LibraryViewModel.setFilter]. Default no-op keeps previews/tests
     * compiling.
     */
    onLibraryFilterChange: (LibraryStateFilter) -> Unit = {},
    openLibraryUpdates: Boolean = false,
    onLibraryUpdatesIntentConsumed: () -> Unit = {},
) {
    var route: AppRoute by rememberSaveable(stateSaver = AppRouteSaver) {
        mutableStateOf(AppRoute.Library)
    }
    var updatesSelected by rememberSaveable { mutableStateOf(openLibraryUpdates) }
    var updatesRefreshing by remember { mutableStateOf(false) }
    // Preview has no returnTo of its own; it always opens from the Sources
    // list, so remember which return target that list was using.
    var sourcesReturnTo by rememberSaveable { mutableStateOf(SourcesReturnTo.SETTINGS) }
    // Not rememberSaveable: this is a 6s transient banner, not durable UI
    // state, and must not survive process death and mask the next import line
    // (review HIGH-3).
    var filesGoneAtMillis by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(filesGoneAtMillis) {
        if (filesGoneAtMillis != null) {
            delay(ImportViewModel.LINE_WINDOW_MILLIS)
            filesGoneAtMillis = null
        }
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val appGraph = remember(appGraphOverride) {
        appGraphOverride ?: AppGraph.get(context.applicationContext)
    }
    val updateLog by appGraph.chapterUpdateRecorder.observe()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val libraryHasUnseenUpdates = updateLog.any { it.seenAtMillis == null }
    // Tagged with the item it was read for, so a chapter route never draws its pager from another series' rows
    // (or before its own load): the transition cards add pager slots, and adding them late would shift every page.
    val localReaderChapterRows by produceState<Pair<Long, List<ChapterShelfRow>>?>(
        initialValue = null,
        key1 = route,
    ) {
        val readerRoute = route as? AppRoute.Reader
        if (readerRoute?.chapterId != null) {
            value = readerRoute.itemId to withContext(Dispatchers.IO) {
                appGraph.repository.chapterShelfRowsForItem(readerRoute.itemId)
            }
        }
    }
    val backgroundIngestRegistry = appGraph.backgroundIngestRegistry
    val cloudProcess = remember { appGraph.cloudProcessSession }
    // A52 (owner bug backlog, ledger 2026-09-04): the tracker that turns scan
    // runs into Activity import rows. Held here — the app root composable —
    // NOT inside the Activity route block, so its seenDoneRunIds dedup and
    // pendingDone receipts survive navigation between tabs: a done row that
    // already rendered on one visit clears only when the NEXT visit begins
    // (beginVisit, called from the Activity route's visit boundary), and a
    // receipt from a previous session never renders at all (the processStartMs
    // staleness gate below). In-session only, deliberately: process death
    // losing the one-visit visibility is the accepted weaker guarantee (see
    // the A52 audit).
    val importActivityTracker = remember {
        ImportActivityTracker(
            // A52 (finding 3): stamped once per process at the root, so a
            // cold start can tell receipts from before this process (stale,
            // stay hidden) from runs that finished live (fresh, show once).
            processStartMs = System.currentTimeMillis(),
        )
    }
    // Single AppGraph-owned instance — Task 3/4 background pumps and this
    // composable share the exact same durable queue instead of each holding
    // their own in-memory copy that could silently diverge.
    val cloudSyncQueue = appGraph.cloudSyncQueue
    val libraryLayoutStore = remember {
        LibraryLayoutStore(SharedPreferencesMap.from(context))
    }
    var viewMode by rememberSaveable { mutableStateOf(libraryLayoutStore.read()) }
    // Enable stores must exist before BrowsePrefsStore so its lazy lambdas
    // can read them on first read().
    val ehentaiEnableStore = remember { EhentaiEnableStore(SharedPreferencesMap.from(context)) }
    var ehentaiEnabled by remember { mutableStateOf(ehentaiEnableStore.isEnabled()) }
    val ehentaiAccountStore = remember { EhentaiAccountStore(SharedPreferencesMap.from(context)) }
    var ehentaiAccount by remember { mutableStateOf(ehentaiAccountStore.read()) }
    val zaimanhuaAccountStore = remember { appGraph.zaimanhuaAccountStore }
    var zaimanhuaAccount by remember { mutableStateOf(zaimanhuaAccountStore.read()) }
    var zaimanhuaAccountChangeEpoch by remember { mutableIntStateOf(0) }
    val currentZaimanhuaAccount by rememberUpdatedState(zaimanhuaAccount)
    val zaimanhuaExpiryText = remember(zaimanhuaAccount) {
        if (!zaimanhuaAccount.isSignedIn()) {
            ""
        } else {
            formatZaimanhuaExpiry(zaimanhuaAccount.expSeconds)
        }
    }
    val zaimanhuaSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.getStringExtra(ZaimanhuaSignInActivity.SUCCESS_MESSAGE_EXTRA)?.let { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val launchZaimanhuaSignIn: () -> Unit = {
        zaimanhuaSignInLauncher.launch(Intent(context, ZaimanhuaSignInActivity::class.java))
    }
    fun updateZaimanhuaAccountIfChanged(fresh: ZaimanhuaAccount) {
        if (fresh != currentZaimanhuaAccount) {
            zaimanhuaAccount = fresh
            zaimanhuaAccountChangeEpoch += 1
        }
    }
    // W7: ExHentai's own session-gated enable. The row (Settings) and registry
    // entry (Browse) both read this state; the sign-in activity persists the
    // session + verdict, so coming back from sign-in must re-read both stores.
    val exhentaiEnableStore = remember {
        ExhentaiEnableStore(SharedPreferencesMap.from(context), ehentaiAccountStore)
    }
    var exhentaiEnabled by remember { mutableStateOf(exhentaiEnableStore.isEnabled()) }
    val exhentaiFavoritesSyncStore = remember {
        ExhentaiFavoritesSyncStore(SharedPreferencesMap.from(context), ehentaiAccountStore)
    }
    var exhentaiFavoritesSyncEnabled by remember { mutableStateOf(exhentaiFavoritesSyncStore.isEnabled()) }
    var exhentaiFavoritesImporting by remember { mutableStateOf(false) }
    var exhentaiFavoritesStatusText by rememberSaveable { mutableStateOf<String?>(null) }
    val typesafeApiKeyStore = remember { appGraph.typesafeApiKeyStore }
    var typesafeApiKey by remember { mutableStateOf(typesafeApiKeyStore.read()) }
    val nhentaiEnableStore = remember { NhentaiEnableStore(SharedPreferencesMap.from(context)) }
    val jmEnableStore = remember { JmEnableStore(SharedPreferencesMap.from(context)) }
    var jmEnabled by remember { mutableStateOf(jmEnableStore.isEnabled()) }
    val languageStore = remember { AppLanguageStore(SharedPreferencesMap.from(context)) }
    var languagePreference by remember { mutableStateOf(AppI18n.applyStoredPreference(languageStore)) }
    // #E1: daily update check on resume. The dialog shows once per version; the More row stays until the user updates.
    val updateStore = remember { UpdatePrefsStore(SharedPreferencesMap.from(context)) }
    val updateChecker = remember {
        UpdateChecker(
            updateStore,
            BuildConfig.VERSION_CODE,
            releaseUpdatesInstallable = !BuildConfig.DEBUG || updateStore.debugOffersAllowed(),
            fetch = { UpdateCheckClient().fetch() },
        )
    }
    var updateOffer by remember { mutableStateOf<UpdateOffer?>(null) }
    var updateCheckEnabled by remember { mutableStateOf(updateStore.isEnabled()) }
    var updateCheckEpoch by remember { mutableIntStateOf(0) }
    var updateDialogPhase by remember { mutableStateOf<AppUpdateDialogPhase>(AppUpdateDialogPhase.Ready()) }
    var updateDownloadJob by remember { mutableStateOf<Job?>(null) }
    var waitingPermissionApkFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { AppUpdateInstaller.sweepUpdateApks(context, BuildConfig.VERSION_CODE) }
        }
    }
    LaunchedEffect(updateCheckEpoch, updateCheckEnabled) {
        val pendingFile = waitingPermissionApkFile
        if (pendingFile != null) {
            if (AppUpdateInstaller.canRequestPackageInstalls(context)) {
                waitingPermissionApkFile = null
                updateDialogPhase = AppUpdateDialogPhase.Ready(alreadyDownloaded = true)
                runCatching { AppUpdateInstaller.installUpdateApk(context, pendingFile) }
                    .onFailure {
                        updateDialogPhase = AppUpdateDialogPhase.Error(
                            AppUpdateDialogPhase.Error.Reason.DOWNLOAD_FAILED,
                        )
                    }
            }
            return@LaunchedEffect
        }
        val checked = withContext(Dispatchers.IO) { updateChecker.check(System.currentTimeMillis()) }
        val isPipelineBusy = updateDialogPhase is AppUpdateDialogPhase.Downloading ||
            updateDialogPhase is AppUpdateDialogPhase.Verifying ||
            updateDialogPhase is AppUpdateDialogPhase.PermissionRequired
        if (!isPipelineBusy) {
            val cachedFile = checked?.release?.let { rel ->
                withContext(Dispatchers.IO) {
                    runCatching { AppUpdateInstaller.hasVerifiedCachedApk(context, rel) }.getOrNull()
                }
            }
            updateDialogPhase = AppUpdateDialogPhase.Ready(alreadyDownloaded = cachedFile != null)
        }
        updateOffer = checked
    }
    var nhentaiEnabled by remember { mutableStateOf(nhentaiEnableStore.isEnabled()) }
    // #C15 audit HIGH-2: bumped on ON_RESUME so Browse re-probes after a
    // pause/resume (new network), not only on route entry.
    var browseProbeEpoch by remember { mutableIntStateOf(0) }
    // 2026-09-19 spec §5: the OPDS providers whose Browse-entry probe is in
    // flight right now — their rows read `Checking…`. Owned by the probe
    // effect below; declared here so the ON_RESUME observer can read it.
    var browseCheckingProviderIds by remember { mutableStateOf(emptySet<String>()) }
    // #C15 r3 item 1: ownership token for the checking set — a probe's late finally may only clear the set it filled.
    var browseProbeGeneration by remember { mutableIntStateOf(0) }
    // W7: the sign-in activity writes session + verdict from its own task
    // stack; re-read on every Settings resume so the sibling row reflects
    // reality immediately (the row otherwise stays stale until process death).
    val settingsLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(settingsLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ehentaiAccount = ehentaiAccountStore.read()
                updateZaimanhuaAccountIfChanged(zaimanhuaAccountStore.read())
                exhentaiEnabled = exhentaiEnableStore.isEnabled()
                exhentaiFavoritesSyncEnabled = exhentaiFavoritesSyncStore.isEnabled()
                jmEnabled = jmEnableStore.isEnabled()
                // #C15 audit HIGH-2: bump so the Browse probe effect (keyed
                // on browseProbeEpoch too) re-fires on every resume, not
                // only on route entry — catches a network change while the
                // process survives in the background — but never while a
                // probe is already running: re-keying would cancel it, orphan
                // its blocked connect for the whole timeout and, on a
                // black-hole network, starve the verdict for a user who keeps
                // returning to the app (#C15 re-review item 5). The running
                // probe delivers; the next resume after it re-probes.
                if (browseCheckingProviderIds.isEmpty()) browseProbeEpoch += 1
                updateCheckEpoch += 1
            }
        }
        settingsLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { settingsLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val browsePrefsStore = remember {
        BrowsePrefsStore(
            SharedPreferencesMap.from(context),
            ehentaiEnabled = { ehentaiEnableStore.isEnabled() },
            exhentaiEnabled = { exhentaiEnableStore.isEnabled() },
            nhentaiEnabled = { nhentaiEnableStore.isEnabled() },
            zaimanhuaEnabled = { ZAIMANHUA_LISTED_IN_BROWSE },
            jmEnabled = { jmEnableStore.isEnabled() },
        )
    }
    var browsePrefs by remember { mutableStateOf(browsePrefsStore.read()) }
    val browseFilterStore = remember { PreferenceMapSourceFilterStore(SharedPreferencesMap.from(context)) }
    // The whole per-source filter map lives in memory here; persistence happens
    // only on sheet apply. Edits then hit the repository the very next
    // composition frame — and the key below keeps the reload on the same frame.
    var browseFilterStates by remember { mutableStateOf(emptyMap<String, SourceFilterState>()) }
    LaunchedEffect(Unit) {
        // Merge the startup source without erasing a source primed by an interactive handler.
        val sourceId = browsePrefs.sourceId
        val saved = browseFilterStore.get(sourceId)
        if (browseFilterStates[sourceId] == null) {
            browseFilterStates = browseFilterStates + (sourceId to saved)
        }
    }
    var browseQuery by rememberSaveable { mutableStateOf("") }
    var jmSearchSession by remember { mutableStateOf(JmSearchSession()) }
    var browsePrefsOpen by rememberSaveable { mutableStateOf(false) }
    val browseGenres by appGraph.zaimanhuaGenres.choices.collectAsStateWithLifecycle()
    LaunchedEffect(browsePrefsOpen, browsePrefs.sourceId) {
        if (browsePrefsOpen && browsePrefs.sourceId == GalleryIdentityV1.ZAIMANHUA) {
            withContext(Dispatchers.IO) { appGraph.zaimanhuaGenres.refreshIfDue() }
        }
    }
    LaunchedEffect(browsePrefs.sourceId) {
        val sourceId = browsePrefs.sourceId
        if (browseFilterStates[sourceId] == null) {
            val saved = browseFilterStore.get(sourceId)
            // An interactive genre selection published during the read wins.
            if (browseFilterStates[sourceId] == null) {
                browseFilterStates = browseFilterStates + (sourceId to saved)
            }
        }
    }
    val opdsProviderStore = remember { OpdsProviderStore(SharedPreferencesMap.from(context)) }
    var opdsProviders by remember { mutableStateOf(opdsProviderStore.readAll()) }
    // Accountless cloud: there is no sign-in and no session. Cloud availability
    // is purely "is a bucket bound on this device" — tracked by
    // bridgeR2BucketLabel. Nothing here gates the local library, Browse, or
    // reading. Libraries are plaintext by default; an opted-in library's
    // optional DEK lives in appGraph.libraryDekStore, behind
    // appGraph.cloudLibraryGate — this device state doesn't hold it.
    val bridgeDeviceR2SecretStore = remember {
        BridgeDeviceR2SecretStore(SharedPreferencesMap.from(context))
    }
    val bridgeOAuthDeviceSecretStore = remember {
        BridgeOAuthDeviceSecretStore(SharedPreferencesMap.from(context))
    }
    val bridgeOAuthPkceSessionStore = remember {
        BridgeOAuthPkceSessionStore(SharedPreferencesMap.from(context))
    }
    val cloudflarePkce = remember {
        CloudflarePkceClient(clientId = CloudflareOAuthConfig.CLIENT_ID)
    }
    // CloudProcessSession is the single source of truth (survives Activity
    // recreation); observe it directly instead of mirroring it into a
    // compose-local var that could desync from the session's copy.
    val oauthRestCache by cloudProcess.oauthRestCacheFlow.collectAsStateWithLifecycle()
    var libraryCloudItems by remember { mutableStateOf(cloudProcess.libraryCloudItems) }
    val bridgeR2BindPresenter = remember {
        BridgeR2BindPresenter(
            bindingCache = appGraph.r2BindingCache,
            deviceStore = bridgeDeviceR2SecretStore,
            oauthStore = bridgeOAuthDeviceSecretStore,
            webDavStore = appGraph.bridgeDeviceWebDavStore,
            oneDriveStore = appGraph.bridgeDeviceOneDriveStore,
            googleDriveStore = appGraph.bridgeDeviceGoogleDriveStore,
            pkceSessionStore = bridgeOAuthPkceSessionStore,
            pkce = cloudflarePkce,
            r2Admin = CloudflareR2AdminClient(),
            openAuthorization = { uri ->
                CloudflareAuthorizeOpener.open(
                    uri = uri,
                    launchCustomTab = {
                        CustomTabsIntent.Builder().build().launchUrl(context, it.toUri())
                    },
                    launchView = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, it.toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    onUnavailable = {
                        android.widget.Toast.makeText(
                            context,
                            "No browser on this device can open Cloudflare.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            },
            clientId = CloudflareOAuthConfig.CLIENT_ID,
            bindingMutationGate = appGraph.cloudBindingMutationGate,
            onBindSwitching = { appGraph.cloudLibraryGate.beginSwitch() },
            onBindSwitchAborted = { appGraph.cloudLibraryGate.finishSwitch(null) },
            onBindReplaced = { dek ->
                // A rebind targets a different bucket — the old bucket's DEK
                // and lock-gate memo must not leak onto the new one. #C57: an
                // encrypted empty-bucket bind hands its new DEK over here, and
                // applyBindCommit unlocks the gate BEFORE the notify below
                // wakes any uploader.
                // C37-c review (F3) + Task 5 re-review (A1): a (re)bind
                // replaces the grant, so the process-wide token source must
                // drop its cached access token and dead-grant latch — via
                // onBucketBound(resetTokenSource = true), NOT from here. This
                // lambda runs on Main (the paste-S3 onSave path), and reset()
                // takes the same monitor an in-flight token exchange holds for
                // 0.5-8 s. The unlock caller below passes no flag: same grant.
                //
                // Third pump kick (loyal-pipelines Slice B pump task §2): wipes
                // any OTHER bucket's stale mirror rows (a same-bucket rebind is
                // a no-op wipe) and kicks HydrationWorker to fill/refresh the
                // shelf's local mirror.
                applyBindCommit(dek, appGraph.libraryDekStore, appGraph.cloudLibraryGate) {
                    appGraph.notifyBucketBound(resetTokenSource = true)
                }
            },
            locationHint = {
                val telephony = context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                R2LocationHint.fromDevice(
                    timeZoneId = java.util.TimeZone.getDefault().id,
                    networkCountryIso = telephony?.networkCountryIso,
                    simCountryIso = telephony?.simCountryIso,
                )
            },
        )
    }
    // #C50: the WebDAV bind flow; it commits through bridgeR2BindPresenter (one committer for every kind).
    val webDavBindPresenter = remember {
        // #D16: the phone's own library key guards the renew path (only this library can be renewed into).
        WebDavBindPresenter(commit = bridgeR2BindPresenter::commitWebDav, deviceDek = { appGraph.libraryDekStore.load() })
    }
    val customTabOpener: (String) -> Unit = { uri ->
        CloudflareAuthorizeOpener.open(
            uri = uri,
            launchCustomTab = { CustomTabsIntent.Builder().build().launchUrl(context, it.toUri()) },
            launchView = { context.startActivity(Intent(Intent.ACTION_VIEW, it.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            onUnavailable = {
                android.widget.Toast.makeText(context, "No browser on this device can open the sign-in page.", android.widget.Toast.LENGTH_LONG).show()
            },
        )
    }
    val googleDriveBindPresenter = remember {
        OAuthDriveBindPresenter(
            OAuthDrives.googleDrive(
                commit = { binding, dek ->
                    bridgeR2BindPresenter.commitGoogleDrive(binding, dek)
                    appGraph.googleDriveTokenSource?.reset()
                },
                renewSignIn = { refreshToken, _ ->
                    bridgeR2BindPresenter.renewGoogleDriveSignIn(refreshToken)
                    appGraph.googleDriveTokenSource?.reset()
                },
            ),
            openAuthorization = customTabOpener,
        )
    }
    val oneDriveBindPresenter = remember {
        OAuthDriveBindPresenter(
            OAuthDrives.oneDrive(
                commit = { binding, dek ->
                    bridgeR2BindPresenter.commitOneDrive(binding, dek)
                    // A new grant: the process-wide token owner must start from the new refresh token.
                    appGraph.oneDriveTokenSource?.reset()
                },
                renewSignIn = { refreshToken, _ ->
                    bridgeR2BindPresenter.renewOneDriveSignIn(refreshToken)
                    appGraph.oneDriveTokenSource?.reset()
                },
            ),
            openAuthorization = customTabOpener,
        )
    }
    var bridgeR2BucketLabel by remember { mutableStateOf(cloudProcess.bridgeR2BucketLabel) }
    var bridgeR2UnlockedEpoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (!AppLiveDeviceBindings.shouldRunCloudIo(bindLiveDeviceSession)) {
            return@LaunchedEffect
        }
        bridgeR2BindPresenter.refreshBoundLabel()
        bridgeR2BucketLabel = bridgeR2BindPresenter.state.boundBucketLabel
        bridgeR2UnlockedEpoch += 1
    }
    var browseResults by remember { mutableStateOf<List<BrowseFeedItem>>(emptyList()) }
    var browseFeedNotice by remember { mutableStateOf<BrowseFeedNotice?>(null) }
    var browseLoading by remember { mutableStateOf(false) }
    var browseLoadingMore by remember { mutableStateOf(false) }
    var browseNextContinuation by remember { mutableStateOf<String?>(null) }
    var browseKnownTotalItems by remember { mutableStateOf<Int?>(null) }
    var browseLoadMoreError by remember { mutableStateOf<String?>(null) }
    var browseError by remember { mutableStateOf<String?>(null) }
    var browseSessionArmed by remember { mutableStateOf(false) }
    var browseLoadedKey by remember { mutableStateOf<BrowseFeedSessionKey?>(null) }
    var browseFeedGeneration by remember { mutableIntStateOf(0) }
    var browseRecoveryEpoch by remember { mutableIntStateOf(0) }
    val browseListState = rememberLazyListState()
    // Metadata searches are transient surfaces above their originating detail.
    // Their state is intentionally process-local and never written to the
    // persisted Browse stores.
    var tagSearchStack by remember { mutableStateOf(TagSearchStack()) }
    var transientBrowseSession by remember { mutableStateOf<TransientBrowseSession?>(null) }
    var transientBrowseFilterOpen by remember { mutableStateOf(false) }
    val tagSearchStateHolder = rememberSaveableStateHolder()
    var serializedReaderOriginLayerToken by remember { mutableStateOf<String?>(null) }
    val transientBrowseListState = rememberLazyListState()
    // #12: OPDS galleries are OPDS navigation feeds whose covers are series
    // subsections, so tapping one changes browseFeedUrl and the feed reload
    // effect below runs scrollToItem(0) — losing the gallery's scroll on the way
    // back. Remember each feed's scroll position keyed by its session identity so
    // returning restores it (EH/nhentai never hit this because their detail is an
    // overlay that doesn't change the session key).
    val browseScrollMemory = remember { mutableMapOf<BrowseFeedSessionKey, Pair<Int, Int>>() }
    // A56: Library/History tab scroll positions survive route changes only if
    // their lazy-list/grid states live HERE — the root's `when (route)`
    // disposes a tab's whole branch on navigation, so screen-local states
    // died with it and Back meant the top (Browse already survives this way
    // via browseListState above). The two Library layouts keep separate
    // states, so a view-mode switch preserves each one's own position.
    val libraryGridState = rememberLazyGridState()
    val libraryListState = rememberLazyListState()
    val historyListState = rememberLazyListState()
    var libraryCloudTrashCards by remember { mutableStateOf(cloudProcess.libraryCloudTrashCards) }
    var libraryRecycleItems by remember { mutableStateOf(cloudProcess.libraryRecycleItems) }
    // #D8b review: the bucket label the trash listing last answered for. The bound page's
    // "trash" number is a placeholder until it matches the bound label — an empty list
    // before the first listing is not "0 deleted".
    var libraryRecycleListedFor by remember { mutableStateOf<String?>(null) }
    // A16: cloudComicIds with an in-flight Restore/Delete-forever so the row's
    // buttons disable instead of letting repeated taps queue duplicate work.
    var restoringCloudIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var libraryCloudReady by remember { mutableStateOf(cloudProcess.libraryCloudReady) }
    var libraryCloudBlocked by remember { mutableStateOf(cloudProcess.libraryCloudBlocked) }
    var libraryCloudSignInRejected by remember { mutableStateOf(cloudProcess.libraryCloudSignInRejected) }
    var libraryCloudEpoch by remember { mutableIntStateOf(0) }
    // Owner bug #3 fix: bumped once per accepted network reconnect
    // (CloudConnectivityReKick.connectivityEpoch) so the cloud-readiness
    // grace loop below re-arms on reconnect instead of leaving a stale
    // Unreachable banner up until some unrelated recomposition re-runs it.
    val connectivityEpoch by appGraph.cloudConnectivityReKick.connectivityEpoch.collectAsStateWithLifecycle()
    // Narrowly-scoped readiness signal for the catch-up sweep ONLY (owner
    // bug #3 fix, task E): true only once the grace loop below has actually
    // observed GraceState.Ready. Deliberately NOT folded into
    // libraryCloudReady itself — that flag is the Slice B "local-first
    // mirror shelf" signal (purely `fp != null`, see the
    // LaunchedEffect(cloudBucketFingerprint) below) and many other UI sites
    // read it for that no-cold-start-gap contract; redefining it to also
    // require a confirmed network probe would regress all of them. This
    // flag instead gates only the catch-up sweep, which is genuinely unsafe
    // to race ahead of a confirmed-reachable bucket.
    var cloudProbeReady by remember { mutableStateOf(false) }
    // Spec-review fix (correction #3): the identity (bucket/oauth/
    // connectivity, deliberately excluding libraryCloudEpoch) the grace
    // loop below last confirmed GraceState.Ready against. Lets a re-run of
    // that LaunchedEffect triggered only by a libraryCloudEpoch bump (e.g.
    // Restore/Discard in Recently deleted) skip the validated-network gate
    // + ping + backoff and go straight to listing — see shouldReprobe.
    var lastReadyProbeKeys by remember { mutableStateOf<CloudProbeKeys?>(null) }
    /** Raw mirror rows behind [libraryCloudItems] (loyal-pipelines Slice B §2, B4a). */
    // C29 audit MEDIUM-1: seeded from the process session, like
    // libraryCloudItems above — an Activity recreation must not blank the
    // shelf while Room's observeActiveCards flow re-subscribes.
    var libraryCloudCards by remember { mutableStateOf(cloudProcess.libraryCloudCards) }
    /** cloudComicId -> local thumb file URI, filled in progressively by the background thumb-fill effect. */
    var libraryCloudCoverUrls by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var libraryOpdsShelfItems by remember { mutableStateOf<List<BrowseFeedItem>>(emptyList()) }
    var libraryOpdsShelfLoading by remember { mutableStateOf(false) }
    var libraryOpdsShelfError by remember { mutableStateOf<String?>(null) }
    var libraryOpdsProviderId by remember { mutableStateOf<String?>(null) }
    var libraryOpdsLoadGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(
        oauthRestCache,
        libraryCloudItems,
        libraryCloudCards,
        libraryCloudTrashCards,
        libraryRecycleItems,
        libraryCloudReady,
        libraryCloudBlocked,
        libraryCloudSignInRejected,
        bridgeR2BucketLabel,
    ) {
        if (!AppLiveDeviceBindings.shouldRunCloudIo(bindLiveDeviceSession)) {
            return@LaunchedEffect
        }
        // oauthRestCache is no longer copied here — it now reads/writes
        // straight through to cloudProcess via oauthRestCacheFlow, so this
        // effect no longer needs to mirror it back.
        cloudProcess.libraryCloudItems = libraryCloudItems
        // C29 review MEDIUM-4: never write a stale card list back over a
        // Disconnect reset. libraryCloudReady IS `fp != null` (and is one of this
        // effect's keys), so it is the in-scope spelling of "a bucket is bound".
        cloudProcess.libraryCloudCards = if (libraryCloudReady) libraryCloudCards else emptyList()
        cloudProcess.libraryCloudTrashCards = libraryCloudTrashCards
        cloudProcess.libraryRecycleItems = libraryRecycleItems
        cloudProcess.libraryCloudReady = libraryCloudReady
        cloudProcess.libraryCloudBlocked = libraryCloudBlocked
        cloudProcess.libraryCloudSignInRejected = libraryCloudSignInRejected
        cloudProcess.bridgeR2BucketLabel = bridgeR2BucketLabel
    }
    var collectBookmarkEpoch by remember { mutableIntStateOf(0) }
    // #C56: a hydration completion may have adopted new bookmarks into the
    // collection off the UI thread (AppGraph.syncLedgerWithMirror) — bump the
    // same epoch the shelf's bookmark list is keyed on so it shows up without
    // a restart. drop(1): the flow's own initial value is not a new adoption.
    LaunchedEffect(Unit) {
        appGraph.collectionAdoptionEpoch.drop(1).collect { collectBookmarkEpoch += 1 }
    }
    val newChapterMarkChanges by appGraph.collectedSeriesNewChapterStore.changes
        .collectAsStateWithLifecycle()
    var browseFeedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var browseFeedProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    // C26 audit MEDIUM-1: every navigation that re-keys the feed must disown the
    // outgoing feed SYNCHRONOUSLY. The reload LaunchedEffect bumps the same
    // generation, but only after composition — and a load-more continuation
    // queued on AndroidUiDispatcher drains in the trampoline phase BEFORE that
    // frame, so the feed the user just left could still merge its page N+1 (and
    // its continuation/total) into the feed now on screen. This bump is what
    // makes BrowseFeedSession.shouldApplyLoadMoreResult reject that late page;
    // the load-more flags go with it because the rejected coroutine
    // `return@launch`es ABOVE its own `browseLoadingMore = false`.
    //
    // This half is safe ANYWHERE, including where the new key turns out equal:
    // it never empties the list. Clearing without a guaranteed re-key is what
    // resetBrowseFeed() below is for — see its comment.
    fun invalidateBrowseFeed() {
        // Never bump while a feed load is in flight: that load bumped the
        // generation when it started, so any load-more still alive is already
        // rejected (loadMoreBrowse() cannot start one while browseLoading), and
        // a second bump would make its finally fail shouldReleaseLoading and
        // skip the `browseLoading = false` release — a permanent skeleton at
        // every site whose key can come out equal.
        if (!browseLoading) browseFeedGeneration += 1
        browseLoadingMore = false
        browseLoadMoreError = null
    }

    // The destructive half (2026-09-21 device send-back / review BLOCKING-1):
    // ONLY for navigations where a re-key is GUARANTEED. The reload effect is
    // keyed on browseSessionKey, so a clear whose key comes out equal is never
    // reloaded — "Back to sources" then reselecting the same source left a
    // permanently empty list with no skeleton, no error and no retry.
    fun resetBrowseFeed() {
        invalidateBrowseFeed()
        browseLoadedKey = null
        browseResults = emptyList()
        browseError = null
        browseNextContinuation = null
        browseKnownTotalItems = null
        browseFeedNotice = null
    }

    var remoteDetailItem by remember { mutableStateOf<BrowseFeedItem?>(null) }
    // A saved serialized chapter reader has no durable navigation stack. Keep
    // the tab underneath its detail only for this process; after process death
    // the reader uses the Library fallback.
    var serializedReaderOriginRoute by remember { mutableStateOf<AppRoute?>(null) }
    var pendingSerializedConfirmation by remember {
        mutableStateOf<PendingConfirmation?>(null)
    }
    // C34-c: the CloudCollectionStore the OPEN cloud detail resolved for its
    // previews. Its first thumb already paid for `pages.manifest.json`, and
    // that memo is per instance — handing this one to the reader is what
    // saves the reader's duplicate manifest GET on the first screen.
    var remoteDetailCloudStore by remember { mutableStateOf<CloudCollectionStore?>(null) }
    // C34 audit (S4): one structural guard for the seven places that can null
    // `remoteDetailItem`. Whichever of them closed the detail, the retained
    // store — and the `pages.manifest.json` memo map it holds — is dropped
    // here, so a closed detail never keeps a bucket-bound instance alive.
    // The explicit clears at the open/close sites stay: they order the drop
    // BEFORE the next detail resolves its own store.
    LaunchedEffect(remoteDetailItem == null) {
        if (remoteDetailItem == null) remoteDetailCloudStore = null
    }
    var remoteDetailBusy by remember { mutableStateOf(false) }
    var remoteDetailStatus by remember { mutableStateOf<String?>(null) }
    var remoteDetailMetadataLoading by remember { mutableStateOf(false) }
    // K2: which comic [remoteDetailStatus] is about, or null for a line that
    // belongs to no particular comic. Collect/uncollect announce asynchronously,
    // so an unattributed outcome would otherwise be mirrored onto whichever
    // Local Detail happens to be open when it lands.
    var remoteDetailStatusAcquisition by remember { mutableStateOf<String?>(null) }

    /**
     * The ONE writer of [remoteDetailStatus]. Setting the message and its owning
     * acquisition together is what stops an unstamped late failure (a discard,
     * a restore, an online-read error) inheriting the stamp of whatever was
     * announced before it. Everything that is not collect/uncollect passes no
     * acquisition, which is the honest answer: that line is about a surface,
     * not about a comic some other screen is waiting to hear about.
     */
    fun sayRemote(message: String?, acquisition: String? = null) {
        remoteDetailStatus = message
        remoteDetailStatusAcquisition = if (message == null) null else acquisition
    }
    var remoteDetailSourceFamily by remember {
        mutableStateOf(DetailSourceFamily.FRONT_INDEX)
    }
    /** Origin acquisition for Cloud Collection cards (never a cloudComicId identity). */
    var remoteDetailOriginAcquisition by remember { mutableStateOf<String?>(null) }
    var remoteDetailOriginProviderId by remember { mutableStateOf<String?>(null) }
    var remoteDetailChapters by remember { mutableStateOf<ChapterListResult?>(null) }
    var remoteDetailSnapshot by remember { mutableStateOf<SeriesDetailSnapshot?>(null) }
    var remoteDetailOpenGeneration by remember { mutableLongStateOf(0L) }
    var remoteDetailCloudChapterRows by remember { mutableStateOf<List<CloudChapterRow>>(emptyList()) }
    var remoteDetailChaptersUnavailable by remember { mutableStateOf(false) }
    // #E21 tail 3: one line shown in place of a cloud series' chapter list
    // while its summary has never hydrated (series.json missing, no mirror).
    var remoteDetailSeriesEmptyLine by remember { mutableStateOf<String?>(null) }
    var cloudPreviewInitialBusy by remember { mutableStateOf(false) }
    var frontPreviewInitialBusy by remember { mutableStateOf(false) }
    var opdsPreviewInitialBusy by remember { mutableStateOf(false) }
    var onlineReaderSession by remember { mutableStateOf<OnlineReaderSession?>(null) }
    var cloudReaderSession by remember { mutableStateOf<CloudReaderSession?>(null) }
    var cloudReaderSeriesManifest by remember { mutableStateOf<CloudSeriesManifest?>(null) }
    LaunchedEffect(openLibraryUpdates) {
        if (!openLibraryUpdates) return@LaunchedEffect
        updatesSelected = true
        route = AppRoute.Library
        remoteDetailItem = null
        sayRemote(null)
        onlineReaderSession = null
        cloudReaderSession = null
        onLibraryUpdatesIntentConsumed()
    }
    // A42 remainder: device-local remote-read progress (RemoteReadingProgressStore
    // — the source of truth when a tile re-opens a read) and the recorder the
    // open reader sessions bind to onProgressSettled. The recorder is built
    // from the item the session was opened FOR (readOnline/awaitCloudCollection
    // Reader set it just before installing the session), so the write key
    // matches the re-open key even when Detail remapped the acquisition.
    val remoteProgressStore = remember { appGraph.remoteReadingProgressStore }
    fun cloudChapterResolver(manifest: CloudSeriesManifest): CloudReaderChapterResolver =
        CloudReaderChapterResolver.fromManifest(manifest) { series, chapterId ->
            remoteProgressStore.get(RemoteProgressPolicy.keyForChapter(series, chapterId))
        }
    var remoteReadProgressItem by remember { mutableStateOf<BrowseFeedItem?>(null) }
    var remoteReadProgressChapter by remember { mutableStateOf<ChapterIdentityV2?>(null) }
    // A42 remainder: the snapshot the shelf renders bars from. Re-read on every
    // remote-reader close (the moments a read's final position just landed);
    // a Compose State read inside the settle write would be the cleaner
    // observer but the store is deliberately not observable (small-store
    // convention) — an epoch keyed to reader-open/close is enough for the
    // shelf, which only renders while no reader is open.
    var remoteProgressEpoch by remember { mutableIntStateOf(0) }
    val remoteProgressMap = remember(remoteProgressEpoch) {
        remoteProgressStore.all()
    }
    DisposableEffect(onlineReaderSession) {
        val session = onlineReaderSession
        onDispose { session?.onClose?.invoke() }
    }
    LaunchedEffect(onlineReaderSession, cloudReaderSession) {
        // A reader just opened (progress map already fresh from its own open)
        // or closed — re-read the store so a settled final page shows on the
        // tile immediately when the shelf returns.
        remoteProgressEpoch += 1
    }
    // A17/E11: one lazy gallery-plan memo per acquisition so Detail, preview
    // warm-through, and Read share the page-0 metadata and any resolved grids.
    // computeIfAbsent makes page-0 construction single-flight across a Detail
    // open and a Read tapped before that open finishes.
    val nhentaiPlanCache = remember { ConcurrentHashMap<Long, NhentaiGalleryPlan>() }
    val ehentaiLazyPlanCache = remember { ConcurrentHashMap<Long, EhentaiLazyGalleryPlan>() }
    fun nhentaiPlanFor(
        indexId: Long,
        beforeRequest: () -> Unit = { CatalogRequestGate.Interactive.acquire() },
    ): NhentaiGalleryPlan {
        nhentaiPlanCache[indexId]?.let { return it }
        val plan = NhentaiClient(
            beforeRequest = beforeRequest,
        ).fetchGallery(indexId)
        nhentaiPlanCache.putIfAbsent(indexId, plan)
        return nhentaiPlanCache[indexId] ?: plan
    }
    fun ehentaiLazyPlanFor(
        galleryId: Long,
        token: String,
        beforeRequest: () -> Unit,
    ): EhentaiLazyGalleryPlan = ehentaiLazyPlanCache.computeIfAbsent(galleryId) {
        EhentaiLazyGalleryPlan(
            firstPageClient = EhentaiClient.fromAccount(
                ehentaiAccountStore.read(),
                beforeRequest = beforeRequest,
            ),
            galleryId = galleryId,
            token = token,
        )
    }
    // A13: permanent, low-noise nav diagnostics — correlate an unexplained
    // navigation with logcat without changing any behavior.
    LaunchedEffect(route) { Log.i("NAV", "route=$route") }
    LaunchedEffect(remoteDetailItem?.providerId, remoteDetailItem?.id) { Log.i("NAV", "remoteDetailItem.id=${redactAcquisition(remoteDetailItem?.id)}") }
    LaunchedEffect(cloudReaderSession != null) { Log.i("NAV", "cloudReaderSession!=null=${cloudReaderSession != null}") }
    LaunchedEffect(onlineReaderSession != null) { Log.i("NAV", "onlineReaderSession!=null=${onlineReaderSession != null}") }
    val preferenceMap = remember { SharedPreferencesMap.from(context) }
    val cloudReleaseAfterUploadStore = remember { CloudReleaseAfterUploadStore(preferenceMap) }
    var cloudReleaseAfterUploadEnabled by remember { mutableStateOf(cloudReleaseAfterUploadStore.read()) }
    var backgroundRefreshEnabled by remember { mutableStateOf(appGraph.backgroundRefreshEnabledStore.read()) }
    var backgroundWifiOnly by remember { mutableStateOf(appGraph.backgroundRefreshWifiOnlyStore.read()) }
    var backgroundHintEpoch by remember { mutableIntStateOf(0) }
    var cloudReleaseCatchUpPrompt by remember { mutableStateOf(false) }
    var cloudReleaseCatchUpSummary by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(bridgeR2BucketLabel, cloudReleaseAfterUploadEnabled) {
        cloudReleaseCatchUpPrompt =
            bridgeR2BucketLabel != null &&
                cloudReleaseAfterUploadEnabled &&
                !cloudReleaseAfterUploadStore.readCatchUpAsked()
    }
    var cacheBudgetMb by remember { mutableIntStateOf(appGraph.cacheBudgetStore.readMb()) }
    var cacheUsageBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(route) {
        if (route == AppRoute.Settings) {
            // C28 audit HIGH-2: a thrown LaunchedEffect body takes the process
            // down; `cacheBudget` is `by lazy` so a thrown initializer is not
            // memoised and re-throws on every open — one unreadable cache root
            // turned "open More" into a permanent crash loop. Review LOW-5:
            // `null` is the screen's "Measuring…" state, so a contained failure
            // reports 0 rather than measuring for ever.
            cacheUsageBytes = withContext(Dispatchers.IO) { runCatching { appGraph.cacheUsageBytes() }.getOrDefault(0L) }
        }
    }
    val readerKeepScreenOnStore = remember { ReaderKeepScreenOnStore(preferenceMap) }
    var keepScreenOnEnabled by remember { mutableStateOf(readerKeepScreenOnStore.read()) }
    // S6-A (owner setting "max concurrent transfers"): mirrors
    // AppGraph.maxConcurrentTransfersStore so the Settings screen reflects
    // the live value and a change takes effect on the pump's next run.
    var maxConcurrentTransfers by remember { mutableIntStateOf(appGraph.maxConcurrentTransfersStore.read()) }
    val readerVolumeKeyPagingStore = remember { ReaderVolumeKeyPagingStore(preferenceMap) }
    var volumeKeyPagingEnabled by remember { mutableStateOf(readerVolumeKeyPagingStore.read()) }
    val readerRtlPagingStore = remember { ReaderRtlPagingStore(preferenceMap) }
    var rtlPagingEnabled by remember { mutableStateOf(readerRtlPagingStore.read()) }
    val readerLayoutModeStore = remember { ReaderLayoutModeStore(preferenceMap) }
    var readerLayoutMode by remember { mutableStateOf(readerLayoutModeStore.read()) }
    val readerFitWidthStore = remember { ReaderFitWidthStore(preferenceMap) }
    // #D6: the Fit width switch is gone from Settings, so a value stored while it existed must not
    // strand a reader in fill-width mode with no way back. Always the fit-whole-page default.
    var fitWidthEnabled by remember { mutableStateOf(false) }
    val browseHistoryStore = remember { BrowseHistoryStore(preferenceMap) }
    val collectBookmarkStore = remember { CollectBookmarkStore(preferenceMap) }
    // Single AppGraph-owned instance — shared with the background CloudIngestRunner.
    val cloudComicIdStore = appGraph.cloudComicIdStore
    var historyEntries by remember { mutableStateOf(browseHistoryStore.read()) }
    var pendingLegacyBackupPassword by remember { mutableStateOf<String?>(null) }
    var legacyBackupResult by remember { mutableStateOf<String?>(null) }
    fun oauthRestStore(): ObjectStore? = appGraph.validCachedOauthStore()
    suspend fun refreshOauthRestStore() {
        // Single-flight NonCancellable bootstrap: cancelled LaunchedEffects must
        // not drop oauthRestCache after refresh-token rotation (ready-race).
        // Delegates to AppGraph.refreshOauthRestStore() — the ONE bootstrap
        // implementation, also used by the compose-free resolveGatedObjectStore()
        // background-pump path. oauthRestCache is session-backed now, so the
        // AppGraph defaults (which read/write cloudProcessSession directly)
        // are exactly what this composable wants too — no override needed;
        // LaunchedEffects keyed on oauthRestCache still recompose because
        // they observe the same session flow.
        val result = appGraph.refreshOauthRestStore()
        when (result) {
            is OauthRestStoreRefreshResult.Ready -> Unit
            // #D7: this used to write into the old backup screen's status line, the only place it
            // was ever shown; the refresh's own failure path already records it.
            is OauthRestStoreRefreshResult.Failed -> DiagLog.w("CloudSync", "oauth store refresh failed")
            OauthRestStoreRefreshResult.Skipped -> Unit
        }
    }
    /** Ungated store — the lock object itself must be reachable while LOCKED. */
    fun rawObjectStore(): ByoCloudIoResult =
        ByoCloudIo.resolve(
            working = appGraph.r2BindingCache.workingBinding(),
            storeFor = { binding -> R2ObjectStore(binding) },
            oauthStore = oauthRestStore(),
            webDavStore = appGraph.webDavObjectStoreOrNull(),
            oneDriveStore = appGraph.oneDriveObjectStoreOrNull(),
            googleDriveStore = appGraph.googleDriveObjectStoreOrNull(),
        )

    /** Content IO goes through the encryption gate; Blocked passes through untouched. */
    fun productObjectStore(): ByoCloudIoResult {
        // #C64: generation BEFORE the binding — a commit in between yields a dead wrapper.
        val born = appGraph.cloudLibraryGate.generation()
        return when (val io = rawObjectStore()) {
            is ByoCloudIoResult.Blocked -> io
            is ByoCloudIoResult.Ready -> ByoCloudIoResult.Ready(appGraph.cloudLibraryGate.wrap(io.store, born))
        }
    }
    // Declared after rawObjectStore() (local function; Kotlin resolves local
    // declarations in source order, so an earlier remember{} could not close
    // over it) — bridgeR2UnlockedEpoch is already in scope either way.
    val libraryLockPresenter = remember {
        LibraryLockPresenter(
            gate = appGraph.cloudLibraryGate,
            dekStore = appGraph.libraryDekStore,
            rawStore = {
                (rawObjectStore() as? ByoCloudIoResult.Ready)?.store
            },
            onLibraryUsable = {
                bridgeR2UnlockedEpoch += 1
                // Third pump kick (loyal-pipelines Slice B review H2): an
                // unlock is the only other place besides bind/cold-start that
                // can turn a Blocked hydration pump back into a runnable one
                // — without this, a bucket bound while still locked leaves
                // the pump permanently stranded until the process restarts.
                // wipeAllExcept(fp) is a no-op for the same bucket, so this
                // degenerates to a cheap generation-check kick when the
                // bucket was already (partially) hydrated before the lock.
                appGraph.notifyBucketBound()
            },
        )
    }
    LaunchedEffect(bridgeR2BucketLabel, bridgeR2UnlockedEpoch, oauthRestCache?.first) {
        if (!AppLiveDeviceBindings.shouldRunCloudIo(bindLiveDeviceSession)) {
            return@LaunchedEffect
        }
        val needsBootstrap = LibraryCloudReadiness.needsOauthRestBootstrap(
            hasOauthDeviceSecrets = bridgeOAuthDeviceSecretStore.load() != null,
            workingBindingPresent = appGraph.r2BindingCache.workingBinding() != null,
            oauthRestCacheReady = oauthRestStore() != null,
        )
        if (!needsBootstrap) return@LaunchedEffect
        refreshOauthRestStore()
    }
    SideEffect {
        appGraph.browseUiInputs = AppGraph.BrowseUiInputs(
            // A metadata-search layer owns its filter/session in memory. The
            // source adapters still receive only BrowsePrefs + query, so this
            // bridge must expose the layer's state while it is active; falling
            // back to the root map would silently turn scoped JM searches into
            // ordinary searches and drop a transient Zaimanhua genre filter.
            filterStateFor = { sourceId ->
                transientBrowseSession
                    ?.takeIf { it.prefs.sourceId == sourceId }
                    ?.filterState
                    ?: browseFilterStates[sourceId]
                    ?: SourceFilterState()
            },
            jmSearchScopeFor = { query ->
                val transient = transientBrowseSession
                val session = transient?.jmSearchSession ?: jmSearchSession
                val sourceId = transient?.prefs?.sourceId ?: browsePrefs.sourceId
                val sessionQuery = transient?.query ?: browseQuery
                session.scope.takeIf {
                    sourceId == JmEnableStore.SOURCE_ID &&
                        !session.awaitingSubmit && query == sessionQuery && query.isNotBlank()
                }
            },
        )
    }
    val browseFeedRepository = remember(
        opdsProviders,
        ehentaiEnabled,
        nhentaiEnabled,
        jmEnabled,
        exhentaiEnabled,
        ehentaiAccount,
        bridgeR2BucketLabel,
        bridgeR2UnlockedEpoch,
    ) {
        BrowseFeedRepository(
            sourceRegistry = appGraph.comicSourceRegistry,
        )
    }
    // Slimmed loader effect (loyal-pipelines Slice B §2, B4a): the active
    // shelf no longer reads through here at all — see the mirror-backed
    // LaunchedEffect(cloudBucketFingerprint) below. This effect keeps only
    // the blocked-state message (libraryCloudBlocked) and the trash/recycle
    // pass, which stays a live network read (trash/recycle mirroring is
    // explicitly out of scope for Slice B).
    //
    // Owner bug #3 fix: this used to fire the Ready-branch listing pass
    // immediately on every key change, so any transient blip (radio still
    // negotiating on cold start, a captive portal, a slow DNS resolve)
    // landed straight on the user as a permanent-looking "Cloud collection
    // could not be listed" banner with no retry. It now waits for the OS to
    // report a VALIDATED network, then runs a real measured R2 round-trip
    // through an adaptive grace loop (CloudProbeGrace) before ever touching
    // libraryCloudBlocked with anything other than null. Genuine fail-closed
    // errors (NewerCloudFormatException / UnreadableCloudLibraryException /
    // LibraryLockedException / DamagedLibraryLockException) are unaffected — they can only surface
    // once the grace loop has already confirmed the bucket is reachable and
    // the existing listing pass below actually runs.
    LaunchedEffect(
        bridgeR2BucketLabel,
        bridgeR2UnlockedEpoch,
        libraryCloudEpoch,
        oauthRestCache?.first,
        connectivityEpoch,
    ) {
        if (!AppLiveDeviceBindings.shouldRunCloudIo(bindLiveDeviceSession)) {
            return@LaunchedEffect
        }
        bridgeR2BindPresenter.refreshBoundLabel()
        bridgeR2BucketLabel = bridgeR2BindPresenter.state.boundBucketLabel

        // Extracted so both the fast path below (an epoch-only re-run that
        // skips re-probing) and the settled GraceState.Ready branch of the
        // full probe run the exact same listing logic against whichever
        // store turned out to be the right one.
        suspend fun listCloudTrash(store: ObjectStore) {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val cloudStore = CloudCollectionStore(store)
                    val now = System.currentTimeMillis()
                    cloudStore.purgeExpiredTrash(now)
                    val cards = cloudStore.listAllTrashCards()
                    val thumbDir = CloudThumbCache.root(context.cacheDir)
                    val coverUrls = cards.associate { card ->
                        card.cloudComicId to appGraph.downloadCloudCachedCover(cloudStore, card.cloudComicId, card.coverThumbObjectKey, CloudThumbCache.recycleCoverFile(thumbDir, card.cloudComicId))
                    }
                    val recycleItems = LibraryRecycleFeedMapper.mapCards(cards, now) { coverUrls[it.cloudComicId] }
                    cards to recycleItems
                }
                libraryCloudBlocked = null
                libraryCloudSignInRejected = false
                libraryCloudTrashCards = loaded.first
                libraryRecycleItems = loaded.second
                libraryRecycleListedFor = bridgeR2BucketLabel
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: NewerCloudFormatException) {
                libraryCloudBlocked = NewerCloudFormatException.MESSAGE
                libraryCloudTrashCards = emptyList()
                libraryRecycleItems = emptyList()
            } catch (_: UnreadableCloudLibraryException) {
                // Fail closed: the bucket holds objects this build cannot
                // read. Say so instead of a generic list failure.
                libraryCloudBlocked = UnreadableCloudLibraryException.MESSAGE
                libraryCloudTrashCards = emptyList()
                libraryRecycleItems = emptyList()
            } catch (_: LibraryLockedException) {
                // Same fail-closed contract: an encrypted library the app
                // hasn't unlocked yet is not a generic list failure.
                libraryCloudBlocked = LibraryLockedException.MESSAGE
                libraryCloudTrashCards = emptyList()
                libraryRecycleItems = emptyList()
            } catch (_: DamagedLibraryLockException) {
                // The keyslot itself is unreadable — distinct message,
                // same propagate-don't-flatten contract.
                libraryCloudBlocked = DamagedLibraryLockException.MESSAGE
                libraryCloudTrashCards = emptyList()
                libraryRecycleItems = emptyList()
            } catch (error: Exception) {
                // #D16: the fast path lists without re-probing, so a password refused since the last passing check
                // lands here — the verdict, not "could not be listed".
                val refused = CloudSignIn.rejected(error)
                libraryCloudSignInRejected = refused
                libraryCloudBlocked = if (refused) {
                    CloudSignInCopy.sentence(bridgeR2BindPresenter.state.boundKind)
                } else {
                    LibraryCloudSegmentCopy.LIST_FAILED
                }
                libraryCloudTrashCards = emptyList()
                libraryRecycleItems = emptyList()
            }
        }

        val currentProbeKeys = CloudProbeKeys(
            bucketLabel = bridgeR2BucketLabel,
            unlockedEpoch = bridgeR2UnlockedEpoch,
            oauthCacheKey = oauthRestCache?.first,
            connectivityEpoch = connectivityEpoch,
        )
        // Spec-review fix (correction #3): this LaunchedEffect is also
        // re-triggered by a bare libraryCloudEpoch bump (Restore/Discard in
        // Recently deleted expecting a quick trash re-list) that has nothing
        // to do with bucket/oauth/connectivity identity. Re-running the full
        // validated-network gate + ping + backoff on those re-runs would add
        // seconds of latency to every cloud action, so skip straight to the
        // listing when the identity hasn't changed since the last confirmed
        // Ready.
        if (!shouldReprobe(prevKeys = lastReadyProbeKeys, newKeys = currentProbeKeys, wasReady = cloudProbeReady)) {
            val fastIo = productObjectStore()
            if (fastIo is ByoCloudIoResult.Ready) {
                listCloudTrash(fastIo.store)
                return@LaunchedEffect
            }
            // Unexpected: the recorded identity claims a Ready store, but
            // it isn't resolvable right now (e.g. a transient hiccup). Fall
            // through to the full probe below rather than silently doing
            // nothing.
        }

        if (!appGraph.cloudConnectivityReKick.isValidatedNow()) {
            // No validated network yet — this is CHECKING, not blocked: no
            // banner. The connectivityEpoch key re-runs this effect the
            // moment the OS reports a reconnect.
            cloudProbeReady = false
            libraryCloudBlocked = null
            return@LaunchedEffect
        }
        val firstIo = productObjectStore()
        if (firstIo is ByoCloudIoResult.Blocked && appGraph.currentBucketFingerprintOrNull() == null) {
            // Nothing bound at all — not transient, never grace-looped.
            cloudProbeReady = false
            libraryCloudBlocked = firstIo.message
            libraryCloudTrashCards = emptyList()
            libraryRecycleItems = emptyList()
            return@LaunchedEffect
        }
        // If firstIo IS Blocked here, a binding exists but this attempt's
        // store resolution failed (e.g. cold-start OAuth refresh not yet
        // warm) — fall through into the same grace loop a Ready store's
        // ping would run, resolving a fresh store on every attempt below.
        val plan = CloudProbeGrace.plan(appGraph.cloudPingBaselineStore.baselineMs())
        val totalAttempts = 1 + plan.retryDelaysMs.size
        var grace: GraceState = GraceState.Checking
        var readyStore: ObjectStore? = (firstIo as? ByoCloudIoResult.Ready)?.store
        var attempt = 0
        while (attempt < totalAttempts) {
            // Resolved fresh every attempt (not just the first) so a store
            // that only becomes resolvable on a LATER attempt — the owner's
            // original bug #3 symptom, an OAuth refresh warming up after a
            // cold-start hiccup — is exactly the instance probed AND, on
            // success, the instance the listing pass below then uses.
            val io = if (attempt == 0) firstIo else productObjectStore()
            val outcome = when (io) {
                is ByoCloudIoResult.Ready -> {
                    readyStore = io.store
                    appGraph.probeCloudReachability(plan.timeoutMs, io.store)
                }
                is ByoCloudIoResult.Blocked -> {
                    // hasBinding is re-checked per-attempt too: a binding
                    // could in principle disappear mid-loop (user unbinds).
                    graceOutcomeForBlockedStore(
                        hasBinding = appGraph.currentBucketFingerprintOrNull() != null,
                        message = io.message,
                    ) ?: run {
                        cloudProbeReady = false
                        libraryCloudBlocked = io.message
                        libraryCloudTrashCards = emptyList()
                        libraryRecycleItems = emptyList()
                        return@LaunchedEffect
                    }
                }
            }
            grace = graceStateAfter(attemptIndex = attempt, totalAttempts = totalAttempts, outcome = outcome)
            if (grace !is GraceState.Checking) break
            libraryCloudBlocked = null
            delay(plan.retryDelaysMs[attempt])
            attempt += 1
        }
        when (val settled = grace) {
            is GraceState.Checking -> {
                // Unreachable — the loop above always resolves to
                // Ready/Unreachable by its last attempt; kept only
                // for exhaustiveness over the sealed interface.
                cloudProbeReady = false
                libraryCloudBlocked = null
            }
            is GraceState.Unreachable -> {
                cloudProbeReady = false
                // #D16: a refused sign-in is not "unreachable" — say what it is and where the fix lives.
                val signIn = settled.cause == ProbeCause.SIGN_IN_REJECTED
                libraryCloudSignInRejected = signIn
                libraryCloudBlocked = if (signIn) {
                    CloudSignInCopy.sentence(bridgeR2BindPresenter.state.boundKind)
                } else {
                    LibraryCloudSegmentCopy.CLOUD_UNREACHABLE_AFTER_RETRIES
                }
                libraryCloudTrashCards = emptyList()
                libraryRecycleItems = emptyList()
            }
            is GraceState.Ready -> {
                cloudProbeReady = true
                libraryCloudSignInRejected = false
                lastReadyProbeKeys = currentProbeKeys
                val store = checkNotNull(readyStore) {
                    "GraceState.Ready without a resolved store — probeCloudReachability " +
                        "only returns Reachable for a ByoCloudIoResult.Ready branch, which " +
                        "always sets readyStore first."
                }
                // #D16: the drive accepts the saved sign-in (again) — downloads it refused go back to work.
                // Refused uploads and Collect checks are FAILED rows: onBucketBound and the catch-up sweep revive those.
                if (withContext(Dispatchers.IO) { appGraph.reviveSignInRefusedCloudDownloads() } > 0) {
                    DownloadPumpWorker.kick(context)
                }
                listCloudTrash(store)
            }
        }
    }

    /** Scopes the local Room mirror ([appGraph.cloudMirrorStore]) to whichever bucket is currently bound. */
    val cloudBucketFingerprint = remember(bridgeR2BucketLabel, bridgeR2UnlockedEpoch) {
        appGraph.currentBucketFingerprintOrNull()
    }
    val cloudThumbDir = remember { CloudThumbCache.root(context.cacheDir) }
    // S4 Task 1 (owner bug C4): a durable on-device cover (card.coverPath)
    // is always preferred — it is what makes the Cloud shelf browsable with
    // zero R2 network calls once a comic has synced. Only a card that has
    // never had one written (e.g. hydrated from another device's index
    // chunk before this device's own downloadThumb fallback below has run)
    // falls back to the volatile cloud-thumbs cache-file convention that
    // predates this fix.
    // S4 Task 1 review (M1): a durable cover path is only trusted when the
    // file it names both exists AND is non-empty — a 0-byte file (left
    // behind by a pre-fix write, or any future partial-write bug) must fall
    // back to the volatile cache/remote path below, never be treated as a
    // real cover.
    fun coverUrlFromCacheOrNull(card: CloudCollectionCard): String? =
        when (
            val source = resolveCoverSource(card.coverPath) { path ->
                val file = File(path)
                file.isFile && file.length() > 0
            }
        ) {
            is CoverSource.Local -> File(source.path).toURI().toString()
            CoverSource.Remote ->
                // C5 audit BLOCKING-1: the cover cache file, never the strip's
                // ordinal-0 cell; and a real file, like the Local branch above.
                CloudThumbCache.coverFile(cloudThumbDir, card.cloudComicId).takeIf { it.isFile && it.length() > 0 }
                    // C1 audit HIGH-2: a displayed cache file is touched, or the
                    // oldest-first sweep evicts the shelf's covers first.
                    ?.also { CloudThumbCache.touch(it) }
                    ?.toURI()?.toString()
        }

    /**
     * B4b progress strip (loyal-pipelines Slice B §2, live updates): the
     * hydration checkpoint for the bound bucket, or null when there is no
     * bucket bound or hydration has never checkpointed for it yet — either
     * way [LibraryScreen] keeps the "Importing · N/M" strip hidden.
     */
    val cloudMirrorHydrationState by remember(cloudBucketFingerprint) {
        cloudBucketFingerprint?.let { appGraph.cloudMirrorStore.observeState(it) } ?: flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)

    // B4a mirror-backed shelf (loyal-pipelines Slice B §2): the Cloud tab's
    // active shelf is a live collection of the local Room mirror — instant
    // open, offline browsing, and progressive fill as HydrationWorker
    // imports, with no cold-start "not ready" gap. Readiness is purely "is a
    // bucket bound" (fp != null); unlike libraryCloudBlocked above, it no
    // longer waits on the encryption gate/OAuth dance to resolve.
    LaunchedEffect(cloudBucketFingerprint) {
        val fp = cloudBucketFingerprint
        libraryCloudReady = fp != null
        // C29 audit MEDIUM-1 + review MEDIUM-1/MEDIUM-2: this is the first thing
        // that runs with the bound fingerprint in scope, so it is where the
        // warm-restart seed is re-decided. A direct rebind A -> B never passes
        // through the unbound branch below, so without this the previous
        // bucket's tiles would stay on screen (and stay memoised) until B's
        // flow emitted.
        libraryCloudCards = seedCloudShelfCards(
            memoCards = libraryCloudCards,
            memoFingerprint = cloudProcess.libraryCloudFingerprint,
            currentFingerprint = fp,
        )
        cloudProcess.libraryCloudFingerprint = fp
        if (fp == null) {
            libraryCloudCards = emptyList()
            return@LaunchedEffect
        }
        appGraph.cloudMirrorStore.observeActiveCards(fp).collect { cards ->
            // An emission for the bound bucket is authoritative, including when
            // it is EMPTY (an emptied bucket must clear the shelf). Until the
            // first one the seeded cards stay on screen — that is the whole
            // no-flash contract, and it is an assignment, not a policy.
            libraryCloudCards = cards
        }
    }

    // Recombines the mirror's raw cards with whatever cover thumbnails are
    // already cached on disk / fetched this session. Runs a FULL pass only
    // when the card LIST itself changes (new bucket, hydration upsert,
    // re-walk) — cover availability is patched per-card by the thumb-fill
    // effect below, so a 5k-card shelf with N covers still does O(N) work
    // total (A39: the old keys included the coverUrl map, so every single
    // cover write re-mapped every card AND re-probed every card's disk
    // state — O(N²) file probes for N sequential covers). The coverUrl map
    // stays as the disk-probe memo: a card absent from it falls through to
    // one disk probe, then never probes again.
    LaunchedEffect(libraryCloudCards) {
        libraryCloudItems = libraryCloudCards.map { card ->
            CloudCollectionFeedMapper.mapCard(
                card,
                coverUrl = libraryCloudCoverUrls[card.cloudComicId] ?: coverUrlFromCacheOrNull(card),
            )
        }
    }

    // Background thumb fill: bounded to one in-flight download at a time
    // (serial), newest-first (observeActiveCards is already ordered that
    // way), skipping anything already cached on disk under the same
    // cache-file convention the old active-pass network walk used. Never
    // blocks the list itself — libraryCloudItems already renders a
    // placeholder for any card whose thumb isn't cached yet.
    //
    // S4 Task 1 review (M2): keyed on (cloudBucketFingerprint,
    // cloudProbeReady, bridgeR2UnlockedEpoch, <the actual set of card ids>)
    // instead of just the card *count* — the prior count-only key meant a
    // rebind to a different bucket with the same number of cards, or an R2
    // unlock/relock cycle mid-fill, never restarted this effect, so
    // "missing" covers for the new bucket's cards could sit unfilled
    // indefinitely. Keying on the id *set* (not the list) still avoids
    // restarting on every upsertChunkCards re-emit for the same underlying
    // cards (structural equality on an unordered set), matching the original
    // count-based effect's intent of restarting only when what's actually
    // missing could have changed.
    LaunchedEffect(
        cloudBucketFingerprint,
        cloudProbeReady,
        bridgeR2UnlockedEpoch,
        libraryCloudCards.map { it.cloudComicId }.toSet(),
    ) {
        val fp = cloudBucketFingerprint ?: return@LaunchedEffect
        if (!AppLiveDeviceBindings.shouldRunCloudIo(bindLiveDeviceSession)) return@LaunchedEffect
        val missing = libraryCloudCards.filter { coverUrlFromCacheOrNull(it) == null }
        if (missing.isEmpty()) return@LaunchedEffect
        val store = (productObjectStore() as? ByoCloudIoResult.Ready)?.store ?: return@LaunchedEffect
        val collectionStore = CloudCollectionStore(store)
        withContext(Dispatchers.IO) {
            cloudThumbDir.mkdirs()
            for (card in missing) {
                if (!isActive) break
                try {
                    val cover = appGraph.downloadCloudShelfCover(collectionStore, card.cloudComicId, card.coverThumbObjectKey) ?: continue
                    val uri = cover.uri
                    libraryCloudCoverUrls = libraryCloudCoverUrls + (card.cloudComicId to uri)
                    if (cover.durablePath != null) {
                        appGraph.cloudMirrorStore.setCoverPath(fp, card.cloudComicId, cover.durablePath)
                    }
                    // A39: patch ONLY this card's shelf item instead of
                    // re-mapping every card per cover (the old full re-map
                    // keyed on the coverUrl map, O(N²) for N sequential
                    // covers). The helper is idempotent and returns the same
                    // list instance when nothing changed.
                    libraryCloudItems = CloudCollectionFeedMapper.patchCover(
                        libraryCloudItems,
                        cloudComicId = card.cloudComicId,
                        coverUrl = uri,
                        providerId = card.providerId,
                        providerSeriesId = card.providerSeriesId,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DiagLog.w("CloudSync", "background thumb fill failed id=${card.cloudComicId} msg=${error.message}")
                }
            }
        }
    }

    // #D7: an older .pclbackup (state only) still restores; the device archive lives in BackupPresenter.
    suspend fun applyStateSnapshot(snapshot: LibraryStateSnapshot) {
        browseHistoryStore.importRaw(snapshot.historyJson)
        collectBookmarkStore.importRaw(snapshot.bookmarksJson)
        // W7 S4: the session never travels in the envelope, so there is
        // nothing session-shaped to apply — and overwriting this device's
        // live session with the envelope's empty cookie would actively sign
        // the user out. The account store is left untouched; a device that
        // never signed in stays signed out, one that did stays signed in.
        ehentaiEnableStore.setEnabled(snapshot.frontEnabled)
        nhentaiEnableStore.setEnabled(snapshot.indexEnabled)
        ehentaiAccount = ehentaiAccountStore.read()
        ehentaiEnabled = ehentaiEnableStore.isEnabled()
        exhentaiEnabled = exhentaiEnableStore.isEnabled()
        nhentaiEnabled = nhentaiEnableStore.isEnabled()
        historyEntries = browseHistoryStore.read()
        collectBookmarkEpoch += 1
        val repo = repository ?: return
        // MEDIUM-1: unfiltered read, matching buildStateSnapshot above.
        val items = repo.observeAllItems().first()
        val byKey = items.associateBy { it.title to it.pageCount }
        ReadingProgressBackup.decode(snapshot.progressJson).forEach { entry ->
            val match = byKey[entry.title to entry.pageCount] ?: return@forEach
            repo.setReadingProgress(match.readingUnitId, entry.progressOrdinal)
        }
    }

    val importLegacyBackupLauncher = rememberLauncherForActivityResult(
        OpenDocumentInDownloads(),
    ) { uri: Uri? ->
        val passphrase = pendingLegacyBackupPassword
        pendingLegacyBackupPassword = null
        if (uri == null || passphrase.isNullOrBlank()) {
            return@rememberLauncherForActivityResult
        }
        coroutineScope.launch {
            legacyBackupResult = withContext(Dispatchers.IO) {
                runCatching {
                    val sealedFile = File.createTempFile("pcl-state-in-", ".pclbackup", context.cacheDir)
                    val plain = File.createTempFile("pcl-state-open-", ".json", context.cacheDir)
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            sealedFile.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Could not read backup")
                        EncryptedBackupCodec.openFromFile(
                            passphrase.toCharArray(),
                            sealedFile,
                            plain,
                        )
                        val snapshot = LibraryStateBackup.decode(plain.readBytes())
                        withContext(Dispatchers.Main) {
                            applyStateSnapshot(snapshot)
                        }
                        "Backup restored."
                    } finally {
                        sealedFile.delete()
                        plain.delete()
                    }
                }.getOrElse { error ->
                    when (error) {
                        is EncryptedBackupException -> "Wrong passphrase or damaged backup."
                        else -> error.message ?: "Import failed"
                    }
                }
            }
        }
    }

    // #D7: device-to-device archive. The presenter holds the flow; these launchers only turn the
    // user's pick into a stream. A multi-GB export streams straight into the picked document.
    class UriDestination(val uri: Uri) : Destination
    val backupPresenter = remember {
        BackupPresenter(
            planFor = { include -> appGraph.archiveExporter().plan(include) },
            export = { dest, pw, plan, onPage, isCancelled ->
                val uri = (dest as UriDestination).uri
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not write backup")
                try {
                    appGraph.archiveExporter().export(output, pw, plan, onPage, isCancelled)
                } catch (e: Throwable) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    throw e
                }
            },
            openArchive = { dest, pw ->
                val input = context.contentResolver.openInputStream((dest as UriDestination).uri) ?: error("Could not read backup")
                ArchiveReader.open(input, pw)
            },
            preview = { reader -> appGraph.archiveRestorer().preview(reader) },
            restore = { reader, onComic -> appGraph.archiveRestorer().restore(reader, onComic) },
        )
    }
    val backupUiState by backupPresenter.state.collectAsStateWithLifecycle()
    // #E6: ArchiveRestorer writes the durable stores, while these settings are
    // cached in Compose state. Refresh them after the presenter reports a
    // successful restore so the new phone does not need a restart.
    suspend fun refreshCachedSettingsAfterArchiveRestore() {
        withContext(Dispatchers.Main) {
            viewMode = libraryLayoutStore.read()
            ehentaiEnabled = ehentaiEnableStore.isEnabled()
            nhentaiEnabled = nhentaiEnableStore.isEnabled()
            jmEnabled = jmEnableStore.isEnabled()
            invalidateBrowseFeed()
            browsePrefs = browsePrefsStore.read()
            keepScreenOnEnabled = readerKeepScreenOnStore.read()
            volumeKeyPagingEnabled = readerVolumeKeyPagingStore.read()
            rtlPagingEnabled = readerRtlPagingStore.read()
            cacheBudgetMb = appGraph.cacheBudgetStore.readMb()
            cloudReleaseAfterUploadEnabled = cloudReleaseAfterUploadStore.read()
            maxConcurrentTransfers = appGraph.maxConcurrentTransfersStore.read()
            // #E5: restored online/cloud reading positions show without a restart.
            remoteProgressEpoch += 1
        }
    }
    var pendingArchiveExport by remember { mutableStateOf<Triple<Boolean, String, String>?>(null) }
    data class RestorePrompt(
        val uri: Uri?,
        val legacy: Boolean,
        val state: BackupPassphrasePromptState = BackupPassphrasePromptState(action = BackupPassphraseAction.IMPORT_STATE),
    )
    var restorePrompt by remember { mutableStateOf<RestorePrompt?>(null) }
    val archiveExportLauncher = rememberLauncherForActivityResult(
        CreateDocumentInDownloads("application/octet-stream"),
    ) { uri: Uri? ->
        val choice = pendingArchiveExport
        pendingArchiveExport = null
        if (uri == null || choice == null) return@rememberLauncherForActivityResult
        coroutineScope.launch(Dispatchers.IO) {
            backupPresenter.export(choice.first, choice.second, choice.third, UriDestination(uri))
        }
    }
    val archiveRestoreLauncher = rememberLauncherForActivityResult(
        OpenDocumentInDownloads(),
    ) { uri: Uri? ->
        if (uri != null) restorePrompt = RestorePrompt(uri = uri, legacy = false)
    }

    val browseSourceOptionList = remember(ehentaiEnabled, exhentaiEnabled, nhentaiEnabled, jmEnabled, bridgeR2BucketLabel, opdsProviders) {
        browseSourceOptions(
            ehentaiEnabled = ehentaiEnabled,
            exhentaiEnabled = exhentaiEnabled,
            nhentaiEnabled = nhentaiEnabled,
            cloudCollectionEnabled = bridgeR2BucketLabel != null,
            zaimanhuaEnabled = ZAIMANHUA_LISTED_IN_BROWSE,
            jmEnabled = jmEnabled,
            opdsProviders = opdsProviders,
        )
    }
    // Browse Level 1 (two-level spec §1): root source-list rows. Official
    // sources render Healthy (no persisted health exists for them yet). OPDS
    // providers carry real persisted health, so a catalog the last probe could
    // not reach renders UNREACHABLE here rather than a false Healthy — the row
    // stays tappable per spec §4. No probe loop lives here; this only reads
    // what the probe already persisted.
    val browseSourceList = remember(ehentaiEnabled, exhentaiEnabled, nhentaiEnabled, jmEnabled, opdsProviders, browseCheckingProviderIds) {
        browseSourceListItems(
            ehentaiEnabled = ehentaiEnabled,
            exhentaiEnabled = exhentaiEnabled,
            nhentaiEnabled = nhentaiEnabled,
            zaimanhuaEnabled = ZAIMANHUA_LISTED_IN_BROWSE,
            jmEnabled = jmEnabled,
            opdsProviders = opdsProviders,
            unreachableProviderIds = opdsProviders.filter { it.isDimmed }.map { it.id }.toSet(),
            checkingProviderIds = browseCheckingProviderIds,
        )
    }

    // Level 2 is entered for the currently selected source; the Level-2 header
    // back arrow (and the system back key at the Browse root) pop back to the
    // Level-1 list.
    var browseSourceListVisible by rememberSaveable { mutableStateOf(true) }

    fun currentBrowseSnapshot(): TagSearchBrowseSnapshot {
        val transient = transientBrowseSession
        return if (transient != null) {
            TagSearchBrowseSnapshot(
                prefs = transient.prefs,
                filterStates = mapOf(transient.prefs.sourceId to transient.filterState),
                query = transient.query,
                jmSearchSession = transient.jmSearchSession,
                feedUrl = transient.feedUrl,
                feedProviderId = transient.feedProviderId,
                sourceListVisible = false,
                loadedKey = transient.loadedKey,
                results = transient.results,
                nextContinuation = transient.nextContinuation,
                knownTotalItems = transient.knownTotalItems,
                loadMoreError = transient.loadMoreError,
                error = transient.error,
                notice = transient.notice,
                scroll = transientBrowseListState.firstVisibleItemIndex to
                    transientBrowseListState.firstVisibleItemScrollOffset,
            )
        } else {
            TagSearchBrowseSnapshot(
                prefs = browsePrefs,
                filterStates = browseFilterStates,
                query = browseQuery,
                jmSearchSession = jmSearchSession,
                feedUrl = browseFeedUrl,
                feedProviderId = browseFeedProviderId,
                sourceListVisible = browseSourceListVisible,
                loadedKey = browseLoadedKey,
                results = browseResults,
                nextContinuation = browseNextContinuation,
                knownTotalItems = browseKnownTotalItems,
                loadMoreError = browseLoadMoreError,
                error = browseError,
                notice = browseFeedNotice,
                scroll = browseListState.firstVisibleItemIndex to browseListState.firstVisibleItemScrollOffset,
            )
        }
    }

    fun detailSnapshotForCurrentSurface(): TagSearchDetailSnapshot? = when {
        remoteDetailItem != null -> {
            val item = checkNotNull(remoteDetailItem)
            val token = (tagSearchStack.top() as? TagSearchSurface.Detail)
                ?.snapshot
                ?.takeIf { it is TagSearchDetailSnapshot.Remote && it.item == item }
                ?.token
                ?: "remote-$remoteDetailOpenGeneration"
            TagSearchDetailSnapshot.Remote(
                token = token,
                item = item,
                route = route,
                originAcquisition = remoteDetailOriginAcquisition,
                originProviderId = remoteDetailOriginProviderId,
                serializedReaderOriginRoute = serializedReaderOriginRoute,
            )
        }
        route is AppRoute.Detail -> TagSearchDetailSnapshot.Local(
            token = "local-${(route as AppRoute.Detail).itemId}-${tagSearchStack.surfaces.size}",
            route = route as AppRoute.Detail,
        )
        else -> null
    }

    fun beginTagSearch(target: TagSearchTarget) {
        // Reuse the top detail frame when it already represents the on-screen
        // detail: rebuilding an equivalent-but-not-equal snapshot would push a
        // duplicate frame, and Back would then reveal that frame instead of
        // the layer beneath it.
        val detail = (tagSearchStack.top() as? TagSearchSurface.Detail)?.snapshot
            ?.takeIf { snapshot ->
                (snapshot is TagSearchDetailSnapshot.Remote && snapshot.item == remoteDetailItem) ||
                    (snapshot is TagSearchDetailSnapshot.Local && route is AppRoute.Detail && snapshot.route == route)
            }
            ?: detailSnapshotForCurrentSurface()
            ?: return
        val originRoute = serializedReaderOriginRoute
        // Reject any root-feed continuation that was queued before the detail
        // became a transient layer; no saved Browse identity is changed.
        invalidateBrowseFeed()
        val surfaces = tagSearchStack.surfaces
        val beneath = surfaces.getOrNull(surfaces.size - 2)
        if (tagSearchStack.top() is TagSearchSurface.Detail &&
            beneath is TagSearchSurface.Results &&
            beneath.target == target
        ) {
            // The same target is already the layer directly under this detail:
            // reveal its rows unchanged instead of pushing a duplicate layer
            // that would re-request page 1 (the root Browse same-target rule).
            tagSearchStack = tagSearchStack.pop()
            transientBrowseSession = beneath.transient.copy(loading = false, loadingMore = false)
            transientBrowseFilterOpen = false
            serializedReaderOriginLayerToken = null
            remoteDetailOpenGeneration += 1L
            remoteDetailItem = null
            remoteDetailCloudStore = null
            sayRemote(null)
            remoteDetailBusy = false
            remoteDetailMetadataLoading = false
            remoteDetailOriginAcquisition = null
            remoteDetailOriginProviderId = null
            browseSourceListVisible = false
            route = AppRoute.Browse
            coroutineScope.launch { transientBrowseListState.scrollToItem(0) }
            return
        }
        val baseBrowse: TagSearchBrowseSnapshot = currentBrowseSnapshot()
        val knownFilters = baseBrowse.filterStates[target.sourceId]
        val session = TransientBrowseSession.fromTarget(
            target = target,
            savedFilters = knownFilters ?: SourceFilterState(),
            filterResolved = knownFilters != null,
        )
        tagSearchStack = tagSearchStack.pushSearch(
            detail = detail,
            browse = baseBrowse,
            target = target,
            transient = session,
        )
        transientBrowseSession = session
        transientBrowseFilterOpen = false
        serializedReaderOriginLayerToken = detail.token
        if (originRoute != null) serializedReaderOriginRoute = originRoute
        remoteDetailOpenGeneration += 1L
        remoteDetailItem = null
        remoteDetailCloudStore = null
        sayRemote(null)
        remoteDetailBusy = false
        remoteDetailMetadataLoading = false
        remoteDetailOriginAcquisition = null
        remoteDetailOriginProviderId = null
        browseSourceListVisible = false
        route = AppRoute.Browse
    }

    fun tagSearchActionFor(
        item: BrowseFeedItem?,
        originalProviderId: String? = null,
        localProviderId: String? = null,
    ): ((String) -> Unit)? {
        val detail = item ?: return null
        val providerId = detailSearchProviderId(detail, originalProviderId, localProviderId)
        val sample = detail.tags.firstOrNull { it.isNotBlank() }
            ?: detail.artists.firstOrNull { it.isNotBlank() }
            ?: detail.groups.firstOrNull { it.isNotBlank() }
            ?: return null
        if (tagSearchTarget(
                providerId = providerId,
                kind = DetailSearchKind.TAG,
                rawText = sample,
                offeredSources = browseSourceOptionList,
                genres = browseGenres,
            ) == null
        ) return null
        return { raw ->
            tagSearchTarget(
                providerId = detailSearchProviderId(detail, originalProviderId, localProviderId),
                kind = DetailSearchKind.TAG,
                rawText = raw,
                offeredSources = browseSourceOptionList,
                genres = browseGenres,
            )?.let(::beginTagSearch)
        }
    }
    // #C15 re-review item 1: ONE writer for an OPDS health verdict. The Browse
    // entry probe and a failed feed page both land here, so a page that fails
    // while the user is already inside level 2 dims the source exactly like a
    // failed probe would — the footer's honest sentence follows from `isDimmed`.
    fun commitOpdsProbeOutcome(provider: OpdsProviderConfig, reachable: Boolean, observedAt: Long) {
        if (BrowseFeedSession.shouldRetryAfterProbe(
                activeSourceId = browsePrefs.sourceId,
                providerId = provider.id,
                reachable = reachable,
                browseError = browseError,
                wasDimmed = provider.isDimmed,
            )
        ) {
            // #C15 re-review item 4: recovery also re-arms pagination — a failed
            // load-more disables the auto-append effect and (by design) has no
            // Retry, so nothing else would ever fetch page N+1 again.
            browseLoadMoreError = null
            browseRecoveryEpoch += 1
        }
        val fresh = opdsProviderStore.readAll()
        val merged = OpdsCatalogConnect.applyProbeOutcome(
            fresh = fresh,
            snapshotProvider = provider,
            reachable = reachable,
            atEpochMs = observedAt,
        )
        if (merged != fresh) {
            opdsProviderStore.writeAll(merged)
            opdsProviders = merged
        }
    }
    // C15: Browse entry is itself a health boundary. Probe configured OPDS
    // catalogs once when the route is entered, then merge only health facts
    // into the latest persisted rows so an edit made during the probe cannot
    // be lost. The effect is keyed only by route: navigating from the source
    // list into level 2 must not cancel an in-flight probe.
    // Re-keyed on browseProbeEpoch (#C15 audit HIGH-2): a resume re-probes —
    // but only once the previous probe has finished, because re-keying cancels
    // it (#C15 re-review item 5); the ON_RESUME observer holds the bump while
    // browseCheckingProviderIds is non-empty.
    LaunchedEffect(route, browseProbeEpoch) {
        browseProbeGeneration += 1
        val probeGeneration = browseProbeGeneration
        if (route != AppRoute.Browse || opdsProviders.isEmpty()) {
            return@LaunchedEffect
        }
        val snapshot = opdsProviders
        // 2026-09-19 spec §5: every snapshot row reads Checking… from the
        // same frame the probe starts; each commit clears its own row.
        browseCheckingProviderIds = snapshot.map { it.id }.toSet()
        val client = OpdsCatalogClient()
        try {
            OpdsCatalogConnect.probeAndPersistIncrementally(
                snapshot = snapshot,
                probe = { provider ->
                    withContext(Dispatchers.IO) {
                        OpdsCatalogConnect.probeReachability(provider, client)
                    }
                },
                atEpochMs = { System.currentTimeMillis() },
                commitOutcome = { provider, reachable, observedAt ->
                    browseCheckingProviderIds = browseCheckingProviderIds - provider.id
                    commitOpdsProbeOutcome(provider, reachable, observedAt)
                },
            )
        } finally {
            // #C15 r3 item 1: a cancelled probe's blocking connect returns
            // LATE (up to the 15 s timeout) — by then a newer generation may
            // own the set. Only the current generation may clear it; the
            // same rule the feed effect uses for browseLoading.
            if (BrowseFeedSession.shouldReleaseLoading(requestGeneration = probeGeneration, currentGeneration = browseProbeGeneration)) {
                browseCheckingProviderIds = emptySet()
            }
        }
    }
    // #E31: one label rule shared with the source list (BrowsePrefs) — the
    // header and search hint can no longer disagree with it, and a source
    // keeps its name even while its enable toggle is off.
    val browseHeaderSourceLabel = remember(browsePrefs.sourceId, opdsProviders) {
        browseSourceLabel(
            sourceId = browsePrefs.sourceId,
            opdsProviders = opdsProviders,
            sourceRegistry = appGraph.comicSourceRegistry,
        )
    }


    val browseSessionKey = BrowseFeedSessionKey(
        prefs = browsePrefs,
        query = browseQuery,
        feedUrl = browseFeedUrl,
        feedProviderId = browseFeedProviderId,
        opdsIds = opdsProviders.map { it.id },
        frontEnabled = ehentaiEnabled,
        exhentaiEnabled = exhentaiEnabled,
        indexEnabled = nhentaiEnabled,
        unlockEpoch = bridgeR2UnlockedEpoch,
        // A2(a): only the ACTIVE source's filter state — not the whole
        // browseFilterStates map — so editing another source's filter (e.g.
        // priming it on a source switch) can't spuriously reload the feed
        // that's actually on screen right now.
        filterState = browseFilterStates[browsePrefs.sourceId],
        jmSearchScope = jmSearchSession.scope.takeIf {
            browsePrefs.sourceId == JmEnableStore.SOURCE_ID && browseQuery.isNotBlank() && !jmSearchSession.awaitingSubmit
        },
        searchAwaitingSubmit = jmSearchSession.awaitingSubmit,
    )
    val currentBrowseSessionKey by rememberUpdatedState(browseSessionKey)

    val transientBrowseKey = transientBrowseSession?.let { session ->
        BrowseFeedSessionKey(
            prefs = session.prefs,
            query = session.query,
            feedUrl = session.feedUrl,
            feedProviderId = session.feedProviderId,
            opdsIds = opdsProviders.map { it.id },
            frontEnabled = ehentaiEnabled,
            exhentaiEnabled = exhentaiEnabled,
            indexEnabled = nhentaiEnabled,
            unlockEpoch = bridgeR2UnlockedEpoch,
            filterState = session.filterState,
            jmSearchScope = session.jmSearchSession.scope.takeIf {
                session.prefs.sourceId == JmEnableStore.SOURCE_ID &&
                    session.query.isNotBlank() && !session.jmSearchSession.awaitingSubmit
            },
            searchAwaitingSubmit = session.jmSearchSession.awaitingSubmit,
        )
    }

    // A layer whose target source has no root filter-map entry (pushed from a
    // History, Library, or Updates detail) must still search with the source's
    // saved filter. Resolve it from the store, then rebuild the session so the
    // load effect re-keys with the resolved filter instead of the placeholder.
    LaunchedEffect(transientBrowseSession?.filterResolved, transientBrowseSession?.prefs?.sourceId) {
        val session = transientBrowseSession ?: return@LaunchedEffect
        if (session.filterResolved) return@LaunchedEffect
        val saved = withContext(Dispatchers.IO) { browseFilterStore.get(session.prefs.sourceId) }
        val target = (tagSearchStack.top() as? TagSearchSurface.Results)?.target ?: return@LaunchedEffect
        val latest = transientBrowseSession
        if (latest != null && !latest.filterResolved && latest.prefs.sourceId == session.prefs.sourceId) {
            transientBrowseSession = TransientBrowseSession.fromTarget(
                target = target,
                savedFilters = saved,
                filterResolved = true,
            )
        }
    }

    // Keyed on the resolved flag too: a genre target's placeholder and saved
    // filter can canonicalize to the SAME session key, so the key alone would
    // never restart this effect after the resolver finishes.
    LaunchedEffect(transientBrowseKey, transientBrowseSession?.filterResolved) {
        val key = transientBrowseKey ?: return@LaunchedEffect
        val current = transientBrowseSession ?: return@LaunchedEffect
        if (current.loadedKey == key || current.loading) return@LaunchedEffect
        if (!current.filterResolved) return@LaunchedEffect
        if (!BrowseFeedSession.canStartRequest(key)) {
            // The layer's search field was edited but not submitted: hold the
            // rows, exactly like the root Browse session (#D2).
            transientBrowseSession = current.copy(
                loading = false,
                loadingMore = false,
                nextContinuation = null,
                loadMoreError = null,
            )
            return@LaunchedEffect
        }
        val generation = current.generation + 1
        transientBrowseSession = current.copy(
            loading = true,
            loadingMore = false,
            loadedKey = null,
            results = emptyList(),
            nextContinuation = null,
            knownTotalItems = null,
            loadMoreError = null,
            error = null,
            notice = null,
            generation = generation,
        )
        val result = withContext(Dispatchers.IO) {
            browseFeedRepository.load(
                prefs = key.prefs,
                query = key.query,
                explicitFeedUrl = key.feedUrl,
                explicitProviderId = key.feedProviderId,
            )
        }
        val latest = transientBrowseSession
        if (latest == null || latest.generation != generation || transientBrowseKey != key) {
            return@LaunchedEffect
        }
        transientBrowseSession = when (result) {
            is BrowseFeedResult.Ok -> latest.copy(
                loading = false,
                loadedKey = key,
                results = result.items,
                nextContinuation = result.nextContinuation,
                knownTotalItems = result.totalItems,
                notice = result.notice,
                error = null,
            )
            is BrowseFeedResult.Error -> latest.copy(
                loading = false,
                loadedKey = null,
                results = emptyList(),
                nextContinuation = null,
                error = result.message,
            )
        }
    }

    fun loadMoreTransientBrowse() {
        val session = transientBrowseSession ?: return
        val key = transientBrowseKey ?: return
        val continuation = session.nextContinuation ?: return
        if (session.loading || session.loadingMore || session.filterState == null) return
        val generation = session.generation
        transientBrowseSession = session.copy(loadingMore = true, loadMoreError = null)
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                browseFeedRepository.load(
                    prefs = key.prefs,
                    query = key.query,
                    explicitFeedUrl = key.feedUrl,
                    explicitProviderId = key.feedProviderId,
                    continuation = continuation,
                )
            }
            val latest = transientBrowseSession
            if (latest == null || latest.generation != generation || transientBrowseKey != key) return@launch
            transientBrowseSession = when (result) {
                is BrowseFeedResult.Ok -> latest.copy(
                    loadingMore = false,
                    results = BrowseFeedAppend.merge(latest.results, result.items),
                    nextContinuation = result.nextContinuation,
                    knownTotalItems = result.totalItems ?: latest.knownTotalItems,
                    notice = result.notice ?: latest.notice,
                )
                is BrowseFeedResult.Error -> latest.copy(
                    loadingMore = false,
                    loadMoreError = result.message,
                )
            }
        }
    }

    fun loadMoreBrowse() {
        val requestKey = browseSessionKey
        val continuation = browseNextContinuation ?: return
        if (!BrowseFeedSession.canStartRequest(requestKey)) return
        if (browseLoadingMore || browseLoading || requestKey.filterState == null) return
        val requestGeneration = browseFeedGeneration
        browseLoadingMore = true
        browseLoadMoreError = null
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                browseFeedRepository.load(
                    prefs = requestKey.prefs,
                    query = requestKey.query,
                    explicitFeedUrl = requestKey.feedUrl,
                    explicitProviderId = requestKey.feedProviderId,
                    continuation = continuation,
                )
            }
            if (!BrowseFeedSession.acceptsResult(
                    requestKey = requestKey,
                    currentKey = currentBrowseSessionKey,
                    requestGeneration = requestGeneration,
                    currentGeneration = browseFeedGeneration,
                )
            ) {
                return@launch
            }
            browseLoadingMore = false
            when (result) {
                is BrowseFeedResult.Ok -> {
                    browseResults = BrowseFeedAppend.merge(browseResults, result.items)
                    browseNextContinuation = result.nextContinuation
                    if (result.totalItems != null) {
                        browseKnownTotalItems = result.totalItems
                    }
                    if (result.notice != null) browseFeedNotice = result.notice
                    if (requestKey.prefs.sourceId == GalleryIdentityV1.ZAIMANHUA) {
                        updateZaimanhuaAccountIfChanged(zaimanhuaAccountStore.read())
                    }
                    browseLoadMoreError = null
                }
                is BrowseFeedResult.Error -> {
                    browseLoadMoreError = result.message
                    // #C15 re-review item 1: a failed OPDS page is evidence.
                    BrowseFeedSession.providerToMarkUnreachableAfterLoadFailure(
                        activeSourceId = requestKey.prefs.sourceId,
                        opdsProviderIds = opdsProviders.map { it.id }.toSet(),
                        failureMessage = result.message,
                    )?.let { id ->
                        opdsProviders.firstOrNull { it.id == id }?.let { provider ->
                            commitOpdsProbeOutcome(provider, reachable = false, observedAt = System.currentTimeMillis())
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(route) {
        if (route == AppRoute.Browse) {
            // Re-entering the Browse tab preserves the last level (source list vs
            // feed) and scroll — the flags are rememberSaveable. Cold start still
            // lands on Level-1 via browseSourceListVisible's mutableStateOf(true)
            // default.
            browseSessionArmed = true
        }
    }
    LaunchedEffect(route, browseSessionArmed, browseSessionKey, browseRecoveryEpoch) {
        if (route != AppRoute.Browse || !browseSessionArmed) return@LaunchedEffect
        if (!BrowseFeedSession.canStartRequest(browseSessionKey)) {
            browseLoading = false
            browseLoadingMore = false
            browseNextContinuation = null
            browseLoadMoreError = null
            return@LaunchedEffect
        }
        if (!BrowseFeedSession.shouldReload(browseLoadedKey, browseSessionKey)) {
            return@LaunchedEffect
        }
        if (browseSessionKey.filterState == null) return@LaunchedEffect
        val previousKey = browseLoadedKey
        browseFeedGeneration += 1
        val generation = browseFeedGeneration
        // Only a completed successful feed is reusable. An interrupted or
        // failed load must retry when its source/tab is entered again.
        browseLoadedKey = null
        browseLoading = true
        browseLoadingMore = false
        browseError = null
        browseLoadMoreError = null
        browseNextContinuation = null
        browseKnownTotalItems = null
        browseFeedNotice = null
        try {
            val result = withContext(Dispatchers.IO) {
                browseFeedRepository.load(
                    prefs = browseSessionKey.prefs,
                    query = browseSessionKey.query,
                    explicitFeedUrl = browseSessionKey.feedUrl,
                    explicitProviderId = browseSessionKey.feedProviderId,
                )
            }
            if (!BrowseFeedSession.acceptsResult(
                    requestKey = browseSessionKey,
                    currentKey = currentBrowseSessionKey,
                    requestGeneration = generation,
                    currentGeneration = browseFeedGeneration,
                )
            ) return@LaunchedEffect
            when (result) {
                is BrowseFeedResult.Ok -> {
                    browseResults = result.items
                    browseNextContinuation = result.nextContinuation
                    browseKnownTotalItems = result.totalItems
                    browseFeedNotice = result.notice
                    if (browseSessionKey.prefs.sourceId == GalleryIdentityV1.ZAIMANHUA) {
                        updateZaimanhuaAccountIfChanged(zaimanhuaAccountStore.read())
                    }
                    browseError = null
                    browseLoadedKey = browseSessionKey
                }
                is BrowseFeedResult.Error -> {
                    browseResults = emptyList()
                    browseNextContinuation = null
                    browseError = result.message
                    // #C15 re-review item 1: a failed FIRST page of an OPDS
                    // source is the same evidence as a failed probe, so the
                    // level-1 row agrees with the level-2 empty state.
                    BrowseFeedSession.providerToMarkUnreachableAfterLoadFailure(
                        activeSourceId = browsePrefs.sourceId,
                        opdsProviderIds = opdsProviders.map { it.id }.toSet(),
                        failureMessage = result.message,
                    )?.let { id ->
                        opdsProviders.firstOrNull { it.id == id }?.let { provider ->
                            commitOpdsProbeOutcome(provider, reachable = false, observedAt = System.currentTimeMillis())
                        }
                    }
                }
            }
        } finally {
            // #C15 audit LOW-2 + re-review item 2: release on every exit path, but
            // only if this is still the generation that set the flag — a cancelled
            // older load's finally runs late (blocking socket) and must not clear a
            // newer load's skeleton.
            if (BrowseFeedSession.shouldReleaseLoading(requestGeneration = generation, currentGeneration = browseFeedGeneration)) {
                browseLoading = false
            }
        }
        if (browseLoadedKey == browseSessionKey) {
            // #12 / #D3: a remembered feed comes back where the user left it; any other
            // successful load starts at the top (see BrowseFeedSession.landingAfterLoad).
            when (val landing = BrowseFeedSession.landingAfterLoad(previousKey, browseScrollMemory.remove(browseSessionKey))) {
                is BrowseFeedLanding.Restore -> browseListState.scrollToItem(landing.index, landing.offset)
                BrowseFeedLanding.Top -> browseListState.scrollToItem(0)
            }
        }
    }

    // #12: snapshot the on-screen feed's scroll before navigating away from it
    // (descending into a subsection, or backing out) so the reload that follows
    // can restore it.
    fun saveBrowseScrollPosition() {
        val key = browseLoadedKey ?: return
        browseScrollMemory[key] =
            browseListState.firstVisibleItemIndex to browseListState.firstVisibleItemScrollOffset
    }

    fun openSources(returnTo: SourcesReturnTo) {
        sourcesReturnTo = returnTo
        route = AppRoute.Sources(returnTo)
    }

    fun loadLibraryOpdsShelf(providerId: String, feedUrl: String) {
        libraryOpdsProviderId = providerId
        libraryOpdsShelfItems = emptyList()
        libraryOpdsShelfError = null
        libraryOpdsShelfLoading = true
        val generation = libraryOpdsLoadGeneration + 1
        libraryOpdsLoadGeneration = generation
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                browseFeedRepository.load(
                    prefs = BrowsePrefs(sourceId = providerId),
                    query = "",
                    explicitFeedUrl = feedUrl,
                    explicitProviderId = providerId,
                )
            }
            if (generation != libraryOpdsLoadGeneration) return@launch
            libraryOpdsShelfLoading = false
            when (result) {
                is BrowseFeedResult.Ok -> {
                    libraryOpdsShelfItems = result.items
                    libraryOpdsShelfError = null
                }
                is BrowseFeedResult.Error -> {
                    libraryOpdsShelfItems = emptyList()
                    libraryOpdsShelfError = LibraryOpdsShelfCopy.LOAD_FAILED
                }
            }
        }
    }

    /** Opens one readable serialized chapter through the registry page adapter. */
    fun readSerializedChapter(
        item: BrowseFeedItem,
        chapterId: String,
        initialOrdinal: Int? = null,
    ) {
        val source = appGraph.comicSourceRegistry.source(item.providerId) ?: return
        if (!source.descriptor.serialized) return
        val chapter = remoteDetailChapters?.chapters?.firstOrNull {
            it.identity.canonicalChapterId == chapterId && it.canRead
        } ?: return
        val series = seriesIdentityFor(item)
        val routeChapter = chapter.copy(
            identity = chapter.identity.copy(series = series),
        )
        val chapterGeneration = remoteDetailChapters?.fetchedAtMillis ?: System.currentTimeMillis()
        val transition = remoteDetailChapters
            ?.onlineChapterTransitionPresentation(routeChapter.identity.canonicalChapterId)
        val storedInMap = remoteProgressMap[RemoteProgressPolicy.keyForChapter(series, chapterId)]
        val provisionalOrdinal = initialOrdinal ?: storedInMap?.ordinal ?: 0
        val provisionalReady = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val provisionalSession = OnlineReaderSession(
            title = routeChapter.label.ifBlank { item.title },
            imageUrls = listOf(""),
            initialOrdinal = 0,
            chapterContext = com.easemic.app.feature.reader.ReaderChapterContext(
                chapterId = routeChapter.identity.canonicalChapterId,
                chapterLabel = routeChapter.label.ifBlank { item.title },
            ),
            transition = transition,
            hasStoredProgress = true,
            resolveBlankPageUrl = {
                provisionalReady.await()
                ""
            },
        )
        remoteDetailBusy = false
        sayRemote(null)
        remoteReadProgressItem = null
        remoteReadProgressChapter = routeChapter.identity
        onlineReaderSession = provisionalSession
        coroutineScope.launch {
            val stored = withContext(Dispatchers.IO) {
                RemoteProgressPolicy
                    .keyForChapter(series, chapterId)
                    .let { remoteProgressStore.get(it) }
            } ?: storedInMap
            val resumeOrdinal = initialOrdinal ?: stored?.ordinal ?: provisionalOrdinal
            val chapterRoute = try {
                withContext(Dispatchers.IO) {
                    SerializedChapterRoute.prepare(
                        pageAdapter = source.pages,
                        chapter = routeChapter.identity,
                        title = routeChapter.label.ifBlank { item.title },
                        initialOrdinal = resumeOrdinal,
                    ) { ordinal ->
                        item.previewUrls.getOrNull(ordinal)?.takeIf { it.isNotBlank() }
                            ?: "${item.acquisitionUrl?.substringBefore("://")?.takeIf { it.isNotBlank() } ?: "chapter"}://${chapterId}/$ordinal"
                    }
                }
            } catch (cancelled: CancellationException) {
                provisionalReady.complete(false)
                throw cancelled
            } catch (_: Exception) {
                provisionalReady.complete(false)
                if (onlineReaderSession === provisionalSession) {
                    onlineReaderSession = null
                }
                remoteDetailBusy = false
                sayRemote("Couldn't reach ${source.descriptor.displayName}.")
                return@launch
            }
            if (onlineReaderSession !== provisionalSession) {
                provisionalReady.complete(false)
                return@launch
            }
            browseHistoryStore.record(
                HistoryEntry(
                    id = item.id,
                    title = item.title,
                    secondaryLine = item.secondaryLine,
                    coverUrl = item.coverUrl,
                    acquisitionUrl = item.acquisitionUrl,
                    providerId = item.providerId,
                    pageStreamUrl = item.pageStreamUrl,
                    pageCount = item.pageCount,
                    sourceName = historySourceNameFor(item.providerId, opdsProviders, item.upstreamAcquisitionUrl),
                    upstreamAcquisitionUrl = item.upstreamAcquisitionUrl,
                    lastReadChapterId = chapterId,
                    lastReadChapterLabel = routeChapter.label,
                    lastReadPageOrdinal = chapterRoute.initialOrdinal,
                    serialized = true,
                ),
            )
            historyEntries = browseHistoryStore.read()
            val session = withContext(Dispatchers.IO) {
                val cache = if (appGraph.downloadPageAdapter.prefersFileBackedOnlineRead(
                        chapterRoute.galleryIdentity, chapterRoute.locator,
                    )) appGraph.serializedPageFileCache else appGraph.serializedBytePageCache
                com.easemic.app.storage.serializedOnlinePageSession(
                    chapterRoute, appGraph.downloadPageAdapter, cache, chapterGeneration,
                )
            }.copy(
                transition = transition,
                hasStoredProgress = stored != null,
            )
            if (onlineReaderSession === provisionalSession) {
                remoteDetailBusy = false
                sayRemote(null)
                remoteReadProgressItem = null
                remoteReadProgressChapter = chapterRoute.chapter
                onlineReaderSession = session
            } else {
                session.onClose()
            }
            provisionalReady.complete(true)
        }
    }

    // F4a: the detail loader publishes its stages through this seam into the
    // app-root detail state (the vars screens already consume). The lambdas
    // bind this composition's state vars and local functions; the loader only
    // ever reads the open generation — the tag-search pushes below keep
    // invalidating it.
    val detailOpenStage = DetailOpenStage(
        openItem = { remoteDetailItem },
        openGeneration = { remoteDetailOpenGeneration },
        cloudBucketFingerprint = { cloudBucketFingerprint },
        libraryCloudCards = { libraryCloudCards },
        snapshot = { remoteDetailSnapshot },
        setSnapshot = { remoteDetailSnapshot = it },
        chapters = { remoteDetailChapters },
        setChapters = { remoteDetailChapters = it },
        metadataLoading = { remoteDetailMetadataLoading },
        setMetadataLoading = { remoteDetailMetadataLoading = it },
        chaptersUnavailable = { remoteDetailChaptersUnavailable },
        setChaptersUnavailable = { remoteDetailChaptersUnavailable = it },
        seriesManifest = { cloudReaderSeriesManifest },
        setSeriesManifest = { cloudReaderSeriesManifest = it },
        cloudChapterRows = { remoteDetailCloudChapterRows },
        setCloudChapterRows = { remoteDetailCloudChapterRows = it },
        sayRemote = { message, acquisition -> sayRemote(message, acquisition) },
        readSerializedChapter = { item, chapterId, initialOrdinal ->
            readSerializedChapter(item, chapterId, initialOrdinal)
        },
        applyZaimanhuaAccount = { updateZaimanhuaAccountIfChanged(it) },
        setPendingSerializedConfirmation = { item, chapterCount ->
            pendingSerializedConfirmation = PendingConfirmation(
                item = item,
                chapterCount = chapterCount,
                fromCollectTap = false,
            )
        },
        setOpenItem = { remoteDetailItem = it },
        setSeriesEmptyLine = { remoteDetailSeriesEmptyLine = it },
        setOriginAcquisition = { remoteDetailOriginAcquisition = it },
        setOriginProviderId = { remoteDetailOriginProviderId = it },
        setCloudStore = { remoteDetailCloudStore = it },
        setCloudPreviewInitialBusy = { cloudPreviewInitialBusy = it },
        productObjectStore = { productObjectStore() },
    )
    val detailLoader = DetailLoader(
        appGraph = appGraph,
        stage = detailOpenStage,
        coroutineScope = coroutineScope,
        remoteProgressStore = remoteProgressStore,
    )

    fun openRemoteDetail(
        item: BrowseFeedItem,
        @Suppress("UNUSED_PARAMETER") sourceFamily: DetailSourceFamily = DetailSourceFamily.FRONT_INDEX,
        serializedResume: HistoryReopenTarget? = null,
        entryOrigin: DetailEntryOrigin = DetailEntryOrigin.SOURCE,
    ) {
        val cloudIdForDetail = CloudCollectionFeedMapper.parseCloudComicId(item.acquisitionUrl)
        val cloudCardForDetail = cloudIdForDetail?.let { id ->
            libraryCloudCards.firstOrNull { it.cloudComicId == id }
        }
        val cloudSeriesDetail = cloudCardForDetail?.layoutKind == CloudCardLayout.SERIES
        val source = appGraph.comicSourceRegistry.source(item.providerId)
        if (source?.descriptor?.serialized == true || cloudSeriesDetail) {
            appGraph.clearNewChapterMark(
                if (cloudSeriesDetail) {
                    com.easemic.app.feature.download.SeriesIdentityV2(item.providerId, item.id)
                } else {
                    seriesIdentityFor(item)
                },
            )
        }
        remoteDetailSourceFamily = DetailSourceFamilyResolver.of(item)
        remoteDetailOpenGeneration += 1L
        val openGeneration = remoteDetailOpenGeneration
        if (source?.descriptor?.serialized == true || cloudSeriesDetail) {
            serializedReaderOriginRoute = route.takeIf {
                it == AppRoute.Browse || it == AppRoute.History || it == AppRoute.Library
            }
        } else {
            serializedReaderOriginRoute = null
        }
        remoteDetailItem = item
        // C34-c: drop the previous detail's store before this one resolves its
        // own. A store is bound to the bucket it was resolved from, so it must
        // never outlive the detail it belongs to and be handed to a reader
        // after a rebind. Re-set below once the preview path resolves one.
        remoteDetailCloudStore = null
        sayRemote(null)
        remoteDetailBusy = false
        remoteDetailMetadataLoading =
            DetailSourceFamilyResolver.of(item) != DetailSourceFamily.OPDS
        remoteDetailOriginAcquisition = null
        remoteDetailOriginProviderId = null
        remoteDetailChaptersUnavailable = false
        remoteDetailSeriesEmptyLine = null
        // Serialized detail opens stay out of History; the chapter read path
        // records the one-series row once a readable chapter is resolved.
        if (source?.descriptor?.serialized != true) {
            browseHistoryStore.record(
                HistoryEntry(
                    id = item.id,
                    title = item.title,
                    secondaryLine = item.secondaryLine,
                    coverUrl = item.coverUrl,
                    acquisitionUrl = item.acquisitionUrl,
                    providerId = item.providerId,
                    pageStreamUrl = item.pageStreamUrl,
                    pageCount = item.pageCount,
                    sourceName = historySourceNameFor(item.providerId, opdsProviders, item.upstreamAcquisitionUrl),
                    // A12: carry the upstream gallery acquisition so a cloud copy
                    // and the same gallery opened directly from Browse collapse
                    // into one history row (see HistoryEntry.upstreamAcquisitionUrl).
                    upstreamAcquisitionUrl = item.upstreamAcquisitionUrl,
                    serialized = false,
                ),
            )
        }
        historyEntries = browseHistoryStore.read()
        remoteDetailChapters = null
        remoteDetailSnapshot = item.seriesMetadata?.let(::snapshotFromListMetadata)
        remoteDetailCloudChapterRows = emptyList()
        cloudReaderSeriesManifest = null
        if (source?.descriptor?.serialized == true && !cloudSeriesDetail) {
            // F4a: the serialized open's stages (seed → stored → live) run in
            // DetailLoader; it captures this open's generation and publishes
            // each stage under the identity + generation guard.
            detailLoader.openSerialized(
                item = item,
                source = source,
                serializedResume = serializedResume,
                entryOrigin = entryOrigin,
            )
            return
        }
        val cloudId = cloudIdForDetail
        if (cloudId != null) {
            if (cloudSeriesDetail && cloudCardForDetail != null) {
                // F4b: the cloud-card series open's stages (the #E77 staged
                // merge — seed → stored → live) run in DetailLoader; it
                // captures this open's generation and publishes each stage
                // under the identity + generation guard. The meta.json path
                // below stays in the app root.
                detailLoader.openCloud(
                    item = item,
                    cloudCardForDetail = cloudCardForDetail,
                )
                return
            }
            coroutineScope.launch {
                var store: CloudCollectionStore? = null
                try {
                    // S4 Task 1 review (R2, M8 residual): thumbCount must be
                    // computed inside this SAME Dispatchers.IO block as
                    // downloadMeta — store.thumbCount does a synchronous
                    // network GET (pagesManifestOrNull -> downloadOrNull),
                    // and calling it back on the Main dispatcher after this
                    // withContext returns throws NetworkOnMainThreadException,
                    // which the old bare runCatching{}.getOrNull() silently
                    // swallowed, so the manifest-derived ceiling below could
                    // never actually execute in production. Logged (not just
                    // swallowed) on failure so a legacy/unreachable manifest
                    // is distinguishable from a real bug in logcat.
                    val loadedMeta = withContext(Dispatchers.IO) {
                        runCatching {
                            resolveCloudDetailMetadata(
                                bucketFingerprint = cloudBucketFingerprint,
                                cloudComicId = cloudId,
                                local = { fp, id -> appGraph.cloudMirrorStore.detail(fp, id) },
                                remote = { id ->
                                    val io = productObjectStore()
                                    val ready = (io as? ByoCloudIoResult.Ready)?.store
                                    ready?.let {
                                        CloudCollectionStore(it).also { resolved -> store = resolved }
                                            .downloadMeta(id)
                                    }
                                },
                            ) ?: error("CLOUD_DETAIL_METADATA_UNAVAILABLE")
                        }
                    }
                    // Fail closed: the unreadable-bucket message IS the locked
                    // copy, so it reaches Detail instead of a silent no-op.
                    val loadError = loadedMeta.exceptionOrNull()
                    if (sameRemoteItem(remoteDetailItem, item)) {
                        when (loadError) {
                            is NewerCloudFormatException ->
                                sayRemote(NewerCloudFormatException.MESSAGE)
                            is UnreadableCloudLibraryException ->
                                sayRemote(UnreadableCloudLibraryException.MESSAGE)
                            is LibraryLockedException ->
                                sayRemote(LibraryLockedException.MESSAGE)
                            is DamagedLibraryLockException ->
                                sayRemote(DamagedLibraryLockException.MESSAGE)
                            else -> Unit
                        }
                    }
                    val meta = loadedMeta.getOrNull() ?: return@launch
                    if (!sameRemoteItem(remoteDetailItem, item)) return@launch
                    remoteDetailItem = CloudCollectionFeedMapper.applyMeta(
                        item = item,
                        meta = meta,
                        coverUrl = item.coverUrl,
                    ).copy(previewUrls = emptyList())
                    remoteDetailOriginAcquisition = meta.acquisitionRef
                    remoteDetailOriginProviderId = meta.providerId
                    remoteDetailMetadataLoading = false
                    // A local mirror hit intentionally skips the remote
                    // `meta.json` read, so no CloudCollectionStore was
                    // created in that branch. Resolve the store separately
                    // for page previews; metadata remains usable when this
                    // second resolution is unavailable.
                    val previewStore = resolveCloudDetailPreviewResource(store) {
                        withContext(Dispatchers.IO) {
                            val io = productObjectStore()
                            (io as? ByoCloudIoResult.Ready)?.store?.let(::CloudCollectionStore)
                        }
                    }
                    if (previewStore == null) return@launch
                    // Covers both branches: the remote `meta.json` read's store
                    // and the one resolved here after a local-mirror metadata
                    // hit. Whichever it is, the previews below warm its manifest
                    // memo, so the reader opened from this detail must read
                    // through this instance (C34-c).
                    if (!sameRemoteItem(remoteDetailItem, item)) return@launch
                    remoteDetailCloudStore = previewStore
                    cloudPreviewInitialBusy = true
                    val thumbDir = CloudThumbCache.root(context.cacheDir)
                    // #16/C41: every preview ordinal reads its v3 grid thumb
                    // (`thumbs/<ordinal>`, #C5) — never the full page object.
                    val limit = PreviewPrefetchWindow.INITIAL_CELLS.coerceAtMost(meta.pageCount)
                    withContext(Dispatchers.IO) {
                        CloudDetailPreviewProgress.appendSuccesses(
                            seed = emptyList(),
                            ordinals = 0 until limit,
                            uriFor = { ordinal ->
                                runCatching {
                                    val bytes = cloudPreviewBytes(previewStore, cloudId, ordinal)
                                        ?: return@runCatching null
                                    CloudThumbCache.write(thumbDir, CloudThumbCache.cellFile(thumbDir, cloudId, ordinal).name, bytes, appGraph.cacheBudget)
                                }.getOrNull()
                            },
                            onPartial = { urls ->
                                withContext(Dispatchers.Main) {
                                    if (!sameRemoteItem(remoteDetailItem, item)) return@withContext
                                    remoteDetailItem = remoteDetailItem?.copy(
                                        previewUrls = urls,
                                        // C5 audit HIGH-2: the header cover is
                                        // the item's own cover (durable / cached
                                        // full-res page-0, resolved upstream by
                                        // coverUrlFromCacheOrNull) — NEVER the
                                        // strip's ordinal-0 cell, which since #C5
                                        // is a 320 px thumb; the old "the two are
                                        // meant to be the same image" fallback
                                        // predates #C5 and put a blur-up in the
                                        // header (and, through openRemoteDetail's
                                        // snapshot, into History).
                                        coverUrl = remoteDetailItem?.coverUrl,
                                    )
                                }
                            },
                            stopOnFailure = false,
                        )
                    }

                } finally {
                    cloudPreviewInitialBusy = false
                    if (sameRemoteItem(remoteDetailItem, item)) {
                        remoteDetailMetadataLoading = false
                    }
                }
            }
            return
        }
        val indexId = item.acquisitionUrl?.let(BrowseFeedRepository::parseNhentaiAcquisition)
        if (indexId != null) {
            coroutineScope.launch {
                val enriched = withContext(Dispatchers.IO) {
                    runCatching {
                        val plan = nhentaiPlanFor(
                            indexId = indexId,
                            beforeRequest = { CatalogRequestGate.Shared.acquire() },
                        )
                        NhentaiDetailEnrichment.apply(
                            item = item,
                            plan = plan,
                            previewUrls = plan.previewThumbUrls.take(PreviewPrefetchWindow.INITIAL_CELLS),
                        )
                    }.getOrNull()
                } ?: run {
                    if (sameRemoteItem(remoteDetailItem, item)) remoteDetailMetadataLoading = false
                    return@launch
                }
                if (!sameRemoteItem(remoteDetailItem, item)) return@launch
                remoteDetailItem = enriched
                remoteDetailMetadataLoading = false
            }
            return
        }
        // #18: an OPDS row carries a PSE page-stream template but no cloud/
        // nhentai/e-hentai acquisition, so it fell through every branch above and
        // its preview grid never populated (previewUrls stayed empty → every tile
        // shimmered forever). Materialize the first window of previews straight
        // from the page-streaming endpoint, exactly as the reader does.
        val opdsStream = item.pageStreamUrl?.trim().orEmpty()
        if (opdsStream.contains("{pageNumber}") && (item.pageCount ?: 0) > 0) {
            val opdsAuth = OpdsPseReadAuth.resolve(item.providerId, opdsProviders)
            if (opdsAuth is OpdsPseReadAuth.Ready) {
                opdsPreviewInitialBusy = true
                coroutineScope.launch {
                    try {
                        val count = item.pageCount ?: 0
                        val limit = PreviewPrefetchWindow.INITIAL_CELLS.coerceAtMost(count)
                        val pageUrls = OpdsPsePages.expand(opdsStream, count, maxWidth = 512)
                        val cacheRoot = File(context.cacheDir, "opds-detail-previews")
                        withContext(Dispatchers.IO) {
                            CloudDetailPreviewProgress.appendSuccesses(
                                seed = emptyList(),
                                ordinals = 0 until limit,
                                uriFor = { ordinal ->
                                    runCatching {
                                        OpdsPsePageFetch.fetchToCache(
                                            url = pageUrls[ordinal],
                                            httpGet = OpdsHttpGet { request ->
                                                OpdsCatalogClient.defaultGet(request)
                                            },
                                            authorizationHeader = opdsAuth.authorizationHeader,
                                            cacheRoot = cacheRoot,
                                            ordinal = ordinal,
                                            budget = appGraph.cacheBudget,
                                        )
                                    }.getOrNull()
                                },
                                onPartial = { urls ->
                                    withContext(Dispatchers.Main) {
                                        if (!sameRemoteItem(remoteDetailItem, item)) return@withContext
                                        remoteDetailItem = remoteDetailItem?.copy(previewUrls = urls)
                                    }
                                },
                                stopOnFailure = true,
                            )
                        }
                    } finally {
                        opdsPreviewInitialBusy = false
                        if (sameRemoteItem(remoteDetailItem, item)) remoteDetailMetadataLoading = false
                    }
                }
            } else {
                remoteDetailMetadataLoading = false
            }
            return
        }
        val frontRef = item.acquisitionUrl?.let(BrowseFeedRepository::parseEhentaiAcquisition)
        if (frontRef != null) {
            coroutineScope.launch {
                val account = ehentaiAccountStore.read()
                val lazyPlan = withContext(Dispatchers.IO) {
                    runCatching {
                        ehentaiLazyPlanFor(
                            galleryId = frontRef.first,
                            token = frontRef.second,
                            beforeRequest = { CatalogRequestGate.Shared.acquire() },
                        )
                    }.getOrNull()
                } ?: run {
                    if (sameRemoteItem(remoteDetailItem, item)) remoteDetailMetadataLoading = false
                    return@launch
                }
                if (!sameRemoteItem(remoteDetailItem, item)) return@launch
                val plan = lazyPlan.metadata
                remoteDetailItem = EhentaiDetailEnrichment.apply(
                    item = item,
                    plan = plan,
                    previewUrls = emptyList(),
                ).copy(
                    pageCount = lazyPlan.pageCount,
                    previewUrls = emptyList(),
                )
                remoteDetailMetadataLoading = false
                frontPreviewInitialBusy = true
                val thumbDir = File(context.cacheDir, "eh-detail-previews").also { it.mkdirs() }
                val cookie = account.normalizedCookie()
                val initialThumbs = withContext(Dispatchers.IO) {
                    runCatching {
                        lazyPlan.thumbsThrough(PreviewPrefetchWindow.INITIAL_CELLS, EhentaiClient.fromAccount(account))
                    }.getOrDefault(emptyList())
                }
                try {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            EhentaiPreviewMaterializer.materialize(
                                thumbs = initialThumbs,
                                fetchSheet = { sheetUrl ->
                                    CatalogRequestGate.Shared.acquire()
                                    val response = EhentaiClient.defaultGet(
                                        url = sheetUrl,
                                        cookieHeader = cookie,
                                    )
                                    if (response.code !in 200..299) {
                                        error("EHENTAI_PREVIEW_HTTP_${response.code}")
                                    }
                                    response.body
                                },
                                cropAndStore = { index, sheetBytes, thumb ->
                                    val jpeg = EhentaiPreviewMaterializer.cropSpriteToJpeg(
                                        sheetBytes = sheetBytes,
                                        thumb = thumb,
                                    ) ?: return@materialize null
                                    EhentaiPreviewMaterializer.writeJpegToCache(
                                        cacheDir = thumbDir,
                                        galleryId = plan.galleryId,
                                        index = index,
                                        jpeg = jpeg,
                                        budget = appGraph.cacheBudget,
                                    )
                                },
                                limit = PreviewPrefetchWindow.INITIAL_CELLS,
                                onPartial = { urls ->
                                    withContext(Dispatchers.Main) {
                                        if (!sameRemoteItem(remoteDetailItem, item)) return@withContext
                                        remoteDetailItem = remoteDetailItem?.copy(
                                            previewUrls = urls,
                                        )
                                    }
                                },
                            )
                        }
                    }
                } finally {
                    frontPreviewInitialBusy = false
                }
            }
        } else {
            remoteDetailMetadataLoading = false
        }
    }

    fun restoreBrowseSnapshot(snapshot: TagSearchBrowseSnapshot) {
        browsePrefs = snapshot.prefs
        browseFilterStates = snapshot.filterStates
        browseQuery = snapshot.query
        jmSearchSession = snapshot.jmSearchSession
        browseFeedUrl = snapshot.feedUrl
        browseFeedProviderId = snapshot.feedProviderId
        browseSourceListVisible = snapshot.sourceListVisible
        browseLoadedKey = snapshot.loadedKey
        browseResults = snapshot.results
        browseNextContinuation = snapshot.nextContinuation
        browseKnownTotalItems = snapshot.knownTotalItems
        browseLoadMoreError = snapshot.loadMoreError
        browseError = snapshot.error
        browseFeedNotice = snapshot.notice
        coroutineScope.launch {
            snapshot.scroll?.let { browseListState.scrollToItem(it.first, it.second) }
        }
    }

    fun restoreDetailSurface(snapshot: TagSearchDetailSnapshot) {
        when (snapshot) {
            is TagSearchDetailSnapshot.Local -> {
                remoteDetailItem = null
                remoteDetailCloudStore = null
                serializedReaderOriginLayerToken = snapshot.token
                route = snapshot.route
            }
            is TagSearchDetailSnapshot.Remote -> {
                route = snapshot.route
                openRemoteDetail(snapshot.item)
                // openRemoteDetail derives this from the temporary Browse
                // route. Restore the captured origin facts after its fresh
                // generation has been allocated.
                remoteDetailOriginAcquisition = snapshot.originAcquisition
                remoteDetailOriginProviderId = snapshot.originProviderId
                serializedReaderOriginRoute = snapshot.serializedReaderOriginRoute
                serializedReaderOriginLayerToken = snapshot.token
            }
        }
    }

    fun popTagSearchSurface() {
        val top = tagSearchStack.top() ?: return
        remoteDetailOpenGeneration += 1L
        tagSearchStack = tagSearchStack.pop()
        when (top) {
            is TagSearchSurface.Detail -> {
                val restored = tagSearchStack.top()
                if (restored is TagSearchSurface.Results) {
                    // Reveal the layer's own captured session; rebuilding from
                    // the root snapshot would load the root's query instead.
                    transientBrowseSession = restored.transient.copy(loading = false, loadingMore = false)
                    remoteDetailItem = null
                    remoteDetailCloudStore = null
                    sayRemote(null)
                    route = AppRoute.Browse
                } else if (tagSearchStack.surfaces.isEmpty()) {
                    // The root detail frame has already been revealed. This
                    // is the next Back: close it through the normal detail
                    // semantics while dropping the stack token.
                    transientBrowseSession = null
                    remoteDetailItem = null
                    remoteDetailCloudStore = null
                    sayRemote(null)
                    serializedReaderOriginLayerToken = null
                } else {
                    transientBrowseSession = null
                    restoreDetailSurface(top.snapshot)
                }
            }
            is TagSearchSurface.Results -> {
                transientBrowseSession = null
                val restored = tagSearchStack.top()
                if (restored is TagSearchSurface.Detail) {
                    if (tagSearchStack.surfaces.none { it is TagSearchSurface.Results }) {
                        restoreBrowseSnapshot(top.browse)
                    }
                    restoreDetailSurface(restored.snapshot)
                } else {
                    tagSearchStack = TagSearchStack()
                }
            }
        }
        if (tagSearchStack.surfaces.isEmpty()) {
            serializedReaderOriginLayerToken = null
        }
    }

    fun openTagSearchResult(item: BrowseFeedItem) {
        val top = tagSearchStack.top()
        if (top !is TagSearchSurface.Results) return
        val token = "remote-${remoteDetailOpenGeneration + 1L}"
        // Freeze the live layer session into the Results surface it belongs to,
        // so Back from this detail can reveal the same rows and query.
        transientBrowseSession?.let { live ->
            val frozen = live.copy(loading = false, loadingMore = false)
            tagSearchStack = TagSearchStack(tagSearchStack.surfaces.dropLast(1) + top.copy(transient = frozen))
        }
        tagSearchStack = tagSearchStack.pushResultDetail(
            TagSearchDetailSnapshot.Remote(
                token = token,
                item = item,
                route = AppRoute.Browse,
                originAcquisition = item.acquisitionUrl,
                originProviderId = item.providerId,
                serializedReaderOriginRoute = route,
            ),
        )
        transientBrowseSession = null
        route = AppRoute.Browse
        openRemoteDetail(item)
        serializedReaderOriginLayerToken = token
    }

    LaunchedEffect(zaimanhuaAccountChangeEpoch) {
        if (zaimanhuaAccountChangeEpoch == 0) return@LaunchedEffect
        invalidateBrowseFeed()
        browseLoadedKey = null
        browseRecoveryEpoch += 1
        remoteDetailItem
            ?.takeIf { it.providerId == GalleryIdentityV1.ZAIMANHUA }
            ?.let(::openRemoteDetail)
    }

    fun warmRemoteDetailPreviews(endExclusive: Int) {
        val current = remoteDetailItem ?: return
        val validSeed = current.previewUrls.takeWhile(::isReadableCacheUri)
        if (validSeed.size != current.previewUrls.size) {
            remoteDetailItem = current.copy(previewUrls = validSeed)
        }
        if (endExclusive <= validSeed.size) return
        val cloudId = CloudCollectionFeedMapper.parseCloudComicId(current.acquisitionUrl)
        if (cloudId != null) {
            if (cloudPreviewInitialBusy) return
            coroutineScope.launch {
                // #C45 D3: reuse the detail's instance — it holds the pages-manifest
                // memo the initial walk already paid for; a fresh instance re-GETs
                // pages.manifest.json right under the reader's first page.
                // C34-c: this walk can outlive the detail that started it, and a
                // closed detail must never keep a bucket-bound instance alive —
                // give up before resolving, and write back only while current.
                if (!sameRemoteItem(remoteDetailItem, current)) return@launch
                // C1 audit HIGH-2: the cells seeded from disk are about to be shown
                // again — touch them so the budget's oldest-first sweep treats them
                // as recently used (the write set their mtime once; reads never did).
                withContext(Dispatchers.IO) { validSeed.forEach { CloudThumbCache.touchUri(it) } }
                val store = remoteDetailCloudStore ?: run {
                    val io = productObjectStore()
                    if (io !is ByoCloudIoResult.Ready) return@launch
                    CloudCollectionStore(io.store).also { if (sameRemoteItem(remoteDetailItem, current)) remoteDetailCloudStore = it }
                }
                // S4 Task 1 review (R2, M8 residual): thumbCount must be
                // computed inside this SAME Dispatchers.IO block as
                // downloadMeta — see the first Detail-open call site's
                // identical comment for why a separate/later call would
                // silently NetworkOnMainThreadException-and-swallow.
                val meta = withContext(Dispatchers.IO) {
                    runCatching { store.downloadMeta(cloudId) }.getOrNull()
                } ?: return@launch
                val thumbDir = CloudThumbCache.root(context.cacheDir)
                // #16/C41: every preview ordinal reads its v3 grid thumb
                // (`thumbs/<ordinal>`, #C5) — never the full page object.
                val target = minOf(endExclusive, meta.pageCount)
                val seed = remoteDetailItem?.previewUrls ?: validSeed
                if (target <= seed.size) return@launch
                withContext(Dispatchers.IO) {
                    CloudDetailPreviewProgress.appendSuccesses(
                        seed = seed,
                        ordinals = seed.size until target,
                        uriFor = { ordinal ->
                            runCatching {
                                val bytes = cloudPreviewBytes(store, cloudId, ordinal)
                                    ?: return@runCatching null
                                CloudThumbCache.write(thumbDir, CloudThumbCache.cellFile(thumbDir, cloudId, ordinal).name, bytes, appGraph.cacheBudget)
                            }.getOrNull()
                        },
                        onPartial = { urls ->
                            withContext(Dispatchers.Main) {
                                if (!sameRemoteItem(remoteDetailItem, current)) return@withContext
                                if (urls.size <= (remoteDetailItem?.previewUrls?.size ?: 0)) {
                                    return@withContext
                                }
                                remoteDetailItem = remoteDetailItem?.copy(previewUrls = urls)
                            }
                        },
                        stopOnFailure = false,
                        isUsableUri = { it == CloudDetailPreviewProgress.MISSING_URI || isReadableCacheUri(it) },
                    )
                }
            }
            return
        }
        val indexId = current.acquisitionUrl?.let(BrowseFeedRepository::parseNhentaiAcquisition)
        if (indexId != null) {
            coroutineScope.launch {
                val extended = withContext(Dispatchers.IO) {
                    runCatching {
                        val plan = NhentaiClient(
                            beforeRequest = { CatalogRequestGate.Shared.acquire() },
                        ).fetchGallery(indexId)
                        plan.previewThumbUrls.take(endExclusive)
                    }.getOrNull()
                } ?: return@launch
                if (!sameRemoteItem(remoteDetailItem, current)) return@launch
                if (extended.size <= (remoteDetailItem?.previewUrls?.size ?: 0)) return@launch
                remoteDetailItem = remoteDetailItem?.copy(previewUrls = extended)
            }
            return
        }
        val opdsStream = current.pageStreamUrl?.trim().orEmpty()
        if (opdsStream.contains("{pageNumber}") && (current.pageCount ?: 0) > 0) {
            if (opdsPreviewInitialBusy) return
            val opdsAuth = OpdsPseReadAuth.resolve(current.providerId, opdsProviders)
            if (opdsAuth !is OpdsPseReadAuth.Ready) return
            coroutineScope.launch {
                val count = current.pageCount ?: 0
                val seed = remoteDetailItem?.previewUrls ?: validSeed
                val target = endExclusive.coerceAtMost(count)
                if (target <= seed.size) return@launch
                val pageUrls = OpdsPsePages.expand(opdsStream, count, maxWidth = 512)
                val cacheRoot = File(context.cacheDir, "opds-detail-previews")
                withContext(Dispatchers.IO) {
                    CloudDetailPreviewProgress.appendSuccesses(
                        seed = seed,
                        ordinals = seed.size until target,
                        uriFor = { ordinal ->
                            runCatching {
                                OpdsPsePageFetch.fetchToCache(
                                    url = pageUrls[ordinal],
                                    httpGet = OpdsHttpGet { request ->
                                        OpdsCatalogClient.defaultGet(request)
                                    },
                                    authorizationHeader = opdsAuth.authorizationHeader,
                                    cacheRoot = cacheRoot,
                                    ordinal = ordinal,
                                    budget = appGraph.cacheBudget,
                                )
                            }.getOrNull()
                        },
                        onPartial = { urls ->
                            withContext(Dispatchers.Main) {
                                if (!sameRemoteItem(remoteDetailItem, current)) return@withContext
                                if (urls.size <= (remoteDetailItem?.previewUrls?.size ?: 0)) {
                                    return@withContext
                                }
                                remoteDetailItem = remoteDetailItem?.copy(previewUrls = urls)
                            }
                        },
                        stopOnFailure = true,
                        isUsableUri = ::isReadableCacheUri,
                    )
                }
            }
            return
        }
        val frontRef = current.acquisitionUrl?.let(BrowseFeedRepository::parseEhentaiAcquisition)
            ?: return
        if (frontPreviewInitialBusy) return
        coroutineScope.launch {
            val account = ehentaiAccountStore.read()
            val lazyPlan = withContext(Dispatchers.IO) {
                runCatching {
                    ehentaiLazyPlanFor(
                        galleryId = frontRef.first,
                        token = frontRef.second,
                        beforeRequest = { CatalogRequestGate.Shared.acquire() },
                    )
                }.getOrNull()
            } ?: return@launch
            val plan = lazyPlan.metadata
            val thumbDir = File(context.cacheDir, "eh-detail-previews").also { it.mkdirs() }
            val cookie = account.normalizedCookie()
            val seed = remoteDetailItem?.previewUrls ?: validSeed
            if (endExclusive <= seed.size) return@launch
            val thumbs = withContext(Dispatchers.IO) {
                runCatching { lazyPlan.thumbsThrough(endExclusive, EhentaiClient.fromAccount(account)) }.getOrNull()
            } ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    EhentaiPreviewMaterializer.materialize(
                        thumbs = thumbs,
                        fetchSheet = { sheetUrl ->
                            CatalogRequestGate.Shared.acquire()
                            val response = EhentaiClient.defaultGet(
                                url = sheetUrl,
                                cookieHeader = cookie,
                            )
                            if (response.code !in 200..299) {
                                error("EHENTAI_PREVIEW_HTTP_${response.code}")
                            }
                            response.body
                        },
                        cropAndStore = { index, sheetBytes, thumb ->
                            val jpeg = EhentaiPreviewMaterializer.cropSpriteToJpeg(
                                sheetBytes = sheetBytes,
                                thumb = thumb,
                            ) ?: return@materialize null
                            EhentaiPreviewMaterializer.writeJpegToCache(
                                cacheDir = thumbDir,
                                galleryId = plan.galleryId,
                                index = index,
                                jpeg = jpeg,
                                budget = appGraph.cacheBudget,
                            )
                        },
                        limit = endExclusive,
                        seed = seed,
                        onPartial = { urls ->
                            withContext(Dispatchers.Main) {
                                if (!sameRemoteItem(remoteDetailItem, current)) return@withContext
                                if (urls.size <= (remoteDetailItem?.previewUrls?.size ?: 0)) {
                                    return@withContext
                                }
                                remoteDetailItem = remoteDetailItem?.copy(previewUrls = urls)
                            }
                        },
                        isUsableUri = ::isReadableCacheUri,
                    )
                }
            }
        }
    }

    fun peekedCloudIdFor(item: BrowseFeedItem): String? =
        listOfNotNull(
            item.acquisitionUrl,
            item.upstreamAcquisitionUrl,
            remoteDetailOriginAcquisition,
        ).firstNotNullOfOrNull { url -> cloudComicIdStore.peek(url) }

    fun cloudCardMatchesItem(card: CloudCollectionCard, item: BrowseFeedItem): Boolean =
        cloudItemMatches(
            card = card,
            item = item,
            upstream = item.upstreamAcquisitionUrl ?: remoteDetailOriginAcquisition,
            peekedCloudId = peekedCloudIdFor(item),
        )

    fun listedCloudMatchesItem(listed: BrowseFeedItem, item: BrowseFeedItem): Boolean {
        val listedId = CloudCollectionFeedMapper.parseCloudComicId(listed.acquisitionUrl) ?: return false
        return cloudCardMatchesItem(
            CloudCollectionCard(
                cloudComicId = listedId,
                title = listed.title,
                coverThumbObjectKey = "",
                collectedAtEpochMs = 0L,
                acquisitionRef = listed.upstreamAcquisitionUrl,
                layoutKind = if (CloudCollectionFeedMapper.isSeries(listed)) CloudCardLayout.SERIES else CloudCardLayout.SINGLE_CHAPTER,
                providerId = listed.providerId,
                providerSeriesId = listed.id,
            ),
            item,
        )
    }


    fun syncExhentaiFavoriteIfEnabled(
        item: BrowseFeedItem,
        favcat: String,
        fallbackAcquisitionUrls: List<String?> = emptyList(),
    ) {
        if (!exhentaiFavoritesSyncStore.isEnabled()) return
        val account = ehentaiAccountStore.read()
        if (!account.effectiveUseExhentai()) return
        val candidates = listOfNotNull(
            item.upstreamAcquisitionUrl,
            item.acquisitionUrl,
            remoteDetailOriginAcquisition,
        ) + fallbackAcquisitionUrls.filterNotNull()
        val parsed = candidates.firstNotNullOfOrNull { BrowseFeedRepository.parseEhentaiAcquisition(it) }
            ?: return
        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                EhentaiClient.fromAccount(account).updateFavorite(
                    galleryId = parsed.first,
                    token = parsed.second,
                    favcat = favcat,
                )
            }.onFailure {
                DiagLog.w("ExhentaiFavSync", "updateFavorite failed gid=${parsed.first} favcat=$favcat")
            }
        }
    }

    fun collectBookmark(item: BrowseFeedItem, syncCloud: Boolean = true, announce: Boolean = true, syncExhentaiFavorite: Boolean = true) {
        val acquisition = item.acquisitionUrl ?: return
        val serializedSource = appGraph.comicSourceRegistry.source(item.providerId)
            ?.takeIf { it.descriptor.serialized }
        // K2: stamp every outcome with the comic it belongs to, including the
        // ones written from the ingest coroutine long after this call returned.
        fun say(message: String) = sayRemote(message, acquisition = acquisition)
        // Owner 2026-09-09: OPDS is a first-class source — its comics collect to
        // the cloud like E-Hentai's. An http(s) acquisition resolves to an
        // opds:<cfg> identity below, so it takes the fetch-job route (download
        // pump pulls + extracts, SEALED hands local pages to the upload).
        collectBookmarkStore.upsert(
            CollectedBookmark(
                id = item.id,
                title = item.title,
                acquisitionUrl = acquisition,
                providerId = item.providerId,
                coverUrl = item.coverUrl,
                feedUrl = item.feedUrl,
                serialized = serializedSource != null,
            ),
        )
        collectBookmarkEpoch += 1
        if (syncExhentaiFavorite && announce && serializedSource == null) {
            syncExhentaiFavoriteIfEnabled(item, favcat = EhentaiClient.FAVORITE_SLOT_DEFAULT)
        }
        // The serialized collector owns series creation, chapter reconciliation
        // and per-chapter dual-intent enqueueing; branch before gallery
        // preflight while allowing chapter rows to use the cloud writer path.
        if (serializedSource != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val result = appGraph.collectSerializedSeries(item)
                when (result) {
                    // Only a user's own Collect asks; background re-collects never raise a dialog.
                    is SerializedCollectResult.NeedsConfirmation -> if (announce) {
                        withContext(Dispatchers.Main) {
                            pendingSerializedConfirmation = PendingConfirmation(
                                item = item,
                                chapterCount = result.chapterCount,
                                fromCollectTap = true,
                            )
                        }
                    }
                    is SerializedCollectResult.Blocked -> if (announce) {
                        say("Could not collect chapters: ${result.reason}")
                    }
                    is SerializedCollectResult.Collected -> Unit
                }
            }
            return
        }
        // A45: dismissal suppresses one failed attempt, not the comic. Re-
        // collecting is an explicit new user action, so it clears the flag and
        // the row is allowed to queue again. The catch-up sweep cannot reach
        // here for a dismissed bookmark (a dismissal makes catchUpSweepAction
        // return SKIP), so this only ever fires on a real re-collect; for an
        // undismissed id it is a no-op.
        appGraph.cloudSyncDismissalStore.restore(acquisition)
        if (!syncCloud) {
            return
        }
        // #C22 audit HIGH-2: a background-ingest-registry liveness check used
        // to early-return here — BEFORE any placeholder existed. It
        // accepted the tap (bookmark upserted, epoch bumped, dismissal cleared)
        // and returned with no Activity row and no message, because it assumed
        // a row was already on screen. The registered Job and the queue row have
        // different lifetimes (RESTORE/SKIP_INGEST remove the row from inside
        // the still-running block), so that assumption was violable. The
        // placeholder itself is the dedup the tap needs: `beginPreflight`
        // refuses a live row and `enqueueCloudCollectPreflight` returns null,
        // which falls through to the bare pump kick at the end of this function.
        when (val io = productObjectStore()) {
            is ByoCloudIoResult.Blocked -> {
                if (announce) {
                    say("Collected (bookmark only). ${io.message}")
                }
                return
            }
            is ByoCloudIoResult.Ready -> {
                val memoryAction = CloudCollectLifecycle.collectAction(
                    hasActive = libraryCloudItems.any { listedCloudMatchesItem(it, item) },
                    hasTrash = libraryCloudTrashCards.any { cloudCardMatchesItem(it, item) },
                )
                if (announce) {
                    // A54: INGEST is silent — the upload's own icon states are
                    // the feedback. Only outcomes that HAVE already landed by
                    // the time of this tap announce: RESTORE (a trash copy came
                    // back) and SKIP_INGEST (the cloud already has it).
                    when (memoryAction) {
                        CloudCollectAction.INGEST -> Unit
                        CloudCollectAction.RESTORE, CloudCollectAction.SKIP_INGEST ->
                            say("Collected.")
                    }
                }
                val resolvedIdentity = (
                    BrowseGalleryIdentity.resolve(
                        item = item,
                        originAcquisitionUrl = remoteDetailOriginAcquisition,
                        originProviderId = remoteDetailOriginProviderId,
                    ) as? BrowseGalleryIdentityResult.Resolved
                    )?.identity
                val resolvedLocator = item.toDownloadLocator(acquisition)
                val upstreamAtCollect = item.upstreamAcquisitionUrl ?: remoteDetailOriginAcquisition
                val peekedAtCollect = peekedCloudIdFor(item)
                // C22: accept the user's Collect intent before any cloud
                // trash/index read. The placeholder is durable and visible in
                // Activity immediately; the worker remains gated by the bit
                // until the preflight chooses RESTORE, SKIP, or INGEST.
                val preflight = appGraph.enqueueCloudCollectPreflight(
                    row = CloudSyncQueueItem(
                        id = acquisition,
                        title = item.title,
                        coverUrl = item.coverUrl,
                        stage = CloudSyncStage.WAITING,
                        readyPages = 0,
                        expectedPages = item.pageCount?.takeIf { it > 0 },
                        providerId = resolvedIdentity?.providerNamespace,
                        canonicalItemId = resolvedIdentity?.canonicalItemId,
                    ),
                    onFailure = {
                        // Includes an unexpected handoff/store failure. Keep
                        // the bookmark without crashing the Activity.
                        // Re-review r1 BLOCKING-1: this lambda is where EVERY
                        // exception the catch arms below do NOT name lands — an
                        // upsertEnqueue SQLiteFullException, putKeepingCatalogFacts,
                        // the RESTORE branch, cloudComicIdStore. Park the
                        // placeholder FIRST: the completion hook retires it the
                        // moment this returns, and the tap would leave no
                        // Activity row and no lasting message at all.
                        // Re-review r2 L2/L3: a reason CODE, never raw exception
                        // text — and the row and toast now say the same thing.
                        cloudSyncQueue.failPreflight(acquisition, CollectPreflightStatus.rowMessage(CollectPreflightStatus.reasonCode(it)))
                        DiagLog.w("CloudSync", "collect handoff failed")
                        if (announce) say(CollectPreflightStatus.checkFailed(CollectPreflightStatus.reasonCode(it)))
                    },
                ) preflight@{
                    resolvedIdentity?.let { identity ->
                        appGraph.downloadLocatorStore.putKeepingCatalogFacts(identity, resolvedLocator)
                    }
                    val ingestJob = coroutineContext[Job]
                    if (ingestJob != null &&
                        !backgroundIngestRegistry.register(acquisition, ingestJob)
                    ) {
                        // Another ingest already owns this acquisition.
                        // Re-review r1 BLOCKING-1: this is NOT the ordinary
                        // double-tap (beginPreflight already refused that, and
                        // the tap fell through to the bare kick). Reaching here
                        // means a registered ingest is live while NO queue row
                        // exists — the RESTORE / SKIP_INGEST window, where the
                        // still-running block removed the row from under us. A
                        // bare return retires the placeholder and leaves the tap
                        // with nothing on screen; park it as a Retry row instead
                        // (RECOLLECT re-runs cleanly once the registry entry
                        // completes).
                        // Re-review r2 MEDIUM-4: the bail changed nothing, so a
                        // reason the replaced FAILED row already carried wins over
                        // the generic key.
                        cloudSyncQueue.failPreflightKeepingPriorReason(acquisition, fallback = CollectPreflightStatus.rowMessage("CLOUD_COLLECT_BUSY"))
                        return@preflight
                    }
                    val action = try {
                        withContext(Dispatchers.IO) {
                            val store = CloudCollectionStore(io.store)
                            val peeked = peekedAtCollect
                            val cloudIdFromUrl = CloudCollectionFeedMapper.parseCloudComicId(acquisition)
                            val upstream = upstreamAtCollect
                            // Durable per-comic sync ledger (S3 Task 1, owner
                            // bug C3/A3): if this comic is already recorded as
                            // synced to the CURRENT bucket, skip the live-list
                            // decision entirely and treat it as SKIP_INGEST — a
                            // known-synced comic must never be re-uploaded, even
                            // if a (transiently incomplete) listAllIndexCards
                            // read would otherwise miss it and route to INGEST.
                            val ledgerKey = galleryDedupKey(acquisition)
                                ?: upstream?.let { galleryDedupKey(it) }
                            val knownSynced = ledgerKey != null &&
                                appGraph.currentBucketFingerprintOrNull()?.let { fp ->
                                    appGraph.cloudSyncLedgerStore.isSynced(fp, ledgerKey)
                                } == true
                            fun matches(card: CloudCollectionCard) = CloudCollectLifecycle.cardMatches(
                                card = card,
                                acquisitionUrl = acquisition,
                                upstreamAcquisitionUrl = upstream,
                                peekedCloudId = peeked,
                                cloudIdFromUrl = cloudIdFromUrl,
                            )
                            // C35-d: the decision is pure and costs exactly one
                            // active-index listing plus one trash listing. It
                            // deliberately cannot purge expired trash — that
                            // used to run right here, ahead of everything, and
                            // charged the tap a listing plus a discardComic
                            // (2N+2 deletes) per expired card under
                            // INDEX_MUTATION_LOCK. It is now AppGraph's
                            // best-effort foreground sweep.
                            val decision = CloudCollectPreflightDecision(store)
                                .decide(matches = ::matches, knownSynced = knownSynced)
                            when (decision) {
                                is CloudCollectPreflightDecision.Decision.Restore -> {
                                    // SF-4 (C35 audit): the decision came from a
                                    // listing that can already be stale (another
                                    // device purged the card, or it was
                                    // discarded). Un-trashing the mirror row and
                                    // reporting "Collected." on a card that is
                                    // not there leaves a phantom: nothing was
                                    // restored AND nothing was uploaded.
                                    if (store.restoreComic(decision.cloudComicId)) {
                                        // B4c live update (loyal-pipelines Slice B §2):
                                        // un-trash the mirror row in the same act, so
                                        // the shelf shows it again without restart.
                                        appGraph.currentBucketFingerprintOrNull()?.let { fp ->
                                            appGraph.cloudMirrorStore.setTrashed(fp, decision.cloudComicId, null)
                                        }
                                        CloudCollectAction.RESTORE
                                    } else {
                                        DiagLog.i(
                                            "CloudSync",
                                            "restore found no trash card id=${decision.cloudComicId} — ingesting instead",
                                        )
                                        CloudCollectAction.INGEST
                                    }
                                }
                                CloudCollectPreflightDecision.Decision.SkipIngest ->
                                    CloudCollectAction.SKIP_INGEST
                                CloudCollectPreflightDecision.Decision.Ingest ->
                                    CloudCollectAction.INGEST
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: NewerCloudFormatException) {
                        cloudSyncQueue.failPreflight(acquisition, CollectPreflightStatus.rowMessage(NewerCloudFormatException.MESSAGE))
                        if (announce) {
                            say(NewerCloudFormatException.MESSAGE)
                        }
                        return@preflight
                    } catch (_: UnreadableCloudLibraryException) {
                        // Fail closed: surface the locked copy verbatim — and
                        // leave the row (#C22 BLOCKING-1), so the tap is visible.
                        cloudSyncQueue.failPreflight(acquisition, CollectPreflightStatus.rowMessage(UnreadableCloudLibraryException.MESSAGE))
                        if (announce) {
                            say(UnreadableCloudLibraryException.MESSAGE)
                        }
                        return@preflight
                    } catch (_: LibraryLockedException) {
                        cloudSyncQueue.failPreflight(acquisition, CollectPreflightStatus.rowMessage(LibraryLockedException.MESSAGE))
                        if (announce) {
                            say(LibraryLockedException.MESSAGE)
                        }
                        return@preflight
                    } catch (_: DamagedLibraryLockException) {
                        cloudSyncQueue.failPreflight(acquisition, CollectPreflightStatus.rowMessage(DamagedLibraryLockException.MESSAGE))
                        if (announce) {
                            say(DamagedLibraryLockException.MESSAGE)
                        }
                        return@preflight
                    } catch (error: Exception) {
                        // Nothing was uploaded: the pre-flight read failed, so
                        // "cloud sync failed" would claim an attempt that never
                        // happened. #C22 BLOCKING-1: but the tap must stay
                        // visible — park the placeholder as a terminal attention
                        // row (Retry re-runs this preflight, see onRetrySync)
                        // instead of retiring it and leaving nothing until the
                        // next cold start's catch-up sweep re-collects it.
                        DiagLog.w("CloudSync", "collect pre-flight failed")
                        // Re-review r2 L2/L3: a reason CODE, never raw exception
                        // text — and the row and toast now say the same thing.
                        cloudSyncQueue.failPreflight(acquisition, CollectPreflightStatus.rowMessage(CollectPreflightStatus.reasonCode(error)))
                        if (announce) {
                            say(CollectPreflightStatus.checkFailed(CollectPreflightStatus.reasonCode(error)))
                        }
                        return@preflight
                    }
                    when (action) {
                        CloudCollectAction.RESTORE -> {
                            // Still drives the trash pass (a restore frees a
                            // trash slot) and any other epoch-keyed consumer —
                            // the active shelf itself no longer needs it since
                            // the mirror's setTrashed(..., null) call above
                            // already made Room's observeActiveCards Flow pick
                            // this card back up live.
                            libraryCloudEpoch += 1
                            // T3d (owner bug A3/C5): a prior INGEST attempt on
                            // this same acquisition may have left a stale
                            // FAILED queue row (e.g. it failed, then the user
                            // discarded and re-collected, hitting RESTORE off
                            // the trash instead). Nothing will ever re-drive
                            // that zombie row to completion — clear it so the
                            // sync sheet doesn't show a "retrying
                            // automatically" row that never actually retries.
                            cloudSyncQueue.remove(acquisition)
                            if (announce) {
                                say("Collected.")
                            }
                        }
                        CloudCollectAction.SKIP_INGEST -> {
                            // T3d (owner bug A3/C5): same zombie-row hazard as
                            // RESTORE above — this acquisition is already
                            // present cloud-side, so any leftover FAILED
                            // queue row from an earlier attempt is stale.
                            cloudSyncQueue.remove(acquisition)
                            if (announce) {
                                say("Collected.")
                            }
                        }
                        CloudCollectAction.INGEST -> {
                            // A54: no toast — the enqueued upload's own icon
                            // states (filled cloud switch, Activity's live
                            // sync section) are the feedback; the old
                            // "Syncing to cloud…" narrated a success that
                            // had not happened yet.
                            // Decouple fetch from upload (spec §1.1): when this
                            // acquisition maps to a fetchable identity, hand it
                            // to the download pump instead of queuing a direct
                            // R2 upload — the upload row is enqueued later by
                            // AppGraph's SEALED handoff once that job's pages
                            // are fully on device. An identity that can't be
                            // resolved (e.g. an OPDS http acquisition
                            // OpdsCloudIngestGuard already refuses fetch-pipeline
                            // routing for) keeps today's direct-to-R2 behavior.
                            val route = CollectCloudIngestHandoff.handle(
                                identity = resolvedIdentity,
                                // A32 §2: same builder as the download enqueue —
                                // the store is last-write-wins, so a Collect that
                                // rebuilt this by hand erased the row's tags.
                                locator = resolvedLocator,
                                expectedPageCount = item.pageCount?.takeIf { it > 0 },
                                // S3 review L2: the sweep and the Local Detail
                                // collect switch reach here with a fact-less
                                // item; a plain put would erase the tags an
                                // enqueue already recorded for this identity.
                                putLocator = { id, loc ->
                                    appGraph.downloadLocatorStore.putKeepingCatalogFacts(id, loc)
                                },
                                upsertEnqueue = { id, count, cloud, local ->
                                    appGraph.downloadJobStore.upsertEnqueue(
                                        identity = id,
                                        expectedPageCount = count,
                                        intentCloud = cloud,
                                        intentLocal = local,
                                    )
                                },
                                kickDownloadPump = { DownloadPumpWorker.kick(context) },
                                isResolvableForCloudHandoff = appGraph::isJobResolvableForDirectCloudHandoff,
                                // SF-2: THIS is the tap — the user is waiting on
                                // it right now, so it preempts a pump sitting on
                                // a scheduled delay. Spelled out rather than a
                                // method reference: the argument is what decides
                                // whether a kick can cancel an in-flight upload.
                                fireCloudHandoffDirectly = { job ->
                                    appGraph.enqueueCloudUploadForJob(job, userInitiated = true)
                                },
                            )
                            if (route is CollectCloudIngestRoute.TerminalJobNotResolvable) {
                                // #C22 HIGH-1: the comic is on device but its pages
                                // cannot be handed to the upload (a hole, no page
                                // count, a released tree). Nothing downstream will
                                // ever act on it — say so where the user looks.
                                DiagLog.w("CloudSync", "collect: terminal job not resolvable for upload job=${route.job.id}")
                                cloudSyncQueue.failPreflight(acquisition, CollectPreflightStatus.LOCAL_PAGES_INCOMPLETE)
                                return@preflight
                            }
                            if (route is CollectCloudIngestRoute.LegacyDirectUpload) {
                                val row = CloudSyncQueueItem(
                                    id = acquisition,
                                    title = item.title,
                                    coverUrl = item.coverUrl,
                                    stage = CloudSyncStage.WAITING,
                                    readyPages = 0,
                                    expectedPages = item.pageCount,
                                    providerId = item.providerId,
                                    preflightPending = false,
                                )
                                cloudSyncQueue.upsert(row)
                                // Resolved (and, if a job is live, registered) before
                                // the pump picks the row up. Task 4: the ingest
                                // itself no longer runs on this coroutine — a
                                // unique WorkManager worker drains the queue, so it
                                // keeps running after this composable (and the
                                // app) is gone. That means `ingestJob` now
                                // completes almost immediately after the kick
                                // below, so `backgroundIngestRegistry` can no
                                // longer represent "this row is actively
                                // uploading" the way it did pre-Task-4 — see
                                // uncollectBookmark/discardCloudCollection, which
                                // fall back to `cloudSyncQueue.remove(id)` (works
                                // for a still-WAITING row; cannot interrupt a row
                                // the worker has already started running).
                                val cloudId = appGraph.cloudComicIdStore.getOrCreate(acquisition)
                                if (ingestJob != null) {
                                    backgroundIngestRegistry.register(cloudId, ingestJob)
                                }
                                // Completion status for the visible detail is no
                                // longer a direct return value (the worker owns
                                // the run) — it is picked up by the
                                // cloudSyncQueue.rows observer LaunchedEffect
                                // below instead.
                                CloudIngestWorker.kickNow(context)
                            }
                            // EnqueueFetchJob: nothing else to do here — the
                            // fetch pump (with its symptom routing + resume)
                            // now owns page acquisition for this identity.
                            // Completion removes only our pending placeholder;
                            // Activity now shows the durable download job.
                        }
                    }
                }
                // Existing WAITING/FAILED work keeps its progress and retry
                // budget, but still receives the catch-up wake-up it needs.
                // C35-a: kickNow now reads the pump's WorkInfo before choosing
                // REPLACE vs APPEND_OR_REPLACE, so it is a suspend call off the
                // main thread (same shape as the Retry-sync kick below).
                if (preflight == null) {
                    coroutineScope.launch(Dispatchers.IO) { CloudIngestWorker.kickNow(context) }
                }
            }
        }
    }

    /**
     * Whole-slice review GAP 2 / A31 (owner bug backlog): uncollecting (or
     * discarding) mid-fetch previously left the underlying download job
     * running, so a comic removed from Collected still finished fetching,
     * sealed, and uploaded to R2 anyway — and a cloud-only job that had
     * ALREADY reached a terminal state (FAILED/SEALED/FINISH) before being
     * uncollected was left alive forever, silently, since the old state gate
     * below only cancelled WAIT/DOWNLOAD (an auto-revive sweep or manual
     * Retry could then resurrect it with no bookmark and no way to ever
     * upload). Resolves the same download identity the collect path uses
     * ([BrowseGalleryIdentity.resolve] with the same origin acquisition/
     * provider) and applies [uncollectJobOutcome] via [applyUncollectJobOutcome]:
     * a dual-intent job (local download still wanted) only has its cloud
     * intent dropped; a cloud-only job is discarded in ANY state via the same
     * primitives [AppGraph.downloadJobStore]'s WAIT/DOWNLOAD `cancel` path
     * uses internally. Best effort: the job may have already raced to a
     * terminal state by the time this resolves — nothing left to touch is not
     * an error, hence [runCatching].
     */
    suspend fun cancelFetchJobForUncollect(item: BrowseFeedItem) {
        val identityResult = BrowseGalleryIdentity.resolve(
            item = item,
            originAcquisitionUrl = remoteDetailOriginAcquisition,
            originProviderId = remoteDetailOriginProviderId,
        )
        val identity = (identityResult as? BrowseGalleryIdentityResult.Resolved)?.identity ?: return
        val job = appGraph.downloadJobStore.jobFor(identity) ?: return
        runCatching {
            applyUncollectJobOutcome(
                job = job,
                dropCloudIntent = appGraph.downloadJobStore::dropCloudIntent,
                cancelActiveJob = appGraph.downloadJobStore::cancel,
                discardTerminalJob = { jobId ->
                    appGraph.downloadJobStore.discardCloudOnlyJob(jobId) { discardIdentity, revision ->
                        com.easemic.app.feature.download.deleteCatalogDownloadRevision(
                            filesDir = appGraph.appFilesDir,
                            identity = discardIdentity,
                            downloadRevision = revision,
                        )
                    }
                },
            )
        }
    }

    fun listedCloudItemFor(item: BrowseFeedItem): BrowseFeedItem? {
        val parsed = CloudCollectionFeedMapper.parseCloudComicId(item.acquisitionUrl)
        if (parsed != null) {
            libraryCloudItems.firstOrNull {
                CloudCollectionFeedMapper.parseCloudComicId(it.acquisitionUrl) == parsed
            }?.let { return it }
            libraryCloudTrashCards.firstOrNull { it.cloudComicId == parsed }?.let { card ->
                return CloudCollectionFeedMapper.mapCard(card)
            }
            return item
        }
        libraryCloudItems.firstOrNull { listedCloudMatchesItem(it, item) }?.let { return it }
        libraryCloudTrashCards.firstOrNull { cloudCardMatchesItem(it, item) }?.let { card ->
            return CloudCollectionFeedMapper.mapCard(card)
        }
        return peekedCloudIdFor(item)?.let { id ->
            item.copy(
                acquisitionUrl = "cloud:$id",
                upstreamAcquisitionUrl = item.upstreamAcquisitionUrl ?: item.acquisitionUrl,
            )
        }
    }

    fun resolveCloudComicId(item: BrowseFeedItem): String? {
        CloudCollectionFeedMapper.parseCloudComicId(item.acquisitionUrl)?.let { return it }
        listedCloudItemFor(item)?.let { listed ->
            CloudCollectionFeedMapper.parseCloudComicId(listed.acquisitionUrl)?.let { return it }
        }
        return listOfNotNull(
            item.upstreamAcquisitionUrl,
            item.acquisitionUrl,
            remoteDetailOriginAcquisition,
        ).firstNotNullOfOrNull { cloudComicIdStore.peek(it) }
    }

    fun uncollectBookmark(item: BrowseFeedItem) {
        // #C62: captured before removal so a Failed bucket outcome below can
        // put them back — the comic stays collected when the bucket-side
        // tombstone itself never happened.
        val bookmarksBeforeRemoval = collectBookmarkStore.readAll()
        val selectedBookmarks = bookmarksForItem(bookmarksBeforeRemoval, item)
        val bookmarkForItem = selectedBookmarks.firstOrNull()
        val identityBookmarks = selectedBookmarks.filter(::isSerializedBookmark)
        selectedBookmarks.forEach { collectBookmarkStore.remove(it.id, it.providerId) }
        val acquisition = item.acquisitionUrl
        val upstream = item.upstreamAcquisitionUrl
        // Matches on upstream too: a cloud-shelf item's acquisitionUrl is
        // "cloud:<id>", never what the bookmark was persisted under at
        // collect time (the original acquisition) — matching acquisitionUrl
        // alone silently leaves that bookmark behind, risking a later
        // catch-up-pass resurrection of a comic the user just uncollected.
        val matchingBookmarks = if (bookmarkForItem?.let(::isSerializedBookmark) == true) {
            emptyList()
        } else {
            bookmarksMatchingUncollect(collectBookmarkStore.readAll(), acquisition, upstream)
        }
        matchingBookmarks.forEach { collectBookmarkStore.remove(it.id, it.providerId) }
        val removedBookmarks = (identityBookmarks + listOfNotNull(bookmarkForItem) + matchingBookmarks)
            .distinctBy { it.providerId to it.id }
        // Serialized uncollect is a whole-series operation. The bookmark has
        // already been removed above, so the orchestrator can restore these
        // exact rows if a bound bucket refuses the tombstone.
        if (bookmarkForItem?.let(::isSerializedBookmark) == true ||
            appGraph.comicSourceRegistry.source(item.providerId)?.descriptor?.serialized == true
        ) {
            val series = SeriesIdentityV2(
                providerNamespace = bookmarkForItem?.providerId ?: item.providerId,
                canonicalSeriesId = bookmarkForItem?.id ?: item.id,
            )
            val serializedBookmarks = removedBookmarks
            collectBookmarkEpoch += 1
            coroutineScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    appGraph.unfollowSerializedSeries(series, serializedBookmarks)
                }
                when (outcome) {
                    SerializedSeriesUnfollowResult.Unfollowed -> {
                        sayRemote("Removed from Collected.", acquisition = acquisition)
                    }
                    is SerializedSeriesUnfollowResult.CloudFailed -> {
                        collectBookmarkEpoch += 1
                        sayRemote(
                            when (outcome.kind) {
                                UncollectCloudFailureKind.LOCKED -> LibraryLockedException.MESSAGE
                                UncollectCloudFailureKind.NEWER -> NewerCloudFormatException.MESSAGE
                                UncollectCloudFailureKind.UNREADABLE -> UnreadableCloudLibraryException.MESSAGE
                                UncollectCloudFailureKind.UNREACHABLE ->
                                    "Couldn't reach your cloud. Still collected — try again."
                                UncollectCloudFailureKind.SYNCING ->
                                    "Your cloud library is still syncing. Try again in a moment."
                            },
                            acquisition = acquisition,
                        )
                    }
                    is SerializedSeriesUnfollowResult.Failed -> {
                        collectBookmarkEpoch += 1
                        sayRemote("Couldn't remove this series. Still collected — try again.", acquisition = acquisition)
                    }
                }
            }
            return
        }
        if (acquisition != null) {
            backgroundIngestRegistry.cancel(acquisition)
            coroutineScope.launch { cancelFetchJobForUncollect(item) }
            cloudSyncQueue.remove(acquisition)
        }
        if (upstream != null) {
            backgroundIngestRegistry.cancel(upstream)
            cloudSyncQueue.remove(upstream)
        }
        peekedCloudIdFor(item)?.let { backgroundIngestRegistry.cancel(it) }
        CloudCollectionFeedMapper.parseCloudComicId(item.acquisitionUrl)?.let { id ->
            backgroundIngestRegistry.cancel(id)
        }
        // Durable per-comic sync ledger (S3 Task 1, owner bug C3/A3): an
        // uncollected comic may legitimately be re-uploaded later, so drop its
        // ledger entry now — otherwise a future re-collect's pre-flight would
        // SKIP_INGEST a comic the user just removed from the cloud. Keyed off
        // both the original acquisition and the upstream (a cloud-shelf item's
        // own acquisitionUrl is "cloud:<id>", not the source key the ledger
        // was written under). Fire-and-forget and store-state-independent so
        // it clears even when the bucket store is currently Blocked.
        //
        // #C62: these side effects (registry cancels, queue removal, ledger
        // clear) are NOT undone on a Failed bucket outcome below. For a comic
        // that is still genuinely in the bucket they are no-ops or self-heal
        // at the next ledger backfill (#C56 behaviour) — not worth threading
        // a rollback through fire-and-forget coroutines for.
        appGraph.currentBucketFingerprintOrNull()?.let { fp ->
            val keys = listOfNotNull(
                galleryDedupKey(acquisition),
                upstream?.let { galleryDedupKey(it) },
            ).distinct()
            if (keys.isNotEmpty()) {
                coroutineScope.launch {
                    keys.forEach { key -> appGraph.cloudSyncLedgerStore.clear(fp, key) }
                }
            }
        }
        collectBookmarkEpoch += 1
        // K2: same ownership stamp as collectBookmark's `say`.
        sayRemote("Removed from Collected.", acquisition = acquisition)
        val closeCloudCard = CloudCollectionFeedMapper.parseCloudComicId(item.acquisitionUrl) != null
        fun closeCloudDetail() {
            remoteDetailItem = null
            remoteDetailCloudStore = null
            sayRemote(null)
            remoteDetailBusy = false
            remoteDetailOriginAcquisition = null
            remoteDetailOriginProviderId = null
        }
        // Resolve the cloud id directly — the same network-read-free
        // resolution discardCloudCollection uses — rather than requiring a
        // fresh listAllIndexCards() scan to find a match first. That extra
        // scan was uncollect's single point of failure and Discard never
        // had it: any divergence between the active index and what the
        // shelf/mirror still show as collected (a slow/flaky real network
        // read misclassified by looksLikeMissingObject as "chunk absent",
        // or a lost update from a second concurrent writer on the same
        // bucket) made the scan's match come back null and silently no-op
        // both the bucket tombstone and the mirror update below, even
        // though the id was recoverable for free the whole time.
        val resolvedCloudId = resolveCloudComicId(item)
        val removedBookmarkAcquisitions = removedBookmarks.map { it.acquisitionUrl }
        when (val io = productObjectStore()) {
            is ByoCloudIoResult.Blocked -> {
                syncExhentaiFavoriteIfEnabled(
                    item = item,
                    favcat = EhentaiClient.FAVORITE_REMOVE,
                    fallbackAcquisitionUrls = removedBookmarkAcquisitions,
                )
                if (closeCloudCard) closeCloudDetail()
            }
            is ByoCloudIoResult.Ready -> {
                coroutineScope.launch {
                    // #C62: tombstoneForUncollect never throws (except
                    // CancellationException) — a bucket failure used to reach
                    // this scope's uncaught-exception handler (it has none)
                    // and kill the process. See
                    // docs/superpowers/specs/2026-09-25-c62-uncollect-offline-crash-design.md.
                    val outcome = withContext(Dispatchers.IO) {
                        val store = CloudCollectionStore(io.store)
                        tombstoneForUncollect(
                            store = store,
                            cloudComicId = resolvedCloudId,
                            // Fallback only: kept for any item shape
                            // resolveCloudComicId doesn't cover, not as the
                            // primary resolution path. SF-4 (C35 audit): no
                            // purge here — that used to cost every tap a full
                            // trash listing plus a discardComic per expired
                            // card under INDEX_MUTATION_LOCK; AppGraph's
                            // foreground sweep covers expired trash instead.
                            fallback = {
                                store.listAllIndexCards().firstOrNull { card ->
                                    cloudCardMatchesItem(card, item)
                                }?.cloudComicId
                            },
                            nowMs = System.currentTimeMillis(),
                        )
                    }
                    when (outcome) {
                        is UncollectCloudOutcome.Tombstoned -> {
                            syncExhentaiFavoriteIfEnabled(
                                item = item,
                                favcat = EhentaiClient.FAVORITE_REMOVE,
                                fallbackAcquisitionUrls = removedBookmarkAcquisitions,
                            )
                            // B4c live update (loyal-pipelines Slice B §2):
                            // trash the mirror row unconditionally alongside
                            // the bucket-side tombstone above — not gated on
                            // it — so the shelf drops this card immediately
                            // even if the bucket-side index has diverged.
                            appGraph.currentBucketFingerprintOrNull()?.let { fp ->
                                appGraph.cloudMirrorStore.setTrashed(fp, outcome.cloudComicId, outcome.tombstonedAtMs)
                            }
                            // Still drives the trash pass and any other
                            // epoch-keyed consumer — the active shelf itself
                            // no longer needs it since the mirror's
                            // setTrashed(..., tombstonedAtMs) call above
                            // already made Room's observeActiveCards Flow
                            // drop this card live.
                            libraryCloudEpoch += 1
                            if (closeCloudCard) closeCloudDetail()
                        }
                        is UncollectCloudOutcome.NotInIndex -> {
                            syncExhentaiFavoriteIfEnabled(
                                item = item,
                                favcat = EhentaiClient.FAVORITE_REMOVE,
                                fallbackAcquisitionUrls = removedBookmarkAcquisitions,
                            )
                            // #C62 review (Sev2): the old code trashed the
                            // mirror row unconditionally once an id resolved,
                            // even when the active index had already
                            // diverged and had nothing to tombstone (see
                            // UncollectFromCloudShelfIndexDivergenceTest) —
                            // preserve that behaviour so a diverged card
                            // still drops off the shelf.
                            appGraph.currentBucketFingerprintOrNull()?.let { fp ->
                                appGraph.cloudMirrorStore.setTrashed(fp, outcome.cloudComicId, outcome.atMs)
                            }
                            libraryCloudEpoch += 1
                            if (closeCloudCard) closeCloudDetail()
                        }
                        UncollectCloudOutcome.NotFound -> {
                            syncExhentaiFavoriteIfEnabled(
                                item = item,
                                favcat = EhentaiClient.FAVORITE_REMOVE,
                                fallbackAcquisitionUrls = removedBookmarkAcquisitions,
                            )
                            libraryCloudEpoch += 1
                            if (closeCloudCard) closeCloudDetail()
                        }
                        is UncollectCloudOutcome.Failed -> {
                            // #C62: the comic stays collected — put back the
                            // bookmarks this uncollect removed above, leave
                            // the mirror untouched (nothing was trashed), and
                            // keep the detail open so the user sees why. No
                            // raw exception message reaches the log or the UI.
                            removedBookmarks.forEach { collectBookmarkStore.upsert(it) }
                            collectBookmarkEpoch += 1
                            DiagLog.w("CloudSync", "uncollect tombstone failed kind=${outcome.kind}")
                            sayRemote(
                                when (outcome.kind) {
                                    UncollectCloudFailureKind.LOCKED -> LibraryLockedException.MESSAGE
                                    UncollectCloudFailureKind.NEWER -> NewerCloudFormatException.MESSAGE
                                    UncollectCloudFailureKind.UNREADABLE -> UnreadableCloudLibraryException.MESSAGE
                                    UncollectCloudFailureKind.UNREACHABLE ->
                                        "Couldn't reach your cloud. Still collected — try again."
                                    UncollectCloudFailureKind.SYNCING ->
                                        "Your cloud library is still syncing. Try again in a moment."
                                },
                                acquisition = acquisition,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Durable per-comic sync ledger clear for a comic being removed from the
     * cloud by its cloud id (S3 Task 1, owner bug C3/A3). Reads the mirror
     * card's original source `acquisitionRef` (the canonical ledger key) so a
     * cloud-shelf item — whose own `acquisitionUrl` is only "cloud:<id>" —
     * still clears the right entry; falls back to the item's own acquisition/
     * upstream keys. Call this BEFORE deleting the mirror card so the
     * acquisitionRef lookup still resolves.
     */
    suspend fun clearSyncLedgerForCloudComic(fp: String, cloudComicId: String, item: BrowseFeedItem) {
        val keys = listOfNotNull(
            appGraph.cloudMirrorStore.card(fp, cloudComicId)?.acquisitionRef?.let { galleryDedupKey(it) },
            galleryDedupKey(item.acquisitionUrl),
            item.upstreamAcquisitionUrl?.let { galleryDedupKey(it) },
        ).distinct()
        keys.forEach { key -> appGraph.cloudSyncLedgerStore.clear(fp, key) }
    }

    fun discardCloudCollection(item: BrowseFeedItem) {
        item.acquisitionUrl?.let {
            backgroundIngestRegistry.cancel(it)
            coroutineScope.launch { cancelFetchJobForUncollect(item) }
            cloudSyncQueue.remove(it)
        }
        resolveCloudComicId(item)?.let { backgroundIngestRegistry.cancel(it) }
        val id = resolveCloudComicId(item) ?: run {
            sayRemote("Discard failed: not in Cloud collection.")
            return
        }
        when (val io = productObjectStore()) {
            is ByoCloudIoResult.Blocked -> {
                sayRemote(io.message)
            }
            is ByoCloudIoResult.Ready -> {
                remoteDetailBusy = true
                sayRemote("Discarding from cloud…")
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            CloudCollectionStore(io.store).discardComic(id)
                        }
                    }
                    remoteDetailBusy = false
                    result.fold(
                        onSuccess = {
                            // Still drives the trash pass and any other
                            // epoch-keyed consumer — the active shelf itself
                            // no longer needs it, since deleteCard below
                            // already makes Room's observeActiveCards Flow
                            // drop this card live, without restart.
                            libraryCloudEpoch += 1
                            appGraph.currentBucketFingerprintOrNull()?.let { fp ->
                                // Clear the ledger BEFORE deleting the mirror
                                // card, so the acquisitionRef lookup still
                                // resolves — a discarded comic must be
                                // re-uploadable on a later re-collect.
                                clearSyncLedgerForCloudComic(fp, id, item)
                                appGraph.cloudMirrorStore.deleteCard(fp, id)
                            }
                            if (CloudCollectionFeedMapper.parseCloudComicId(item.acquisitionUrl) != null) {
                                remoteDetailItem = null
                                sayRemote(null)
                            } else {
                                sayRemote("Discarded from your cloud storage.")
                            }
                        },
                        onFailure = { error ->
                            sayRemote(
                                when {
                                    error is NewerCloudFormatException ->
                                        NewerCloudFormatException.MESSAGE
                                    error is UnreadableCloudLibraryException ->
                                        UnreadableCloudLibraryException.MESSAGE
                                    error is LibraryLockedException -> LibraryLockedException.MESSAGE
                                    error is DamagedLibraryLockException ->
                                        DamagedLibraryLockException.MESSAGE
                                    // #D16: the drive refused the saved sign-in — say so, don't print a code.
                                    CloudSignIn.rejected(error) -> CloudSignInCopy.sentence(bridgeR2BindPresenter.state.boundKind)
                                    else -> "Discard failed: ${error.message}"
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    /**
     * Task 14 (browse-remaining §4): Recently deleted card actions on the
     * Cloud storage screen. The row only disappears when the bucket confirms
     * it — success bumps [libraryCloudEpoch], which re-fires the live trash
     * listing; failure leaves the row in place for an honest retry.
     */
    fun restoreRecentlyDeleted(item: BrowseFeedItem) {
        val id = resolveCloudComicId(item) ?: return
        // A16: ignore repeat taps while a restore for this id is in flight.
        if (!recentlyDeletedButtonEnabled(id, restoringCloudIds)) return
        when (val io = productObjectStore()) {
            is ByoCloudIoResult.Blocked -> Unit // The card is unreachable-gated; nothing to do.
            is ByoCloudIoResult.Ready -> {
                restoringCloudIds = restoringCloudIds + id
                // Optimistic UI: drop the card immediately, re-insert on failure
                // so a slow network doesn't read as "nothing happened".
                val removedIndex = libraryRecycleItems.indexOfFirst { resolveCloudComicId(it) == id }
                val removedItem = removedIndex.takeIf { it >= 0 }?.let { libraryRecycleItems[it] }
                if (removedItem != null) {
                    libraryRecycleItems = libraryRecycleItems.filterIndexed { index, _ -> index != removedIndex }
                }
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { CloudCollectionStore(io.store).restoreComic(id) }
                    }.onSuccess {
                        libraryCloudEpoch += 1
                        appGraph.currentBucketFingerprintOrNull()?.let { fp ->
                            appGraph.cloudMirrorStore.setTrashed(fp, id, null)
                        }
                    }.onFailure { error ->
                        if (removedItem != null) {
                            libraryRecycleItems = libraryRecycleItems.toMutableList().apply {
                                add(removedIndex.coerceIn(0, size), removedItem)
                            }
                        }
                        sayRemote(
                            if (error is NewerCloudFormatException) {
                                NewerCloudFormatException.MESSAGE
                            } else {
                                "Restore failed — try again"
                            },
                        )
                    }
                    restoringCloudIds = restoringCloudIds - id
                }
            }
        }
    }

    /** Task 14: permanent R2 delete behind the card's two-tap confirm. */
    fun deleteRecentlyDeletedForever(item: BrowseFeedItem) {
        val id = resolveCloudComicId(item) ?: return
        // A16: ignore repeat taps while a delete for this id is in flight.
        if (!recentlyDeletedButtonEnabled(id, restoringCloudIds)) return
        when (val io = productObjectStore()) {
            is ByoCloudIoResult.Blocked -> Unit
            is ByoCloudIoResult.Ready -> {
                restoringCloudIds = restoringCloudIds + id
                val removedIndex = libraryRecycleItems.indexOfFirst { resolveCloudComicId(it) == id }
                val removedItem = removedIndex.takeIf { it >= 0 }?.let { libraryRecycleItems[it] }
                if (removedItem != null) {
                    libraryRecycleItems = libraryRecycleItems.filterIndexed { index, _ -> index != removedIndex }
                }
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { CloudCollectionStore(io.store).discardComic(id) }
                    }.onSuccess {
                        libraryCloudEpoch += 1
                        appGraph.currentBucketFingerprintOrNull()?.let { fp ->
                            // Permanent delete: drop the ledger entry (reading
                            // the mirror card's key first) so a later
                            // re-collect can re-upload the comic.
                            clearSyncLedgerForCloudComic(fp, id, item)
                            appGraph.cloudMirrorStore.deleteCard(fp, id)
                        }
                    }.onFailure { error ->
                        if (removedItem != null) {
                            libraryRecycleItems = libraryRecycleItems.toMutableList().apply {
                                add(removedIndex.coerceIn(0, size), removedItem)
                            }
                        }
                        sayRemote(
                            if (error is NewerCloudFormatException) {
                                NewerCloudFormatException.MESSAGE
                            } else {
                                "Delete failed — try again"
                            },
                        )
                    }
                    restoringCloudIds = restoringCloudIds - id
                }
            }
        }
    }

    /**
     * A42 remainder: the STORE is the source of truth when the user re-opens
     * a read from a tile; an explicit (non-null) [initialOrdinal] (a preview-cell
     * deep link) still wins (see [RemoteProgressPolicy.resumeOrdinal]).
     */

    fun readOnline(item: BrowseFeedItem, initialOrdinal: Int? = null) {
        serializedReaderOriginLayerToken =
            (tagSearchStack.top() as? TagSearchSurface.Detail)?.snapshot?.token
        val storedInMap = RemoteProgressPolicy.keyFor(item)?.let { remoteProgressMap[it] }
        val knownPageCount = item.pageCount?.takeIf { it > 0 } ?: 1
        val provisionalStart = RemoteProgressPolicy.resumeOrdinal(
            explicitInitialOrdinal = initialOrdinal,
            stored = storedInMap,
        ).coerceIn(0, knownPageCount - 1)
        val provisionalReady = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val provisionalSession = OnlineReaderSession(
            title = item.title,
            imageUrls = List(knownPageCount) { "" },
            initialOrdinal = provisionalStart,
            hasStoredProgress = true,
            resolveBlankPageUrl = {
                provisionalReady.await()
                ""
            },
        )
        remoteDetailBusy = false
        sayRemote(null)
        remoteReadProgressItem = item
        remoteReadProgressChapter = null
        onlineReaderSession = provisionalSession
        coroutineScope.launch {
            // Store read off-main like every other prefs read in this path —
            // it parses the progress JSON envelope.
            val storedProgress = withContext(Dispatchers.IO) {
                RemoteProgressPolicy.keyFor(item)?.let { remoteProgressStore.get(it) }
            } ?: storedInMap
            val resumeStart = RemoteProgressPolicy.resumeOrdinal(
                explicitInitialOrdinal = initialOrdinal,
                stored = storedProgress,
            )
            var pendingResolver: com.easemic.app.feature.download.DownloadPageResolver? = null
            try {
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = item.pageStreamUrl?.trim().orEmpty()
                    val streamCount = item.pageCount
                    if (stream.isNotEmpty() && streamCount != null && streamCount > 0) {
                        val authorizationHeader = when (
                            val auth = OpdsPseReadAuth.resolve(item.providerId, opdsProviders)
                        ) {
                            OpdsPseReadAuth.Unavailable -> return@runCatching null
                            is OpdsPseReadAuth.Ready -> auth.authorizationHeader
                        }
                        val urls = OpdsPsePages.expand(stream, streamCount, maxWidth = 2048)
                        val cacheRoot = File(context.cacheDir, "opds-pages")
                        val start = resumeStart.coerceIn(0, streamCount - 1)
                        return@runCatching OnlineReaderSession(
                            title = item.title,
                            imageUrls = List(streamCount) { "" },
                            initialOrdinal = start,
                            resolveBlankPageUrl = { ordinal ->
                                val pageUrl = urls.getOrElse(ordinal) {
                                    error("OPDS page ordinal $ordinal out of ${urls.size}")
                                }
                                withContext(Dispatchers.IO) {
                                    OpdsPsePageFetch.fetchToCache(
                                        url = pageUrl,
                                        httpGet = OpdsHttpGet { request ->
                                            OpdsCatalogClient.defaultGet(request)
                                        },
                                        authorizationHeader = authorizationHeader,
                                        cacheRoot = cacheRoot,
                                        ordinal = ordinal,
                                        budget = appGraph.cacheBudget,
                                    )
                                }
                            },
                        )
                    }
                    var acquisition = item.acquisitionUrl?.trim().orEmpty()
                    val cloudId = CloudCollectionFeedMapper.parseCloudComicId(acquisition)
                    if (cloudId != null) {
                        val io = productObjectStore()
                        if (io !is ByoCloudIoResult.Ready) {
                            error(
                                (io as ByoCloudIoResult.Blocked).message,
                            )
                        }
                        val meta = CloudCollectionStore(io.store).downloadMeta(cloudId)
                        acquisition = CloudCollectionFeedMapper.upstreamAcquisitionForOnlineRead(
                            itemAcquisitionUrl = item.acquisitionUrl,
                            metaAcquisitionRef = meta.acquisitionRef,
                        ).orEmpty()
                    }
                    if (acquisition.isEmpty()) {
                        return@runCatching null
                    }
                    val indexId = BrowseFeedRepository.parseNhentaiAcquisition(acquisition)
                    val frontRef = BrowseFeedRepository.parseEhentaiAcquisition(acquisition)
                    when {
                        indexId != null -> {
                            // A17: open immediately once the page count is
                            // known — from the browse item when present, else
                            // a single (cached) plan fetch. No per-page
                            // network before the session opens; page 0 (and
                            // every other ordinal) resolves lazily below.
                            val identity = GalleryIdentityV1(
                                providerNamespace = GalleryIdentityV1.NHENTAI,
                                canonicalItemId = indexId.toString(),
                            )
                            val job = appGraph.downloadJobStore.jobFor(identity)
                            val pageResolver = appGraph.downloadPageResolver(job).also { pendingResolver = it }
                            val pageCount = job?.takeIf { it.state == DownloadJobState.FINISH }?.expectedPageCount
                                ?: item.pageCount ?: nhentaiPlanFor(indexId, beforeRequest = {}).imageUrls.size
                            if (pageCount <= 0) {
                                null
                            } else {
                                val start = resumeStart.coerceIn(0, pageCount - 1)
                                // Cheap, local-disk-only READY check — never
                                // touches the network — so an already
                                // downloaded page 0 still shows instantly.
                                val readyUri = job?.let {
                                    appGraph.readyPageStore
                                        .resolveReady(identity, it.downloadRevision, start)
                                        ?.toUri()
                                        ?.toString()
                                }
                                OnlineReaderSession(
                                    title = item.title,
                                    imageUrls = FrontOnlineReadPrep.seedImageUrls(
                                        pageCount = pageCount,
                                        initialOrdinal = start,
                                        resolvedInitialUrl = readyUri ?: "",
                                    ),
                                    initialOrdinal = start,
                                    onClose = pageResolver::close,
                                    followProgress = { ordinal ->
                                        pageResolver.followProgress(identity, ordinal)
                                    },
                                    resolveBlankPageUrl = { ordinal ->
                                        val plan by lazy { nhentaiPlanFor(indexId, beforeRequest = {}) }
                                        val locator = withContext(Dispatchers.IO) {
                                            appGraph.downloadLocatorStore.get(identity)
                                                ?: DownloadLocator(
                                                    acquisitionUrl = BrowseFeedRepository.nhentaiAcquisitionUrl(indexId),
                                                    title = item.title,
                                                )
                                        }
                                        pageResolver.resolveDisplayUriCancellable(
                                            identity = identity,
                                            ordinal = ordinal,
                                            upstreamDisplayUri = { plan.imageUrls.getOrElse(ordinal) { "" } },
                                            fetchUpstreamBytes = {
                                                appGraph.downloadPageAdapter.fetchPageBytes(identity, locator, ordinal)
                                            },
                                        ).displayUri
                                    },
                                )
                            }
                        }
                        frontRef != null -> {
                            // A17: same shape as the nhentai branch above —
                            // open on page count alone, resolve every ordinal
                            // (including 0) lazily. E-Hentai plans only carry
                            // view-page paths, so resolveBlankPageUrl also
                            // does the per-page fetchPageImage lookup that
                            // used to run synchronously before open.
                            val identity = GalleryIdentityV1(
                                providerNamespace = GalleryIdentityV1.EHENTAI,
                                canonicalItemId = frontRef.first.toString(),
                            )
                            val job = appGraph.downloadJobStore.jobFor(identity)
                            val pageResolver = appGraph.downloadPageResolver(job).also { pendingResolver = it }
                            // E11: built only when a page count or path is actually missing, so a
                            // known item.pageCount still opens the reader without a request.
                            val lazyPlan by lazy {
                                ehentaiLazyPlanFor(
                                    galleryId = frontRef.first,
                                    token = frontRef.second,
                                    beforeRequest = {},
                                )
                            }
                            val pageCount = job?.takeIf { it.state == DownloadJobState.FINISH }?.expectedPageCount
                                ?: item.pageCount ?: lazyPlan.pageCount
                            if (pageCount <= 0) {
                                null
                            } else {
                                val start = resumeStart.coerceIn(0, pageCount - 1)
                                val readyUri = job?.let {
                                    appGraph.readyPageStore
                                        .resolveReady(identity, it.downloadRevision, start)
                                        ?.toUri()
                                        ?.toString()
                                }
                                val urlCache = java.util.concurrent.ConcurrentHashMap<Int, String>()
                                OnlineReaderSession(
                                    title = item.title,
                                    imageUrls = FrontOnlineReadPrep.seedImageUrls(
                                        pageCount = pageCount,
                                        initialOrdinal = start,
                                        resolvedInitialUrl = readyUri ?: "",
                                    ),
                                    initialOrdinal = start,
                                    onClose = pageResolver::close,
                                    followProgress = { ordinal ->
                                        pageResolver.followProgress(identity, ordinal)
                                    },
                                    resolveBlankPageUrl = { ordinal ->
                                        val clientAndLocator = withContext(Dispatchers.IO) {
                                            val client = EhentaiClient.fromAccount(
                                                ehentaiAccountStore.read(),
                                                beforeRequest = {},
                                            )
                                            val locator = appGraph.downloadLocatorStore.get(identity)
                                                ?: DownloadLocator(
                                                    acquisitionUrl = BrowseFeedRepository.ehentaiAcquisitionUrl(
                                                        frontRef.first,
                                                        frontRef.second,
                                                    ),
                                                    title = item.title,
                                                )
                                            client to locator
                                        }
                                        val client = clientAndLocator.first
                                        val locator = clientAndLocator.second
                                        pageResolver.resolveDisplayUriCancellable(
                                            identity = identity,
                                            ordinal = ordinal,
                                            upstreamDisplayUri = {
                                                val cached = urlCache[ordinal]
                                                if (cached != null) {
                                                    cached
                                                } else {
                                                    val pagePath = runCatching { lazyPlan.pathFor(ordinal, client) }
                                                        .getOrElse {
                                                            throw DownloadPageResolveException(
                                                                ordinal = ordinal,
                                                                message = "Couldn't load this page.",
                                                                cause = it,
                                                            )
                                                        }
                                                    val loaded = client.fetchPageImage(pagePath, ordinal + 1).imageUrl
                                                    urlCache.putIfAbsent(ordinal, loaded)
                                                    urlCache[ordinal] ?: loaded
                                                }
                                            },
                                            fetchUpstreamBytes = {
                                                appGraph.downloadPageAdapter.fetchPageBytes(identity, locator, ordinal)
                                            },
                                        ).displayUri
                                    },
                                )
                            }
                        }
                        else -> null
                    }
                }
            }
            remoteDetailBusy = false
            prepared.onFailure { error ->
                if (onlineReaderSession === provisionalSession) {
                    onlineReaderSession = null
                }
                // #D16: Continue falls back here when the cloud open failed, and a cloud comic's online read asks the
                // cloud for its source first — say the verdict, never CLOUD_HTTP_401 over the cloud read's line.
                sayRemote(CloudSignInCopy.onlineReadFailure(error, bridgeR2BindPresenter.state.boundKind))
            }
            val session = prepared.getOrNull()
            if (session == null || session.imageUrls.isEmpty()) {
                if (onlineReaderSession === provisionalSession) {
                    onlineReaderSession = null
                }
                if (prepared.isSuccess) {
                    sayRemote("Online read is not available for this source.")
                }
                provisionalReady.complete(false)
                return@launch
            }
            if (onlineReaderSession === provisionalSession) {
                sayRemote(null)
                // A42 remainder: remember the item this session was opened FOR, so
                // the reader's settles write progress under the same key the next
                // readOnline call resumes from.
                remoteReadProgressItem = item
                remoteReadProgressChapter = null
                onlineReaderSession = session
                pendingResolver = null // the composed session now owns its lease
            }
            provisionalReady.complete(true)
            } finally {
                provisionalReady.complete(false)
                pendingResolver?.close()
            }
        }
    }

    /**
     * Suspending cloud-reader open with a real outcome: true only once the session is
     * prepared and installed; false when the cloud id is missing, the store is blocked,
     * or the manifest/session preparation failed (status text is set for the failure).
     *
     * A42 remainder: the STORE is the source of truth when the user re-enters a
     * collected/cloud comic via a tile; an explicit (non-null) [initialOrdinal]
     * still wins (see [RemoteProgressPolicy.resumeOrdinal]).
     */
    suspend fun awaitCloudCollectionReader(
        item: BrowseFeedItem,
        initialOrdinal: Int? = null,
        /**
         * C34-c: the cloud detail screen's already-warm store, when the reader
         * was opened from one. Null opens through a freshly built store, as
         * every entry point did before.
         */
        store: CloudCollectionStore? = null,
        chapter: com.easemic.app.feature.browse.CloudReaderChapter? = null,
        seriesManifest: CloudSeriesManifest? = null,
        transition: CloudReaderTransitionPresentation? = null,
    ): Boolean {
        CloudOpenTrace.log("reader-tap", 0)
        val cloudId = CloudCollectionFeedMapper.parseCloudComicId(item.acquisitionUrl)
            ?: return false
        // Store read off-main (same reason as readOnline's).
        val storedProgress = withContext(Dispatchers.IO) {
            if (chapter != null) {
                remoteProgressStore.get(RemoteProgressPolicy.keyForChapter(chapter.series, chapter.providerChapterId))
            } else {
                RemoteProgressPolicy.keyFor(item)?.let { remoteProgressStore.get(it) }
            }
        }
        val resumeStart = RemoteProgressPolicy.resumeOrdinal(
            explicitInitialOrdinal = initialOrdinal,
            stored = storedProgress,
        )
        return when (val io = productObjectStore()) {
            is ByoCloudIoResult.Blocked -> {
                sayRemote(io.message)
                false
            }
            is ByoCloudIoResult.Ready -> {
                remoteDetailBusy = true
                sayRemote("Opening cloud pages…")
                val reusedStore = store != null
                var ok = false
                val prepared = CloudOpenTrace.timed(
                    phase = "reader-prepare",
                    extras = { "reusedStore=$reusedStore ok=$ok" },
                ) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            // C34-c lifetime: the loader below closes over this
                            // INSTANCE, so the open reader keeps reading after
                            // closeCloudDetail() nulls remoteDetailCloudStore —
                            // that state only holds the reference for the next
                            // open. The only reader entry points that pass this
                            // store are inside the detail composition, and the
                            // only way to enter a detail is `openRemoteDetail`,
                            // which nulls `remoteDetailCloudStore` first, so a
                            // post-rebind store is unreachable with a live
                            // reference. Do not cache this store anywhere that
                            // outlives the detail.
                            val cloudStore = CloudReaderStoreChoice.resolve(store) {
                                CloudCollectionStore(io.store)
                            }
                            val layout = chapter?.let { ChapterCloudObjectLayout(cloudId, it.chapterKey) }
                            val count = chapter?.pageCount?.takeIf { it > 0 }
                                ?: item.pageCount?.takeIf { it > 0 }
                                ?: layout?.let { cloudStore.pageCountOrNull(it) }
                                ?: if (layout != null) cloudStore.downloadPagesManifest(layout).pageCount
                                else cloudStore.pageCountOrNull(cloudId)
                                    ?: cloudStore.downloadPagesManifest(cloudId).pageCount
                            require(count > 0) { "No cloud pages" }
                            val supportsEfficientRange = io.store.supportsEfficientRange
                            val diskCache = CloudPageDiskCache(File(context.cacheDir, "cloud-page-cache"), budget = appGraph.cacheBudget)
                            val cacheId = if (chapter == null) cloudId else "$cloudId-${chapter.chapterKey}"
                            val pageFiles = CloudReaderPageFiles(
                                cloudStore,
                                diskCache,
                                layout ?: GalleryCloudObjectLayout(cloudId),
                                cacheId,
                            )
                            CloudReaderSession(
                                title = item.title,
                                pageCount = count,
                                cacheKey = if (chapter == null) {
                                    "collection:$cloudId"
                                } else {
                                    "collection:$cloudId:chapter:${chapter.providerChapterId}"
                                },
                                initialOrdinal = resumeStart.coerceIn(0, count - 1),
                                hasStoredProgress = storedProgress != null,
                                loader = CloudPageFileLoader { ordinal -> pageFiles.load(ordinal) },
                                focusedLoader = if (supportsEfficientRange) {
                                    CloudPageFileLoader { ordinal -> pageFiles.load(ordinal, focused = true) }
                                } else null,
                                focusedMode = if (supportsEfficientRange) "EXACT" else "WINDOW",
                                chapterContext = chapter?.let {
                                    com.easemic.app.feature.reader.ReaderChapterContext(
                                        chapterId = it.providerChapterId,
                                        chapterLabel = it.label,
                                    )
                                },
                                transition = transition,
                                onChapterBoundary = null,
                            ).withOpeningPageStarted(Dispatchers.IO.asExecutor()) // #D17: fetch while the reader composes
                        }
                    }.also { ok = it.isSuccess }
                }
                remoteDetailBusy = false
                prepared.onFailure { error ->
                    // #D16: the verdict in words (a refused sign-in says where the fix lives), never a transport code.
                    sayRemote(CloudSignInCopy.readFailure(error, bridgeR2BindPresenter.state.boundKind))
                }
                val session = prepared.getOrNull() ?: return false
                sayRemote(null)
                // A42 remainder: the cloud reader's settles write under the
                // item's own key (same shape as readOnline above).
                remoteReadProgressItem = chapter?.let { null } ?: item
                remoteReadProgressChapter = chapter?.let {
                    ChapterIdentityV2(it.series, it.providerChapterId)
                }
                cloudReaderSeriesManifest = seriesManifest
                cloudReaderSession = session
                true
            }
        }
    }

    fun openCloudCollectionReader(
        item: BrowseFeedItem,
        initialOrdinal: Int? = null,
        onFailure: (() -> Unit)? = null,
        /** C34-c: see [awaitCloudCollectionReader]'s `store`. */
        store: CloudCollectionStore? = null,
    ) {
        coroutineScope.launch {
            val opened = awaitCloudCollectionReader(item, initialOrdinal, store)
            if (!opened) onFailure?.invoke()
        }
    }

    /**
     * [presence] is the comic's presence in the user's own library, as the
     * detail resolved it. C4 §2.2: a Download tap on a comic that is ALREADY in
     * the cloud is a PULL-BACK, and a pull-back never carries a cloud intent —
     * see the `intentCloud` expression below.
     */
    fun enqueueBrowseLocalDownload(item: BrowseFeedItem, presence: ResourcePresence) {
        coroutineScope.launch(Dispatchers.IO) {
            val identityResult = BrowseGalleryIdentity.resolve(
                item = item,
                originAcquisitionUrl = remoteDetailOriginAcquisition,
                originProviderId = remoteDetailOriginProviderId,
            )
            val identity = (identityResult as? BrowseGalleryIdentityResult.Resolved)?.identity
            if (identity == null) {
                withContext(Dispatchers.Main) {
                    val current = remoteDetailStatus
                    sayRemote(
                        listOfNotNull(
                            current?.takeIf { it.isNotBlank() },
                            BrowseGalleryIdentity.CLOUD_UNAVAILABLE_LABEL,
                        ).joinToString(" "),
                    )
                }
                return@launch
            }
            val acquisition = remoteDetailOriginAcquisition?.takeIf { it.isNotBlank() }
                ?: item.acquisitionUrl
            if (!acquisition.isNullOrBlank()) {
                appGraph.downloadLocatorStore.putKeepingCatalogFacts(
                    identity,
                    // A32 §2: the only chance to persist the catalog facts —
                    // the row is in hand here and gone by the time the worker
                    // FINISHes.
                    item.toDownloadLocator(acquisition),
                )
            }
            appGraph.downloadJobStore.upsertEnqueue(
                identity = identity,
                expectedPageCount = item.pageCount?.takeIf { it > 0 },
                intentLocal = true,
                // With "Keep only the cloud copy" enabled, a download of an
                // already-collected item also feeds the normal fetch→upload
                // handoff.  Download-only items stay local and are never
                // silently uploaded merely because a bucket is bound.
                //
                // C4 §2.2: unless the comic is already IN the cloud. Then this
                // tap is the user pulling their copy back down, not a request
                // to upload it again — and a cloud intent here would make the
                // every-foreground release sweep delete it right back off the
                // device.
                intentCloud = cloudReleaseAfterUploadStore.read() &&
                    presence != ResourcePresence.CLOUD &&
                    !acquisition.isNullOrBlank() &&
                    appGraph.collectBookmarkStore.containsAcquisition(acquisition),
            )
            DownloadPumpWorker.kick(context)
        }
    }

    /** The feed item a Collect re-run needs, rebuilt from the durable bookmark (the sweep and Retry share it). */
    fun bookmarkFeedItem(bookmark: CollectedBookmark): BrowseFeedItem = BrowseFeedItem(
        id = bookmark.id,
        title = bookmark.title,
        acquisitionUrl = bookmark.acquisitionUrl,
        providerId = bookmark.providerId,
        coverUrl = bookmark.coverUrl,
        feedUrl = bookmark.feedUrl,
    )

    suspend fun openCloudUpdate(item: BrowseFeedItem, row: UpdateRowPresentation) {
        val card = libraryCloudCards.firstOrNull { candidate ->
            candidate.layoutKind == CloudCardLayout.SERIES &&
                candidate.providerId == row.namespace &&
                candidate.providerSeriesId == row.seriesId
        }
        val manifest = if (card == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val fingerprint = cloudBucketFingerprint ?: return@withContext null
                runCatching {
                    cloudSeriesManifestFromMirror(
                        card,
                        appGraph.cloudMirrorStore.chapterRows(fingerprint, card.cloudComicId),
                    )
                }.getOrNull()
            }
        }
        val target = manifest?.let { cloudChapterResolver(it).open(row.chapterId) }
        if (manifest == null || target == null || card == null) {
            withContext(Dispatchers.Main) { openRemoteDetail(item) }
            return
        }
        // The follow bookmark's acquisition is the source URL. The cloud reader
        // only accepts cloud:<id>, which is the mirror card's acquisition.
        val opened = awaitCloudCollectionReader(
            CloudCollectionFeedMapper.mapCard(card),
            initialOrdinal = target.initialOrdinal,
            chapter = target.chapter,
            seriesManifest = manifest,
            transition = cloudChapterResolver(manifest).transition(row.chapterId),
        )
        if (!opened) withContext(Dispatchers.Main) { openRemoteDetail(item) }
    }

    fun openUpdate(row: UpdateRowPresentation) {
        val bookmark = collectBookmarkStore.readAll().firstOrNull {
            isSerializedBookmark(it) && it.providerId == row.namespace && it.id == row.seriesId
        }
        val item = bookmark?.let { bookmarkFeedItem(it) }
        // Opening a chapter of the series counts as opening the series (+N rule).
        appGraph.clearNewChapterMark(SeriesIdentityV2(row.namespace, row.seriesId))
        when {
            item == null || row.opensDetail -> if (item != null) openRemoteDetail(item)
            row.route == SerializedReadRoute.LOCAL && row.libraryItemId != null -> {
                // Updates open from Library and retain their existing fallback.
                serializedReaderOriginRoute = null
                route = AppRoute.Reader(
                    itemId = row.libraryItemId,
                    initialOrdinal = row.progressOrdinal,
                    readingUnitId = row.readingUnitId,
                    chapterId = row.chapterId,
                )
            }
            row.route == SerializedReadRoute.CLOUD -> coroutineScope.launch { openCloudUpdate(item, row) }
            row.route == SerializedReadRoute.ONLINE -> openRemoteDetail(
                item,
                serializedResume = HistoryReopenTarget(
                    item = item,
                    chapterId = row.chapterId,
                    initialOrdinal = row.progressOrdinal,
                ),
            )
            else -> openRemoteDetail(item)
        }
    }

    fun openReaderBack(readerRoute: AppRoute.Reader) {
        coroutineScope.launch {
            // #E108: a serialized chapter reader returns to the serialized
            // series detail whether or not a collect bookmark exists — #E73's
            // download-only path deliberately writes none, and the bookmark
            // gate below used to fall those rows through to the gallery shell.
            val serializedTarget = withContext(Dispatchers.IO) {
                if (readerRoute.chapterId == null) {
                    null
                } else {
                    val identity = (repository as? DownloadLibraryAvailability)
                        ?.findSerializedSeriesIdentity(LibraryItemId(readerRoute.itemId))
                    if (identity == null) {
                        null
                    } else {
                        val bookmark = serializedOpenTarget(
                            itemId = readerRoute.itemId,
                            downloadIdentity = identity,
                            bookmarks = collectBookmarkStore.readAll(),
                        )
                        bookmark?.let { bookmarkFeedItem(it) }
                            ?: repository.observeAllItems().first()
                                .firstOrNull { it.id.value == readerRoute.itemId }
                                .let { item ->
                                    localSerializedDetailItem(
                                        identity = identity,
                                        title = item?.title.orEmpty(),
                                        coverUrl = item?.seriesCoverUri,
                                    )
                                }
                    }
                }
            }
            val layerToken = serializedReaderOriginLayerToken
            val layeredDetail = layerToken?.let { token ->
                tagSearchStack.surfaces.asReversed()
                    .mapNotNull { (it as? TagSearchSurface.Detail)?.snapshot }
                    .firstOrNull { it.token == token }
            }
            if (layeredDetail != null) {
                restoreDetailSurface(layeredDetail)
                serializedReaderOriginLayerToken = layeredDetail.token
                return@launch
            }
            when (readerBackTarget(readerRoute, serializedTarget != null)) {
                ReaderBackTarget.SERIALIZED_DETAIL -> {
                    route = serializedReaderBackRoute(serializedReaderOriginRoute)
                    openRemoteDetail(checkNotNull(serializedTarget), entryOrigin = DetailEntryOrigin.SAVED)
                    serializedReaderOriginLayerToken = null
                }
                ReaderBackTarget.DETAIL -> {
                    serializedReaderOriginRoute = null
                    route = AppRoute.Detail(readerRoute.itemId)
                }
            }
        }
    }

    // Per-bookmark catch-up reconciliation (resume-interrupted-ingest, spec
    // §0/§3), run by the catch-up gate below.
    //
    // Split into two independently-gated jobs (spec §3 review fix for the
    // slice C1 HIGH regression: the non-empty rebind gate over-blocked this
    // WHOLE function, which killed job A on any established library):
    //  - job A, FAILED-row retry — [failedRowsOnly] alone selects this. Safe
    //    on ANY routing: the FAILED row is current-bucket by construction.
    //  - job B, absent-bookmark reconcile — only runs when [failedRowsOnly]
    //    is false, and judges "already present" from the authoritatively
    //    fetched [presentCards] (never the async-filling Slice-B mirror,
    //    which lags hydration and was a timing hazard even on the user's own
    //    bucket).
    suspend fun runCloudCatchUpSweep(
        failedRowsOnly: Boolean,
        presentCards: List<CloudCollectionCard> = emptyList(),
    ) {
        // Durable per-comic sync ledger (S3 Task 1, owner bug C3/A3): consulted
        // per bookmark BEFORE any live-list decision, so a comic known-synced
        // to the CURRENT bucket is never re-uploaded because one cold-start R2
        // read failed. Resolved once here — this whole function is already in a
        // suspend/off-main context.
        val ledgerFingerprint = appGraph.currentBucketFingerprintOrNull()
        // #C56 send-back 2 S1: ONE fresh trash listing from the CURRENTLY
        // bound bucket, taken in the SAME pass as [presentCards] — never the
        // shelf's own independently-refreshed trash Compose state (see its
        // declaration higher up in this composable), which at rebind / first
        // launch can still be the PREVIOUS bucket's list (or empty) at this
        // exact moment. A stale/empty read there would turn a genuine
        // tombstone into a re-upload of a comic another device just deleted.
        // null means the listing itself failed (unreadable bucket);
        // [trashKnown] below then defers every decision a real trash hit
        // would have changed instead of guessing.
        val freshTrashCards = withContext(Dispatchers.IO) {
            runCatching {
                appGraph.resolveGatedObjectStore()?.let { CloudCollectionStore(it).listAllTrashCards() }
            }.getOrNull()
        }
        val trashKnown = freshTrashCards != null
        // #C56 spec 4.3: counts DROP_BOOKMARK outcomes so the epoch bump and
        // the log line below fire at most once per sweep, not once per row.
        var dropped = 0
        // #C56 send-back 2 S1: bookmarks whose decision could not be
        // resolved this pass because the trash listing was unreadable.
        var deferredDrops = 0
        collectBookmarkStore.readAll().forEach { bookmark ->
            val item = bookmarkFeedItem(bookmark)
            if (isSerializedBookmark(bookmark)) {
                // A serialized follow's source refresh is separate from this
                // sweep. Here we only repair finished local chapters whose
                // chapter row is absent from the current bucket; cloud-known
                // siblings are evaluated independently.
                val series = GalleryIdentityV1(bookmark.providerId, bookmark.id)
                val seriesIdentity = SeriesIdentityV2(bookmark.providerId, bookmark.id)
                val seriesKey = serializedSeriesLedgerKey(seriesIdentity)
                val fp = appGraph.currentBucketFingerprintOrNull()
                val card = fp?.let { appGraph.cloudMirrorStore.activeSeriesCard(it, seriesIdentity) }
                val mirrorChapterIds = if (fp != null && card != null) {
                    appGraph.cloudMirrorStore.chapterRows(fp, card.cloudComicId)
                        .mapTo(mutableSetOf()) { it.chapterIdentity }
                } else {
                    emptySet()
                }
                val jobs = appGraph.downloadJobStore.jobsForSeries(series)
                jobs.asSequence()
                    .filter { it.intentCloud && it.state in setOf(DownloadJobState.SEALED, DownloadJobState.FINISH) }
                    .forEach { job ->
                        val locator = appGraph.downloadLocatorStore.get(job.storageIdentity)
                        val acquisition = locator?.acquisitionUrl
                            ?: serializedChapterLocatorKey(SeriesIdentityV2(series.providerNamespace, series.canonicalItemId), job.chapterIdentity)
                        val row = cloudSyncQueue.get(acquisition, job.chapterIdentity)
                        val knownSynced = fp != null &&
                            appGraph.cloudSyncLedgerStore.isSynced(fp, seriesKey, job.chapterIdentity)
                        val dismissed = appGraph.cloudSyncDismissalStore.isDismissed(acquisition, job.chapterIdentity)
                        val decision = serializedChapterCatchUpAction(
                            queueStage = row?.stage,
                            ingestJobActive = backgroundIngestRegistry.isActive(acquisition),
                            dismissed = dismissed,
                            shouldQueueChapter = { !knownSynced && job.chapterIdentity !in mirrorChapterIds },
                        )
                        if (decision == CloudCatchUpSweepAction.RECOLLECT) {
                            appGraph.enqueueCloudUploadForJob(job, userInitiated = false)
                        }
                    }
                return@forEach
            }
            val knownSyncedToBucket = ledgerFingerprint != null &&
                galleryDedupKey(bookmark.acquisitionUrl)?.let { key ->
                    appGraph.cloudSyncLedgerStore.isSynced(ledgerFingerprint, key)
                } == true
            val queueStage = cloudSyncQueue.get(bookmark.acquisitionUrl)?.stage
            val ingestJobActive = backgroundIngestRegistry.isActive(bookmark.acquisitionUrl)
            val presentInIndex = presentCards.any { cloudCardMatchesItem(it, item) }
            // A45: the row's queue id IS bookmark.acquisitionUrl (see the
            // LegacyDirectUpload upsert above), so a dismissal recorded
            // from Activity is readable here directly. Without this the
            // sweep would re-collect the bookmark on the very next launch
            // and the dismissed row would be back.
            val dismissed = appGraph.cloudSyncDismissalStore.isDismissed(bookmark.acquisitionUrl)
            val decision = if (trashKnown) {
                catchUpSweepAction(
                    knownSyncedToBucket = knownSyncedToBucket,
                    queueStage = queueStage,
                    ingestJobActive = ingestJobActive,
                    presentInIndex = presentInIndex,
                    hasTrash = freshTrashCards.orEmpty().any { cloudCardMatchesItem(it, item) },
                    failedRowsOnly = failedRowsOnly,
                    dismissed = dismissed,
                )
            } else {
                // #C56 send-back 2 S1: never guess. If this bookmark WOULD
                // have been dropped had the listing succeeded, defer it
                // (SKIP, counted) instead of risking a re-upload of an
                // already-trashed comic under a false hasTrash=false.
                val ifTrashed = catchUpSweepAction(
                    knownSyncedToBucket = knownSyncedToBucket,
                    queueStage = queueStage,
                    ingestJobActive = ingestJobActive,
                    presentInIndex = presentInIndex,
                    hasTrash = true,
                    failedRowsOnly = failedRowsOnly,
                    dismissed = dismissed,
                )
                if (ifTrashed == CloudCatchUpSweepAction.DROP_BOOKMARK) {
                    deferredDrops += 1
                    CloudCatchUpSweepAction.SKIP
                } else {
                    catchUpSweepAction(
                        knownSyncedToBucket = knownSyncedToBucket,
                        queueStage = queueStage,
                        ingestJobActive = ingestJobActive,
                        presentInIndex = presentInIndex,
                        hasTrash = false,
                        failedRowsOnly = failedRowsOnly,
                        dismissed = dismissed,
                    )
                }
            }
            when (decision) {
                CloudCatchUpSweepAction.SKIP -> return@forEach
                CloudCatchUpSweepAction.RECOLLECT -> collectBookmark(item, syncCloud = true, announce = false)
                CloudCatchUpSweepAction.DROP_BOOKMARK -> {
                    // #C56 spec 4.3: the bucket's trash tombstones this
                    // bookmark — drop it locally instead of restoring/
                    // re-uploading it (job B's old behavior).
                    // #C56 send-back R3: [bookmark] is a snapshot read at the
                    // top of this loop — re-check the queue right before
                    // removing, and remove only if the stored row is still
                    // exactly that snapshot, so a bookmark the user
                    // re-created (a Collect tap) between the snapshot and
                    // this write is left alone instead of deleted out from
                    // under them.
                    // #C56 send-back 2 S2: a lingering FAILED row (job A's
                    // own retry target) no longer defers the drop — only a
                    // LIVE row (WAITING/FETCHING/UPLOADING) does, since that
                    // means a pump is actually driving it right now. When
                    // the drop succeeds and a FAILED row was present, clear
                    // it too so Activity does not keep a dead retry around
                    // for a comic this device no longer has bookmarked.
                    val queueRow = cloudSyncQueue.get(bookmark.acquisitionUrl)
                    val noLiveRow = queueRow == null || queueRow.stage == CloudSyncStage.FAILED
                    if (noLiveRow && collectBookmarkStore.removeIfUnchanged(bookmark)) {
                        if (queueRow != null) {
                            cloudSyncQueue.remove(bookmark.acquisitionUrl)
                        }
                        dropped += 1
                        // #C56 send-back 3: also clear this comic's sync
                        // ledger entry for the current bucket — otherwise the
                        // stale "already synced" fact lingers and would
                        // SKIP_INGEST a later re-collect of the same comic
                        // (same ledger clear uncollectBookmark already does).
                        ledgerFingerprint?.let { fp ->
                            galleryDedupKey(bookmark.acquisitionUrl)?.let { key ->
                                appGraph.cloudSyncLedgerStore.clear(fp, key)
                            }
                        }
                    }
                }
            }
        }
        if (deferredDrops > 0) {
            DiagLog.i("CloudSync", "catch-up: trash listing unavailable, drops deferred=$deferredDrops")
        }
        if (dropped > 0) {
            DiagLog.i("CloudSync", "catch-up: dropped=$dropped tombstoned bookmarks")
            collectBookmarkEpoch += 1
        }
    }

    // Authoritative index fetch for the catch-up gate below
    // (spec §3 emptiness-probe note): never trust the still-hydrating
    // Slice-B mirror for a reconcile decision. R2 is our own bucket, so this
    // cheap read carries no source-politeness cost.
    suspend fun probeCloudBucket(): CloudBucketProbe = withContext(Dispatchers.IO) {
        runCatching {
            val store = appGraph.resolveGatedObjectStore() ?: return@runCatching CloudBucketProbe.Unreadable
            val cards = CloudCollectionStore(store).listAllIndexCards()
            if (cards.isEmpty()) CloudBucketProbe.Empty else CloudBucketProbe.NonEmpty(cards)
        }.getOrElse { CloudBucketProbe.Unreadable }
    }

    // Catch-up gate: re-probe the bound bucket and reconcile the device's
    // collection against it whenever readiness, unlock, library epoch, the
    // fingerprint or reachability changes.
    LaunchedEffect(
        libraryCloudReady,
        bridgeR2UnlockedEpoch,
        libraryCloudEpoch,
        cloudBucketFingerprint,
        cloudProbeReady,
    ) {
        if (!AppLiveDeviceBindings.shouldRunCloudIo(bindLiveDeviceSession)) {
            return@LaunchedEffect
        }
        if (!LibraryCloudReadiness.catchUpEligible(libraryCloudReady = libraryCloudReady)) {
            return@LaunchedEffect
        }
        // Owner bug #3 fix: libraryCloudReady is purely `fp != null` (Slice
        // B's no-cold-start-gap signal — see the LaunchedEffect(cloudBucketFingerprint)
        // above) and says nothing about whether the bucket is actually
        // reachable right now. The catch-up sweep does real cloud reads/writes,
        // so it additionally waits for the grace loop above to have confirmed
        // reachability at least once for the currently bound bucket, instead
        // of racing ahead of it on a bucket that just got bound but hasn't
        // been probed yet.
        if (!cloudProbeReady) {
            return@LaunchedEffect
        }
        if (cloudBucketFingerprint == null) return@LaunchedEffect
        // #C61: one rule for every epoch, first bind and rebind alike — the
        // device's collection is the truth (#C56), so a readable bucket is
        // always fully reconciled against its own authoritative index, and an
        // unreadable read is never proof of anything (owner bug A3/C5: only the
        // FAILED-row retry runs). The old per-fingerprint routing (Migrate
        // offer, adoption record, one-time seed) had no reachable branch left.
        when (val plan = catchUpPlanFor(probeCloudBucket())) {
            is FastPathCatchUpPlan.FullReconcile ->
                runCloudCatchUpSweep(failedRowsOnly = false, presentCards = plan.cards)
            FastPathCatchUpPlan.FailedRowsOnly ->
                runCloudCatchUpSweep(failedRowsOnly = true)
        }
    }

    BackHandler(enabled = route != AppRoute.Library || browseFeedUrl != null || remoteDetailItem != null || cloudReaderSession != null || onlineReaderSession != null || tagSearchStack.surfaces.isNotEmpty() || (route == AppRoute.Browse && !browseSourceListVisible)) {
        if (cloudReaderSession != null) {
            cloudReaderSession = null
            remoteReadProgressItem = null
            remoteReadProgressChapter = null
            return@BackHandler
        }
        if (onlineReaderSession != null) {
            onlineReaderSession = null
            remoteReadProgressItem = null
            remoteReadProgressChapter = null
            return@BackHandler
        }
        if (route !is AppRoute.Reader && tagSearchStack.top() != null) {
            popTagSearchSurface()
            return@BackHandler
        }
        if (remoteDetailItem != null) {
            remoteDetailItem = null
            serializedReaderOriginRoute = null
            sayRemote(null)
            remoteDetailBusy = false
            remoteDetailOriginAcquisition = null
            remoteDetailOriginProviderId = null
            return@BackHandler
        }
        if (route == AppRoute.Browse && browseFeedUrl != null) {
            saveBrowseScrollPosition()
            // C26 audit MEDIUM-1: the enclosing `if` proves browseFeedUrl != null,
            // so writing null here is a guaranteed re-key — safe to clear.
            resetBrowseFeed()
            browseFeedUrl = null
            browseFeedProviderId = null
            return@BackHandler
        }
        if (route == AppRoute.Browse && !browseSourceListVisible) {
            // Level 2 (single-source gallery) back: pop to the Level-1 list.
            browseSourceListVisible = true
            return@BackHandler
        }
        route = when (val current = route) {
            is AppRoute.Sources -> when (current.returnTo) {
                SourcesReturnTo.SETTINGS -> AppRoute.Settings
                SourcesReturnTo.LIBRARY -> AppRoute.Library
            }
            is AppRoute.Reader -> {
                openReaderBack(current)
                current
            }
            AppRoute.OpdsCatalogs -> AppRoute.Settings
            AppRoute.BackupRestore -> AppRoute.Settings
            AppRoute.DownloadQueue -> AppRoute.Settings
            AppRoute.CloudSyncQueue -> AppRoute.Settings
            AppRoute.BridgeR2Bind -> AppRoute.Settings
            AppRoute.Browse,
            AppRoute.Activity,
            AppRoute.History,
            AppRoute.Settings,
            is AppRoute.Detail,
            -> AppRoute.Library
            AppRoute.Library -> AppRoute.Library
        }
    }

    // A40 (owner bug backlog, ledger 2026-09-06): Detail arrives from Library,
    // Browse, Activity and History alike, and `route` carries no origin, so the
    // tab the user last stood on is remembered and stays lit underneath it.
    var bottomBarOriginTab: AppBottomBarDestination by rememberSaveable {
        mutableStateOf(AppBottomBarDestination.LIBRARY)
    }
    val onDetailRoute = route is AppRoute.Detail
    // `toBottomBarDestination()` answers LIBRARY for Detail: that is the spec's
    // contract and the default when nothing better is known. It is only a
    // default, though, so Detail is withheld from the origin machinery below --
    // otherwise a comic opened from Browse would both relight Library and
    // overwrite the remembered Browse tab it should return to.
    val routeDestination = route.toBottomBarDestination()
    val primaryDestination = routeDestination.takeUnless { onDetailRoute }
    // Recorded in an effect, not inline: assigning state straight from the
    // composable body is a backwards write, which Compose flags and re-runs.
    LaunchedEffect(primaryDestination) {
        bottomBarOriginTab = AppBottomBarSelection.originTab(primaryDestination, bottomBarOriginTab)
    }

    PrivateComicLibraryTheme {
        val bottomBarDestination = AppBottomBarSelection.selected(
            routeDestination = primaryDestination,
            onDetail = onDetailRoute,
            detailOriginTab = bottomBarOriginTab,
        )
        Scaffold(
            // Only the immersive reader needs full-bleed; Import/Preview keep
            // default Scaffold insets so headers clear the status bar/cutout.
            contentWindowInsets = if (
                route is AppRoute.Reader ||
                onlineReaderSession != null ||
                cloudReaderSession != null
            ) {
                WindowInsets(0, 0, 0, 0)
            } else {
                ScaffoldDefaults.contentWindowInsets
            },
            bottomBar = {
                if (
                    bottomBarDestination != null &&
                    onlineReaderSession == null &&
                    cloudReaderSession == null
                ) {
                    AppBottomBar(
                        selected = bottomBarDestination,
                        libraryHasUnseenUpdates = libraryHasUnseenUpdates,
                        onSelect = { destination ->
                            remoteDetailItem = null
                            serializedReaderOriginRoute = null
                            sayRemote(null)
                            remoteDetailBusy = false
                            route = destination.toRoute()
                        },
                    )
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                val cloudSession = cloudReaderSession
                val onlineSession = onlineReaderSession
                val remoteItem = remoteDetailItem
                if (cloudSession == null && onlineSession == null) {
                    CfChallengeBanner(appGraph, context)
                }
                // A42 remainder: one recorder per open remote read, keyed by
                // the item the session was opened FOR. Settles write the store
                // synchronously — the recorder hops onto Dispatchers.IO first
                // (putStringSync on the main thread would jank every page turn).
                val remoteProgressRecorder = remember(remoteReadProgressItem, remoteReadProgressChapter) {
                    remoteReadProgressChapter?.let { chapter ->
                        RemoteReadingProgressRecorder.forChapter(
                            series = chapter.series,
                            chapterId = chapter.canonicalChapterId,
                            store = remoteProgressStore,
                        )
                    } ?: remoteReadProgressItem?.let {
                        RemoteReadingProgressRecorder.forItem(it, remoteProgressStore)
                    }
                }
                when {
                    cloudSession != null -> CloudReaderScreen(
                        session = cloudSession.copy(
                            onChapterBoundary = { boundary ->
                                val current = remoteReadProgressChapter
                                val manifest = cloudReaderSeriesManifest
                                val item = remoteReadProgressItem ?: remoteDetailItem
                                if (current != null && manifest != null && item != null) {
                                    val resolver = cloudChapterResolver(manifest)
                                    resolver.boundary(current.canonicalChapterId, boundary)?.let { target ->
                                        coroutineScope.launch {
                                            val cloudItem = serializedCloudReadItem(item, libraryCloudCards)
                                            if (cloudItem == null || !awaitCloudCollectionReader(
                                                item = cloudItem,
                                                initialOrdinal = target.initialOrdinal,
                                                store = remoteDetailCloudStore,
                                                chapter = target.chapter,
                                                seriesManifest = manifest,
                                                transition = resolver.transition(target.chapter.providerChapterId),
                                            )) sayRemote("Couldn't open the cloud copy of this chapter")
                                        }
                                    }
                                }
                            },
                        ),
                        onBack = {
                            cloudReaderSession = null
                            cloudReaderSeriesManifest = null
                            remoteReadProgressItem = null
                            remoteReadProgressChapter = null
                        },
                        onProgressSettled = { ordinal, pageCount ->
                            coroutineScope.launch(Dispatchers.IO) {
                                remoteProgressRecorder?.onProgressSettled(ordinal, pageCount)
                            }
                        },
                        volumeKeyPagingEnabled = volumeKeyPagingEnabled,
                        rtlPagingEnabled = rtlPagingEnabled,
                        onRtlPagingChange = { enabled ->
                            rtlPagingEnabled = enabled
                            readerRtlPagingStore.write(enabled)
                        },
                        keepScreenOnEnabled = keepScreenOnEnabled,
                        signInRejectedLine = CloudSignInCopy.sentence(bridgeR2BindPresenter.state.boundKind),
                        layoutMode = readerLayoutMode,
                        onLayoutModeChange = { mode ->
                            readerLayoutMode = mode
                            readerLayoutModeStore.write(mode)
                        },
                    )
                    onlineSession != null -> OnlineReaderScreen(
                        session = onlineSession,
                        onBack = {
                            onlineReaderSession = null
                            remoteReadProgressItem = null
                            remoteReadProgressChapter = null
                        },
                        keepScreenOnEnabled = keepScreenOnEnabled,
                        volumeKeyPagingEnabled = volumeKeyPagingEnabled,
                        rtlPagingEnabled = rtlPagingEnabled,
                        onRtlPagingChange = { enabled ->
                            rtlPagingEnabled = enabled
                            readerRtlPagingStore.write(enabled)
                        },
                        fitWidthEnabled = fitWidthEnabled,
                        layoutMode = readerLayoutMode,
                        onLayoutModeChange = { mode ->
                            readerLayoutMode = mode
                            readerLayoutModeStore.write(mode)
                        },
                        chapterContext = onlineSession.chapterContext,
                        onChapterBoundary = { boundary ->
                            val item = remoteDetailItem
                            val chapter = remoteReadProgressChapter
                            val result = remoteDetailChapters
                            if (item != null && chapter != null && result != null) {
                                val coordinator = ChapterReaderCoordinator(
                                    serializedReaderChapters(result, remoteProgressStore),
                                )
                                val adjacent = when (boundary) {
                                    ReaderChapterBoundary.END -> coordinator.atEnd(chapter.canonicalChapterId)
                                    ReaderChapterBoundary.START -> coordinator.atStart(chapter.canonicalChapterId)
                                }
                                when (adjacent) {
                                    is com.easemic.app.feature.reader.ReaderBoundary.OpenNext ->
                                        readSerializedChapter(item, adjacent.chapterId, initialOrdinal = 0)
                                    is com.easemic.app.feature.reader.ReaderBoundary.OpenPrevious ->
                                        readSerializedChapter(item, adjacent.chapterId, initialOrdinal = LAST_PAGE)
                                    com.easemic.app.feature.reader.ReaderBoundary.NoAdjacentChapter -> Unit
                                }
                            }
                        },
                        onProgressSettled = { ordinal, pageCount ->
                            coroutineScope.launch(Dispatchers.IO) {
                                remoteProgressRecorder?.onProgressSettled(ordinal, pageCount)
                            }
                        },
                    )
                    remoteItem != null -> {
                        val detailSurfaceToken =
                            (tagSearchStack.top() as? TagSearchSurface.Detail)?.snapshot?.token
                                ?: "remote-$remoteDetailOpenGeneration"
                        tagSearchStateHolder.SaveableStateProvider(detailSurfaceToken) {
                        val bookmarks = remember(collectBookmarkEpoch) {
                            collectBookmarkStore.readAll()
                        }
                        val bookmarked = bookmarksForItem(bookmarks, remoteItem).isNotEmpty()
                        val (listedIds, listedRefs) = remember(libraryCloudItems) {
                            CloudPresenceSets.fromItems(libraryCloudItems)
                        }
                        val listedScopedRefs = remember(libraryCloudItems) {
                            CloudPresenceSets.fromItemsScoped(libraryCloudItems).second
                        }
                        val (trashIds, trashRefs) = remember(libraryCloudTrashCards) {
                            libraryCloudTrashCards.map { it.cloudComicId }.toSet() to
                                libraryCloudTrashCards.mapNotNull { card ->
                                    card.acquisitionRef?.trim()?.takeIf { it.isNotEmpty() }
                                }.toSet()
                        }
                        val trashScopedRefs = remember(libraryCloudTrashCards) {
                            val mapped = libraryCloudTrashCards.map(CloudCollectionFeedMapper::mapCard)
                            CloudPresenceSets.fromItemsScoped(mapped).second
                        }
                        val peekedCloudId = remember(
                            remoteItem.acquisitionUrl,
                            remoteItem.upstreamAcquisitionUrl,
                            remoteDetailOriginAcquisition,
                            libraryCloudEpoch,
                        ) {
                            listOfNotNull(
                                remoteItem.acquisitionUrl,
                                remoteItem.upstreamAcquisitionUrl,
                                remoteDetailOriginAcquisition,
                            ).firstNotNullOfOrNull { url -> cloudComicIdStore.peek(url) }
                        }
                        val presence = ResourcePresenceResolver.resolve(
                            acquisitionUrl = remoteItem.acquisitionUrl,
                            bookmarked = bookmarked,
                            listedCloudIds = listedIds,
                            listedAcquisitionRefs = listedRefs,
                            peekedCloudId = peekedCloudId,
                            trashCloudIds = trashIds,
                            trashAcquisitionRefs = trashRefs,
                            providerId = remoteItem.providerId,
                            serialized = appGraph.comicSourceRegistry.source(remoteItem.providerId)?.descriptor?.serialized == true,
                            listedProviderAcquisitionRefs = listedScopedRefs,
                            trashProviderAcquisitionRefs = trashScopedRefs,
                        )
                        val cloudComicIdForDownload = remember(
                            remoteItem.acquisitionUrl,
                            remoteItem.upstreamAcquisitionUrl,
                            remoteDetailOriginAcquisition,
                            libraryCloudItems,
                            libraryCloudTrashCards,
                            peekedCloudId,
                            presence,
                        ) {
                            if (presence.usesCloudCollection()) {
                                resolveCloudComicId(remoteItem) ?: peekedCloudId
                            } else {
                                null
                            }
                        }
                        // C4: the detail drives the job that owns (or is fetching) the
                        // pages. Two candidates: the ORIGIN identity the source fetch runs
                        // under (#15's stable-cloud rule alone switched a Browse detail to
                        // the cloud identity the moment its upload landed, and the pages it
                        // had just fetched read as "Not downloaded") and the stable
                        // `cloud_collection` identity a pull-back from the cloud library
                        // downloads under. [DetailDownloadIdentity.choose] picks.
                        val originIdentityResult = remember(
                            remoteItem.id,
                            remoteItem.acquisitionUrl,
                            remoteItem.providerId,
                            remoteDetailOriginAcquisition,
                            remoteDetailOriginProviderId,
                        ) {
                            BrowseGalleryIdentity.resolve(
                                item = remoteItem,
                                originAcquisitionUrl = remoteDetailOriginAcquisition,
                                originProviderId = remoteDetailOriginProviderId,
                            )
                        }
                        val originIdentity =
                            (originIdentityResult as? BrowseGalleryIdentityResult.Resolved)?.identity
                        val cloudDownloadIdentity = remember(presence, cloudComicIdForDownload, remoteItem.acquisitionUrl) {
                            if (!presence.usesCloudCollection()) {
                                null
                            } else {
                                (
                                    cloudComicIdForDownload
                                        ?: CloudCollectionFeedMapper.parseCloudComicId(remoteItem.acquisitionUrl)
                                    )?.let { id ->
                                    GalleryIdentityV1(
                                        providerNamespace = GalleryIdentityV1.CLOUD_COLLECTION,
                                        canonicalItemId = id,
                                    )
                                }
                            }
                        }
                        val serializedSource = remember(remoteItem.providerId) {
                            appGraph.comicSourceRegistry.source(remoteItem.providerId)
                        }
                        val serializedDetail = serializedSource?.descriptor?.serialized == true ||
                            cloudReaderSeriesManifest != null || libraryCloudCards.any {
                                it.layoutKind == CloudCardLayout.SERIES && it.cloudComicId ==
                                    CloudCollectionFeedMapper.parseCloudComicId(remoteItem.acquisitionUrl)
                            }
                        val serializedReadSeries = serializedDetailSeries(cloudReaderSeriesManifest, remoteItem)
                        val serializedSeriesIdentity = remember(serializedReadSeries) {
                            GalleryIdentityV1(serializedReadSeries.providerNamespace, serializedReadSeries.canonicalSeriesId)
                        }
                        // Observe item creation and chapter revisions independently of jobs: deleting saved
                        // chapters clears the catalog after the last job emission.
                        val serializedLocalFlow = remember(appGraph, serializedDetail, serializedSeriesIdentity) {
                            if (serializedDetail) {
                                combine(
                                    appGraph.repository.observeSerializedSeriesLedgerKeys(),
                                    appGraph.downloadJobStore.observeSeriesJobs(serializedSeriesIdentity),
                                ) { _, _ ->
                                    withContext(Dispatchers.IO) {
                                        appGraph.repository.findDownloadItemId(serializedSeriesIdentity)
                                    }
                                }.distinctUntilChanged().flatMapLatest { itemId ->
                                    combine(
                                        itemId?.let { appGraph.repository.observeChapterShelfRowsForItem(it.value) }
                                            ?: flowOf(emptyList()),
                                        appGraph.downloadJobStore.observeSeriesJobs(serializedSeriesIdentity),
                                    ) { rows, jobs ->
                                        SerializedLocalState(
                                            series = serializedSeriesIdentity,
                                            itemId = itemId,
                                            rows = rows,
                                            jobs = jobs,
                                        )
                                    }
                                }
                            } else {
                                flowOf(
                                    SerializedLocalState(
                                        series = serializedSeriesIdentity,
                                        itemId = null,
                                        rows = emptyList(),
                                        jobs = emptyList(),
                                    ),
                                )
                            }
                        }
                        val serializedLocal by serializedLocalFlow.collectAsStateWithLifecycle(
                            initialValue = SerializedLocalState(
                                series = serializedSeriesIdentity,
                                itemId = null,
                                rows = emptyList(),
                                jobs = emptyList(),
                            ),
                        )
                        val currentSerializedLocal = localRowsFor(serializedSeriesIdentity, serializedLocal)
                        val serializedItemId: LibraryItemId? = currentSerializedLocal.itemId
                        val serializedShelfRows = currentSerializedLocal.rows
                        val serializedJobs = currentSerializedLocal.jobs
                        val originJob by (
                            originIdentity?.let { id -> appGraph.downloadJobStore.observeJob(id) }
                                ?: flowOf(null)
                            ).collectAsStateWithLifecycle(initialValue = null)
                        val cloudJob by (
                            cloudDownloadIdentity?.let { id -> appGraph.downloadJobStore.observeJob(id) }
                                ?: flowOf(null)
                            ).collectAsStateWithLifecycle(initialValue = null)
                        val resolvedIdentity = DetailDownloadIdentity.choose(
                            originIdentity = originIdentity,
                            cloudIdentity = cloudDownloadIdentity,
                            originJob = originJob,
                            cloudJob = cloudJob,
                            presenceUsesCloud = presence.usesCloudCollection(),
                        )
                        val identityResult: BrowseGalleryIdentityResult =
                            if (resolvedIdentity != null) {
                                BrowseGalleryIdentityResult.Resolved(resolvedIdentity)
                            } else {
                                originIdentityResult
                            }
                        val localJob = if (cloudDownloadIdentity != null && resolvedIdentity == cloudDownloadIdentity) cloudJob else originJob
                        // A36 review M2: "is it on this phone" is not `state ==
                        // FINISH` any more — a truncated seal owns a readable
                        // INCOMPLETE row. Resolve that row once here so the
                        // switch, the Read route, the preview taps and the
                        // delete all answer the same question.
                        //
                        // Review N2: and it is the AVAILABLE association, not a
                        // provenance claim. `findDownloadItemId` still finds a
                        // row whose location went MISSING — the store's delete
                        // guard and the enqueue revive both require an
                        // available one, so trusting the claim here would show
                        // "Downloaded" over a comic that can be neither read,
                        // deleted, nor re-fetched.
                        var localDownloadOnDevice by remember(resolvedIdentity) {
                            mutableStateOf(false)
                        }
                        LaunchedEffect(resolvedIdentity, localJob?.state) {
                            localDownloadOnDevice = resolvedIdentity?.let { id ->
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        appGraph.repository.downloadOnDeviceAssociationCount(id) > 0
                                    }.getOrNull()
                                }
                            } ?: false
                        }
                        val localOwnsPages = jobOwnsLocalPages(
                            job = localJob,
                            hasLibraryRow = localDownloadOnDevice,
                        )
                        // #C60: the Read button names the page Read will resume at —
                        // the local copy's progress when Read opens the local copy,
                        // else the cloud/online reader's. Re-read when a reader closes
                        // (remoteProgressEpoch) so a just-finished read shows at once.
                        var localProgressOrdinal by remember(resolvedIdentity) { mutableStateOf<Int?>(null) }
                        LaunchedEffect(resolvedIdentity, localOwnsPages, remoteProgressEpoch) {
                            localProgressOrdinal = if (!localOwnsPages) {
                                null
                            } else {
                                resolvedIdentity?.let { id ->
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            appGraph.repository.findDownloadItemId(id)?.let { itemId ->
                                                appGraph.repository.observeItemDetail(itemId).first()?.item?.progressOrdinal
                                            }
                                        }.getOrNull()
                                    }
                                }
                            }
                        }
                        val serializedSignedIn = zaimanhuaAccount.isSignedIn() &&
                            serializedReadSeries.providerNamespace == GalleryIdentityV1.ZAIMANHUA
                        val serializedReadTarget = if (serializedDetail) serializedDetailReadButtonTarget(
                            series = serializedReadSeries,
                            result = remoteDetailChapters,
                            shelfRows = serializedShelfRows,
                            jobs = serializedJobs,
                            cloudRows = remoteDetailCloudChapterRows,
                            remoteProgress = remoteProgressMap,
                            signedIn = serializedSignedIn,
                        ) else null
                        val remoteReadLabel = if (serializedDetail) "Read" else detailReadLabel(
                            localProgressOrdinal = localProgressOrdinal,
                            remoteProgressOrdinal = RemoteProgressPolicy.keyFor(remoteItem)
                                ?.let { remoteProgressMap[it] }?.ordinal,
                        )
                        val detailSyncRows by cloudSyncQueue.rows.collectAsStateWithLifecycle()
                        val serializedTransferPending = serializedDetail && serializedSeriesTransferPending(
                            serializedJobs, detailSyncRows, serializedReadSeries,
                        )
                        // #E103: chapters whose upload rows spent their retry budget —
                        // surfaced per-chapter ("Cloud copy failed · tap to retry") and
                        // excluded from the eternal "Uploading" pending above.
                        val serializedCloudStrandedIds = remember(detailSyncRows, serializedReadSeries) {
                            if (serializedDetail) {
                                detailSyncRows
                                    .filter {
                                        it.providerId == serializedReadSeries.providerNamespace &&
                                            it.canonicalItemId == serializedReadSeries.canonicalSeriesId &&
                                            cloudUploadRowStranded(it)
                                    }
                                    .mapNotNull { row -> row.chapterIdentity?.takeIf(String::isNotEmpty) }
                                    .toSet()
                            } else {
                                emptySet()
                            }
                        }
                        val detailCloudQueued = remember(
                            detailSyncRows,
                            remoteItem.acquisitionUrl,
                            remoteItem.upstreamAcquisitionUrl,
                            remoteDetailOriginAcquisition,
                        ) {
                            cloudUploadInFlight(
                                rows = detailSyncRows,
                                acquisitions = setOfNotNull(
                                    remoteItem.acquisitionUrl,
                                    remoteItem.upstreamAcquisitionUrl,
                                    remoteDetailOriginAcquisition,
                                ),
                                cloudBound = appGraph.currentBucketFingerprintOrNull() != null,
                            )
                        }
                        val serializedChaptersForDetail = serializedChapterResultForDetail(
                            fetched = remoteDetailChapters,
                            sourceUnavailable = remoteDetailChaptersUnavailable,
                        )
                        val serializedLabelRows = serializedChapterGroups(
                            serializedChaptersForDetail ?: com.easemic.app.feature.source.ChapterListResult(
                                emptyList(), 0L, com.easemic.app.feature.source.ChapterListCompleteness.UNKNOWN,
                            ),
                            serializedShelfRows, serializedJobs, remoteDetailCloudChapterRows,
                            signedIn = serializedSignedIn,
                        ).flatMap { group -> group.rows }
                        val serializedContinue = if (serializedDetail) serializedContinueText(
                            series = serializedReadSeries,
                            target = serializedReadTarget,
                            rows = serializedLabelRows,
                            shelfRows = serializedShelfRows,
                            remoteProgress = remoteProgressMap,
                        ) else null
                        val serializedSourceName = if (serializedDetail) {
                            appGraph.comicSourceRegistry.source(serializedReadSeries.providerNamespace)
                                ?.descriptor?.displayName
                                ?: historySourceNameFor(serializedReadSeries.providerNamespace, opdsProviders)
                        } else null
                        val serializedMetadata = remoteDetailSnapshot?.metadata
                        val serializedActions = com.easemic.app.feature.browse.serializedDetailMetadataActions(
                            providerId = serializedReadSeries.providerNamespace,
                            tags = (if (serializedReadSeries.providerNamespace == GalleryIdentityV1.ZAIMANHUA) {
                                serializedMetadata?.genres
                            } else serializedMetadata?.tags)?.valueOrNull().orEmpty(),
                            authors = serializedMetadata?.authors?.valueOrNull().orEmpty(),
                            optionsFor = { browseSourceOptionList },
                            genresFor = { browseGenres },
                            apply = ::beginTagSearch,
                        )
                        fun openLocalSerializedChapter(chapterId: String, initialOrdinal: Int? = null): Boolean {
                            val itemId = serializedItemId ?: return false
                            val shelf = serializedShelfRows.firstOrNull {
                                it.sourceChapterId == chapterId && it.pageCount > 0
                            } ?: return false
                            serializedReaderOriginRoute = route.takeIf {
                                it == AppRoute.Browse || it == AppRoute.History || it == AppRoute.Library
                            }
                            serializedReaderOriginLayerToken =
                                (tagSearchStack.top() as? TagSearchSurface.Detail)?.snapshot?.token
                            remoteDetailItem = null
                            sayRemote(null)
                            remoteDetailBusy = false
                            remoteDetailOriginAcquisition = null
                            remoteDetailOriginProviderId = null
                            remoteDetailSourceFamily = DetailSourceFamily.FRONT_INDEX
                            route = AppRoute.Reader(
                                itemId = itemId.value,
                                initialOrdinal = initialOrdinal ?: shelf.progressOrdinal,
                                readingUnitId = shelf.readingUnitId,
                                chapterId = shelf.sourceChapterId,
                            )
                            return true
                        }
                        fun openSerializedPrimary() {
                            val manifest = cloudReaderSeriesManifest
                            val target = serializedReadTarget
                            if (target == null) {
                                sayRemote(serializedNothingToReadLine(
                                    chaptersLoaded = remoteDetailChapters != null,
                                    sourceUnavailable = remoteDetailChaptersUnavailable,
                                    seriesEmptyLine = remoteDetailSeriesEmptyLine,
                                ))
                                return
                            }
                            val chapterId = target.chapterId
                            if (openLocalSerializedChapter(chapterId, target.initialOrdinal)) return
                            val cloudResolver = manifest?.let(::cloudChapterResolver)
                            val cloudTarget = cloudResolver?.open(chapterId)
                            if (cloudTarget != null) {
                                coroutineScope.launch {
                                    val cloudItem = serializedCloudReadItem(remoteItem, libraryCloudCards)
                                    if (cloudItem == null || !awaitCloudCollectionReader(
                                        cloudItem,
                                        initialOrdinal = target.initialOrdinal,
                                        store = remoteDetailCloudStore,
                                        chapter = cloudTarget.chapter,
                                        seriesManifest = manifest,
                                        transition = cloudResolver.transition(chapterId),
                                    )) sayRemote("Couldn't open the cloud copy of this chapter")
                                }
                            } else {
                                readSerializedChapter(remoteItem, chapterId, target.initialOrdinal)
                            }
                        }
                        ComicDetailScreen(
                        subject = DetailSubject.Remote(remoteItem),
                        detailRegistry = appGraph.sourceDetailRegistry,
                        zaimanhuaSignedIn = serializedSignedIn,
                        onZaimanhuaSignIn = launchZaimanhuaSignIn,
                        readLabel = remoteReadLabel,
                        serializedDetail = serializedDetail,
                        serializedChapters = serializedChaptersForDetail,
                        serializedEmptyLine = remoteDetailSeriesEmptyLine,
                        serializedChapterShelfRows = serializedShelfRows,
                        serializedChapterJobs = serializedJobs,
                        serializedCloudChapterRows = remoteDetailCloudChapterRows,
                        serializedCloudStrandedChapterIds = serializedCloudStrandedIds,
                        onRetryCloudChapter = { chapterId ->
                            // #E103: same revival shape as the Activity's manual Retry —
                            // park the stranded row WAITING (keeping its checkpoint),
                            // then drive the pump now.
                            coroutineScope.launch(Dispatchers.IO) {
                                runCatching {
                                    appGraph.cloudSyncQueue.snapshot()
                                        .firstOrNull {
                                            it.providerId == serializedReadSeries.providerNamespace &&
                                                it.canonicalItemId == serializedReadSeries.canonicalSeriesId &&
                                                it.chapterIdentity == chapterId
                                        }
                                        ?.let { row ->
                                            appGraph.cloudSyncQueue.upsert(
                                                row.copy(
                                                    stage = CloudSyncStage.WAITING,
                                                    errorMessage = null,
                                                    preflightPending = false,
                                                ),
                                            )
                                        }
                                }
                                CloudIngestWorker.kickNow(context)
                            }
                        },
                        onDownloadChapters = if (serializedDetail) {
                            // #E73 spec §4: local-only chapter fetches — no
                            // bookmark write, no cloud intent, no follow. The
                            // picker hands back ids+labels and this is the one
                            // enqueue (locator + upsertEnqueue + pump kick).
                            { requests ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    val chapterList = serializedChaptersForDetail
                                    if (chapterList != null) {
                                        runCatching {
                                            appGraph.enqueueSerializedChapterDownloads(
                                                series = serializedSeriesIdentity,
                                                title = remoteItem.title,
                                                coverLocator = remoteItem.coverUrl,
                                                chapterList = chapterList,
                                                chapters = requests,
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            null
                        },
                        serializedRemoteProgress = remoteProgressMap,
                        serializedTransferPending = serializedTransferPending,
                        serializedSnapshot = remoteDetailSnapshot,
                        serializedSourceName = serializedSourceName,
                        serializedContinue = serializedContinue,
                        serializedIdentity = serializedReadSeries,
                        serializedOrderStore = appGraph.serializedChapterOrderStore,
                        keepOnlyCloud = cloudReleaseAfterUploadEnabled,
                        onChapterReadOnline = { chapterId ->
                            val onlineItem = onlineReadItemFor(
                                remoteItem,
                                serializedReadSeries,
                                remoteDetailOriginAcquisition,
                            )
                            readSerializedChapter(onlineItem, chapterId)
                        },
                        onDeleteSavedChapter = { chapterId ->
                            coroutineScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { appGraph.deleteSavedSerializedChapters(serializedSeriesIdentity, chapterId) }
                                        .getOrElse { SerializedDeleteResult.Failed }
                                }
                                sayRemote(when (result) {
                                    is SerializedDeleteResult.Deleted, SerializedDeleteResult.NothingSaved -> null
                                    SerializedDeleteResult.RefusedActive -> "Wait for the downloads to finish first."
                                    SerializedDeleteResult.RefusedUpload -> "Wait for the cloud upload to finish first."
                                    SerializedDeleteResult.Failed -> "Saved chapter could not be deleted."
                                })
                            }
                        },
                        busy = remoteDetailBusy,
                        metadataLoading = remoteDetailMetadataLoading,
                        statusMessage = remoteDetailStatus,
                        presence = presence,
                        cloudBound = libraryCloudReady,
                        cloudQueued = detailCloudQueued,
                        sourceFamily = remoteDetailSourceFamily,
                        onBack = {
                            if (tagSearchStack.top() is TagSearchSurface.Detail) {
                                popTagSearchSurface()
                            } else {
                                remoteDetailItem = null
                                serializedReaderOriginRoute = null
                                sayRemote(null)
                                remoteDetailBusy = false
                                remoteDetailOriginAcquisition = null
                                remoteDetailOriginProviderId = null
                                remoteDetailSourceFamily = DetailSourceFamily.FRONT_INDEX
                            }
                        },
                        onCollect = { collectBookmark(remoteItem) },
                        onRead = {
                            if (serializedDetail) {
                                openSerializedPrimary()
                            } else {
                            val switchState = DetailSwitchState.from(
                                DetailSwitchFacts(
                                    cloudBound = libraryCloudReady,
                                    collected = presence == ResourcePresence.BOOKMARKED ||
                                        presence == ResourcePresence.CLOUD,
                                    uploaded = presence == ResourcePresence.CLOUD && !serializedTransferPending,
                                    downloadState = localJob?.state,
                                    cloudQueued = detailCloudQueued,
                                    delistedAtSource = localJob?.isDelistedAtSource() == true,
                                    downloadOwnsLocalPages = localOwnsPages,
                                ),
                            )
                            coroutineScope.launch {
                                val resolver = ReadRouteResolver(
                                    openLocal = { _ ->
                                        val identity = resolvedIdentity
                                        val itemId = identity?.let {
                                            withContext(Dispatchers.IO) {
                                                appGraph.repository.findDownloadItemId(it)
                                            }
                                        }
                                        if (itemId == null) {
                                            null
                                        } else {
                                            remoteDetailItem = null
                                            sayRemote(null)
                                            // A32 S2 (J3): the origin belongs to
                                            // the Cloud card we are leaving. The
                                            // local Detail's own Collect consults
                                            // it as an upstream fallback, so
                                            // carrying it across would let one
                                            // comic's collect pre-flight decide
                                            // against another comic's record.
                                            remoteDetailOriginAcquisition = null
                                            remoteDetailOriginProviderId = null
                                            route = AppRoute.Detail(itemId.value)
                                            Unit
                                        }
                                    },
                                    openCloud = { target ->
                                        val listed = listedCloudItemFor(target)
                                        if (listed == null) {
                                            null
                                        } else if (
                                            awaitCloudCollectionReader(listed, store = remoteDetailCloudStore)
                                        ) {
                                            Unit
                                        } else {
                                            null
                                        }
                                    },
                                    openOnline = { target ->
                                        readOnline(target)
                                    },
                                )
                                resolver.resolve(
                                    item = remoteItem,
                                    state = switchState,
                                    localAvailable = resolvedIdentity != null,
                                )
                            }
                            }
                        },
                        onReadOnline = {
                            if (serializedDetail) {
                                openSerializedPrimary()
                            } else {
                                readOnline(remoteItem)
                            }
                        },
                        onChapterRead = { chapterId ->
                            val manifest = cloudReaderSeriesManifest
                            val cloudTarget = manifest?.let {
                                cloudChapterResolver(it).open(chapterId)
                            }
                            if (cloudTarget == null) {
                                readSerializedChapter(remoteItem, chapterId)
                            } else {
                                coroutineScope.launch {
                                    val cloudItem = serializedCloudReadItem(remoteItem, libraryCloudCards)
                                    if (cloudItem == null || !awaitCloudCollectionReader(
                                        cloudItem,
                                        store = remoteDetailCloudStore,
                                        chapter = cloudTarget.chapter,
                                        seriesManifest = manifest,
                                        transition = cloudChapterResolver(manifest).transition(chapterId),
                                    )) sayRemote("Couldn't open the cloud copy of this chapter")
                                }
                            }
                        },
                        onChapterLocalRead = { chapter ->
                            openLocalSerializedChapter(chapter.chapterId)
                        },
                        onRetryChapter = { jobId ->
                            coroutineScope.launch(Dispatchers.IO) {
                                runCatching { appGraph.downloadJobStore.retry(jobId) }
                                    .onSuccess { DownloadPumpWorker.kick(context) }
                            }
                        },
                        onDeleteSavedSerializedChapters = if (serializedDetail) {
                            {
                                // The chapter action owns the series identity. It must never
                                // fall through to the gallery delete path, whose guard rejects
                                // serialized jobs and whose identity points at the wrong tree.
                                sayRemote(null)
                                coroutineScope.launch {
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) {
                                            appGraph.deleteSavedSerializedChapters(serializedSeriesIdentity)
                                        }
                                    }.getOrElse { SerializedDeleteResult.Failed }
                                    when (result) {
                                        is SerializedDeleteResult.Deleted,
                                        SerializedDeleteResult.NothingSaved,
                                        -> sayRemote(null)
                                        SerializedDeleteResult.RefusedActive -> sayRemote(
                                            "Wait for the downloads to finish first.",
                                            acquisition = remoteItem.acquisitionUrl,
                                        )
                                        SerializedDeleteResult.RefusedUpload -> sayRemote(
                                            "Wait for the cloud upload to finish first.",
                                            acquisition = remoteItem.acquisitionUrl,
                                        )
                                        SerializedDeleteResult.Failed -> sayRemote(
                                            "Saved chapters could not be deleted.",
                                            acquisition = remoteItem.acquisitionUrl,
                                        )
                                    }
                                }
                            }
                        } else {
                            null
                        },
                        onUncollect = { uncollectBookmark(remoteItem) },
                        onPreviewPageClick = { ordinal ->
                            if (presence.usesCloudCollection()) {
                                coroutineScope.launch {
                                    val identity = resolvedIdentity
                                    if (localOwnsPages && identity != null) {
                                        val itemId = withContext(Dispatchers.IO) {
                                            appGraph.repository.findDownloadItemId(identity)
                                        }
                                        if (itemId != null) {
                                            serializedReaderOriginRoute = null
                                            remoteDetailItem = null
                                            sayRemote(null)
                                            route = AppRoute.Reader(itemId.value, ordinal)
                                            return@launch
                                        }
                                    }
                                    listedCloudItemFor(remoteItem)?.let { target ->
                                        openCloudCollectionReader(
                                            target,
                                            initialOrdinal = ordinal,
                                            store = remoteDetailCloudStore,
                                        )
                                    }
                                }
                            } else {
                                readOnline(remoteItem, initialOrdinal = ordinal)
                            }
                        },
                        onPreviewWarmThrough = { endExclusive ->
                            warmRemoteDetailPreviews(endExclusive)
                        },
                        localDownloadAvailable = resolvedIdentity != null &&
                            appGraph.comicSourceRegistry.source(remoteItem.providerId)?.descriptor?.serialized != true,
                        localDownloadState = localJob?.state,
                        localDownloadOwnsLocalPages = localOwnsPages,
                        localDownloadDelistedAtSource = localJob?.isDelistedAtSource() == true,
                        localDownloadUnavailableReason =
                            (identityResult as? BrowseGalleryIdentityResult.Unavailable)?.reason,
                        onLocalDownload = {
                            // Serialized chapters are queued by the chapter
                            // rows in Slice 4b. Keep the generic series
                            // Download switch inert until then.
                            if (appGraph.comicSourceRegistry.source(remoteItem.providerId)?.descriptor?.serialized == true) {
                                return@ComicDetailScreen
                            }
                            val identity = resolvedIdentity
                            if (identity != null) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    if (presence.usesCloudCollection()) {
                                        val cloudId = cloudComicIdForDownload
                                        if (cloudId.isNullOrBlank()) return@launch
                                        appGraph.downloadLocatorStore.putKeepingCatalogFacts(
                                            identity,
                                            CloudLocalDownloadIdentity.locator(
                                                cloudComicId = cloudId,
                                                title = remoteItem.title,
                                                coverUrl = remoteItem.coverUrl,
                                                // S3 review L2c: this locator is
                                                // what FINISH commits from, and
                                                // the detail row has the facts.
                                                // Round 2: through the one
                                                // builder — a cloud row keeps
                                                // its creators in artists/groups,
                                                // not in `tags`.
                                                tags = remoteItem.locatorCatalogTags(),
                                                language = remoteItem.language,
                                                category = remoteItem.category,
                                                browseSourceId = remoteDetailOriginProviderId,
                                            ),
                                        )
                                    } else {
                                        val acquisition = remoteDetailOriginAcquisition
                                            ?.takeIf { it.isNotBlank() }
                                            ?: remoteItem.acquisitionUrl
                                        if (!acquisition.isNullOrBlank()) {
                                            appGraph.downloadLocatorStore.putKeepingCatalogFacts(
                                                identity,
                                                remoteItem.toDownloadLocator(acquisition),
                                            )
                                        }
                                    }
                                    appGraph.downloadJobStore.upsertEnqueue(
                                        identity = identity,
                                        expectedPageCount = remoteItem.pageCount?.takeIf { it > 0 },
                                        // A31 (owner bug backlog, controller review HIGH 1): without
                                        // intentLocal this job merges (via upsertEnqueue's DAO OR) into
                                        // a bare cloud intent once Collect fires, and Uncollect then
                                        // reads it as cloud-only and DISCARDs — deleting the user's
                                        // real Library download. Mirrors enqueueBrowseLocalDownload.
                                        intentLocal = true,
                                        // C4 §2.2: a Download on a comic already
                                        // in the cloud is a PULL-BACK — local
                                        // only. With a cloud intent it would
                                        // FINISH against an existing ledger row
                                        // and the every-foreground release sweep
                                        // would delete it again under ON.
                                        intentCloud = cloudReleaseAfterUploadStore.read() &&
                                            presence != ResourcePresence.CLOUD &&
                                            !remoteItem.acquisitionUrl.isNullOrBlank() &&
                                            appGraph.collectBookmarkStore.containsAcquisition(
                                                remoteItem.acquisitionUrl,
                                            ),
                                    )
                                    DownloadPumpWorker.kick(context)
                                }
                            }
                        },
                        onLocalDownloadCancel = {
                            val jobId = localJob?.id
                            if (jobId != null) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    runCatching { appGraph.downloadJobStore.cancel(jobId) }
                                }
                            }
                        },
                        onLocalDownloadRetry = {
                            val jobId = localJob?.id
                            if (jobId != null) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    runCatching { appGraph.downloadJobStore.retry(jobId) }
                                        .onSuccess { DownloadPumpWorker.kick(context) }
                                }
                            }
                        },
                        onLocalDownloadDelete = {
                            val identity = resolvedIdentity
                            if (identity != null) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    // A36 review M2: the SAME deleter the Library
                                    // Detail uses. This used to pass `{ true }`,
                                    // which dropped the row and the READY
                                    // bookkeeping but left the materialised
                                    // catalog tree on disk with nothing left
                                    // pointing at it.
                                    runCatching { appGraph.deleteDownloadedComic(identity) }
                                        .onFailure {
                                            DiagLog.w("PclDetail", "Delete download failed for ${redactIdentity(identity)}", it)
                                        }
                                }
                            }
                        },
                        onTagClick = if (serializedDetail) serializedActions.onTag else tagSearchActionFor(
                            item = remoteItem,
                            originalProviderId = remoteDetailOriginProviderId,
                        ),
                        onAuthorClick = serializedActions.onAuthor,
                    )
                        }
                    }
                    else -> when (val currentRoute = route) {
                    AppRoute.Library -> {
                    // C9: the delisted verdict for collected-only / cloud tiles
                    // (a Local tile reads it from the shelf projection). Reduced
                    // to the SAME dedup keys the composer dedups bookmarks on. OPDS is the
                    // known gap (spec §4/§9, #C12/#C19): an opds:<cfg>://<id> job key never
                    // equals the bookmark's http acquisition key — no match, never a false one.
                    val delistedJobs by remember(appGraph) { appGraph.downloadJobStore.observeDelistedJobs() }
                        .collectAsStateWithLifecycle(initialValue = emptyList())
                    // #C12: an opds:<cfg>://<id> job key never equals the bookmark's
                    // http acquisition key — translate through the job's locator.
                    // #C12 audit M1: the translation reads a locator FILE per delisted
                    // job; done in a bare remember{} it ran those disk reads on the
                    // composition thread. produceState hoists them to IO so a shelf with
                    // many delisted OPDS tiles cannot jank the frame — the slash overlay
                    // simply resolves a frame later.
                    val delistedAcquisitionKeys by produceState(emptySet<String>(), delistedJobs) {
                        value = withContext(Dispatchers.IO) {
                            delistedShelfKeys(delistedJobs) { identity -> appGraph.downloadLocatorStore.get(identity) }
                        }
                    }
                    // Activity's job flow, not the series counts: counts stay put while a
                    // chapter moves WAIT → DOWNLOAD and while ready pages land.
                    val updateActivityJobs by remember(appGraph) {
                        appGraph.downloadJobStore.observeActivityJobs()
                    }.collectAsStateWithLifecycle(initialValue = emptyList())
                    val updateReadyPages by remember(appGraph) {
                        appGraph.downloadJobStore.observeReadyPages()
                    }.collectAsStateWithLifecycle(initialValue = emptyList())
                    val projectedUpdates by produceState(
                        initialValue = emptyList<UpdateDayGroup>(),
                        updateLog,
                        state.items,
                        state.serializedSeriesLedgerKeyByItemId,
                        collectBookmarkEpoch,
                        updateActivityJobs,
                        updateReadyPages,
                        libraryCloudCards,
                    ) {
                        val groups = withContext(Dispatchers.IO) {
                            appGraph.presentChapterUpdates(updateLog)
                        }
                        val bookmarks = collectBookmarkStore.readAll()
                        value = groups.map { group ->
                            group.copy(
                                rows = group.rows.map { row ->
                                    val ledger = serializedSeriesLedgerKey(row.namespace, row.seriesId)
                                    val ledgerItemId = state.serializedSeriesLedgerKeyByItemId.entries
                                        .firstOrNull { it.value == ledger }
                                        ?.key
                                    val itemId = row.libraryItemId ?: ledgerItemId
                                    val libraryItem = state.items.firstOrNull { it.id.value == itemId }
                                    val bookmark = bookmarks.firstOrNull {
                                        isSerializedBookmark(it) && it.providerId == row.namespace && it.id == row.seriesId
                                    }
                                    row.copy(
                                        libraryItemId = itemId,
                                        seriesTitle = libraryItem?.title?.takeIf { it.isNotBlank() }
                                            ?: bookmark?.title?.takeIf { it.isNotBlank() }
                                            ?: row.seriesTitle,
                                        coverLibraryItem = libraryItem,
                                        coverUrl = if (libraryItem == null) bookmark?.coverUrl else null,
                                    )
                                },
                            )
                        }
                    }
                    val backgroundRefreshHint by produceState<String?>(
                        initialValue = null,
                        updatesSelected,
                        backgroundHintEpoch,
                        collectBookmarkEpoch,
                    ) {
                        value = if (!updatesSelected) {
                            null
                        } else {
                            withContext(Dispatchers.IO) {
                                val run = appGraph.backgroundRefreshRunStore.read()
                                backgroundRefreshHintLine(
                                    context = context,
                                    settingEnabled = appGraph.backgroundRefreshEnabledStore.read(),
                                    hasSerializedFollows = appGraph.collectBookmarkStore.readAll().any { isSerializedBookmark(it) },
                                    lastFinishedAtMillis = run.lastFinishedAtMillis,
                                    scheduleSinceMillis = run.scheduleSinceMillis ?: 0L,
                                    dismissedAtMillis = run.dismissedAtMillis,
                                    nowMillis = System.currentTimeMillis(),
                                )
                            }
                        }
                    }
                    val serializedSourceCoverEpoch by appGraph.serializedSourceCoverFiles.epoch
                        .collectAsStateWithLifecycle()
                    // uriIfValid stats and image-sniffs one file per serialized
                    // identity; in a bare remember{} a backfill epoch bump re-read
                    // every cover on the composition thread. produceState hoists
                    // the reads to IO, mirroring delistedAcquisitionKeys above.
                    // #E75: null until the first read finishes, so the shelf
                    // holds back a remote source URL instead of fetching a cover
                    // this device already has (produceState keeps the last value
                    // across key changes, so it never returns to null).
                    val serializedSourceCoverUris by produceState<Map<SeriesIdentityV2, String>?>(
                        initialValue = null,
                        libraryCloudItems,
                        collectBookmarkEpoch,
                        serializedSourceCoverEpoch,
                    ) {
                        value = withContext(Dispatchers.IO) {
                            serializedSourceCoverUris(
                                serializedSourceCoverIdentities(
                                    libraryCloudItems,
                                    collectBookmarkStore.readAll(),
                                ),
                                appGraph.serializedSourceCoverFiles::uriIfValid,
                            )
                        }
                    }
                    LibraryScreen(
                        state = state,
                        viewMode = viewMode,
                        cloudBound = libraryCloudReady,
                        cloudUnreachable = libraryCloudBlocked != null,
                        cloudSignInRejected = libraryCloudSignInRejected && libraryCloudBlocked != null,
                        onOpenImport = onImportFolder,
                        // A56: hoisted scroll states (see their declaration
                        // beside browseListState) keep this tab's position
                        // alive while its branch is disposed for another route.
                        gridState = libraryGridState,
                        listState = libraryListState,
                        onOpenItem = { itemId ->
                            filesGoneAtMillis = null
                            onOpenItem(itemId)
                            coroutineScope.launch {
                                // #E108: same target shape as the reader back —
                                // a download-only serialized series (no collect
                                // bookmark) opens the serialized detail built
                                // from its own Library facts, never the gallery
                                // shell.
                                val target = withContext(Dispatchers.IO) {
                                    val identity = (repository as? DownloadLibraryAvailability)
                                        ?.findSerializedSeriesIdentity(LibraryItemId(itemId))
                                        ?: return@withContext null
                                    serializedOpenTarget(
                                        itemId = itemId,
                                        downloadIdentity = identity,
                                        bookmarks = collectBookmarkStore.readAll(),
                                    )?.let { bookmarkFeedItem(it) }
                                        ?: repository.observeAllItems().first()
                                            .firstOrNull { it.id.value == itemId }
                                            .let { item ->
                                                localSerializedDetailItem(
                                                    identity = identity,
                                                    title = item?.title.orEmpty(),
                                                    coverUrl = item?.seriesCoverUri,
                                                )
                                            }
                                }
                                if (target != null) {
                                    route = AppRoute.Library
                                    openRemoteDetail(target, entryOrigin = DetailEntryOrigin.SAVED)
                                } else if (repository != null) {
                                    route = AppRoute.Detail(itemId)
                                }
                            }
                        },
                        onCoverOpenFailed = onCoverOpenFailed,
                        onDismissNotice = onDismissNotice,
                        onOpenSearch = onEnterLibrarySearch,
                        onCloseSearch = onExitLibrarySearch,
                        onSearchQueryChange = onLibrarySearchQueryChange,
                        onFilterChange = onLibraryFilterChange,
                        collectedBookmarkIds = remember(collectBookmarkEpoch) {
                            collectBookmarkStore.readAll()
                                .map { it.id }
                                .toSet()
                        },
                        collectedBookmarks = AppLiveDeviceBindings.collectedShelfBookmarks(
                            bindLiveDeviceSession = bindLiveDeviceSession,
                            overrideGraph = appGraphOverride != null,
                            bookmarks = remember(collectBookmarkEpoch) {
                                collectBookmarkStore.readAll()
                            },
                        ),
                        onOpenCollected = { bookmark ->
                            openRemoteDetail(
                                bookmarkFeedItem(bookmark),
                                entryOrigin = DetailEntryOrigin.SAVED,
                            )
                        },
                        cloudCollectionItems = AppLiveDeviceBindings.shelfItems(
                            bindLiveDeviceSession,
                            libraryCloudItems,
                        ),
                        cloudCollectionReady = libraryCloudReady,
                        cloudCollectionBlocked = libraryCloudBlocked,
                        cloudImportStateExists = cloudMirrorHydrationState != null,
                        cloudImportHydrationComplete = cloudMirrorHydrationState?.hydrationComplete == true,
                        // A63 (a): the Cloud tab's current mirror count feeds
                        // the "Importing · N/M" strip's numerator. #13: count only
                        // cards whose cover thumbnail has landed (coverUrl != null),
                        // so the fraction climbs with covers instead of snapping to
                        // full on tier-1 metadata rows.
                        cloudImportVisibleCards = CloudImportProgressPresentation.coveredCardCount(
                            libraryCloudItems.map { it.coverUrl != null },
                        ),
                        cloudImportTotalCards = cloudMirrorHydrationState?.totalCards,
                        // A42 remainder: the stored remote-read positions the
                        // shelf renders tile bars from.
                        remoteProgress = remoteProgressMap,
                        newChapterMarks = remember(collectBookmarkEpoch, newChapterMarkChanges) {
                            appGraph.newChapterMarks()
                        },
                        serializedSourceCoverUris = serializedSourceCoverUris,
                        delistedAcquisitionKeys = delistedAcquisitionKeys,
                        shelfLine = when {
                            filesGoneAtMillis != null -> ShelfLine(
                                text = "This comic's files are no longer on the phone.",
                                testTag = "library-files-gone-line",
                            )
                            importState.importOutcome != null -> ShelfLine(
                                text = ImportOutcomeCopy.line(importState.importOutcome),
                                testTag = "library-added-line",
                                actionLabel = ImportOutcomeCopy.actionLabel(importState.importOutcome),
                                actionTestTag = "library-added-undo",
                            )
                            else -> null
                        },
                        onShelfLineAction = {
                            importState.importOutcome?.let { outcome ->
                                importActions.onStopWatching(outcome.sourceId)
                            }
                            importActions.onDismissImportOutcome()
                        },
                        onOpenCloudCollection = { item ->
                            openRemoteDetail(item)
                        },
                        opdsLibraries = opdsProviders,
                        opdsShelfItems = libraryOpdsShelfItems,
                        opdsShelfLoading = libraryOpdsShelfLoading,
                        opdsShelfError = libraryOpdsShelfError,
                        onSelectOpds = { id ->
                            val provider = opdsProviders.firstOrNull { it.id == id }
                            if (provider != null) {
                                loadLibraryOpdsShelf(provider.id, provider.catalogUrl)
                            }
                        },
                        onOpenOpdsFolder = { url ->
                            val providerId = libraryOpdsProviderId
                            if (providerId != null) {
                                loadLibraryOpdsShelf(providerId, url)
                            }
                        },
                        onOpenOpdsItem = { item ->
                            openRemoteDetail(item, sourceFamily = DetailSourceFamily.OPDS)
                        },
                        updates = projectedUpdates,
                        updatesSelected = updatesSelected,
                        onSelectUpdates = { updatesSelected = true },
                        onSelectLibrary = { updatesSelected = false },
                        onVisibleUpdateKeys = { keys ->
                            coroutineScope.launch(Dispatchers.IO) {
                                val seenAt = System.currentTimeMillis()
                                keys.forEach { key ->
                                    val (namespace, seriesId, chapterId) = parseUpdateStableKey(key) ?: return@forEach
                                    appGraph.chapterUpdateRecorder.markSeen(namespace, seriesId, chapterId, seenAt)
                                }
                            }
                        },
                        onForceRefreshUpdates = {
                            if (!updatesRefreshing) {
                                updatesRefreshing = true
                                coroutineScope.launch(Dispatchers.IO) {
                                    try {
                                        appGraph.refreshFollowsNow()
                                    } finally {
                                        withContext(Dispatchers.Main) { updatesRefreshing = false }
                                    }
                                }
                            }
                        },
                        onOpenUpdate = ::openUpdate,
                        updatesRefreshing = updatesRefreshing,
                        backgroundRefreshHint = backgroundRefreshHint,
                        onOpenBackgroundRefreshHint = { launchBackgroundRefreshOpen(context) },
                        onDismissBackgroundRefreshHint = {
                            appGraph.backgroundRefreshRunStore.dismiss(System.currentTimeMillis())
                            backgroundHintEpoch += 1
                        },
                    )
                    }
                    AppRoute.Browse -> {
                        val layerSurface = tagSearchStack.top() as? TagSearchSurface.Results
                        val layerSession = transientBrowseSession
                        if (layerSurface != null && layerSession != null) {
                            val layerSourceId = layerSession.prefs.sourceId
                            val layerFilterShape = remember(layerSourceId, opdsProviders) {
                                sourceFilterSheetShapeFor(layerSourceId, appGraph.comicSourceRegistry)
                            }
                            BrowseScreen(
                                prefs = layerSession.prefs,
                                filterSheetShape = layerFilterShape,
                                filterState = layerSession.filterState,
                                query = layerSession.query,
                                results = layerSession.results,
                                knownTotalItems = layerSession.knownTotalItems,
                                loading = layerSession.loading,
                                loadingMore = layerSession.loadingMore,
                                canLoadMore = layerSession.nextContinuation != null,
                                loadMoreError = layerSession.loadMoreError,
                                errorMessage = layerSession.error,
                                sourceUnreachable = opdsProviders.firstOrNull { it.id == layerSourceId }?.isDimmed == true,
                                sourceLabel = browseSourceLabel(
                                    layerSourceId,
                                    opdsProviders,
                                    appGraph.comicSourceRegistry,
                                ),
                                sourceOptions = browseSourceOptionList,
                                genres = browseGenres,
                                feedNotice = layerSession.notice,
                                onZaimanhuaSignIn = launchZaimanhuaSignIn,
                                filterOpen = transientBrowseFilterOpen,
                                onQueryChange = { query ->
                                    transientBrowseSession = layerSession.copy(
                                        query = query,
                                        jmSearchSession = layerSession.jmSearchSession.submitted(),
                                        loadedKey = null,
                                        generation = layerSession.generation + 1,
                                    )
                                },
                                onQueryEdit = {
                                    if (layerSession.jmSearchSession.scope != null) {
                                        transientBrowseSession = layerSession.copy(
                                            jmSearchSession = layerSession.jmSearchSession.edited(),
                                            loadedKey = null,
                                            generation = layerSession.generation + 1,
                                        )
                                    }
                                },
                                jmSearchScope = transientBrowseKey?.jmSearchScope,
                                onJmSearchScopeDismiss = {
                                    transientBrowseSession = layerSession.copy(
                                        jmSearchSession = JmSearchSession(),
                                        loadedKey = null,
                                        generation = layerSession.generation + 1,
                                    )
                                },
                                onOpenFilter = { transientBrowseFilterOpen = true },
                                onCloseFilter = { transientBrowseFilterOpen = false },
                                onApplyFilter = { state ->
                                    transientBrowseSession = layerSession.copy(
                                        filterState = state,
                                        loadedKey = null,
                                        generation = layerSession.generation + 1,
                                    )
                                    transientBrowseFilterOpen = false
                                },
                                onFilterChipDismiss = { state ->
                                    transientBrowseSession = layerSession.copy(
                                        filterState = state,
                                        loadedKey = null,
                                        generation = layerSession.generation + 1,
                                    )
                                },
                                onBack = ::popTagSearchSurface,
                                onSourceChange = { sourceId ->
                                    transientBrowseSession = layerSession.copy(
                                        prefs = layerSession.prefs.copy(sourceId = sourceId),
                                        query = "",
                                        filterState = SourceFilterState(),
                                        jmSearchSession = JmSearchSession(),
                                        loadedKey = null,
                                        generation = layerSession.generation + 1,
                                    )
                                },
                                onItemClick = { item ->
                                    if (item.acquisitionUrl != null) {
                                        openTagSearchResult(item)
                                    }
                                },
                                onLoadMore = ::loadMoreTransientBrowse,
                                onRetryLoadMore = ::loadMoreTransientBrowse,
                                listState = transientBrowseListState,
                            )
                        } else if (browseSourceListVisible) {
                            BrowseSourceList(
                                sources = browseSourceList,
                                onSourceClick = { sourceId ->
                                    val differentSource = sourceId != browsePrefs.sourceId
                                    browseSourceListVisible = false
                                    if (differentSource || browseFeedUrl != null || browseFeedProviderId != null) {
                                        resetBrowseFeed()
                                    } else {
                                        // Same source, no subsection: the key comes out EQUAL, so
                                        // the reload effect will not re-run and a clear would be
                                        // permanent. Bump only (a load-more may be in flight).
                                        invalidateBrowseFeed()
                                    }
                                    if (differentSource) {
                                        browseQuery = ""
                                        jmSearchSession = JmSearchSession()
                                    }
                                    browsePrefs = browsePrefs.copy(sourceId = sourceId)
                                    browsePrefsStore.write(browsePrefs)
                                    coroutineScope.launch {
                                        browseFilterStates = browseFilterStates +
                                            (sourceId to browseFilterStore.get(sourceId))
                                    }
                                    browseFeedUrl = null
                                    browseFeedProviderId = null
                                },
                                onAddCatalogClick = { route = AppRoute.OpdsCatalogs },
                            )
                        } else {
                        val browseFilterState =
                            browseFilterStates[browsePrefs.sourceId] ?: SourceFilterState()
                        val filterSheetShape = remember(browsePrefs.sourceId, opdsProviders) {
                            sourceFilterSheetShapeFor(browsePrefs.sourceId, appGraph.comicSourceRegistry)
                        }
                        BrowseScreen(
                                prefs = browsePrefs,
                                filterSheetShape = filterSheetShape,
                                filterState = browseFilterState,
                                query = browseQuery,
                                results = browseResults,
                                knownTotalItems = browseKnownTotalItems,
                                loading = browseLoading,
                                loadingMore = browseLoadingMore,
                                canLoadMore = browseNextContinuation != null,
                                loadMoreError = browseLoadMoreError,
                                errorMessage = browseError,
                                sourceUnreachable = opdsProviders
                                    .firstOrNull { it.id == browsePrefs.sourceId }
                                    ?.isDimmed == true ||
                                    (browseError != null &&
                                        opdsProviders.any { it.id == browsePrefs.sourceId }),
                                sourceLabel = browseHeaderSourceLabel,
                                sourceOptions = browseSourceOptionList,
                                genres = browseGenres,
                                feedNotice = browseFeedNotice,
                                onZaimanhuaSignIn = launchZaimanhuaSignIn,
                                filterOpen = browsePrefsOpen,
                                onQueryChange = {
                                    // #D2: only Search (or clear) submits a new ordinary query.
                                    invalidateBrowseFeed()
                                    browseQuery = it
                                    jmSearchSession = jmSearchSession.submitted()
                                },
                                onQueryEdit = {
                                    if (jmSearchSession.scope != null) {
                                        jmSearchSession = jmSearchSession.edited()
                                        invalidateBrowseFeed()
                                        browseLoading = false
                                        browseLoadingMore = false
                                        browseNextContinuation = null
                                        browseLoadMoreError = null
                                    }
                                },
                                jmSearchScope = browseSessionKey.jmSearchScope,
                                onJmSearchScopeDismiss = {
                                    val scope = jmSearchSession.scope
                                    if (scope != null) {
                                        jmSearchSession = JmSearchSession()
                                        invalidateBrowseFeed()
                                        browseQuery = browseQuery
                                    }
                                },
                                onOpenFilter = { browsePrefsOpen = true },
                                onCloseFilter = { browsePrefsOpen = false },
                                onApplyFilter = { state ->
                                    // C26 audit MEDIUM-1: the ACTIVE source's filter state is
                                    // part of BrowseFeedSessionKey. Bump only — Apply with
                                    // nothing changed produces an EQUAL state, hence an equal key.
                                    invalidateBrowseFeed()
                                    val sourceId = browsePrefs.sourceId
                                    browseFilterStates = browseFilterStates + (sourceId to state)
                                    coroutineScope.launch {
                                        browseFilterStore.put(sourceId, state)
                                    }
                                },
                                onFilterChipDismiss = { state ->
                                    // C26 audit MEDIUM-1: same key as onApplyFilter.
                                    invalidateBrowseFeed()
                                    val sourceId = browsePrefs.sourceId
                                    browseFilterStates = browseFilterStates + (sourceId to state)
                                    coroutineScope.launch {
                                        browseFilterStore.put(sourceId, state)
                                    }
                                },
                                onBack = {
                                    // Level-2 header back arrow (T11 chrome):
                                    // pops to the Level-1 source list.
                                    // The list is an OVERLAY over the still-selected source's feed:
                                    // browseFeedUrl/browseFeedProviderId are already null for every
                                    // non-OPDS source and for an OPDS root feed, so nothing in
                                    // browseSessionKey changes and the reload effect never re-runs.
                                    // Bump only — clearing here left a permanently empty feed after
                                    // "Back to sources" → reselect the same source (device 2026-09-21).
                                    invalidateBrowseFeed()
                                    browseFeedUrl = null
                                    browseFeedProviderId = null
                                    browseSourceListVisible = true
                                },
                                onSourceChange = { sourceId ->
                                    val differentSource = sourceId != browsePrefs.sourceId
                                    if (differentSource || browseFeedUrl != null || browseFeedProviderId != null) {
                                        resetBrowseFeed()
                                    } else {
                                        // Same source, no subsection: the key comes out EQUAL, so
                                        // the reload effect will not re-run and a clear would be
                                        // permanent. Bump only (a load-more may be in flight).
                                        invalidateBrowseFeed()
                                    }
                                    if (differentSource) {
                                        browseQuery = ""
                                        jmSearchSession = JmSearchSession()
                                    }
                                    browsePrefs = browsePrefs.copy(sourceId = sourceId)
                                    browsePrefsStore.write(browsePrefs)
                                    coroutineScope.launch {
                                        browseFilterStates = browseFilterStates +
                                            (sourceId to browseFilterStore.get(sourceId))
                                    }
                                    browseFeedUrl = null
                                    browseFeedProviderId = null
                                },
                                onItemClick = { item ->
                                    when {
                                        item.subsectionUrl != null -> {
                                            saveBrowseScrollPosition()
                                            // C26 audit MEDIUM-1: the reported flow — tapping a
                                            // subsection while the root feed's load-more is in
                                            // flight used to let the root's next page append to
                                            // the subsection's entries. A non-null subsection url
                                            // is a guaranteed re-key, so the clear is safe here.
                                            resetBrowseFeed()
                                            browseFeedUrl = item.subsectionUrl
                                            browseFeedProviderId = item.providerId
                                        }
                                        item.acquisitionUrl != null -> openRemoteDetail(item)
                                    }
                                },
                                onLoadMore = { loadMoreBrowse() },
                                onRetryLoadMore = { loadMoreBrowse() },
                                listState = browseListState,
                            )
                        }
                    }
                    AppRoute.Activity -> {
                        val syncRows by cloudSyncQueue.rows.collectAsStateWithLifecycle()
                        val activityDownloadRows = rememberActivityDownloadRows(appGraph)
                        // E15 Task 3a: the in-flight page tracker. While any
                        // page is in flight the screen re-reads the slow counts
                        // on a short ticker (see ActivityScreen), so a page
                        // crossing 30 s changes its row on time alone.
                        val inFlightPages by remember(appGraph) {
                            appGraph.downloadProgressState.inFlight
                        }.collectAsStateWithLifecycle()
                        val serializedJobs by remember(appGraph) {
                            appGraph.downloadJobStore.observeSerializedChapterJobs()
                        }.collectAsStateWithLifecycle(initialValue = emptyList())
                        // #E108: titles include download-only series (no bookmark),
                        // so refresh when jobs change as well as on bookmark epochs.
                        val serializedSeriesTitles by produceState(
                            initialValue = emptyMap<Pair<String, String>, String>(),
                            serializedJobs,
                            collectBookmarkEpoch,
                        ) {
                            value = withContext(Dispatchers.IO) { appGraph.serializedSeriesTitles() }
                        }
                        val serializedPopulations by produceState<Map<SeriesIdentityV2, SerializedChapterPopulation>>(
                            initialValue = emptyMap(),
                            serializedJobs,
                            syncRows,
                            cloudBucketFingerprint,
                        ) {
                            value = appGraph.serializedActivityPopulations(serializedJobs, syncRows)
                        }
                        // C43: prefs-backed markers, re-read whenever a row
                        // changes (a resumed pump clears them on its first
                        // protected start, which also moves a row).
                        val backgroundPause = remember(syncRows, activityDownloadRows) {
                            appGraph.backgroundPauseState()
                        }
                        ActivityScreen(
                            downloadRows = activityDownloadRows,
                            syncRows = syncRows,
                            nowMs = System.currentTimeMillis(),
                            backgroundPause = backgroundPause,
                            anyPageInFlight = inFlightPages.isNotEmpty(),
                            slowPages = { appGraph.downloadProgressState.slowPageCounts() },
                            // A52: the import/scan feed. The tracker instance
                            // is the app root's (see importActivityTracker
                            // above) so its receipts survive tab navigation;
                            // the row derivation itself runs here — inside the
                            // Activity route — following the
                            // rememberActivityDownloadRows pattern, and the
                            // route's visit boundary (see
                            // rememberActivityImportRows) clears the previous
                            // visit's Done receipts on re-entry.
                            importRows = rememberActivityImportRows(
                                appGraph,
                                importActivityTracker,
                            ),
                            // A27 (owner bug backlog, ledger 2026-09-05): the
                            // same "is a bucket bound" signal the Library/
                            // Cloud tabs already pass as their own
                            // `cloudBound` param (see the other three
                            // `cloudBound = libraryCloudReady` call sites in
                            // this file) — purely `fp != null`, updated by
                            // the LaunchedEffect(cloudBucketFingerprint) that
                            // sets libraryCloudReady elsewhere in this
                            // composable.
                            cloudBound = libraryCloudReady,
                            serializedPopulations = serializedPopulations,
                            seriesTitles = serializedSeriesTitles,
                            onOpenDownload = { row ->
                                // B2: open the item's Detail (remote switches
                                // screen — works for in-flight rows too, unlike
                                // local Detail which needs a committed library row).
                                // A12: canonical id so this converges with the
                                // same gallery's history row from Browse.
                                openRemoteDetail(
                                    BrowseFeedItem(
                                        id = activityNavItemId(row.acquisitionUrl) ?: row.jobId,
                                        title = row.title,
                                        providerId = row.providerId,
                                        acquisitionUrl = row.acquisitionUrl,
                                        coverUrl = row.coverUrl,
                                    ),
                                )
                            },
                            onOpenSync = { row ->
                                // A12: same id-normalization as onOpenDownload above.
                                openRemoteDetail(
                                    BrowseFeedItem(
                                        id = activityNavItemId(row.id) ?: row.id,
                                        title = row.title,
                                        providerId = row.providerId ?: "",
                                        acquisitionUrl = row.id,
                                        coverUrl = row.coverUrl,
                                    ),
                                )
                            },
                            onRetryDownload = { row ->
                                // A11: FAILED rows use the same Detail Retry
                                // path as onLocalDownloadRetry above. Parked
                                // rows (state DOWNLOAD, resumeNotBeforeMs in
                                // the future) are not FAILED, so retry()'s
                                // FAILED-only precondition doesn't apply —
                                // there is no dedicated per-job "un-park" API,
                                // so recordFailure is reused purely to zero
                                // resumeNotBeforeMs while keeping the job's
                                // WAIT/DOWNLOAD state and recorded reason.
                                // C9: a GONE row never gets Retry; defence in
                                // depth against a stale row object.
                                if (row.state != DownloadJobState.GONE) {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        runCatching {
                                            if (row.state == DownloadJobState.FAILED) {
                                                appGraph.downloadJobStore.retry(row.jobId)
                                            } else {
                                                appGraph.downloadJobStore.recordFailure(
                                                    jobId = row.jobId,
                                                    failureClass = row.failureClass ?: "UNSPECIFIED",
                                                    detail = row.failureDetail,
                                                    resumeNotBeforeMs = 0L,
                                                )
                                            }
                                        }.onSuccess { DownloadPumpWorker.kick(context) }
                                    }
                                }
                            },
                            onOpenSeries = { summary: SerializedActivitySummary ->
                                // #E108: tapping a download-only series row must
                                // open its detail even with no collect bookmark —
                                // the summary's own facts build the same target.
                                val bookmark = collectBookmarkStore.readAll()
                                    .asSequence()
                                    .filter(::isSerializedBookmark)
                                    .firstOrNull { candidate ->
                                        val series = seriesIdentityFor(bookmarkFeedItem(candidate))
                                        series.providerNamespace == summary.providerId &&
                                            series.canonicalSeriesId == summary.seriesId
                                    }
                                openRemoteDetail(
                                    bookmark?.let { bookmarkFeedItem(it) }
                                        ?: localSerializedDetailItem(
                                            identity = com.easemic.app.feature.download.GalleryIdentityV1(
                                                summary.providerId,
                                                summary.seriesId,
                                            ),
                                            title = summary.title,
                                            coverUrl = summary.coverUrl,
                                        ),
                                )
                            },
                            onRetrySeries = { failedJobIds ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    failedJobIds.forEach { jobId ->
                                        runCatching { appGraph.downloadJobStore.retry(jobId) }
                                    }
                                    DownloadPumpWorker.kick(context)
                                }
                            },
                            onRetryCloudSeries = { targets ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    if (com.easemic.app.feature.activity.retryCloudSeriesRows(
                                            cloudSyncQueue, targets, cloudBound = appGraph.currentBucketFingerprintOrNull() != null,
                                        )
                                    ) {
                                        CloudIngestWorker.kickNow(context)
                                    }
                                }
                            },
                            onRetrySync = { row ->
                                // #C22 BLOCKING-1 / HIGH-1: what Retry means
                                // depends on what actually failed, and the row's
                                // own message is the only carrier of that — the
                                // routing itself is pure and JVM-pinned in
                                // CollectPreflightStatusTest.
                                when (CollectPreflightStatus.retryAction(row.errorMessage)) {
                                    SyncRetryAction.REQUEUE_UPLOAD -> {
                                        // A11: no dedicated sync "retry" method on
                                        // CloudSyncQueue — reuse its general upsert()
                                        // to flip the row back to WAITING (the same
                                        // reset a clean sync attempt applies via
                                        // onProgress) so the next CloudIngestWorker
                                        // kick picks it back up.
                                        coroutineScope.launch(Dispatchers.IO) {
                                            runCatching {
                                                cloudSyncQueue.upsert(
                                                    row.copy(
                                                        stage = CloudSyncStage.WAITING,
                                                        errorMessage = null,
                                                        // Re-review r2 HIGH-1: a re-queue is a worker
                                                        // row, never a placeholder — clear the bit here
                                                        // so the invariant is local, not emergent.
                                                        preflightPending = false,
                                                    ),
                                                )
                                            }.onSuccess { CloudIngestWorker.kickNow(context) }
                                        }
                                    }
                                    SyncRetryAction.RECOLLECT -> {
                                        // #C22 BLOCKING-1: the preflight never reached a
                                        // decision — run the Collect again from the
                                        // bookmark (beginPreflight replaces the parked row).
                                        val bookmark = collectBookmarkStore.readAll().firstOrNull { it.acquisitionUrl == row.id }
                                        if (bookmark != null) {
                                            collectBookmark(bookmarkFeedItem(bookmark), announce = false)
                                        } else {
                                            cloudSyncQueue.remove(row.id)
                                        }
                                    }
                                    SyncRetryAction.REDOWNLOAD_AND_RECOLLECT -> {
                                        // #C22 HIGH-1: the local copy cannot be uploaded.
                                        // The row said so ("Retry re-downloads it"): drop
                                        // the unusable copy, then Collect again — the
                                        // dual-intent fetch re-downloads and the SEALED/
                                        // FINISH handoff uploads.
                                        val bookmark = collectBookmarkStore.readAll().firstOrNull { it.acquisitionUrl == row.id }
                                        val identity = row.providerId?.takeIf { it.isNotBlank() }?.let { ns ->
                                            row.canonicalItemId?.takeIf { it.isNotBlank() }?.let { GalleryIdentityV1(ns, it) }
                                        }
                                        coroutineScope.launch {
                                            if (identity != null) {
                                                // Re-review r1 M3: deleteDownloadedComic
                                                // THROWS on a cloud-only SEALED job (no
                                                // Library row), the throw was swallowed
                                                // here, and the re-collect rebuilt the
                                                // identical parked row on every tap.
                                                // Re-review r2 MEDIUM-3: branch on the
                                                // result. A copy that did not go (upload in
                                                // flight, refused discard, IO failure) makes
                                                // the re-collect rebuild the identical row;
                                                // say so instead, and let Retry try the
                                                // drop again.
                                                val dropped = runCatching { appGraph.dropUnusableLocalCopyForRecollect(identity) }
                                                    .getOrElse {
                                                        DiagLog.w("CloudSync", "retry: delete before re-download failed")
                                                        false
                                                    }
                                                if (!dropped) {
                                                    cloudSyncQueue.markFailedTerminal(row.id, CollectPreflightStatus.LOCAL_COPY_NOT_DROPPED)
                                                    return@launch
                                                }
                                            }
                                            if (bookmark != null) {
                                                collectBookmark(bookmarkFeedItem(bookmark), announce = false)
                                            } else {
                                                cloudSyncQueue.remove(row.id)
                                            }
                                        }
                                    }
                                }
                            },
                            onDismissDownload = { row ->
                                // A45: FAILED -> NONE drops the row out of
                                // observeActivityJobs and out of the revive
                                // sweep's FAILED filter, so it stays gone after
                                // process death. A parked row is not FAILED, so
                                // dismissAttentionRow would no-op on it -- cancel is
                                // the equivalent exit there. Neither deletes a
                                // fetched page or the shelf pointer.
                                // C9: a GONE row is dismissed by a persisted mark, not a state change.
                                coroutineScope.launch(Dispatchers.IO) {
                                    runCatching {
                                        if (row.state == DownloadJobState.FAILED || row.state == DownloadJobState.GONE) {
                                            appGraph.downloadJobStore.dismissAttentionRow(row.jobId)
                                        } else {
                                            appGraph.downloadJobStore.cancel(row.jobId)
                                        }
                                    }
                                }
                            },
                            onDismissSync = { row ->
                                // A45: the queue remove alone comes back --
                                // the bookmark still carries the acquisition
                                // URL and catchUpSweepAction re-collects any
                                // collected bookmark with no queue row that is
                                // absent from the bucket index. Record the
                                // dismissal first (synchronous commit, so a
                                // kill right after the tap keeps it), then drop
                                // the row. Deliberately not an uncollect: the
                                // bookmark, the local comic and any cloud
                                // object all stay.
                                coroutineScope.launch(Dispatchers.IO) {
                                    runCatching {
                                        appGraph.cloudSyncDismissalStore.dismiss(row.id, row.chapterIdentity)
                                        cloudSyncQueue.remove(row.id, chapterIdentity = row.chapterIdentity)
                                    }
                                }
                            },
                            // A52: tapping a live/done import row goes to the
                            // Folders screen where that folder's row lives.
                            // Activity is a tab, so Sources has no meaningful
                            // Activity return target — SETTINGS is the same
                            // default the More → Folders entry uses, and
                            // Back from Folders returns there.
                            onOpenImport = { _ ->
                                openSources(SourcesReturnTo.SETTINGS)
                            },
                            // A52: a failed import's "Choose folder again" is
                            // exactly the reauth repair FoldersScreen already
                            // offers for access-lost folders -- the same
                            // ImportViewModel path that reopens the SAF picker
                            // for that source.
                            onChooseFolderAgain = importActions.onReauthorizeSource,
                            // A52 (finding 4): reauthorizeSource failures set
                            // ImportViewModel's notice; the Folders screen
                            // renders it, and now Activity does too, so the
                            // tap is never silent here. Only the
                            // ReauthorizationFailed copy can arrive while the
                            // Activity tab is the origin of the action.
                            notice = importState.notice,
                            onDismissNotice = importActions.onDismissNotice,
                        )
                    }
                    AppRoute.History -> {
                        // A21: cloud rows resolve their cover from the id at
                        // render time (durable cover -> cache thumb -> placeholder)
                        // instead of trusting the snapshot taken at record time,
                        // which was null whenever the thumb wasn't cached yet.
                        // The durable path comes from the mirror's coverPath when
                        // the card is in the emitted list, else from the shared
                        // filesDir/cloud-covers/<id>.jpg convention (cold start
                        // before the first Room emission, pruned/discarded card).
                        // libraryCloudCoverUrls is in the key so a thumb landing
                        // from the shelf's fill effect re-derives the rows.
                        val cloudCoverPaths = remember(libraryCloudCards) {
                            libraryCloudCards.associate { it.cloudComicId to it.coverPath }
                        }
                        val historyRows = remember(historyEntries, cloudCoverPaths, libraryCloudCoverUrls) {
                            historyEntries.map { entry ->
                                val cloudId = CloudCollectionFeedMapper.parseCloudComicId(entry.acquisitionUrl)
                                if (cloudId == null) {
                                    entry
                                } else {
                                    entry.copy(
                                        coverUrl = historyRowCoverUrl(
                                            entry = entry,
                                            durableCoverPath = cloudCoverPaths[cloudId]
                                                ?: cloudDurableCoverPath(context.filesDir, cloudId),
                                            cacheThumbPath = cloudThumbCachePath(context.cacheDir, cloudId),
                                            // A28 review (M3): mirror coverUrlFromCacheOrNull's
                                            // predicate above -- a 0-byte file (partial write,
                                            // interrupted download) must not be trusted as a
                                            // real cover; fall back to the cache/remote path.
                                            fileExists = {
                                                val file = File(it)
                                                file.isFile && file.length() > 0
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                        HistoryScreen(
                            entries = historyRows,
                            // A56: hoisted (see declaration beside
                            // browseListState) so the History tab's scroll
                            // survives route changes.
                            listState = historyListState,
                            onOpenEntry = { entry ->
                                // Gallery rows reopen through the existing detail
                                // path; serialized rows carry their saved chapter
                                // target into the chapter read path below.
                                resolveHistoryOpenTarget(entry, browseResults)?.let { target ->
                                    val serialized = target.chapterId != null &&
                                        appGraph.comicSourceRegistry.source(target.item.providerId)
                                            ?.descriptor?.serialized == true
                                    if (serialized) {
                                        // History is the route underneath this
                                        // serialized detail; keep it for the
                                        // local chapter reader's return.
                                        openRemoteDetail(target.item, serializedResume = target)
                                    } else {
                                        route = AppRoute.Browse
                                        openRemoteDetail(target.item)
                                    }
                                }
                            },
                        )
                    }
                    AppRoute.Settings -> SettingsScreen(
                        onOpenLibrarySources = { openSources(SourcesReturnTo.SETTINGS) },
                        librarySourceCount = importState.folderRows.size,
                        // Review MEDIUM-2: the raw per-source count is
                        // independent of row status, so this no longer
                        // silently drops to 0 while a folder is Scanning or
                        // AccessLost.
                        // SEND-BACK #2 item D: sum only sources that still
                        // have a live folder row -- comicCountsBySource can
                        // carry stragglers from a source the DAO query alone
                        // didn't filter (e.g. mid-removal), so Settings and
                        // Folders must derive the total from the same list.
                        libraryComicCount = importState.folderRows.sumOf { row ->
                            importState.comicCountsBySource[row.sourceId] ?: 0
                        },
                        opdsCatalogCount = opdsProviders.size,
                        onOpenOpdsCatalogs = { route = AppRoute.OpdsCatalogs },
                        onOpenBackupRestore = { route = AppRoute.BackupRestore },
                        onOpenBridgeR2 = { route = AppRoute.BridgeR2Bind },
                        bridgeR2BucketLabel = bridgeR2BucketLabel,
                        bridgeBoundKind = bridgeR2BindPresenter.states.collectAsStateWithLifecycle().value.boundKind,
                        bridgeSignInRejected = libraryCloudSignInRejected && libraryCloudBlocked != null,
                        cloudReleaseAfterUploadEnabled = cloudReleaseAfterUploadEnabled,
                        cloudReleaseCatchUpSummary = cloudReleaseCatchUpSummary,
                        typesafeApiKey = typesafeApiKey,
                        onTypesafeApiKeyChange = { nextKey ->
                            typesafeApiKeyStore.write(nextKey)
                            typesafeApiKey = typesafeApiKeyStore.read()
                        },
                        backgroundRefreshEnabled = backgroundRefreshEnabled,
                        onBackgroundRefreshEnabledChange = { enabled ->
                            backgroundRefreshEnabled = enabled
                            appGraph.backgroundRefreshEnabledStore.write(enabled)
                            appGraph.collectedSeriesRefreshScheduler.reconcile()
                        },
                        backgroundWifiOnly = backgroundWifiOnly,
                        onBackgroundWifiOnlyChange = { wifiOnly ->
                            backgroundWifiOnly = wifiOnly
                            appGraph.backgroundRefreshWifiOnlyStore.write(wifiOnly)
                            appGraph.collectedSeriesRefreshScheduler.reconcile()
                        },
                        onCloudReleaseAfterUploadChange = { enabled ->
                            cloudReleaseAfterUploadEnabled = enabled
                            cloudReleaseAfterUploadStore.write(enabled)
                            if (enabled && !cloudReleaseAfterUploadStore.readCatchUpAsked()) {
                                cloudReleaseCatchUpPrompt = true
                            } else if (!enabled) {
                                cloudReleaseAfterUploadStore.writeCatchUpAsked(false)
                                cloudReleaseCatchUpSummary = null
                            }
                        },
                        libraryViewMode = viewMode,
                        onLibraryViewModeChange = { mode ->
                            viewMode = mode
                            libraryLayoutStore.write(mode)
                        },
                        ehentaiEnabled = ehentaiEnabled,
                        onEhentaiEnabledChange = { enabled ->
                            ehentaiEnabled = enabled
                            ehentaiEnableStore.setEnabled(enabled)
                        },
                        exhentaiAccessVerdict = ehentaiAccount.exhentaiAccess,
                        exhentaiEnabled = exhentaiEnabled,
                        onExhentaiEnabledChange = { enabled ->
                            exhentaiEnabled = exhentaiEnableStore.setEnabled(enabled)
                            if (!enabled) {
                                // Turning the source off while its shelf is
                                // on screen must not keep serving its rows:
                                // reset the Browse selection the same way the
                                // E-Hentai switch's disable does (prefs store
                                // normalize falls back to the default source).
                                // C26 review MEDIUM-3: browsePrefs.sourceId is part of
                                // BrowseFeedSessionKey, so this is a feed-identity write —
                                // bump only, since normalize can return the SAME id.
                                invalidateBrowseFeed()
                                browsePrefs = browsePrefs.copy(
                                    sourceId = normalizeBrowseSourceId(
                                        raw = browsePrefs.sourceId,
                                        ehentaiEnabled = ehentaiEnableStore.isEnabled(),
                                        nhentaiEnabled = nhentaiEnableStore.isEnabled(),
                                        zaimanhuaEnabled = ZAIMANHUA_LISTED_IN_BROWSE,
                                        jmEnabled = jmEnableStore.isEnabled(),
                                        opdsProviderIds = opdsProviders.map { it.id }.toSet(),
                                    ),
                                )
                                browsePrefsStore.write(browsePrefs)
                            }
                        },
                        // W7: the nav-row tap IS the sign-in (and the
                        // Unknown/NoExhentaiAccess re-probe — the flow's
                        // exhentai.org visit is the access check). The
                        // activity persists session + verdict; coming back
                        // here recomposes this row from the store.
                        onOpenExhentaiSignIn = {
                            context.startActivity(
                                Intent(context, EhentaiSignInActivity::class.java),
                            )
                        },
                        exhentaiFavoritesSyncEnabled = exhentaiFavoritesSyncEnabled,
                        onExhentaiFavoritesSyncEnabledChange = { enabled ->
                            exhentaiFavoritesSyncEnabled = exhentaiFavoritesSyncStore.setEnabled(enabled)
                        },
                        exhentaiFavoritesImporting = exhentaiFavoritesImporting,
                        exhentaiFavoritesStatusText = exhentaiFavoritesStatusText,
                        onImportExhentaiFavorites = {
                            if (exhentaiFavoritesImporting) return@SettingsScreen
                            val account = ehentaiAccountStore.read()
                            if (!account.effectiveUseExhentai()) {
                                exhentaiFavoritesStatusText = "Sign in to ExHentai first."
                                return@SettingsScreen
                            }
                            exhentaiFavoritesImporting = true
                            exhentaiFavoritesStatusText = "Scanning ExHentai favorites…"
                            coroutineScope.launch {
                                val client = EhentaiClient.fromAccount(account)
                                val importer = ExhentaiFavoritesImporter(
                                    fetchFavoritesPage = { nextSeekGid, pageIndex ->
                                        withContext(Dispatchers.IO) {
                                            client.fetchFavoritesPage(nextSeekGid = nextSeekGid, pageIndex = pageIndex)
                                        }
                                    },
                                    probeGalleryAvailable = { gid, token ->
                                        withContext(Dispatchers.IO) {
                                            ExhentaiFavoritesImporter.probeWithClient(client, gid, token)
                                        }
                                    },
                                    existingDedupKeys = {
                                        val bookmarkKeys = collectBookmarkStore.readAll()
                                            .mapNotNull { galleryDedupKey(it.acquisitionUrl) }
                                        val localKeys = state.downloadAcquisitionByItemId.values
                                            .mapNotNull { galleryDedupKey(it) }
                                        val cloudKeys = libraryCloudItems
                                            .mapNotNull { galleryDedupKey(it.upstreamAcquisitionUrl ?: it.acquisitionUrl) }
                                        (bookmarkKeys + localKeys + cloudKeys).toSet()
                                    },
                                    enqueueGallery = { feedItem ->
                                        withContext(Dispatchers.Main) {
                                            collectBookmark(
                                                item = feedItem,
                                                syncCloud = true,
                                                announce = false,
                                                syncExhentaiFavorite = false,
                                            )
                                        }
                                    },
                                )
                                val result = importer.run { progress ->
                                    exhentaiFavoritesStatusText = progress.formatStatusLine()
                                }
                                exhentaiFavoritesImporting = false
                                exhentaiFavoritesStatusText = when (result) {
                                    is ExhentaiFavoritesImportResult.Completed -> result.formatSummaryLine()
                                    ExhentaiFavoritesImportResult.Unauthorized ->
                                        "ExHentai session expired — sign in again."
                                    is ExhentaiFavoritesImportResult.Failed -> result.message
                                }
                            }
                        },
                        zaimanhuaSignedIn = zaimanhuaAccount.isSignedIn(),
                        zaimanhuaExpiryText = zaimanhuaExpiryText,
                        onZaimanhuaSignIn = launchZaimanhuaSignIn,
                        onZaimanhuaSignOut = {
                            zaimanhuaAccountStore.clear()
                            zaimanhuaAccount = zaimanhuaAccountStore.read()
                            ZaimanhuaWebSession.clear()
                            zaimanhuaAccountChangeEpoch += 1
                        },
                        nhentaiEnabled = nhentaiEnabled,
                        onNhentaiEnabledChange = { enabled ->
                            nhentaiEnabled = enabled
                            nhentaiEnableStore.setEnabled(enabled)
                        },
                        jmEnabled = jmEnabled,
                        onJmEnabledChange = { enabled ->
                            invalidateBrowseFeed()
                            jmEnableStore.setEnabled(enabled)
                            jmEnabled = enabled
                            if (!enabled && browsePrefs.sourceId == JmEnableStore.SOURCE_ID) {
                                browsePrefs = browsePrefsStore.read()
                                browseQuery = ""
                                jmSearchSession = JmSearchSession()
                                browseSourceListVisible = true
                            }
                        },
                        keepScreenOnEnabled = keepScreenOnEnabled,
                        onKeepScreenOnEnabledChange = { enabled ->
                            keepScreenOnEnabled = enabled
                            readerKeepScreenOnStore.write(enabled)
                        },
                        volumeKeyPagingEnabled = volumeKeyPagingEnabled,
                        onVolumeKeyPagingEnabledChange = { enabled ->
                            volumeKeyPagingEnabled = enabled
                            readerVolumeKeyPagingStore.write(enabled)
                        },
                        rtlPagingEnabled = rtlPagingEnabled,
                        onRtlPagingEnabledChange = { enabled ->
                            rtlPagingEnabled = enabled
                            readerRtlPagingStore.write(enabled)
                        },
                        fitWidthEnabled = fitWidthEnabled,
                        onFitWidthEnabledChange = { enabled ->
                            fitWidthEnabled = enabled
                            readerFitWidthStore.write(enabled)
                        },
                        maxConcurrentTransfers = maxConcurrentTransfers,
                        onMaxConcurrentTransfersChange = { value ->
                            maxConcurrentTransfers = value
                            appGraph.maxConcurrentTransfersStore.write(value)
                        },
                        cacheBudgetMb = cacheBudgetMb,
                        cacheUsageBytes = cacheUsageBytes,
                        onCacheBudgetMbChange = { value ->
                            cacheBudgetMb = value
                            coroutineScope.launch(Dispatchers.IO) {
                                // C28 audit HIGH-2: a throw out of this launch takes the
                                // process down just like the LaunchedEffect body above, and
                                // both calls touch the same `by lazy` budget (setCacheBudgetMb
                                // writes cacheBudget.maxBytes) whose failed initializer
                                // re-throws on every touch. The preference itself is written
                                // before the budget is touched, so a contained failure still
                                // persists the user's choice.
                                runCatching { appGraph.setCacheBudgetMb(value) }
                                    .onFailure { DiagLog.w("CacheBudget", "budget apply failed: ${it.javaClass.simpleName}") }
                                val usage = runCatching { appGraph.cacheUsageBytes() }.getOrDefault(0L)
                                withContext(Dispatchers.Main) {
                                    cacheBudgetMb = appGraph.cacheBudgetStore.readMb()
                                    cacheUsageBytes = usage
                                }
                            }
                        },
                        onClearCache = {
                            coroutineScope.launch(Dispatchers.IO) {
                                // C28 audit HIGH-2: clearAll's delete loops are the second
                                // live throw path (the usage sweep is the first) and this
                                // launch has no handler — an undeletable file used to take
                                // the process down from the Clear cache button.
                                runCatching { appGraph.clearRebuildableCaches() }
                                    .onFailure { DiagLog.w("CacheBudget", "clear failed: ${it.javaClass.simpleName}") }
                                val usage = runCatching { appGraph.cacheUsageBytes() }.getOrDefault(0L)
                                withContext(Dispatchers.Main) {
                                    cacheUsageBytes = usage
                                    // C1 audit HIGH-1: the shelf's write-once cover memo may
                                    // point at cloud-thumbs files that were just deleted —
                                    // forget it so every card re-probes (durable cover first).
                                    libraryCloudCoverUrls = emptyMap()
                                }
                            }
                        },
                        availableUpdate = updateOffer?.release,
                        onOpenAvailableUpdate = {
                            val rel = updateOffer?.release
                            if (rel != null) {
                                coroutineScope.launch {
                                    val cached = withContext(Dispatchers.IO) {
                                        runCatching { AppUpdateInstaller.hasVerifiedCachedApk(context, rel) }.getOrNull()
                                    }
                                    if (updateDialogPhase !is AppUpdateDialogPhase.Downloading &&
                                        updateDialogPhase !is AppUpdateDialogPhase.Verifying
                                    ) {
                                        updateDialogPhase = AppUpdateDialogPhase.Ready(alreadyDownloaded = cached != null)
                                    }
                                    updateOffer = updateOffer?.copy(showDialog = true)
                                }
                            }
                        },
                        updateCheckEnabled = updateCheckEnabled,
                        onUpdateCheckEnabledChange = { enabled ->
                            updateStore.setEnabled(enabled)
                            updateCheckEnabled = enabled
                        },
                        languagePreference = languagePreference,
                        onLanguagePreferenceChange = { pref ->
                            languageStore.writePreference(pref)
                            languagePreference = pref
                            AppI18n.applyPreference(pref)
                        },
                    )
                    AppRoute.DownloadQueue -> {
                        DownloadQueueScreen(rows = rememberDownloadQueueRows(appGraph))
                    }
                    AppRoute.CloudSyncQueue -> {
                        val syncRows by cloudSyncQueue.rows.collectAsStateWithLifecycle()
                        val io = remember(
                            bridgeR2BucketLabel,
                            bridgeR2UnlockedEpoch,
                            oauthRestCache?.first,
                        ) {
                            productObjectStore()
                        }
                        val blocked = when (io) {
                            is ByoCloudIoResult.Blocked -> io.message
                            is ByoCloudIoResult.Ready -> null
                        }
                        CloudSyncQueueScreen(rows = syncRows, blockedHint = blocked)
                    }
                    AppRoute.BackupRestore -> {
                        LaunchedEffect(Unit) {
                            withContext(Dispatchers.IO) { backupPresenter.open() }
                        }
                        BackupRestoreScreen(
                            state = backupUiState,
                            onExport = { include, pw, confirm ->
                                val problem = backupPresenter.passwordProblem(pw, confirm)
                                if (problem != null) {
                                    coroutineScope.launch { backupPresenter.export(include, pw, confirm, UriDestination(Uri.EMPTY)) }
                                } else {
                                    pendingArchiveExport = Triple(include, pw, confirm)
                                    archiveExportLauncher.launch(BackupFileNames.archive(System.currentTimeMillis()))
                                }
                            },
                            onRestore = { archiveRestoreLauncher.launch(arrayOf("*/*")) },
                            onRestoreLegacy = {
                                restorePrompt = RestorePrompt(uri = null, legacy = true)
                            },
                            onConfirmRestore = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    backupPresenter.confirmRestore {
                                        refreshCachedSettingsAfterArchiveRestore()
                                    }
                                }
                            },
                            onDismissPreview = { backupPresenter.dismissPreview() },
                            onCancelWork = { backupPresenter.cancel() },
                            onBack = { route = AppRoute.Settings },
                        )
                        restorePrompt?.let { prompt ->
                            BackupPassphraseDialog(
                                state = prompt.state,
                                onPasswordChange = { typed ->
                                    restorePrompt = prompt.copy(state = prompt.state.copy(password = typed, errorMessage = null))
                                },
                                onConfirmChange = {},
                                onSubmit = {
                                    val checked = prompt.state.validated()
                                    if (!checked.isValid()) {
                                        restorePrompt = prompt.copy(state = checked)
                                    } else {
                                        restorePrompt = null
                                        val pw = checked.password
                                        if (prompt.legacy) {
                                            // An older state-only .pclbackup (#D7 spec §7).
                                            pendingLegacyBackupPassword = pw
                                            importLegacyBackupLauncher.launch(arrayOf("*/*"))
                                        } else {
                                            val uri = checkNotNull(prompt.uri)
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val fileName = runCatching {
                                                    context.contentResolver.query(
                                                        uri,
                                                        arrayOf(OpenableColumns.DISPLAY_NAME),
                                                        null,
                                                        null,
                                                        null,
                                                    )?.use { cursor ->
                                                        if (!cursor.moveToFirst()) return@use null
                                                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                                        if (index < 0) {
                                                            null
                                                        } else {
                                                            cursor.getString(index)?.takeIf { it.isNotBlank() }
                                                        }
                                                    }
                                                }.getOrNull()
                                                backupPresenter.pickRestore(UriDestination(uri), pw, fileName)
                                            }
                                        }
                                    }
                                },
                                onDismiss = { restorePrompt = null },
                            )
                        }
                        legacyBackupResult?.let { line ->
                            AlertDialog(
                                onDismissRequest = { legacyBackupResult = null },
                                text = { Text(line) },
                                confirmButton = { TextButton(onClick = { legacyBackupResult = null }) { Text("OK") } },
                            )
                        }
                    }
                    AppRoute.BridgeR2Bind -> {
                        val lockState by libraryLockPresenter.states.collectAsStateWithLifecycle()
                        LaunchedEffect(bridgeR2BucketLabel, bridgeR2UnlockedEpoch, oauthRestCache?.first) {
                            // OAuth binds only resolve to a Ready raw store once
                            // oauthRestCache lands — without this key, an
                            // OAuth-bound library never re-fires and the lock UI
                            // (LOCKED) never renders.
                            libraryLockPresenter.refresh(bound = bridgeR2BucketLabel != null)
                        }
                        // #C50: unbound → pick a place first (layout A). A Cloudflare sign-in
                        // in flight returns to the S3 screen, whose listener takes the redirect.
                        var cloudPlace by rememberSaveable {
                            mutableStateOf(if (bridgeOAuthPkceSessionStore.load() != null) CloudStoragePlace.S3.name else null)
                        }
                        val bindState by bridgeR2BindPresenter.states.collectAsStateWithLifecycle()
                        val unbound = bindState.boundBucketLabel == null && bridgeR2BucketLabel == null
                        // #C50 "sign in again" runs the drive's sign-in screen while bound;
                        // #D16: a refused WebDAV password renews in the same place, only while renewing.
                        val webDavState by webDavBindPresenter.states.collectAsStateWithLifecycle()
                        val reauthing = cloudPlaceReauthing(cloudPlace, unbound, webDavState.renewing)
                        // #C50: system Back inside a chosen place returns to the picker, as the
                        // on-screen Back does; composed after the route-level handler, so it wins.
                        BackHandler(enabled = (unbound || reauthing) && cloudPlace != null) {
                            if (cloudPlace == CloudStoragePlace.GOOGLE_DRIVE.name) {
                                val gd = googleDriveBindPresenter.state
                                if (gd.step == DriveBindStep.CREATE_PASSWORD || gd.step == DriveBindStep.UNLOCK_PASSWORD) {
                                    googleDriveBindPresenter.onCancelPasswordStep()
                                    return@BackHandler
                                }
                                googleDriveBindPresenter.reset()
                            }
                            if (cloudPlace == CloudStoragePlace.ONEDRIVE.name) {
                                val od = oneDriveBindPresenter.state
                                if (od.step == DriveBindStep.CREATE_PASSWORD || od.step == DriveBindStep.UNLOCK_PASSWORD) {
                                    oneDriveBindPresenter.onCancelPasswordStep()
                                    return@BackHandler
                                }
                                oneDriveBindPresenter.reset()
                            }
                            if (cloudPlace == CloudStoragePlace.WEBDAV.name) {
                                val web = webDavBindPresenter.state
                                if (web.step == DriveBindStep.CREATE_PASSWORD || web.step == DriveBindStep.UNLOCK_PASSWORD) {
                                    webDavBindPresenter.onCancelPasswordStep()
                                    return@BackHandler
                                }
                                webDavBindPresenter.reset()
                            }
                            cloudPlace = null
                        }
                        if (unbound && cloudPlace == null) {
                            CloudStoragePickerScreen(
                                onPick = { picked ->
                                    if (picked == CloudStoragePlace.S3_CUSTOM) {
                                        bridgeR2BindPresenter.setPasteFormExpanded(true)
                                        cloudPlace = CloudStoragePlace.S3.name
                                    } else {
                                        if (picked == CloudStoragePlace.S3) {
                                            bridgeR2BindPresenter.setPasteFormExpanded(false)
                                        }
                                        cloudPlace = picked.name
                                    }
                                },
                                onBack = { route = AppRoute.Settings },
                                apiKeyBusy = bindState.busy,
                                apiKeyError = bindState.errorMessage,
                                onClearApiKeyError = { bridgeR2BindPresenter.setPasteFormExpanded(bindState.pasteFormExpanded) },
                                onConnectWithApiKey = { rawKey, providerOverride ->
                                    coroutineScope.launch {
                                        val ok = bridgeR2BindPresenter.onConnectWithApiKey(rawKey, providerOverride)
                                        if (ok && bridgeR2BindPresenter.state.boundBucketLabel != null) {
                                            cloudPlace = null
                                            bridgeR2BucketLabel = bridgeR2BindPresenter.state.boundBucketLabel
                                            bridgeR2UnlockedEpoch += 1
                                        }
                                    }
                                },
                            )
                        } else if ((unbound || reauthing) && cloudPlace == CloudStoragePlace.GOOGLE_DRIVE.name) {
                            OneDriveBindScreen(
                                presenter = googleDriveBindPresenter,
                                kind = DriveKind.GOOGLE_DRIVE,
                                onBound = {
                                    cloudPlace = null
                                    bridgeR2BucketLabel = bridgeR2BindPresenter.state.boundBucketLabel
                                    bridgeR2UnlockedEpoch += 1
                                },
                                onBack = {
                                    googleDriveBindPresenter.reset()
                                    cloudPlace = null
                                },
                            )
                        } else if ((unbound || reauthing) && cloudPlace == CloudStoragePlace.ONEDRIVE.name) {
                            OneDriveBindScreen(
                                presenter = oneDriveBindPresenter,
                                onBound = {
                                    cloudPlace = null
                                    bridgeR2BucketLabel = bridgeR2BindPresenter.state.boundBucketLabel
                                    bridgeR2UnlockedEpoch += 1
                                },
                                onBack = {
                                    oneDriveBindPresenter.reset()
                                    cloudPlace = null
                                },
                            )
                        } else if ((unbound || reauthing) && cloudPlace == CloudStoragePlace.WEBDAV.name) {
                            WebDavBindScreen(
                                presenter = webDavBindPresenter,
                                onBound = {
                                    cloudPlace = null
                                    bridgeR2BucketLabel = bridgeR2BindPresenter.state.boundBucketLabel
                                    bridgeR2UnlockedEpoch += 1
                                    // #D16: the detail's warm store still holds the old password — drop it.
                                    remoteDetailCloudStore = null
                                },
                                onBack = {
                                    webDavBindPresenter.reset()
                                    cloudPlace = null
                                },
                            )
                        } else BridgeR2BindScreen(
                            presenter = bridgeR2BindPresenter,
                            cloudComicCount = if (libraryCloudReady) libraryCloudCards.size else null,
                            onBack = {
                                if (unbound) cloudPlace = null else route = AppRoute.Settings
                            },
                            onDisconnect = {
                                bridgeR2BindPresenter.onDisconnect()
                                // C37-c: the process-wide token source outlives
                                // this composable, but its reset belongs off the
                                // main thread (Task 5 re-review A1) — the
                                // notifyCloudDisconnected() below already runs
                                // onCloudDisconnected() on the IO scope, and
                                // that resets the token source first thing.
                                // Replicates the former sign-out wipe: Disconnect is
                                // the only path that tears down process cloud state
                                // in the accountless model. oauthRestCache is
                                // session-backed now (a val derived from
                                // cloudProcess.oauthRestCacheFlow) and cloudProcess.reset()
                                // below already nulls it — no separate write needed.
                                cloudProcess.reset()
                                libraryCloudItems = emptyList()
                                // C29 review MEDIUM-4: V3 made this a memoised field, so
                                // Disconnect must zero it here like every other one —
                                // the fp == null branch of the mirror effect is a frame later.
                                libraryCloudCards = emptyList()
                                libraryCloudTrashCards = emptyList()
                                libraryRecycleItems = emptyList()
                                libraryCloudReady = false
                                libraryCloudBlocked = null
                                libraryCloudSignInRejected = false
                                // Cross-bucket cover cache: not keyed by bucket
                                // fingerprint, so it survives the libraryCloudCards
                                // reset above unless dropped here explicitly
                                // — otherwise a rebind can
                                // briefly pair a stale cover URI with a
                                // same-named comic in the new bucket.
                                libraryCloudCoverUrls = emptyMap()
                                bridgeR2BucketLabel = null
                                // Disconnect drops the DEK cache and gate memo too —
                                // the next bucket must re-probe from a clean slate.
                                appGraph.libraryDekStore.clear()
                                appGraph.cloudLibraryGate.reset()
                                // Third pump wipe (loyal-pipelines Slice B pump task
                                // §2): no bucket is bound anymore, so every mirror
                                // row is stale.
                                appGraph.notifyCloudDisconnected()
                                bridgeR2UnlockedEpoch += 1
                            },
                            onBoundChanged = { label ->
                                bridgeR2BucketLabel = label
                                bridgeR2UnlockedEpoch += 1
                            },
                            listUsage = {
                                (productObjectStore() as? ByoCloudIoResult.Ready)?.store?.listUsage()
                            },
                            usageGeneration = oauthRestCache?.first
                                ?: appGraph.r2BindingCache.workingBinding()?.bucket
                                ?: appGraph.currentBucketFingerprintOrNull(),
                            // Task 14: Recently deleted card. The trash listing
                            // is a live read loaded by the epoch-keyed effect
                            // above; blocked (unreachable/locked/unreadable)
                            // collapses the card to its honest status line.
                            recentlyDeletedRows = AppLiveDeviceBindings.shelfItems(
                                bindLiveDeviceSession,
                                libraryRecycleItems,
                            ),
                            recentlyDeletedUnreachable = bridgeR2BucketLabel != null &&
                                libraryCloudBlocked != null,
                            recentlyDeletedCount = if (
                                bridgeR2BucketLabel != null && libraryRecycleListedFor == bridgeR2BucketLabel
                            ) {
                                libraryRecycleItems.size
                            } else {
                                null
                            },
                            onRestoreRecentlyDeleted = { item -> restoreRecentlyDeleted(item) },
                            onDeleteRecentlyDeletedForever = { item -> deleteRecentlyDeletedForever(item) },
                            recentlyDeletedBusyIds = restoringCloudIds,
                            // #C50: a revoked drive sign-in (the token owner hit invalid_grant).
                            driveSignInExpired = bindState.boundKind.let { kind ->
                                (kind == BoundKind.ONEDRIVE && appGraph.isOneDriveBindingDead()) ||
                                    (kind == BoundKind.GOOGLE_DRIVE && appGraph.googleDriveTokenSource?.isDead() == true)
                            },
                            onDriveSignInAgain = {
                                when (bindState.boundKind) {
                                    BoundKind.ONEDRIVE -> appGraph.bridgeDeviceOneDriveStore.load()?.let {
                                        cloudPlace = CloudStoragePlace.ONEDRIVE.name
                                        oneDriveBindPresenter.onSignInAgain(it.rootItemId)
                                    }
                                    BoundKind.GOOGLE_DRIVE -> appGraph.bridgeDeviceGoogleDriveStore.load()?.let {
                                        cloudPlace = CloudStoragePlace.GOOGLE_DRIVE.name
                                        googleDriveBindPresenter.onSignInAgain(it.rootFolderId)
                                    }
                                    else -> Unit
                                }
                            },
                            // #D16: the card's "Enter the new password" opens the renew form with the saved identity.
                            onWebDavNewPassword = {
                                appGraph.bridgeDeviceWebDavStore.load()?.let {
                                    cloudPlace = CloudStoragePlace.WEBDAV.name
                                    webDavBindPresenter.onRenew(it)
                                }
                            },
                            lock = lockState,
                            onLockEvent = { event ->
                                when (event) {
                                    is LibraryLockEvent.UnlockPasswordChanged ->
                                        libraryLockPresenter.onUnlockPasswordChange(event.value)
                                    LibraryLockEvent.UnlockTapped ->
                                        coroutineScope.launch { libraryLockPresenter.unlock() }
                                    LibraryLockEvent.ChangeExpandToggled ->
                                        libraryLockPresenter.onChangeExpandToggle()
                                    is LibraryLockEvent.OldPasswordChanged ->
                                        libraryLockPresenter.onOldPasswordChange(event.value)
                                    is LibraryLockEvent.NewPasswordChanged ->
                                        libraryLockPresenter.onNewPasswordChange(event.value)
                                    is LibraryLockEvent.NewConfirmChanged ->
                                        libraryLockPresenter.onNewConfirmChange(event.value)
                                    LibraryLockEvent.ChangeTapped ->
                                        coroutineScope.launch { libraryLockPresenter.changePassword() }
                                }
                            },
                        )
                    }

                    AppRoute.OpdsCatalogs -> {
                        // On-demand health probe: only while this settings page
                        // is open. No background polling — the persisted health
                        // fields record the last observed reachability.
                        LaunchedEffect(Unit) {
                            val snapshot = opdsProviders
                            if (snapshot.isNotEmpty()) {
                                val client = OpdsCatalogClient()
                                OpdsCatalogConnect.probeAndPersistIncrementally(
                                    snapshot = snapshot,
                                    probe = { provider ->
                                        withContext(Dispatchers.IO) {
                                            OpdsCatalogConnect.probeReachability(provider, client)
                                        }
                                    },
                                    atEpochMs = { System.currentTimeMillis() },
                                    commitOutcome = { provider, reachable, observedAt ->
                                        val fresh = opdsProviderStore.readAll()
                                        val merged = OpdsCatalogConnect.applyProbeOutcome(
                                            fresh = fresh,
                                            snapshotProvider = provider,
                                            reachable = reachable,
                                            atEpochMs = observedAt,
                                        )
                                        if (merged != fresh) {
                                            // #C15 audit LOW-4: publish only alongside the persisted write,
                                            // matching Browse's probe commit. Safe because every writer keeps
                                            // `opdsProviders == opdsProviderStore.readAll()` on Main, so
                                            // `merged == fresh` already means `merged == opdsProviders`.
                                            opdsProviderStore.writeAll(merged)
                                            opdsProviders = merged
                                        }
                                    },
                                )
                            }
                        }
                        OpdsProvidersScreen(
                        providers = opdsProviders,
                        onConnect = { name, url, user, pass ->
                            val existing = opdsProviders
                            val result = withContext(Dispatchers.IO) {
                                OpdsCatalogConnect.connect(
                                    existing = existing,
                                    displayName = name,
                                    catalogUrl = url,
                                    username = user,
                                    password = pass,
                                    client = OpdsCatalogClient(),
                                )
                            }
                            if (result is OpdsConnectResult.Connected) {
                                val added = result.providers.first { next ->
                                    existing.none { it.id == next.id && it.catalogUrl == next.catalogUrl }
                                }
                                val merged = OpdsCatalogConnect.mergeNew(opdsProviders, added)
                                if (merged !== opdsProviders && merged != opdsProviders) {
                                    opdsProviderStore.writeAll(merged)
                                    opdsProviders = merged
                                }
                            }
                            result
                        },
                        onEditSave = { id, name, url, user, pass ->
                            val result = OpdsCatalogConnect.saveEdit(
                                existing = opdsProviders,
                                id = id,
                                displayName = name,
                                catalogUrl = url,
                                username = user,
                                password = pass,
                            )
                            if (result is OpdsCatalogConnectEditResult.Edited) {
                                opdsProviderStore.writeAll(result.providers)
                                opdsProviders = result.providers
                            }
                            result
                        },
                        onDisconnect = { id ->
                            val updated = OpdsCatalogConnect.disconnect(opdsProviders, id)
                            // C26 audit MEDIUM-1: opdsIds is part of BrowseFeedSessionKey, so
                            // removing a catalog re-keys the feed (and the user is off the Browse
                            // tab, whose `route` re-entry re-runs the effect anyway).
                            resetBrowseFeed()
                            opdsProviders = updated
                            opdsProviderStore.writeAll(updated)
                        },
                        onBack = { route = AppRoute.Settings },
                        )
                    }
                    is AppRoute.Sources -> {
                        FoldersScreen(
                            rows = importState.folderRows,
                            onRescanNow = importActions.onRescanSource,
                            onStopWatching = importActions.onStopWatching,
                            onChooseFolderAgain = importActions.onReauthorizeSource,
                            onImportFolder = onImportFolder,
                            onRefreshAll = importActions.onRescanAllFolders,
                            isRefreshing = importState.refreshInFlight,
                            stoppedFolder = importState.stoppedFolder,
                            onUndoStopWatching = importActions.onUndoStopWatching,
                            // Review HIGH-4: ImportUiState.notice was rendered
                            // nowhere; the Folders screen is its home now.
                            notice = importState.notice,
                            onDismissNotice = importActions.onDismissNotice,
                        )
                    }
                    is AppRoute.Detail -> {
                        val detailSurfaceToken =
                            (tagSearchStack.top() as? TagSearchSurface.Detail)?.snapshot?.token
                                ?: "local-${currentRoute.itemId}"
                        tagSearchStateHolder.SaveableStateProvider(detailSurfaceToken) {
                        val detailViewModel: DetailViewModel = viewModel(
                            key = "detail-${currentRoute.itemId}",
                            factory = DetailViewModel.Factory(
                                itemId = currentRoute.itemId,
                                repository = checkNotNull(repository),
                            ),
                        )
                        val detailState by detailViewModel.uiState.collectAsStateWithLifecycle()
                        // A32 §5: collect / cloud / download-job facts are app-scope
                        // singletons, not repository state, so the route completes the
                        // subject the ViewModel started.
                        var detailAcquisitionKey by remember(currentRoute.itemId) {
                            mutableStateOf<String?>(null)
                        }
                        var detailDownloadState by remember(currentRoute.itemId) {
                            mutableStateOf<DownloadJobState?>(null)
                        }
                        // A32 S2 / P-a: the identity is what the ··· delete acts on, and
                        // the seal is the only honest way to say a download stopped short.
                        var detailIdentity by remember(currentRoute.itemId) {
                            mutableStateOf<GalleryIdentityV1?>(null)
                        }
                        var detailTruncated by remember(currentRoute.itemId) {
                            mutableStateOf<DetailTruncatedSeal?>(null)
                        }
                        var detailDelisted by remember(currentRoute.itemId) { mutableStateOf(false) }
                        // J1: the locator is the app's own record of how this
                        // comic was acquired — the only honest basis for a
                        // Collect. Null ⇒ no Collect switch at all.
                        var detailLocator by remember(currentRoute.itemId) {
                            mutableStateOf<DownloadLocator?>(null)
                        }
                        // J4: collect/uncollect outcomes used to be written to
                        // remoteDetailStatus, which this screen never renders —
                        // so a blocked-bucket explanation was unreachable copy.
                        var detailStatus by remember(currentRoute.itemId) {
                            mutableStateOf<String?>(null)
                        }
                        // K2: which comic THIS screen is waiting to hear about.
                        var detailStatusOwner by remember(currentRoute.itemId) {
                            mutableStateOf<String?>(null)
                        }
                        // J3 backstop: any other way into this route (shelf tap,
                        // Reader back) must not inherit a Cloud card's origin or
                        // its leftover status line.
                        LaunchedEffect(currentRoute.itemId) {
                            remoteDetailOriginAcquisition = null
                            remoteDetailOriginProviderId = null
                            sayRemote(null)
                        }
                        // J4: collect/uncollect report through remoteDetailStatus,
                        // which only the catalog screen renders. Mirror it here so
                        // the outcome — including the synchronous blocked-bucket
                        // explanation and the later ingest updates — is readable
                        // on the screen that triggered it. K2: only when the
                        // announcement is about the comic this screen asked about.
                        LaunchedEffect(remoteDetailStatus, remoteDetailStatusAcquisition) {
                            detailStatusForOwner(
                                owner = detailStatusOwner,
                                statusAcquisition = remoteDetailStatusAcquisition,
                                status = remoteDetailStatus,
                            )?.let { detailStatus = it }
                        }
                        // Read through the injected seam, not appGraph, so an isolated
                        // app test can fake these facts. A fake that is only a
                        // LibraryRepository simply has no download identity, which is the
                        // truthful answer for a row that is not a catalog download.
                        val detailRepository = checkNotNull(repository)
                        LaunchedEffect(currentRoute.itemId, detailRepository) {
                            val resolved = withContext(Dispatchers.IO) {
                                val identity = (detailRepository as? DownloadLibraryAvailability)
                                    ?.findDownloadIdentity(LibraryItemId(currentRoute.itemId))
                                val key = runCatching {
                                    detailRepository.downloadAcquisitionKeys()[currentRoute.itemId]
                                }.getOrNull()
                                val job = identity?.let { appGraph.downloadJobStore.jobFor(it) }
                                val locator = identity?.let {
                                    runCatching { appGraph.downloadLocatorStore.get(it) }.getOrNull()
                                }
                                DetailRouteFacts(key, job, identity, locator)
                            }
                            detailAcquisitionKey = resolved.acquisitionKey
                            detailDownloadState = resolved.job?.state
                            detailTruncated = detailTruncatedSeal(resolved.job)
                            detailDelisted = resolved.job?.isDelistedAtSource() == true
                            detailIdentity = resolved.identity
                            detailLocator = resolved.locator
                        }
                        val bookmarkKeys = remember(collectBookmarkEpoch) {
                            collectBookmarkStore.readAll()
                                .mapNotNull { galleryDedupKey(it.acquisitionUrl) }
                                .toSet()
                        }
                        val cloudSession = cloudReaderSession
                        if (cloudSession != null) {
                            // A42 remainder: same recorder seam as the main
                            // column's cloud branch above.
                            val remoteProgressRecorder = remember(remoteReadProgressItem) {
                                remoteReadProgressItem?.let {
                                    RemoteReadingProgressRecorder.forItem(it, remoteProgressStore)
                                }
                            }
                            CloudReaderScreen(
                                session = cloudSession,
                                onBack = {
                                    cloudReaderSession = null
                                    remoteReadProgressItem = null
                                },
                                onProgressSettled = { ordinal, pageCount ->
                                    coroutineScope.launch(Dispatchers.IO) {
                                        remoteProgressRecorder?.onProgressSettled(ordinal, pageCount)
                                    }
                                },
                                volumeKeyPagingEnabled = volumeKeyPagingEnabled,
                                rtlPagingEnabled = rtlPagingEnabled,
                                onRtlPagingChange = { enabled ->
                                    rtlPagingEnabled = enabled
                                    readerRtlPagingStore.write(enabled)
                                },
                                keepScreenOnEnabled = keepScreenOnEnabled,
                                signInRejectedLine = CloudSignInCopy.sentence(bridgeR2BindPresenter.state.boundKind),
                                layoutMode = readerLayoutMode,
                                onLayoutModeChange = { mode ->
                                    readerLayoutMode = mode
                                    readerLayoutModeStore.write(mode)
                                },
                            )
                        } else {
                            when (val loaded = detailState) {
                                DetailUiState.Loading -> DetailLoadingScreen()
                                DetailUiState.Unavailable -> DetailItemGoneScreen(
                                    onBack = { route = AppRoute.Library },
                                )
                                is DetailUiState.Ready -> {
                                    val dedupKey = galleryDedupKey(detailAcquisitionKey)
                                    // A32 §3 / J1: the collect switch on a downloaded row is
                                    // a real control, so it needs the same value Browse
                                    // collects with — read off the download locator, never
                                    // synthesised from the identity (whose acquisition key
                                    // has no E-Hentai token and an id no other screen uses).
                                    val detailFeedItem = remember(
                                        detailIdentity,
                                        detailLocator,
                                        collectBookmarkEpoch,
                                        loaded.row.metadata.title,
                                        loaded.row.item.pageCount,
                                    ) {
                                        detailIdentity?.let { identity ->
                                            detailCollectFeedItem(
                                                identity = identity,
                                                locator = detailLocator,
                                                // K1: a collected-then-downloaded
                                                // comic's locator points at the
                                                // user's own bucket; its bookmark
                                                // is the only record of the
                                                // upstream.
                                                bookmarks = collectBookmarkStore.readAll(),
                                                fallbackTitle = loaded.row.metadata.title,
                                                pageCount = loaded.row.item.pageCount,
                                            )
                                        }
                                    }
                                    // C4 §2.1: the Library route needs the same
                                    // fact the Browse route already had — is
                                    // THIS comic's upload in flight? Without it
                                    // the one destructive verb on this screen
                                    // ("Delete download?") stays live while the
                                    // Collect-driven upload is reading the pages
                                    // it deletes, and both copies are lost.
                                    val localSyncRows by cloudSyncQueue.rows
                                        .collectAsStateWithLifecycle()
                                    val localCloudQueued = remember(
                                        localSyncRows,
                                        detailLocator,
                                        detailFeedItem,
                                        detailAcquisitionKey,
                                    ) {
                                        cloudUploadInFlight(
                                            rows = localSyncRows,
                                            acquisitions = setOfNotNull(
                                                detailLocator?.acquisitionUrl,
                                                detailFeedItem?.acquisitionUrl,
                                                detailAcquisitionKey,
                                            ),
                                            cloudBound = appGraph.currentBucketFingerprintOrNull() != null,
                                        )
                                    }
                                    val localDetailTagItem = detailFeedItem?.let { item ->
                                        item.copy(
                                            tags = item.tags + loaded.row.metadata.tags + loaded.row.metadata.creators,
                                        )
                                    }
                                    ComicDetailScreen(
                                        subject = DetailSubject.Local(
                                            item = loaded.row.item,
                                            metadata = loaded.row.metadata,
                                            acquisitionKey = detailAcquisitionKey,
                                            collected = dedupKey != null && dedupKey in bookmarkKeys,
                                            // A copy downloaded FROM the cloud library carries a
                                            // cloud_collection identity whose key never matches an
                                            // upstream dedup key — it is in the cloud iff its id is
                                            // still listed there (owner 2026-09-09).
                                            inCloud = LocalCloudPresence.isInCloud(
                                                acquisitionKey = detailAcquisitionKey,
                                                identity = detailIdentity,
                                                listedCloudItems = libraryCloudItems,
                                            ),
                                            downloadState = detailDownloadState,
                                            truncatedSeal = detailTruncated,
                                            delistedAtSource = detailDelisted,
                                        ),
                                        cloudBound = libraryCloudReady,
                                        cloudQueued = localCloudQueued,
                                        statusMessage = detailStatus,
                                        collectTargetResolved = detailFeedItem != null,
                                        onBack = {
                                            if (tagSearchStack.top() is TagSearchSurface.Detail) {
                                                popTagSearchSurface()
                                            } else {
                                                serializedReaderOriginRoute = null
                                                route = AppRoute.Library
                                            }
                                        },
                                        onTagClick = tagSearchActionFor(
                                            item = localDetailTagItem,
                                            localProviderId = detailLocator?.browseSourceId
                                                ?: detailIdentity?.providerNamespace,
                                        ),
                                        onCollect = {
                                            detailFeedItem?.let { item ->
                                                // J4: say the outcome HERE. collectBookmark
                                                // writes remoteDetailStatus, which only the
                                                // catalog screen renders, so a blocked-bucket
                                                // explanation would otherwise never be read.
                                                detailStatus = null
                                                sayRemote(null)
                                                detailStatusOwner = item.acquisitionUrl
                                                collectBookmark(item)
                                            }
                                        },
                                        onUncollect = {
                                            detailFeedItem?.let { item ->
                                                detailStatus = null
                                                sayRemote(null)
                                                detailStatusOwner = item.acquisitionUrl
                                                // J1: remove the bookmark Browse actually
                                                // wrote, matched by gallery dedup key —
                                                // E-Hentai tokens rotate, so the locator's
                                                // URL need not be byte-identical to it.
                                                uncollectBookmark(
                                                    detailUncollectFeedItem(
                                                        item,
                                                        collectBookmarkStore.readAll(),
                                                    ),
                                                )
                                            }
                                        },
                                        onReadFrom = { ordinal ->
                                            serializedReaderOriginRoute = null
                                            route = AppRoute.Reader(
                                                currentRoute.itemId,
                                                initialOrdinal = ordinal,
                                            )
                                        },
                                        onPreviewPageClick = { ordinal ->
                                            serializedReaderOriginRoute = null
                                            route = AppRoute.Reader(
                                                currentRoute.itemId,
                                                initialOrdinal = ordinal,
                                            )
                                        },
                                        onOpenSources = { openSources(SourcesReturnTo.LIBRARY) },
                                        // A30 §2.3: the app's one file-deleting verb, and
                                        // only ever over files the app itself downloaded.
                                        // Null for an imported folder, so the ··· is never
                                        // rendered there at all.
                                        onDeleteDownload = detailIdentity?.let { identity ->
                                            {
                                                // J5: leave only when the download really
                                                // went. A skipped or failed delete used to
                                                // navigate to a shelf still showing the
                                                // comic, with no word about why.
                                                detailStatus = null
                                                coroutineScope.launch {
                                                    val outcome = withContext(Dispatchers.IO) {
                                                        runCatching {
                                                            // A36 review M2: one helper for
                                                            // both Detail surfaces, so the
                                                            // row, the READY tree, the
                                                            // catalog tree and the job row
                                                            // can never part ways. SEALED
                                                            // counts too when the truncated
                                                            // wind-down gave it a Library
                                                            // row — the store re-checks that.
                                                            appGraph.deleteDownloadedComic(
                                                                identity,
                                                            ) ?: error(
                                                                "no deletable download job " +
                                                                    "for ${redactIdentity(identity)}",
                                                            )
                                                        }
                                                    }
                                                    val deleted = outcome.getOrNull()
                                                        ?.state == DownloadJobState.NONE
                                                    if (deleted) {
                                                        route = AppRoute.Library
                                                    } else {
                                                        DiagLog.w(
                                                            "PclDetail",
                                                            "Delete download failed for " +
                                                                "${redactIdentity(identity)}",
                                                            outcome.exceptionOrNull(),
                                                        )
                                                        detailStatus =
                                                            DetailDownloadDeleteCopy.FAILED
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        }
                    }
                    is AppRoute.Reader -> {
                        val chapterRows = localReaderChapterRows
                            ?.takeIf { it.first == currentRoute.itemId }
                            ?.second
                        val transition = remember(chapterRows, currentRoute.chapterId) {
                            currentRoute.chapterId?.let { chapterId ->
                                chapterRows?.localChapterTransitionPresentation(chapterId)
                            }
                        }
                        val readerViewModel: ReaderViewModel = viewModel(
                            key = "reader-${currentRoute.itemId}-${currentRoute.initialOrdinal ?: -1}-" +
                                "${currentRoute.readingUnitId ?: -1}-${currentRoute.chapterId ?: ""}",
                            factory = ReaderViewModel.Factory(
                                itemId = currentRoute.itemId,
                                repository = checkNotNull(repository),
                                initialOrdinal = currentRoute.initialOrdinal,
                                readingUnitId = currentRoute.readingUnitId?.let(::ReadingUnitId),
                                chapterContext = currentRoute.chapterId?.let(::ReaderChapterContext),
                            ),
                        )
                        val readerState by readerViewModel.uiState.collectAsStateWithLifecycle()
                        ReaderScreen(
                            state = if (currentRoute.chapterId != null && chapterRows == null) {
                                ReaderUiState.Loading
                            } else {
                                readerState
                            },
                            onSettledPage = readerViewModel::onSettledPage,
                            onPageOpenFailed = readerViewModel::onPageOpenFailed,
                            onBack = {
                                openReaderBack(currentRoute)
                            },
                            // T-storage-overlay: "Back to Library" is a distinct
                            // destination from plain back -- it must land on the
                            // shelf, not wherever the reader happened to be opened
                            // from.
                            onExitToLibrary = {
                                serializedReaderOriginRoute = null
                                route = AppRoute.Library
                            },
                            keepScreenOnEnabled = keepScreenOnEnabled,
                            volumeKeyPagingEnabled = volumeKeyPagingEnabled,
                            rtlPagingEnabled = rtlPagingEnabled,
                            onRtlPagingChange = { enabled ->
                                rtlPagingEnabled = enabled
                                readerRtlPagingStore.write(enabled)
                            },
                            fitWidthEnabled = fitWidthEnabled,
                            layoutMode = readerLayoutMode,
                            onLayoutModeChange = { mode ->
                                readerLayoutMode = mode
                                readerLayoutModeStore.write(mode)
                            },
                            onOpenSources = { openSources(SourcesReturnTo.LIBRARY) },
                            transition = transition,
                            onOpenChapter = { target ->
                                route = AppRoute.Reader(
                                    currentRoute.itemId,
                                    target.initialOrdinal,
                                    target.readingUnitId,
                                    target.chapterId,
                                )
                            },
                            onFilesGone = {
                                filesGoneAtMillis = System.currentTimeMillis()
                                route = AppRoute.Library
                            },
                        )
                    }
                                    }
                }
            }
        }
        pendingSerializedConfirmation?.let { pending ->
            SerializedCollectConfirmationDialog(
                chapterCount = pending.chapterCount,
                estimatedBytes = null,
                onConfirm = {
                    pendingSerializedConfirmation = null
                    coroutineScope.launch(Dispatchers.IO) {
                        when (val result = appGraph.collectSerializedSeries(pending.item, confirmed = true)) {
                            is SerializedCollectResult.Blocked -> withContext(Dispatchers.Main) {
                                sayRemote(
                                    "Could not collect chapters: ${result.reason}",
                                    acquisition = pending.item.acquisitionUrl,
                                )
                            }
                            is SerializedCollectResult.Collected,
                            is SerializedCollectResult.NeedsConfirmation -> Unit
                        }
                    }
                },
                onCancel = {
                    pendingSerializedConfirmation = null
                    if (shouldRemoveSerializedBookmarkOnConfirmationCancel(pending.fromCollectTap)) {
                        bookmarksForItem(collectBookmarkStore.readAll(), pending.item)
                            .forEach { collectBookmarkStore.remove(it.id, it.providerId) }
                        collectBookmarkEpoch += 1
                    }
                },
            )
        }
        // #E4 / #E123: inside the theme so the dialog wears the app's colours; supports in-app download + verify + install.
        updateOffer?.takeIf { it.showDialog }?.let { offer ->
            val openInBrowser: () -> Unit = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, offer.release.downloadUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            UpdateAvailableDialog(
                release = offer.release,
                phase = updateDialogPhase,
                onUpdate = {
                    if (updateDownloadJob?.isActive == true) return@UpdateAvailableDialog
                    updateDownloadJob = coroutineScope.launch {
                        val rel = offer.release
                        val existingVerified = withContext(Dispatchers.IO) {
                            runCatching { AppUpdateInstaller.hasVerifiedCachedApk(context, rel) }.getOrNull()
                        }
                        val verifiedFile = if (existingVerified != null) {
                            existingVerified
                        } else {
                            val dir = AppUpdateInstaller.updateDir(context)
                            val partFile = AppUpdateHelper.partApkFile(dir, rel.versionCode)
                            val finalFile = AppUpdateHelper.finalApkFile(dir, rel.versionCode)
                            updateDialogPhase = AppUpdateDialogPhase.Downloading(
                                bytesReceived = 0L,
                                totalBytes = rel.sizeBytes ?: 0L,
                            )
                            val downloadOk = try {
                                withContext(Dispatchers.IO) {
                                    AppUpdateHelper.streamApkDownload(
                                        downloadUrl = rel.downloadUrl,
                                        updateDir = dir,
                                        partFile = partFile,
                                        finalFile = finalFile,
                                        expectedSizeBytes = rel.sizeBytes,
                                        shouldCancel = { !coroutineContext.isActive },
                                        onProgress = { received, total ->
                                            updateDialogPhase = AppUpdateDialogPhase.Downloading(
                                                bytesReceived = received,
                                                totalBytes = total,
                                            )
                                        },
                                    )
                                }
                                true
                            } catch (cancelled: CancellationException) {
                                updateDialogPhase = AppUpdateDialogPhase.Ready(alreadyDownloaded = false)
                                throw cancelled
                            } catch (_: Throwable) {
                                if (!coroutineContext.isActive) {
                                    updateDialogPhase = AppUpdateDialogPhase.Ready(alreadyDownloaded = false)
                                    return@launch
                                }
                                updateDialogPhase = AppUpdateDialogPhase.Error(
                                    AppUpdateDialogPhase.Error.Reason.DOWNLOAD_FAILED,
                                )
                                false
                            }
                            if (!downloadOk) return@launch
                            updateDialogPhase = AppUpdateDialogPhase.Verifying
                            val verdict = withContext(Dispatchers.IO) {
                                AppUpdateInstaller.verifyDownloadedApk(context, finalFile, rel)
                            }
                            if (!verdict.isValid) {
                                updateDialogPhase = AppUpdateDialogPhase.Error(
                                    AppUpdateDialogPhase.Error.Reason.VERIFICATION_FAILED,
                                )
                                return@launch
                            }
                            finalFile
                        }
                        if (!AppUpdateInstaller.canRequestPackageInstalls(context)) {
                            waitingPermissionApkFile = verifiedFile
                            updateDialogPhase = AppUpdateDialogPhase.PermissionRequired(verifiedFile.absolutePath)
                            return@launch
                        }
                        waitingPermissionApkFile = null
                        updateDialogPhase = AppUpdateDialogPhase.Ready(alreadyDownloaded = true)
                        runCatching {
                            AppUpdateInstaller.installUpdateApk(context, verifiedFile)
                        }.onFailure {
                            updateDialogPhase = AppUpdateDialogPhase.Error(
                                AppUpdateDialogPhase.Error.Reason.DOWNLOAD_FAILED,
                            )
                        }
                    }
                },
                onCancelDownload = {
                    updateDownloadJob?.cancel()
                    updateDownloadJob = null
                    updateDialogPhase = AppUpdateDialogPhase.Ready(alreadyDownloaded = false)
                },
                onOpenUnknownSources = {
                    runCatching { AppUpdateInstaller.openUnknownAppSourcesSettings(context) }
                },
                onOpenBrowser = openInBrowser,
                onLater = {
                    updateDownloadJob?.cancel()
                    updateDownloadJob = null
                    waitingPermissionApkFile = null
                    updateDialogPhase = AppUpdateDialogPhase.Ready(alreadyDownloaded = false)
                    updateChecker.dismiss(offer.release)
                    updateOffer = offer.copy(showDialog = false)
                },
            )
        }
        // #C57: the mandatory "encrypt or not" question for a bind to an EMPTY
        // bucket. A sibling of Scaffold so it pops up on any route (the OAuth
        // redirect may land while another screen is showing).
        val bindUi by bridgeR2BindPresenter.states.collectAsStateWithLifecycle()
        bindUi.modeChoice?.let { choice ->
            val syncBoundLabel = {
                bridgeR2BucketLabel = bridgeR2BindPresenter.state.boundBucketLabel
                bridgeR2UnlockedEpoch += 1
            }
            EmptyBucketModeChoiceDialog(
                state = choice,
                onEncryptSelected = bridgeR2BindPresenter::onModeChoiceEncryptSelected,
                onPasswordChange = bridgeR2BindPresenter::onModeChoicePasswordChange,
                onConfirmChange = bridgeR2BindPresenter::onModeChoiceConfirmChange,
                onTurnOnEncryption = {
                    coroutineScope.launch {
                        // Only a real commit bumps bindGeneration; a refused or
                        // failed answer (the dialog may also close on NotEmpty)
                        // must not re-sync the label or bump the epoch.
                        val generationBefore = bridgeR2BindPresenter.state.bindGeneration
                        bridgeR2BindPresenter.onModeChoiceTurnOnEncryption()
                        if (bridgeR2BindPresenter.state.bindGeneration != generationBefore) syncBoundLabel()
                    }
                },
                onDontEncrypt = {
                    coroutineScope.launch {
                        val generationBefore = bridgeR2BindPresenter.state.bindGeneration
                        bridgeR2BindPresenter.onModeChoiceDontEncrypt()
                        if (bridgeR2BindPresenter.state.bindGeneration != generationBefore) syncBoundLabel()
                    }
                },
                onCancel = bridgeR2BindPresenter::onModeChoiceCancel,
            )
        }
        if (cloudReleaseCatchUpPrompt && route == AppRoute.Settings) {
            AlertDialog(
                onDismissRequest = {
                    cloudReleaseAfterUploadStore.writeCatchUpAsked(true)
                    cloudReleaseCatchUpPrompt = false
                },
                title = { Text(CloudReleaseAfterUploadCopy.CATCH_UP_TITLE) },
                text = { Text(CloudReleaseAfterUploadCopy.CATCH_UP_BODY) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            cloudReleaseAfterUploadStore.writeCatchUpAsked(true)
                            cloudReleaseCatchUpPrompt = false
                            coroutineScope.launch {
                                val summary = appGraph.releasePreviouslyUploadedLocalCopies()
                                cloudReleaseCatchUpSummary = CloudReleaseAfterUploadCopy.catchUpSummary(
                                    released = summary.released,
                                    failed = summary.failed,
                                )
                            }
                        },
                        modifier = Modifier.testTag("cloud-release-catch-up-confirm"),
                    ) { Text(CloudReleaseAfterUploadCopy.CATCH_UP_CONFIRM) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            cloudReleaseAfterUploadStore.writeCatchUpAsked(true)
                            cloudReleaseCatchUpPrompt = false
                        },
                        modifier = Modifier.testTag("cloud-release-catch-up-dismiss"),
                    ) { Text(CloudReleaseAfterUploadCopy.CATCH_UP_DISMISS) }
                },
                modifier = Modifier.testTag("cloud-release-catch-up-dialog"),
            )
        }
    }
}

/**
 * The app-scope facts the Local Detail route resolves off the main thread in
 * one pass: the acquisition key, the download job, its identity and its
 * locator. A named holder rather than a tuple because J1 added a fourth field
 * and `Triple` had already stopped being readable at three.
 */
private data class DetailRouteFacts(
    val acquisitionKey: String?,
    val job: DownloadJob?,
    val identity: GalleryIdentityV1?,
    val locator: DownloadLocator?,
)

/**
 * Live rows for the local download queue (WAIT + DOWNLOAD, FIFO, spec §4.3).
 * Used by the legacy DownloadQueue route.
 */
@Composable
private fun rememberDownloadQueueRows(appGraph: AppGraph): List<DownloadQueueRow> =
    rememberDownloadRows(
        appGraph,
        // P-b: the flow itself must be remembered. `collectAsStateWithLifecycle`
        // keys its collection on the Flow instance, so building a fresh one on
        // every recomposition tears down and restarts the Room query each time.
        remember(appGraph) { appGraph.downloadJobStore.observeQueue() },
    )

/**
 * A20: rows for the Activity tab (WAIT + DOWNLOAD + FAILED) — unlike the
 * legacy [rememberDownloadQueueRows] this also surfaces FAILED jobs so a
 * capped/dead download stays visible with a Retry button instead of vanishing
 * from the feed the moment the pump gives up on it.
 */
@Composable
private fun rememberActivityDownloadRows(appGraph: AppGraph): List<DownloadQueueRow> =
    rememberDownloadRows(
        appGraph,
        remember(appGraph) { appGraph.downloadJobStore.observeActivityJobs() },
    )

/**
 * A52 (owner bug backlog, ledger 2026-09-04): import rows for the Activity
 * tab, fed by the same per-source run stream the Folders screen reads
 * (`observeLatestRunsBySource`) — the tracker (see
 * [ImportActivityTracker]) turns (sources, latestRuns) into Live/Done/Failed
 * rows.
 *
 * Split of ownership with the app root: the tracker INSTANCE lives at the
 * root (see `importActivityTracker` there) so its dedup memory and pending
 * Done receipts survive leaving and re-entering the Activity tab. The row
 * DERIVATION lives here, inside the Activity route, following the
 * [rememberActivityDownloadRows] pattern.
 *
 * Visit boundary (2026-09-08 audit, finding 2): a plain `remember { }`
 * calculation below runs exactly once per entry of the Activity route into
 * composition — that is a new visit — and, because composition executes
 * top-to-bottom, it runs BEFORE this visit's first accept. The previous
 * visit's Done receipts are therefore cleared before any row of the new
 * visit can render, so a re-entry can never flash last visit's receipts for
 * a frame (the reason this is a remember, not a LaunchedEffect — effects
 * fire after composition, after the first accept already ran).
 *
 * Idempotence (2026-09-08 audit, finding 2): accept returns the same rows for
 * a repeated identical (sources, latestRuns) pair, so `accept` inside
 * `remember(latestRuns, sources) { ... }` is safe — an aborted composition
 * re-running the block cannot eat a rendered row (the remember keys change
 * only when Room emits fresh instances).
 */
@Composable
private fun rememberActivityImportRows(
    appGraph: AppGraph,
    tracker: ImportActivityTracker,
): List<ImportActivityRow> {
    // A52 (finding 2): entering this composable IS entering the Activity
    // route — the visit boundary. remember's calculation runs once per
    // (re-)entry into composition, before the accept block below, so the
    // previous visit's Done receipts expire before this visit's first rows
    // derive. Re-running on an aborted composition is harmless: beginVisit
    // is idempotent and there is nothing of THIS visit to lose yet. The
    // remembered value itself is unused — the boolean exists only because
    // Compose lint requires remember to return something.
    remember(tracker) {
        tracker.beginVisit()
        true
    }
    // P-b (same rule as rememberDownloadRows): the flows must be remembered,
    // or every recomposition restarts the Room queries.
    val latestRunsFlow = remember(appGraph) { appGraph.scanRunRepository.observeLatestRunsBySource() }
    val sourcesFlow = remember(appGraph) { appGraph.sourceRepository.observeSources() }
    val latestRuns by latestRunsFlow.collectAsStateWithLifecycle(initialValue = emptyMap())
    val sources by sourcesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    return remember(latestRuns, sources) {
        tracker.accept(sources, latestRuns)
    }
}

/**
 * Shared row-building logic behind [rememberDownloadQueueRows] and
 * [rememberActivityDownloadRows]: both read the exact same ready-pages/meter
 * inputs and mapping so the two surfaces never drift into two copies — they
 * differ only in which job-state flow feeds them.
 */
@Composable
private fun rememberDownloadRows(
    appGraph: AppGraph,
    jobsFlow: Flow<List<DownloadJob>>,
): List<DownloadQueueRow> {
    val queueJobs by jobsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // P-b: same rule as the caller's job flow — remembered, or every
    // recomposition restarts the READY-pages query.
    val readyPagesFlow = remember(appGraph) {
        appGraph.downloadJobStore.observeReadyPages()
    }
    val readyPages by readyPagesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val meterTick by appGraph.downloadTransferMeter.ticks
        .collectAsStateWithLifecycle()
    return remember(queueJobs, readyPages, meterTick) {
        val now = System.currentTimeMillis()
        val locators = queueJobs.associate { job ->
            job.storageIdentity to appGraph.downloadLocatorStore.get(job.storageIdentity)
        }
        downloadQueueRows(
            jobs = queueJobs,
            readyPages = readyPages,
            locators = locators,
            bytesPerSecond = { job ->
                appGraph.downloadTransferMeter.bytesPerSecond(
                    job.id,
                    now,
                )
            },
        )
    }
}

/**
 * Slim top-of-app banner (T2b) offering a one-tap Cloudflare-challenge solve
 * when the download pump has an nh job parked on [cfChallengeJob]. Observes
 * the same durable queue the pump itself reads
 * ([com.easemic.app.feature.download.RoomDownloadJobStore.observeQueue])
 * so it appears/disappears in lockstep with the pump's own notification
 * ([com.easemic.app.work.CfChallengeNotification]) without
 * either one owning the other's state.
 */
@Composable
private fun CfChallengeBanner(appGraph: AppGraph, context: android.content.Context) {
    val jobs by appGraph.downloadJobStore.observeQueue()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    if (cfChallengeJob(jobs) == null) return
    // Only nhentai jobs ever park on a CF challenge; surface its real name
    // whenever the source is enabled instead of hiding behind a code name.
    val nhentaiEnabled = NhentaiEnableStore(SharedPreferencesMap.from(context)).isEnabled()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PclSpacing.Large, vertical = PclSpacing.Small),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = cfChallengeBannerSourceLabel(nhentaiEnabled = nhentaiEnabled) +
                    " needs a quick check — tap to solve",
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    context.startActivity(Intent(context, CloudflareChallengeActivity::class.java))
                },
            ) {
                Text("Solve")
            }
        }
    }
}

private fun serializedReaderChapters(
    result: ChapterListResult,
    store: com.easemic.app.feature.browse.RemoteReadingProgressStore,
): List<ReaderChapter> = result.chapters.map { summary ->
        val chapterId = summary.identity.canonicalChapterId
        val progress = store.get(
            com.easemic.app.feature.browse.RemoteProgressPolicy.keyForChapter(
                summary.identity.series,
                chapterId,
            ),
        )
        ReaderChapter(
            chapterId = chapterId,
            groupOrder = summary.groupOrder,
            readingOrderInGroup = summary.readingOrderInGroup,
            canRead = summary.canRead,
            progressOrdinal = progress?.ordinal ?: 0,
            completed = progress?.let { it.ordinal == it.pageCount - 1 } ?: false,
            lastReadAtMillis = progress?.updatedAtMillis ?: 0L,
        )
    }
