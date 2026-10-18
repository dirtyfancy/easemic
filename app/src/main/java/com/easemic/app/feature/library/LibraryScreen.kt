package com.easemic.app.feature.library

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.easemic.app.feature.browse.CloudCollectionFeedMapper
import com.easemic.app.feature.download.serializedSeriesLedgerKey
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.easemic.app.feature.browse.BrowseCoverThumb
import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.ComicTitleCleaner
import com.easemic.app.feature.browse.RemoteReadingProgress
import com.easemic.app.feature.browse.QUIET_SHELF_COVER_HEIGHT_DP
import com.easemic.app.feature.browse.QUIET_SHELF_COVER_WIDTH_DP
import com.easemic.app.feature.bridge.CloudSignInCopy
import com.easemic.app.feature.collect.CollectedBookmark
import com.easemic.app.feature.providers.OpdsProviderConfig
import com.easemic.app.feature.source.sourceDisplayNameFor
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.importing.ImportFailure
import com.easemic.app.model.LibraryItem
import com.easemic.app.model.ScanRejection
import com.easemic.app.storage.buildPageImageRequest
import com.easemic.app.ui.LibraryViewMode
import com.easemic.app.ui.library.LibraryTileBadges
import com.easemic.app.ui.library.ShelfBadgeFacts
import com.easemic.app.ui.library.ShelfPresence
import com.easemic.app.model.ShelfDownloadState
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.ui.theme.QuietShelfColors
import com.easemic.app.ui.theme.QuietShelfShimmer
import com.easemic.app.ui.theme.quietShelfPalette
import com.easemic.app.ui.components.PclButton
import com.easemic.app.ui.components.PclOutlinedButton
import com.easemic.app.ui.components.BrokenCoverPlaceholder
import com.easemic.app.ui.components.QuietIndeterminateProgressBar
import com.easemic.app.ui.components.QuietProgressBar
import com.easemic.app.ui.theme.PclSpacing
import com.easemic.app.ui.fastscroll.FastScroller

