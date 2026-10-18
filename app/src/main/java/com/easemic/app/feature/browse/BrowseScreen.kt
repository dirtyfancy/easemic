package com.easemic.app.feature.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.ui.components.PclTextButton
import com.easemic.app.ui.theme.QuietShelfShimmer
import com.easemic.app.ui.theme.quietShelfPalette
import com.easemic.app.ui.fastscroll.FastScroller
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.providers.ExhentaiEnableStore
import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.feature.source.SeriesDetailMetadata
import com.easemic.app.feature.source.zaimanhua.ZAIMANHUA_LISTED_IN_BROWSE

/** One row in the Browse feed (OPDS navigation or acquisition entry). */
data class BrowseFeedItem(
    val id: String,
    val title: String,
    val secondaryLine: String = "",
    val providerId: String = "",
    val subsectionUrl: String? = null,
    val acquisitionUrl: String? = null,
    val coverUrl: String? = null,
    val pageCount: Int? = null,
    val pageStreamUrl: String? = null,
    val language: String? = null,
    val artists: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val uploadDateEpochSeconds: Long? = null,
    val previewUrls: List<String> = emptyList(),
    val upstreamAcquisitionUrl: String? = null,
    /** E-Hentai list rating (0–5); only present for Front-catalog rows. */
    val rating: Float? = null,
    /** E-Hentai list relative posted label (e.g. "3 hours ago"). */
    val postedAge: String? = null,
    /** nhentai list num_favorites; only present for Index-catalog rows. */
    val favorites: Int? = null,
    /** OPDS author, when the feed entry carries one. */
    val author: String? = null,
    /** OPDS publisher fallback, when the feed entry carries one. */
    val publisher: String? = null,
    /** #E115: Zaimanhua latest chapter label for the bottom-right cover badge (e.g. "第321话"). */
    val latestChapterLabel: String? = null,
    /** #E115: Zaimanhua ranking badge for the top-left cover pill (e.g. "#1"). */
    val rankBadge: String? = null,
    /**
     * #C12: the OPDS feed URL this entry was mapped from. The existence check
     * re-reads that feed when a server answers "gone" with a shape no status
     * code can name (Ubooquity: 200 + HTML). Only the OPDS mapper sets it.
     */
    val feedUrl: String? = null,
    val seriesMetadata: SeriesDetailMetadata? = null,
    /** #E118: Number of trailing commercial sponsor ad pages trimmed on cloud upload. */
    val trimmedAdPages: Int? = null,
)

/**
 * Browse second level: one source's gallery. Quiet Shelf header (back to
 * sources + source name + funnel), in-source search pill, dismissible
 * active-filter chips, and calm feed rows. The funnel and its [SourceFilterSheet]
 * are per source (spec §3); sources with no filter panel (OPDS) hide the funnel.
 */
