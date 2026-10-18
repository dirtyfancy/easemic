package com.easemic.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.easemic.app.feature.bridge.CloudflareOAuthTrampoline
import com.easemic.app.feature.bridge.OAuthRedirectBus
import com.easemic.app.feature.bridge.DriveRedirectBus
import com.easemic.app.feature.bridge.DriveRedirectParser
import com.easemic.app.feature.importing.ImportAuthorizationCoordinator
import com.easemic.app.feature.importing.ImportNotice
import com.easemic.app.feature.importing.ImportPickerLauncher
import com.easemic.app.feature.importing.ImportViewModel
import com.easemic.app.feature.importing.PickerRequest
import com.easemic.app.feature.importing.PickerRequestSnapshot
import com.easemic.app.model.AdapterKind
import com.easemic.app.feature.library.LibraryViewModel
import com.easemic.app.feature.reader.ReaderVolumeKeyBus
import com.easemic.app.storage.OpenReadOnlyDocuments
import com.easemic.app.storage.OpenReadOnlyTree
import com.easemic.app.ui.ImportUiActions
import com.easemic.app.ui.PrivateComicLibraryApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingPickerRequests = PendingPickerRequests()
    private var openLibraryUpdates by mutableStateOf(false)
    private val appGraph by lazy { AppGraph.get(applicationContext) }
    private val libraryViewModel by viewModels<LibraryViewModel> {
        LibraryViewModel.Factory(
            appGraph.repository,
            appGraph.importCoordinator,
            appGraph.documentAccess,
        )
    }
    private val importViewModel by viewModels<ImportViewModel> {
        ImportViewModel.Factory(
            appGraph.sourceRepository,
            appGraph.scanRunRepository,
            appGraph.scanWorkScheduler,
        )
    }
    private val importAuthorizationCoordinator by lazy {
        ImportAuthorizationCoordinator(
            authorizeTreeSource = appGraph.sourceRepository::authorizeTreeSource,
            authorizeDocumentSources = appGraph.sourceRepository::authorizeDocumentBatch,
            reauthorizeTreeSource = appGraph.sourceRepository::reauthorizeTreeSource,
            reauthorizeDocumentSource = appGraph.sourceRepository::reauthorizeDocumentSource,
            scheduleRun = importViewModel::scheduleImportScan,
            treeDisplayName = appGraph.documentAccess::treeDisplayName,
            documentDisplayName = { uri ->
                appGraph.documentAccess.document(uri).displayName
            },
            onNotice = importViewModel::showNotice,
            onSourceImported = importViewModel::onFolderImported,
        )
    }
    private val openEhViewerTree = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val grant = OpenReadOnlyTree.parseResult(result.resultCode, result.data)
        val request = pendingPickerRequests.takeEhViewerTree()
        if (request == null) {
            importViewModel.showNotice(ImportNotice.AuthorizationFailed)
        } else {
            lifecycleScope.launch {
                importAuthorizationCoordinator.acceptTree(request, grant)
            }
        }
    }
    private val openGenericTree = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val grant = OpenReadOnlyTree.parseResult(result.resultCode, result.data)
        val request = pendingPickerRequests.takeGenericTree()
        if (request == null) {
            importViewModel.showNotice(ImportNotice.AuthorizationFailed)
        } else {
            lifecycleScope.launch {
                importAuthorizationCoordinator.acceptTree(request, grant)
            }
        }
    }
    private val openComicArchives = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val grants = OpenReadOnlyDocuments.parseResult(result.resultCode, result.data)
        val request = pendingPickerRequests.takeArchives()
        if (request == null) {
            importViewModel.showNotice(ImportNotice.AuthorizationFailed)
        } else {
            lifecycleScope.launch {
                importAuthorizationCoordinator.acceptArchives(request, grants)
            }
        }
    }
    private val importPickerLauncher by lazy {
        ImportPickerLauncher(
            launchEhViewerTree = { request ->
                pendingPickerRequests.record(request)
                openEhViewerTree.launch(OpenReadOnlyTree.createIntent(request.initialUri))
            },
            launchGenericTree = { request ->
                pendingPickerRequests.record(request)
                openGenericTree.launch(OpenReadOnlyTree.createIntent(request.initialUri))
            },
            launchArchives = { request ->
                pendingPickerRequests.record(request)
                openComicArchives.launch(OpenReadOnlyDocuments.createIntent())
            },
        )
    }

    /**
     * Owner bug A14 ("后台下载/上云不稳定", phone-verified on HONOR PGT-AN20):
     * the download/upload pumps run as a proper `dataSync` foreground
     * service, but nothing in the app ever requested runtime
     * `POST_NOTIFICATIONS` — so on Android 13+ the permission is silently
     * denied, the foreground service is invisible, and OEM app-launch
     * management kills it after screen-off. This launcher plus the
     * once-per-install check in [onCreate] closes that gap; declining is
     * fine (the Settings "Background tasks" row still offers the same
     * request later) — the result is intentionally unused.
     */
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Declining is a valid, non-blocking choice; Settings offers a retry. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPickerRequests = PendingPickerRequests.restore(savedInstanceState)
        openLibraryUpdates = savedInstanceState == null && consumeLibraryUpdatesExtra(intent)
        if (savedInstanceState != null) intent?.removeExtra(EXTRA_OPEN_LIBRARY_UPDATES)
        requestNotificationPermissionOnce()
        setContent {
            val state by libraryViewModel.uiState.collectAsStateWithLifecycle()
            val importState by importViewModel.uiState.collectAsStateWithLifecycle()
            PrivateComicLibraryApp(
                state = state,
                importState = importState,
                importActions = ImportUiActions(
                    onPickerRequest = importPickerLauncher::launch,
                    onRescanSource = importViewModel::rescanSource,
                    onRescanAllFolders = importViewModel::rescanAllFolders,
                    onReauthorizeSource = { sourceId ->
                        importViewModel.reauthorizeSource(
                            sourceId,
                            importPickerLauncher::launch,
                        )
                    },
                    onDismissNotice = importViewModel::dismissNotice,
                    onStopWatching = importViewModel::stopWatching,
                    onUndoStopWatching = importViewModel::undoStopWatching,
                    onDismissImportOutcome = importViewModel::dismissImportOutcome,
                ),
                onImportFolder = {
                    importPickerLauncher.launch(
                        PickerRequest.Tree(
                            adapterKind = AdapterKind.GENERIC_DIRECTORY,
                            initialUri = null,
                        ),
                    )
                },
                onOpenItem = {},
                onDismissNotice = libraryViewModel::dismissNotice,
                onCoverOpenFailed = libraryViewModel::onCoverOpenFailed,
                repository = appGraph.repository,
                appGraphOverride = appGraph,
                onEnterLibrarySearch = libraryViewModel::enterSearch,
                onExitLibrarySearch = libraryViewModel::exitSearch,
                onLibrarySearchQueryChange = libraryViewModel::setSearchQuery,
                onLibraryFilterChange = libraryViewModel::setFilter,
                openLibraryUpdates = openLibraryUpdates,
                onLibraryUpdatesIntentConsumed = { openLibraryUpdates = false },
            )
        }
        handleOAuthIntent(intent)
    }

    /**
     * Owner bug 2026-09-10: route hardware volume keys to the on-screen reader
     * ([ReaderVolumeKeyBus]). onKeyDown/onKeyUp are the Activity's own volume
     * hook — reached before the AudioManager fallback and, unlike
     * ComponentActivity.dispatchKeyEvent, not a @RestrictedApi. The bus pages on
     * DOWN and swallows both DOWN and UP so no system volume UI shows; when no
     * reader is registered (or paging is off) it passes through and the system
     * handles volume normally everywhere else.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (event != null && ReaderVolumeKeyBus.dispatch(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (event != null && ReaderVolumeKeyBus.dispatch(event)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        // A22 §3: foreground re-check of the cloud manifest (throttled inside
        // AppGraph). C43: also the transfer pumps' recovery point — on a cold
        // launch this overlaps AppGraph.start's own kicks; without a
        // denial/stop marker the foreground kick is KEEP and dedups with them,
        // with a marker it REPLACEs (see AppGraph.onAppForeground).
        appGraph.onAppForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthIntent(intent)
        if (consumeLibraryUpdatesExtra(intent)) openLibraryUpdates = true
    }

    private fun consumeLibraryUpdatesExtra(intent: Intent?): Boolean {
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_LIBRARY_UPDATES, false)) return false
        intent.removeExtra(EXTRA_OPEN_LIBRARY_UPDATES)
        setIntent(intent)
        return true
    }

    private fun handleOAuthIntent(intent: Intent?) {
        if (intent == null) return
        // #C50: a drive sign-in bounce (forwarded as VIEW data by the trampoline).
        if (intent.action == null || intent.action == Intent.ACTION_VIEW) {
            intent.dataString?.let(DriveRedirectParser::parse)?.let { drive ->
                intent.data = null
                DriveRedirectBus.dispatch(drive)
                return
            }
        }
        val parsed = CloudflareOAuthTrampoline.consumeResult(intent) ?: return
        OAuthRedirectBus.dispatch(parsed)
    }

    /**
     * Owner bug A14: requests `POST_NOTIFICATIONS` at most once per install
     * (tracked via [AppGraph.backgroundNotificationAskedStore], not a
     * per-process flag, so a user who declines isn't asked again on the next
     * cold start either). No-op below Android 13 (the permission doesn't
     * exist) or when already granted.
     */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) return
        val store = appGraph.backgroundNotificationAskedStore
        if (store.hasAsked()) return
        store.markAsked()
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingPickerRequests.save(outState)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_OPEN_LIBRARY_UPDATES = "open_library_updates"
    }
}