private const val COVER_ASPECT_RATIO = 2f / 3f
private const val SKELETON_PLACEHOLDER_COUNT = 4
private val ListCoverShape = RoundedCornerShape(9.dp)
private val ListRowShape = RoundedCornerShape(14.dp)
private val GridCardInnerPadding = PclSpacing.ExtraSmall
private const val GridScrimHeightFraction = 0.35f
private const val GridScrimAlpha = 0.78f
private val ShelfBottomClearance = PclSpacing.ExtraSmall
private val GridGutterHorizontal = PclSpacing.ExtraSmall
private val GridGutterVertical = PclSpacing.Small

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    viewMode: LibraryViewMode = LibraryViewMode.GRID,
    onOpenImport: () -> Unit,
    onOpenItem: (Long) -> Unit,
    onCoverOpenFailed: (Long) -> Unit,
    onDismissNotice: () -> Unit,
    /**
     * Task 4 (quiet-shelf): header chrome callbacks. The Library tab no longer
     * renders a fake search field or the segment chip row; instead the header
     * exposes affordances that Hand the actual search/filter UI (owned by the
     * ViewModel / later tasks) over to a parent. Defaults keep existing
     * callers and connected tests compiling.
     */
    onOpenSearch: () -> Unit = {},
    onOpenFilter: () -> Unit = {},
    /**
     * Task 5 (quiet-shelf): in-place search mode. When [LibraryUiState.searchMode]
     * is true the header swaps to back-arrow + live query field; [onCloseSearch]
     * backs out (and clears the query) and [onSearchQueryChange] streams edits.
     * Defaults keep existing callers compiling.
     */
    onCloseSearch: () -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    /**
     * Task 6 (quiet-shelf): the filter affordance opens a modal bottom sheet
     * with multi-select shelf-state rows and live counts. Filter selections
     * stream out through [onFilterChange] (production binds
     * [LibraryViewModel.setFilter]).
     */
    onFilterChange: (LibraryStateFilter) -> Unit = {},
    collectedBookmarkIds: Set<String> = emptySet(),
    collectedBookmarks: List<CollectedBookmark> = emptyList(),
    onOpenCollected: (CollectedBookmark) -> Unit = {},
    cloudCollectionItems: List<BrowseFeedItem> = emptyList(),
    /**
     * Task 7 (quiet-shelf): badge model inputs. [cloudBound] is the app's
     * R2-bound signal (production: LibraryCloudReadiness / bound bucket label);
     * [cloudUnreachable] reuses the cloud-import strip's blocked signal. Both
     * default to the unbound world: hearts only, no cloud glyphs.
     */
    cloudBound: Boolean = false,
    cloudUnreachable: Boolean = false,
    /** #D16: the bound drive refused the saved sign-in; the banner says that instead of "unreachable". */
    cloudSignInRejected: Boolean = false,
    cloudCollectionReady: Boolean = false,
    cloudCollectionBlocked: String? = null,
    onOpenCloudCollection: (BrowseFeedItem) -> Unit = {},
    opdsLibraries: List<OpdsProviderConfig> = emptyList(),
    opdsShelfItems: List<BrowseFeedItem> = emptyList(),
    opdsShelfLoading: Boolean = false,
    opdsShelfError: String? = null,
    onSelectOpds: (String) -> Unit = {},
    onOpenOpdsItem: (BrowseFeedItem) -> Unit = {},
    onOpenOpdsFolder: (String) -> Unit = {},
    /**
     * Cloud import progress strip (loyal-pipelines Slice B §2, live updates).
     * Additive/optional — every default keeps the strip hidden, so existing
     * callers (and androidTest call sites) are unaffected. [cloudImportStateExists]
     * is false until hydration has ever checkpointed for this bucket;
     * [cloudImportTotalCards] null/non-positive renders an indeterminate strip.
     *
     * A63 (a): [cloudImportVisibleCards] is the current Cloud-mirror count
     * (the caller already holds `libraryCloudItems`); before A63 the params
     * reached this screen but nothing rendered them.
     */
    cloudImportStateExists: Boolean = false,
    cloudImportHydrationComplete: Boolean = false,
    cloudImportVisibleCards: Int = 0,
    cloudImportTotalCards: Int? = null,
    /**
     * A42 remainder: the whole stored remote-read progress map
     * (RemoteReadingProgressStore.all()). A collected/cloud tile whose comic
     * has a record renders the SAME quiet progress bar local rows have; the
     * default empty map keeps existing callers and tests unchanged.
     */
    remoteProgress: Map<String, RemoteReadingProgress> = emptyMap(),
    /** Quiet follow mark counts keyed by the serialized series' dedup key. */
    newChapterMarks: Map<String, Int> = emptyMap(),
    /** Same-device managed source-cover files for serialized remote cards; null until first resolved. */
    serializedSourceCoverUris: Map<SeriesIdentityV2, String>? = emptyMap(),
    /**
     * C9: dedup keys of every job the source delisted; a collected-only or
     * cloud tile whose key is here renders the slashed cloud. Default empty
     * keeps existing callers and tests unchanged.
     */
    delistedAcquisitionKeys: Set<String> = emptySet(),
    /**
     * A30 §2.1: the one transient line under the grid -- import outcome with
     * Undo, nothing-found, or the Reader's "files are gone" exit. Null renders
     * nothing; the caller clears it after 6 s.
     */
    shelfLine: ShelfLine? = null,
    onShelfLineAction: () -> Unit = {},
    /**
     * A56: hoisted scroll states so the Library tab's position survives route
     * changes (the root's `when (route)` disposes this branch on tab switch —
     * a screen-local state died with it, so Back meant the top). Defaults
     * follow BrowseScreen's `listState` convention: callers and tests that
     * don't care keep a screen-local scroll state, while the production root
     * passes its hoisted states. Grid and list are separate states, so a
     * view-mode switch keeps each layout's own position.
     */
    gridState: LazyGridState = rememberLazyGridState(),
    listState: LazyListState = rememberLazyListState(),
    updates: List<UpdateDayGroup> = emptyList(),
    updatesSelected: Boolean = false,
    onSelectUpdates: () -> Unit = {},
    onSelectLibrary: () -> Unit = {},
    onVisibleUpdateKeys: (List<String>) -> Unit = {},
    onForceRefreshUpdates: () -> Unit = {},
    onOpenUpdate: (UpdateRowPresentation) -> Unit = {},
    updatesRefreshing: Boolean = false,
    backgroundRefreshHint: String? = null,
    onOpenBackgroundRefreshHint: () -> Unit = {},
    onDismissBackgroundRefreshHint: () -> Unit = {},
) {
    var filterSheetOpen by remember { mutableStateOf(false) }
    // MEDIUM-3 (review): measure the FAB's on-screen footprint (its own
    // height plus the bottom margin it is padded with) so the shelf line
    // can be pushed up above it instead of risking an overlap. Plain
    // remember (not saveable): a stale pixel value across process death is
    // harmless, the next frame re-measures it before it is ever drawn on.
    var fabFootprintHeightPx by remember { mutableIntStateOf(0) }
    /**
     * S2 Task 3 (owner bug #1): the shelf merges local items with
     * collected-bookmark and cloud-mirror entries, deduped by gallery
     * acquisition key, so a resource that's been collected or mirrored to
     * the cloud (but never downloaded) still shows up on the Library shelf.
     */
    val cloudActiveKeys = remember(cloudCollectionItems) {
        cloudCollectionItems.mapNotNull { galleryDedupKey(it.upstreamAcquisitionUrl ?: it.acquisitionUrl) }.toSet()
    }
    val shelfEntries = remember(
        state.items,
        state.downloadAcquisitionByItemId,
        state.serializedSeriesLedgerKeyByItemId,
        collectedBookmarks,
        cloudCollectionItems,
        cloudActiveKeys,
        remoteProgress,
        newChapterMarks,
        delistedAcquisitionKeys,
        serializedSourceCoverUris,
    ) {
        LibraryShelfComposer.compose(
            localItems = state.items,
            localAcquisitionByItemId = state.downloadAcquisitionByItemId,
            localSerializedSeriesLedgerKeyByItemId = state.serializedSeriesLedgerKeyByItemId,
            bookmarks = collectedBookmarks,
            cloudCards = cloudCollectionItems,
            cloudActiveKeys = cloudActiveKeys,
            remoteProgress = remoteProgress,
            newChapterMarks = newChapterMarks,
            delistedKeys = delistedAcquisitionKeys,
            serializedSourceCoverUris = serializedSourceCoverUris,
        )
    }
    /**
     * Task 5 (quiet-shelf): the shelf is a single on-device surface. In-place
     * search mode layers a case-insensitive title `contains` over the shelf;
     * the count line and the grid both read from this same filtered list.
     * Task 6: [LibraryUiState.stateFilter] intersects on top of that via each
     * entry's [ShelfFacts] (same facts the filter sheet's counts use).
     */
    val shelfFilteredEntries = remember(shelfEntries, state.stateFilter) {
        shelfEntries.filter { it.matchesShelfFilter(state.stateFilter) }
    }
    val filteredEntries = remember(shelfFilteredEntries, state.searchMode, state.searchQuery) {
        if (state.searchMode && state.searchQuery.isNotBlank()) {
            shelfFilteredEntries.filter { it.displayTitle().contains(state.searchQuery, ignoreCase = true) }
        } else {
            shelfFilteredEntries
        }
    }
    /**
     * Bug fix (owner-reported 2026-09-04): [libraryShelfMode] is the single
     * decision point for which shelf body to render. `showEmpty` is derived
     * from it and now only means "never imported/collected/mirrored
     * anything" — a state filter or search query that matches zero shelf
     * entries no longer routes through the import empty state (which used to
     * swallow the header row, leaving no way to see or clear the filter).
     */
    val shelfMode = libraryShelfMode(
        shelfCount = shelfEntries.size,
        filteredCount = filteredEntries.size,
        filterActive = state.stateFilter.any,
        searchMode = state.searchMode,
        searchQueryBlank = state.searchQuery.isBlank(),
        isLoading = state.isLoading,
    )
    val showEmpty = shelfMode == LibraryShelfMode.EMPTY_LIBRARY
    /**
     * Audit F1 (§4): the query the cover captions highlight. Only live while
     * search mode is active; empty otherwise so non-search renders never pay
     * for or accidentally trigger the highlight path.
     */
    val highlightQuery = if (state.searchMode) state.searchQuery else ""
    /**
     * Audit F2 (§6): the quiet cloud-unreachable banner only makes sense in
     * the bound world — in the unbound world there is no cloud vocabulary at
     * all. No retry button; health lives in Settings → Cloud.
     */
    val showCloudUnreachableBanner = cloudBound && cloudUnreachable
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PclSpacing.Small),
        ) {
            Spacer(modifier = Modifier.height(PclSpacing.Small))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LibraryPaneSwitch(
                    updatesSelected = updatesSelected,
                    onSelectLibrary = onSelectLibrary,
                    onSelectUpdates = onSelectUpdates,
                )
                if (!updatesSelected && !showEmpty && !state.searchMode) {
                    Spacer(modifier = Modifier.weight(1f))
                    LibraryHeaderActions(
                        filterActive = state.stateFilter.any,
                        onOpenSearch = onOpenSearch,
                        onOpenFilter = { filterSheetOpen = true },
                    )
                }
            }
            Spacer(modifier = Modifier.height(PclSpacing.Small))
            if (updatesSelected) {
                UpdatesList(
                    groups = updates,
                    refreshing = updatesRefreshing,
                    onRefresh = onForceRefreshUpdates,
                    onVisibleUpdateKeys = onVisibleUpdateKeys,
                    onOpenUpdate = onOpenUpdate,
                    backgroundRefreshHint = backgroundRefreshHint,
                    onOpenBackgroundRefreshHint = onOpenBackgroundRefreshHint,
                    onDismissBackgroundRefreshHint = onDismissBackgroundRefreshHint,
                    modifier = Modifier.weight(1f),
                )
            } else {
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (!showEmpty && state.searchMode) {
                LibrarySearchHeaderRow(
                    // W3 (A38): the field echoes the RAW query so the
                    // typist never sees their own text lag the 250 ms
                    // filter debounce.
                    query = state.rawSearchQuery,
                    filterActive = state.stateFilter.any,
                    onQueryChange = onSearchQueryChange,
                    onCloseSearch = onCloseSearch,
                    onOpenFilter = { filterSheetOpen = true },
                )
                Spacer(modifier = Modifier.height(PclSpacing.Small))
            }
            if (state.searchMode) {
                Text(
                    AppStrings.inYourLibraryCount(filteredEntries.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = quietShelfPalette().muted,
                )
                Spacer(modifier = Modifier.height(PclSpacing.Small))
            }
            // Task 4: the segment/OPDS chip row is retired from the Library tab
            // (chips still live in LibraryChips for Settings reuse).
            // Task 7: the Grid/List toggle left the Library chrome too — the
            // shelf layout is now pushed in by the caller from the Settings
            // "Library layout" store (LibraryLayoutStore).
            state.notice?.let {
                LibraryNoticeCard(it, onDismissNotice)
                Spacer(modifier = Modifier.height(8.dp))
            }
            // A63 (a): the "Importing · N/M" strip. Pure render of
            // CloudImportProgressPresentation's decision — the presentation
            // object owns visibility/label/fraction, this decides only where
            // (above the shelf content, below the chrome — the same slot the
            // shelf line uses). Hidden entirely when not visible.
            val cloudImportProgress = CloudImportProgressPresentation.present(
                hasState = cloudImportStateExists,
                hydrationComplete = cloudImportHydrationComplete,
                visibleCards = cloudImportVisibleCards,
                totalCards = cloudImportTotalCards,
            )
            if (cloudImportProgress.visible) {
                LibraryCloudImportStrip(
                    label = cloudImportProgress.label,
                    fraction = cloudImportProgress.fraction,
                )
                Spacer(modifier = Modifier.height(PclSpacing.Small))
            }
            // #C8 audit MEDIUM: the fast scroller's track must end above the
            // "Import folder" FAB (same measured footprint the shelf line dodges),
            // or the thumb's last stretch — where a drag to the end lands — is
            // under the button.
            val scrollerBottomInset = if (!showEmpty && fabFootprintHeightPx > 0) {
                with(LocalDensity.current) { fabFootprintHeightPx.toDp() } + PclSpacing.ExtraSmall
            } else {
                0.dp
            }
            when (shelfMode) {
                LibraryShelfMode.EMPTY_LIBRARY -> LibraryEmptyState(
                    importing = state.isImporting,
                    onOpenImport = onOpenImport,
                )
                LibraryShelfMode.LOADING -> LibraryLoadingSkeleton(viewMode)
                LibraryShelfMode.NO_FILTER_MATCH -> LibraryNoMatchState(
                    text = AppStrings.noComicsMatchFilters,
                    actionLabel = AppStrings.clearFilters,
                    onAction = { onFilterChange(LibraryStateFilter()) },
                )
                LibraryShelfMode.NO_SEARCH_MATCH -> LibraryNoMatchState(
                    text = AppStrings.nothingMatchesQuery(state.searchQuery),
                    actionLabel = null,
                    onAction = {},
                )
                LibraryShelfMode.CONTENT -> when {
                    viewMode == LibraryViewMode.LIST -> LibraryListContent(
                        entries = filteredEntries,
                        listState = listState,
                        scrollerBottomInset = scrollerBottomInset,
                        onOpenItem = onOpenItem,
                        onCoverOpenFailed = onCoverOpenFailed,
                        onOpenCollected = onOpenCollected,
                        onOpenCloudCollection = onOpenCloudCollection,
                        highlightQuery = highlightQuery,
                        badgeInputs = LibraryBadgeInputs(
                            cloudBound = cloudBound,
                            cloudUnreachable = cloudUnreachable,
                        ),
                        modifier = if (showCloudUnreachableBanner) Modifier.weight(1f) else Modifier,
                    )
                    else -> LibraryGridContent(
                        entries = filteredEntries,
                        gridState = gridState,
                        scrollerBottomInset = scrollerBottomInset,
                        onOpenItem = onOpenItem,
                        onCoverOpenFailed = onCoverOpenFailed,
                        onOpenCollected = onOpenCollected,
                        onOpenCloudCollection = onOpenCloudCollection,
                        highlightQuery = highlightQuery,
                        badgeInputs = LibraryBadgeInputs(
                            cloudBound = cloudBound,
                            cloudUnreachable = cloudUnreachable,
                        ),
                        modifier = if (showCloudUnreachableBanner) Modifier.weight(1f) else Modifier,
                    )
                }
            }
            if (showCloudUnreachableBanner && !showEmpty) {
                Spacer(modifier = Modifier.height(PclSpacing.Small))
                LibraryCloudUnreachableBanner(signInRejected = cloudSignInRejected)
            }
            }
            }
        }
        if (!showEmpty && !updatesSelected) {
            QuietImportFab(
                enabled = !state.isImporting,
                importing = state.isImporting,
                onClick = onOpenImport,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .onGloballyPositioned { fabFootprintHeightPx = it.size.height }
                    .padding(end = PclSpacing.Medium, bottom = PclSpacing.Medium),
            )
        }
        if (shelfLine != null && !updatesSelected) {
            // MEDIUM-3 (review): the line must never paint over the FAB. When
            // the FAB is showing, stack the line above its measured footprint
            // (own height + own bottom margin) plus one more gap, instead of
            // the fixed end-padding dodge that only worked by accident.
            val fabFootprintDp = with(LocalDensity.current) { fabFootprintHeightPx.toDp() }
            val shelfLineBottomPadding = if (!showEmpty && fabFootprintHeightPx > 0) {
                fabFootprintDp + PclSpacing.Small
            } else {
                PclSpacing.Medium
            }
            QuietShelfLine(
                line = shelfLine,
                onAction = onShelfLineAction,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = PclSpacing.Small,
                        end = 96.dp,
                        bottom = shelfLineBottomPadding,
                    ),
            )
        }
    }
    if (filterSheetOpen) {
        onOpenFilter()
        LibraryFilterSheet(
            filter = state.stateFilter,
            counts = computeShelfCounts(shelfEntries),
            onFilterChange = onFilterChange,
            onDismiss = { filterSheetOpen = false },
        )
    }
}

