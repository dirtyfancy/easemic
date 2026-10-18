package com.easemic.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.easemic.app.data.LibraryRepository
import com.easemic.app.importing.ImportCoordinator
import com.easemic.app.importing.ImportFailure
import com.easemic.app.importing.ImportResult
import com.easemic.app.model.LibraryItem
import com.easemic.app.storage.DocumentAccess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val items: List<LibraryItem> = emptyList(),
    val isImporting: Boolean = false,
    val notice: LibraryNotice? = null,
    val isLoading: Boolean = false,
    val stateFilter: LibraryStateFilter = LibraryStateFilter(),
    /**
     * W3 (A38): the RAW query echoes every keystroke instantly (the search
     * field binds this); [searchQuery] is the debounced value that drives
     * filtering/highlight/empty-state.
     */
    val rawSearchQuery: String = "",
    val searchQuery: String = "",
    val searchMode: Boolean = false,
    val downloadAcquisitionByItemId: Map<Long, String?> = emptyMap(),
    val serializedSeriesLedgerKeyByItemId: Map<Long, String> = emptyMap(),
)

data class ShelfUiState(
    val stateFilter: LibraryStateFilter = LibraryStateFilter(),
    val searchQuery: String = "",
    val searchMode: Boolean = false,
)

sealed interface LibraryNotice {
    data object MetadataFallback : LibraryNotice
    data class ImportFailed(val reason: ImportFailure) : LibraryNotice
    data object AvailabilityRefreshFailed : LibraryNotice
}

class LibraryViewModel(
    private val repository: LibraryRepository,
    private val importCoordinator: ImportCoordinator,
    private val documentAccess: DocumentAccess? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val importState = MutableStateFlow(ImportState())
    private val shelfState = MutableStateFlow(ShelfUiState())

    /**
     * W3 (A38): the search query debounced 250 ms before it reaches the
     * combined uiState — each keystroke no longer re-filters the whole
     * composed shelf list; the filter in LibraryScreen runs once per settled
     * query instead of once per key. The initial value bypasses the debounce
     * (onStart emit) so the shelf still renders instantly on open; only
     * actual query CHANGES settle.
     */
    @OptIn(FlowPreview::class)
    private val debouncedSearchQuery: Flow<String> =
        shelfState.map { it.searchQuery }
            .debounce(250L)
            .onStart { emit(shelfState.value.searchQuery) }
            .distinctUntilChanged()

    /**
     * W3 (A38): reactive acquisition keys — the old shape polled
     * `downloadAcquisitionKeys()` on EVERY library emission (double
     * subscription AND double load per change); the Flow re-emits only when
     * provenance claims actually change. Fails closed to the old poll's
     * degradation: a keys-flow error must degrade to empty badges and never
     * break the shelf's combined flow (the old runCatching/getOrDefault
     * behavior, kept for the reactive shape).
     */
    private val downloadAcquisitionState: Flow<Map<Long, String>> =
        repository.observeDownloadAcquisitionKeys()
            .catch { emit(emptyMap()) }

    private val serializedSeriesLedgerKeyState: Flow<Map<Long, String>> =
        repository.observeSerializedSeriesLedgerKeys()
            .catch { emit(emptyMap()) }

    private val downloadIdentityState: Flow<Pair<Map<Long, String>, Map<Long, String>>> =
        combine(downloadAcquisitionState, serializedSeriesLedgerKeyState) { acquisition, series ->
            acquisition to series
        }

    val uiState: StateFlow<LibraryUiState> = combine(
        repository.observeShelf(),
        importState,
        debouncedSearchQuery,
        downloadIdentityState,
        shelfState,
    ) { items, import, query, identities, shelf ->
        LibraryUiState(
            items = items,
            isImporting = import.isImporting,
            notice = import.notice,
            isLoading = false,
            stateFilter = shelf.stateFilter,
            // W3 (A38): rawSearchQuery echoes keystrokes instantly so the
            // search FIELD never lags the typist; the settled (debounced)
            // query drives filter/highlight/empty-state below.
            rawSearchQuery = shelf.searchQuery,
            searchQuery = query,
            searchMode = shelf.searchMode,
            downloadAcquisitionByItemId = identities.first,
            serializedSeriesLedgerKeyByItemId = identities.second,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = LibraryUiState(isLoading = true),
    )

    fun setFilter(filter: LibraryStateFilter) {
        shelfState.update { it.copy(stateFilter = filter) }
    }

    fun setSearchQuery(query: String) {
        shelfState.update { it.copy(searchQuery = query) }
    }

    fun enterSearch() {
        shelfState.update { it.copy(searchMode = true) }
    }

    fun exitSearch() {
        shelfState.update { it.copy(searchMode = false, searchQuery = "") }
    }

    // A30 §1 principle 3 / §2.1 (S4b/B3): there is no longer a startup availability sweep.
    // It rewrote *every* location of a folder whose tree it could not read to UNAVAILABLE
    // -- marking data missing because of an access problem -- which is what emptied the
    // shelf and zeroed the Folders counts the moment Android revoked a grant, while the row
    // still claimed "N comics · scanned N min ago". Access now lives on the source
    // (SourceRepository.revalidateReadyGrants + ShelfVisibility); location state changes
    // only when a scan reconciles what it actually saw, or when the reader proves a file is
    // gone. `documentAccess` is kept in the signature for the Factory's call sites.

    fun importTree(treeUri: String) {
        if (importState.value.isImporting) return
        importState.value = importState.value.copy(isImporting = true, notice = null)
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                importCoordinator.importTree(treeUri)
            }
            val notice = when (result) {
                is ImportResult.Imported ->
                    if (result.metadataWarning) LibraryNotice.MetadataFallback else null
                is ImportResult.Failed -> LibraryNotice.ImportFailed(result.reason)
            }
            importState.value = ImportState(isImporting = false, notice = notice)
        }
    }

    fun dismissNotice() {
        importState.value = importState.value.copy(notice = null)
    }

    /**
     * A30 §1 principle 3 (S4b/B3): a cover that fails to decode or open is not proof that
     * the comic is gone -- it is a decode error, a busy provider, or an access problem, and
     * any of those used to delete the tile from the shelf (and from every folder count) by
     * writing UNAVAILABLE straight into the catalog. The shelf now keeps the tile with its
     * placeholder; only a rescan's reconciliation, or the reader proving the file is gone,
     * changes a location's state.
     */
    fun onCoverOpenFailed(itemId: Long) {
        // Intentionally inert: kept so the shelf can keep reporting cover failures if a
        // future surface needs them, without letting one back into the catalog.
    }

    class Factory(
        private val repository: LibraryRepository,
        private val importCoordinator: ImportCoordinator,
        private val documentAccess: DocumentAccess,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            check(modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return LibraryViewModel(
                repository,
                importCoordinator,
                documentAccess,
                ioDispatcher,
            ) as T
        }
    }

    private data class ImportState(
        val isImporting: Boolean = false,
        val notice: LibraryNotice? = null,
    )
}