@Composable
fun BrowseScreen(
    prefs: BrowsePrefs,
    filterSheetShape: SourceFilterSheetShape,
    filterState: SourceFilterState = SourceFilterState(),
    query: String,
    results: List<BrowseFeedItem> = emptyList(),
    loading: Boolean = false,
    loadingMore: Boolean = false,
    canLoadMore: Boolean = false,
    loadMoreError: String? = null,
    errorMessage: String? = null,
    sourceUnreachable: Boolean = false,
    sourceLabel: String? = null,
    sourceOptions: List<BrowseSourceOption> = browseSourceOptions(
        zaimanhuaEnabled = ZAIMANHUA_LISTED_IN_BROWSE,
    ),
    filterOpen: Boolean = false,
    genres: List<ZGenre> = emptyList(),
    feedNotice: BrowseFeedNotice? = null,
    onZaimanhuaSignIn: () -> Unit = {},
    jmSearchScope: JmSearchScope? = null,
    onJmSearchScopeDismiss: () -> Unit = {},
    onQueryEdit: () -> Unit = {},
    onQueryChange: (String) -> Unit,
    onOpenFilter: () -> Unit,
    onCloseFilter: () -> Unit = {},
    onApplyFilter: (SourceFilterState) -> Unit = {},
    onFilterChipDismiss: (SourceFilterState) -> Unit = {},
    onBack: () -> Unit = {},
    onSourceChange: (String) -> Unit = {},
    onItemClick: (BrowseFeedItem) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onRetryLoadMore: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    knownTotalItems: Int? = null,
) {
    val palette = quietShelfPalette()
    val sourceName = sourceLabel
        ?: sourceOptions.firstOrNull { it.id == prefs.sourceId }?.label
        ?: browseSourceLabel(prefs.sourceId)
    val sheetShape = filterSheetShape
    val serialized = sheetShape == SourceFilterSheetShape.ZAIMANHUA || sheetShape == SourceFilterSheetShape.JM
    val presentation = serializedFilterPresentation(sheetShape, filterState, query, genres, feedNotice)
    val chips = if (serialized) presentation.chips else activeSourceFilterChips(filterState)
    val filterActive = if (serialized) chips.isNotEmpty() else filterState != SourceFilterState()
    var headerExpanded by remember(prefs.sourceId) { mutableStateOf(true) }
    var prevScrollIndex by remember(prefs.sourceId) { mutableIntStateOf(listState.firstVisibleItemIndex) }
    var prevScrollOffset by remember(prefs.sourceId) { mutableIntStateOf(listState.firstVisibleItemScrollOffset) }
    LaunchedEffect(listState, results.isNotEmpty(), loading) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (currentIndex, currentOffset) ->
            headerExpanded = BrowseHeaderScrollPolicy.nextExpandedState(
                currentlyExpanded = headerExpanded,
                hasResults = results.isNotEmpty(),
                loading = loading,
                previousIndex = prevScrollIndex,
                previousOffset = prevScrollOffset,
                currentIndex = currentIndex,
                currentOffset = currentOffset,
            )
            prevScrollIndex = currentIndex
            prevScrollOffset = currentOffset
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            BrowseLevel2Header(
                sourceName = sourceName,
                funnelVisible = sheetShape != SourceFilterSheetShape.NONE,
                filterActive = filterActive,
                onBack = onBack,
                onOpenFilter = onOpenFilter,
            )
            AnimatedVisibility(
                visible = headerExpanded || results.isEmpty() || loading,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(modifier = Modifier.fillMaxWidth().testTag("browse-collapsible-header")) {
                    Spacer(modifier = Modifier.height(8.dp))
                    key(prefs.sourceId) {
                        QuietBrowseSearchPill(
                            query = query,
                            placeholder = AppStrings.searchInSource(sourceName),
                            onQueryChange = onQueryChange,
                            onQueryEdit = onQueryEdit,
                        )
                    }
                    if (presentation.zaimanhuaSignInNotice) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("browse-zaimanhua-signed-out-notice"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                AppStrings.signedOutZaimanhuaNotice,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.muted,
                                modifier = Modifier.weight(1f),
                            )
                            PclTextButton(onClick = onZaimanhuaSignIn, modifier = Modifier.testTag("browse-zaimanhua-sign-in")) {
                                Text(AppStrings.signIn)
                            }
                        }
                    }
                    presentation.notices.forEach { notice ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            modifier = Modifier.testTag("browse-filter-notice"),
                        )
                    }
                    // #E89: after an empty scoped search fell back to all results, the scope no longer applies.
                    if (jmSearchScope != null && feedNotice !is BrowseFeedNotice.JmScopedFallback) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ActiveFilterChipRow(
                            chips = listOf(
                                ActiveFilterChip(
                                    label = if (jmSearchScope == JmSearchScope.AUTHOR) AppStrings.authorSearch else AppStrings.tagSearch,
                                    testTag = "browse-jm-search-scope",
                                    remove = { it },
                                ),
                            ),
                            onDismiss = { onJmSearchScopeDismiss() },
                        )
                    }
                    if (chips.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ActiveFilterChipRow(
                            chips = chips,
                            onDismiss = { chip -> onFilterChipDismiss(chip.remove(filterState)) },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            when {
                sourceUnreachable && results.isEmpty() -> {
                    Text(
                        AppStrings.currentSourceUnreachable,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.ink,
                        modifier = Modifier
                            .padding(top = 24.dp)
                            .testTag("browse-feed-error"),
                    )
                }
                loading -> BrowseFeedSkeleton()
                errorMessage != null -> {
                    Text(
                        errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.ink,
                        modifier = Modifier
                            .padding(top = 24.dp)
                            .testTag("browse-feed-error"),
                    )
                }
                serialized && query.isBlank() && filterActive && results.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 36.dp)
                            .testTag("browse-feed-zero-results"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            AppStrings.noSeriesMatchFilters,
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.ink,
                        )
                        PclTextButton(
                            onClick = { onApplyFilter(resetSerializedFilters(sheetShape, filterState)) },
                            modifier = Modifier.testTag("browse-empty-reset-filters"),
                        ) { Text(AppStrings.resetFilters) }
                    }
                }
                results.isEmpty() && (query.isNotBlank() || filterState != SourceFilterState()) -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 36.dp)
                            .testTag("browse-feed-zero-results"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            AppStrings.noMatches,
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.ink,
                        )
                        Text(
                            AppStrings.tryDifferentSearchOrFilter,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                        )
                    }
                }
                query.isEmpty() && results.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            AppStrings.nothingToShowYet,
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.ink,
                        )
                        Text(
                            AppStrings.connectCatalogInSettings,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                        )
                    }
                }
                else -> BrowseFeedList(
                    results = results,
                    loadingMore = loadingMore,
                    canLoadMore = canLoadMore,
                    loadMoreError = loadMoreError,
                    sourceUnreachable = sourceUnreachable,
                    onItemClick = onItemClick,
                    onLoadMore = onLoadMore,
                    onRetryLoadMore = onRetryLoadMore,
                    listState = listState,
                    knownTotalItems = knownTotalItems,
                    showPositionBubble = browseFastScrollerShowsPositionBubble(prefs.sourceId),
                )
            }
        }
        if (filterOpen && sheetShape != SourceFilterSheetShape.NONE) {
            SourceFilterSheet(
                sourceName = sourceName,
                state = filterState,
                shape = sheetShape,
                query = query,
                genres = genres,
                onApply = { applied ->
                    onApplyFilter(applied)
                    onCloseFilter()
                },
                onDismiss = onCloseFilter,
            )
        }
    }
}