@Composable
private fun LibraryPaneSwitch(
    updatesSelected: Boolean,
    onSelectLibrary: () -> Unit,
    onSelectUpdates: () -> Unit,
) {
    val palette = quietShelfPalette()
    Row(horizontalArrangement = Arrangement.spacedBy(PclSpacing.Medium)) {
        LibraryPaneTab(
            label = AppStrings.navLibrary,
            selected = !updatesSelected,
            tag = "library-library-tab",
            color = if (updatesSelected) palette.muted else palette.ink,
            onClick = onSelectLibrary,
        )
        LibraryPaneTab(
            label = AppStrings.updatesTab,
            selected = updatesSelected,
            tag = "library-updates-tab",
            color = if (updatesSelected) palette.ink else palette.muted,
            onClick = onSelectUpdates,
        )
    }
}

@Composable
private fun LibraryPaneTab(
    label: String,
    selected: Boolean,
    tag: String,
    color: Color,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        modifier = Modifier
            .testTag(tag)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .semantics { this.selected = selected }
            .padding(vertical = PclSpacing.ExtraSmall),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdatesList(
    groups: List<UpdateDayGroup>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onVisibleUpdateKeys: (List<String>) -> Unit,
    onOpenUpdate: (UpdateRowPresentation) -> Unit,
    modifier: Modifier = Modifier,
    backgroundRefreshHint: String? = null,
    onOpenBackgroundRefreshHint: () -> Unit = {},
    onDismissBackgroundRefreshHint: () -> Unit = {},
) {
    val palette = quietShelfPalette()
    val listState = rememberLazyListState()
    val rowKeys = remember(groups) {
        groups.flatMap { group -> group.rows.map { it.stableKey } }.toSet()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed, rowKeys) {
        if (!resumed) return@LaunchedEffect
        val sent = mutableSetOf<String>()
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                (info.key as? String)?.takeIf { it in rowKeys }
            }
        }.collectLatest { keys ->
            delay(500)
            val fresh = keys.filter { sent.add(it) }
            if (fresh.isNotEmpty()) onVisibleUpdateKeys(fresh)
        }
    }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier
            .fillMaxSize()
            .testTag("updates-pull-refresh"),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("updates-list"),
            verticalArrangement = Arrangement.spacedBy(PclSpacing.ExtraSmall),
        ) {
            if (backgroundRefreshHint != null) {
                item(key = "updates-background-hint") {
                    BackgroundRefreshHintLine(
                        text = backgroundRefreshHint,
                        onOpen = onOpenBackgroundRefreshHint,
                        onDismiss = onDismissBackgroundRefreshHint,
                    )
                }
            }
            if (groups.isEmpty() || groups.all { it.rows.isEmpty() }) {
                item(key = "updates-empty") {
                    Text(
                        AppStrings.noNewChapters,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.muted,
                        modifier = Modifier.padding(vertical = PclSpacing.Medium),
                    )
                }
            }
            groups.forEach { group ->
                item(key = "day:${group.label}") {
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.muted,
                        modifier = Modifier.padding(top = PclSpacing.Small, bottom = PclSpacing.Tiny),
                    )
                }
                items(items = group.rows, key = { it.stableKey }) { row ->
                    UpdateChapterCard(row = row, onOpen = onOpenUpdate)
                }
            }
        }
    }
}

@Composable
private fun BackgroundRefreshHintLine(
    text: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = quietShelfPalette()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("updates-background-hint")
            .padding(vertical = PclSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(PclSpacing.ExtraSmall),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = palette.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(PclSpacing.Medium)) {
            Text(
                "Open",
                style = MaterialTheme.typography.labelLarge,
                color = palette.ink,
                modifier = Modifier
                    .testTag("updates-background-hint-open")
                    .clickable(onClick = onOpen)
                    .padding(vertical = PclSpacing.ExtraSmall),
            )
            Text(
                "✕",
                style = MaterialTheme.typography.labelLarge,
                color = palette.muted,
                modifier = Modifier
                    .testTag("updates-background-hint-dismiss")
                    .clickable(onClick = onDismiss)
                    .semantics { contentDescription = "Dismiss" }
                    .padding(vertical = PclSpacing.ExtraSmall),
            )
        }
    }
}

