package com.easemic.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.core.graphics.scale
import android.os.storage.StorageManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.easemic.app.data.room.AppDatabase
import com.easemic.app.data.room.MIGRATION_1_2
import com.easemic.app.data.room.MIGRATION_2_3
import com.easemic.app.data.room.MIGRATION_3_4
import com.easemic.app.data.room.MIGRATION_4_5
import com.easemic.app.data.room.MIGRATION_5_6
import com.easemic.app.data.room.MIGRATION_6_7
import com.easemic.app.data.room.MIGRATION_7_8
import com.easemic.app.data.room.MIGRATION_8_9
import com.easemic.app.data.room.MIGRATION_9_10
import com.easemic.app.data.room.MIGRATION_10_11
import com.easemic.app.data.room.MIGRATION_11_12
import com.easemic.app.data.room.MIGRATION_12_13
import com.easemic.app.data.room.MIGRATION_13_14
import com.easemic.app.data.room.MIGRATION_14_15
import com.easemic.app.data.room.MIGRATION_15_16
import com.easemic.app.data.room.MIGRATION_16_17
import com.easemic.app.data.room.MIGRATION_17_18
import com.easemic.app.data.room.MIGRATION_18_19
import com.easemic.app.data.room.MIGRATION_19_20
import com.easemic.app.data.room.RoomLibraryRepository
import com.easemic.app.data.room.RoomSourceRepository
import com.easemic.app.data.room.RoomScanRunRepository
import com.easemic.app.data.room.ChapterUpdateEntity
import com.easemic.app.feature.browse.CloudChapterRow
import com.easemic.app.feature.browse.CloudThumbCache
import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.seriesIdentityFor
import com.easemic.app.feature.browse.EhentaiBrowseSource
import com.easemic.app.feature.browse.ExhentaiBrowseSource
import com.easemic.app.feature.browse.NhentaiBrowseSource
import com.easemic.app.feature.browse.OpdsBrowseSource
import com.easemic.app.feature.browse.SourceFilterState
import com.easemic.app.feature.download.CloudCollectionDownloadAdapter
import com.easemic.app.feature.download.bindingMemoKey
import com.easemic.app.feature.download.memoizedCloudCollectionStore
import com.easemic.app.feature.download.DownloadCommitFacts
import com.easemic.app.feature.download.DownloadCommitMetadata
import com.easemic.app.feature.download.DownloadCore
import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.DownloadLocator
import com.easemic.app.feature.download.DownloadPageBytesAdapter
import com.easemic.app.feature.download.DownloadPageResolver
import com.easemic.app.feature.download.DownloadProgressState
import com.easemic.app.feature.download.DownloadWorkerLoop
import com.easemic.app.feature.download.parseSerializedOwnershipKey
import com.easemic.app.feature.download.SourceLivenessOracle
import com.easemic.app.feature.source.AppGraphComicSourceRegistry
import com.easemic.app.feature.source.CloudCollectionComicSource
import com.easemic.app.feature.source.ComicSourceRegistry
import com.easemic.app.feature.source.EhentaiComicSource
import com.easemic.app.feature.source.ExhentaiComicSource
import com.easemic.app.feature.source.NhentaiComicSource
import com.easemic.app.feature.source.OpdsComicSource
import com.easemic.app.feature.source.zaimanhua.ZaimanhuaSource
import com.easemic.app.feature.providers.ZaimanhuaAccountStore
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.mergeSeriesSnapshot
import com.easemic.app.feature.source.sameSeriesText
import com.easemic.app.data.room.SerializedChapterJob
import com.easemic.app.feature.activity.SerializedChapterPopulation
import com.easemic.app.feature.activity.serializedChapterPopulations
import com.easemic.app.feature.activity.serializedActivitySummaries
import com.easemic.app.feature.activity.serializedCloudActivitySummaries
import com.easemic.app.feature.collect.SerializedCollectResult
import com.easemic.app.feature.collect.SerializedSeriesCollector
import com.easemic.app.feature.collect.SerializedSeriesUnfollow
import com.easemic.app.feature.collect.SerializedSeriesUnfollowResult
import com.easemic.app.feature.collect.SerializedChapterQueuePolicy
import com.easemic.app.feature.collect.CollectedSeriesRefreshCoordinator
import com.easemic.app.feature.collect.CollectedSeriesRefreshStore
import com.easemic.app.feature.collect.CollectedSeriesNewChapterStore
import com.easemic.app.feature.collect.RefreshDecision
import com.easemic.app.feature.collect.RefreshResult
import com.easemic.app.feature.collect.ChapterUpdateRecorder
import com.easemic.app.feature.collect.followCanRefresh
import com.easemic.app.feature.collect.SmartRefreshStateStore
import com.easemic.app.feature.collect.recordNewChapterMarks
import com.easemic.app.feature.collect.backfillSerializedSourceCoversOnce
import com.easemic.app.feature.importing.SerializedSourceCoverFiles
import com.easemic.app.feature.importing.encodeCloudCoverDerivative
import com.easemic.app.feature.importing.isCoverReencodeNeeded
import com.easemic.app.feature.importing.REENCODE_SKIP_BYTES
import com.easemic.app.feature.importing.COVER_MAX_EDGE
import com.easemic.app.feature.source.SeriesCoverSource
import com.easemic.app.feature.settings.BackgroundRefreshEnabledStore
import com.easemic.app.feature.settings.BackgroundRefreshRunStore
import com.easemic.app.feature.settings.BackgroundRefreshWifiOnlyStore
import com.easemic.app.work.CollectedSeriesRefreshScheduler
import com.easemic.app.work.CollectedSeriesRefreshWorker
import com.easemic.app.work.SummaryChapter
import com.easemic.app.work.UpdatesNotification
import com.easemic.app.work.WorkManagerPeriodicRefreshGateway
import com.easemic.app.feature.download.downloadOwnershipKey
import com.easemic.app.feature.detail.isDeletableDownloadJobState
import com.easemic.app.feature.download.deleteCatalogDownloadRevision
import com.easemic.app.feature.download.downloadJobsToRevive
import com.easemic.app.feature.download.earliestResumeAfter
import com.easemic.app.feature.download.FailedDownloadStagingSweep
import com.easemic.app.feature.download.FileDownloadLocatorStore
import com.easemic.app.feature.download.FrontDownloadPageAdapter
import com.easemic.app.feature.download.frontPageImageGet
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.GalleryIdentityV2
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.library.ChapterShelfRow
import com.easemic.app.feature.library.UpdateDayGroup
import com.easemic.app.feature.library.projectUpdateRow
import com.easemic.app.feature.library.updateDayGroups
import com.easemic.app.feature.library.updateStableKey
import com.easemic.app.feature.download.serializedSeriesLedgerKey
import com.easemic.app.feature.download.IndexDownloadPageAdapter
import com.easemic.app.feature.download.indexPageImageGet
import com.easemic.app.feature.download.OpdsAcquisitionPageBytes
import com.easemic.app.feature.download.OpdsExistenceCheck
import com.easemic.app.feature.download.OpdsDownloadPageAdapter
import com.easemic.app.feature.download.R2SourceAcquisition
import com.easemic.app.feature.download.R2SourceDownloadAdapter
import com.easemic.app.feature.download.ReadyPageStore
import com.easemic.app.feature.download.RegistryDownloadPageAdapter
import com.easemic.app.feature.download.isCloudIngestReadyDownloadJob
import com.easemic.app.feature.download.isSerializedChapter
import com.easemic.app.feature.download.resolveLocalCatalogPagesForIngest
import com.easemic.app.feature.download.resolveSealedReadyPagesForIngest
import com.easemic.app.feature.download.OpdsDoubleNamespaceCleanup
import com.easemic.app.feature.download.purgeDoubledJobFiles
import com.easemic.app.feature.download.reclaimOrphanLegacyCatalogTrees
import com.easemic.app.feature.download.RoomDownloadJobStore
import com.easemic.app.feature.download.RoomDownloadWorkerHost
import com.easemic.app.feature.download.DownloadLocatorStore
import com.easemic.app.feature.download.downloadNotificationText as formatDownloadNotificationText
import com.easemic.app.feature.download.downloadQueueRows
import com.easemic.app.feature.download.storageIdentity
import com.easemic.app.feature.download.SerializedSeriesDelete
import com.easemic.app.feature.download.SerializedDownloadChapterRequest
import com.easemic.app.feature.download.enqueueLocalOnlyChapterDownloads
import com.easemic.app.feature.download.signInRefusedCloudDownloads
import com.easemic.app.feature.download.TransferRateMeter
import com.easemic.app.feature.download.putKeepingCatalogFacts
import com.easemic.app.feature.bridge.BridgeDeviceLibraryKeyStore
import com.easemic.app.feature.bridge.BridgeDeviceR2SecretStore
import com.easemic.app.feature.bridge.BridgeOAuthDeviceSecretStore
import com.easemic.app.feature.bridge.BridgeR2Binding
import com.easemic.app.feature.bridge.BridgeR2BindCopy
import com.easemic.app.feature.bridge.CloudPingBaselineStore
import com.easemic.app.feature.bridge.CloudProcessSession
import com.easemic.app.feature.bridge.ProbeCause
import com.easemic.app.feature.bridge.ProbeOutcome
import com.easemic.app.feature.bridge.classifyLockProbeFailure
import com.easemic.app.feature.bridge.CloudflareOAuthConfig
import com.easemic.app.feature.bridge.CloudflarePkceClient
import com.easemic.app.feature.bridge.currentBucketFingerprint
import com.easemic.app.feature.bridge.BridgeDeviceWebDavStore
import com.easemic.app.feature.bridge.BridgeDeviceOneDriveStore
import com.easemic.app.feature.bridge.BridgeDeviceGoogleDriveStore
import com.easemic.app.feature.bridge.DeadGrant
import com.easemic.app.sync.GoogleDriveObjectStore
import com.easemic.app.feature.bridge.BucketFingerprint
import com.easemic.app.feature.bridge.DriveOAuthConfigs
import com.easemic.app.feature.bridge.DrivePkceClient
import com.easemic.app.feature.bridge.DriveTokenSource
import com.easemic.app.sync.OneDriveObjectStore
import com.easemic.app.sync.WebDavBinding
import com.easemic.app.sync.WebDavObjectStore
import com.easemic.app.feature.bridge.LocalR2BindingCache
import com.easemic.app.feature.bridge.OauthAccessTokenSource
import com.easemic.app.feature.bridge.OauthRestStoreBootstrap
import com.easemic.app.feature.bridge.OauthRestStoreRefresh
import com.easemic.app.feature.bridge.OauthRestStoreRefreshResult
import com.easemic.app.feature.collect.CloudCollectionAdopter
import com.easemic.app.feature.collect.CloudComicIdStore
import com.easemic.app.feature.collect.applyUncollectJobOutcome
import com.easemic.app.feature.collect.tombstoneForUncollect
import com.easemic.app.feature.collect.UncollectCloudOutcome
import com.easemic.app.feature.collect.UncollectCloudFailureKind
import com.easemic.app.feature.collect.backfillFromMirror
import com.easemic.app.feature.collect.cloudHandoffRepairCandidates
import com.easemic.app.feature.collect.backfillCloudQueueIdentities
import com.easemic.app.feature.collect.CloudBoundIndexGate
import com.easemic.app.feature.collect.CloudHostedLocalRelease
import com.easemic.app.feature.collect.CloudReleaseCatchUpSweep
import com.easemic.app.feature.collect.CloudReleaseCatchUpSweepSummary
import com.easemic.app.feature.collect.CloudHydrationEngine
import com.easemic.app.feature.collect.CloudLocalReleaseSweep
import com.easemic.app.feature.collect.CloudIngestRunner
import com.easemic.app.feature.collect.CloudSyncDismissalStore
import com.easemic.app.feature.browse.RemoteReadingProgressStore
import com.easemic.app.feature.collect.CloudSyncIngestGate
import com.easemic.app.feature.collect.CloudSyncQueue
import com.easemic.app.feature.collect.CloudSyncQueueItem
import com.easemic.app.feature.collect.CloudSyncQueueStore
import com.easemic.app.feature.collect.CloudSyncStage
import com.easemic.app.feature.collect.CollectBookmarkStore
import com.easemic.app.feature.collect.isSerializedBookmark
import com.easemic.app.feature.collect.cloudUploadInFlight
import com.easemic.app.feature.collect.cloudUploadRowForJob
import com.easemic.app.feature.collect.cloudUploadRowSkipReason
import com.easemic.app.feature.collect.cloudHandoffRepairChapterCandidates
import com.easemic.app.feature.collect.isSerializedChapterSyncedToBucket
import com.easemic.app.feature.collect.isSerializedJobSyncedToBucket
import com.easemic.app.feature.collect.partitionUncollectedCloudHandoffCandidates
import com.easemic.app.feature.collect.recordSerializedChapterSynced
import com.easemic.app.feature.collect.serializedChapterCloudFactsOrNull
import com.easemic.app.feature.collect.reviveRowsOnBind
import com.easemic.app.feature.collect.RoomCloudMirrorStore
import com.easemic.app.feature.collect.RoomCloudSyncLedgerStore
import com.easemic.app.feature.collect.HydrationRecheckStore
import com.easemic.app.feature.collect.shouldRecheckHydration
import com.easemic.app.feature.collect.confirmedUploadFingerprint
import com.easemic.app.sync.redactAcquisition
import com.easemic.app.sync.redactIdentity
import com.easemic.app.feature.library.galleryDedupKey
import com.easemic.app.feature.download.downloadOwnershipKey
import com.easemic.app.sync.CloudSeriesChapterUploadRequest
import com.easemic.app.sync.CloudSeriesManifest
import com.easemic.app.sync.fileThumbnailSupplier
import com.easemic.app.sync.LibraryLockedException
import com.easemic.app.sync.NewerCloudFormatException
import com.easemic.app.sync.UnreadableCloudLibraryException
import com.easemic.app.sync.CloudChapterMeta
import com.easemic.app.sync.CloudCardLayout

import com.easemic.app.importing.ComicInfoParser
import com.easemic.app.importing.CandidateRecognizer
import com.easemic.app.importing.ImageDirectoryScanner
import com.easemic.app.importing.ImportCoordinator
import com.easemic.app.feature.providers.EhentaiAccountStore
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.providers.ExhentaiEnableStore
import com.easemic.app.feature.providers.JmEnableStore
import com.easemic.app.feature.providers.NhentaiCookieStore
import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.feature.providers.OpdsProviderStore
import com.easemic.app.feature.settings.BackgroundNotificationAskedStore
import com.easemic.app.feature.settings.CacheBudgetStore
import com.easemic.app.feature.settings.CloudReleaseAfterUploadStore
import com.easemic.app.feature.settings.MaxConcurrentTransfersStore
import com.easemic.app.importing.CatalogRequestGate
import com.easemic.app.importing.ehentai.EhentaiClient
import com.easemic.app.importing.ehentai.EhentaiImporter
import com.easemic.app.importing.nhentai.NhentaiClient
import com.easemic.app.importing.nhentai.NhentaiImporter
import com.easemic.app.sync.CloudIngestWorkSweep
import com.easemic.app.sync.DiagLog
import com.easemic.app.sync.DiagTraceFile
import com.easemic.app.ui.SharedPreferencesMap
import com.easemic.app.importing.opds.OpdsAcquisitionImporter
import com.easemic.app.importing.opds.OpdsCatalogClient
import com.easemic.app.importing.opds.OpdsHttpGet
import com.easemic.app.importing.opds.extractOpdsAcquisitionPages
import com.easemic.app.importing.opds.opdsIndexResultFrom
import com.easemic.app.feature.importing.boundedThumbnailSampleSize
import com.easemic.app.feature.importing.thumbnailDecodeSampleSize
import com.easemic.app.feature.importing.thumbnailTargetSize
import com.easemic.app.feature.importing.CloudCoverFiles
import com.easemic.app.feature.importing.CloudShelfCover
import com.easemic.app.net.SourceHttp
import com.easemic.app.storage.ContentResolverDocumentAccess
import com.easemic.app.storage.CacheBudget
import com.easemic.app.storage.CoilDiskCacheOwner
import com.easemic.app.storage.AndroidPersistedReadGrantManager
import com.easemic.app.storage.AppPrivateReadGrantManager
import com.easemic.app.storage.ArchiveAccess
import com.easemic.app.storage.ArchiveCache
import com.easemic.app.storage.DocumentUnavailableException
import com.easemic.app.storage.LocalPageContentOpener
import com.easemic.app.storage.isAppPrivateFilePath
import com.easemic.app.importing.archive.SafArchiveCandidateIndexer
import com.easemic.app.importing.archive.ZipArchiveIndexer
import com.easemic.app.importing.source.ComicArchiveSourceAdapter
import com.easemic.app.importing.source.GenericDirectorySourceAdapter
import com.easemic.app.importing.source.SourceAdapterRegistry
import com.easemic.app.work.CfChallengeNotification
import com.easemic.app.work.CloudConnectivityReKick
import com.easemic.app.work.CloudIngestWorker
import com.easemic.app.work.cloudIngestWorkRemains
import com.easemic.app.work.backgroundPauseState
import com.easemic.app.work.BackgroundPauseState
import com.easemic.app.work.ForegroundReturnAction
import com.easemic.app.work.foregroundReturnAction
import com.easemic.app.work.DownloadPumpWorker
import com.easemic.app.work.HydrationWorker
import com.easemic.app.work.ImportForegroundInfo
import com.easemic.app.work.PumpForegroundInfo
import com.easemic.app.work.RoomFencedCatalogMutations
import com.easemic.app.work.RoomFencedScanRunMutations
import com.easemic.app.work.RunRecovery
import com.easemic.app.work.StartupRunRecoveryCoordinator
import com.easemic.app.work.ScanEngine
import com.easemic.app.work.ScanRunReader
import com.easemic.app.work.ScanWorkerFactory
import com.easemic.app.work.WorkManagerScanWorkScheduler
import androidx.work.WorkManager
import com.easemic.app.sync.CloudCollectionKeys
import com.easemic.app.sync.SerializedSourceCoverUpload
import com.easemic.app.sync.serializedSourceCoverRepairNeeded
import com.easemic.app.sync.CloudCollectionStore
import com.easemic.app.sync.CloudLibraryGate
import com.easemic.app.sync.CloudOpenTrace
import com.easemic.app.sync.ObjectStore
import com.easemic.app.sync.R2ObjectStore
import com.easemic.app.sync.adfilter.AdFingerprintStore
import com.easemic.app.sync.adfilter.TrailingAdStripper
import com.easemic.app.sync.adfilter.TypesafeApiKeyStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.easemic.app.feature.bridge.CloudBindingMutationGate

/** S4 Task 1 review (M7): log tag for [AppGraph]'s cloud-cover decode/write failure paths. */
private const val TAG_CLOUD_COVER = "AppGraphCloudCover"

