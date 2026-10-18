package com.easemic.app.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.easemic.app.data.LibraryRepository
import com.easemic.app.feature.download.isCatalogDownloadRow
import com.easemic.app.model.GrantKind
import com.easemic.app.model.LibraryItemId
import com.easemic.app.model.PageRef
import com.easemic.app.model.ReadingUnitId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Ready(
        val itemId: Long,
        val readingUnitId: ReadingUnitId,
        val title: String,
        val pages: List<PageRef>,
        val initialOrdinal: Int,
        val sourceGrantKind: GrantKind,
        val isIncomplete: Boolean = false,
        /**
         * A36: the pages came from a catalog download, not a watched folder.
         * Decides which account of [isIncomplete] the reader may give — there
         * is no folder to rescan for a download.
         *
         * Review N3: read off the row via
         * [com.easemic.app.feature.download.isCatalogDownloadRow],
         * which is the SAME call the Detail's incomplete block makes about the
         * same [com.easemic.app.model.LibraryItem] — one
         * definition, so the reader and the Detail can never disagree about
         * which kind of incomplete a comic is.
         */
        val isCatalogDownload: Boolean = false,
        val problem: ReaderPageProblem? = null,
    ) : ReaderUiState

    data class Failed(
        val title: String,
        val problem: ReaderPageProblem,
        val sourceGrantKind: GrantKind,
    ) : ReaderUiState

    data class Unavailable(
        val sourceGrantKind: GrantKind = GrantKind.TREE,
        val reason: ReaderUnavailableReason = ReaderUnavailableReason.FILES_GONE,
    ) : ReaderUiState
}

/**
 * A30 §2.4 (S4b/G2): why the reader cannot show this comic. Only [FILES_GONE] exits to the
 * shelf with "This comic's files are no longer on the phone." -- [ACCESS_LOST] is an access
 * problem, so it keeps the reader open on the Folders recovery instead of claiming the
 * files left the phone.
 */
enum class ReaderUnavailableReason {
    FILES_GONE,
    ACCESS_LOST,
}

enum class ReaderPageFailure {
    REAUTHORIZE,
    PROVIDER_UNAVAILABLE,

    /**
     * A30 §2.4 (S4b/B4): the comic's file is no longer on the phone. The reader does not
     * argue with that -- it records the location as MISSING and leaves; the shelf states it
     * once and the tile is gone on the next frame.
     */
    FILES_GONE,
    SOURCE_CHANGED,
    INSUFFICIENT_SPACE,
    UNSUPPORTED_PAGE,
    RENDER_FAILED,
}

data class ReaderPageProblem(
    val page: PageRef,
    val failure: ReaderPageFailure,
)