internal class PendingPickerRequests private constructor(
    private var ehViewerTree: PickerRequest.Tree?,
    private var genericTree: PickerRequest.Tree?,
    private var archives: PickerRequest.ComicArchives?,
) {
    constructor() : this(null, null, null)

    fun record(request: PickerRequest) {
        when (request) {
            is PickerRequest.Tree -> when (request.adapterKind) {
                com.easemic.app.model.AdapterKind.EHVIEWER ->
                    ehViewerTree = request
                com.easemic.app.model.AdapterKind.GENERIC_DIRECTORY ->
                    genericTree = request
                com.easemic.app.model.AdapterKind.COMIC_ARCHIVE ->
                    error("Archive imports must use the multi-document picker.")
                com.easemic.app.model.AdapterKind.CATALOG_DOWNLOAD ->
                    error("Catalog downloads are not imported via the folder picker.")
            }
            is PickerRequest.ComicArchives -> archives = request
        }
    }

    fun takeEhViewerTree(): PickerRequest.Tree? =
        ehViewerTree.also { ehViewerTree = null }

    fun takeGenericTree(): PickerRequest.Tree? =
        genericTree.also { genericTree = null }

    fun takeArchives(): PickerRequest.ComicArchives? =
        archives.also { archives = null }

    fun save(outState: Bundle) {
        ehViewerTree?.let { request ->
            outState.putStringArrayList(
                STATE_EHVIEWER_TREE,
                ArrayList(PickerRequestSnapshot.encode(request)),
            )
        }
        genericTree?.let { request ->
            outState.putStringArrayList(
                STATE_GENERIC_TREE,
                ArrayList(PickerRequestSnapshot.encode(request)),
            )
        }
        archives?.let { request ->
            outState.putStringArrayList(
                STATE_ARCHIVES,
                ArrayList(PickerRequestSnapshot.encode(request)),
            )
        }
    }

    companion object {
        fun restore(savedInstanceState: Bundle?): PendingPickerRequests =
            PendingPickerRequests(
                ehViewerTree = savedInstanceState
                    ?.getStringArrayList(STATE_EHVIEWER_TREE)
                    ?.let(PickerRequestSnapshot::decode)
                    ?.let { it as? PickerRequest.Tree }
                    ?.takeIf {
                        it.adapterKind ==
                            com.easemic.app.model.AdapterKind.EHVIEWER
                    },
                genericTree = savedInstanceState
                    ?.getStringArrayList(STATE_GENERIC_TREE)
                    ?.let(PickerRequestSnapshot::decode)
                    ?.let { it as? PickerRequest.Tree }
                    ?.takeIf {
                        it.adapterKind ==
                            com.easemic.app.model.AdapterKind.GENERIC_DIRECTORY
                    },
                archives = savedInstanceState
                    ?.getStringArrayList(STATE_ARCHIVES)
                    ?.let(PickerRequestSnapshot::decode)
                    ?.let { it as? PickerRequest.ComicArchives },
            )

        const val STATE_EHVIEWER_TREE = "pending-ehviewer-tree"
        const val STATE_GENERIC_TREE = "pending-generic-tree"
        const val STATE_ARCHIVES = "pending-archives"
    }
}
