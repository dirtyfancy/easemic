package com.easemic.app.feature.importing

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.easemic.app.data.ScanRunRepository
import com.easemic.app.data.SourceRepository
import com.easemic.app.data.room.AuthorizedDocumentBatch
import com.easemic.app.data.room.AuthorizedSourceOutcome
import com.easemic.app.data.room.DocumentSourceSelection
import com.easemic.app.model.AdapterKind
import com.easemic.app.model.AuthorizedSource
import com.easemic.app.model.GrantKind
import com.easemic.app.model.ScanRun
import com.easemic.app.model.ScanRunId
import com.easemic.app.model.ScanRunState
import com.easemic.app.model.SourceGrantState
import com.easemic.app.model.SourceId
import com.easemic.app.model.WorkRequestId
import com.easemic.app.storage.GrantedUri
import com.easemic.app.work.ScanWorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface PickerRequest {
    data class Tree(
        val adapterKind: AdapterKind,
        val initialUri: Uri?,
        val reauthorizeSourceId: SourceId? = null,
    ) : PickerRequest

    data class ComicArchives(
        val reauthorizeSourceId: SourceId? = null,
    ) : PickerRequest
}

data class ImportUiState(
    val sources: List<AuthorizedSource> = emptyList(),
    val latestRunsBySource: Map<SourceId, ScanRun> = emptyMap(),
    val notice: String? = null,
    val importOutcome: ImportOutcome? = null,
    val stoppedFolder: StoppedFolder? = null,
    val folderRows: List<FolderRow> = emptyList(),
    /** Review MEDIUM-2: raw per-source comic counts, independent of row status,
     * so Settings' total never silently zeroes out Scanning/AccessLost rows. */
    val comicCountsBySource: Map<SourceId, Int> = emptyMap(),
    /** SEND-BACK #2 item C: true from the moment a refresh is requested until
     * it either enqueues nothing or every folder it started stops scanning. */
    val refreshInFlight: Boolean = false,
)

/** A30 §2.2: the folder currently hidden by an in-flight "Stop watching" Undo window. */
data class StoppedFolder(
    val sourceId: SourceId,
    val displayName: String,
)

/** A30 §2.2/§5: live per-folder facts backing the Folders row status line. */
data class FolderFacts(
    val comicCounts: Map<SourceId, Int> = emptyMap(),
    val lastCompletedAtMillis: Map<SourceId, Long> = emptyMap(),
)

/** SEND-BACK #2 items C/J: single combine slot carrying both folder rows and
 * comic counts so the final `uiState` combine stays within the 5-flow limit. */
private data class DerivedFolderState(
    val folderRows: List<FolderRow>,
    val comicCountsBySource: Map<SourceId, Int>,
)