class AppGraph private constructor(
    context: Context,
    private val sourceRegistryOverride: ComicSourceRegistry? = null,
) {
    /**
     * An override (test) graph keeps its own database: the app's singleton graph starts with the process and its
     * download pump cannot see an override registry's sources, so on a shared file it would claim and fail the test's
     * jobs (#E2 4a device gate, `UNSUPPORTED_IDENTITY`) — and the test's rows would land in the user's library.
     */
    internal val database = if (sourceRegistryOverride == null) {
        buildAppDatabase(context.applicationContext)
    } else {
        buildAppDatabase(context.applicationContext, "private-comic-library-override.db")
    }
    val appFilesDir: File = context.applicationContext.filesDir
    /**
     * #C45 D4: the CloudOpen / DownloadPump / CloudSync lines also land in a
     * 256 KB ring file under `files/diag/`, so the owner's phone keeps its own
     * evidence across logcat's rollover. Installed from the earliest init
     * block, in declaration order, before anything in this graph can log.
     */
    init {
        DiagLog.fileSink = DiagTraceFile(File(appFilesDir, "diag")).let { file -> { line -> file.append(line) } }
    }
    // Bare constructor params are only reachable from property initializers
    // (incl. `by lazy {}` blocks); a real property is required to use the
    // application Context from a plain member function such as start().
    private val appContext: Context = context.applicationContext
    /** Serializes binding replacement/disconnect with destructive local release. */
    val cloudBindingMutationGate = CloudBindingMutationGate()
    val cacheBudgetStore by lazy {
        CacheBudgetStore(SharedPreferencesMap.from(appContext))
    }
    /** Once-per-process latch for the [cacheBudget] warm-up in [onAppForeground]. */
    private val cacheBudgetWarmed = AtomicBoolean(false)
    /** Prevents overlapping foreground refresh passes from issuing duplicate source requests. */
    private val collectedSeriesRefreshInFlight = AtomicBoolean(false)
    /** Prevents overlapping one-pass source-cover backfills in this process. */
    private val serializedSourceCoverBackfillInFlight = AtomicBoolean(false)
    private val serializedSourceCoverAttempted = java.util.concurrent.ConcurrentHashMap.newKeySet<SeriesIdentityV2>()
    /**
     * WorkManager's download pump runs the app's singleton graph, which cannot see an override registry's sources;
     * an override (test) graph therefore never kicks it for serialized work — its test drives [downloadWorkerLoop].
     */
    private val kickSerializedDownloads: () -> Unit =
        if (sourceRegistryOverride == null) ({ DownloadPumpWorker.kick(appContext) }) else ({})
    /** Throttle stamp for [purgeExpiredCloudTrash] (C35-d, bounded by SF-4). */
    private val lastCloudTrashPurgeAtMs = AtomicLong(0L)
    /**
     * Shared cache budget. Construction registers the six rebuildable roots,
     * and each registration walks **that root only** once to seed the budget's
     * running byte total (C34-a); from then on writers report their bytes and
     * a full sweep runs only when the total crosses the cap. Cost is therefore
     * one walk per root at first touch — warmed off the reader's critical path
     * by the first [onAppForeground], because the first toucher used to be the
     * cloud reader. A WorkManager-only process never warms it and pays nothing.
     *
     * `cloud-thumbs` joined the roots in the #C1 completion: the detail strip
     * and the cloud shelf both re-fetch a thumb whose file has gone, so
     * evicting one costs a round-trip and never a missing cover.
     */
    val cacheBudget: CacheBudget by lazy {
        // C34-d: the whole init is one `budget-init` phase — if a reader open
        // ever races the warm-up again, the trace says how long it waited and
        // how many files each root's single walk had to count.
        var rootCounts = ""
        CloudOpenTrace.timed("budget-init", extras = { "roots=$rootCounts" }) {
            // Coil owns a journaled DiskCache and is intentionally not registered
            // with this file walker. Its explicit maxSizeBytes is configured by
            // PrivateComicLibraryApplication and takes effect on loader restart;
            // clearRebuildableCaches therefore cannot unlink Coil metadata.
            CacheBudget(
                maxBytes = CacheBudget.genericBudgetBytes(cacheBudgetStore.readBytes()),
                trustedBase = appContext.dataDir,
            ).also { budget ->
                listOf(
                    "opds-pages",
                    "opds-detail-previews",
                    "eh-detail-previews",
                    "cloud-page-cache",
                    "serialized-pages",
                    "serialized-page-files",
                    CloudThumbCache.DIR_NAME,
                ).forEach { budget.registerRoot(File(appContext.cacheDir, it)) }
                rootCounts = budget.registeredFileCountsForTrace()
            }
        }
    }
    /** One owner per graph; construction removes abandoned serialized page parts once. */
    val serializedPageFileCache by lazy {
        com.easemic.app.feature.reader.SerializedPageFileCache(
            File(appContext.cacheDir, "serialized-page-files"), cacheBudget,
        )
    }
    val serializedBytePageCache by lazy {
        com.easemic.app.feature.reader.SerializedPageFileCache(
            File(appContext.cacheDir, "serialized-pages"), cacheBudget,
        )
    }
    val coilDiskCache by lazy {
        CoilDiskCacheOwner(
            directory = File(appContext.cacheDir, "coil3_disk_cache"),
            maxSizeBytes = CacheBudget.COIL_PARTITION_BYTES,
        )
    }

    fun setCacheBudgetMb(value: Int) {
        cacheBudgetStore.writeMb(value)
        cacheBudget.maxBytes = CacheBudget.genericBudgetBytes(cacheBudgetStore.readBytes())
    }

    fun cacheUsageBytes(): Long =
        cacheBudget.usageBytes() + coilDiskCache.size

    fun clearRebuildableCaches() {
        // C28 review LOW-4: with the More-tab call site contained, a throw out of
        // clearAll() used to skip the Coil cache entirely and be swallowed into a
        // single log line — "Clear cache" reporting success with half of it
        // untouched. Both steps are always attempted; the first failure is still
        // surfaced to the (contained) caller.
        val budget = runCatching { cacheBudget.clearAll() }
        val coil = runCatching { coilDiskCache.clear() }
        budget.exceptionOrNull()?.let { throw it }
        coil.exceptionOrNull()?.let { throw it }
    }
    private val opdsDownloadsRoot = File(appFilesDir, "opds-downloads").also { it.mkdirs() }
    private val ehentaiDownloadsRoot = File(appFilesDir, "ehentai-downloads").also { it.mkdirs() }
    private val nhentaiDownloadsRoot = File(appFilesDir, "nhentai-downloads").also { it.mkdirs() }
    /**
     * S4 Task 1 (owner bug C4): durable on-device cover JPEGs, one per synced
     * cloud comic (`<cloudComicId>.jpg`) — written once at ingest or
     * hydration so the Cloud shelf can render covers with zero R2 network
     * calls once synced. Distinct from the pre-existing ephemeral
     * `cacheDir/cloud-thumbs` the UI used before this fix (which the OS can
     * evict at any time and which this fix's local-first read now only
     * falls back to, never relies on as the primary source).
     */
    private val cloudCoversRoot = File(appFilesDir, "cloud-covers").also { it.mkdirs() }
    val cloudCoverFiles by lazy {
        CloudCoverFiles(File(appFilesDir, "cloud-cover-staging"), validImage = ::validateCoverFile)
    }
    val opdsAcquisitionImporter: OpdsAcquisitionImporter by lazy {
        OpdsAcquisitionImporter(
            httpGet = OpdsHttpGet { request -> OpdsCatalogClient.defaultGet(request) },
            downloadsRoot = opdsDownloadsRoot,
            // Spec 2026-09-09 (A47 audit tail): indexComics so a container archive
            // (top level = N image directories) reaches the importer as
            // ContainerAccepted → N candidates instead of the retired
            // OPDS_ARCHIVE_CONTAINER guard; single-comic archives keep the exact
            // old Accepted shape via the same synthesis SafArchiveCandidateIndexer
            // uses.
            indexArchive = opdsIndexResultFrom(ZipArchiveIndexer()),
            extractPages = { archive, dest, pages -> extractOpdsAcquisitionPages(archive, dest, pages) },
        )
    }
    /**
     * The graph-owned E-Hentai session store. Public for the sign-in activity
     * ([com.easemic.app.ui.EhentaiSignInActivity]), which
     * persists a harvested session + verdict through the same single store
     * every other reader uses (W7: one store, one normalized header, no
     * divergent copies).
     */
    val ehentaiAccountStore by lazy {
        EhentaiAccountStore(SharedPreferencesMap.from(context.applicationContext))
    }

    val zaimanhuaAccountStore by lazy {
        ZaimanhuaAccountStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /**
     * Holds the nhentai `cf_clearance` cookie minted by the (separate,
     * not-yet-built) WebView Cloudflare challenge-solve flow. Public so that
     * future UI can write to it once solved.
     */
    val nhentaiCookieStore by lazy {
        NhentaiCookieStore(SharedPreferencesMap.from(context.applicationContext))
    }

    private val nhentaiEnableStore by lazy {
        NhentaiEnableStore(SharedPreferencesMap.from(context.applicationContext))
    }

    private val ehentaiEnableStore by lazy {
        EhentaiEnableStore(SharedPreferencesMap.from(context.applicationContext))
    }

    private val exhentaiEnableStore by lazy {
        ExhentaiEnableStore(
            SharedPreferencesMap.from(context.applicationContext),
            ehentaiAccountStore,
        )
    }

    private val jmEnableStore by lazy {
        JmEnableStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /**
     * Owner bug A14 ("后台下载/上云不稳定"): backs the once-per-install
     * runtime `POST_NOTIFICATIONS` request in `MainActivity` so a user who
     * already dismissed the system dialog isn't re-prompted on every cold
     * start. See `feature/settings/BackgroundNotificationAskedStore`.
     */
    val backgroundNotificationAskedStore by lazy {
        BackgroundNotificationAskedStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /**
     * A22 §3: last manifest-check timestamp, stamped by [cloudHydrationEngine]
     * after every manifest GET and consulted by [onAppForeground]'s 5-minute
     * throttle. See `feature/collect/HydrationRecheck.kt`.
     */
    val hydrationRecheckStore by lazy {
        HydrationRecheckStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /**
     * S6-A (owner setting "max concurrent transfers"): single shared source
     * of truth for how many gallery/upload slots may run at once, read live
     * by [downloadWorkerLoop] and [workerFactory]'s cloud-ingest pump, and
     * written by the Settings screen. See `feature/settings/MaxConcurrentTransfersStore`.
     */
    val maxConcurrentTransfersStore by lazy {
        MaxConcurrentTransfersStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /**
     * Owner bug A14: best-effort "was this pump interrupted by the system"
     * signal, written from [CloudIngestWorker.recordSystemStop] /
     * [DownloadPumpWorker.recordSystemStop]. Exposed here (rather than left
     * as a raw prefs key) so a future Activity-tab surface can show "被系统
     * 中断" without needing to know the storage details — see those workers'
     * kdoc for what actually writes it. `null` means never observed stopped
     * (or the value failed to parse).
     */
    fun lastCloudIngestStoppedAtMs(): Long? =
        SharedPreferencesMap.from(appContext)
            .getString(CloudIngestWorker.LAST_STOPPED_PREF_KEY, null)
            ?.toLongOrNull()

    /** See [lastCloudIngestStoppedAtMs] — same mechanism, the download pump. */
    fun lastDownloadPumpStoppedAtMs(): Long? =
        SharedPreferencesMap.from(appContext)
            .getString(DownloadPumpWorker.LAST_STOPPED_PREF_KEY, null)
            ?.toLongOrNull()

    /**
     * C43: what the Activity tab shows when a transfer pump was denied its
     * foreground service or stopped by the system since its last protected
     * start (both markers are cleared by the pump on a granted start). Null
     * when nothing is recorded.
     */
    fun backgroundPauseState(): BackgroundPauseState? {
        val prefs = SharedPreferencesMap.from(appContext)
        return backgroundPauseState(
            downloadDeniedAt = prefs.getString(DownloadPumpWorker.LAST_FGS_DENIED_PREF_KEY, null),
            cloudDeniedAt = prefs.getString(CloudIngestWorker.LAST_FGS_DENIED_PREF_KEY, null),
            downloadStoppedAt = prefs.getString(DownloadPumpWorker.LAST_STOPPED_PREF_KEY, null),
            cloudStoppedAt = prefs.getString(CloudIngestWorker.LAST_STOPPED_PREF_KEY, null),
            downloadStopReason = prefs.getString(DownloadPumpWorker.LAST_STOP_REASON_PREF_KEY, null),
            cloudStopReason = prefs.getString(CloudIngestWorker.LAST_STOP_REASON_PREF_KEY, null),
        )
    }

    val ehentaiImporter: EhentaiImporter
        get() {
            val account = ehentaiAccountStore.read()
            val client = EhentaiClient.fromAccount(account)
            val cookie = account.normalizedCookie()
            return EhentaiImporter(
                client = client,
                imageGet = { url ->
                    CatalogRequestGate.Shared.acquire()
                    EhentaiClient.defaultGet(url, cookieHeader = cookie)
                },
                downloadsRoot = ehentaiDownloadsRoot,
                beforeImageRequest = { CatalogRequestGate.Shared.acquire() },
            )
        }
    val nhentaiImporter: NhentaiImporter by lazy {
        // TODO(nh-cf-challenge): this is a separate construction site from the
        // download-pump's IndexDownloadPageAdapter below — not wired to
        // nhentaiCookieStore yet. Wire via NhentaiClient.fromCookie
        // if the browse/import path is also observed 503-ing on a CF
        // challenge.
        NhentaiImporter(
            client = NhentaiClient(
                beforeRequest = { CatalogRequestGate.Shared.acquire() },
            ),
            imageGet = { url ->
                CatalogRequestGate.Shared.acquire()
                NhentaiClient.defaultGet(url)
            },
            downloadsRoot = nhentaiDownloadsRoot,
            beforeImageRequest = { CatalogRequestGate.Shared.acquire() },
        )
    }
    val documentAccess = ContentResolverDocumentAccess(
        contentResolver = context.contentResolver,
        hasPersistedReadGrant = { uri ->
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val path = uri.path ?: return@ContentResolverDocumentAccess false
                isAppPrivateFilePath(path, listOf(appFilesDir))
            } else {
                context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
            }
        },
        streamOpener = { documentUri ->
            if (documentUri.startsWith("file:", ignoreCase = true)) {
                val file = File(java.net.URI(documentUri))
                if (!file.isFile) throw DocumentUnavailableException()
                file.inputStream()
            } else {
                context.contentResolver.openInputStream(documentUri.toUri())
                    ?: throw DocumentUnavailableException()
            }
        },
    )
    val grantManager = AppPrivateReadGrantManager(
        privateRoots = listOf(appFilesDir),
        delegate = AndroidPersistedReadGrantManager(context.contentResolver),
    )
    private val scanner = ImageDirectoryScanner(documentAccess, ComicInfoParser())
    private val archiveCacheDirectory = File(context.cacheDir, "archive-import")
    private val archiveAccess = buildProductionArchiveAccess(
        context = context.applicationContext,
        documentAccess = documentAccess,
        archiveCacheDirectory = archiveCacheDirectory,
    )
    private val candidateRecognizer = buildProductionCandidateRecognizer(
        context = context.applicationContext,
        documentAccess = documentAccess,
        directoryScanner = scanner,
        archiveCacheDirectory = archiveCacheDirectory,
        archiveAccess = archiveAccess,
    )
    val serializedSourceCoverFiles by lazy {
        SerializedSourceCoverFiles(appFilesDir)
    }
    val repository = RoomLibraryRepository(database, sourceCoverFiles = serializedSourceCoverFiles)
    private val archiveAppContext: Context = context.applicationContext

    private val serializedSeriesArchive by lazy {
        com.easemic.app.feature.settings.backup.SerializedSeriesArchive(
            database = database,
            repository = repository,
            downloadJobStore = downloadJobStore,
            locatorStore = downloadLocatorStore,
            filesDir = appFilesDir,
        )
    }

    /** #D7: the device-to-device archive's two halves, on the same seams a finished download uses. */
    fun archiveExporter(): com.easemic.app.feature.settings.backup.ArchiveExporter =
        com.easemic.app.feature.settings.backup.ArchiveExporter(
            prefs = SharedPreferencesMap.from(archiveAppContext),
            allItems = { repository.observeAllItems().first() },
            downloadIdentity = { repository.findDownloadIdentity(it) },
            metadataFor = { id ->
                val meta = repository.observeItemDetail(id).first()?.metadata
                com.easemic.app.feature.download.DownloadCommitMetadata(
                    tags = meta?.tags.orEmpty(),
                    language = meta?.language,
                    category = meta?.genres?.firstOrNull(),
                )
            },
            serializedSeriesFor = { itemId -> serializedSeriesArchive.build(itemId) },
            isSerializedSeries = { itemId -> repository.findSerializedSeriesIdentity(itemId) != null },
            appVersion = runCatching {
                archiveAppContext.packageManager.getPackageInfo(archiveAppContext.packageName, 0).versionName
            }.getOrNull() ?: "",
        )

    fun archiveRestorer(): com.easemic.app.feature.settings.backup.ArchiveRestorer =
        com.easemic.app.feature.settings.backup.ArchiveRestorer(
            prefs = SharedPreferencesMap.from(archiveAppContext),
            filesDir = appFilesDir,
            alreadyDownloaded = { repository.findDownloadItemId(it) != null },
            commit = { identity, location, title, pages, metadata, completeness ->
                repository.commitDownloadLocalAvailability(
                    identity = identity,
                    downloadLocationUri = location,
                    downloadRevision = com.easemic.app.feature.settings.backup.ArchiveRestorer.RESTORED_REVISION,
                    title = title,
                    pages = pages,
                    metadata = metadata,
                    completeness = completeness,
                )
            },
            progressTargets = {
                repository.observeAllItems().first().map { item ->
                    com.easemic.app.feature.settings.backup.ArchiveRestorer.ProgressTarget(
                        key = if (repository.findSerializedSeriesIdentity(item.id) != null) {
                            ""
                        } else {
                            repository.findDownloadIdentity(item.id)
                                ?.let { com.easemic.app.feature.settings.backup.ArchiveExporter.progressKey(it) } ?: ""
                        },
                        title = item.title,
                        pageCount = item.pageCount,
                        readingUnitId = item.readingUnitId,
                        currentProgressOrdinal = item.progressOrdinal,
                    )
                }
            },
            setProgress = { unit, ordinal -> repository.setReadingProgress(unit, ordinal) },
            freeBytes = { appFilesDir.usableSpace },
            alreadySeries = { series ->
                repository.findSerializedSeriesItemId(GalleryIdentityV1(series.providerId, series.providerSeriesId)) != null
            },
            restoreSeries = { series, coverFile, chapters, followed ->
                serializedSeriesArchive.restore(series, coverFile, chapters, followed)
            },
            // #E18: a restored gallery must be recorded as a finished download, or Collect finds no job row and
            // enqueues a fresh fetch that re-downloads every page the restore just placed. intentCloud = false: a
            // restore must not start uploads by itself — Collect adds the cloud intent when it is collected.
            recordRestoredGalleryJob = { restored, pageCount ->
                downloadJobStore.insertRestoredFinish(
                    identity = restored,
                    chapterIdentity = com.easemic.app.feature.download.GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID,
                    expectedPageCount = pageCount,
                    downloadRevision = com.easemic.app.feature.settings.backup.ArchiveRestorer.RESTORED_REVISION,
                    intentCloud = false,
                )
            },
        )
    /**
     * READY page publication root (spec §4.5). Shared by download worker and
     * reader [DownloadPageResolver] so ordinal N single-flights with the downloader.
     */
    val readyPageStore: ReadyPageStore by lazy {
        ReadyPageStore(
            File(appFilesDir, "download-ready-pages").also { it.mkdirs() }.toPath(),
            scheduleLeaseCleanup = { cleanup ->
                applicationScope?.launch(Dispatchers.IO) { cleanup() } ?: cleanup()
            },
        )
    }
    /** Durable download jobs; More → Download queue observes [RoomDownloadJobStore.observeQueue]. */
    val downloadJobStore: RoomDownloadJobStore by lazy {
        RoomDownloadJobStore(
            dao = database.downloadDao(),
            database = database,
            libraryAvailability = repository,
            readyPageStore = readyPageStore,
            // A36 review O1: lets a Download tap on a SEALED row commit the
            // pages already on the device instead of re-running the fetch.
            filesDir = appFilesDir,
            commitFactsFor = { identity ->
                val locator = runCatching { downloadLocatorStore.get(identity) }.getOrNull()
                DownloadCommitFacts(
                    title = locator?.title,
                    metadata = DownloadCommitMetadata(
                        tags = locator?.tags.orEmpty(),
                        language = locator?.language,
                        category = locator?.category,
                    ),
                )
            },
            // #C16 re-review item 1: the UI's cancel/uncollect takes a job out of
            // the queue without the pump ever seeing it again; release the
            // adapter's cached plan (the OPDS archive) at that moment.
            onJobLeftQueue = { identity -> downloadPageAdapter.invalidatePlan(identity) },
        )
    }

    /** Local serialized-series engine; constructed only when its first caller needs it. */
    val serializedSeriesCollector: SerializedSeriesCollector by lazy {
        SerializedSeriesCollector(
            library = repository,
            downloads = downloadJobStore,
            locators = downloadLocatorStore,
            sourceCoverFiles = serializedSourceCoverFiles,
            chapterQueuePolicy = SerializedChapterQueuePolicy(
                localJobExists = { series, chapterId ->
                    downloadJobStore.jobFor(
                        GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId),
                        chapterId,
                    ) != null
                },
                ledgerHasChapter = { series, chapterId ->
                    val fp = currentBucketFingerprintOrNull()
                    fp != null && cloudSyncLedgerStore.isSynced(
                        fp,
                        serializedSeriesLedgerKey(series),
                        chapterId,
                    )
                },
                mirrorHasChapter = { series, chapterId ->
                    val fp = currentBucketFingerprintOrNull()
                    if (fp == null) false else {
                        val card = cloudMirrorStore.activeSeriesCard(fp, series)
                        card != null && cloudMirrorStore.chapterRows(fp, card.cloudComicId)
                            .any { it.chapterIdentity == chapterId }
                    }
                },
                localJobDeleted = { series, chapterId ->
                    downloadJobStore.jobFor(
                        GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId),
                        chapterId,
                    )?.state == DownloadJobState.NONE
                },
            ),
        )
    }

    /** Six-hour cadence state for collected serialized-series refreshes. */
    val collectedSeriesRefreshStore by lazy {
        CollectedSeriesRefreshStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /** Last complete check and last known publication status for the smart cadence. */
    val smartRefreshStateStore by lazy {
        SmartRefreshStateStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /** Durable Updates log. Collect does not receive this recorder. */
    val chapterUpdateRecorder by lazy {
        ChapterUpdateRecorder(database.chapterUpdateDao()) { block ->
            database.withTransaction { block() }
        }
    }

    /** Durable quiet-shelf marks for newly discovered chapters in serialized follows. */
    val collectedSeriesNewChapterStore by lazy {
        CollectedSeriesNewChapterStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /** Current shelf-facing counts, keyed by the serialized provider identity. */
    fun newChapterMarks(): Map<String, Int> =
        collectedSeriesNewChapterStore.all().mapNotNull { (series, count) ->
            serializedSeriesLedgerKey(series).let { it to count }
        }.toMap()

    /** Clears a serialized-series shelf mark synchronously before opening its detail. */
    fun clearNewChapterMark(series: SeriesIdentityV2) {
        collectedSeriesNewChapterStore.clear(series)
    }

    /** App-owned coordinator for foreground and detail-open serialized refreshes. */
    val collectedSeriesRefreshCoordinator by lazy {
        CollectedSeriesRefreshCoordinator(
            sourceFor = { comicSourceRegistry.source(it) },
            collectedSeries = { collectBookmarkStore.readAll().filter { isSerializedBookmark(it) } },
            collector = serializedSeriesCollector,
            store = collectedSeriesRefreshStore,
            chapterTimes = { series ->
                val itemId = database.libraryDao().itemIdByClaim(
                    RoomLibraryRepository.DOWNLOAD_PROVENANCE_NAMESPACE,
                    RoomLibraryRepository.DOWNLOAD_OWNERSHIP_CLAIM,
                    downloadOwnershipKey(GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId)),
                )
                if (itemId == null) {
                    emptyList()
                } else {
                    database.libraryDao().chapterShelfRowsForItem(itemId).map { it.sourceUpdatedAtMillis }
                }
            },
            updates = chapterUpdateRecorder,
            smartState = smartRefreshStateStore,
            sourceCoverFiles = serializedSourceCoverFiles,
            afterReconcile = { series ->
                applicationScope?.launch(Dispatchers.IO) { writeBackChapterFactsAfterReconcile(series) }
                    ?: writeBackChapterFactsAfterReconcile(series)
            },
            mirroredChapterIds = { series ->
                val fp = currentBucketFingerprintOrNull()
                val card = fp?.let { cloudMirrorStore.activeSeriesCard(it, series) }
                if (fp == null || card == null) {
                    emptySet()
                } else {
                    cloudMirrorStore.chapterRows(fp, card.cloudComicId)
                        .mapTo(mutableSetOf()) { it.chapterIdentity }
                }
            },
            mirrorResolved = { series -> serializedMirrorResolved(series) },
        )
    }

    /** Titles used by Activity and the local download foreground notice. */
    suspend fun serializedSeriesTitles(): Map<Pair<String, String>, String> {
        val fromBookmarks = collectBookmarkStore.readAll()
            .asSequence()
            .filter { isSerializedBookmark(it) }
            .associate { bookmark ->
                val series = seriesIdentityFor(
                    BrowseFeedItem(
                        id = bookmark.id,
                        title = bookmark.title,
                        acquisitionUrl = bookmark.acquisitionUrl,
                        providerId = bookmark.providerId,
                        coverUrl = bookmark.coverUrl,
                        feedUrl = bookmark.feedUrl,
                    ),
                )
                (series.providerNamespace to series.canonicalSeriesId) to bookmark.title
            }
        // #E108: a download-only series (#E73 writes no bookmark) still owns a
        // Library row with its real name — without this, Activity falls back to
        // the bare provider series id (a number on 再漫画).
        val itemTitles = repository.observeAllItems().first()
            .associate { it.id.value to it.title }
        val fromLibrary = database.libraryDao()
            .observeOwnershipClaims(
                namespace = RoomLibraryRepository.DOWNLOAD_PROVENANCE_NAMESPACE,
                claimKey = RoomLibraryRepository.DOWNLOAD_OWNERSHIP_CLAIM,
            )
            .first()
            .mapNotNull { row ->
                val identity = parseSerializedOwnershipKey(row.claimValue) ?: return@mapNotNull null
                val title = itemTitles[row.itemId]?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                (identity.providerNamespace to identity.canonicalItemId) to title
            }
            .toMap()
        return fromLibrary + fromBookmarks
    }

    /** Current local download text shared by Activity's serialized rows. */
    suspend fun downloadNotificationText(): String {
        val jobs = downloadJobStore.observeActivityJobs().first()
        val readyPages = downloadJobStore.observeReadyPages().first()
        val locators = jobs.associate { job ->
            job.storageIdentity to downloadLocatorStore.get(job.storageIdentity)
        }
        val rows = downloadQueueRows(
            jobs = jobs,
            readyPages = readyPages,
            locators = locators,
        )
        val serializedJobs = downloadJobStore.observeSerializedChapterJobs().first()
        val cloudRows = cloudSyncQueue.snapshot()
        val populations = serializedActivityPopulations(serializedJobs, cloudRows)
        val seriesTitles = serializedSeriesTitles()
        val summaries = serializedActivitySummaries(
            rows = rows,
            populations = populations,
            seriesTitles = seriesTitles,
        )
        val cloudSummaries = serializedCloudActivitySummaries(
            rows = cloudRows,
            populations = populations,
            seriesTitles = seriesTitles,
        )
        val galleryActiveCount = jobs.count { job ->
            !job.isSerializedChapter && job.state in setOf(DownloadJobState.WAIT, DownloadJobState.DOWNLOAD)
        }
        // E15 Task 3a: with exactly one active gallery and no active series the
        // notice names that gallery and shows its page line plus the slow
        // suffix; otherwise the formatter keeps today's wording.
        val activeGalleryRows = rows.filter { row ->
            row.seriesCanonicalId == null &&
                row.state in setOf(DownloadJobState.WAIT, DownloadJobState.DOWNLOAD)
        }
        return formatDownloadNotificationText(
            summaries + cloudSummaries,
            galleryActiveCount,
            activeGalleryRows,
            downloadProgressState.slowPageCounts(),
        )
    }

    /** Shared population for every series with chapter jobs or cloud queue rows, in the current bucket only. */
    suspend fun serializedActivityPopulations(
        jobs: List<SerializedChapterJob>,
        rows: List<CloudSyncQueueItem>,
    ): Map<SeriesIdentityV2, SerializedChapterPopulation> {
        val series = serializedChapterPopulations(jobs, rows).keys
        val fingerprint = currentBucketFingerprintOrNull()
        val inCloud = if (fingerprint == null) emptyMap() else series.associateWith { identity ->
            cloudSyncLedgerStore.syncedChapterIds(fingerprint, serializedSeriesLedgerKey(identity))
        }
        return serializedChapterPopulations(jobs, rows, inCloud)
    }

    /** Production collect route for a serialized Browse item; chapters carry both intents. */
    suspend fun collectSerializedSeries(
        item: BrowseFeedItem,
        confirmed: Boolean = false,
        chapterList: ChapterListResult? = null,
    ): SerializedCollectResult {
        val source = comicSourceRegistry.source(item.providerId)
            ?: return SerializedCollectResult.Blocked("Source is unavailable.")
        if (!source.descriptor.serialized) {
            return SerializedCollectResult.Blocked("Source does not provide serialized chapters.")
        }
        val series = seriesIdentityFor(item)
        val result = if (chapterList != null) {
            serializedSeriesCollector.collectFrom(
                series = GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId),
                title = item.title,
                coverLocator = item.coverUrl,
                chapterList = chapterList,
                confirmed = confirmed,
            )
        } else {
            serializedSeriesCollector.collect(
                source = source,
                series = GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId),
                title = item.title,
                coverLocator = item.coverUrl,
                confirmed = confirmed,
            )
        }
        if (result is SerializedCollectResult.Collected) {
            val publishedCover = serializedSeriesCollector.ensureSourceCover(
                identity = SeriesIdentityV2(series.providerNamespace, series.canonicalSeriesId),
                locator = item.coverUrl,
                source = source,
            )
            if (publishedCover != null) {
                publishSerializedCloudSourceCoverIfPresent(
                    SeriesIdentityV2(series.providerNamespace, series.canonicalSeriesId),
                    publishedCover,
                )
            }
        }
        if (result is SerializedCollectResult.Collected) {
            result.queuedChapterIds.forEach { chapterId ->
                cloudSyncDismissalStore.restore(
                    com.easemic.app.feature.download.serializedChapterLocatorKey(
                        SeriesIdentityV2(series.providerNamespace, series.canonicalSeriesId),
                        chapterId,
                    ),
                    chapterId,
                )
            }
        }
        if (result is SerializedCollectResult.Collected && result.queuedChapterIds.isNotEmpty()) {
            kickSerializedDownloads()
        }
        runCatching { collectedSeriesRefreshScheduler.reconcile() }
        return result
    }

    private suspend fun publishSerializedCloudSourceCoverIfPresent(
        identity: SeriesIdentityV2,
        uri: String,
    ) {
        val fingerprint = currentBucketFingerprintOrNull() ?: return
        val card = cloudMirrorStore.activeSeriesCard(fingerprint, identity) ?: return
        val file = runCatching { File(java.net.URI(uri)) }.getOrNull()
            ?.takeIf { it.isFile }
            ?: return
        runCatching {
            val upload = SerializedSourceCoverUpload.fromFile(card.cloudComicId, file) ?: return
            if (!serializedSourceCoverRepairNeeded(card.coverThumbObjectKey, upload.key)) return
            resolveGatedObjectStore()?.let { objectStore ->
                    val publishedKey = CloudCollectionStore(objectStore)
                    .updateSeriesInfo(card.cloudComicId, identity, coverFile = file)
                if (publishedKey != null) {
                    cloudMirrorStore.setCoverThumbObjectKey(fingerprint, card.cloudComicId, publishedKey)
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            DiagLog.w("SerializedCover", "cloud cover repair failed type=${error.javaClass.simpleName}")
        }
    }

    /** Applies an already-fetched serialized detail list to its collected follow. */
    suspend fun refreshSerializedSeriesOnDetailOpen(
        item: BrowseFeedItem,
        chapters: ChapterListResult,
    ): RefreshResult? {
        val identity = seriesIdentityFor(item)
        val bookmark = collectBookmarkStore.readAll().firstOrNull {
            isSerializedBookmark(it) && it.providerId == identity.providerNamespace && it.id == identity.canonicalSeriesId
        } ?: return null
        val result = collectedSeriesRefreshCoordinator.refreshOnDetailOpen(
            bookmark = bookmark,
            fetched = chapters,
            itemCoverLocator = item.coverUrl,
        )
        serializedSourceCoverFiles.uriIfValid(identity)?.let { uri ->
            publishSerializedCloudSourceCoverIfPresent(identity, uri)
        }
        if (result.queuedChapterIds.isNotEmpty()) {
            kickSerializedDownloads()
        }
        return result
    }

    /**
     * F3 (a) series-info write-back (spec §3.3): after a SUCCESSFUL live
     * stage for a series this phone tracks in the cloud, the live text is
     * published into the bucket's series manifest when it differs from what
     * the manifest carries (prefer-newer inside [CloudCollectionStore.updateSeriesInfo]).
     * Called fire-and-forget from both detail-open folds — a slow or
     * unreachable bucket must never fail or delay the open.
     *
     * Mirror-first (spec §3.3): when the mirror already holds exactly this
     * text there is no bucket request at all.
     */
    suspend fun writeBackSeriesInfoAfterLiveStage(
        item: BrowseFeedItem,
        fetched: ChapterListResult,
    ) {
        try {
            val identity = seriesIdentityFor(item)
            val fingerprint = currentBucketFingerprintOrNull() ?: return
            val card = cloudMirrorStore.activeSeriesCard(fingerprint, identity) ?: return
            val live = mergeSeriesSnapshot(old = null, result = fetched)
            val mirrored = runCatching {
                cloudMirrorStore.seriesDetailSnapshot(fingerprint, card.cloudComicId)
            }.getOrNull()
            if (mirrored != null && live.sameSeriesText(mirrored)) return
            resolveGatedObjectStore()?.let { store ->
                CloudCollectionStore(store).updateSeriesInfo(
                    seriesCloudId = card.cloudComicId,
                    identity = identity,
                    snapshot = live,
                )
                // Spec §3.3 residual (controller 2026-10-11): after the
                // bucket write-back, the cloud mirror converges to the same
                // text, so the mirror-first compare above sees live text
                // without waiting for a chapter sync to copy it from Room.
                // Only reached after updateSeriesInfo returned (a failed PUT
                // throws past this point), so the mirror never gets ahead of
                // the bucket.
                runCatching {
                    cloudMirrorStore.upsertLiveSeriesSnapshot(fingerprint, card.cloudComicId, live)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DiagLog.w("SeriesInfoWriteBack", "series-info write-back failed type=${error.javaClass.simpleName}")
        }
    }

    /**
     * #E48: after a refresh reconciled a series' chapters in Room, publish what
     * changed for chapters already in the cloud (removed at source, order,
     * titles) so a restore elsewhere sees it. Mirror-first: when this device's
     * mirror already matches Room there is no bucket request at all. Never
     * throws: a slow or unreachable bucket must not fail the refresh.
     */
    suspend fun writeBackChapterFactsAfterReconcile(series: SeriesIdentityV2) {
        try {
            val fingerprint = currentBucketFingerprintOrNull() ?: return
            val card = cloudMirrorStore.activeSeriesCard(fingerprint, series) ?: return
            val mirrored = cloudMirrorStore.chapterRows(fingerprint, card.cloudComicId)
            if (mirrored.isEmpty()) return
            val itemId = database.libraryDao().itemIdByClaim(
                RoomLibraryRepository.DOWNLOAD_PROVENANCE_NAMESPACE,
                RoomLibraryRepository.DOWNLOAD_OWNERSHIP_CLAIM,
                downloadOwnershipKey(GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId)),
            ) ?: return
            val facts = database.libraryDao().readingUnitsForItem(itemId).map { unit ->
                com.easemic.app.sync.CloudChapterSourceFact(
                    providerChapterId = unit.sourceChapterId,
                    label = unit.label,
                    group = unit.sourceGroup,
                    readingOrder = unit.ordinal,
                    sourceUpdatedAtEpochMs = unit.sourceUpdatedAtMillis,
                    sourceState = unit.sourceState,
                )
            }
            val byId = facts.associateBy { it.providerChapterId }
            val differs = mirrored.any { row ->
                val fact = byId[row.chapterIdentity] ?: return@any false
                fact.label != row.label || fact.group != row.groupLabel ||
                    fact.readingOrder != row.ordinal || fact.sourceState != row.sourceState
            }
            if (!differs) return
            val store = resolveGatedObjectStore() ?: return
            val written = CloudCollectionStore(store).updateSeriesChapterFacts(card.cloudComicId, series, facts)
                ?: return
            DiagLog.i("ChapterFactsWriteBack", "series chapters updated generation=${written.generation}")
            runCatching { cloudMirrorStore.replaceSeriesChapters(fingerprint, card, written) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DiagLog.w("ChapterFactsWriteBack", "chapter-facts write-back failed type=${error.javaClass.simpleName}")
        }
    }

    /** Pull to refresh: forced check, then +N marks, then the download pump when anything was queued. */
    suspend fun refreshFollowsNow() {
        val startedAt = System.currentTimeMillis()
        val results = collectedSeriesRefreshCoordinator.refreshAllForced()
        recordNewChapterMarks(
            results,
            collectedSeriesNewChapterStore,
            observedAtMs = startedAt,
        )
        if (results.any { it.queuedChapterIds.isNotEmpty() }) {
            kickSerializedDownloads()
        }
    }

    /** Current shelf, job, and cloud facts for the Updates log. The source list is not refetched. */
    suspend fun presentChapterUpdates(
        rows: List<ChapterUpdateEntity>,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<UpdateDayGroup> {
        val shelves = mutableMapOf<Pair<String, String>, List<ChapterShelfRow>>()
        val jobs = mutableMapOf<Pair<String, String>, List<DownloadJob>>()
        val clouds = mutableMapOf<Pair<String, String>, List<CloudChapterRow>>()
        return updateDayGroups(rows, nowMillis).map { group ->
            group.copy(
                rows = group.rows.map { partial ->
                    val entity = rows.first { updateStableKey(it) == partial.stableKey }
                    val key = entity.providerNamespace to entity.providerSeriesId
                    val shelfRows = shelves.getOrPut(key) {
                        shelfRowsForSeries(entity.providerNamespace, entity.providerSeriesId)
                    }
                    val seriesJobs = jobs.getOrPut(key) {
                        downloadJobStore.jobsForSeries(
                            GalleryIdentityV1(entity.providerNamespace, entity.providerSeriesId),
                        )
                    }
                    val cloudRows = clouds.getOrPut(key) {
                        cloudRowsForSeries(entity.providerNamespace, entity.providerSeriesId)
                    }
                    projectUpdateRow(
                        update = entity,
                        shelf = shelfRows.firstOrNull { it.sourceChapterId == entity.providerChapterId },
                        job = seriesJobs.firstOrNull { it.chapterIdentity == entity.providerChapterId },
                        cloud = cloudRows.firstOrNull { it.chapterId == entity.providerChapterId },
                    )
                },
            )
        }
    }

    private suspend fun shelfRowsForSeries(namespace: String, seriesId: String): List<ChapterShelfRow> {
        val itemId = database.libraryDao().itemIdByClaim(
            RoomLibraryRepository.DOWNLOAD_PROVENANCE_NAMESPACE,
            RoomLibraryRepository.DOWNLOAD_OWNERSHIP_CLAIM,
            downloadOwnershipKey(GalleryIdentityV1(namespace, seriesId)),
        ) ?: return emptyList()
        return database.libraryDao().chapterShelfRowsForItem(itemId)
    }

    private suspend fun cloudRowsForSeries(namespace: String, seriesId: String): List<CloudChapterRow> {
        val fingerprint = currentBucketFingerprintOrNull() ?: return emptyList()
        val series = SeriesIdentityV2(namespace, seriesId)
        val card = cloudMirrorStore.activeSeriesCard(fingerprint, series) ?: return emptyList()
        return cloudMirrorStore.chapterRows(fingerprint, card.cloudComicId).map { row ->
            CloudChapterRow(
                chapterId = row.chapterIdentity,
                label = row.label ?: row.chapterIdentity,
                group = row.groupLabel,
                groupOrder = row.ordinal,
                readingOrderInGroup = row.ordinal,
                pageCount = row.pageCount ?: 0,
                sourceState = row.sourceState,
            )
        }
    }

    /**
     * A36 review M2 — the ONE way any Detail deletes a downloaded comic.
     *
     * There are two Detail surfaces that offer it (the Library/local one and
     * the Browse/catalog one for the same gallery), and they used to pass
     * different media deleters: the Browse side passed `{ true }`, so the
     * Library row and READY bookkeeping went while the materialised
     * `…-downloads/<id>/r<n>/` tree stayed on disk forever — invisible, since
     * nothing lists it once the row is gone. Both now call this, so the row,
     * the READY tree, the catalog tree and the job row always move together.
     *
     * Returns the job as it ended up: NONE means the delete really happened.
     * Null means there was no download job for [identity] or its state is not
     * one this verb may act on ([downloadJobOwnsLocalPages] via the store's own
     * guard), so the caller can say so instead of navigating away from a
     * comic that is still there.
     */
    suspend fun deleteDownloadedComic(identity: GalleryIdentityV1): DownloadJob? {
        val job = downloadJobStore.jobFor(identity) ?: return null
        if (!isDeletableDownloadJobState(job.state)) return null
        // C4 §2.1: the Detail lock is a courtesy, not a lock — this is. A running
        // upload reads the formal catalog tree lazily and a FINISH job holds no
        // lease (C2's lease covers SEALED only), so deleteFinished here would
        // unlink the pages under the pump: the local copy goes, the cloud copy
        // never gets its manifest/meta/index, and the row dies retrying
        // CLOUD_INGEST_PAGES_MISSING_POST_SEAL.
        val acquisition = downloadLocatorStore.get(identity)?.acquisitionUrl
        val cloudBound = currentBucketFingerprintOrNull() != null
        if (acquisition != null && cloudUploadInFlight(
                cloudSyncQueue.snapshot(),
                setOf(acquisition),
                cloudBound = cloudBound,
            )) {
            DiagLog.i(
                "CloudSync",
                "delete refused: upload in flight for " +
                    redactIdentity(identity),
            )
            return null
        }
        val result = downloadJobStore.deleteFinished(job.id) {
            deleteCatalogDownloadRevision(
                filesDir = appFilesDir,
                identity = identity,
                downloadRevision = job.downloadRevision,
            )
        }
        // F3: a full delete removes the row, pages, catalog tree and job above --
        // drop the resume locator too so no orphan download-locators/<ns>/<id>.locator
        // survives the comic it used to resume. With no cloud bound, a never-started
        // WAITING cloud row owns no remote copy and must leave with the local delete.
        runCatching { downloadLocatorStore.remove(identity) }
        if (!cloudBound && result.state == DownloadJobState.NONE && acquisition != null) {
            cloudSyncQueue.snapshot()
                .filter { it.id == acquisition }
                .forEach { row ->
                    cloudSyncDismissalStore.restore(row.id, row.chapterIdentity)
                    cloudSyncQueue.remove(row.id, row.chapterIdentity)
                }
        }
        return result
    }

    /**
     * #E73 spec §4 — the download picker's confirm: one local-only fetch job
     * per selected chapter (`intentLocal = true`, `intentCloud = false`), a
     * resume locator per chapter, NO bookmark write — the series is not
     * followed and nothing is queued for the cloud. [enqueueLocalOnlyChapterDownloads]
     * owns the decision shape (and its JVM tests); this wires it to the real
     * stores and kicks the serialized pump.
     */
    suspend fun enqueueSerializedChapterDownloads(
        series: GalleryIdentityV1,
        title: String,
        coverLocator: String?,
        chapterList: com.easemic.app.feature.source.ChapterListResult,
        chapters: List<SerializedDownloadChapterRequest>,
    ): List<String> = enqueueLocalOnlyChapterDownloads(
        series = series,
        title = title,
        coverLocator = coverLocator,
        chapterList = chapterList,
        chapters = chapters,
        reconcileSeries = { identity, seriesTitle, cover, list ->
            repository.reconcileSeries(identity, seriesTitle, cover, list)
        },
        putLocator = { storageIdentity, locator ->
            downloadLocatorStore.putKeepingCatalogFacts(storageIdentity, locator)
        },
        upsertEnqueue = { identity, expectedPageCount, intentLocal, intentCloud, chapterIdentity ->
            downloadJobStore.upsertEnqueue(
                identity = identity,
                expectedPageCount = expectedPageCount,
                intentLocal = intentLocal,
                intentCloud = intentCloud,
                chapterIdentity = chapterIdentity,
            )
        },
        kickDownloadPump = { kickSerializedDownloads() },
    )

    /** Deletes saved pages for every serialized chapter while keeping the follow. */
    suspend fun deleteSavedSerializedChapters(
        series: GalleryIdentityV1,
        chapterId: String? = null,
    ): SerializedDeleteResult {
        return SerializedSeriesDelete(
            jobsForSeries = downloadJobStore::jobsForSeries,
            acquisitionFor = { job -> downloadLocatorStore.get(job.storageIdentity)?.acquisitionUrl },
            uploadInFlight = { acquisitions ->
                cloudUploadInFlight(
                    cloudSyncQueue.snapshot(),
                    acquisitions,
                    cloudBound = currentBucketFingerprintOrNull() != null,
                )
            },
            deleteCatalogTree = { identity, revision ->
                deleteCatalogDownloadRevision(appFilesDir, identity, revision)
            },
            deleteChapter = { jobId, deleteOwnedMedia ->
                downloadJobStore.deleteSerializedChapter(jobId, deleteOwnedMedia)
            },
            removeChapterAvailability = repository::removeChapterLocalAvailability,
            removeCloudQueue = { job ->
                downloadLocatorStore.get(job.storageIdentity)?.acquisitionUrl?.let { acquisition ->
                    cloudSyncDismissalStore.restore(acquisition, job.chapterIdentity)
                    cloudSyncQueue.remove(acquisition, job.chapterIdentity)
                }
            },
        ).delete(series, chapterIds = chapterId?.let(::setOf))
    }

    /** Runs the identity-first whole-series uncollect path used by the serialized UI. */
    suspend fun unfollowSerializedSeries(
        series: SeriesIdentityV2,
        removedBookmarks: List<com.easemic.app.feature.collect.CollectedBookmark>,
    ): SerializedSeriesUnfollowResult = SerializedSeriesUnfollow(
        jobsForSeries = downloadJobStore::jobsForSeries,
        currentBucketFingerprint = ::currentBucketFingerprintOrNull,
        resolveCloudComicId = { fp, identity ->
            cloudMirrorStore.activeSeriesCard(fp, identity)?.cloudComicId
                ?: database.cloudSyncLedgerDao().cloudComicIdForSeries(fp, serializedSeriesLedgerKey(identity))
        },
        hasUnhydratedSeriesCards = { fp ->
            cloudMirrorStore.activeCards(fp).any { card ->
                card.layoutKind == CloudCardLayout.SERIES &&
                    (card.providerId.isNullOrBlank() || card.providerSeriesId.isNullOrBlank())
            }
        },
        tombstoneCloud = { cloudComicId ->
            try {
                val store = resolveGatedObjectStore()
                if (store == null) {
                    UncollectCloudOutcome.Failed(UncollectCloudFailureKind.UNREACHABLE)
                } else {
                    withContext(Dispatchers.IO) {
                        tombstoneForUncollect(
                            store = com.easemic.app.sync.CloudCollectionStore(store),
                            cloudComicId = cloudComicId,
                            fallback = { null },
                            nowMs = System.currentTimeMillis(),
                        )
                    }
                }
            } catch (_: LibraryLockedException) {
                UncollectCloudOutcome.Failed(UncollectCloudFailureKind.LOCKED)
            } catch (_: NewerCloudFormatException) {
                UncollectCloudOutcome.Failed(UncollectCloudFailureKind.NEWER)
            } catch (_: UnreadableCloudLibraryException) {
                UncollectCloudOutcome.Failed(UncollectCloudFailureKind.UNREADABLE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                UncollectCloudOutcome.Failed(UncollectCloudFailureKind.UNREACHABLE)
            }
        },
        markMirrorTrashed = { fp, cloudComicId, atMs ->
            cloudMirrorStore.setTrashed(fp, cloudComicId, atMs)
        },
        cancelJob = { jobId -> downloadJobStore.cancel(jobId) },
        dismissFailedJob = { jobId -> downloadJobStore.dismissAttentionRow(jobId) },
        dropCloudIntent = downloadJobStore::dropCloudIntent,
        discardCloudOnlyJob = { jobId ->
            downloadJobStore.discardCloudOnlyJob(jobId) { identity, revision ->
                deleteCatalogDownloadRevision(appFilesDir, identity, revision)
            }
        },
        purgeJob = downloadJobStore::purgeJob,
        hasLibraryAssociation = { job ->
            repository.downloadOnDeviceAssociationCount(job.identity) > 0
        },
        deleteFailedRevision = { job ->
            check(
                deleteCatalogDownloadRevision(appFilesDir, job.storageIdentity, job.downloadRevision),
            ) { "failed serialized chapter catalog cleanup refused for ${job.id}" }
            readyPageStore.deletePublishedRevision(job.storageIdentity, job.downloadRevision)
        },
        queueRows = cloudSyncQueue::snapshot,
        removeQueueRow = { id, chapter -> cloudSyncQueue.remove(id, chapter) },
        restoreDismissal = { id, chapter -> cloudSyncDismissalStore.restore(id, chapter) },
        clearSeriesLedger = cloudSyncLedgerStore::clearSeries,
        restoreBookmarks = { bookmarks -> bookmarks.forEach(collectBookmarkStore::upsert) },
        clearRefreshState = { identity ->
            collectedSeriesRefreshStore.clear(identity)
            smartRefreshStateStore.clear(identity)
        },
        clearNewChapterMark = { identity -> collectedSeriesNewChapterStore.clear(identity) },
        deleteUpdatesForSeries = chapterUpdateRecorder::deleteForSeries,
        removeSourceCover = { identity ->
            // Prevent a foreground pass that captured the old follow list from
            // publishing this identity after unfollow has completed.
            serializedSourceCoverAttempted.add(identity)
            serializedSourceCoverFiles.remove(identity)
        },
    ).run(series, removedBookmarks).also {
        runCatching { collectedSeriesRefreshScheduler.reconcile() }
    }

    /**
     * Re-review r1 M3: Activity's "Retry re-downloads it" for a terminal job
     * whose pages are unusable. [deleteDownloadedComic] covers FINISH and a
     * SEALED job the shelf still points at; a CLOUD-ONLY seal (no on-device
     * Library association) makes `RoomDownloadJobStore.deleteFinished`'s
     * `require` throw, the Retry handler swallows it, and the re-collect
     * rebuilds the identical parked `LOCAL_PAGES_INCOMPLETE` row — forever.
     * The discard verb is the one that may drop a cloud-only seal.
     *
     * Returns true when the caller's re-collect may proceed: the local copy
     * really went, OR there was nothing left to drop (re-review r3 HIGH-1 —
     * false for "nothing to drop" re-parked `LOCAL_COPY_NOT_DROPPED`, which
     * Retry maps straight back to this arm, so a comic the owner had already
     * deleted himself could never be re-collected from Activity again). False
     * means a real refusal: an upload in flight, a refused discard, or IO.
     * Which verb applies is [recollectDropDecision]'s decision table.
     */
    suspend fun dropUnusableLocalCopyForRecollect(identity: GalleryIdentityV1): Boolean {
        val job = downloadJobStore.jobFor(identity)
        return when (recollectDropDecision(job?.state, repository.downloadOnDeviceAssociationCount(identity))) {
            // r3 HIGH-1: nothing left to drop is SUCCESS — the re-collect proceeds.
            RecollectDropDecision.NOTHING_TO_DROP -> true
            // FINISH, or a SEALED job the shelf still points at.
            RecollectDropDecision.DELETE_VERB -> deleteDownloadedComic(identity) != null
            RecollectDropDecision.DISCARD_VERB -> {
                val seal = checkNotNull(job) // DISCARD_VERB implies a SEALED row
                // C4 §2.1, the same reason deleteDownloadedComic refuses: unlinking
                // the catalog tree under a running upload loses BOTH copies.
                val acquisition = downloadLocatorStore.get(identity)?.acquisitionUrl
                if (acquisition != null && cloudUploadInFlight(
                        cloudSyncQueue.snapshot(),
                        setOf(acquisition),
                        cloudBound = currentBucketFingerprintOrNull() != null,
                    )) {
                    DiagLog.i(
                        "CloudSync",
                        "retry discard refused: upload in flight for " +
                            redactIdentity(identity),
                    )
                    return false
                }
                val discarded = downloadJobStore.discardCloudOnlyJob(seal.id) { id, revision ->
                    deleteCatalogDownloadRevision(
                        filesDir = appFilesDir,
                        identity = id,
                        downloadRevision = revision,
                    )
                }
                discarded.state == DownloadJobState.NONE
            }
        }
    }

    /**
     * Local Room mirror of the cloud collection (loyal-pipelines Slice B).
     * Populated by [cloudHydrationEngine] (chunked walk) and [cloudIngestRunner]
     * (live upload upserts), and read by the Cloud tab shelf for instant/
     * offline/progressive rendering.
     */
    val cloudMirrorStore: RoomCloudMirrorStore by lazy {
        RoomCloudMirrorStore(
            dao = database.cloudMirrorDao(),
            // #E35: the same bounded file publisher serves hydration, shelf, trash and upload copies.
            coverSync = { cloudComicId, coverThumbObjectKey ->
                resolveGatedObjectStore()?.let { store ->
                    downloadCloudCover(CloudCollectionStore(store), cloudComicId, coverThumbObjectKey)
                }
            },
        )
    }


    /**
     * Durable per-comic sync ledger (S3 Task 1, owner bug C3/A3). Written on
     * upload success ([cloudIngestRunner]'s `onSynced` hook) and consulted by
     * the cold-start catch-up sweep BEFORE any live-R2-list logic, so a comic
     * known-synced to the CURRENT bucket is never re-uploaded just because one
     * startup R2 read momentarily failed.
     */
    val cloudSyncLedgerStore: RoomCloudSyncLedgerStore by lazy {
        RoomCloudSyncLedgerStore(dao = database.cloudSyncLedgerDao())
    }

    /**
     * #C66: [cloudIngestRunner]'s `presentInCurrentIndex` seam — the rebind
     * gap between `onBucketBound` reviving queued rows and
     * [syncLedgerWithMirror]'s ledger backfill (only run on hydration
     * completion, a minute or more later) otherwise re-uploads every comic
     * the freshly-bound bucket already carries. Reads the bound bucket's own
     * live index (memoised per fingerprint/generation — see
     * [CloudBoundIndexGate]'s KDoc) and skips the read entirely once
     * [RoomCloudMirrorStore.state]'s `hydrationComplete` is true, matching
     * [syncLedgerWithMirror]'s own fast-path condition exactly.
     */
    val cloudBoundIndexGate: CloudBoundIndexGate by lazy {
        CloudBoundIndexGate(
            currentBucketFingerprint = ::currentBucketFingerprintOrNull,
            generationFor = { fp -> cloudMirrorStore.state(fp)?.generation ?: 0 },
            hydrationComplete = { fp -> cloudMirrorStore.state(fp)?.hydrationComplete == true },
            listCards = listCards@{
                val store = resolveGatedObjectStore() ?: return@listCards emptyList()
                withContext(Dispatchers.IO) { CloudCollectionStore(store).listAllIndexCards() }
            },
        )
    }

    /**
     * S4 Task 3 ("remote hosting", owner bug C2): releases a pure-cloud job's
     * device pages once [cloudIngestRunner]'s `onSynced` has proven the
     * sync-ledger row is present. Never throws (see [CloudHostedLocalRelease]);
     * the catalog tree under `<ns>-downloads/<id>/r<rev>` is deleted the same
     * way the manual Library delete does it ([deleteCatalogDownloadRevision])
     * and the READY tree via [RoomDownloadJobStore.releaseLocalPagesAfterCloud].
     * r3 MEDIUM-3: that delete governs the NAMESPACED shape only, so a pre-#C20
     * legacy tree survives this release on purpose and is taken at the next
     * start by [reclaimOrphanLegacyCatalogTrees] instead — "the device copy is
     * gone" is true of the shape this verb owns, not of every byte.
     * Never touches `cloud-covers/`, the mirror, or the ledger. For an
     * r2source migration job the catalog path resolves to a never-existing
     * `opds-downloads/` dir, so only its READY tree goes (documented no-op).
     */
    val cloudHostedLocalRelease: CloudHostedLocalRelease by lazy {
        CloudHostedLocalRelease(
            jobFor = { identity -> downloadJobStore.jobFor(identity) },
            release = { job, expectedFingerprint ->
                cloudBindingMutationGate.withCurrentBinding(
                    currentFingerprint = ::currentBucketFingerprintOrNull,
                    expectedFingerprint = expectedFingerprint,
                ) {
                    withContext(Dispatchers.IO) {
                        downloadJobStore.releaseLocalPagesAfterCloud(
                            jobId = job.id,
                            releaseAfterUpload = cloudReleaseAfterUploadStore.read(),
                        ) { identity, revision ->
                            deleteCatalogDownloadRevision(
                                filesDir = appFilesDir,
                                identity = identity,
                                downloadRevision = revision,
                            )
                        }
                    }
                } ?: job
            },
            log = { message -> DiagLog.w("CloudSync", message) },
            logInfo = { message -> DiagLog.i("CloudSync", message) },
            releaseAfterUploadEnabled = { cloudReleaseAfterUploadStore.read() },
        )
    }

    /**
     * S3 Task 6 (owner bug 2026-09-04 — "很多漫画已经橙云了，但依然在活动页出现、在跑同步"):
     * shared ledger-presence check. True when [acquisition] (raw, possibly
     * token-bearing — a `CloudSyncQueueItem.id`, a `DownloadLocator.acquisitionUrl`,
     * or a bookmark URL) is already durably recorded in [cloudSyncLedgerStore]
     * as synced to the CURRENTLY-bound bucket. Normalizes through
     * [galleryDedupKey] first so this matches regardless of which acquisition
     * form the caller holds. No bucket currently bound, or [galleryDedupKey]
     * cannot parse [acquisition] (e.g. an `r2source://` migration row — a
     * documented limitation, see S3 Task 6 report) -> false, which preserves
     * today's unconditional-run behavior for that shape. Wired into
     * [cloudIngestRunner]'s and [workerFactory]'s own `isKnownSynced` guards,
     * [enqueueCloudUploadForJob]'s handoff-time guard, and [start]'s
     * cold-start repair sweep.
     */
    private suspend fun isKnownSyncedToCurrentBucket(
        acquisition: String,
        expectedFingerprint: String? = null,
    ): Boolean {
        val fp = currentBucketFingerprintOrNull() ?: return false
        if (expectedFingerprint != null && fp != expectedFingerprint) return false
        val key = galleryDedupKey(acquisition) ?: return false
        return cloudSyncLedgerStore.isSynced(fp, key)
    }

    /**
     * Acquisition / Front-token locators keyed by identity (never part of identity).
     * File-backed so WAIT/DOWNLOAD can resume after process restart.
     */
    val downloadLocatorStore: DownloadLocatorStore by lazy {
        FileDownloadLocatorStore(File(appFilesDir, "download-locators").also { it.mkdirs() })
    }

    val downloadTransferMeter: TransferRateMeter = TransferRateMeter()

    /**
     * E15 Task 3a: process-local in-flight page tracker. The download pump
     * records every page attempt here; the Activity row and the foreground
     * notice read it (via [downloadNotificationText] and the Activity route)
     * to say "N pages downloading slowly" while large animated pages grind.
     */
    val downloadProgressState: DownloadProgressState by lazy { DownloadProgressState() }

    private val opdsProviderStore by lazy {
        OpdsProviderStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /**
     * Survives Activity recreation. Compose `remember` for OAuth REST + Cloud
     * list does not.
     */
    val cloudProcessSession = CloudProcessSession()

    /**
     * Durable collect-to-cloud queue. Attached to its prefs-backed store at
     * construction time (single owner — Task 3/4 background pumps and the UI
     * both read/mutate this one instance instead of each holding their own
     * in-memory queue that could silently diverge).
     */
    val cloudSyncQueue by lazy {
        CloudSyncQueue().also {
            it.attach(CloudSyncQueueStore(SharedPreferencesMap.from(context.applicationContext)))
            // #C46 D2: nothing is in flight at process start \u2014 the pump's own
            // events flip rows back. load() already demotes persisted rows;
            // this also covers rows upserted before the first pump kick.
            it.markAllIdle()
        }
    }

    /**
     * A45: cloud-sync "Needs attention" rows the user has dismissed. One
     * instance, same reason as [cloudSyncQueue] — Activity writes the
     * dismissal, the cold-start catch-up sweep reads it to decide it must not
     * re-collect the bookmark behind it.
     */
    val cloudSyncDismissalStore by lazy {
        CloudSyncDismissalStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /** Durable More-tab setting for releasing local pages after cloud upload. */
    val serializedChapterOrderStore by lazy {
        com.easemic.app.feature.browse.SerializedChapterOrderStore(SharedPreferencesMap.from(context))
    }

    val cloudReleaseAfterUploadStore by lazy {
        CloudReleaseAfterUploadStore(SharedPreferencesMap.from(context.applicationContext))
    }

    val backgroundRefreshEnabledStore by lazy {
        BackgroundRefreshEnabledStore(SharedPreferencesMap.from(context.applicationContext))
    }

    val backgroundRefreshWifiOnlyStore by lazy {
        BackgroundRefreshWifiOnlyStore(SharedPreferencesMap.from(context.applicationContext))
    }

    val backgroundRefreshRunStore by lazy {
        BackgroundRefreshRunStore(SharedPreferencesMap.from(context.applicationContext))
    }

    val collectedSeriesRefreshScheduler by lazy {
        CollectedSeriesRefreshScheduler(
            enabled = { backgroundRefreshEnabledStore.read() },
            wifiOnly = { backgroundRefreshWifiOnlyStore.read() },
            hasSerializedFollows = { collectBookmarkStore.readAll().any { isSerializedBookmark(it) } },
            work = WorkManagerPeriodicRefreshGateway(WorkManager.getInstance(context.applicationContext)),
            runs = backgroundRefreshRunStore,
        )
    }

    /**
     * A42 remainder: device-local reading progress for cloud/online reads
     * (the remote-tile bar + read-resume source of truth). One instance for
     * the same single-owner reason as [cloudSyncDismissalStore] — the shelf
     * reads it while the readers write it.
     */
    val remoteReadingProgressStore by lazy {
        RemoteReadingProgressStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /**
     * Process-wide unlocked S3 binding cache (accountless — replaces the Supabase
     * binding client). Seeded lazily from the durable device secret so a headless
     * background pump (no compose UI to call `restoreFromDevice`) still resolves an
     * S3-bound store on a cold start — see [LocalR2BindingCache] (C46 Task 8).
     */
    val r2BindingCache = LocalR2BindingCache(persistedLoader = { bridgeDeviceR2SecretStore.load() })

    /** Per-device cache of the unlocked library DEK (encrypted libraries). */
    val libraryDekStore by lazy {
        BridgeDeviceLibraryKeyStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /** Process-wide encryption gate over the product object store. */
    val cloudLibraryGate by lazy {
        CloudLibraryGate(
            loadCachedDek = { libraryDekStore.load() },
            clearCachedDek = { libraryDekStore.clear() },
        )
    }

    /** Device-only Cloudflare OAuth bind secrets (refresh token bootstrap source). */
    private val bridgeOAuthDeviceSecretStore by lazy {
        BridgeOAuthDeviceSecretStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /**
     * Device-only S3/R2 paste-bind secrets. The UI
     * ([com.easemic.app.ui.PrivateComicLibraryApp]) owns
     * its own instance of this store for the bind screen; this one reads the
     * same [SharedPreferencesMap] prefs file, so both always agree on what is
     * currently bound without either side needing to own the other's
     * instance.
     */
    private val bridgeDeviceR2SecretStore by lazy {
        BridgeDeviceR2SecretStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /** #C50: the durable WebDAV binding; same prefs file as the UI's instance, so both agree. */
    val bridgeDeviceWebDavStore by lazy {
        BridgeDeviceWebDavStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /**
     * #C50: one [WebDavObjectStore] per binding, so its known-directories memo survives
     * across the many `resolveGatedObjectStore()` calls a pump makes (a fresh store per
     * call would re-MKCOL every parent on every comic).
     */
    @Volatile private var webDavStoreMemo: Pair<WebDavBinding, WebDavObjectStore>? = null

    /** #C50: the durable OneDrive binding (refresh token rotates; written synchronously). */
    val bridgeDeviceOneDriveStore by lazy {
        BridgeDeviceOneDriveStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /**
     * #C50: the ONE OneDrive token owner and store per binding (identity = drive + app folder).
     * A new sign-in (new binding value) builds a fresh pair; a rotation keeps the same one.
     */
    @Volatile private var oneDriveMemo: Pair<String, OneDriveObjectStore>? = null
    @Volatile var oneDriveTokenSource: DriveTokenSource? = null
        private set

    fun oneDriveObjectStoreOrNull(): OneDriveObjectStore? {
        val binding = bridgeDeviceOneDriveStore.load() ?: return null
        val identity = BucketFingerprint.forOneDrive(binding)
        oneDriveMemo?.let { (id, store) -> if (id == identity) return store }
        val tokens = DriveTokenSource(
            loadRefreshToken = { bridgeDeviceOneDriveStore.load()?.refreshToken },
            saveRefreshTokenSync = bridgeDeviceOneDriveStore::saveRefreshTokenSync,
            refresh = { DrivePkceClient(DriveOAuthConfigs.ONEDRIVE).refresh(it) },
            accessCache = bridgeDeviceOneDriveStore.accessCache,
        )
        oneDriveTokenSource = tokens
        return OneDriveObjectStore(binding.rootItemId, tokens).also { oneDriveMemo = identity to it }
    }

    /** #C50: the durable Google Drive binding (Google does not rotate refresh tokens). */
    val bridgeDeviceGoogleDriveStore by lazy {
        BridgeDeviceGoogleDriveStore(SharedPreferencesMap.from(context.applicationContext))
    }

    @Volatile private var googleDriveMemo: Pair<String, GoogleDriveObjectStore>? = null
    @Volatile var googleDriveTokenSource: DriveTokenSource? = null
        private set

    fun googleDriveObjectStoreOrNull(): GoogleDriveObjectStore? {
        val binding = bridgeDeviceGoogleDriveStore.load() ?: return null
        val identity = BucketFingerprint.forGoogleDrive(binding)
        googleDriveMemo?.let { (id, store) -> if (id == identity) return store }
        val tokens = DriveTokenSource(
            loadRefreshToken = { bridgeDeviceGoogleDriveStore.load()?.refreshToken },
            saveRefreshTokenSync = {},
            refresh = { DrivePkceClient(DriveOAuthConfigs.GOOGLE_DRIVE).refresh(it) },
            accessCache = bridgeDeviceGoogleDriveStore.accessCache,
        )
        googleDriveTokenSource = tokens
        return GoogleDriveObjectStore(binding.rootFolderId, tokens).also { googleDriveMemo = identity to it }
    }

    /** #C50: the OneDrive grant was revoked (`invalid_grant`); only a new sign-in recovers. */
    fun isOneDriveBindingDead(): Boolean =
        bridgeDeviceOneDriveStore.load() != null && oneDriveTokenSource?.isDead() == true

    fun webDavObjectStoreOrNull(): WebDavObjectStore? {
        val binding = bridgeDeviceWebDavStore.load() ?: return null
        webDavStoreMemo?.let { (memoBinding, store) -> if (memoBinding == binding) return store }
        return WebDavObjectStore(binding).also { webDavStoreMemo = binding to it }
    }

    /**
     * Persisted rolling window of real R2 ping latencies (owner bug #3 fix,
     * S3 Task 5 task B) — feeds [CloudProbeGrace.plan]'s adaptive timeout via
     * [probeCloudReachability], which records every successful probe's
     * measured latency here.
     */
    val cloudPingBaselineStore by lazy {
        CloudPingBaselineStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /**
     * Fingerprint of whichever durable binding is currently persisted — at
     * most one of the two device-secret stores above is normally populated
     * (each bind path clears the other). Null when neither is bound. Used to
     * scope the hydration mirror ([cloudMirrorStore]) and as
     * [CloudHydrationEngine]'s `fingerprint()` lambda.
     */
    fun currentBucketFingerprintOrNull(): String? =
        currentBucketFingerprint(
            bridgeDeviceR2SecretStore.load(),
            bridgeOAuthDeviceSecretStore.load(),
            bridgeDeviceWebDavStore.load(),
            bridgeDeviceOneDriveStore.load(),
            bridgeDeviceGoogleDriveStore.load(),
        )

    private val cloudflarePkce by lazy {
        CloudflarePkceClient(clientId = CloudflareOAuthConfig.CLIENT_ID)
    }

    /**
     * Single-flight OAuth REST store bootstrap. A shared instance (not a
     * per-call `OauthRestStoreBootstrap()`) so a background pump and the
     * compose LaunchedEffect can never publish two stores for the same
     * bucket; the token behind them belongs to [oauthTokenSource].
     */
    private val oauthRestBootstrap by lazy { OauthRestStoreBootstrap() }

    /** C37-c: the ONE OAuth access-token owner in this process. Blocking; callers hop to IO. */
    val oauthTokenSource by lazy {
        OauthAccessTokenSource(
            secrets = bridgeOAuthDeviceSecretStore,
            exchange = { refreshToken -> cloudflarePkce.refresh(refreshToken) },
        )
    }

    /**
     * Publishes the cached R2 REST store for the bound bucket. This is the
     * ONE bootstrap implementation: `PrivateComicLibraryApp`'s compose
     * LaunchedEffect calls this (with the defaults, which read/write
     * [cloudProcessSession] — the same session flow its `oauthRestCache`
     * state observes, so recomposition still reacts to a newly published
     * store) and so does the compose-free [resolveGatedObjectStore] below.
     * Neither caller exchanges a token itself: [oauthTokenSource] does, once,
     * for the whole process.
     *
     * The cache key is `accountId|bucket` ([OauthRestStoreRefresh.cacheKeyFor]),
     * which is stable across the refresh-token rotation every exchange
     * performs — keying it on the token used to make a live 401 recovery look
     * like a cache miss and trigger yet another exchange.
     */
    suspend fun refreshOauthRestStore(
        failureMessage: String = BridgeR2BindCopy.CONNECT_FAILED,
        loadCachedKey: () -> String? = { cloudProcessSession.oauthRestCache?.first },
        onReady: (cacheKey: String, store: ObjectStore) -> Unit = { cacheKey, store ->
            cloudProcessSession.oauthRestCache = cacheKey to store
        },
    ): OauthRestStoreRefreshResult = oauthRestBootstrap.refresh(
        loadSecrets = { bridgeOAuthDeviceSecretStore.load() },
        clientIdBlank = CloudflareOAuthConfig.CLIENT_ID.isBlank(),
        loadCachedKey = loadCachedKey,
        tokenSource = oauthTokenSource,
        failureMessage = failureMessage,
        onReady = onReady,
    )

    /**
     * Cached OAuth REST store, valid only while its `accountId|bucket` key
     * still matches the currently-persisted device secrets — the single
     * staleness check shared by the compose `oauthRestStore()` accessor and
     * [resolveGatedObjectStore]'s cached branch, so a device rebind can never
     * leave one caller holding a store pointed at the previous bucket while
     * the other has already moved on.
     *
     * C37-c review (F5): a dead [oauthTokenSource] invalidates the cache too,
     * key or no key — a grant revoked mid-session leaves the `accountId|bucket`
     * key perfectly valid in front of a store whose bearer can never produce a
     * token again. Rejecting it here makes [resolveGatedObjectStore] fall
     * through to [refreshOauthRestStore], whose own dead check (above its
     * cache-key skip) answers Failed(RECONNECT); the gated store resolves to
     * null and `CloudIngestRunner`'s dead-grant branch parks the row with that
     * copy, while the Cloud storage screen's `refreshOauthRestStore()` consumer
     * shows the user the same words. See
     * [OauthRestStoreRefresh.cachedOauthStoreUsable] for the rule.
     */
    fun validCachedOauthStore(): ObjectStore? {
        val secrets = bridgeOAuthDeviceSecretStore.load() ?: return null
        val (cachedKey, store) = cloudProcessSession.oauthRestCache ?: return null
        return store.takeIf {
            OauthRestStoreRefresh.cachedOauthStoreUsable(
                cachedKey = cachedKey,
                secrets = secrets,
                dead = oauthTokenSource.isDead(),
            )
        }
    }

    /** C37-g: true when the persisted OAuth grant has been rejected by the server (`invalid_grant`). */
    fun isOauthBindingDead(): Boolean =
        bridgeOAuthDeviceSecretStore.load() != null && oauthTokenSource.isDead()

    /**
     * C35-a acceptance-probe seam: when non-null,
     * [resolveGatedObjectStore] answers from this lambda and never looks at
     * the real binding. `null` in production — nothing but an instrumentation
     * test ever assigns it. `@Volatile` because the probe sets it from the
     * test thread while background pumps read it.
     */
    @VisibleForTesting
    @Volatile
    internal var cloudStoreResolveOverride: (suspend () -> ObjectStore?)? = null

    /**
     * Compose-free gated product store for background pumps: same resolution
     * order and the same encryption gate as the UI's `productObjectStore()`
     * (S3 working binding -> cached OAuth REST store -> fresh OAuth
     * bootstrap). Returns null when nothing is bound (worker rows then fail
     * honestly instead of silently skipping).
     */
    suspend fun resolveGatedObjectStore(): ObjectStore? {
        val fp = currentBucketFingerprintOrNull()
        val store = resolveGatedObjectStoreWithoutJournal() ?: return null
        return if (fp == null) store else com.easemic.app.sync.SeriesIndexObjectStore(
            store, seriesIndexJournal(fp),
        )
    }

    private fun seriesIndexJournal(fp: String) = com.easemic.app.sync.SeriesIndexJournal(
        File(appFilesDir, "series-index/$fp"),
    )

    private suspend fun repairSeriesIndexes(force: Boolean): Boolean {
        val fp = currentBucketFingerprintOrNull() ?: return true
        if (seriesIndexJournal(fp).dirtyIds().isEmpty() && !CloudCollectionStore.hasPendingChapterTails()) return true
        val store = resolveGatedObjectStore() ?: return false
        val rows = cloudSyncQueue.snapshot()
        val downloads = downloadJobStore.loadAllJobs()
        return kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            CloudCollectionStore(store).repairSeriesIndexes(
                force = force,
                forceSeries = { summary ->
                    com.easemic.app.feature.collect.shouldFlushSeriesIndex(summary, rows, downloads, force)
                },
            )
        }
    }

    private suspend fun resolveGatedObjectStoreWithoutJournal(): ObjectStore? {
        // C35-a acceptance seam: consulted FIRST so a probe can force
        // `Deferred` (override returning null) even on a device with a live
        // binding, and arm a real pump backoff to measure a user kick against.
        cloudStoreResolveOverride?.let { return it() }
        val born = cloudLibraryGate.generation() // #C64: before the binding
        val working = r2BindingCache.workingBinding()
        if (working != null) return cloudLibraryGate.wrap(R2ObjectStore(working), born)
        validCachedOauthStore()?.let { return cloudLibraryGate.wrap(it, born) }
        // #C50: a WebDAV binding needs no token bootstrap; same precedence as the fingerprint.
        if (bridgeOAuthDeviceSecretStore.load() == null) {
            webDavObjectStoreOrNull()?.let { return cloudLibraryGate.wrap(it, born) }
            oneDriveObjectStoreOrNull()?.let { return cloudLibraryGate.wrap(it, born) }
            googleDriveObjectStoreOrNull()?.let { return cloudLibraryGate.wrap(it, born) }
        }
        val result = refreshOauthRestStore()
        val store = (result as? OauthRestStoreRefreshResult.Ready)?.store ?: return null
        return cloudLibraryGate.wrap(store, born)
    }

    /**
     * S4 Task 1 (owner bug C4): bounded downscale (longest edge <=
     * [THUMBNAIL_MAX_EDGE], via the shared [boundedThumbnailSampleSize]
     * `inSampleSize` logic) + JPEG quality-80 re-encode of an on-device page
     * file into a small durable cover. Returns null on any decode failure —
     * [com.easemic.app.sync.CloudCollectionIngest]'s
     * `thumbnailer` seam falls back to the page's raw bytes unmodified in
     * that case, exactly the pre-fix behavior.
     */
    private fun decodeCoverThumbnail(file: File): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        encodeCoverJpeg(bounds) { options -> BitmapFactory.decodeFile(file.absolutePath, options) }
    } catch (error: Exception) {
        DiagLog.w(TAG_CLOUD_COVER, "failed decoding cover thumbnail from file ${OpdsCatalogClient.redactUrl(file.name)}", error)
        null
    }

    /**
     * Validates a remote cover body for [cloudMirrorStore]'s hydration path.
     * The downloaded object is already the full-resolution `pages/` cover;
     * unlike the per-page `thumbs/` derivative, it must be persisted without
     * downscaling or re-encoding.
     */
    private fun validateCoverFile(file: File): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        bounds.outWidth > 0 && bounds.outHeight > 0
    } catch (error: Exception) {
        DiagLog.w(TAG_CLOUD_COVER, "failed validating cover file", error)
        false
    }

    private fun encodeCoverJpeg(
        bounds: BitmapFactory.Options,
        decode: (BitmapFactory.Options) -> Bitmap?,
    ): ByteArray? {
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // C5 audit MEDIUM-3: decode one power of two ABOVE the cap, then scale
        // to exactly the cap — power-of-two sampling alone halved a 1288 px
        // page to a 161 px cell.
        val options = BitmapFactory.Options().apply {
            inSampleSize = thumbnailDecodeSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = decode(options) ?: return null
        val (targetW, targetH) = thumbnailTargetSize(decoded.width, decoded.height)
        val bitmap = if (targetW == decoded.width && targetH == decoded.height) {
            decoded
        } else {
            decoded.scale(targetW, targetH).also { if (it !== decoded) decoded.recycle() }
        }
        return try {
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)) return null
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * #E102: the durable shelf cover is a 720px JPEG-80 DERIVATIVE of the
     * ingest's first page, not the page itself — a hi-res or animated first
     * page is multi-MB forever otherwise. Publication semantics unchanged:
     * [CloudCoverFiles.copyBytes] stages, validates and atomically moves the
     * derived bytes exactly like the old raw copy, before the upload's page
     * lease ends. Returns null when the source does not decode — the durable
     * copy is skipped rather than persisting undecodable bytes (logged under
     * [TAG_CLOUD_COVER] by [encodeCloudCoverDerivative]).
     */
    fun persistCloudCover(cloudComicId: String, source: File): String? {
        val derived = encodeCloudCoverDerivative(source) ?: return null
        return cloudCoverFiles.copyBytes(cloudComicId, derived, File(cloudCoversRoot, "$cloudComicId.jpg"))
    }

    private suspend fun cloudCoverSummary(id: String): com.easemic.app.feature.importing.CloudCoverSummary? {
        val fp = currentBucketFingerprintOrNull() ?: return null
        val card = cloudMirrorStore.card(fp, id)?.takeIf { it.layoutKind == com.easemic.app.sync.CloudCardLayout.SERIES }
            ?: return null
        return com.easemic.app.feature.importing.CloudCoverSummary(fp, card.seriesGeneration,
            card.title, card.seriesMetaObjectKey.orEmpty(), card.chapterCount)
    }

    suspend fun downloadCloudCover(store: CloudCollectionStore, id: String, key: String): String? {
        val summary = cloudCoverSummary(id)
        return runInterruptible(Dispatchers.IO) {
            // #E102: the hydrated object is the full-resolution pages/ cover;
            // the bucket copy stays untouched (cross-device format stability)
            // and only the on-device durable copy becomes the 720px derivative.
            cloudCoverFiles.download(store, id, key, File(cloudCoversRoot, "$id.jpg"), summary,
                transform = ::encodeCloudCoverDerivative)
        }
    }

    suspend fun downloadCloudCachedCover(store: CloudCollectionStore, id: String, key: String, dest: File): String? {
        val summary = cloudCoverSummary(id)
        return runInterruptible(Dispatchers.IO) {
            cloudCoverFiles.download(store, id, key, dest, summary) { publish ->
                CloudThumbCache.publishFile(checkNotNull(dest.parentFile), dest, cacheBudget, publish)
            }
        }?.let { File(it).toURI().toString() }
    }

    /** Prefer durable storage; if it fails, recover through the separately budgeted cache file route. */
    suspend fun downloadCloudShelfCover(store: CloudCollectionStore, id: String, key: String): CloudShelfCover? {
        val durable = downloadCloudCover(store, id, key)
        if (durable != null) return CloudShelfCover(File(durable).toURI().toString(), durable)
        val cached = downloadCloudCachedCover(
            store, id, key, CloudThumbCache.coverFile(CloudThumbCache.root(appContext.cacheDir), id),
        ) ?: return null
        return CloudShelfCover(cached, null)
    }

    /**
     * Real, lightweight R2 round-trip for the cloud-reachability grace loop
     * (owner bug #3, S3 Task 5 task C, spec-review fix). GETs the tiny
     * bucket-root lock object ([CloudCollectionKeys.lockKey]) — the one
     * object this library always writes in plaintext — instead of a full
     * bucket listing.
     *
     * Spec-review correction: the original version of this probe called
     * [ObjectStore.listKeys], which pages the ENTIRE bucket via repeated
     * ListObjectsV2 calls. That is the opposite of lightweight, and it
     * violates the model's rule that browsing/probing never lists R2 — up
     * to 4x per cold start once the grace loop's retries are counted. A
     * single-object GET is the cheapest real round-trip [ObjectStore]
     * exposes; no prefix parameter was added to [ObjectStore] itself
     * (the sync package is owned by another agent in-flight).
     *
     * [store] is resolved by the caller (the grace loop in
     * PrivateComicLibraryApp.kt), fresh on every attempt — so a store that
     * only becomes resolvable on a later attempt (e.g. OAuth warms up after
     * a cold-start hiccup) is exactly the instance this probes AND, on
     * success, the instance the caller then uses for the listing that
     * follows. This function no longer resolves or Blocked-classifies the
     * store itself; that decision now lives in the caller so the SAME
     * Blocked-this-attempt case (an OAuth refresh not yet warm) can be
     * graded through the same grace/backoff path as a failed ping instead
     * of short-circuiting straight to a banner — see
     * [com.easemic.app.feature.bridge.graceOutcomeForBlockedStore].
     *
     * A 404/NoSuchKey response still counts as [ProbeOutcome.Reachable]:
     * the bucket answered the request — the lock object simply isn't there
     * yet (an unencrypted or brand-new library) — which is not the same as
     * an unreachable bucket. 401 (and a revoked drive sign-in) is a refused
     * sign-in ([ProbeCause.SIGN_IN_REJECTED], #D16); 403 is a store error
     * ([ProbeCause.STORE_ERROR]); timeout, DNS, other IO, and any other
     * HTTP status (5xx included) are [ProbeCause.NETWORK]. See
     * [com.easemic.app.feature.bridge.classifyLockProbeFailure]
     * for the pure, JVM-tested version of this classification.
     */
    suspend fun probeCloudReachability(timeoutMs: Long, store: ObjectStore): ProbeOutcome {
        val startedAtMs = System.currentTimeMillis()
        val result = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) { runCatching { store.download(CloudCollectionKeys.lockKey()) } }
        }
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        if (result == null) {
            return ProbeOutcome.Unreachable(ProbeCause.NETWORK, "Cloud ping timed out.")
        }
        return result.fold(
            onSuccess = {
                cloudPingBaselineStore.record(elapsedMs)
                ProbeOutcome.Reachable(elapsedMs)
            },
            onFailure = { error ->
                val outcome = classifyLockProbeFailure(error.message, elapsedMs)
                if (outcome is ProbeOutcome.Reachable) {
                    cloudPingBaselineStore.record(elapsedMs)
                }
                outcome
            },
        )
    }

    /**
     * The third unique foreground pump's engine (loyal-pipelines Slice B pump
     * task §2). Same gated-store resolution as [cloudIngestRunner]/the UI's
     * product store, mirrored into [cloudMirrorStore]. [CloudCollectionStore]'s
     * calls are synchronous, so each lambda wraps its actual store hit in
     * [withContext]([Dispatchers.IO]) the same way [CloudIngestRunner.run]
     * does — [resolveGatedObjectStore] itself is left on the ambient
     * dispatcher, same as [cloudIngestRunner]'s `resolveStore`. A store that
     * resolves to null (bucket fingerprint present but nothing currently
     * resolvable — e.g. a transient OAuth bootstrap failure) fails the step
     * honestly via [error] rather than silently skipping, so the pump maps it
     * onto [com.easemic.app.feature.collect.HydrationOutcome.Failed]
     * and retries instead of getting stuck.
     */
    val cloudHydrationEngine: CloudHydrationEngine by lazy {
        CloudHydrationEngine(
            fingerprint = ::currentBucketFingerprintOrNull,
            downloadManifest = {
                val store = resolveGatedObjectStore() ?: error("CLOUD_HYDRATION_STORE_UNAVAILABLE")
                withContext(Dispatchers.IO) {
                    val collection = CloudCollectionStore(store)
                    // Do not prune the uploading device's live cards/ledger against its own lagging index.
                    // Hydration needs the index now, so it forces one publication attempt instead of waiting
                    // out the 30 s coalescing window; a failed attempt still defers hydration to its retry.
                    check(collection.repairSeriesIndexes(force = true)) { "CLOUD_SERIES_INDEX_PENDING" }
                    collection.downloadCollectionManifest()
                }
            },
            listChunk = { chunkIndex ->
                val store = resolveGatedObjectStore() ?: error("CLOUD_HYDRATION_STORE_UNAVAILABLE")
                withContext(Dispatchers.IO) { CloudCollectionStore(store).listIndexChunk(chunkIndex).cards }
            },
            downloadMeta = { cloudComicId ->
                val store = resolveGatedObjectStore() ?: error("CLOUD_HYDRATION_STORE_UNAVAILABLE")
                withContext(Dispatchers.IO) { CloudCollectionStore(store).downloadMeta(cloudComicId) }
            },
            downloadSeriesManifest = { seriesMetaObjectKey ->
                val store = resolveGatedObjectStore() ?: error("CLOUD_HYDRATION_STORE_UNAVAILABLE")
                withContext(Dispatchers.IO) { CloudCollectionStore(store).downloadSeriesManifest(seriesMetaObjectKey) }
            },
            mirror = cloudMirrorStore,
            onManifestChecked = { nowMs -> hydrationRecheckStore.markChecked(nowMs) },
            // A24-1: a hydration re-walk's own prune just evicted mirror
            // cards no longer present upstream — reconcile the durable sync
            // ledger against the SAME (fp, keepGeneration) right away so an
            // orphaned row does not sit around skipping that comic's
            // re-upload until the next cold start's reconcile pass.
            onPruned = { fp, keepGeneration ->
                val removed = cloudSyncLedgerStore.reconcileWithMirror(
                    fp,
                    keepGeneration,
                    cloudMirrorStore.allCards(fp),
                )
                if (removed > 0) {
                    DiagLog.i(
                        "CloudSync",
                        "cloud ledger reconcile (hydration prune): removed $removed orphan rows fp=$fp generation=$keepGeneration",
                    )
                }
                // Unconditional: reconcile is idempotent, so if repair fails
                // once, a retry would see removed == 0 and never repair
                // again. The sweep itself is cheap (loads jobs, enqueues
                // only un-ledgered terminal cloud-intent jobs).
                try {
                    repairCloudHandoffs()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DiagLog.w("CloudSync", "post-prune handoff repair failed; will retry at next cold start", error)
                }
            },
            // A34: a hydration walk just transitioned the mirror to
            // hydrationComplete = true — this is the SAME event that fires
            // after a disconnect (AppGraph.onCloudDisconnected wipes the
            // ledger via invalidateAll) followed by a rebind to the same
            // bucket, which only re-hydrates the mirror rather than running
            // the cold-start sweep. Without this, the ledger would sit empty
            // until the next process launch even though the mirror is fully
            // populated again.
            onHydrationComplete = { fp, generation -> syncLedgerWithMirror(fp, generation) },
        )
    }

    /**
     * Caps concurrent cloud Collect ingests. A single shared instance so a
     * future WorkManager pump (Task 4) and the UI never independently allow
     * more than [MaxConcurrentTransfersStore.MAX] uploads at once — sized to
     * the store's ceiling rather than [CloudSyncIngestGate.DEFAULT_MAX_ACTIVE]
     * since the owner's Settings-screen slider (S6-A) can raise the actual
     * live cap up to that ceiling at runtime; the semaphore itself must be
     * built wide enough up front to ever honour it.
     */
    val cloudSyncIngestGate by lazy { CloudSyncIngestGate(maxActive = MaxConcurrentTransfersStore.MAX) }

    /** Stable cloudComicId per acquisition URL; shared by the UI and [cloudIngestRunner]. */
    val cloudComicIdStore by lazy {
        CloudComicIdStore(SharedPreferencesMap.from(context.applicationContext))
    }

    /**
     * Same persisted "Collected" list the UI layer reads/writes (both resolve
     * to the same [SharedPreferencesMap.PREFS_NAME] file regardless of which
     * Context constructs them). [enqueueCloudUploadForJob] consults this to
     * skip an upload for a job whose bookmark was uncollected mid-fetch
     * (whole-slice review GAP 2) — a dual-intent job is deliberately left
     * running (see [com.easemic.app.feature.collect.applyUncollectJobOutcome])
     * and can still reach SEALED/FINISH after the bookmark is gone.
     */
    val collectBookmarkStore by lazy {
        CollectBookmarkStore(SharedPreferencesMap.from(context.applicationContext))
    }

    val typesafeApiKeyStore by lazy {
        TypesafeApiKeyStore(SharedPreferencesMap.from(context.applicationContext))
    }

    val adFingerprintStore by lazy {
        AdFingerprintStore(SharedPreferencesMap.from(context.applicationContext))
    }

    val trailingAdStripper by lazy {
        TrailingAdStripper(fingerprintStore = adFingerprintStore)
    }

    /**
     * #C56: adopts every ACTIVE hydrated card with a source acquisition into
     * [collectBookmarkStore], called from [syncLedgerWithMirror]. See
     * `docs/superpowers/specs/2026-09-21-c56-collection-adoption-design.md` §4.1.
     */
    val cloudCollectionAdopter by lazy {
        CloudCollectionAdopter(
            bookmarks = collectBookmarkStore,
            fileExists = { File(it).exists() },
            coverUri = { Uri.fromFile(File(it)).toString() },
        )
    }

    /** #C56: bumped once per hydration completion that adds >=1 bookmark, so the UI can refresh without a restart. */
    val collectionAdoptionEpoch = MutableStateFlow(0)

    /** Shared identity resolution for the cloud-ingest local-pages helpers below. */
    /**
     * C2 audit MEDIUM-1: the cloud ingest of a SEALED job reads its pages from
     * the `download-ready-pages` staging revision for the whole upload, so it
     * holds the same reader lease the online reader holds
     * ([ReadyPageStore.retainReader]); a FINISH that lands meanwhile (a Download
     * tap adopting the sealed pages) then defers C2's staging reclaim until the
     * upload closes the lease instead of unlinking the pages under it. Null for
     * anything that is not a SEALED job (FINISH reads the formal tree).
     */
    private suspend fun retainSealedStagingForIngest(
        persistedIdentity: GalleryIdentityV1?,
        r2Source: Pair<String, String>?,
        indexGalleryId: Long?,
        frontGalleryId: Long?,
    ): AutoCloseable? {
        val identity = persistedIdentity
            ?: r2Source?.let { (fingerprint, cloudComicId) ->
                GalleryIdentityV1(providerNamespace = GalleryIdentityV1.r2SourceNamespace(fingerprint), canonicalItemId = cloudComicId)
            }
            ?: identityForCloudIngest(indexGalleryId, frontGalleryId)
            ?: return null
        val job = downloadJobStore.jobFor(identity) ?: return null
        if (job.state != DownloadJobState.SEALED) return null
        return readyPageStore.retainReader(job.identity, job.downloadRevision)
    }

    private suspend fun retainSerializedSealedStagingForIngest(
        series: GalleryIdentityV1,
        chapterIdentity: String,
    ): AutoCloseable? {
        val job = downloadJobStore.jobFor(series, chapterIdentity) ?: return null
        if (job.state != DownloadJobState.SEALED) return null
        return readyPageStore.retainReader(job.storageIdentity, job.downloadRevision)
    }

    private fun identityForCloudIngest(indexGalleryId: Long?, frontGalleryId: Long?): GalleryIdentityV1? =
        when {
            indexGalleryId != null -> GalleryIdentityV1(
                providerNamespace = GalleryIdentityV1.NHENTAI,
                canonicalItemId = indexGalleryId.toString(),
            )
            frontGalleryId != null -> GalleryIdentityV1(
                providerNamespace = GalleryIdentityV1.EHENTAI,
                canonicalItemId = frontGalleryId.toString(),
            )
            else -> null
        }

    /**
     * Local pages resolvable for [job.state] (FINISH or SEALED), factored out
     * of [localCatalogPagesForCloudIngest] so a caller that already holds a
     * [DownloadJob] in hand (FIX3's collect-time direct handoff) does not
     * have to re-query [downloadJobStore] by identity to reuse the same
     * page-resolution logic.
     */
    private fun resolvePagesForDownloadJob(job: DownloadJob): List<File>? =
        when (job.state) {
            DownloadJobState.FINISH -> {
                val expected = job.expectedPageCount ?: return null
                resolveLocalCatalogPagesForIngest(
                    filesDir = appFilesDir,
                    identity = job.storageIdentity,
                    downloadRevision = job.downloadRevision,
                    expectedPageCount = expected,
                )
            }
            DownloadJobState.SEALED -> {
                val pageCount = job.sealedTruncatedAt ?: job.expectedPageCount ?: return null
                resolveSealedReadyPagesForIngest(
                    readyPageStore = readyPageStore,
                    identity = job.storageIdentity,
                    downloadRevision = job.downloadRevision,
                    pageCount = pageCount,
                )
            }
            else -> null
        }

    /**
     * Locally already-downloaded pages for a cloud ingest. FINISH (local-intent
     * completion) reads the materialized catalog-download tree, unchanged.
     * SEALED (spec §1.1 fetch→upload handoff — full completion or truncation
     * wind-down of a cloud-intent job) reads straight off the [ReadyPageStore]
     * tree via [resolveSealedReadyPagesForIngest] instead: that job's pages were
     * never materialized into the FINISH tree. Moved here (from the compose
     * layer) so [cloudIngestRunner] can call it without a UI dependency.
     */
    suspend fun localCatalogPagesForCloudIngest(
        indexGalleryId: Long?,
        frontGalleryId: Long?,
    ): List<File>? {
        val identity = identityForCloudIngest(indexGalleryId, frontGalleryId) ?: return null
        val job = downloadJobStore.jobFor(identity) ?: return null
        return resolvePagesForDownloadJob(job)
    }

    /** Identity-first variant for opaque providers such as OPDS. */
    private suspend fun localCatalogPagesForCloudIngestIdentity(
        identity: GalleryIdentityV1,
    ): List<File>? {
        val job = downloadJobStore.jobFor(identity) ?: return null
        return resolvePagesForDownloadJob(job)
    }

    /**
     * True for a SEALED cloud-intent download job, or a FINISH job that also
     * carries intentCloud (spec §1.1 FIX2: a dual-intent collect+download,
     * OR-merged by the DAO onto one row, FINISHes rather than SEALs but still
     * has device pages and no source hit needed) — gates [cloudIngestRunner]
     * onto the upload-only path. Delegates to [isCloudIngestReadyDownloadJob]
     * so the same rule is unit-testable without an Android Context.
     */
    suspend fun isDownloadJobSealedForCloudIngest(indexGalleryId: Long?, frontGalleryId: Long?): Boolean {
        val identity = identityForCloudIngest(indexGalleryId, frontGalleryId) ?: return false
        return isCloudIngestReadyDownloadJob(downloadJobStore.jobFor(identity))
    }

    /** Identity-first sealed-state gate for opaque providers such as OPDS. */
    private suspend fun isDownloadJobSealedForCloudIngestIdentity(
        identity: GalleryIdentityV1,
    ): Boolean = isCloudIngestReadyDownloadJob(downloadJobStore.jobFor(identity))

    /** Identity-first truncation metadata lookup for opaque providers. */
    private suspend fun truncatedFetchedPagesForCloudIngestIdentity(
        identity: GalleryIdentityV1,
    ): Int? = downloadJobStore.jobFor(identity)?.sealedTruncatedAt

    /**
     * True when [job] (already resolved — e.g. the row [downloadJobStore]
     * just returned from an `upsertEnqueue` call at collect time) is a
     * terminal state whose pages are already on-device, so the collect-time
     * caller (FIX3) can fire [enqueueCloudUploadForJob] directly instead of
     * relying on [downloadWorkerLoop]'s pump — a terminal job is never
     * re-admitted to the consumption queue, so the pump would never revisit
     * it. Does not itself re-query the job store: callers that only have
     * identity should go through [isDownloadJobSealedForCloudIngest] instead.
     */
    fun isJobResolvableForDirectCloudHandoff(job: DownloadJob): Boolean =
        isCloudIngestReadyDownloadJob(job) && resolvePagesForDownloadJob(job) != null


    /** Threaded into the uploaded [com.easemic.app.sync.CloudComicMeta] (spec §1.3 option A). */
    suspend fun truncatedFetchedPagesForCloudIngest(indexGalleryId: Long?, frontGalleryId: Long?): Int? {
        val identity = identityForCloudIngest(indexGalleryId, frontGalleryId) ?: return null
        return downloadJobStore.jobFor(identity)?.sealedTruncatedAt
    }

    /**
     * Slice-C2: resolves already-fetched OLD-bucket page files for an
     * r2source row. The r2source download adapter materializes pages through
     * the exact same READY/FINISH job mechanics as every other provider
     * namespace, so [resolvePagesForDownloadJob] is reused unchanged — only
     * the identity lookup differs (fingerprint+oldCloudComicId instead of a
     * Front/Index gallery id).
     */
    private suspend fun r2SourceLocalPagesForIngest(oldFingerprint: String, oldCloudComicId: String): List<File>? {
        val identity = GalleryIdentityV1(
            providerNamespace = GalleryIdentityV1.r2SourceNamespace(oldFingerprint),
            canonicalItemId = oldCloudComicId,
        )
        val job = downloadJobStore.jobFor(identity) ?: return null
        return resolvePagesForDownloadJob(job)
    }

    /** #E130: [followCanRefresh] with this device's binding, jobs and mirror; an unreadable answer means wait. */
    private suspend fun serializedMirrorResolved(series: SeriesIdentityV2): Boolean = try {
        val fp = currentBucketFingerprintOrNull()
        followCanRefresh(
            bucketBound = fp != null,
            hasLocalChapterJobs = fp == null && downloadJobStore
                .jobsForSeries(GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId))
                .isNotEmpty(),
            mirrorComplete = fp != null && cloudMirrorStore.state(fp)?.hydrationComplete == true,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    /** Resolves a serialized series id without allowing a chapter locator to mint one. */
    private suspend fun serializedCloudIdFor(identity: SeriesIdentityV2): String? {
        val key = serializedSeriesLedgerKey(identity)
        val fp = currentBucketFingerprintOrNull()
        if (fp != null) {
            database.cloudSyncLedgerDao().cloudComicIdForSeries(fp, key)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            cloudMirrorStore.activeSeriesCard(fp, identity)
                ?.cloudComicId
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return cloudComicIdStore.getOrCreate(identity)
    }

    /**
     * #E80: the top-level `cloud-ingest` work-dir names the CURRENT queue rows
     * map to. Each row resolves through exactly the id the runner would upload
     * under (CloudIngestRunner's own cloudId pick): a serialized row through
     * [serializedCloudIdFor], a gallery row through the runner's `cloudComicIdFor`
     * (= [cloudComicIdStore].getOrCreate). Fail closed for the WHOLE set: one
     * row whose id is unresolvable or throws means the sweep cannot tell live
     * from orphaned, and "cannot tell" deletes nothing.
     */
    private suspend fun liveCloudIngestWorkIds(): Set<String>? {
        try {
            val ids = mutableSetOf<String>()
            for (row in cloudSyncQueue.snapshot()) {
                val id = if (row.chapterIdentity != GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID) {
                    val provider = row.providerId?.trim().orEmpty()
                    val canonical = row.canonicalItemId?.trim().orEmpty()
                    // Same missing-identity rule as the runner's cloudId pick:
                    // a blank pair cannot name a work dir, so it cannot be told
                    // apart from an orphan.
                    if (provider.isBlank() || canonical.isBlank()) return null
                    serializedCloudIdFor(SeriesIdentityV2(provider, canonical)) ?: return null
                } else {
                    cloudComicIdStore.getOrCreate(row.id)
                }
                ids += id
            }
            return ids
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
    }

    private suspend fun isJobSyncedToCurrentBucket(job: DownloadJob): Boolean {
        val fp = currentBucketFingerprintOrNull() ?: return false
        return isJobSyncedToBucket(job, fp)
    }

    private suspend fun isJobSyncedToBucket(job: DownloadJob, fp: String): Boolean {
        if (job.isSerializedChapter) {
            return isSerializedJobSyncedToBucket(job, fp) { bucket, key, chapter ->
                cloudSyncLedgerStore.isSynced(bucket, key, chapter)
            }
        }
        return downloadLocatorStore.get(job.identity)?.acquisitionUrl
            ?.let { isKnownSyncedToCurrentBucket(it) } == true
    }

    /** Builds one serialized upload solely from the follow, shelf facts, and local page files. */
    private suspend fun serializedUploadRequestFor(
        row: com.easemic.app.feature.collect.CloudSyncQueueItem,
        seriesCloudId: String,
        chapterKey: String,
        checkpointWorkDir: File,
        syncedChapterKeys: Map<String, String>,
    ): CloudSeriesChapterUploadRequest? {
        val provider = row.providerId?.takeIf { it.isNotBlank() } ?: return null
        val canonical = row.canonicalItemId?.takeIf { it.isNotBlank() } ?: return null
        val seriesIdentity = GalleryIdentityV1(provider, canonical)
        val job = downloadJobStore.jobFor(seriesIdentity, row.chapterIdentity) ?: return null
        val pageFiles = resolvePagesForDownloadJob(job) ?: return null
        if (pageFiles.isEmpty()) return null
        val bookmark = collectBookmarkStore.readAll().firstOrNull { bookmark ->
            isSerializedBookmark(bookmark) && bookmark.providerId == provider && bookmark.id == canonical
        } ?: return null
        if (bookmark.title.isBlank()) return null
        val seriesAcquisition = bookmark.acquisitionUrl
        val itemId = database.libraryDao().itemIdByClaim(
            namespace = RoomLibraryRepository.DOWNLOAD_PROVENANCE_NAMESPACE,
            claimKey = RoomLibraryRepository.DOWNLOAD_OWNERSHIP_CLAIM,
            claimValue = downloadOwnershipKey(seriesIdentity),
        )
        val chapter = itemId?.let { repository.chapterShelfRowsForItem(it) }
            ?.firstOrNull { it.sourceChapterId == row.chapterIdentity }
        val chapterFacts = serializedChapterCloudFactsOrNull(chapter) ?: return null
        val title = bookmark.title
        val detailSnapshot = itemId?.let { repository.seriesDetailSnapshot(SeriesIdentityV2(provider, canonical)) }
        val sourceIdentity = SeriesIdentityV2(provider, canonical)
        val coverFile = serializedSourceCoverFiles.file(sourceIdentity)
            .takeIf { serializedSourceCoverFiles.uriIfValid(sourceIdentity) != null }
        val thumbs = fileThumbnailSupplier(pageFiles, ::decodeCoverThumbnail)
        val series = CloudSeriesManifest(
            seriesCloudId = seriesCloudId,
            providerId = provider,
            providerSeriesId = canonical,
            seriesAcquisitionRef = seriesAcquisition,
            title = title,
            coverObjectKey = null,
            generation = 0,
            chapters = emptyList(),
            detailSnapshot = detailSnapshot,
        )
        val chapterMeta = CloudChapterMeta(
            seriesCloudId = seriesCloudId,
            providerId = provider,
            providerSeriesId = canonical,
            seriesAcquisitionRef = seriesAcquisition,
            providerChapterId = row.chapterIdentity,
            chapterKey = chapterKey,
            label = chapterFacts.label,
            group = chapterFacts.group,
            readingOrder = chapterFacts.readingOrder,
            sourceState = chapterFacts.sourceState,
            removedAtEpochMs = null,
            sourceDiagnostic = null,
            pageCount = pageFiles.size,
            coverObjectKey = null,
            collectedAtEpochMs = System.currentTimeMillis(),
        )
        return CloudSeriesChapterUploadRequest(
            series = series,
            chapter = chapterMeta,
            pageFiles = pageFiles,
            thumbSupplier = thumbs,
            checkpointWorkDir = checkpointWorkDir,
            seriesCoverFile = coverFile,
            syncedChapterKeys = syncedChapterKeys,
        )
    }

    /**
     * #E80: the durable upload workdir root under `cache/`. The ingest runner
     * writes it and the orphan sweep ([sweepOrphanedCloudIngestWorkDirs])
     * prunes it — one shared spelling, so the sweep can never end up reading a
     * different tree than the one the runner fills.
     */
    private val cloudIngestWorkRoot by lazy { File(context.cacheDir, "cloud-ingest") }

    /**
     * Runs one [CloudSyncQueue] row to completion: UI-free, so a future
     * WorkManager pump (Task 4) can call it with the app closed. Today's
     * compose `collectBookmark` coroutine already delegates here — behavior
     * is unchanged from the previous inline implementation.
     */
    val cloudIngestRunner by lazy {
        CloudIngestRunner(
            queue = cloudSyncQueue,
            gate = cloudSyncIngestGate,
            resolveStore = ::resolveGatedObjectStore,
            // T3a (owner bug A3/C5): a persisted binding is exactly what
            // currentBucketFingerprintOrNull() reports — non-null means this
            // device has bound a bucket, independent of whether
            // resolveGatedObjectStore() can resolve a live store THIS
            // attempt (that call does a live OAuth-refresh network round
            // trip and can transiently fail right after app reopen).
            hasBinding = { currentBucketFingerprintOrNull() != null },
            // C37-g: a revoked/spent OAuth grant is terminal — no amount of
            // WorkManager backoff fixes it, only a user Reconnect.
            isBindingDead = {
                // #C50: a revoked OneDrive / Google Drive sign-in is as terminal as a revoked Cloudflare grant.
                DeadGrant.any(
                    cloudflareDead = isOauthBindingDead(),
                    oneDrive = (bridgeDeviceOneDriveStore.load() != null) to (oneDriveTokenSource?.isDead() == true),
                    googleDrive = (bridgeDeviceGoogleDriveStore.load() != null) to (googleDriveTokenSource?.isDead() == true),
                )
            },
            // A28 (owner bug backlog, ledger 2026-09-04; coordinator
            // review 2026-09-05 M1): called TWICE by CloudIngestRunner.run --
            // once BEFORE resolveStore() to capture the fingerprint an
            // upload actually started against, and again at completion --
            // so onSynced below can detect a mid-upload rebind either way.
            currentBucketFingerprint = ::currentBucketFingerprintOrNull,
            cloudComicIdFor = cloudComicIdStore::getOrCreate,
            frontClientFactory = { EhentaiClient.fromAccount(ehentaiAccountStore.read()) },
            localPageFiles = ::localCatalogPagesForCloudIngest,
            localPageFilesForIdentity = ::localCatalogPagesForCloudIngestIdentity,
            workRoot = cloudIngestWorkRoot,
            serializedCloudIdFor = ::serializedCloudIdFor,
            serializedMirrorReady = { series -> serializedMirrorResolved(series) },
            serializedUploadRequestFor = ::serializedUploadRequestFor,
            // #E21 tail 2: the keys resolve from the manifest the attempt
            // already decoded — no per-chapter series.json GET here.
            syncedChapterKeysFor = { series, seriesManifest ->
                val fp = currentBucketFingerprintOrNull()
                if (fp == null) {
                    emptyMap()
                } else {
                    val key = serializedSeriesLedgerKey(series)
                    val seriesCloudId = database.cloudSyncLedgerDao().cloudComicIdForSeries(fp, key)
                    val objectStore = resolveGatedObjectStore()
                    if (seriesCloudId == null || objectStore == null) {
                        emptyMap()
                    } else {
                        val seriesStore = CloudCollectionStore(objectStore)
                        cloudSyncLedgerStore.syncedChapterIds(fp, key).associateWith { chapterId ->
                            seriesStore.chapterKeyFor(seriesCloudId, chapterId, seriesManifest)
                        }
                    }
                }
            },
            onSerializedSynced = { request, upload, _, generations, uploadedToFingerprint ->
                val fp = confirmedUploadFingerprint(uploadedToFingerprint, currentBucketFingerprintOrNull())
                if (fp != null) {
                    // F3 (b) (spec §3.3): the mirror's seriesSnapshotJson gets
                    // this phone's Room series text. Identity from the
                    // REQUEST's series fields — the SOURCE ids (#E97 class);
                    // never a cloud-collection id.
                    val seriesSnapshot = runCatching {
                        request.series.providerSeriesId?.trim()?.takeIf { it.isNotEmpty() }?.let { canonical ->
                            repository.seriesDetailSnapshot(
                                SeriesIdentityV2(request.series.providerId, canonical),
                            )
                        }
                    }.getOrNull()
                    recordSerializedChapterSynced(
                        ledger = cloudSyncLedgerStore,
                        mirror = cloudMirrorStore,
                        bucketFingerprint = fp,
                        request = request,
                        upload = upload,
                        newGeneration = generations?.newGeneration,
                        nowMs = System.currentTimeMillis(),
                        seriesSnapshot = seriesSnapshot,
                    ) {
                        val seriesIdentity = GalleryIdentityV1(
                            request.series.providerId,
                            request.series.providerSeriesId ?: "",
                        )
                        val job = downloadJobStore.jobFor(
                            seriesIdentity,
                            request.chapter.providerChapterId,
                        )
                        if (job != null) {
                            downloadJobStore.releaseLocalPagesAfterCloud(
                                jobId = job.id,
                                releaseAfterUpload = cloudReleaseAfterUploadStore.read(),
                            ) { identity, revision ->
                                deleteCatalogDownloadRevision(
                                    filesDir = appFilesDir,
                                    identity = identity,
                                    downloadRevision = revision,
                                )
                            }
                        }
                    }
                }
            },
            isSealedDownloadJob = ::isDownloadJobSealedForCloudIngest,
            isSealedDownloadJobForIdentity = ::isDownloadJobSealedForCloudIngestIdentity,
            retainSealedPages = ::retainSealedStagingForIngest,
            retainSerializedSealedPages = ::retainSerializedSealedStagingForIngest,
            truncatedFetchedPagesFor = ::truncatedFetchedPagesForCloudIngest,
            truncatedFetchedPagesForIdentity = ::truncatedFetchedPagesForCloudIngestIdentity,
            // #C30: the enqueue already recorded this comic's catalog facts in
            // the locator; without this the sealed-local upload wrote
            // meta.json with empty tags/artists/groups/category/language.
            catalogFactsForIdentity = { identity -> downloadLocatorStore.get(identity) },
            r2SourceLocalPages = ::r2SourceLocalPagesForIngest,
            // S3 Task 6 (owner bug 2026-09-04): defense-in-depth alongside
            // the pump's own presence guard below — see
            // isKnownSyncedToCurrentBucket's doc.
            isKnownSynced = ::isKnownSyncedToCurrentBucket,
            // #E30: same chapter ledger read as isJobSyncedToBucket's serialized arm,
            // so a pickup cannot disagree with the release sweep.
            isSerializedChapterSynced = { provider, canonical, chapter ->
                val fp = currentBucketFingerprintOrNull()
                if (fp == null) {
                    false
                } else {
                    isSerializedChapterSyncedToBucket(provider, canonical, chapter, fp) { bucket, key, id ->
                        cloudSyncLedgerStore.isSynced(bucket, key, id)
                    }
                }
            },
            // #C66: the bound bucket's own live index, consulted only while
            // isKnownSynced said false (the rebind gap this closes) — see
            // cloudBoundIndexGate's KDoc for the hydration gate and the
            // per-pump-pass memo.
            presentInCurrentIndex = cloudBoundIndexGate::matchingCloudComicId,
            // #C66: records the row's acquisition as synced to the CURRENTLY
            // bound bucket the same way onSynced below does — same
            // galleryDedupKey key, same cloudSyncLedgerStore.markSynced call,
            // same mirror-state generation — so a comic this gate found does
            // not have to be rediscovered by every later pump pass before
            // hydration completes. A blank fp/key (no bucket currently bound,
            // or an unparseable acquisition) no-ops via markSynced itself.
            recordSynced = { acquisition, cloudComicId ->
                val fp = currentBucketFingerprintOrNull()
                val key = galleryDedupKey(acquisition)
                if (fp != null && key != null) {
                    cloudSyncLedgerStore.markSynced(
                        fp = fp,
                        acquisitionKey = key,
                        cloudComicId = cloudComicId,
                        generation = cloudMirrorStore.state(fp)?.generation ?: 0,
                        nowMs = System.currentTimeMillis(),
                    )
                }
            },
            // C33: the per-page thumbnailer is still bounded for `thumbs/`,
            // while the durable cover writer validates and preserves the
            // original page-0 bytes — see this file's
            // decodeCoverThumbnail/validateCoverFile below. Forwarded
            // straight through CloudIngestRunner into the CloudCollectionIngest
            // it constructs internally.
            thumbnailer = { file -> decodeCoverThumbnail(file) },
            coverWriter = { cloudComicId, source -> persistCloudCover(cloudComicId, source) },
            adStripper = trailingAdStripper,
            typesafeApiKey = { typesafeApiKeyStore.read() },
            onSynced = { card, meta, sourceRowAcquisition, indexGenerations, uploadedToFingerprint, providerNamespace, canonicalItemId ->
                // A28 (owner bug backlog, ledger 2026-09-04): resolve the
                // CURRENTLY bound bucket once, at completion time, and
                // compare it against the fingerprint CloudIngestRunner
                // captured when this upload actually STARTED. A rebind
                // mid-upload means every write below (stamp, mirror upsert,
                // ledger, migration markDone, release) would otherwise land
                // against a bucket this upload never reached.
                val currentFingerprint = currentBucketFingerprintOrNull()
                val fp = confirmedUploadFingerprint(uploadedToFingerprint, currentFingerprint)
                if (fp != null) {
                    // A22 §4: stamp the mirror's checkpoint generation BEFORE
                    // the live-card upsert below, so a hydration walk racing
                    // this upload sees the new generation together with
                    // (never ahead of) this comic's own card.
                    if (indexGenerations != null) {
                        val stamped = cloudMirrorStore.stampGeneration(fp, indexGenerations)
                        DiagLog.i(
                            "CloudSync",
                            "stampGeneration fp=$fp newGeneration=${indexGenerations.newGeneration} result=$stamped",
                        )
                    }
                    // Live shelf update (loyal-pipelines Slice B §2): the mirror
                    // gets this comic's card the instant the upload completes,
                    // stamped with the bucket's current generation.
                    // CloudHydrationEngine.hydrateStep publishes a new walk's
                    // generation to the mirror's state row at walk START, not
                    // just after its first chunk lands (loyal-pipelines Slice B
                    // review M2) — so this upsert reads the walk's new
                    // generation and survives that walk's end-of-chunk prune for
                    // the whole walk, not only once chunk 0 has completed.
                    cloudMirrorStore.upsertLiveCard(fp, card, meta)
                    // S4 Task 3: the release below runs ONLY if the ledger row is
                    // durably present — proven by reading it back, because
                    // markSynced silently no-ops on a blank fp/key. A Room failure
                    // here throws past this lambda (pre-existing behavior: the
                    // runner marks the row FAILED and the pump re-drives it) with
                    // ledgerWritten still false.
                    var ledgerWritten = false
                    // Durable per-comic sync ledger (S3 Task 1, owner bug C3/A3):
                    // the moment this comic's upload lands, record it against the
                    // current bucket so a later cold start's transient R2 re-list
                    // failure can never make it look un-synced and re-upload it.
                    // The acquisition key is the token-free canonical dedup key so
                    // it matches the bookmark URL (which may carry an E-Hentai
                    // token) on the read side. meta.acquisitionRef carries the
                    // ORIGINAL source acquisition forward (same field
                    // CloudCollectLifecycle.cardMatches uses), so it — not the
                    // synthetic r2source:// row acquisition — is the durable key.
                    // generation is the bucket's current mirror-state generation
                    // (CloudCollectionCard has no generation of its own); it is
                    // bookkeeping only — isSynced consults presence, not it.
                    val acquisitionKey = galleryDedupKey(
                        meta.acquisitionRef.ifBlank { null }
                            ?: card.acquisitionRef
                            ?: sourceRowAcquisition,
                    )
                    if (acquisitionKey != null) {
                        val generation = cloudMirrorStore.state(fp)?.generation ?: 0
                        cloudSyncLedgerStore.markSynced(
                            fp = fp,
                            acquisitionKey = acquisitionKey,
                            cloudComicId = card.cloudComicId,
                            generation = generation,
                            nowMs = System.currentTimeMillis(),
                        )
                        ledgerWritten = cloudSyncLedgerStore.isSynced(fp, acquisitionKey)
                    }
                    // S4 Task 3 ("remote hosting", owner bug C2): last, after every
                    // durable bookkeeping write. Mirror card + meta + the S4-T1
                    // cover under cloud-covers/ (written by CloudCollectionIngest
                    // BEFORE the runner reaches this hook) stay; only
                    // download-ready-pages/ and <ns>-downloads/ for a pure-cloud
                    // job go. Never throws.
                    val releaseOutcome = cloudHostedLocalRelease.afterConfirmedUpload(
                        sourceRowAcquisition = sourceRowAcquisition,
                        ledgerWritten = ledgerWritten,
                        providerNamespace = providerNamespace,
                        canonicalItemId = canonicalItemId,
                        confirmedBucketFingerprint = fp,
                    )
                    DiagLog.i(
                        "CloudSync",
                        "remote-hosting release cloudComicId=${card.cloudComicId} acquisition=${redactAcquisition(sourceRowAcquisition)} outcome=$releaseOutcome",
                    )
                } else {
                    // A28: a rebind happened while this upload was in flight —
                    // skip every write above rather than ledger/stamp/release
                    // against a binding this upload never reached. Local pages
                    // are deliberately left alone (release is one of the
                    // skipped writes); a future reconcile against whichever
                    // bucket ends up bound will re-drive this comic.
                    DiagLog.w(
                        "CloudSync",
                        "onSynced skipped: bucket rebind detected uploadedTo=$uploadedToFingerprint current=$currentFingerprint cloudComicId=${card.cloudComicId} acquisition=${redactAcquisition(sourceRowAcquisition)}",
                    )
                }
            },
        )
    }

    private val opdsDownloadPageAdapter: OpdsDownloadPageAdapter by lazy {
        OpdsDownloadPageAdapter(
            loadPageFiles = { acquisitionUrl, workDir ->
                OpdsAcquisitionPageBytes.loadToFilesSuspending(
                    acquisitionUrl = acquisitionUrl,
                    authorizationHeader = resolveOpdsAuthorization(acquisitionUrl),
                    workDir = workDir.toFile(),
                )
            },
            workRoot = File(appFilesDir, "opds-download-work").toPath(),
        )
    }

    private val cloudDownloadPageAdapter: CloudCollectionDownloadAdapter by lazy {
        // A43: the shared resolver, not a static S3 working binding — an
        // OAuth-only device has no working binding and used to fail every
        // cloud→device download. resolveGatedObjectStore() applies the
        // encryption gate itself, so no second wrap here: an ungated store
        // reads raw PCLO ciphertext on encrypted libraries and misreports
        // it as a damaged bucket.
        //
        // C10: memoised per bucket fingerprint so all pages of one
        // download share a store (and its pages.manifest.json memo)
        // instead of paying GET manifest + GET image every page. The memo
        // drops on rebind/sign-out — see memoizedCloudCollectionStore.
        CloudCollectionDownloadAdapter(
            memoizedCloudCollectionStore(
                // #C64: fingerprint AND gate generation — see bindingMemoKey.
                fingerprint = { bindingMemoKey(currentBucketFingerprintOrNull(), cloudLibraryGate.generation()) },
                resolveStore = ::resolveGatedObjectStore,
            ),
        )
    }

    private val indexDownloadPageAdapter: IndexDownloadPageAdapter by lazy {
        IndexDownloadPageAdapter(
            // #C10: same reasoning as the front adapter above — the
            // worker's per-source gate is the only pacer for the download
            // path. nhentai keeps its own 3 s / 3-wide policy there.
            client = {
                NhentaiClient.fromCookie(
                    store = nhentaiCookieStore,
                    beforeRequest = {},
                )
            },
            // #E19 Task 5 (controller review 1 item 3): the page-image wiring
            // lives in FetchTransportMapping.indexPageImageGet, which lets
            // NhentaiSlowNodeException and the NHENTAI first-byte stall reach
            // the adapter typed — the i1–i4 host failover keys on those exact
            // shapes and would never fire on the device if they were
            // laundered into ResumablePageTransferException here.
            imageGet = indexPageImageGet(cookie = { nhentaiCookieStore.read() }),
        )
    }

    private val frontDownloadPageAdapter: FrontDownloadPageAdapter by lazy {
        FrontDownloadPageAdapter(
            client = {
                val account = ehentaiAccountStore.read()
                // #C38/#C39 (2026-09-15): explicit empty beforeRequest so the
                // RESOLUTION path (page-view GET + `showpage` POST) is paced
                // ONLY by SourcePacingGate, same as the image path below.
                // fromAccount's default is
                // { CatalogRequestGate.Shared.acquire() } — a process-wide
                // 750 ms monitor. #C10 removed that second limiter from the
                // image bytes but the download client kept inheriting it on
                // every resolution request, re-serialising the worker's
                // window process-wide and making the concurrency setting
                // inert on e-hentai (C40 §B21). Interactive browse/list/import
                // callers keep CatalogRequestGate on purpose; only the worker
                // download client drops it.
                EhentaiClient.fromAccount(account, beforeRequest = {})
            },
            // SEND_BACK item 4 (owner bug backlog, AppGraph scope
            // extension): page-IMAGE bytes go through
            // defaultGetWithStallWatchdog via frontPageImageGet, not the
            // plain defaultGet every other e-h caller (ehentaiImporter
            // above, gallery/list/page-view HTML inside EhentaiClient)
            // keeps — a dead/near-dead H@H node is exactly the "keeps
            // the socket alive with a trickle" case the watchdog exists
            // for. frontPageImageGet is extracted into feature/download
            // (pure) specifically so this wiring choice has JVM
            // coverage — see FetchTransportMappingTest.
            // #C10 (owner 2026-09-10, "让读/下载/上传都走物理极限"): NO
            // beforeRequest here. Every page fetch already runs inside
            // [SourcePacingGate.paced] (DownloadWorkerLoop:980), a real
            // per-source limiter: a Semaphore sized
            // maxConcurrentFetches plus a mutex-guarded start-slot
            // timestamp shared by every gallery of that source. Adding
            // CatalogRequestGate.Shared on top put a SECOND limiter in
            // series — process-wide, 750 ms, and sleeping while holding a
            // `synchronized` lock — which collapsed the worker's parallel
            // window back to one page at a time for EVERY source. That is
            // what made downloads far slower than streaming, made the
            // parallel-download setting look inert, and throttled cloud
            // page fetches whose own policy is explicitly 0 ms / 6-wide.
            // e-h is bounded by SourceThrottlePolicy.EHENTAI alone: EhViewer
            // parity since #C48 — 3 in flight, no spacing.
            imageGet = frontPageImageGet(
                cookie = { ehentaiAccountStore.read().normalizedCookie() },
            ),
        )
    }

    /**
     * Page-bytes router for worker + reader upstream fetch: cloudSource, then the cloud collection, then the
     * identity's source (#E2 Slice 2).
     */
    val downloadPageAdapter: DownloadPageBytesAdapter by lazy {
        RegistryDownloadPageAdapter(
            registry = comicSourceRegistry,
            cloud = cloudDownloadPageAdapter,
            // #C61: the old-bucket Migrate that produced r2source jobs is gone,
            // so there is never an old bucket to read; an r2source row (device
            // probe fixture or leftover) fails honestly here instead of falling
            // through to the current bucket under a foreign id.
            cloudSource = R2SourceDownloadAdapter { null },
            opdsFallback = opdsDownloadPageAdapter,
        )
    }

    /**
     * C14: the authoritative liveness oracle wired into the download loop's
     * GONE seal boundary. Uses the ANONYMOUS public gdata endpoint (works for
     * ExHentai gids too, no cookie — see the C14 evidence), so it never depends
     * on the bound account and cannot leak a cookie. A veto only — see
     * [DownloadWorkerLoop.vetoGoneIfAlive].
     */
    private val sourceLivenessOracle: SourceLivenessOracle by lazy {
        SourceLivenessOracle(
            ehApiEndpoint = EhentaiClient.DEFAULT_API_ENDPOINT,
            ehPost = { url, body, contentType -> EhentaiClient.defaultPost(url, body, contentType) },
            nhApiOrigin = NhentaiClient.DEFAULT_API_ORIGIN,
            nhGet = { url -> NhentaiClient.defaultGet(url) },
        )
    }

    private val opdsExistenceCheck: OpdsExistenceCheck by lazy {
        OpdsExistenceCheck(httpGet = OpdsHttpGet { request -> OpdsCatalogClient.defaultGet(request) })
    }

    /**
     * #E2 Slice 2: Browse's per-source filter map lives in Compose state and leads its store (Apply persists
     * asynchronously), so the UI installs a reader from a SideEffect each composition; until then Browse sees
     * default filters, which is what the UI's own first frame shows.
     */
    class BrowseUiInputs(
        val filterStateFor: (String) -> SourceFilterState,
        val jmSearchScopeFor: (String) -> com.easemic.app.feature.browse.JmSearchScope? = { null },
    ) {
        companion object {
            val NONE = BrowseUiInputs(filterStateFor = { SourceFilterState() })
        }
    }

    @Volatile
    var browseUiInputs: BrowseUiInputs = BrowseUiInputs.NONE

    val comicSourceRegistry: ComicSourceRegistry by lazy {
        sourceRegistryOverride ?: buildComicSourceRegistry()
    }

    val sourceDetailRegistry: com.easemic.app.feature.source.SourceDetailRegistry by lazy {
        com.easemic.app.feature.source.SourceDetailRegistry(comicSourceRegistry)
    }

    /** The saved 再漫画 taxonomy is graph-owned so production and override graphs use the same reader. */
    internal val zaimanhuaGenres by lazy {
        com.easemic.app.feature.source.zaimanhua.ZaimanhuaGenres(
            prefs = SharedPreferencesMap.from(appContext),
            fetch = {
                val source = comicSourceRegistry.source(GalleryIdentityV1.ZAIMANHUA)
                    as? ZaimanhuaSource
                    ?: error("No genre source")
                source.genres()
            },
        )
    }

    // Independent of the Browse switch: follows, saved and cloud identities always resolve this instance.
    private val jmRequestGate by lazy { com.easemic.app.feature.source.jm.JmRequestGate() }
    internal val jmDomains: com.easemic.app.feature.source.jm.JmDomains by lazy {
        fun publicationFrom(url: String): suspend () -> String = {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", com.easemic.app.feature.source.jm.JM_USER_AGENT)
                    .build()
                jmRequestGate.wire { jmApi.read(jmHttpClient.newCall(request)) { response ->
                    check(response.isSuccessful)
                    val body = checkNotNull(response.body)
                    check(body.contentLength() <= 64 * 1024)
                    val output = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= 64 * 1024)
                            output.write(buffer, 0, count)
                        }
                    }
                    output.toString(Charsets.UTF_8.name())
                } }
            }
        }
        com.easemic.app.feature.source.jm.JmDomains(
            storage = com.easemic.app.feature.source.jm.JmPreferenceDomainStorage(SharedPreferencesMap.from(appContext)),
            publication = publicationFrom("https://rup4a04-c01.tos-ap-southeast-1.bytepluses.com/newsvr-2025.txt"),
            fallbackPublications = listOf(publicationFrom("https://rup4a04-c02.tos-cn-hongkong.bytepluses.com/newsvr-2025.txt")),
        )
    }
    private val jmHttpClient by lazy {
        SourceHttp.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }
    private val jmApi: com.easemic.app.feature.source.jm.JmApi by lazy {
        com.easemic.app.feature.source.jm.JmApi(
            client = jmHttpClient, domains = jmDomains, gate = jmRequestGate,
        )
    }
    internal val jmSource by lazy {
        com.easemic.app.feature.source.jm.JmSource(
            api = jmApi,
            pages = com.easemic.app.feature.source.jm.JmPages(jmApi),
            filterStateFor = { sourceId -> browseUiInputs.filterStateFor(sourceId) },
            searchScopeFor = { query -> browseUiInputs.jmSearchScopeFor(query) },
            enabled = { jmEnableStore.isEnabled() },
        )
    }
    internal val jmCoverInterceptor by lazy {
        com.easemic.app.feature.source.jm.JmCoverLimiter(jmDomains).interceptor
    }

    private fun buildComicSourceRegistry(): ComicSourceRegistry {
        return AppGraphComicSourceRegistry(
            builtIns = listOf(
                CloudCollectionComicSource(pages = cloudDownloadPageAdapter),
                NhentaiComicSource(
                    browseSource = NhentaiBrowseSource(
                        enabled = { nhentaiEnableStore.isEnabled() },
                        // Browse's own client: cookie-less, paced by the interactive catalog gate (not fromCookie).
                        client = NhentaiClient(beforeRequest = { CatalogRequestGate.Shared.acquire() }),
                        filterStateFor = { sourceId -> browseUiInputs.filterStateFor(sourceId) },
                    ),
                    pages = indexDownloadPageAdapter,
                    livenessOracle = sourceLivenessOracle,
                ),
                EhentaiComicSource(
                    browseSource = EhentaiBrowseSource(
                        enabled = { ehentaiEnableStore.isEnabled() },
                        client = { EhentaiClient.fromAccount(ehentaiAccountStore.read()) },
                        filterStateFor = { sourceId -> browseUiInputs.filterStateFor(sourceId) },
                    ),
                    pages = frontDownloadPageAdapter,
                    livenessOracle = sourceLivenessOracle,
                ),
                ExhentaiComicSource(
                    browseSource = ExhentaiBrowseSource(
                        // W7: the exhentai.org origin follows the Granted verdict via
                        // fromAccount (effectiveUseExhentai consults the verdict alone),
                        // and the enable store gates the row on top of it.
                        enabled = { exhentaiEnableStore.isEnabled() },
                        client = { EhentaiClient.fromAccount(ehentaiAccountStore.read()) },
                        filterStateFor = { sourceId -> browseUiInputs.filterStateFor(sourceId) },
                    ),
                    pages = frontDownloadPageAdapter,
                    livenessOracle = sourceLivenessOracle,
                ),
                jmSource,
                ZaimanhuaSource(
                    client = SourceHttp.newBuilder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build(),
                    accountStore = zaimanhuaAccountStore,
                    filterStateFor = { sourceId -> browseUiInputs.filterStateFor(sourceId) },
                ),
            ),
            opdsProviders = { opdsProviderStore.readAll() },
            opdsSources = { providers ->
                val browse = OpdsBrowseSource({ providers }, OpdsCatalogClient())
                providers.map { provider ->
                    OpdsComicSource(
                        provider = provider,
                        pages = opdsDownloadPageAdapter,
                        browseSource = browse,
                        existenceCheck = opdsExistenceCheck,
                        authorization = ::resolveOpdsAuthorization,
                    )
                }
            },
        )
    }

    val downloadWorkerLoop: DownloadWorkerLoop by lazy {
        DownloadWorkerLoop(
            host = RoomDownloadWorkerHost(
                store = downloadJobStore,
                filesDir = appFilesDir,
                // #C70: the same "keep only the cloud copy" setting the
                // release sweep reads (releaseLocalPagesForCloudOnlyJobs
                // above) — a dual-intent job only skips the local fetch when
                // this is ON.
                releaseAfterUpload = { cloudReleaseAfterUploadStore.read() },
                // #C70: the CURRENTLY bound bucket's ledger, never a stale
                // one — same lookup the release sweep uses.
                knownSyncedToCurrentBucket = { job ->
                    isJobSyncedToCurrentBucket(job)
                },
            ),
            readyPageStore = readyPageStore,
            adapter = downloadPageAdapter,
            locators = downloadLocatorStore,
            maxActiveGalleriesSource = { maxConcurrentTransfersStore.read() },
            transferMeter = downloadTransferMeter,
            // E15 Task 3a: every source's page attempts funnel through one
            // seam; record them for the Activity row and the notice.
            progressState = downloadProgressState,
            // C14: confirm a page-body GONE verdict against gdata/nh-v2 before
            // sealing — a veto that only ever PREVENTS a false death.
            livenessOracle = { namespace, url ->
                comicSourceRegistry.source(namespace)?.liveness(url, null)
                    ?: sourceLivenessOracle.check(namespace, url)
            },
            // #C12: an OPDS "gone" has no status code of its own (Kavita 500,
            // Ubooquity 200 + HTML); the catalog is asked, with the provider's
            // own Basic auth resolved from the acquisition URL.
            opdsExistence = { identity, locator ->
                comicSourceRegistry.source(identity.providerNamespace)?.liveness(
                    acquisitionUrl = locator.acquisitionUrl,
                    feedUrl = locator.feedUrl,
                ) ?: opdsExistenceCheck.check(
                    acquisitionUrl = locator.acquisitionUrl,
                    feedUrl = locator.feedUrl,
                    // A removed provider is absent from the registry; retain the
                    // existing resolver and catalog check for that persisted row.
                    authorizationHeader = resolveOpdsAuthorization(locator.acquisitionUrl),
                )
            },
            sourceFailureClassifier = { namespace, statusCode, resolvedUrl, responseBody, errorMessage, responseHeaders, retryContext ->
                comicSourceRegistry.source(namespace)?.classifyFailure(
                    statusCode = statusCode,
                    resolvedUrl = resolvedUrl,
                    responseBody = responseBody,
                    errorMessage = errorMessage,
                    responseHeaders = responseHeaders,
                    retryContext = retryContext,
                )
            },
            // SF-2 (C35 audit): a completed download is NOT a tap. This fires
            // once per finishing job, and a batch finishes in a burst — N
            // REPLACE kicks in a second would cancel the pump N times, and
            // every cancel costs whatever upload was in flight its progress.
            // KEEP collapses the burst into the one drain that is already
            // running (or starts it when none is).
            onCloudHandoff = { job -> enqueueCloudUploadForJob(job, userInitiated = false) },
            onBoundedPassCloudHandoff = { job ->
                enqueueCloudUploadForJob(job, userInitiated = false, kickPump = false)
            },
        )
    }

    /**
     * Fetch→upload handoff (spec §1.1): fired by [downloadWorkerLoop] when a
     * cloud-intent job reaches a terminal completion (SEALED, or FINISH for a
     * dual-intent collect+download — FIX1), and callable directly by the
     * collect-time routing (FIX3) for a job that was already terminal *before*
     * being collected, since such a job never re-enters the pump. Enqueues the
     * [CloudSyncQueue] upload row (keyed by the same acquisition URL the
     * legacy direct-upload path used) and kicks [CloudIngestWorker]. A job
     * whose locator was never stored (a bug — [downloadLocatorStore] should
     * have been populated at collect-enqueue time) or whose acquisition URL is
     * blank is skipped rather than uploading a row with no way to resolve it
     * back to a source. Whole-slice review GAP 2: also skipped when
     * [collectBookmarkStore] no longer has a bookmark for the acquisition —
     * uncollecting mid-fetch cancels a cloud-only job outright, but a
     * dual-intent job (local download still wanted) is left running and must
     * not still upload a comic the user removed from Collected.
     *
     * Slice-C2: an `r2source:*`-namespaced job (old-bucket migration) is
     * never in [collectBookmarkStore] — migration is not a Collect action —
     * so the bookmark gate is unconditionally bypassed for it instead.
     *
     * C35-a: [userInitiated] decides which kick this ends with, and SF-2 (C35
     * audit) made it EXPLICIT at every call site. It used to default to `true`,
     * which quietly made `::enqueueCloudUploadForJob` — a method reference, so
     * no argument is visible at the call site at all — a preempting user kick.
     * That is right for the Collect tap and wrong for everything else: the
     * download pump's completion handoff fires once per finishing job, so a
     * batch REPLACE-cancelled the pump once per completion, each cancel
     * costing an in-flight upload its progress. Only the tap passes `true`.
     *
     * [kickPump] false still upserts the row and skips both kicks. A background
     * check uses that: the row waits for the next foreground, which already
     * drains waiting rows.
     */
    internal suspend fun enqueueCloudUploadForJob(
        job: DownloadJob,
        userInitiated: Boolean,
        kickPump: Boolean = true,
    ) {
        val locator = downloadLocatorStore.get(if (job.isSerializedChapter) job.storageIdentity else job.identity)
        val isR2Source = job.identity.providerNamespace.startsWith(GalleryIdentityV1.R2_SOURCE_PREFIX)
        val isAcquisitionCollected = { acquisitionUrl: String ->
            isR2Source || collectBookmarkStore.containsAcquisition(acquisitionUrl)
        }
        val isSerializedSeriesCollected = { series: GalleryIdentityV1 ->
            collectBookmarkStore.readAll().any { bookmark ->
                isSerializedBookmark(bookmark) && bookmark.providerId == series.providerNamespace &&
                    bookmark.id == series.canonicalItemId
            }
        }
        val skipReason = cloudUploadRowSkipReason(
            job = job,
            locator = locator,
            isAcquisitionCollected = isAcquisitionCollected,
            isSerializedSeriesCollected = isSerializedSeriesCollected,
        )
        if (skipReason != null) {
            // A31 (owner bug backlog): never silent — a cloud-only job whose
            // bookmark is gone used to return here with no trace, letting an
            // auto-revive sweep or manual Retry resurrect it forever.
            DiagLog.i("CloudSync", "handoff skipped: ${skipReason.description} job=${job.id}")
            return
        }
        val row = cloudUploadRowForJob(
            job = job,
            locator = locator,
            isAcquisitionCollected = isAcquisitionCollected,
            isSerializedSeriesCollected = isSerializedSeriesCollected,
        ) ?: return
        // S3 Task 6 (owner bug 2026-09-04 — "很多漫画已经橙云了，但依然在活动页出现、在跑同步"):
        // the durable sync ledger already proves this comic reached the
        // CURRENT bucket — skip re-enqueuing its upload row instead of
        // re-driving a redundant upload. This is the handoff-time half of
        // the bigger root cause; the cold-start repair sweep in [start] is
        // the other half.
        val alreadySynced = if (job.isSerializedChapter) {
            val fp = currentBucketFingerprintOrNull()
            val key = serializedSeriesLedgerKey(
                job.identity.providerNamespace,
                job.identity.canonicalItemId,
            )
            fp != null && cloudSyncLedgerStore.isSynced(fp, key, job.chapterIdentity)
        } else {
            isKnownSyncedToCurrentBucket(row.id)
        }
        if (alreadySynced) {
            DiagLog.i(
                "CloudSync",
                "handoff skipped: already synced fp=${currentBucketFingerprintOrNull()} acquisition=${redactAcquisition(row.id)}",
            )
            return
        }
        cloudSyncQueue.upsert(row)
        if (!kickPump) {
            DiagLog.i("CloudSync", "row waits for the next foreground job=${job.id}")
            return
        }
        // C35-a: the user's own Collect must PREEMPT a pump sitting in
        // backoff — a KEEP kick there is a silent no-op, which is exactly how
        // a tap could go five minutes without anything happening. A repair
        // sweep stays on KEEP: it runs in repairCloudHandoffs's loop, where
        // one drain covers the whole batch and N preempting kicks would
        // cancel each other.
        if (userInitiated) {
            CloudIngestWorker.kickUser(appContext)
        } else {
            CloudIngestWorker.kick(appContext)
        }
    }

    /**
     * A34: backfills then reconciles the durable sync ledger
     * ([cloudSyncLedgerStore]) against [cloudMirrorStore]'s current state for
     * [fp], extracted so it can run from both the cold-start sweep in
     * [start] and [cloudHydrationEngine]'s `onHydrationComplete` hook — a
     * disconnect ([onCloudDisconnected]) wipes the ledger via `invalidateAll`,
     * and re-binding the SAME bucket only re-fills it via
     * [CloudHydrationEngine]'s own re-walk of the mirror, not via a fresh
     * cold start; without this hook the ledger would sit empty until the
     * next process launch even though the mirror is already fully hydrated
     * again (emulator-5556 A27 acceptance: 3 mirror rows, 0 ledger rows,
     * post-rebind).
     *
     * Backfill runs unconditionally (idempotent, ADD-only); reconcile only
     * runs once [RoomCloudMirrorStore]'s state for [fp] is
     * `hydrationComplete` — a mid-walk generation is not yet a safe "keep"
     * boundary (see `CloudSyncLedgerDao.deleteOrphansBelowGeneration` kdoc) —
     * read via a single [cloudMirrorStore] `state(fp)` call here so the gate
     * lives in one place rather than being re-derived by every caller.
     */
    private suspend fun syncLedgerWithMirror(fp: String, generation: Int) {
        val liveCards = cloudMirrorStore.activeCards(fp)
        val mirrorCards = cloudMirrorStore.allCards(fp)
        val chaptersByCard = liveCards
            .filter { it.layoutKind == com.easemic.app.sync.CloudCardLayout.SERIES }
            .associate { card -> card.cloudComicId to cloudMirrorStore.chapterRows(fp, card.cloudComicId) }
        val backfilled = cloudSyncLedgerStore.backfillFromMirror(
            fp = fp,
            liveCards = liveCards,
            generation = generation,
            nowMs = System.currentTimeMillis(),
            chaptersByCard = chaptersByCard,
        )
        val reconciled = if (cloudMirrorStore.state(fp)?.hydrationComplete == true) {
            cloudSyncLedgerStore.reconcileWithMirror(fp, generation, mirrorCards)
        } else {
            0
        }
        if (backfilled > 0 || reconciled > 0) {
            DiagLog.i(
                "CloudSync",
                "cloud ledger sync: backfilled=$backfilled reconciled=$reconciled fp=$fp generation=$generation",
            )
        }
        // #C56: the device's collection is the truth; every ACTIVE card with a
        // source acquisition joins it here, after the ledger pass so the ledger
        // and the collection stay decoupled sources.
        val adopted = cloudCollectionAdopter.adopt(liveCards) { id ->
            cloudMirrorStore.detail(fp, id)?.providerId
        }
        if (adopted > 0) {
            DiagLog.i("CloudSync", "collection adoption: added=$adopted")
            // #C56 send-back R4: was `.value += 1`, a non-atomic get-then-set.
            collectionAdoptionEpoch.update { it + 1 }
        }
        // A44 (owner: "上云之后为什么不删本地"): the backfill above is exactly
        // the moment a previously un-provable uploaded job becomes provable,
        // so sweep right after it — otherwise a job whose inline onSynced
        // release never ran keeps its pages forever.
        releaseLocalPagesForCloudOnlyJobs()
        // #E80: same cadence — prune cloud-ingest work dirs of rows the queue
        // no longer holds.
        sweepOrphanedCloudIngestWorkDirs()
        // #E101: bounded retention over the staging held by terminal-failure
        // rows. Deliberately AFTER the two cloud sweeps and NOT behind their
        // bound-bucket guard — this is local hygiene, and a leak that only
        // grows while nothing is bound must still be bounded.
        runCatching { sweepFailedDownloadStaging() }
        // #E102: one-time re-encode of pre-derivative durable shelf covers.
        // Local hygiene like the sweep above — judges no cloud state, so no
        // binding guard either.
        runCatching { reencodeDurableCloudCovers() }
    }

    /**
     * A44 (owner: "上云之后为什么不删本地" — measured on his phone: 13 SEALED
     * cloud-only jobs, 18 ledger rows, still 344 MB in
     * `files/download-ready-pages` across 9 galleries).
     *
     * [cloudHostedLocalRelease] (→ [RoomDownloadJobStore.releaseLocalPagesAfterCloud])
     * only ever ran INLINE from [cloudIngestRunner]'s `onSynced`. Any upload
     * that completed before that inline release existed, or whose release
     * never ran (process death, crash, error path), was never revisited —
     * [syncLedgerWithMirror] backfills the ledger but releases nothing — so
     * the wasted disk was permanent and grew. This sweep is that missing
     * second chance, and it deliberately reuses the SAME release path rather
     * than a parallel delete: [CloudLocalReleaseSweep] delegates the intent
     * decision to `shouldReleaseLocalPagesAfterUpload` (dual-intent keeps its
     * pages — the user asked for that local copy) and the deletion itself to
     * `releaseLocalPagesAfterCloud` (which re-reads `intentLocal` one final
     * time before touching a file).
     *
     * [isKnownSyncedToCurrentBucket] is resolved per job against the bucket
     * bound RIGHT NOW and is false while nothing is bound: a ledger row
     * proving the pages sit in bucket A must never authorize deleting them
     * while bucket B is bound, since the device copy could be the only one
     * left. Run from [syncLedgerWithMirror] and from [onAppForeground].
     */
    private suspend fun releaseLocalPagesForCloudOnlyJobs() {
        if (currentBucketFingerprintOrNull() == null) return
        CloudLocalReleaseSweep(
            loadJobs = { downloadJobStore.loadAllJobs() },
            knownSyncedToCurrentBucket = { job ->
                isJobSyncedToCurrentBucket(job)
            },
            uploadInFlight = { job ->
                // #C20 audit HIGH-2: the SAME predicate `deleteDownloadedComic`
                // applies. A running upload reads the catalog tree lazily and a
                // FINISH job holds no lease, so releasing under it unlinks the
                // pages mid-PUT.
                downloadLocatorStore.get(if (job.isSerializedChapter) job.storageIdentity else job.identity)?.acquisitionUrl?.let {
                    cloudUploadInFlight(cloudSyncQueue.snapshot(), setOf(it))
                } == true
            },
            release = { job ->
                val acquisition = downloadLocatorStore.get(if (job.isSerializedChapter) job.storageIdentity else job.identity)?.acquisitionUrl
                cloudBindingMutationGate.withCurrentBinding(::currentBucketFingerprintOrNull) { fp ->
                    if (acquisition == null || !isJobSyncedToBucket(job, fp)) {
                        return@withCurrentBinding job
                    }
                    withContext(Dispatchers.IO) {
                        downloadJobStore.releaseLocalPagesAfterCloud(
                            jobId = job.id,
                            releaseAfterUpload = cloudReleaseAfterUploadStore.read(),
                        ) { identity, revision ->
                            deleteCatalogDownloadRevision(
                                filesDir = appFilesDir,
                                identity = identity,
                                downloadRevision = revision,
                            )
                        }
                    }
                } ?: job
            },
            releaseAfterUploadEnabled = { cloudReleaseAfterUploadStore.read() },
            log = { message -> DiagLog.w("CloudSync", message) },
            logInfo = { message -> DiagLog.i("CloudSync", message) },
        ).run()
    }

    /**
     * #E80: deletes `cloud-ingest` work dirs no live queue row maps to —
     * leftovers from app versions whose synced chapters never deleted their own
     * durable upload state (packs/ + checkpoint.json) after landing. Same
     * cadence and same cloud-bound guard as [releaseLocalPagesForCloudOnlyJobs]:
     * while nothing is bound, no row can be judged live vs orphaned and nothing
     * is deleted. Fail closed throughout — [liveCloudIngestWorkIds] returning
     * null (any unresolvable row) makes the sweep a no-op.
     */
    private suspend fun sweepOrphanedCloudIngestWorkDirs() {
        if (currentBucketFingerprintOrNull() == null) return
        val deleted = CloudIngestWorkSweep(
            workRoot = cloudIngestWorkRoot,
            liveIds = ::liveCloudIngestWorkIds,
        ).run()
        if (deleted > 0) {
            DiagLog.i("CloudSync", "cloud-ingest work sweep: deleted=$deleted")
        }
    }

    /**
     * #E101: bounded retention over the download staging held by terminal-failure rows
     * (FAILED/GONE/NONE). Local hygiene — unlike its two neighbors it needs no bound bucket:
     * nothing here judges cloud state. Fail closed: an unreadable job store evicts nothing.
     */
    private suspend fun sweepFailedDownloadStaging() {
        val evicted = FailedDownloadStagingSweep(
            loadJobs = { downloadJobStore.loadAllJobs() },
            stagingBytes = { job ->
                readyPageStore.stagingDirectory(job.storageIdentity, job.downloadRevision)
                    ?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
            },
            evict = { job -> downloadJobStore.evictTerminalStaging(job.id) },
        ).run()
        if (evicted > 0) DiagLog.i("DownloadPump", "failed staging sweep: evicted=$evicted")
    }

    /**
     * #E102: one-time re-encode of durable shelf covers written before the
     * cover-specific derivative existed (raw first-page copies — multi-MB
     * forever for a hi-res or animated first page). Skips files at or under
     * [REENCODE_SKIP_BYTES] and files whose pixels already fit [COVER_MAX_EDGE]
     * ([isCoverReencodeNeeded] — an already-derived cover is never selected
     * again, so the pass is idempotent by construction). A file that fails to
     * decode or re-encode is left untouched and logged once (also
     * [encodeCloudCoverDerivative]'s own failure log); one bad cover must not
     * strand the rest. Re-publishes through [CloudCoverFiles.copyBytes] — the
     * same atomic move as every other durable-cover write, never an in-place
     * overwrite. Local hygiene: no binding guard, never rethrown per file.
     */
    internal suspend fun reencodeDurableCloudCovers() = withContext(Dispatchers.IO) {
        var reencoded = 0
        for (file in cloudCoversRoot.listFiles().orEmpty().filter { it.isFile }) {
            try {
                val length = file.length()
                if (length <= REENCODE_SKIP_BYTES) continue
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    DiagLog.w(TAG_CLOUD_COVER, "unreadable durable cover left untouched: ${OpdsCatalogClient.redactUrl(file.name)}")
                    continue
                }
                if (!isCoverReencodeNeeded(length, bounds.outWidth, bounds.outHeight)) continue
                val derived = encodeCloudCoverDerivative(file) ?: continue
                if (cloudCoverFiles.copyBytes(file.name, derived, file) != null) reencoded += 1
            } catch (error: Exception) {
                DiagLog.w(TAG_CLOUD_COVER, "durable cover re-encode failed, left untouched: ${OpdsCatalogClient.redactUrl(file.name)}", error)
            }
        }
        if (reencoded > 0) DiagLog.i(TAG_CLOUD_COVER, "durable cover re-encode: reencoded=$reencoded")
    }

    /** Runs the explicit C4 catch-up after the user confirms clearing old local copies. */
    suspend fun releasePreviouslyUploadedLocalCopies(): CloudReleaseCatchUpSweepSummary {
        if (currentBucketFingerprintOrNull() == null) {
            return CloudReleaseCatchUpSweepSummary(0, 0, 0, 0)
        }
        return CloudReleaseCatchUpSweep(
            loadJobs = { downloadJobStore.loadAllJobs() },
            knownSyncedToCurrentBucket = { job ->
                isJobSyncedToCurrentBucket(job)
            },
            uploadInFlight = { job ->
                // #C20 audit HIGH-2: the SAME predicate `deleteDownloadedComic`
                // applies. A running upload reads the catalog tree lazily and a
                // FINISH job holds no lease, so releasing under it unlinks the
                // pages mid-PUT.
                downloadLocatorStore.get(if (job.isSerializedChapter) job.storageIdentity else job.identity)?.acquisitionUrl?.let {
                    cloudUploadInFlight(cloudSyncQueue.snapshot(), setOf(it))
                } == true
            },
            release = { job ->
                val acquisition = downloadLocatorStore.get(if (job.isSerializedChapter) job.storageIdentity else job.identity)?.acquisitionUrl
                cloudBindingMutationGate.withCurrentBinding(::currentBucketFingerprintOrNull) { fp ->
                    if (acquisition == null || !isJobSyncedToBucket(job, fp)) {
                        return@withCurrentBinding job
                    }
                    withContext(Dispatchers.IO) {
                        downloadJobStore.releaseLocalPagesAfterCloud(
                            job.id,
                            releaseAfterUpload = true,
                        ) { identity, revision ->
                            deleteCatalogDownloadRevision(
                                filesDir = appFilesDir,
                                identity = identity,
                                downloadRevision = revision,
                            )
                        }
                    }
                } ?: job
            },
            log = { message -> DiagLog.w("CloudSync", message) },
            logInfo = { message -> DiagLog.i("CloudSync", message) },
        ).run()
    }

    /**
     * S3 Task 6 (owner bug 2026-09-04) cold-start repair sweep, extracted so
     * it can also be re-run by [cloudHydrationEngine]'s `onPruned` hook
     * (A24-1) — a hydration walk's own prune can newly un-ledger a comic's
     * terminal cloud-intent job mid-session, and that job needs the SAME
     * re-derivation this cold-start pass performs, not just at next launch.
     * Re-derives an upload row for every terminal cloud-intent job the
     * durable sync ledger does NOT already prove synced to the current
     * bucket — a terminal job never re-enters the consumption queue on its
     * own, so nothing else re-derives that row once it is missing.
     */
    private suspend fun repairCloudHandoffs() {
        // A27 (owner bug backlog, ledger 2026-09-05): nothing to repair
        // while no bucket is bound — enqueueCloudUploadForJob would only
        // create rows CloudIngestRunner.run immediately parks as Unbound.
        // The repair sweep re-runs from onBucketBound once a bucket exists.
        if (currentBucketFingerprintOrNull() == null) {
            DiagLog.i("CloudSync", "handoff repair deferred: no cloud storage bound")
            return
        }
        val acquisitionFor = { identity: GalleryIdentityV1 -> downloadLocatorStore.get(identity)?.acquisitionUrl }
        val allJobs = downloadJobStore.loadAllJobs()
        val galleryJobs = allJobs.filterNot { it.isSerializedChapter }
        // Legacy queue rows predate persisted opaque-provider identity. Repair
        // them from an exact local locator↔job match; this is migration, not
        // URL parsing. Ambiguous or orphaned rows stay untouched and fail
        // closed rather than guessing a namespace.
        val repairedRows = backfillCloudQueueIdentities(
            rows = cloudSyncQueue.snapshot(),
            jobs = galleryJobs,
            acquisitionFor = acquisitionFor,
        )
        cloudSyncQueue.backfillIdentities(repairedRows)
        // cloudHandoffRepairCandidates deliberately stays a synchronous,
        // plain-JVM-testable pure decision function (matches its existing
        // test suite) — so the suspend ledger lookup is precomputed into
        // a plain Set here rather than called from inside its knownSynced
        // lambda.
        val syncedJobIds = galleryJobs
            .filter { job -> acquisitionFor(job.identity)?.let { isKnownSyncedToCurrentBucket(it) } == true }
            .map { it.id }
            .toSet()
        val candidates = cloudHandoffRepairCandidates(
            jobs = galleryJobs,
            hasCloudRow = cloudSyncQueue::contains,
            acquisitionFor = acquisitionFor,
            knownSynced = { job -> job.id in syncedJobIds },
        )
        // A31 (owner bug backlog): a candidate whose Collect bookmark is gone
        // (r2source jobs exempt — same rule as enqueueCloudUploadForJob's
        // isR2Source, migration is not a Collect action) used to be silently
        // re-discovered by enqueueCloudUploadForJob's own bookmark guard on
        // EVERY launch forever, instead of ever being reconciled — apply the
        // SAME outcome Uncollect itself would have applied (drop the cloud
        // intent for a dual-intent job, discard a cloud-only one) once, here,
        // so a stuck job actually gets fixed instead of just re-found.
        val partition = partitionUncollectedCloudHandoffCandidates(
            candidates = candidates,
            acquisitionFor = acquisitionFor,
            isCollected = { job, acquisition ->
                val isR2Source = job.identity.providerNamespace.startsWith(GalleryIdentityV1.R2_SOURCE_PREFIX)
                isR2Source || collectBookmarkStore.containsAcquisition(acquisition)
            },
        )
        for (job in partition.notCollected) {
            // Controller review HIGH 2: one bad job (e.g. a catalog-tree
            // delete throwing) must not abort the whole sweep and strand
            // every job after it un-repaired until next cold start.
            try {
                val acquisition = acquisitionFor(job.identity)
                val outcome = applyUncollectJobOutcome(
                    job = job,
                    dropCloudIntent = downloadJobStore::dropCloudIntent,
                    cancelActiveJob = downloadJobStore::cancel,
                    discardTerminalJob = { jobId ->
                        downloadJobStore.discardCloudOnlyJob(jobId) { identity, revision ->
                            deleteCatalogDownloadRevision(
                                filesDir = appFilesDir,
                                identity = identity,
                                downloadRevision = revision,
                            )
                        }
                    },
                )
                DiagLog.i(
                    "CloudSync",
                    "handoff dropped: not collected job=${job.id} acquisition=${redactAcquisition(acquisition)} outcome=$outcome",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagLog.w("CloudSync", "handoff drop failed job=${job.id}", error)
            }
        }
        for (job in partition.collected) {
            if (isJobResolvableForDirectCloudHandoff(job)) {
                // C35-a: a background repair sweep, not a user action — KEEP,
                // so a batch of N repaired jobs is one drain rather than N
                // kicks cancelling one another.
                enqueueCloudUploadForJob(job, userInitiated = false)
            }
        }
        val fp = currentBucketFingerprintOrNull()
        val serializedJobs = allJobs.filter { it.isSerializedChapter }
        // knownSynced is a plain function, same as the gallery sweep above.
        // Read the ledger here, then hand the pure candidate filter a Set.
        val serializedSyncedJobIds = buildSet {
            for (job in serializedJobs) {
                val key = serializedSeriesLedgerKey(
                    job.identity.providerNamespace,
                    job.identity.canonicalItemId,
                )
                if (fp != null && cloudSyncLedgerStore.isSynced(fp, key, job.chapterIdentity)) add(job.id)
            }
        }
        val serializedCandidates = cloudHandoffRepairChapterCandidates(
            jobs = serializedJobs,
            hasCloudRow = { acquisition, chapter -> cloudSyncQueue.contains(acquisition, chapter) },
            acquisitionFor = { job -> downloadLocatorStore.get(job.storageIdentity)?.acquisitionUrl },
            knownSynced = { job -> job.id in serializedSyncedJobIds },
        )
        for (job in serializedCandidates) {
            val collected = collectBookmarkStore.readAll().any { bookmark ->
                isSerializedBookmark(bookmark) && bookmark.providerId == job.identity.providerNamespace &&
                    bookmark.id == job.identity.canonicalItemId
            }
            if (collected && isJobResolvableForDirectCloudHandoff(job)) {
                enqueueCloudUploadForJob(job, userInitiated = false)
            }
        }
    }

    /**
     * Bounded auto-revive sweep (S3 Task 4, owner bug A6): a cloud-intent
     * download job stuck at FAILED never re-enters the consumption queue on
     * its own — [downloadJobsToRevive] picks the still-eligible ones and
     * [RoomDownloadJobStore.autoRetry] moves each back to WAIT. Run at
     * cold-start replay and again on every [cloudConnectivityReKick]
     * reconnect, since a reconnect is exactly when a network-caused FAILED
     * job is most likely to succeed if retried. Reaching SEALED/FINISH/GONE
     * again then flows through the normal [DownloadWorkerLoop.onCloudHandoff]
     * runtime path — no change to [com.easemic.app.feature.collect.cloudHandoffRepairCandidates]
     * is needed for a revived job.
     */
    internal suspend fun reviveFailedCloudDownloadJobs(nowMs: Long = System.currentTimeMillis()) {
        val candidates = downloadJobsToRevive(jobs = downloadJobStore.loadAllJobs(), nowMs = nowMs)
        for (job in candidates) {
            downloadJobStore.autoRetry(job.id)
        }
    }

    /** #D16: see [signInRefusedCloudDownloads]; returns how many went back to WAIT. */
    internal suspend fun reviveSignInRefusedCloudDownloads(): Int {
        val jobs = signInRefusedCloudDownloads(downloadJobStore.loadAllJobs())
        jobs.forEach { downloadJobStore.autoRetry(it.id) }
        return jobs.size
    }

    /**
     * Registers a single [android.net.ConnectivityManager.NetworkCallback] for
     * the process lifetime (S3 Task 4 gap A): nothing previously re-kicked the
     * sync pumps when the network reconnected after being offline —
     * WorkManager's `NetworkType.CONNECTED` constraint only gates a worker
     * that was already kicked. Runs [reviveFailedCloudDownloadJobs] before
     * every re-kick so a job stuck at FAILED gets picked back up on the same
     * reconnect.
     */
    val cloudConnectivityReKick: CloudConnectivityReKick by lazy {
        CloudConnectivityReKick(
            context = appContext,
            reviveSweep = { reviveFailedCloudDownloadJobs() },
        )
    }


    /** Basic credentials of the connected OPDS catalog that owns [acquisitionUrl] (strict authority match), or null. */
    fun resolveOpdsAuthorization(acquisitionUrl: String): String? {
        val providers = opdsProviderStore.readAll()
        val match = providers.firstOrNull { provider ->
            // #C12 audit H3: an authority (scheme+host+port) or path-prefix
            // match — never host alone, which leaked Basic credentials across
            // ports on the same host.
            OpdsCatalogClient.opdsAuthorityMatches(acquisitionUrl, provider.catalogUrl)
        }
        return match?.let {
            OpdsCatalogClient.basicAuthorizationHeader(it.username, it.password)
        }
    }

    /**
     * Snapshot-scoped reader resolver: prefers READY for [job]'s identity+revision.
     * Pass null [job] to force upstream-only display URIs.
     *
     * Hydrates trusted Room READY ordinals + on-disk files into [readyPageStore]
     * so process restart does not force a re-fetch (spec §4.5 / acceptance 5, 7).
     */
    suspend fun downloadPageResolver(job: DownloadJob?): DownloadPageResolver {
        val snapshot = job
        val lease = snapshot?.let { readyPageStore.retainReader(it.identity, it.downloadRevision) }
        try {
            if (snapshot != null) {
                downloadJobStore.reclaimStagingForFinishedJob(snapshot.id)
                readyPageStore.hydrateTrusted(
                    snapshot.identity, snapshot.downloadRevision,
                    downloadJobStore.readyOrdinals(snapshot.id, snapshot.downloadRevision),
                )
            }
            return DownloadPageResolver(
                readyPageStore = readyPageStore,
                readerLease = lease,
                findJob = { identity -> snapshot?.takeIf { it.identity == identity } },
            )
        } catch (error: Throwable) {
            lease?.close()
            throw error
        }
    }

    /** In-process domain oracle (JVM tests + future workers); not the UI queue source. */
    val downloadCore: DownloadCore by lazy { DownloadCore() }
    val pageContentOpener = LocalPageContentOpener(
        repository = repository,
        documentAccess = documentAccess,
        grantManager = grantManager,
        archiveAccess = archiveAccess,
    )
    val sourceRepository = RoomSourceRepository(
        database = database,
        grants = grantManager,
        canReadTree = documentAccess::canReadTree,
        canReadDocument = documentAccess::canReadDocument,
    )
    val scanRunRepository = RoomScanRunRepository(database)
    val scanWorkScheduler by lazy {
        WorkManagerScanWorkScheduler(
            WorkManager.getInstance(context.applicationContext),
            scanRunRepository,
        )
    }
    val startupRunRecoveryCoordinator by lazy {
        StartupRunRecoveryCoordinator(
            WorkManager.getInstance(context.applicationContext),
            scanRunRepository,
            sourceRepository,
            RoomFencedScanRunMutations(database),
            scanWorkScheduler,
        )
    }
    private val scanEngine = ScanEngine(
        sources = sourceRepository,
        runReads = object : ScanRunReader {
            override suspend fun run(runId: com.easemic.app.model.ScanRunId) =
                scanRunRepository.run(runId)
            override suspend fun nextSelectedNonTerminalCandidate(
                runId: com.easemic.app.model.ScanRunId,
            ) = scanRunRepository.nextSelectedNonTerminalCandidate(runId)
            override suspend fun persistedProviderDocumentIds(
                runId: com.easemic.app.model.ScanRunId,
                attemptId: com.easemic.app.model.EnumerationAttemptId,
            ) = scanRunRepository.persistedProviderDocumentIds(runId, attemptId)
        },
        runMutations = RoomFencedScanRunMutations(database),
        adapters = SourceAdapterRegistry(
            listOf(
                GenericDirectorySourceAdapter(documentAccess, candidateRecognizer),
                ComicArchiveSourceAdapter(),
            ),
        ),
        recognizer = candidateRecognizer,
        catalog = RoomFencedCatalogMutations(database),
    )

    private fun collectedSeriesRefreshDeps() = CollectedSeriesRefreshWorker.Deps(
        shouldRun = {
            backgroundRefreshEnabledStore.read() &&
                collectBookmarkStore.readAll().any { isSerializedBookmark(it) }
        },
        refresh = { deadline, monotonicClock ->
            collectedSeriesRefreshCoordinator.refreshAllForBackground(deadline, monotonicClock)
        },
        recordMarks = { results ->
            recordNewChapterMarks(results, collectedSeriesNewChapterStore)
        },
        jobIdsFor = { results ->
            val ids = ArrayList<String>()
            for (result in results) {
                val series = GalleryIdentityV1(
                    result.series.providerNamespace,
                    result.series.canonicalSeriesId,
                )
                for (chapterId in result.queuedChapterIds) {
                    downloadJobStore.jobFor(series, chapterId)?.id?.let { ids += it }
                }
            }
            ids
        },
        boundedPass = { jobIds, deadline ->
            downloadWorkerLoop.runBoundedPass(
                jobIds = jobIds,
                deadline = deadline,
                maxFinishedJobs = 20,
                monotonicClock = { android.os.SystemClock.elapsedRealtime() },
            )
        },
        summaryChapters = { results, _ ->
            val rows = database.chapterUpdateDao().selectAll()
            val chapters = ArrayList<SummaryChapter>()
            for (result in results) {
                if (result.decision != RefreshDecision.REFRESHED) continue
                val series = GalleryIdentityV1(
                    result.series.providerNamespace,
                    result.series.canonicalSeriesId,
                )
                val queued = result.queuedChapterIds.toSet()
                for (chapterId in result.newChapterIds) {
                    val row = rows.firstOrNull {
                        it.providerNamespace == result.series.providerNamespace &&
                            it.providerSeriesId == result.series.canonicalSeriesId &&
                            it.providerChapterId == chapterId
                    } ?: continue
                    val membersOnly = chapterId !in queued
                    val job = if (membersOnly) null else downloadJobStore.jobFor(series, chapterId)
                    chapters += SummaryChapter(
                        seriesTitle = row.seriesTitle,
                        chapterLabel = row.chapterLabel,
                        discoveredAtMillis = row.discoveredAtMillis,
                        membersOnly = membersOnly,
                        saved = job?.state == DownloadJobState.FINISH || job?.state == DownloadJobState.SEALED,
                    )
                }
            }
            chapters
        },
        notificationsEnabled = {
            androidx.core.app.NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        },
        postSummary = { summary -> UpdatesNotification.post(appContext, summary) },
        recordFinished = { now, found, saved ->
            backgroundRefreshRunStore.recordFinished(now, found, saved)
        },
        monotonicNow = { android.os.SystemClock.elapsedRealtime() },
        wallNow = { System.currentTimeMillis() },
    )

    val workerFactory = ScanWorkerFactory(
        engine = scanEngine,
        foregroundInfo = ImportForegroundInfo(context.applicationContext),
        recoverForegroundFailure = { runId, requestId ->
            RunRecovery(
                scanRunRepository,
                sourceRepository,
                RoomFencedScanRunMutations(database),
                scanWorkScheduler,
            ).recoverStopped(runId, requestId, com.easemic.app.work.PlatformStopKind.PLATFORM_STOPPED)
        },
        cloudSyncQueue = cloudSyncQueue,
        cloudIngestRunner = cloudIngestRunner,
        pumpForegroundInfo = PumpForegroundInfo(context.applicationContext),
        downloadWorkerLoop = downloadWorkerLoop,
        cloudHydrationEngine = cloudHydrationEngine,
        checkCfChallenge = {
            CfChallengeNotification.updateFor(context.applicationContext, downloadJobStore.loadAllJobs())
        },
        // S3 Task 6 (owner bug 2026-09-04): pump-level presence guard — see
        // isKnownSyncedToCurrentBucket's doc.
        cloudIngestIsKnownSynced = ::isKnownSyncedToCurrentBucket,
        // S6-A (owner setting "max concurrent transfers"): read live so a
        // mid-flight change takes effect on the pump's next run.
        cloudIngestMaxConcurrent = { maxConcurrentTransfersStore.read() },
        repairSeriesIndexes = ::repairSeriesIndexes,
        downloadNotificationText = ::downloadNotificationText,
        cloudNotificationText = ::downloadNotificationText,
        collectedSeriesRefresh = collectedSeriesRefreshDeps(),
    )

    /**
     * Captured at [start] so [notifyBucketBound]/[notifyCloudDisconnected] can
     * fire-and-forget without every UI call site needing its own coroutine
     * scope. Null before [start] runs (there is no legitimate call path for
     * that — both notify functions are reached from UI bind/disconnect
     * actions, which only happen after the app has started).
     */
    private var applicationScope: CoroutineScope? = null

    val backgroundIngestRegistry =
        com.easemic.app.feature.collect.BackgroundIngestRegistry()

    /** Accepted Collect work survives Activity recreation; IO stays in the block's IO context. */
    fun enqueueCloudCollectPreflight(
        row: com.easemic.app.feature.collect.CloudSyncQueueItem,
        onFailure: (Exception) -> Unit,
        block: suspend CoroutineScope.() -> Unit,
    ): kotlinx.coroutines.Job? =
        com.easemic.app.feature.collect.launchCloudCollectPreflight(
            scope = CoroutineScope(checkNotNull(applicationScope).coroutineContext + Dispatchers.Main.immediate),
            queue = cloudSyncQueue,
            row = row,
            onFailure = onFailure,
            block = block,
        )

    fun start(applicationScope: CoroutineScope) {
        this.applicationScope = applicationScope
        // #C19 audit F12 (owner 2026-09-19): the double-prefixed `opds:opds:`
        // namespace. Bookmarks are rewritten SYNCHRONOUSLY (before any
        // catch-up sweep can read them); rows/dirs go on IO. Idempotent, no
        // marker — a no-op after the first start.
        //
        // NEW-5: the rewrite is synchronous, on the main thread, inside
        // Application.onCreate — a throw here (or in the collectBookmarkStore
        // lazy's first prefs load) would stop the app from starting at all.
        runCatching {
            OpdsDoubleNamespaceCleanup.rewriteBookmarks(collectBookmarkStore, remoteReadingProgressStore)
        }.onFailure { DiagLog.w("OpdsCleanup", "bookmark rewrite failed: ${it::class.simpleName}") }
        runCatching { collectedSeriesRefreshScheduler.reconcile() }
            .onFailure { DiagLog.w("SerializedRefresh", "background schedule failed: ${it::class.simpleName}") }
        // r4 LOW #6: the three pump/ingest kicks this method launches below start
        // concurrently with the sweep, so without a handshake a kicked pump can
        // be walking the very rows and staging trees the purge is unlinking.
        // They await this; it is completed in a `finally` so a throw or a
        // cancellation releases them instead of parking them forever.
        val purgeSettled = CompletableDeferred<Unit>()
        applicationScope.launch(Dispatchers.IO) {
            try {
                // NEW-2: this scope has no CoroutineExceptionHandler, and C2's
                // reclaim below must ALWAYS run — belt and braces over purge()'s
                // own internal guard.
                runCatching {
                    OpdsDoubleNamespaceCleanup(
                        loadAllJobs = { downloadJobStore.loadAllJobs() },
                        purgeJob = { job, deleteTree ->
                            purgeDoubledJobFiles(
                                job = job,
                                deleteCatalogTree = deleteTree,
                                filesDir = appFilesDir,
                                readyPageStore = readyPageStore,
                                locatorStore = downloadLocatorStore,
                                removeLibraryAvailability = { repository.removeDownloadLocalAvailability(it) },
                                purgeRow = { downloadJobStore.purgeJob(it) },
                            )
                        },
                        queueRows = { cloudSyncQueue.snapshot() },
                        removeQueueRow = { cloudSyncQueue.remove(it) },
                        uploadInFlight = { job ->
                            downloadLocatorStore.get(if (job.isSerializedChapter) job.storageIdentity else job.identity)?.acquisitionUrl?.let {
                                cloudUploadInFlight(cloudSyncQueue.snapshot(), setOf(it))
                            } == true
                        },
                        filesDir = appFilesDir,
                        locatorRoot = File(appFilesDir, "download-locators"),
                        readyPagesRoot = File(appFilesDir, "download-ready-pages"),
                    ).purge()
                }.onFailure { DiagLog.w("OpdsCleanup", "purge failed: ${it::class.simpleName}") }
                // Re-review r2 BLOCKING-1: legacy (pre-namespace-level) trees are
                // never moved — the Library's page URIs point at them. Once the
                // F12 purge above has dropped doubled rows, whatever legacy tree
                // no job and no Library page row references is an orphan.
                runCatching {
                    reclaimOrphanLegacyCatalogTrees(
                        filesDir = appFilesDir,
                        jobs = downloadJobStore.loadAllJobs(),
                        referencedPageDirectories = repository.catalogDownloadPageDirectories(),
                    ) { DiagLog.i("CatalogTree", it) }
                }.onFailure { DiagLog.w("CatalogTree", "sweep failed: ${it::class.simpleName}") }
                // C2: compensate old duplicates and a process death after FINISH but
                // before unlink. Pure-local downloads need this without any bucket.
                // Runs AFTER the purge so a doubled row is never "reclaimed" first.
                downloadJobStore.reclaimStagingForFinishedJobs()
            } finally {
                purgeSettled.complete(Unit)
            }
        }
        // C22: a process killed while Collect's remote preflight was pending
        // must not leave an undecided placeholder that the cloud pump could
        // mistake for a normal upload. The durable bookmark remains and the
        // catch-up repair below re-drives the decision.
        cloudSyncQueue.clearPreflightPendingRows()
        applicationScope.launch {
            validateThenObserveWork(
                validatePendingGrants = {
                    SourceStartupValidation(
                        validateSources = sourceRepository::validatePendingSources,
                        hasGrant = { uri -> grantManager.hasReadGrant(uri.toUri()) },
                        canReadTree = documentAccess::canReadTree,
                        canReadDocument = documentAccess::canReadDocument,
                    ).run()
                },
                observeWork = startupRunRecoveryCoordinator::observeWorkChanges,
            )
        }
        // Task 10: WorkManager download pump (one active gallery, unchanged) —
        // DownloadPumpWorker ticks downloadWorkerLoop from doWork() instead of
        // an Application-scoped coroutine, so a queued job survives process
        // death instead of sitting inert until the app happens to relaunch.
        // Replay kick: a job can be sitting WAIT/DOWNLOAD from a previous
        // process — nothing else re-enqueues the pump on a cold start, so
        // without this a runnable job would sit inert until the next
        // enqueue/retry action.
        applicationScope.launch {
            // r4 LOW #6: the F12 purge is unlinking rows and staging trees
            // right now; kicking a pump into that is the race the sweep's own
            // row skip cannot cover. Wait, do not reorder — the kick keeps its
            // exact semantics, it just starts after the sweep has settled.
            purgeSettled.await()
            if (downloadJobStore.consumptionQueue().isNotEmpty()) {
                DownloadPumpWorker.kick(appContext)
            } else {
                // spec §1.4 FIX2: consumptionQueue() already SQL-filters
                // resumeNotBeforeMs, so a cold start whose only WAIT/DOWNLOAD
                // jobs are parked in a QUOTA/ban idle window (rather than gone
                // for good) sees an empty queue right above — nothing else
                // re-checks resumeNotBeforeMs once it lapses, so without this
                // the pump would sit inert forever instead of just until the
                // window passes. Schedule a one-time delayed kick at the
                // earliest window instead; if that job re-parks or fails when
                // it runs, its own completion path re-derives the next kick
                // same as it always has.
                val now = System.currentTimeMillis()
                val earliest = earliestResumeAfter(downloadJobStore.loadAllJobs(), now = now)
                if (earliest != null) {
                    DownloadPumpWorker.kickAfter(appContext, delayMs = earliest - now)
                }
            }
        }
        // Replay kick: a row can be sitting WAITING from a previous process
        // (freshly replayed by CloudSyncQueue.attach(), or left behind by a
        // pump that lost its process) — nothing else re-enqueues the pump on
        // a cold start, so without this a WAITING row would sit inert until
        // the next Collect action.
        // C46 Task 8 (device-found): this must be the USER kick, not KEEP. A
        // pump killed mid-run by process death (swipe-kill / force-stop) is
        // re-enqueued by WorkManager with run_attempt_count > 0, and WorkManager
        // then holds that request under its default EXPONENTIAL 30 s backoff;
        // KEEP was a no-op against it, so "kill the app mid-upload and reopen"
        // resumed only once the 30 s expired (measured: relaunch 2 s after the
        // kill → not driven for 23 s; the spec promises ~3 s). The user just
        // opened the app: REPLACE when no pump is RUNNING (cancel the backed-off
        // request, drain now — an interrupted ingest parks its row WAITING with
        // its checkpoint, so the residual race costs a resumable interruption),
        // KEEP when WorkManager already restarted one (chainBehindRunning =
        // false, same shape as the foreground return). Once per process start,
        // so this cannot storm a backoff the way a per-onStart REPLACE could.
        if (cloudSyncQueue.snapshot().any { it.stage == CloudSyncStage.WAITING }) {
            applicationScope.launch {
                // r4 LOW #6: the F12 purge is unlinking rows and staging trees
                // right now; kicking a pump into that is the race the sweep's own
                // row skip cannot cover. Wait, do not reorder — the kick keeps its
                // exact semantics, it just starts after the sweep has settled.
                purgeSettled.await()
                CloudIngestWorker.kickUser(appContext, chainBehindRunning = false)
            }
        }
        // S3 Task 4 gap A6: bounded auto-revive of FAILED cloud-intent jobs at
        // cold start, then a plain pump kick — a revived job is now WAIT, so
        // the pump needs telling regardless of whether reviveFailedCloudDownloadJobs
        // found anything (mirrors the unconditional-kick shape used elsewhere
        // in this file rather than re-deriving consumptionQueue().isNotEmpty()
        // a second time).
        applicationScope.launch {
            // r4 LOW #6: the F12 purge is unlinking rows and staging trees
            // right now; kicking a pump into that is the race the sweep's own
            // row skip cannot cover. Wait, do not reorder — the kick keeps its
            // exact semantics, it just starts after the sweep has settled.
            purgeSettled.await()
            reviveFailedCloudDownloadJobs()
            DownloadPumpWorker.kick(appContext)
        }
        // S3 Task 4 gap A: registers the ConnectivityManager.NetworkCallback
        // once per process so a reconnect after being offline re-kicks both
        // pumps (and re-runs the revive sweep above) — see
        // CloudConnectivityReKick's kdoc for why nothing else covers this.
        cloudConnectivityReKick.register(applicationScope)
        // #E19 Task 6: the family Dns keys its IPv4/IPv6 preference on the
        // default network's handle, so what one network taught never leaks onto
        // the next. Registered once per process beside the only other
        // ConnectivityManager callback; until this runs (early startup) the Dns
        // keeps the delegate's order.
        (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.let { cm ->
            // -1 reads as "no handle": with no active network yet the provider
            // returns null and the default IPv4-first order applies.
            val currentHandle = AtomicLong(cm.activeNetwork?.networkHandle ?: -1L)
            SourceHttp.dns.attachHandleProvider { currentHandle.get().takeIf { it >= 0 } }
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    currentHandle.set(network.networkHandle)
                }

                override fun onLost(network: Network) {
                    SourceHttp.dns.onNetworkLost(network.networkHandle)
                }
            })
        }
        // Handoff repair (spec §1.4 FIX1): a job that reached a terminal
        // cloud-intent completion (SEALED, or FINISH for a dual-intent
        // collect+download) but crashed before its CloudSyncQueue upload row
        // was enqueued would otherwise strand the already-fetched pages — a
        // terminal job never re-enters the consumption queue, so nothing else
        // re-derives that row. enqueueCloudUploadForJob() kicks
        // CloudIngestWorker itself, so no extra kick is needed here.
        //
        // S3 Task 6 (owner bug 2026-09-04 — "很多漫画已经橙云了，但依然在活动页出现、
        // 在跑同步"): this cold-start sweep was the BIGGER of the two root
        // causes — it re-derives an upload row for EVERY terminal cloud-intent
        // job on EVERY launch, entirely bypassing the durable sync ledger
        // (hasCloudRow only checks the transient queue, which is empty again
        // once a past upload's row was removed on success). Fixed two ways:
        // (1) backfillFromMirror runs FIRST so a legacy SEALED/FINISH job that
        // predates the ledger (S3 Task 1) is recognized as already-synced
        // instead of looking un-synced forever; (2) knownSynced excludes any
        // job the ledger already proves synced from the candidate list itself.
        applicationScope.launch {
            val fp = currentBucketFingerprintOrNull()
            if (fp != null) {
                // A24-1 (owner bug sample: job 4169293, 29 local pages, gone
                // from the shelf, never re-uploaded): backfill only ever ADDS
                // rows — it cannot undo a hydration re-walk's own prune (on
                // this device, on a prior launch, before this repair pass
                // ever ran) that evicted a mirror card whose ledger row
                // survived. syncLedgerWithMirror's reconcile half drops that
                // orphan row too, making its comic repair-eligible again
                // below instead of silently skipped forever (see its own
                // kdoc for the hydrationComplete gate).
                syncLedgerWithMirror(fp, cloudMirrorStore.state(fp)?.generation ?: 0)
            }
            try {
                repairCloudHandoffs()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagLog.w("CloudSync", "cold-start handoff repair failed; will retry at next cold start", error)
            }
        }
        // A22 §1: cold-start hydration kick whenever a bucket is bound — the
        // former `hydrationIncomplete` gate meant an already-hydrated device
        // never re-read the manifest and drifted from other devices on the
        // same bucket until a rebind. A generation-equal check is one small
        // GET. C29 audit MEDIUM-2: kickWhenIdle, not kick — a KEEP kick is a
        // no-op against a walk WorkManager is holding in its 30 s post-kill
        // backoff (the #C46 shape), which is exactly the cold start this runs on.
        // `RoomCloudMirrorStore.hydrationIncomplete` now has no production
        // caller — kept intentionally as a test-only helper
        // (RoomCloudMirrorStoreTest x3, androidTest CloudMirrorPersistenceTest).
        applicationScope.launch {
            if (currentBucketFingerprintOrNull() != null) {
                HydrationWorker.kickWhenIdle(appContext)
            }
        }
        // End of AppGraph.start (AppGraphColdStartKickWiringTest slices up to here).
    }

    /**
     * Bucket-bound hook (loyal-pipelines Slice B pump task §2): a bind that
     * resolves to a DIFFERENT bucket than whatever the mirror currently holds
     * must not leave the old bucket's cards visible once hydration starts
     * filling in the new one, so every other bucket's mirror rows are wiped
     * first. A bind that resolves back to the SAME bucket fingerprint is a
     * no-op wipe ([RoomCloudMirrorStore.wipeAllExcept] keeps that
     * fingerprint's own rows) — the [HydrationWorker] kick below then becomes
     * a cheap "already hydrated, nothing to do" generation check rather than
     * a full re-import.
     *
     * Also the unlock hook (loyal-pipelines Slice B review H2): an unlock
     * success calls this too, since it is exactly the other event (besides
     * bind/cold-start) that can turn a Blocked pump back into a runnable one.
     * [HydrationWorker.kickNow] (not [HydrationWorker.kick]) because both
     * bind and unlock are user actions that must preempt any
     * [HydrationWorker.kickAfter] backoff wait already enqueued from a prior
     * run's repeated transient failures (loyal-pipelines Slice B review
     * hygiene).
     */
    suspend fun onBucketBound(resetTokenSource: Boolean = false) {
        // C37-c review (F3) + Task 5 re-review (A1): a (re)bind replaces the
        // grant itself, so the process-wide token source must drop its cached
        // access token AND its dead-grant latch — but [reset] takes the same
        // monitor an in-flight exchange holds for 0.5-8 s, so it can never run
        // on the main thread. It happens HERE, first, because this hook always
        // runs on [applicationScope] (Dispatchers.IO). The UNLOCK caller passes
        // false: it merely entered a password for the SAME grant, and resetting
        // there would throw away a perfectly good access token and force a
        // needless Cloudflare exchange on the next request.
        if (resetTokenSource) oauthTokenSource.reset()
        currentBucketFingerprintOrNull()?.let { fp ->
            cloudMirrorStore.wipeAllExcept(fp)
            // Durable sync ledger rebind hygiene (S3 Task 1): drop every OTHER
            // bucket's ledger while keeping the bound bucket's own — a
            // same-bucket rebind must NOT lose its durable sync knowledge (the
            // whole point of the C3/A3 fix); a different-bucket rebind clears
            // the old bucket's now-irrelevant ledger.
            cloudSyncLedgerStore.invalidateAllExcept(fp)
            // A27 (owner bug backlog, ledger 2026-09-05): revive every row
            // parked/failed with the BIND copy before this bucket existed,
            // then re-run the handoff repair sweep (a no-op while unbound —
            // see above) and wake the pump so the revived + repaired rows
            // actually get driven now — nothing else would wake them (no
            // periodic jobs; an Unbound pump pass returns SUCCESS, not RETRY).
            //
            // C37-g: rows parked FAILED because the previous grant was dead
            // are runnable again now that a fresh grant exists — this is the
            // ONLY thing that revives them (their retry budget is exhausted
            // on purpose, so no pump pass ever re-drives them on its own).
            //
            // #C63: rows parked FAILED because the library was locked are
            // runnable again for the same reason -- unlocking is exactly
            // what reaches this hook (see notifyBucketBound/onLibraryUsable
            // callers). See reviveRowsOnBind's kdoc for the full message set.
            reviveRowsOnBind(cloudSyncQueue, System.currentTimeMillis())
            try {
                repairCloudHandoffs()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagLog.w("CloudSync", "bind handoff repair failed; will retry at next cold start", error)
            }
            CloudIngestWorker.kickNow(appContext)
        }
        HydrationWorker.kickNow(appContext)
    }

    /** Disconnect hook: no bucket is bound anymore, so every mirror row is stale. */
    suspend fun onCloudDisconnected() {
        // C37-c: no grant anymore — forget the access token and the dead latch.
        oauthTokenSource.reset()
        cloudMirrorStore.wipeAll()
        cloudSyncLedgerStore.invalidateAll()
        // A22 §3: the next bind's first foreground must re-check, not coast
        // on a stale timestamp from the bucket that was just disconnected.
        hydrationRecheckStore.clear()
    }

    /**
     * A22 §3 foreground hook, called from `MainActivity.onStart`. Kicks the
     * hydration pump when a bucket is bound and the last manifest check
     * ([hydrationRecheckStore]) is older than `HYDRATION_RECHECK_MIN_INTERVAL_MS`
     * ([shouldRecheckHydration]) so rapid app switching never becomes a kick
     * storm. NOTE: Activity.onStart also fires on the very first launch right
     * after onCreate, so a cold start produces BOTH [start]'s kick and (when
     * lastCheckAtMs is null or > 5 min old) this one — with
     * [HydrationWorker.kickWhenIdle] (C29 audit MEDIUM-2) the second either
     * finds the first RUNNING (KEEP, a no-op) or REPLACEs a request that has
     * not started yet, which still runs the same single walk immediately.
     * That double kick is expected; do not "fix" it by gating onStart on a
     * first-launch flag. The Log line is the emulator-acceptance witness for
     * this trigger.
     */
    /**
     * C35-d: expire 30-day-old trash off every tap path.
     *
     * This used to run inside the Collect preflight — `purgeExpiredTrash`
     * lists the trash, runs a `discardComic` (2N+2 deletes) per expired card
     * and rewrites the trash index, all under `INDEX_MUTATION_LOCK` — so a
     * user with real trash paid for all of it before their tap could move a
     * byte, every single tap. Nothing about it is urgent: the retention window
     * is 30 days ([com.easemic.app.sync.CloudCollectLifecycle.PURGE_AFTER_MS]),
     * so a card expiring a few minutes late costs nobody anything.
     *
     * SF-4 (C35 audit) replaced C35-d's once-per-PROCESS latch with two
     * bounds, because that latch was wrong in both directions:
     *  - a long-lived process expired trash exactly once, ever, no matter how
     *    many cards came due afterwards — so the throttle is now elapsed time
     *    ([CLOUD_TRASH_PURGE_INTERVAL_MS]) and every later foreground is a
     *    fresh chance;
     *  - the one run it did get was UNBOUNDED, so a user with a year of trash
     *    paid every expired card's discard in a single sweep, holding
     *    `INDEX_MUTATION_LOCK` against their own uploads and restores — so one
     *    run now discards at most [CLOUD_TRASH_PURGE_MAX_CARDS] cards.
     * The throttle stamp is also NOT spent on a run that never reached the
     * bucket (no store resolved), or the first foreground after a cold start
     * with a slow binding would silently cost the next half hour.
     *
     * Best-effort by design: no bucket bound, an unreadable library, or a dead
     * grant just logs and leaves the trash for the next sweep. It must never
     * be able to take the foreground hook down with it.
     */
    fun purgeExpiredCloudTrash() {
        val scope = applicationScope ?: return
        if (currentBucketFingerprintOrNull() == null) return
        val startedAtMs = System.currentTimeMillis()
        val previousStamp = lastCloudTrashPurgeAtMs.get()
        if (startedAtMs - previousStamp < CLOUD_TRASH_PURGE_INTERVAL_MS) return
        // CAS, not set(): two foregrounds racing must produce ONE sweep.
        if (!lastCloudTrashPurgeAtMs.compareAndSet(previousStamp, startedAtMs)) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val store = resolveGatedObjectStore()
                if (store == null) {
                    // Never reached the bucket: hand the window back so the
                    // next foreground can try, instead of burning 30 minutes.
                    lastCloudTrashPurgeAtMs.compareAndSet(startedAtMs, previousStamp)
                    return@runCatching
                }
                CloudCollectionStore(store).purgeExpiredTrash(
                    nowEpochMs = System.currentTimeMillis(),
                    maxCards = CLOUD_TRASH_PURGE_MAX_CARDS,
                )
            }.onFailure { error -> DiagLog.w("CloudSync", "expired trash sweep failed", error) }
        }
    }

    fun onAppForeground(nowMs: Long = System.currentTimeMillis()) {
        runCatching { collectedSeriesRefreshScheduler.reconcile() }
        // C34-a (audit S2): warm the lazy budget on the FIRST FOREGROUND — a
        // process that can open a reader — instead of in start(), which also
        // runs on WorkManager-only wakes where nothing reads a cache root and
        // the walk is pure cost. Once per process; the reader's IO block pays
        // at most a single-walk-per-root init if it races this (N7: a failed
        // walk must not take the foreground hook down with it). The latch is
        // only spent once a scope actually exists, so a foreground that
        // somehow precedes start() does not lose the warm-up.
        val warmScope = applicationScope
        if (warmScope != null && cacheBudgetWarmed.compareAndSet(false, true)) {
            warmScope.launch(Dispatchers.IO) { runCatching { cacheBudget } }
        }
        val refreshScope = applicationScope
        if (refreshScope != null && collectedSeriesRefreshInFlight.compareAndSet(false, true)) {
            refreshScope.launch(Dispatchers.IO) {
                try {
                    val passStartedAt = System.currentTimeMillis()
                    val results = collectedSeriesRefreshCoordinator.refreshAllOnForeground()
                    results.forEach { result ->
                        serializedSourceCoverFiles.uriIfValid(result.series)?.let { uri ->
                            publishSerializedCloudSourceCoverIfPresent(result.series, uri)
                        }
                    }
                    recordNewChapterMarks(
                        results,
                        collectedSeriesNewChapterStore,
                        observedAtMs = passStartedAt,
                    )
                    if (results.any { it.queuedChapterIds.isNotEmpty() }) {
                        kickSerializedDownloads()
                    }
                    if (results.any { it.decision == RefreshDecision.FAILED }) {
                        DiagLog.w("SerializedRefresh", "foreground refresh failed")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep foreground maintenance alive and do not expose source URLs from
                    // an exception message in diagnostics.
                    DiagLog.w("SerializedRefresh", "foreground refresh failed")
                } finally {
                    collectedSeriesRefreshInFlight.set(false)
                }
            }
        }
        val coverScope = applicationScope
        if (coverScope != null && serializedSourceCoverBackfillInFlight.compareAndSet(false, true)) {
            coverScope.launch(Dispatchers.IO) {
                try {
                    backfillSerializedSourceCovers()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    DiagLog.w("SerializedCover", "backfill failed")
                } finally {
                    serializedSourceCoverBackfillInFlight.set(false)
                }
            }
        }
        // A30 §2.2 (S4b/B3): Android can take a folder's persisted grant away while the app
        // is alive (Settings → Storage & cache → Clear access). Nothing used to notice until
        // the next cold start, so every Folders row kept claiming "N comics · scanned N min
        // ago". Re-check the persisted grants on every foreground; it is a permission-list
        // read, no tree I/O, and it never touches location state.
        applicationScope?.launch {
            runCatching { sourceRepository.revalidateReadyGrants() }
        }
        // A44 (owner: "上云之后为什么不删本地"): reclaim the device pages of
        // already-uploaded cloud-only jobs. Deliberately ABOVE the hydration
        // recheck throttle below — that throttle exists to stop kick storms
        // against the network, while this is a local ledger read, and gating
        // it the same way would leave a leak accumulated before this fix
        // shipped waiting on an unrelated 5-minute window. Its own bucket and
        // ledger gates make it a cheap no-op when there is nothing to do.
        applicationScope?.launch {
            runCatching { releaseLocalPagesForCloudOnlyJobs() }
                .onFailure { error -> DiagLog.w("CloudSync", "foreground release sweep failed", error) }
        }
        // #E80: same cadence and cloud-bound guard — orphaned cloud-ingest work
        // dirs from earlier app versions are reclaimed without waiting for a
        // cloud event.
        applicationScope?.launch {
            runCatching { sweepOrphanedCloudIngestWorkDirs() }
                .onFailure { error -> DiagLog.w("CloudSync", "foreground cloud-ingest work sweep failed", error) }
        }
        // #E101: same cadence, but no bound-bucket guard — unlike the two sweeps
        // above it judges no cloud state, so it must run with nothing bound too.
        applicationScope?.launch {
            runCatching { sweepFailedDownloadStaging() }
                .onFailure { error -> DiagLog.w("DownloadPump", "foreground failed staging sweep failed", error) }
        }
        // #E102: same cadence and the same no-binding-guard reasoning — it only
        // shrinks cover files already on the device.
        applicationScope?.launch {
            runCatching { reencodeDurableCloudCovers() }
                .onFailure { error -> DiagLog.w(TAG_CLOUD_COVER, "foreground durable cover re-encode failed", error) }
        }
        // C35-d: the expired-trash purge the Collect preflight used to charge
        // to every tap. Its own time throttle and bucket gate make it a cheap
        // no-op; deliberately above the hydration throttle's early return,
        // which exists to stop network kick storms and has nothing to say
        // about this.
        purgeExpiredCloudTrash()
        // C43: the foreground moment is the ONE place an Android 12+ foreground
        // service start is guaranteed to be allowed. A transfer pump whose
        // background restart was denied its FGS (and therefore skipped its
        // pass) can only recover here. This runs on EVERY onStart, so each
        // branch is idempotent (audit H3/M2/M3, re-audit H-1 — see
        // foregroundReturnAction): runnable work WITH a denial/stop marker ->
        // user-initiated kick (REPLACE the parked/self-retrying request; a
        // RUNNING pump is a KEEP no-op, never a chained run); runnable work
        // without a marker -> plain KEEP kick (a pump in its own backoff or
        // waiting on a constraint is not cancelled per onStart); work parked
        // in a resume window -> restore its delayed wake with KEEP and drop
        // any stale marker; a stale marker with nothing to resume -> clear
        // it; idle -> nothing, so an idle app open never flashes a
        // "Downloading" notification. Runs BEFORE the bucket gate: downloads
        // need no bucket.
        applicationScope?.launch {
            runCatching {
                val pause = backgroundPauseState()
                val now = System.currentTimeMillis()
                val downloadHasWork = downloadJobStore.consumptionQueue().isNotEmpty()
                val parkedResumeAt = if (downloadHasWork) null else earliestResumeAfter(downloadJobStore.loadAllJobs(), now = now)
                when (
                    foregroundReturnAction(
                        hasWork = downloadHasWork,
                        parked = parkedResumeAt != null,
                        markerSet = pause?.downloadDeniedAtMs != null || pause?.downloadStoppedAtMs != null,
                    )
                ) {
                    ForegroundReturnAction.KICK_REPLACE -> DownloadPumpWorker.kickUser(appContext, chainBehindRunning = false)
                    ForegroundReturnAction.KICK_KEEP -> DownloadPumpWorker.kick(appContext)
                    ForegroundReturnAction.RESTORE_PARKED_WAKE -> {
                        DownloadPumpWorker.kickAfterUnlessPending(appContext, delayMs = parkedResumeAt!! - now)
                        DownloadPumpWorker.clearPauseMarkers(appContext)
                    }
                    ForegroundReturnAction.CLEAR_MARKERS -> DownloadPumpWorker.clearPauseMarkers(appContext)
                    ForegroundReturnAction.NOTHING -> Unit
                }
                when (
                    foregroundReturnAction(
                        hasWork = cloudIngestWorkRemains(cloudSyncQueue.snapshot()),
                        parked = false,
                        markerSet = pause?.cloudDeniedAtMs != null || pause?.cloudStoppedAtMs != null,
                    )
                ) {
                    ForegroundReturnAction.KICK_REPLACE -> CloudIngestWorker.kickUser(appContext, chainBehindRunning = false)
                    ForegroundReturnAction.KICK_KEEP -> CloudIngestWorker.kick(appContext)
                    ForegroundReturnAction.RESTORE_PARKED_WAKE, ForegroundReturnAction.NOTHING -> Unit
                    ForegroundReturnAction.CLEAR_MARKERS -> CloudIngestWorker.clearPauseMarkers(appContext)
                }
            }.onFailure { error -> DiagLog.w("BgWork", "foreground transfer re-kick failed", error) }
        }
        if (currentBucketFingerprintOrNull() == null) return
        val lastCheckAtMs = hydrationRecheckStore.lastCheckAtMs()
        if (!shouldRecheckHydration(lastCheckAtMs = lastCheckAtMs, nowMs = nowMs)) return
        Log.i("HYDRATION", "foreground recheck kick lastCheckAtMs=$lastCheckAtMs nowMs=$nowMs")
        // C29 audit MEDIUM-2: same reasoning as the cold-start kick — the
        // foreground return is the one moment this can preempt a parked walk,
        // and KEEP made it a no-op. onAppForeground is not suspend, so the
        // WorkInfo read goes to the application scope.
        val scope = applicationScope
        if (scope == null) {
            // Review LOW-1: a foreground that somehow precedes start() has no
            // scope to read WorkInfo on. Fall back to the plain KEEP kick rather
            // than dropping the kick behind the HYDRATION log line above.
            HydrationWorker.kick(appContext)
        } else {
            scope.launch { HydrationWorker.kickWhenIdle(appContext) }
        }
    }

    /** One foreground-only, no-retry pass for serialized source covers. */
    private suspend fun backfillSerializedSourceCovers() {
        val followedSeries = collectBookmarkStore.readAll()
            .asSequence()
            .filter(::isSerializedBookmark)
            .map { bookmark -> SeriesIdentityV2(bookmark.providerId, bookmark.id) }
            .toSet()
        val candidates = repository.serializedSeriesCoverCandidates()
        backfillSerializedSourceCoversOnce(
            candidates = candidates,
            files = serializedSourceCoverFiles,
            sourceFor = { namespace -> comicSourceRegistry.source(namespace) as? SeriesCoverSource },
            attempted = serializedSourceCoverAttempted,
            followedSeries = followedSeries,
        )
        candidates.forEach { candidate ->
            serializedSourceCoverFiles.uriIfValid(candidate.identity)?.let { uri ->
                publishSerializedCloudSourceCoverIfPresent(candidate.identity, uri)
            }
        }
    }

    /** Fire-and-forget [onBucketBound] for UI call sites that are not themselves suspend. */
    fun notifyBucketBound(resetTokenSource: Boolean = false) {
        applicationScope?.launch { onBucketBound(resetTokenSource) }
    }

    /** Fire-and-forget [onCloudDisconnected] for UI call sites that are not themselves suspend. */
    fun notifyCloudDisconnected() {
        applicationScope?.launch { onCloudDisconnected() }
    }

    val importCoordinator = ImportCoordinator(scanner, repository)

    companion object {
        /**
         * SF-4: how long a foreground must wait before it may sweep expired
         * cloud trash again. Long enough that app switching never turns into a
         * sweep storm, short enough that a session lasting all day still
         * expires what comes due.
         */
        private const val CLOUD_TRASH_PURGE_INTERVAL_MS = 30 * 60 * 1_000L

        /**
         * SF-4: how many expired cards ONE sweep may discard. Each discard is
         * 2N+2 deletes under `INDEX_MUTATION_LOCK`, which uploads and restores
         * also need; three keeps any single sweep short, and a 30-day window
         * means the backlog has all the time in the world to drain.
         */
        private const val CLOUD_TRASH_PURGE_MAX_CARDS = 3

        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }

        fun get(context: Context, sourceRegistryOverride: ComicSourceRegistry?): AppGraph {
            if (sourceRegistryOverride != null) {
                return AppGraph(context.applicationContext, sourceRegistryOverride)
            }
            return get(context)
        }
    }
}

sealed interface SerializedDeleteResult {
    data class Deleted(val chapters: Int) : SerializedDeleteResult
    data object RefusedActive : SerializedDeleteResult
    data object RefusedUpload : SerializedDeleteResult
    data object Failed : SerializedDeleteResult
    data object NothingSaved : SerializedDeleteResult
}

/**
 * Which drop verb (if any) [AppGraph.dropUnusableLocalCopyForRecollect] owes a
 * comic whose local pages Activity's Retry wants re-downloaded. Pure, so the
 * table is pinned on the JVM; the suspending helper itself has no seam.
 */
enum class RecollectDropDecision {
    /** There is no shelf copy and no verb that could act: the re-collect just proceeds. */
    NOTHING_TO_DROP,

    /** The ordinary Library delete (row + READY tree + catalog tree + job), with its own in-flight guard. */
    DELETE_VERB,

    /** A cloud-only seal the shelf never pointed at: only `discardCloudOnlyJob` may drop it. */
    DISCARD_VERB,
}

/**
 * Re-review r3 HIGH-1: "there is nothing left to drop" is SUCCESS, not a
 * refusal. The owner deleting the comic from Detail first leaves a `NONE` row
 * (every delete verb ends there without removing it), and `deleteDownloadedComic`
 * refuses `NONE` — which used to re-park `LOCAL_COPY_NOT_DROPPED` on every
 * Retry tap, a permanent dead end.
 *
 * - no row at all / `NONE` -> nothing to drop;
 * - `SEALED` with no on-device Library association -> the cloud-only discard verb
 *   (`deleteFinished`'s `require` would throw on this shape);
 * - `FINISH`, or `SEALED` the shelf still points at -> the ordinary delete verb;
 * - `WAIT`/`DOWNLOAD`/`FAILED`/`GONE` with no association -> nothing to drop (a live
 *   fetch or a failed one holds no shelf copy; the re-collect merges its intents),
 *   and with one -> the delete verb, which refuses honestly and is reported as such.
 */
internal fun recollectDropDecision(jobState: DownloadJobState?, associations: Int): RecollectDropDecision = when {
    jobState == null || jobState == DownloadJobState.NONE -> RecollectDropDecision.NOTHING_TO_DROP
    jobState == DownloadJobState.SEALED ->
        if (associations > 0) RecollectDropDecision.DELETE_VERB else RecollectDropDecision.DISCARD_VERB
    jobState == DownloadJobState.FINISH -> RecollectDropDecision.DELETE_VERB
    associations > 0 -> RecollectDropDecision.DELETE_VERB
    else -> RecollectDropDecision.NOTHING_TO_DROP
}

internal fun buildProductionCandidateRecognizer(
    context: Context,
    documentAccess: com.easemic.app.storage.DocumentAccess,
    directoryScanner: ImageDirectoryScanner =
        ImageDirectoryScanner(documentAccess, ComicInfoParser()),
    archiveCacheDirectory: File = File(context.cacheDir, "archive-import"),
    availableBytes: () -> Long = { availableArchiveCacheBytes(context, archiveCacheDirectory) },
    archiveAccess: ArchiveAccess? = null,
): CandidateRecognizer {
    val resolvedArchiveAccess = archiveAccess ?: buildProductionArchiveAccess(
        context = context,
        documentAccess = documentAccess,
        archiveCacheDirectory = archiveCacheDirectory,
        availableBytes = availableBytes,
    )
    return CandidateRecognizer(
        directoryScanner = directoryScanner,
        archiveIndexer = SafArchiveCandidateIndexer(
            documents = documentAccess,
            archiveAccess = resolvedArchiveAccess,
            zipIndexer = ZipArchiveIndexer(),
        ),
    )
}

internal fun buildProductionArchiveAccess(
    context: Context,
    documentAccess: com.easemic.app.storage.DocumentAccess,
    archiveCacheDirectory: File,
    availableBytes: () -> Long = { availableArchiveCacheBytes(context, archiveCacheDirectory) },
): ArchiveAccess {
    check(archiveCacheDirectory.isDirectory || archiveCacheDirectory.mkdirs()) {
        "Private archive cache directory is unavailable."
    }
    val archiveCache = ArchiveCache(
        cacheDirectory = archiveCacheDirectory,
        availableBytes = availableBytes,
    )
    return ArchiveAccess(
        archiveCache = archiveCache,
        descriptorOpener = documentAccess::openFileDescriptor,
        streamOpener = documentAccess::openStream,
    )
}

private fun availableArchiveCacheBytes(context: Context, cacheDirectory: File): Long =
    try {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val uuid = storageManager.getUuidForPath(cacheDirectory)
        storageManager.getAllocatableBytes(uuid)
    } catch (_: Exception) {
        cacheDirectory.usableSpace
    }

fun buildAppDatabase(
    context: Context,
    name: String = "private-comic-library.db",
): AppDatabase = Room.databaseBuilder(
    context.applicationContext,
    AppDatabase::class.java,
    name,
).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20).build()