@Composable
private fun UpdateChapterCard(
    row: UpdateRowPresentation,
    onOpen: (UpdateRowPresentation) -> Unit,
) {
    val palette = quietShelfPalette()
    val item = row.coverLibraryItem
    Surface(
        onClick = { onOpen(row) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("update-row-${row.chapterId}"),
        shape = ListRowShape,
        color = palette.card,
        border = BorderStroke(1.dp, palette.line),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(
                        width = QUIET_SHELF_COVER_WIDTH_DP.dp,
                        height = QUIET_SHELF_COVER_HEIGHT_DP.dp,
                    )
                    .clip(ListCoverShape),
            ) {
                when {
                    item != null -> LibraryCoverImage(
                        item = item,
                        onCoverOpenFailed = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                    !row.coverUrl.isNullOrBlank() -> AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(row.coverUrl).build(),
                        contentDescription = AppStrings.coverForTitle(row.seriesTitle),
                        modifier = Modifier
                            .fillMaxSize()
                            .background(palette.ink.copy(alpha = 0.08f)),
                        contentScale = ContentScale.Crop,
                    )
                    else -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(palette.ink.copy(alpha = 0.06f)),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = row.seriesTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.chapterLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val stateText = row.stateText
                if (stateText != null) {
                    Text(
                        text = AppStrings.chapterStateText(stateText),
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            stateText.startsWith("Saved") -> palette.statusOk
                            stateText.startsWith("Failed") || stateText.startsWith("Members") -> palette.statusWarn
                            stateText.startsWith("Removed") -> palette.statusWarn
                            else -> palette.muted
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(
    importing: Boolean,
    onOpenImport: () -> Unit,
) {
    val palette = quietShelfPalette()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PclSpacing.Large)
            .padding(bottom = ShelfBottomClearance),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 96.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.ink.copy(alpha = 0.06f))
                .drawBehind {
                    val stroke = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f),
                    )
                    drawRoundRect(
                        color = palette.ink.copy(alpha = 0.18f),
                        cornerRadius = CornerRadius(10.dp.toPx()),
                        style = stroke,
                    )
                },
        )
        Spacer(Modifier.height(12.dp))
        Text(AppStrings.noComicsYet, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            AppStrings.emptyLibraryHint,
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
        )
        Spacer(Modifier.height(14.dp))
        PclButton(
            onClick = onOpenImport,
            enabled = !importing,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            modifier = Modifier.semantics { contentDescription = AppStrings.importFolderA11y },
        ) {
            Text(AppStrings.importFolder, style = MaterialTheme.typography.labelLarge)
        }
        if (importing) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(
                modifier = Modifier
                    .size(32.dp)
                    .semantics { contentDescription = AppStrings.importingFolder },
            )
        }
    }
}

/**
 * Bug fix (owner-reported 2026-09-04): shown for [LibraryShelfMode.NO_FILTER_MATCH]
 * and [LibraryShelfMode.NO_SEARCH_MATCH] — a populated shelf whose active
 * state filter or search query matches nothing. Unlike [LibraryEmptyState]
 * this never replaces the header row (the caller only routes here once the
 * header is already known to be showing), so the filter/search affordance
 * that caused the empty result stays reachable. The filter case additionally
 * offers a one-tap way out via [actionLabel]/[onAction]; the search case has
 * none (backing out of search already clears the query).
 */
@Composable
private fun LibraryNoMatchState(
    text: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    val palette = quietShelfPalette()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PclSpacing.Large)
            .padding(bottom = ShelfBottomClearance)
            .testTag("library-no-match"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = palette.ink,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(14.dp))
            PclOutlinedButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier.testTag("library-clear-filters"),
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun QuietImportFab(
    enabled: Boolean,
    importing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PclButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(
            horizontal = PclSpacing.Small,
            vertical = PclSpacing.ExtraSmall,
        ),
        modifier = modifier
                .semantics { contentDescription = AppStrings.importFolderA11y },
    ) {
        if (importing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(16.dp)
                    .semantics { contentDescription = AppStrings.importingFolder },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(PclSpacing.ExtraSmall))
        }
        Text(AppStrings.importFolder, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Task 5 search-mode header: back arrow + live query field + the same filter
 * affordance (reachable during search, per spec). The query streams to the
 * caller through [onQueryChange]; the close affordance both leaves search mode
 * and clears the query (the caller owns the state).
 */
@Composable
private fun LibrarySearchHeaderRow(
    query: String,
    filterActive: Boolean,
    onQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onOpenFilter: () -> Unit,
) {
    val palette = quietShelfPalette()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onCloseSearch,
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = AppStrings.closeSearch },
        ) {
            BackGlyph()
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleSmall.copy(color = palette.ink),
            cursorBrush = SolidColor(palette.statusOk),
            modifier = Modifier
                .weight(1f)
                .testTag("library-search-field"),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            tr("Search your comics", "搜索你的漫画", "搜尋你的漫畫", "マンガを検索"),
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.muted,
                        )
                    }
                    inner()
                }
            },
        )
        IconButton(
            onClick = onOpenFilter,
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = AppStrings.filterLibrary },
        ) {
            Box {
                FilterGlyph()
                if (filterActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(quietShelfPalette().statusOk)
                            .semantics { contentDescription = AppStrings.filterActive },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryHeaderActions(
    filterActive: Boolean,
    onOpenSearch: () -> Unit,
    onOpenFilter: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onOpenSearch,
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = AppStrings.searchLibrary },
        ) {
            SearchGlyph()
        }
        IconButton(
            onClick = onOpenFilter,
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = AppStrings.filterLibrary },
        ) {
            Box {
                FilterGlyph()
                if (filterActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(quietShelfPalette().statusOk)
                            .semantics { contentDescription = AppStrings.filterActive },
                    )
                }
            }
        }
    }
}

@Composable
private fun BackGlyph() {
    val ink = quietShelfPalette().ink
    Canvas(modifier = Modifier.size(18.dp)) {
        val strokeWidth = 1.75.dp.toPx()
        val mid = size.height / 2f
        // shaft
        drawLine(
            color = ink,
            start = androidx.compose.ui.geometry.Offset(3.dp.toPx(), mid),
            end = androidx.compose.ui.geometry.Offset(size.width - 1.dp.toPx(), mid),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        // arrowhead
        val barb = 5.5.dp.toPx()
        drawLine(
            color = ink,
            start = androidx.compose.ui.geometry.Offset(3.dp.toPx() + barb, mid - barb),
            end = androidx.compose.ui.geometry.Offset(3.dp.toPx(), mid),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = ink,
            start = androidx.compose.ui.geometry.Offset(3.dp.toPx() + barb, mid + barb),
            end = androidx.compose.ui.geometry.Offset(3.dp.toPx(), mid),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

@Composable
private fun SearchGlyph() {
    val ink = quietShelfPalette().ink
    Box(modifier = Modifier.size(18.dp)) {
        Box(
            modifier = Modifier
                .size(11.dp)
                .clip(RoundedCornerShape(50))
                .border(1.75.dp, ink, RoundedCornerShape(50)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 1.5.dp, end = 1.5.dp)
                .width(6.5.dp)
                .height(1.75.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(ink),
        )
    }
}

@Composable
private fun FilterGlyph() {
    val ink = quietShelfPalette().ink
    Column(
        modifier = Modifier.width(18.dp),
        verticalArrangement = Arrangement.spacedBy(3.5.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.75.dp)
                .background(ink, RoundedCornerShape(1.dp)),
        )
        Box(
            modifier = Modifier
                .width(9.dp)
                .height(1.75.dp)
                .background(ink, RoundedCornerShape(1.dp)),
        )
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .height(4.5.dp)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(1.dp))
                .background(ink),
        )
    }
}

/**
 * Task 7 (quiet-shelf): the badge input bundle a tile's cell needs. Kept as a
 * small value object so grid and list feed [LibraryTileBadges] from identical
 * facts.
 */
private class LibraryBadgeInputs(
    val cloudBound: Boolean,
    val cloudUnreachable: Boolean,
)

/**
 * S2 Task 3 follow-up (owner bug #1, id-space gap): the Local tile's glyph is
 * driven off the merged shelf entry's own [ShelfBadges] — the same truth the
 * filter and counts already agree on — instead of comparing `item.id.value`
 * against `collectedBookmarkIds`/`cloudItemIds` (a Room Long vs. provider
 * string compare that never matched, so a downloaded-and-collected tile never
 * showed its badge). Mirrors [LibraryRemoteTileBadges]'s presence mapping.
 */
@Composable
private fun LibraryTileBadgesFor(
    item: LibraryItem,
    badges: ShelfBadges,
    inputs: LibraryBadgeInputs,
    modifier: Modifier = Modifier,
    onScrim: Boolean = false,
    maxGlyphs: Int = Int.MAX_VALUE,
) {
    val presence = when {
        badges.inCloud -> ShelfPresence.Cloud
        badges.collected -> ShelfPresence.Bookmarked
        else -> null
    }
    // W3 (A38): the shelf projection carries only the cover page plus a
    // SQL-precomputed download count (`downloadedPages`), so the badge facts
    // read the precomputed field when present; -1 = full graph read (Detail/
    // Reader/tests) — derive from pages exactly as before.
    val downloaded = if (item.downloadedPages >= 0) {
        item.downloadedPages > 0
    } else {
        item.pages.any { isCatalogDownloadUri(it.documentUri) }
    }
    val hasLocalPages = if (item.downloadedPages >= 0) {
        item.pageCount > 0
    } else {
        item.pages.isNotEmpty()
    }
    // A32 §4: a downloaded-but-uncollected tile used to return here and render
    // nothing, so it was visually identical to an import. A42: an import is no
    // longer badge-less either — it carries the folder glyph, so "imported" is
    // stated in the same icon vocabulary as collected/downloaded instead of
    // being the absence of one.
    LibraryTileBadges(
        facts = ShelfBadgeFacts(
            cloudBound = inputs.cloudBound,
            presence = presence,
            downloaded = downloaded,
            imported = !downloaded && hasLocalPages,
            // A63: the W3 projection's download-job axis (WAIT/DOWNLOAD ⇒
            // hollow arrow, FINISH/SEALED-with-pages ⇒ filled). -1-page
            // constructions (Detail/Reader/fakes) carry NONE and keep the
            // legacy boolean as before.
            downloadState = item.downloadState,
            // #C9: the cloud-off badge rule has existed and been tested since
            // A42, but this call site passed the default `false`, so a comic
            // the source had delisted looked identical to a healthy one. The
            // fact now comes from the projection's GONE job.
            delistedAtSource = item.delistedAtSource,
        ),
        unreachable = inputs.cloudUnreachable,
        onScrim = onScrim,
        maxGlyphs = maxGlyphs,
        modifier = modifier,
    )
}

/**
 * Audit F2 (quiet-shelf §6): a quiet, non-interactive banner at the bottom of
 * the shelf when the bound cloud bucket is unreachable. No retry button —
 * reachability is a state, not an error; health lives in More → Cloud library.
 * Copy locked per §8; #D16's [signInRejected] variant ("the drive refused the
 * saved sign-in") approved by the owner on 2026-09-30.
 */
@Composable
private fun LibraryCloudUnreachableBanner(signInRejected: Boolean) {
    val palette = quietShelfPalette()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (signInRejected) "library-cloud-signin-banner" else "library-cloud-unreachable-banner"),
        shape = RoundedCornerShape(14.dp),
        color = palette.card,
        border = BorderStroke(1.dp, palette.line),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                if (signInRejected) {
                    CloudSignInCopy.BANNER_TITLE
                } else {
                    "Cloud unreachable — Queued tiles stay as hollow clouds and keep " +
                            "reading via the official source. Downloaded tiles are unaffected."
                },
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (signInRejected) {
                    CloudSignInCopy.BANNER_HINT
                } else {
                    "Tiles already on this device still open. Health lives in More → Cloud library."
                },
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
    }
}