/**
 * Level-2 header row (spec §1): back arrow + source name + ⚲ funnel. The
 * funnel carries a small green dot while this source's [SourceFilterState]
 * differs from default (any language selected, any category hidden, or any
 * non-default scope toggle).
 */
@Composable
private fun BrowseLevel2Header(
    sourceName: String,
    funnelVisible: Boolean,
    filterActive: Boolean,
    onBack: () -> Unit,
    onOpenFilter: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = AppStrings.backToSources },
        ) {
            BrowseBackGlyph()
        }
        Text(
            sourceName,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (funnelVisible) {
            IconButton(
                onClick = onOpenFilter,
                modifier = Modifier
                    .size(40.dp)
                    .testTag("browse-filter-funnel")
                    .semantics { contentDescription = AppStrings.filterSource(sourceName) },
            ) {
                Box {
                    BrowseFilterGlyph()
                    if (filterActive) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(2.dp)
                                .size(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(quietShelfPalette().statusOk)
                                .testTag("browse-filter-dot")
                                .semantics { contentDescription = AppStrings.filterActive },
                        )
                    }
                }
            }
        }
    }
}

/** Individually dismissible chips for a source's non-default filter slices. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveFilterChipRow(
    chips: List<ActiveFilterChip>,
    onDismiss: (ActiveFilterChip) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("browse-active-filters"),
    ) {
        chips.forEach { chip ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = quietShelfPalette().statusOk.copy(alpha = 0.12f),
                contentColor = quietShelfPalette().statusOk,
                onClick = { onDismiss(chip) },
                modifier = Modifier
                    .testTag(chip.testTag)
                    .semantics { contentDescription = AppStrings.removeFilter(chip.label) },
            ) {
                Text(
                    text = "${chip.label} ✕",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun QuietBrowseSearchPill(
    query: String,
    placeholder: String = "Search",
    onQueryChange: (String) -> Unit,
    onQueryEdit: () -> Unit = {},
) {
    val palette = quietShelfPalette()
    // #D2: the field keeps its own text; the source is asked only when the keyboard's Search key is
    // pressed (or the field is emptied, which brings the default feed back). Reporting every keystroke
    // re-keyed the feed per letter — "enokido" was seven requests and nhentai answered 429.
    var draft by remember(query) { mutableStateOf(query) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(palette.card)
            .border(1.dp, palette.line, RoundedCornerShape(999.dp))
            .padding(start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = { typed ->
                if (typed != draft) onQueryEdit()
                draft = typed
                if (typed.isEmpty() && query.isNotEmpty()) onQueryChange("")
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onQueryChange(draft.trim()) }),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = palette.ink),
            cursorBrush = SolidColor(palette.statusOk),
            modifier = Modifier
                .weight(1f)
                .testTag("browse-search-field")
                .semantics { contentDescription = AppStrings.searchLibrary },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (draft.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                        )
                    }
                    inner()
                }
            },
        )
        if (draft.isNotEmpty()) {
            IconButton(
                onClick = {
                    draft = ""
                    onQueryChange("")
                },
                modifier = Modifier
                    .size(28.dp)
                    .semantics { contentDescription = AppStrings.clearSearch },
            ) {
                Text("×", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun BrowseBackGlyph() {
    val ink = quietShelfPalette().ink
    androidx.compose.foundation.Canvas(modifier = Modifier.size(18.dp)) {
        val strokeWidth = 1.75.dp.toPx()
        val mid = size.height / 2f
        drawLine(
            color = ink,
            start = androidx.compose.ui.geometry.Offset(3.dp.toPx(), mid),
            end = androidx.compose.ui.geometry.Offset(size.width - 1.dp.toPx(), mid),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
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
private fun BrowseFilterGlyph() {
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

@Composable
private fun BrowseFeedSkeleton() {
    val palette = quietShelfPalette()
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag("browse-feed-skeleton"),
    ) {
        repeat(5) { index ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = palette.card,
                border = BorderStroke(1.dp, palette.line),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = BROWSE_ROW_MIN_HEIGHT_DP.dp)
                    .testTag("browse-feed-skeleton-$index"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QuietShelfShimmer(
                        modifier = Modifier
                            .size(
                                width = QUIET_SHELF_COVER_WIDTH_DP.dp,
                                height = QUIET_SHELF_COVER_HEIGHT_DP.dp,
                            ),
                        shape = RoundedCornerShape(8.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuietShelfShimmer(
                            modifier = Modifier
                                .fillMaxWidth(0.72f)
                                .height(14.dp),
                            shape = RoundedCornerShape(4.dp),
                        )
                        QuietShelfShimmer(
                            modifier = Modifier
                                .fillMaxWidth(0.42f)
                                .height(10.dp),
                            shape = RoundedCornerShape(4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseFeedList(
    results: List<BrowseFeedItem>,
    loadingMore: Boolean,
    canLoadMore: Boolean,
    loadMoreError: String?,
    sourceUnreachable: Boolean,
    onItemClick: (BrowseFeedItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    listState: LazyListState,
    knownTotalItems: Int?,
    showPositionBubble: Boolean,
) {
    val palette = quietShelfPalette()
    LaunchedEffect(results.size, loadingMore, canLoadMore, loadMoreError) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { lastVisible ->
            if (
                loadMoreError == null &&
                BrowseFeedAppend.shouldLoadMore(
                    lastVisibleIndex = lastVisible,
                    totalItems = results.size,
                    nextContinuation = if (canLoadMore) "pending" else null,
                    loadingMore = loadingMore,
                )
            ) {
                onLoadMore()
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("browse-feed-list"),
        ) {
            items(results, key = { remoteItemKey(it.providerId, it.id) }) { item ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = palette.card,
                    border = BorderStroke(1.dp, palette.line),
                    onClick = { onItemClick(item) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = BROWSE_ROW_MIN_HEIGHT_DP.dp)
                        .testTag("browse-feed-item-${item.id}"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Thumbnail + rank badge (top-left) + page/chapter/category pill overlay (bottom-right).
                        Box(
                            modifier = Modifier.size(
                                width = QUIET_SHELF_COVER_WIDTH_DP.dp,
                                height = QUIET_SHELF_COVER_HEIGHT_DP.dp,
                            ),
                        ) {
                            BrowseCoverThumb(
                                coverUrl = item.coverUrl,
                                contentDescription = item.title,
                                modifier = Modifier.fillMaxSize(),
                            )
                            item.rankBadge?.takeIf { it.isNotBlank() }?.let { rank ->
                                Text(
                                    rank,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.card,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(3.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(palette.statusOk.copy(alpha = 0.88f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                        .testTag("browse-rank-badge"),
                                )
                            }
                            item.thumbBadge?.let { badge ->
                                Text(
                                    badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.card,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(3.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(palette.ink.copy(alpha = 0.74f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                        .testTag("browse-thumb-badge"),
                                )
                            }
                        }
                        val metaParts = item.rowMeta().sublineParts()
                        val microChips = item.cardMicroChips()
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = QUIET_SHELF_COVER_HEIGHT_DP.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (metaParts.isNotEmpty()) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        metaParts.forEachIndexed { index, part ->
                                            if (part.showDot) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(RoundedCornerShape(50))
                                                        .background(ehCategoryDotColor(part.text, palette))
                                                        .testTag("browse-cat-dot"),
                                                )
                                            }
                                            Text(
                                                part.text,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = palette.muted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (index < metaParts.lastIndex) {
                                                Text(
                                                    "·",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = palette.muted,
                                                )
                                            }
                                        }
                                    }
                                } else if (item.secondaryLine.isNotBlank()) {
                                    Text(
                                        item.secondaryLine,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.muted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (microChips.isNotEmpty()) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .testTag("browse-card-micro-chips"),
                                ) {
                                    microChips.forEach { chipText ->
                                        val isWebtoonHighlight = chipText == "条漫"
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(
                                                    if (isWebtoonHighlight) {
                                                        palette.statusWarn.copy(alpha = 0.14f)
                                                    } else {
                                                        palette.ink.copy(alpha = 0.06f)
                                                    },
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = chipText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isWebtoonHighlight) palette.statusWarn else palette.ink.copy(alpha = 0.80f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            } else {
                                Spacer(modifier = Modifier.height(1.dp))
                            }
                        }
                    }
                }
            }
            if (loadingMore) {
                item(key = "browse-loading-more") {
                    Text(
                        tr("Loading more…", "正在加载更多…", "正在載入更多…", "さらに読み込み中…"),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .testTag("browse-loading-more"),
                    )
                }
            }
            if (loadMoreError != null) {
                item(key = "browse-load-more-error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .testTag("browse-load-more-error"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (sourceUnreachable) {
                            // #C15 audit HIGH-1 / spec §4: an unreachable source
                            // gets the one honest sentence and NO retry affordance —
                            // here too, not only in the empty state. The raw
                            // OPDS_* token never reaches the user.
                            Text(
                                AppStrings.currentSourceUnreachable,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.ink,
                            )
                        } else {
                            Text(
                                loadMoreError,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.ink,
                            )
                            Text(
                                AppStrings.retry,
                                style = MaterialTheme.typography.labelLarge,
                                color = palette.statusOk,
                                modifier = Modifier
                                    .testTag("browse-load-more-retry")
                                    .clickable(onClick = onRetryLoadMore)
                                    .semantics { contentDescription = AppStrings.retryLoadMore },
                            )
                        }
                    }
                }
            }
        }
        FastScroller(
            state = listState,
            // #C8: a feed that carries no total but has no next page either is
            // fully loaded — its loaded count IS the constant total (audit M1).
            knownTotalItems = knownTotalItems ?: results.size.takeIf { !canLoadMore },
            showPositionBubble = showPositionBubble,
            modifier = Modifier.align(Alignment.TopEnd),
            onRequestMore = onLoadMore,
        )
    }
}

private const val BROWSE_ROW_MIN_HEIGHT_DP = 96

internal fun browseFastScrollerShowsPositionBubble(sourceId: String): Boolean =
    sourceId != EhentaiEnableStore.SOURCE_ID &&
        sourceId != ExhentaiEnableStore.SOURCE_ID &&
        sourceId != NhentaiEnableStore.SOURCE_ID

/** Muted E-Hentai, Zaimanhua, and JM category/status dots. */
private fun ehCategoryDotColor(category: String, palette: com.easemic.app.ui.theme.QuietShelfPalette): androidx.compose.ui.graphics.Color =
    when (category) {
        "Doujinshi", "Asian Porn" -> com.easemic.app.ui.theme.QuietShelfColors.Light.statusBad
        "Manga", "Cosplay", "韓漫", "已完结", "完结" -> palette.statusWarn
        "Artist CG", "Image Set", "连载中", "连载" -> palette.statusOk
        "Game CG", "Non-H" -> palette.statusOk.copy(alpha = 0.65f)
        "Western", "同人" -> androidx.compose.ui.graphics.Color(0xFF7B5C9E)
        "單本", "美漫" -> androidx.compose.ui.graphics.Color(0xFF4A789C)
        else -> palette.muted
    }