class ReaderViewModel(
    private val itemId: Long,
    private val repository: LibraryRepository,
    // F1: authoritative for this VM instance's whole life -- see the uiState
    // else-branch. Never burned, so re-binding the same reader route re-seeks here.
    private val initialOrdinalOverride: Int? = null,
    private val readingUnitId: ReadingUnitId? = null,
    private val chapterContext: ReaderChapterContext? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private var seededLastSettledPage = false
    private var lastSettledPage: PageRef? = null
    private var chapterCompletionWritten = false
    private val activeProblem = MutableStateFlow<ReaderPageProblem?>(null)
    private val progressMutex = Mutex()
    private val observedItem = (readingUnitId?.let { selectedUnitId ->
        repository.observeItemAtUnit(LibraryItemId(itemId), selectedUnitId)
    } ?: repository.observeItem(LibraryItemId(itemId)))
        .onEach { item ->
            val problem = activeProblem.value
            if (
                problem != null &&
                problem.failure.clearsWhenTheComicComesBack() &&
                (
                    item == null ||
                        item.pages.none { it.sameContentIdentity(problem.page) } ||
                        item.isAvailable
                )
            ) {
                activeProblem.compareAndSet(problem, null)
            }
        }

    val uiState: StateFlow<ReaderUiState> = combine(
        observedItem,
        activeProblem,
    ) { item, problem ->
        val currentProblem = problem?.takeIf { candidate ->
            item?.pages?.any { it.sameContentIdentity(candidate.page) } == true
        }
        if (item == null || item.pages.isEmpty()) {
            ReaderUiState.Unavailable()
        } else if (currentProblem?.failure == ReaderPageFailure.FILES_GONE) {
            // A30 §2.4: no overlay, no Retry -- ReaderScreen turns Unavailable into the
            // single shelf line "This comic's files are no longer on the phone."
            ReaderUiState.Unavailable(sourceGrantKind = item.sourceGrantKind)
        } else if (item.sourceAccessLost) {
            // S4b/G2: the folder, not the file. Nothing in the catalog says these pages
            // moved, so the reader must not claim they are gone.
            ReaderUiState.Unavailable(
                sourceGrantKind = item.sourceGrantKind,
                reason = ReaderUnavailableReason.ACCESS_LOST,
            )
        } else if (
            currentProblem != null &&
            currentProblem.failure.isDurableLocationFailure()
        ) {
            ReaderUiState.Failed(
                title = item.title,
                problem = currentProblem,
                sourceGrantKind = item.sourceGrantKind,
            )
        } else if (!item.isAvailable) {
            ReaderUiState.Unavailable(sourceGrantKind = item.sourceGrantKind)
        } else {
            // F1: honour an EXPLICIT open ordinal every time this VM is (re)bound to
            // its route -- do NOT burn it. The reader route keys a VM by
            // item, ordinal, and (for chapters) unit/context, so reader-<id>-0 minted by "Read" (unread) is the
            // very instance a later first-preview-cell tap reuses; a spent override
            // would resume at progress (the A32 acceptance FAIL) instead of page 1.
            // A VM opened with no explicit ordinal (override == null) still resumes
            // at progressOrdinal. lastSettledPage is seeded once; onSettledPage owns
            // it thereafter.
            val savedOrdinal =
                (initialOrdinalOverride ?: item.progressOrdinal).coerceIn(item.pages.indices)
            if (!seededLastSettledPage) {
                lastSettledPage = item.pages[savedOrdinal]
                seededLastSettledPage = true
            }
            ReaderUiState.Ready(
                itemId = item.id.value,
                readingUnitId = item.readingUnitId,
                title = item.title,
                pages = item.pages,
                initialOrdinal = savedOrdinal,
                sourceGrantKind = item.sourceGrantKind,
                isIncomplete = item.isIncomplete,
                isCatalogDownload = item.isCatalogDownloadRow(),
                problem = currentProblem,
            )
        }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ReaderUiState.Loading,
        )

    fun onSettledPage(page: PageRef) {
        viewModelScope.launch {
            progressMutex.withLock {
                val item = observedItem.first()
                    ?: return@withLock
                if (item.pages.none { it.sameContentIdentity(page) }) {
                    return@withLock
                }
                val samePage = lastSettledPage?.sameContentIdentity(page) == true
                val terminalOrdinal = item.pages.maxOfOrNull { it.ordinal }
                val isTerminalPage = page.ordinal == terminalOrdinal
                if (
                    samePage &&
                    (chapterContext == null || !isTerminalPage || chapterCompletionWritten)
                ) {
                    if (activeProblem.value?.page?.sameContentIdentity(page) == true) {
                        activeProblem.value = null
                    }
                    return@withLock
                }
                val updated = if (chapterContext != null) {
                    repository.updateChapterProgressIfCurrent(
                        readingUnitId = item.readingUnitId,
                        revisionId = page.revisionId,
                        locationId = page.locationId,
                        ordinal = page.ordinal,
                        completed = isTerminalPage,
                        lastReadAtMillis = nowMs(),
                    )
                } else {
                    repository.updateProgressIfCurrent(
                        readingUnitId = item.readingUnitId,
                        revisionId = page.revisionId,
                        locationId = page.locationId,
                        ordinal = page.ordinal,
                    )
                }
                if (updated) {
                    lastSettledPage = page
                    if (chapterContext != null && isTerminalPage) {
                        chapterCompletionWritten = true
                    }
                    activeProblem.value = null
                }
            }
        }
    }

    fun onPageOpenFailed(page: PageRef, failure: ReaderPageFailure) {
        viewModelScope.launch {
            val item = observedItem.first()
            if (item == null || item.pages.none { it.sameContentIdentity(page) }) {
                return@launch
            }
            if (failure == ReaderPageFailure.REAUTHORIZE) {
                // A30 principle: never write location state because of an ACCESS problem.
                // Only the source flips; ShelfVisibility hides its comics, Detail and the
                // Reader gate on it, and every location row stays exactly as it was.
                if (
                    repository.markSourceAccessLostIfCurrent(
                        readingUnitId = item.readingUnitId,
                        revisionId = page.revisionId,
                        locationId = page.locationId,
                    )
                ) {
                    activeProblem.value = ReaderPageProblem(page, failure)
                }
            } else if (failure == ReaderPageFailure.FILES_GONE) {
                // Durable, and durably *missing*: MISSING (not UNAVAILABLE) is the state a
                // rescan restores and the structural signature re-links, so the comic comes
                // back by itself when the file does.
                repository.markLocationMissingIfCurrent(
                    readingUnitId = item.readingUnitId,
                    revisionId = page.revisionId,
                    locationId = page.locationId,
                )
                activeProblem.value = ReaderPageProblem(page, failure)
            } else if (failure.isDurableLocationFailure()) {
                val updated = repository.markLocationUnavailableIfCurrent(
                    readingUnitId = item.readingUnitId,
                    revisionId = page.revisionId,
                    locationId = page.locationId,
                    requireSourceReauthorization = failure == ReaderPageFailure.REAUTHORIZE,
                )
                if (updated) {
                    activeProblem.value = ReaderPageProblem(page, failure)
                }
            } else {
                activeProblem.value = ReaderPageProblem(page, failure)
            }
        }
    }

    class Factory(
        private val itemId: Long,
        private val repository: LibraryRepository,
        private val initialOrdinal: Int? = null,
        private val readingUnitId: ReadingUnitId? = null,
        private val chapterContext: ReaderChapterContext? = null,
        private val nowMs: () -> Long = System::currentTimeMillis,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            check(modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return ReaderViewModel(
                itemId = itemId,
                repository = repository,
                initialOrdinalOverride = initialOrdinal,
                readingUnitId = readingUnitId,
                chapterContext = chapterContext,
                nowMs = nowMs,
            ) as T
        }
    }
}

internal fun PageRef.sameContentIdentity(other: PageRef): Boolean =
    sourceId == other.sourceId &&
        locationId == other.locationId &&
        revisionId == other.revisionId &&
        ordinal == other.ordinal &&
        locatorKind == other.locatorKind

/** Only a PROVEN absence writes location state; REAUTHORIZE is handled as a source flip. */
private fun ReaderPageFailure.isDurableLocationFailure(): Boolean =
    this == ReaderPageFailure.SOURCE_CHANGED

/** Every failure that a returning (or re-authorized) comic should silently clear. */
private fun ReaderPageFailure.clearsWhenTheComicComesBack(): Boolean =
    isDurableLocationFailure() ||
        this == ReaderPageFailure.FILES_GONE ||
        this == ReaderPageFailure.REAUTHORIZE