/** Stable diffing key shared by every list/grid rendering of the merged shelf. */
private fun shelfEntryKey(entry: ShelfEntry): String = when (entry) {
    is ShelfEntry.Local -> "local:${entry.item.id.value}"
    is ShelfEntry.Remote -> "remote:" + if (CloudCollectionFeedMapper.isSeries(entry.feedItem)) {
        serializedSeriesLedgerKey(entry.feedItem.providerId, entry.feedItem.id)
    } else {
        galleryDedupKey(entry.feedItem.upstreamAcquisitionUrl ?: entry.feedItem.acquisitionUrl)
            ?: entry.feedItem.id
    }
}

/**
 * S2 Task 3 (owner bug #1): opens a [ShelfEntry.Remote] tile via whichever
 * handler matches its origin. Collected-only entries reconstruct a
 * [CollectedBookmark] from the feed item the composer built it from — lossless,
 * since [LibraryShelfComposer] derives that feed item from a real bookmark in
 * the first place. Cloud-only entries open straight through the cloud handler.
 */
private fun openShelfRemoteEntry(
    entry: ShelfEntry.Remote,
    onOpenCollected: (CollectedBookmark) -> Unit,
    onOpenCloudCollection: (BrowseFeedItem) -> Unit,
) {
    when (entry.origin) {
        RemoteOrigin.COLLECTED -> onOpenCollected(
            CollectedBookmark(
                id = entry.feedItem.id,
                title = entry.feedItem.title,
                acquisitionUrl = entry.feedItem.acquisitionUrl ?: "",
                providerId = entry.feedItem.providerId,
                coverUrl = entry.feedItem.coverUrl,
            ),
        )
        RemoteOrigin.CLOUD -> onOpenCloudCollection(entry.feedItem)
    }
}

@Composable
private fun LibraryGridContent(
    entries: List<ShelfEntry>,
    // A56: required (BrowseFeedList convention) — only LibraryScreen's own
    // dispatch hands this in, from its hoisted-or-defaulted param above.
    gridState: LazyGridState,
    onOpenItem: (Long) -> Unit,
    onCoverOpenFailed: (Long) -> Unit,
    onOpenCollected: (CollectedBookmark) -> Unit,
    onOpenCloudCollection: (BrowseFeedItem) -> Unit,
    badgeInputs: LibraryBadgeInputs,
    modifier: Modifier = Modifier,
    highlightQuery: String = "",
    scrollerBottomInset: Dp = 0.dp,
) {
    Box(modifier = modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ShelfBottomClearance),
            horizontalArrangement = Arrangement.spacedBy(GridGutterHorizontal),
            verticalArrangement = Arrangement.spacedBy(GridGutterVertical),
        ) {
            gridItems(entries, key = ::shelfEntryKey) { entry ->
                when (entry) {
                    is ShelfEntry.Local -> Box {
                        LibraryItemCard(
                            item = entry.item,
                            onOpenItem = onOpenItem,
                            onCoverOpenFailed = onCoverOpenFailed,
                            highlightQuery = highlightQuery,
                            badges = entry.badges,
                            badgeInputs = badgeInputs,
                        )
                        NewChapterChip(
                            count = entry.newChapterCount,
                            testTag = "library-new-chapters-${entry.item.id.value}",
                            modifier = Modifier.align(Alignment.TopStart).padding(PclSpacing.Tiny),
                        )
                    }
                    is ShelfEntry.Remote -> LibraryRemoteGridCard(
                        entry = entry,
                        cloudBound = badgeInputs.cloudBound,
                        cloudUnreachable = badgeInputs.cloudUnreachable,
                        highlightQuery = highlightQuery,
                        onClick = { openShelfRemoteEntry(entry, onOpenCollected, onOpenCloudCollection) },
                    )
                }
            }
        }
        FastScroller(
            state = gridState,
            knownTotalItems = entries.size,
            showPositionBubble = true,
            modifier = Modifier.align(Alignment.TopEnd).padding(bottom = scrollerBottomInset),
        )
    }
}

@Composable
private fun LibraryListContent(
    entries: List<ShelfEntry>,
    // A56: required (BrowseFeedList convention) — see LibraryGridContent.
    listState: LazyListState,
    onOpenItem: (Long) -> Unit,
    onCoverOpenFailed: (Long) -> Unit,
    onOpenCollected: (CollectedBookmark) -> Unit,
    onOpenCloudCollection: (BrowseFeedItem) -> Unit,
    badgeInputs: LibraryBadgeInputs,
    modifier: Modifier = Modifier,
    highlightQuery: String = "",
    scrollerBottomInset: Dp = 0.dp,
) {
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ShelfBottomClearance),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(entries, key = ::shelfEntryKey) { entry ->
                when (entry) {
                    is ShelfEntry.Local -> Box {
                        LibraryListRow(
                            item = entry.item,
                            onOpenItem = onOpenItem,
                            onCoverOpenFailed = onCoverOpenFailed,
                            highlightQuery = highlightQuery,
                            badges = entry.badges,
                            badgeInputs = badgeInputs,
                        )
                        NewChapterChip(
                            count = entry.newChapterCount,
                            testTag = "library-new-chapters-${entry.item.id.value}",
                            modifier = Modifier.align(Alignment.TopEnd).padding(PclSpacing.Tiny),
                        )
                    }
                    is ShelfEntry.Remote -> LibraryRemoteListRow(
                        entry = entry,
                        cloudBound = badgeInputs.cloudBound,
                        cloudUnreachable = badgeInputs.cloudUnreachable,
                        highlightQuery = highlightQuery,
                        onClick = { openShelfRemoteEntry(entry, onOpenCollected, onOpenCloudCollection) },
                    )
                }
            }
        }
        FastScroller(
            state = listState,
            knownTotalItems = entries.size,
            showPositionBubble = true,
            modifier = Modifier.align(Alignment.TopEnd).padding(bottom = scrollerBottomInset),
        )
    }
}