class ImportViewModel(
    private val sourceRepository: SourceRepository,
    private val scanRuns: ScanRunRepository,
    private val scheduler: ScanWorkScheduler,
) : ViewModel() {
    private val notice = MutableStateFlow<String?>(null)
    private val importOutcome = MutableStateFlow<ImportOutcome?>(null)
    private val outcomeTracker = ImportOutcomeTracker()
    private var outcomeClearJob: Job? = null
    private val stoppedFolder = MutableStateFlow<StoppedFolder?>(null)
    private var stopWatchingJob: Job? = null

    // Review LOW-1: shared (not just combined) so the init{} collector and
    // baseUiState's combine() see one live subscription to the underlying
    // repository/DB flows instead of two independent ones.
    private val durableState = combine(
        sourceRepository.observeSources(),
        scanRuns.observeLatestRunsBySource(),
    ) { sources, latestRuns ->
        DurableImportState(sources, latestRuns)
    }.shareIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        replay = 1,
    )
    /**
     * SEND-BACK #2 item H: nullable so "facts genuinely empty" can be told
     * apart from "facts have not loaded yet". The only reader that needed
     * the distinction (the on-open rescanDueFolders sweep) was deleted by
     * A57; [derivedFolderState] resolves null to an empty [FolderFacts] for
     * display, and the null marker stays so a future consumer can tell a
     * genuinely empty library from a still-loading snapshot.
     */
    private val folderFacts: StateFlow<FolderFacts?> = combine(
        sourceRepository.observeComicCountsBySource(),
        sourceRepository.observeLastCompletedScanBySource(),
        ::FolderFacts,
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    /**
     * SEND-BACK #2 items C/J: folded once here so the final [uiState] combine
     * stays within kotlinx.coroutines.flow.combine's 5-flow typed overload.
     * Also replaces the old per-row relative-time ticker: "scanned N ago" now
     * reads a live clock inside FoldersScreen's own produceState instead of
     * re-deriving text here every 30 s.
     */
    private val derivedFolderState: StateFlow<DerivedFolderState> = combine(
        durableState,
        folderFacts,
    ) { durable, facts ->
        val resolvedFacts = facts ?: FolderFacts()
        DerivedFolderState(
            folderRows = FolderRowMapper.rows(
                sources = durable.sources,
                latestRunsBySource = durable.latestRunsBySource,
                comicCountsBySource = resolvedFacts.comicCounts,
                lastCompletedAtMillis = resolvedFacts.lastCompletedAtMillis,
            ),
            comicCountsBySource = resolvedFacts.comicCounts,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = DerivedFolderState(emptyList(), emptyMap()),
    )

    /** SEND-BACK #2 item C: cleared by [rescanAllFolders] itself when it enqueues
     * nothing, otherwise by the collector in `init{}` on the scanning-to-settled edge. */
    private val refreshInFlight = MutableStateFlow(false)

    init {
        // Review HIGH-1: the only other sweep site is at the tail of the
        // stopWatching() coroutine, which process death / a fast second Stop
        // / an Undo can all skip -- leaving a tombstoned source's persisted
        // URI grant held forever. A defensive sweep here is safe because no
        // Undo window can be live yet at construction time (a restored
        // folder is refcount-protected regardless).
        viewModelScope.launch {
            runCatching { sourceRepository.releasePendingStoppedGrants() }
        }
        viewModelScope.launch {
            // SEND-BACK #2 item C: mirrors what a Compose-side
            // LaunchedEffect(anyScanning) did before, but now survives
            // navigating away from the Folders screen mid-refresh, and
            // still clears refreshInFlight even if rescanAllFolders()
            // itself never runs (e.g. every scheduled folder was skipped).
            var sawScanning = false
            derivedFolderState.collect { derived ->
                val anyScanning = derived.folderRows.any { it.status is FolderRowStatus.Scanning }
                if (anyScanning) {
                    sawScanning = true
                } else if (sawScanning) {
                    sawScanning = false
                    refreshInFlight.value = false
                }
            }
        }
        viewModelScope.launch {
            durableState.collect { durable ->
                val outcome = outcomeTracker.accept(durable.sources, durable.latestRunsBySource)
                    ?: return@collect
                importOutcome.value = outcome
                outcomeClearJob?.cancel()
                outcomeClearJob = launch {
                    delay(LINE_WINDOW_MILLIS)
                    importOutcome.compareAndSet(outcome, null)
                }
            }
        }
    }

    // kotlinx.coroutines.flow.combine tops out at 5 flows for the typed overload,
    // so importOutcome is folded in with a second combine stage.
    private val baseUiState = combine(
        durableState,
        notice,
    ) { durable, currentNotice ->
        ImportUiState(
            sources = durable.sources,
            latestRunsBySource = durable.latestRunsBySource,
            notice = currentNotice,
        )
    }

    val uiState: StateFlow<ImportUiState> = combine(
        baseUiState,
        importOutcome,
        stoppedFolder,
        derivedFolderState,
        refreshInFlight,
    ) { base, currentOutcome, currentStopped, derived, currentRefreshInFlight ->
        base.copy(
            importOutcome = currentOutcome,
            stoppedFolder = currentStopped,
            comicCountsBySource = derived.comicCountsBySource,
            folderRows = derived.folderRows,
            refreshInFlight = currentRefreshInFlight,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ImportUiState(),
    )

    fun dismissImportOutcome() {
        outcomeClearJob?.cancel()
        importOutcome.value = null
    }

    /**
     * SEND-BACK #2 items C/I: shared by [rescanSource] and
     * [rescanAllFolders] so "resume a paused run instead of re-enqueuing
     * it" and failure handling live in exactly one place. Returns false
     * (never throws) when scheduling could not be started at all.
     */
    private suspend fun scheduleRescan(sourceId: SourceId, userInitiated: Boolean): Boolean = try {
        val runId = scanRuns.createOrResume(sourceId)
        // Review MEDIUM-5: createOrResume() returns the id of an
        // already-active run when one exists. If that run is
        // paused/pause-requested (e.g. Undo of a mid-scan Stop, or SEND-BACK
        // #2 item I's engine-parked PAUSED run), enqueue()-ing it again
        // leaves it stuck forever -- it must be resumed instead.
        val runState = scanRuns.run(runId)?.state
        if (runState != null && runState in RESUMABLE_STATES) {
            scheduler.resume(runId)
        } else {
            scheduler.enqueue(runId, userInitiated = userInitiated)
        }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    /**
     * A30 §2.2 (S4b/B3): every rescan entry point re-checks the persisted grants first, so
     * a folder whose access Android revoked while the app was running flips to "Access lost
     * · Choose folder again" instead of scanning an unreadable tree and reporting
     * "0 comics". Cheap (permission-list read, no tree I/O) and never throws.
     */
    private suspend fun revalidateGrants(): Set<SourceId> = try {
        sourceRepository.revalidateReadyGrants().toSet()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // A failed re-check must never block the rescan it precedes.
        emptySet()
    }

    /**
     * S4b/G3: the picker's scheduling path. Same rule as every rescan entry point -- an
     * engine-parked run is resumed, everything else is enqueued -- so "Choose folder again"
     * actually restarts the scan it re-authorized.
     */
    suspend fun scheduleImportScan(sourceId: SourceId): Boolean =
        scheduleRescan(sourceId, userInitiated = true)

    /** S4b/G4: the picker just imported this folder; its next committing scan offers Undo. */
    fun onFolderImported(sourceId: SourceId) {
        outcomeTracker.markImported(sourceId)
    }

    fun rescanSource(sourceId: SourceId) {
        viewModelScope.launch {
            revalidateGrants()
            val grantState = try {
                sourceRepository.source(sourceId)?.grantState
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (grantState != SourceGrantState.READY) {
                // The row already states the problem; scheduling a scan it cannot run would
                // only park a run and stamp a misleading "scanned just now".
                return@launch
            }
            if (!scheduleRescan(sourceId, userInitiated = true)) {
                showNotice(ImportNotice.SchedulingFailed)
            }
        }
    }

    /** A30 §2.2: optimistic, reversible, and it never touches the user's files. */
    fun stopWatching(sourceId: SourceId) {
        val label = uiState.value.sources.firstOrNull { it.id == sourceId }?.displayLabel ?: return
        stopWatchingJob?.cancel()
        // SEND-BACK #2 item F: published synchronously, before this coroutine
        // suspends on the repository call below, so a Stop immediately
        // followed by an Undo (in the same call turn) always targets this
        // folder rather than whatever stoppedFolder previously held.
        val stopped = StoppedFolder(sourceId, label)
        stoppedFolder.value = stopped
        stopWatchingJob = viewModelScope.launch {
            val release = try {
                sourceRepository.stopWatchingSource(sourceId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                stoppedFolder.compareAndSet(stopped, null)
                showNotice(ImportNotice.StopWatchingFailed(label))
                return@launch
            }
            release.fencedRunId?.let { runId ->
                try {
                    scanRuns.currentWorkRequest(runId)?.let { requestId ->
                        scheduler.cancelIfCurrent(runId, requestId)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    showNotice(ImportNotice.BackgroundCancellationFailed(label))
                }
            }
            delay(LINE_WINDOW_MILLIS)
            stoppedFolder.compareAndSet(stopped, null)
            try {
                sourceRepository.releasePendingStoppedGrants()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showNotice(ImportNotice.GrantReleaseFailed)
            }
        }
    }

    fun undoStopWatching(sourceId: SourceId) {
        // SEND-BACK #2 item F: only undo the line this action was rendered
        // for -- a stale click after a newer Stop replaced it is a no-op.
        val stopped = stoppedFolder.value?.takeIf { it.sourceId == sourceId } ?: return
        stopWatchingJob?.cancel()
        stopWatchingJob = null
        stoppedFolder.compareAndSet(stopped, null)
        viewModelScope.launch {
            val restored = try {
                sourceRepository.restoreStoppedSource(stopped.sourceId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showNotice(ImportNotice.UndoStopWatchingFailed(stopped.displayName))
                return@launch
            }
            // SEND-BACK #2 item E: cancelling stopWatchingJob above also
            // cancelled this folder's own tail sweep, so nothing else in this
            // process is left to release its grant -- sweep here regardless
            // of whether the restore itself succeeded. A no-op for anything
            // already released by another folder's sweep.
            try {
                sourceRepository.releasePendingStoppedGrants()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showNotice(ImportNotice.GrantReleaseFailed)
            }
            if (restored) rescanNow(stopped.sourceId)
        }
    }

    fun rescanNow(sourceId: SourceId) = rescanSource(sourceId)

    /** Pull-to-refresh: every watched folder, ignoring the 10-minute window. */
    fun rescanAllFolders() {
        // SEND-BACK #2 item C: set synchronously so FoldersScreen's spinner
        // appears the instant the gesture is recognized, not after the first
        // suspending call below returns.
        refreshInFlight.value = true
        viewModelScope.launch {
            val lostAccess = revalidateGrants()
            val eligible = uiState.value.sources.filter {
                it.grantState == SourceGrantState.READY && it.id !in lostAccess
            }
            var anyScheduled = false
            eligible.forEach { source ->
                if (scheduleRescan(source.id, userInitiated = true)) anyScheduled = true
            }
            if (!anyScheduled) {
                // Nothing will ever start Scanning to trigger init{}'s
                // sawScanning collector, so this refresh must clear itself
                // right here instead.
                refreshInFlight.value = false
                if (eligible.isNotEmpty()) showNotice(ImportNotice.SchedulingFailed)
            }
        }
    }

    fun pauseSource(runId: ScanRunId) {
        viewModelScope.launch {
            try {
                scheduler.requestPause(runId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showNotice(ImportNotice.SchedulingFailed)
            }
        }
    }

    fun resumeSource(runId: ScanRunId) {
        viewModelScope.launch {
            try {
                scheduler.resume(runId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showNotice(ImportNotice.SchedulingFailed)
            }
        }
    }

    fun reauthorizeSource(
        sourceId: SourceId,
        onPickerRequest: (PickerRequest) -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val source = sourceRepository.source(sourceId)
                if (source == null) {
                    showNotice(ImportNotice.ReauthorizationFailed)
                    return@launch
                }
                val request = when (source.grantKind) {
                    GrantKind.DOCUMENT -> PickerRequest.ComicArchives(
                        reauthorizeSourceId = source.id,
                    )
                    GrantKind.TREE -> PickerRequest.Tree(
                        source.adapterKind,
                        initialUri = null,
                        reauthorizeSourceId = source.id,
                    )
                }
                onPickerRequest(request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showNotice(ImportNotice.ReauthorizationFailed)
            }
        }
    }

    fun showNotice(value: ImportNotice) {
        notice.value = when (value) {
            ImportNotice.AuthorizationFailed ->
                "Could not keep read access. Choose the folder or archive again."
            ImportNotice.SchedulingFailed ->
                "The rescan did not start. Pull down to try again."
            ImportNotice.ReauthorizationFailed ->
                "Could not prepare reauthorization. Try again."
            ImportNotice.GrantReleaseFailed ->
                "Android kept the folder access. Nothing was changed."
            is ImportNotice.BackgroundCancellationFailed ->
                "Stopped watching ${value.folderName}. Its scan stops at the next step."
            is ImportNotice.StopWatchingFailed ->
                "Could not stop watching ${value.folderName}. Try again."
            is ImportNotice.UndoStopWatchingFailed ->
                "Could not bring ${value.folderName} back. Import it again from the shelf."
        }
    }

    fun dismissNotice() {
        notice.value = null
    }

    class Factory(
        private val sourceRepository: SourceRepository,
        private val scanRuns: ScanRunRepository,
        private val scheduler: ScanWorkScheduler,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            check(modelClass.isAssignableFrom(ImportViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return ImportViewModel(
                sourceRepository,
                scanRuns,
                scheduler,
            ) as T
        }
    }

    companion object {
        const val LINE_WINDOW_MILLIS = 6_000L
        private val RESUMABLE_STATES = setOf(ScanRunState.PAUSED, ScanRunState.PAUSE_REQUESTED)
    }

    private data class DurableImportState(
        val sources: List<AuthorizedSource>,
        val latestRunsBySource: Map<SourceId, ScanRun>,
    )
}

sealed interface ImportNotice {
    data object AuthorizationFailed : ImportNotice
    data object SchedulingFailed : ImportNotice
    data object ReauthorizationFailed : ImportNotice
    data object GrantReleaseFailed : ImportNotice

    /**
     * A30 §2.2: "Stop watching" already committed -- the folder is tombstoned
     * and its active run is durably PAUSE_REQUESTED -- but the immediate
     * WorkManager cancellation could not be confirmed. The worker still stops
     * itself at its next boundary (`ScanEngine.pauseBoundary`, and the
     * tombstoned source is no longer READY), so there is nothing for the user
     * to do; the line states that rather than inventing an action.
     */
    data class BackgroundCancellationFailed(val folderName: String) : ImportNotice

    /** SEND-BACK #2 item G: dedicated copy for "Stop watching" itself failing. */
    data class StopWatchingFailed(val folderName: String) : ImportNotice

    /** SEND-BACK #2 item G: dedicated copy for Undo of a Stop failing. */
    data class UndoStopWatchingFailed(val folderName: String) : ImportNotice
}

class ImportAuthorizationCoordinator(
    /**
     * S4b/H3: reports whether the picker started watching this folder now, so re-picking an
     * already-watched folder is not announced as an import.
     */
    private val authorizeTreeSource:
        suspend (GrantedUri, AdapterKind, String) -> AuthorizedSourceOutcome?,
    private val authorizeDocumentSources: suspend (List<DocumentSourceSelection>) -> AuthorizedDocumentBatch?,
    private val reauthorizeTreeSource:
        suspend (SourceId, GrantedUri, AdapterKind, String) -> SourceId?,
    private val reauthorizeDocumentSource:
        suspend (SourceId, DocumentSourceSelection) -> SourceId?,
    /**
     * S4b/G3: scheduling is [ImportViewModel.scheduleImportScan], not a bare enqueue.
     * "Choose folder again" lands on a source whose run the engine parked with GRANT_LOST;
     * enqueuing that run again just walks into `ScanEngine.pauseBoundary` and stops, so the
     * folder sat at "0 comics" until something else resumed it. Returns false when nothing
     * could be started.
     */
    private val scheduleRun: suspend (SourceId) -> Boolean,
    private val treeDisplayName: suspend (String) -> String,
    private val documentDisplayName: suspend (String) -> String,
    private val onNotice: (ImportNotice) -> Unit,
    /**
     * S4b/G4: called for a folder the user just imported through the picker (never for a
     * "Choose folder again" reauthorization, and never for Undo of Stop watching). This is
     * the only thing that makes a scan count as a first import for the added line.
     */
    private val onSourceImported: (SourceId) -> Unit = {},
) {
    suspend fun acceptTree(
        request: PickerRequest.Tree,
        grant: GrantedUri?,
    ) {
        if (grant == null) return
        var freshlyWatched = false
        val sourceId = try {
            val displayName =
                displayNameOrFallback(grant.uri.toString(), "Selected folder", treeDisplayName)
            val target = request.reauthorizeSourceId
            if (target != null) {
                reauthorizeTreeSource(target, grant, request.adapterKind, displayName)
            } else {
                authorizeTreeSource(grant, request.adapterKind, displayName)?.let { outcome ->
                    freshlyWatched = outcome.freshlyWatched
                    outcome.sourceId
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            onNotice(ImportNotice.AuthorizationFailed)
            return
        }
        if (sourceId == null) {
            onNotice(
                if (request.reauthorizeSourceId == null) {
                    ImportNotice.AuthorizationFailed
                } else {
                    ImportNotice.ReauthorizationFailed
                },
            )
            return
        }
        if (freshlyWatched) onSourceImported(sourceId)
        schedule(sourceId)
    }

    suspend fun acceptArchives(grants: List<GrantedUri>) =
        acceptArchives(PickerRequest.ComicArchives(), grants)

    suspend fun acceptArchives(
        request: PickerRequest.ComicArchives,
        grants: List<GrantedUri>,
    ) {
        if (grants.isEmpty()) return
        val selections = grants.map { grant ->
            DocumentSourceSelection(
                grant = grant,
                adapterKind = AdapterKind.COMIC_ARCHIVE,
                displayLabel = displayNameOrFallback(
                    grant.uri.toString(),
                    "Selected archive",
                    documentDisplayName,
                ),
            )
        }
        val target = request.reauthorizeSourceId
        if (target != null) {
            if (selections.size != 1) {
                onNotice(ImportNotice.ReauthorizationFailed)
                return
            }
            val sourceId = try {
                reauthorizeDocumentSource(target, selections.single())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                onNotice(ImportNotice.ReauthorizationFailed)
                return
            }
            if (sourceId == null) {
                onNotice(ImportNotice.ReauthorizationFailed)
                return
            }
            schedule(sourceId)
            return
        }
        val batch = try {
            authorizeDocumentSources(selections)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            onNotice(ImportNotice.AuthorizationFailed)
            return
        }
        if (batch == null) {
            onNotice(ImportNotice.AuthorizationFailed)
            return
        }
        if (batch.rejectedCount > 0) {
            onNotice(ImportNotice.AuthorizationFailed)
        }
        batch.sourceIds.forEach { sourceId ->
            if (sourceId in batch.freshlyWatchedSourceIds) onSourceImported(sourceId)
            schedule(sourceId)
        }
    }

    private suspend fun schedule(sourceId: SourceId) {
        val scheduled = try {
            scheduleRun(sourceId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!scheduled) onNotice(ImportNotice.SchedulingFailed)
    }

    private suspend fun displayNameOrFallback(
        uri: String,
        fallback: String,
        lookup: suspend (String) -> String,
    ): String = try {
        lookup(uri).takeIf(String::isNotBlank) ?: fallback
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        fallback
    }
}

class ImportPickerLauncher(
    private val launchEhViewerTree: (PickerRequest.Tree) -> Unit,
    private val launchGenericTree: (PickerRequest.Tree) -> Unit,
    private val launchArchives: (PickerRequest.ComicArchives) -> Unit,
) {
    fun launch(request: PickerRequest) {
        when (request) {
            is PickerRequest.Tree -> when (request.adapterKind) {
                AdapterKind.EHVIEWER -> launchEhViewerTree(request)
                AdapterKind.GENERIC_DIRECTORY -> launchGenericTree(request)
                AdapterKind.COMIC_ARCHIVE ->
                    error("Archive imports must use the multi-document picker.")
                AdapterKind.CATALOG_DOWNLOAD ->
                    error("Catalog downloads are not imported via the folder picker.")
            }
            is PickerRequest.ComicArchives -> launchArchives(request)
        }
    }
}

object PickerRequestSnapshot {
    fun encode(request: PickerRequest): List<String> = when (request) {
        is PickerRequest.Tree -> {
            val target = request.reauthorizeSourceId
            if (target == null) {
                listOf(
                    SNAPSHOT_VERSION,
                    "tree",
                    "fresh",
                    request.adapterKind.name,
                    request.initialUri?.toString().orEmpty(),
                )
            } else {
                listOf(
                    SNAPSHOT_VERSION,
                    "tree",
                    "reauthorize",
                    request.adapterKind.name,
                    request.initialUri?.toString().orEmpty(),
                    target.value.toString(),
                )
            }
        }
        is PickerRequest.ComicArchives -> {
            val target = request.reauthorizeSourceId
            if (target == null) {
                listOf(SNAPSHOT_VERSION, "archives", "fresh")
            } else {
                listOf(
                    SNAPSHOT_VERSION,
                    "archives",
                    "reauthorize",
                    target.value.toString(),
                )
            }
        }
    }

    fun decode(values: List<String>): PickerRequest? {
        if (values.firstOrNull() != SNAPSHOT_VERSION) return null
        return when (values.getOrNull(1)) {
            "tree" -> {
                val mode = values.getOrNull(2) ?: return null
                val adapter = values.getOrNull(3)
                    ?.let { runCatching { AdapterKind.valueOf(it) }.getOrNull() }
                    ?.takeIf {
                        it == AdapterKind.EHVIEWER ||
                            it == AdapterKind.GENERIC_DIRECTORY
                    }
                    ?: return null
                val initialUri = values.getOrNull(4)
                    ?.takeIf(String::isNotEmpty)
                    ?.let(Uri::parse)
                when (mode) {
                    "fresh" -> {
                        if (values.size != 5) return null
                        PickerRequest.Tree(
                            adapterKind = adapter,
                            initialUri = initialUri,
                        )
                    }
                    "reauthorize" -> {
                        if (values.size != 6) return null
                        val target = values[5]
                            .toLongOrNull()
                            ?.takeIf { it > 0 }
                            ?.let(::SourceId)
                            ?: return null
                        PickerRequest.Tree(
                            adapterKind = adapter,
                            initialUri = initialUri,
                            reauthorizeSourceId = target,
                        )
                    }
                    else -> null
                }
            }
            "archives" -> when (values.getOrNull(2)) {
                "fresh" -> {
                    if (values.size != 3) return null
                    PickerRequest.ComicArchives()
                }
                "reauthorize" -> {
                    if (values.size != 4) return null
                    val target = values[3]
                        .toLongOrNull()
                        ?.takeIf { it > 0 }
                        ?.let(::SourceId)
                        ?: return null
                    PickerRequest.ComicArchives(reauthorizeSourceId = target)
                }
                else -> null
            }
            else -> null
        }
    }

    private const val SNAPSHOT_VERSION = "1"
}