private enum class LibraryCoverStatus {
    INCOMPLETE,
}

/**
 * A30 spec 2.1: the incomplete badge is the only status a tile still carries.
 * A missing original is not stated per tile -- the item simply leaves the shelf.
 */
private fun libraryCoverStatus(item: LibraryItem): LibraryCoverStatus? = when {
    item.isIncomplete -> LibraryCoverStatus.INCOMPLETE
    else -> null
}

@Composable
private fun LibraryItemCard(
    item: LibraryItem,
    onOpenItem: (Long) -> Unit,
    onCoverOpenFailed: (Long) -> Unit,
    highlightQuery: String = "",
    badges: ShelfBadges,
    badgeInputs: LibraryBadgeInputs,
) {
    // #D1: the cover is the tile. No bordered card, no body padding.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("library-grid-card"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_ASPECT_RATIO)
                .clip(ListCoverShape)
                .testTag("library-grid-cover")
                .then(
                    if (item.isAvailable) {
                        Modifier.clickable { onOpenItem(item.id.value) }
                    } else {
                        Modifier
                    },
                ),
        ) {
            LibraryCoverImage(
                item = item,
                onCoverOpenFailed = onCoverOpenFailed,
                modifier = Modifier.fillMaxSize(),
            )
            libraryCoverStatus(item)?.let { status ->
                LibraryCoverStatusChip(
                    status = status,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(PclSpacing.Tiny),
                )
            }
            LibraryCoverCaption(
                item = item,
                searchQuery = highlightQuery,
            ) {
                LibraryTileBadgesFor(
                    item = item,
                    badges = badges,
                    inputs = badgeInputs,
                    onScrim = true,
                    maxGlyphs = 2,
                )
            }
        }
    }
}

@Composable
private fun LibraryCoverStatusChip(
    status: LibraryCoverStatus,
    modifier: Modifier = Modifier,
) {
    val contentColor = when (status) {
        LibraryCoverStatus.INCOMPLETE -> QuietShelfColors.Light.statusWarn
    }
    val label = when (status) {
        LibraryCoverStatus.INCOMPLETE -> "Incomplete"
    }
    Surface(
        color = Color.White.copy(alpha = 0.9f),
        contentColor = contentColor,
        shape = RoundedCornerShape(50),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun BoxScope.LibraryCoverCaption(
    item: LibraryItem,
    searchQuery: String = "",
    badges: @Composable () -> Unit,
) {
    val position = if (item.progressOrdinal > 0 && item.pageCount > 0) {
        "${item.progressOrdinal + 1} / ${item.pageCount}"
    } else {
        null
    }
    LibraryGridCaption(
        title = highlightTitleMatch(item.title, searchQuery),
        showProgress = item.pageCount > 0,
        progress = { libraryProgressFraction(item) },
        progressTag = null,
        positionText = position,
        badges = badges,
    )
}

/**
 * Bottom ~35% of a grid cover: scrim, white title, status row, and the thin
 * progress bar on the cover's bottom edge.
 */
@Composable
private fun BoxScope.LibraryGridCaption(
    title: AnnotatedString,
    showProgress: Boolean,
    progress: () -> Float,
    progressTag: String?,
    positionText: String?,
    badges: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(GridScrimHeightFraction)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = GridScrimAlpha)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .testTag("library-grid-caption")
                .padding(
                    start = PclSpacing.ExtraSmall,
                    end = PclSpacing.ExtraSmall,
                    top = PclSpacing.Tiny,
                    bottom = PclSpacing.ExtraSmall,
                ),
            verticalArrangement = Arrangement.spacedBy(PclSpacing.Tiny),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                badges()
                if (positionText != null) {
                    Text(
                        text = positionText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = GridScrimAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("library-grid-position"),
                    )
                }
            }
        }
        if (showProgress) {
            QuietProgressBar(
                progress = progress,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .then(
                        if (progressTag != null) Modifier.testTag(progressTag) else Modifier,
                    ),
                color = QuietShelfColors.Light.surface,
                trackColor = Color.White.copy(alpha = 0.35f),
                height = 3.dp,
            )
        }
    }
}

/**
 * Task 5 / audit F1 (quiet-shelf §4): when in-place search is active and the
 * title contains the query, wrap the matched range in a soft accent wash so
 * the user sees *why* this tile survived the filter. Outside search mode (or
 * on a non-matching title) the title renders plain.
 */
@Composable
private fun highlightTitleMatch(title: String, query: String): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(title)
    val start = title.indexOf(query, startIndex = 0, ignoreCase = true)
    if (start < 0) return AnnotatedString(title)
    val end = start + query.length
    val highlight = quietShelfPalette().statusOk.copy(alpha = 0.28f)
    return buildAnnotatedString {
        append(title)
        addStyle(SpanStyle(background = highlight), start, end)
    }
}

@Composable
private fun LibraryListRow(
    item: LibraryItem,
    onOpenItem: (Long) -> Unit,
    onCoverOpenFailed: (Long) -> Unit,
    highlightQuery: String = "",
    badges: ShelfBadges,
    badgeInputs: LibraryBadgeInputs,
) {
    val palette = quietShelfPalette()
    val cleaned = remember(item.title) { ComicTitleCleaner.clean(item.title) }
    val rowContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(
                        width = QUIET_SHELF_COVER_WIDTH_DP.dp,
                        height = QUIET_SHELF_COVER_HEIGHT_DP.dp,
                    )
                    .clip(ListCoverShape)
                    .border(1.dp, palette.ink.copy(alpha = 0.08f), ListCoverShape)
                    .testTag("library-list-cover"),
            ) {
                LibraryCoverImage(
                    item = item,
                    onCoverOpenFailed = onCoverOpenFailed,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // A42 geometry R1 + #E116 Editorial Bookplate: title and dotless
            // capsule progress bar own the full row width; page count and optional
            // micro-chips sit on the footer line opposite the state badges.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = QUIET_SHELF_COVER_HEIGHT_DP.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = highlightTitleMatch(item.title, highlightQuery),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    cleaned.authorOrCircle?.let { author ->
                        Text(
                            text = author,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    LibraryStatusLines(item)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.pageCount > 0) {
                        QuietProgressBar(
                            progress = { libraryProgressFraction(item) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = palette.statusOk,
                            trackColor = palette.ink.copy(alpha = 0.09f),
                            height = 4.dp,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("library-list-footer"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (item.pageCount > 0) {
                            Text(
                                text = libraryPageMetaText(item),
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    !item.isAvailable -> palette.statusBad
                                    item.isIncomplete -> palette.statusWarn
                                    else -> palette.statusOk
                                },
                            )
                        }
                        cleaned.languageTag?.let { lang ->
                            LibraryBookplateMicroChip(lang)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        LibraryTileBadgesFor(item, badges, badgeInputs)
                    }
                }
            }
        }
    }
    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = QUIET_SHELF_COVER_HEIGHT_DP.dp)
    if (item.isAvailable) {
        Surface(
            onClick = { onOpenItem(item.id.value) },
            modifier = rowModifier,
            shape = ListRowShape,
            color = palette.card,
            border = BorderStroke(1.dp, palette.line),
            content = rowContent,
        )
    } else {
        Surface(
            modifier = rowModifier,
            shape = ListRowShape,
            color = palette.card,
            border = BorderStroke(1.dp, palette.line),
            content = rowContent,
        )
    }
}

@Composable
private fun LibraryBookplateMicroChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    val palette = quietShelfPalette()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(palette.ink.copy(alpha = 0.06f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = palette.ink.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * S2 Task 3: badge glyphs for a remote (collected-only or cloud-only) shelf
 * tile. A remote tile is always "collected" conceptually, so it always carries
 * a presence — Cloud once mirrored, Bookmarked while collected-only — and never
 * shows the Downloaded glyph (nothing is on this device yet).
 */
@Composable
private fun LibraryRemoteTileBadges(
    badges: ShelfBadges,
    cloudBound: Boolean,
    cloudUnreachable: Boolean,
    modifier: Modifier = Modifier,
    onScrim: Boolean = false,
    maxGlyphs: Int = Int.MAX_VALUE,
) {
    val presence = if (badges.inCloud) ShelfPresence.Cloud else ShelfPresence.Bookmarked
    LibraryTileBadges(
        facts = ShelfBadgeFacts(
            cloudBound = cloudBound,
            presence = presence,
            downloaded = false,
            imported = false,
            // A63: remote tiles have no local download by definition — the
            // download axis never speaks on them.
            downloadState = ShelfDownloadState.NONE,
            delistedAtSource = badges.delistedAtSource,
        ),
        unreachable = cloudUnreachable,
        onScrim = onScrim,
        maxGlyphs = maxGlyphs,
        modifier = modifier,
    )
}

/**
 * S2 Task 3: grid card for a remote shelf entry. Reuses [BrowseCoverThumb] for
 * the cover and the same caption/badge chrome shape as [LibraryItemCard], but
 * self-contained. A42 remainder: when the comic has stored remote-read progress
 * ([ShelfEntry.Remote.progress]), the caption carries the same cover-gradient
 * fraction bar the local card has.
 */
@Composable
private fun LibraryRemoteGridCard(
    entry: ShelfEntry.Remote,
    cloudBound: Boolean,
    cloudUnreachable: Boolean,
    highlightQuery: String,
    onClick: () -> Unit,
) {
    val storedProgress = entry.progress
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("library-grid-card"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_ASPECT_RATIO)
                .clip(ListCoverShape)
                .testTag("library-remote-grid-cover")
                .clickable(onClick = onClick),
        ) {
            BrowseCoverThumb(
                coverUrl = entry.feedItem.coverUrl,
                contentDescription = AppStrings.coverForTitle(entry.feedItem.title),
                modifier = Modifier.fillMaxSize(),
            )
            CollectedNewChapterChip(
                entry = entry,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(PclSpacing.Tiny),
            )
            LibraryGridCaption(
                title = highlightTitleMatch(entry.feedItem.title, highlightQuery),
                showProgress = storedProgress != null,
                progress = { storedProgress?.let(::remoteProgressFraction) ?: 0f },
                progressTag = "library-remote-tile-progress",
                positionText = null,
            ) {
                LibraryRemoteTileBadges(
                    badges = entry.badges,
                    cloudBound = cloudBound,
                    cloudUnreachable = cloudUnreachable,
                    onScrim = true,
                    maxGlyphs = 2,
                )
            }
        }
    }
}

/**
 * A42 remainder (spec 2026-09-08-a42-remote-progress-design.md D3): the remote
 * list row's fraction bar — the exact quiet treatment the LOCAL list row's
 * A42 R1 bar uses (3.dp height, statusOk fill, 10% ink track), with the same
 * (pageCount - 1) denominator geometry. Deliberately NOT refactored onto the
 * local row path (remote-tile composable unification is deferred, spec D3).
 */
@Composable
private fun LibraryRemoteTileProgressBar(
    progress: ShelfRemoteProgress,
    modifier: Modifier = Modifier,
) {
    val palette = quietShelfPalette()
    if (progress.pageCount > 0) {
        QuietProgressBar(
            progress = { remoteProgressFraction(progress) },
            modifier = modifier.height(4.dp),
            color = palette.statusOk,
            trackColor = palette.ink.copy(alpha = 0.09f),
            height = 4.dp,
        )
    }
}

private fun remoteProgressFraction(progress: ShelfRemoteProgress): Float {
    if (progress.pageCount <= 1) return 0f
    val denominator = (progress.pageCount - 1).coerceAtLeast(1)
    return (progress.ordinal.toFloat() / denominator).coerceIn(0f, 1f)
}

/**
 * S2 Task 3 + #E116 Editorial Bookplate: list row for a remote shelf entry.
 * Mirrors [LibraryListRow]'s top-to-bottom space-between vertical rhythm,
 * extracts author/language from bracketed titles via [ComicTitleCleaner],
 * renders a dotless capsule progress bar when read progress exists, and
 * populates the bottom-left footer with source/language micro-chips when unread
 * so the lower half of the card is never empty.
 */
@Composable
private fun LibraryRemoteListRow(
    entry: ShelfEntry.Remote,
    cloudBound: Boolean,
    cloudUnreachable: Boolean,
    highlightQuery: String,
    onClick: () -> Unit,
) {
    val palette = quietShelfPalette()
    val cleaned = remember(entry.feedItem.title) { ComicTitleCleaner.clean(entry.feedItem.title) }
    val sublineAuthor = remember(entry.feedItem.author, entry.feedItem.artists, cleaned) {
        entry.feedItem.author?.takeIf { it.isNotBlank() }
            ?: entry.feedItem.artists.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: cleaned.authorOrCircle
    }
    val footerSourceLabel = remember(entry.feedItem.providerId, entry.feedItem.upstreamAcquisitionUrl, entry.feedItem.acquisitionUrl) {
        val upstreamId = CloudCollectionFeedMapper.upstreamSourceId(
            entry.feedItem.upstreamAcquisitionUrl ?: entry.feedItem.acquisitionUrl,
        )
        sourceDisplayNameFor(upstreamId ?: entry.feedItem.providerId).takeIf { it.isNotBlank() }
    }
    val footerLanguage = remember(entry.feedItem.language, cleaned) {
        entry.feedItem.language?.takeIf { it.isNotBlank() } ?: cleaned.languageTag
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = QUIET_SHELF_COVER_HEIGHT_DP.dp),
        shape = ListRowShape,
        color = palette.card,
        border = BorderStroke(1.dp, palette.line),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(
                        width = QUIET_SHELF_COVER_WIDTH_DP.dp,
                        height = QUIET_SHELF_COVER_HEIGHT_DP.dp,
                    )
                    .clip(ListCoverShape)
                    .testTag("library-list-cover"),
            ) {
                BrowseCoverThumb(
                    coverUrl = entry.feedItem.coverUrl,
                contentDescription = AppStrings.coverForTitle(entry.feedItem.title),
                    modifier = Modifier.fillMaxSize(),
                )
                entry.feedItem.pageCount?.takeIf { it > 0 }?.let { pages ->
                    Text(
                        text = "${pages}p",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.card,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(3.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(palette.ink.copy(alpha = 0.74f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            // A63 (owner ruling 2026-09-09): the badge cluster moves OUT of the
            // cover Box into the row's body — the same placement the local row
            // uses, so local and remote tiles speak one position language.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = QUIET_SHELF_COVER_HEIGHT_DP.dp)
                    .testTag("library-remote-list-body"),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = highlightTitleMatch(entry.feedItem.title, highlightQuery),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!sublineAuthor.isNullOrBlank()) {
                        Text(
                            text = sublineAuthor,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    CollectedNewChapterChip(entry)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (entry.progress != null) {
                        LibraryRemoteTileProgressBar(
                            progress = entry.progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("library-remote-tile-progress"),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (entry.progress != null) {
                            Text(
                                text = remotePageMetaText(entry.progress),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.statusOk,
                            )
                        }
                        footerSourceLabel?.let { src ->
                            LibraryBookplateMicroChip(src)
                        }
                        footerLanguage?.let { lang ->
                            LibraryBookplateMicroChip(lang)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        LibraryRemoteTileBadges(
                            badges = entry.badges,
                            cloudBound = cloudBound,
                            cloudUnreachable = cloudUnreachable,
                        )
                    }
                }
            }
        }
    }
}

/** A quiet follow update marker shown only on collected serialized tiles. */
@Composable
private fun CollectedNewChapterChip(
    entry: ShelfEntry.Remote,
    modifier: Modifier = Modifier,
) {
    if (entry.origin != RemoteOrigin.COLLECTED) return
    NewChapterChip(entry.newChapterCount, "collected-new-chapters-${entry.feedItem.id}", modifier)
}

/** The quiet "+N new chapters" marker; nothing when [count] is zero. */
@Composable
private fun NewChapterChip(
    count: Int,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    val palette = quietShelfPalette()
    Surface(
        color = palette.card.copy(alpha = 0.92f),
        contentColor = palette.statusOk,
        shape = RoundedCornerShape(50),
        modifier = modifier.testTag(testTag),
    ) {
        Text(
            text = "+$count",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun remotePageMetaText(progress: ShelfRemoteProgress): String {
    val totalPages = progress.pageCount.coerceAtLeast(1)
    val currentPage = (progress.ordinal + 1).coerceIn(1, totalPages)
    return "p.$currentPage/${progress.pageCount}"
}

@Composable
private fun LibraryCoverImage(
    item: LibraryItem,
    onCoverOpenFailed: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (item.isAvailable) {
        val context = LocalContext.current
        val coverPage = item.pages.firstOrNull()
        var sourceCoverFailed by remember(item.seriesCoverUri, coverPage) { mutableStateOf(false) }
        val coverRequest = remember(item.seriesCoverUri, coverPage, sourceCoverFailed) {
            buildLibraryCoverRequest(context, item, preferPageCover = sourceCoverFailed)
        }
        val coverErrorListener = remember(item.id, item.seriesCoverUri, coverPage) {
            CoverErrorListener(item.id.value, onCoverOpenFailed)
        }
        // A62 (spec D4): a cover that failed to load renders the broken-image glyph, not
        // an inert gray block. The existing shelf-level callback still fires so the
        // cover-backfill logic is unchanged; no retry here (recovery lives in Detail /
        // the reader; a stale URL self-heals on the next shelf epoch).
        var coverFailed by remember(item.seriesCoverUri, coverPage) { mutableStateOf(false) }
        if (coverFailed) {
            // A62 device-run finding: the placeholder keeps the tile's own
            // cover description so a failed cover still names its comic
            // (TalkBack + the shelf tests assert the same identity either way).
            BrokenCoverPlaceholder(
                modifier = modifier,
                contentDescription = AppStrings.coverForTitlePages(item.title, AppStrings.pageCount(item.pageCount)),
            )
        } else {
            AsyncImage(
                model = coverRequest,
                contentDescription = AppStrings.coverForTitlePages(item.title, AppStrings.pageCount(item.pageCount)),
                modifier = modifier.background(QuietShelfColors.Light.ink.copy(alpha = 0.08f)),
                contentScale = ContentScale.Crop,
                onError = {
                    if (item.seriesCoverUri != null && coverPage != null && !sourceCoverFailed) {
                        sourceCoverFailed = true
                    } else {
                        coverErrorListener.onError()
                        coverFailed = true
                    }
                },
            )
        }
    } else {
        Spacer(
            modifier = modifier.background(
                QuietShelfColors.Light.ink.copy(alpha = 0.08f),
                RoundedCornerShape(6.dp),
            ),
        )
    }
}

/**
 * A42: `Available on this device` is gone. Every row on the shelf is on the
 * shelf, so the line carried no information — and it was the visible difference
 * that made imported rows look like a different species from collected ones.
 * Only the genuinely exceptional state still speaks here.
 */
@Composable
private fun ColumnScope.LibraryStatusLines(item: LibraryItem) {
    if (item.isIncomplete) {
        Text(AppStrings.incomplete, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LibraryLoadingSkeleton(viewMode: LibraryViewMode) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = AppStrings.libraryLoading },
    ) {
        if (viewMode == LibraryViewMode.LIST) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(SKELETON_PLACEHOLDER_COUNT) { LibrarySkeletonRow() }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(GridGutterHorizontal),
                verticalArrangement = Arrangement.spacedBy(GridGutterVertical),
            ) {
                items(SKELETON_PLACEHOLDER_COUNT) { LibrarySkeletonCard() }
            }
        }
    }
}

@Composable
private fun LibrarySkeletonCard() {
    val palette = quietShelfPalette()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("library-grid-card"),
        shape = ListRowShape,
        color = palette.card,
        border = BorderStroke(1.dp, palette.line),
    ) {
        QuietShelfShimmer(
            modifier = Modifier
                .padding(GridCardInnerPadding)
                .fillMaxWidth()
                .aspectRatio(COVER_ASPECT_RATIO),
            shape = ListCoverShape,
        )
    }
}

@Composable
private fun LibrarySkeletonRow() {
    val palette = quietShelfPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ListRowShape)
            .background(palette.card)
            .border(1.dp, palette.line, ListRowShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QuietShelfShimmer(
            modifier = Modifier
                .size(
                    width = QUIET_SHELF_COVER_WIDTH_DP.dp,
                    height = QUIET_SHELF_COVER_HEIGHT_DP.dp,
                ),
            shape = ListCoverShape,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            QuietShelfShimmer(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(10.dp),
                shape = RoundedCornerShape(4.dp),
            )
            QuietShelfShimmer(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(8.dp),
                shape = RoundedCornerShape(4.dp),
            )
        }
    }
}

internal class CoverErrorListener(
    private val itemId: Long,
    private val onCoverOpenFailed: (Long) -> Unit,
) {
    private var reported = false

    fun onError() {
        if (reported) return
        reported = true
        onCoverOpenFailed(itemId)
    }
}

internal fun buildLibraryCoverRequest(
    context: Context,
    item: LibraryItem,
    preferPageCover: Boolean = false,
): ImageRequest? =
    item.seriesCoverUri?.takeUnless { preferPageCover }?.let { uri ->
        ImageRequest.Builder(context).data(uri).build()
    } ?: item.pages.firstOrNull()?.let { page ->
        buildPageImageRequest(context, page)
    }

/**
 * A63 (a): the cloud-sync progress strip — a one-line label plus a thin
 * fraction bar (or the indeterminate shimmer when the manifest total is
 * unknown). Pure render of [CloudImportProgressPresentation.present]'s
 * decision; the composable owns no visibility logic of its own.
 */
@Composable
private fun LibraryCloudImportStrip(
    label: String,
    fraction: Float?,
) {
    val palette = quietShelfPalette()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("library-cloud-import-strip"),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted,
        )
        Spacer(modifier = Modifier.height(PclSpacing.ExtraSmall))
        if (fraction != null) {
            QuietProgressBar(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .testTag("library-cloud-import-strip-fraction"),
                color = palette.cloudUploaded,
                trackColor = palette.line,
                height = 3.dp,
            )
        } else {
            QuietIndeterminateProgressBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = palette.cloudUploaded,
                trackColor = palette.line,
                height = 3.dp,
            )
        }
    }
}

@Composable
private fun LibraryNoticeCard(
    notice: LibraryNotice,
    onDismissNotice: () -> Unit,
) {
    val palette = quietShelfPalette()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("library-notice"),
        shape = RoundedCornerShape(14.dp),
        color = palette.card,
        border = BorderStroke(1.dp, palette.line),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(notice.message(), color = palette.ink)
            PclOutlinedButton(
                onClick = onDismissNotice,
            ) {
                Text(AppStrings.dismiss)
            }
        }
    }
}

private fun LibraryNotice.message(): String = when (this) {
    LibraryNotice.MetadataFallback ->
        "ComicInfo.xml couldn't be read. The comic was imported using the folder name."
    LibraryNotice.AvailabilityRefreshFailed ->
        "Library availability couldn't be refreshed. Reopen the app to try again."
    is LibraryNotice.ImportFailed -> when (reason) {
        ImportFailure.CatalogWriteFailed ->
            "The catalog was unchanged because the import couldn't be saved. Try importing the folder again."
        is ImportFailure.Scan -> when (reason.reason) {
            ScanRejection.NO_SUPPORTED_IMAGES ->
                "No supported images were found. Choose a folder containing JPG, PNG, or WebP files."
            ScanRejection.NO_DECODABLE_IMAGES ->
                "The supported image files couldn't be opened. Check the files, then choose the folder again."
            ScanRejection.SOURCE_UNAVAILABLE ->
                // S4b/H4 sweep: the last "unavailable" in user-facing copy (A30 §4 bans it).
                "Access to that folder was lost. Choose it again."
            ScanRejection.PROVIDER_ERROR ->
                "The folder provider couldn't be read. Try again or choose a different folder."
        }
    }
}

private fun libraryProgressFraction(item: LibraryItem): Float {
    if (item.pageCount <= 1) return 0f
    val denominator = (item.pageCount - 1).coerceAtLeast(1)
    return (item.progressOrdinal.toFloat() / denominator).coerceIn(0f, 1f)
}

private fun pageCountText(pageCount: Int) = AppStrings.pageCount(pageCount)

private fun libraryPageMetaText(item: LibraryItem): String {
    val totalPages = item.pageCount.coerceAtLeast(1)
    val currentPage = (item.progressOrdinal + 1).coerceIn(1, totalPages)
    return "p.$currentPage/${item.pageCount}"
}
