package com.easemic.app.feature.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.easemic.app.feature.source.ComicSourceRegistry
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.ui.components.PclButton
import com.easemic.app.ui.components.PclTextButton
import com.easemic.app.ui.theme.PclSpacing
import com.easemic.app.ui.theme.quietShelfPalette

/**
 * Which filter-sheet shape a source gets (spec §3 — 源内 filter sheet 按源成形）:
 *
 * - [EHENTAI]: full E-Hentai shape — color-dot category grid + free-text language
 *   tags + advanced scope switches (searchTitle/searchTags/searchDescription/
 *   onlyTorrents).
 * - [NHENTAI]: nhentai-tailored shape (A19) — its own category/language/tag/pages/
 *   sort/uploaded-within controls, built from `NhentaiQueryMapper`'s query grammar
 *   rather than reusing E-Hentai's controls.
 * - [NONE]: the source has no user-level filter panel (OPDS); the second-level
 *   header does not render a funnel at all, so this sheet is never mounted for it.
 */
enum class SourceFilterSheetShape {
    EHENTAI,
    NHENTAI,
    ZAIMANHUA,
    JM,
    NONE,
}

fun sourceFilterSheetShapeFor(
    sourceId: String,
    registry: ComicSourceRegistry,
): SourceFilterSheetShape =
    registry.source(browseSourceNamespace(sourceId))?.capabilities?.filterSheet
        ?: SourceFilterSheetShape.NONE

/** Sheet subtitle from spec §6: scoping promise that edits save per source. */
fun sourceFilterSheetSubtitle(sourceName: String): String =
    tr("$sourceName only · saved for this source", "$sourceName 专用 · 已为此图源保存", "$sourceName 專用 · 已為此圖源儲存", "$sourceName 専用 · このソース用に保存")

/** Human label for a category in rows/chips (dev-stage English copy). */
fun BrowseCategory.displayLabel(): String = when (this) {
    BrowseCategory.DOUJINSHI -> tr("Doujinshi", "同人志", "同人誌", "同人誌")
    BrowseCategory.MANGA -> tr("Manga", "漫画", "漫畫", "マンガ")
    BrowseCategory.ARTIST_CG -> tr("Artist CG", "画师 CG", "畫師 CG", "アーティストCG")
    BrowseCategory.GAME_CG -> tr("Game CG", "游戏 CG", "遊戲 CG", "ゲームCG")
    BrowseCategory.WESTERN -> tr("Western", "欧美", "歐美", "欧米")
    BrowseCategory.NON_H -> tr("Non-H", "非 H", "非 H", "非H")
    BrowseCategory.IMAGE_SET -> tr("Image Set", "图片集", "圖片集", "画像集")
    BrowseCategory.COSPLAY -> tr("Cosplay", "角色扮演", "Cosplay", "コスプレ")
    BrowseCategory.ASIAN_PORN -> tr("Asian Porn", "亚洲成人", "亞洲成人", "アジア系アダルト")
    BrowseCategory.MISC -> tr("Misc", "其他", "其他", "その他")
}

/** Human label for an nhentai category chip (dev-stage English copy). */
fun NhCategory.displayLabel(): String = when (this) {
    NhCategory.DOUJINSHI -> tr("Doujinshi", "同人志", "同人誌", "同人誌")
    NhCategory.MANGA -> tr("Manga", "漫画", "漫畫", "マンガ")
    NhCategory.MISC -> tr("Misc", "其他", "其他", "その他")
}

/** Human label for an nhentai language chip (dev-stage English copy). */
fun NhLanguage.displayLabel(): String = when (this) {
    NhLanguage.ENGLISH -> tr("English", "英语", "英語", "英語")
    NhLanguage.JAPANESE -> tr("Japanese", "日语", "日語", "日本語")
    NhLanguage.CHINESE -> tr("Chinese", "中文", "中文", "中国語")
}

/** Human label for an nhentai sort chip (dev-stage English copy). */
fun NhSort.displayLabel(): String = when (this) {
    NhSort.RECENT -> tr("Recent", "最近", "最近", "新着")
    NhSort.POPULAR -> tr("Popular", "热门", "熱門", "人気")
    NhSort.POPULAR_TODAY -> tr("Popular (today)", "今日热门", "今日熱門", "今日の人気")
    NhSort.POPULAR_WEEK -> tr("Popular (week)", "本周热门", "本週熱門", "今週の人気")
    NhSort.POPULAR_MONTH -> tr("Popular (month)", "本月热门", "本月熱門", "今月の人気")
}

fun EhFeedMode.displayLabel(): String = when (this) {
    EhFeedMode.LATEST -> tr("Latest", "最新", "最新", "最新")
    EhFeedMode.POPULAR -> tr("Popular", "热门", "熱門", "人気")
    EhFeedMode.TOP_YESTERDAY -> tr("Top · Yesterday", "排行 · 昨天", "排行 · 昨天", "ランキング · 昨日")
    EhFeedMode.TOP_MONTH -> tr("Top · Month", "排行 · 本月", "排行 · 本月", "ランキング · 今月")
    EhFeedMode.TOP_YEAR -> tr("Top · Year", "排行 · 本年", "排行 · 本年", "ランキング · 今年")
    EhFeedMode.TOP_ALL_TIME -> tr("Top · All-time", "排行 · 全部时间", "排行 · 全部時間", "ランキング · 全期間")
}

fun EhJumpPreset.displayLabel(): String = tr(label, label, label, label)

/**
 * Independent Quiet Shelf category palette: our own colors, not derived from
 * or copied out of any third-party category/tag scheme.
 */
private val CategoryPalette = listOf(
    Color(0xFF6B4226),
    Color(0xFF3E6E96),
    Color(0xFF8E5572),
    Color(0xFF4C7A5A),
    Color(0xFFB08150),
    Color(0xFF3F6C51),
    Color(0xFF7A5980),
    Color(0xFFAA7139),
    Color(0xFF4F6D7A),
    Color(0xFF8C6A5D),
)

internal fun BrowseCategory.dotColor(): Color =
    CategoryPalette[BrowseCategory.entries.indexOf(this) % CategoryPalette.size]

/**
 * A non-default slice of [SourceFilterState], rendered as a dismissible chip
 * under the second-level search line (spec §3: `English ✕`, `Hide: Western ✕`).
 * [label] is dev-stage English copy; [remove] writes exactly this one dimension
 * back to defaults — dismissing a chip must never touch sibling dimensions.
 */
data class ActiveFilterChip(
    val label: String,
    val testTag: String,
    val remove: (SourceFilterState) -> SourceFilterState,
)

fun activeSourceFilterChips(state: SourceFilterState): List<ActiveFilterChip> = buildList {
    if (state.ehFeedMode != EhFeedMode.LATEST) {
        add(
            ActiveFilterChip(
                label = state.ehFeedMode.displayLabel(),
                testTag = "browse-filter-chip-eh-feed-${state.ehFeedMode.name}",
            ) { current -> current.copy(ehFeedMode = EhFeedMode.LATEST) },
        )
    }
    state.languageTags.sorted().forEach { tag ->
        add(
            ActiveFilterChip(
                label = tag.replaceFirstChar { it.uppercase() },
                testTag = "browse-filter-chip-lang-$tag",
            ) { current -> current.copy(languageTags = current.languageTags - tag) },
        )
    }
    (BrowseCategory.entries.toSet() - state.categories)
        .sortedBy { BrowseCategory.entries.indexOf(it) }
        .forEach { category ->
            add(
                ActiveFilterChip(
                label = AppStrings.hideCategory(category.displayLabel()),
                    testTag = "browse-filter-chip-cat-${category.name}",
                ) { current -> current.copy(categories = current.categories + category) },
            )
        }
    state.ehIncludeTags.forEach { tag ->
        add(
            ActiveFilterChip(
                label = "+$tag",
                testTag = "browse-filter-chip-eh-include-$tag",
            ) { current -> current.copy(ehIncludeTags = current.ehIncludeTags - tag) },
        )
    }
    state.ehExcludeTags.forEach { tag ->
        add(
            ActiveFilterChip(
                label = "\u2212$tag",
                testTag = "browse-filter-chip-eh-exclude-$tag",
            ) { current -> current.copy(ehExcludeTags = current.ehExcludeTags - tag) },
        )
    }
    state.ehMinRating?.takeIf { it in 2..5 }?.let { minRating ->
        add(
            ActiveFilterChip(
                label = "\u2605 \u2265 $minRating",
                testTag = "browse-filter-chip-eh-rating",
            ) { current -> current.copy(ehMinRating = null) },
        )
    }
    state.ehMinPages?.let { minPages ->
        add(
            ActiveFilterChip(
                label = AppStrings.atLeastPages(minPages),
                testTag = "browse-filter-chip-eh-min-pages",
            ) { current -> current.copy(ehMinPages = null) },
        )
    }
    state.ehMaxPages?.let { maxPages ->
        add(
            ActiveFilterChip(
                label = AppStrings.atMostPages(maxPages),
                testTag = "browse-filter-chip-eh-max-pages",
            ) { current -> current.copy(ehMaxPages = null) },
        )
    }
    state.ehJump?.let { jump ->
        add(
            ActiveFilterChip(
                label = AppStrings.jumpTo(jump.displayLabel()),
                testTag = "browse-filter-chip-eh-jump",
            ) { current -> current.copy(ehJump = null) },
        )
    }
    if (state.onlyTorrents) {
        add(
            ActiveFilterChip(
                label = AppStrings.onlyTorrents,
                testTag = "browse-filter-chip-torrents-on",
            ) { current -> current.copy(onlyTorrents = false) },
        )
    }
    if (state.ehShowExpunged) {
        add(
            ActiveFilterChip(
                label = AppStrings.includeExpungedChip,
                testTag = "browse-filter-chip-expunged-on",
            ) { current -> current.copy(ehShowExpunged = false) },
        )
    }
    state.nhCategories.sortedBy { NhCategory.entries.indexOf(it) }.forEach { category ->
        add(
            ActiveFilterChip(
                label = category.displayLabel(),
                testTag = "browse-filter-chip-nhcat-${category.name}",
            ) { current -> current.copy(nhCategories = current.nhCategories - category) },
        )
    }
    val effectiveNhLanguages = when {
        state.nhLanguages.isNotEmpty() -> state.nhLanguages
        state.nhLanguage != null -> setOf(state.nhLanguage)
        else -> emptySet()
    }
    effectiveNhLanguages.sortedBy { NhLanguage.entries.indexOf(it) }.forEach { language ->
        add(
            ActiveFilterChip(
                label = language.displayLabel(),
                testTag = "browse-filter-chip-nhlang-${language.name}",
            ) { current ->
                val remaining = effectiveNhLanguages - language
                current.copy(
                    nhLanguage = remaining.singleOrNull(),
                    nhLanguages = remaining,
                )
            },
        )
    }
    state.includeTags.forEach { tag ->
        add(
            ActiveFilterChip(
                label = "+tag: $tag",
                testTag = "browse-filter-chip-include-$tag",
            ) { current -> current.copy(includeTags = current.includeTags - tag) },
        )
    }
    state.excludeTags.forEach { tag ->
        add(
            ActiveFilterChip(
                // U+2212 minus sign (not a hyphen), to read clearly as "excluded".
                label = "\u2212tag: $tag",
                testTag = "browse-filter-chip-exclude-$tag",
            ) { current -> current.copy(excludeTags = current.excludeTags - tag) },
        )
    }
    state.minPages?.let { minPages ->
        add(
            ActiveFilterChip(
                label = AppStrings.atLeastPages(minPages),
                testTag = "browse-filter-chip-min-pages",
            ) { current -> current.copy(minPages = null) },
        )
    }
    state.maxPages?.let { maxPages ->
        add(
            ActiveFilterChip(
                label = AppStrings.atMostPages(maxPages),
                testTag = "browse-filter-chip-max-pages",
            ) { current -> current.copy(maxPages = null) },
        )
    }
    if (state.nhSort != NhSort.RECENT) {
        add(
            ActiveFilterChip(
                label = state.nhSort.displayLabel(),
                testTag = "browse-filter-chip-sort",
            ) { current -> current.copy(nhSort = NhSort.RECENT) },
        )
    }
    state.uploadedWithinDays?.let { days ->
        add(
            ActiveFilterChip(
                label = AppStrings.uploadedWithinDays(days),
                testTag = "browse-filter-chip-uploaded",
            ) { current -> current.copy(uploadedWithinDays = null) },
        )
    }
}

fun resetEhentaiFilters(state: SourceFilterState): SourceFilterState =
    state.copy(
        languageTags = emptySet(),
        categories = BrowseCategory.entries.toSet(),
        onlyTorrents = false,
        ehFeedMode = EhFeedMode.LATEST,
        ehIncludeTags = emptyList(),
        ehExcludeTags = emptyList(),
        ehMinRating = null,
        ehMinPages = null,
        ehMaxPages = null,
        ehJump = null,
        ehShowExpunged = false,
    )

fun resetNhentaiFilters(state: SourceFilterState): SourceFilterState =
    state.copy(
        nhCategories = emptySet(),
        nhLanguage = null,
        nhLanguages = emptySet(),
        includeTags = emptyList(),
        excludeTags = emptyList(),
        minPages = null,
        maxPages = null,
        nhSort = NhSort.RECENT,
        uploadedWithinDays = null,
    )

/**
 * Task 11 (quiet-shelf): the in-source filter sheet. Opened from the
 * second-level funnel, shaped by [sourceFilterSheetShapeFor], subtitled with
 * the source-scoping promise. Draft edits stay local until Done persists the
 * state through [onApply] (production binds `SourceFilterStore.put` for this
 * source id only).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SourceFilterSheet(
    sourceName: String,
    state: SourceFilterState,
    shape: SourceFilterSheetShape,
    onApply: (SourceFilterState) -> Unit,
    onDismiss: () -> Unit,
    query: String = "",
    genres: List<ZGenre> = emptyList(),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val palette = quietShelfPalette()
        var draft by remember(sourceName, state) { mutableStateOf(state) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PclSpacing.Medium)
                .padding(bottom = PclSpacing.Large)
                .testTag("source-filter-sheet"),
            verticalArrangement = Arrangement.spacedBy(PclSpacing.Small),
        ) {
            Text(AppStrings.filters, style = MaterialTheme.typography.titleLarge)
            Text(
                sourceFilterSheetSubtitle(sourceName),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )

            when (shape) {
                SourceFilterSheetShape.EHENTAI -> {
                    val searchCardsEnabled = query.isNotBlank() || draft.ehFeedMode == EhFeedMode.LATEST
                    FilterCard(title = tr("Feed", "信息流", "資訊流", "フィード")) {
                        EhFeedModeChipRow(
                            selected = draft.ehFeedMode,
                            onChange = { draft = draft.copy(ehFeedMode = it) },
                        )
                    }
                    if (!searchCardsEnabled) {
                        Text(
                            tr("Filters below apply to Latest and searches.", "以下筛选适用于最新内容和搜索。", "以下篩選適用於最新內容和搜尋。", "以下のフィルタは最新フィードと検索に適用されます。"),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            modifier = Modifier.testTag("source-filter-eh-feed-notice"),
                        )
                    }
                    FilterCard(
                        title = tr("Language", "语言", "語言", "言語"),
                        modifier = Modifier.alpha(if (searchCardsEnabled) 1f else 0.45f),
                    ) {
                        LanguageTagsEditor(
                            tags = draft.languageTags,
                            onChange = { if (searchCardsEnabled) draft = draft.copy(languageTags = it) },
                        )
                    }
                    FilterCard(
                        title = tr("Category", "分类", "分類", "カテゴリ"),
                        modifier = Modifier.alpha(if (searchCardsEnabled) 1f else 0.45f),
                    ) {
                        EhentaiCategoryGrid(
                            categories = draft.categories,
                            onChange = { if (searchCardsEnabled) draft = draft.copy(categories = it) },
                        )
                    }
                    FilterCard(
                        title = AppStrings.includeTags,
                        modifier = Modifier.alpha(if (searchCardsEnabled) 1f else 0.45f),
                    ) {
                        FreeTextTagsEditor(
                            tags = draft.ehIncludeTags,
                            placeholder = "female:sole female or tag…",
                            fieldDescription = AppStrings.addEhentaiIncludeTag,
                            testTag = "source-filter-eh-include-tags",
                            namespacePrefixes = EhTagNamespacePrefixes,
                            onChange = { if (searchCardsEnabled) draft = draft.copy(ehIncludeTags = it) },
                        )
                    }
                    FilterCard(
                        title = AppStrings.excludeTags,
                        modifier = Modifier.alpha(if (searchCardsEnabled) 1f else 0.45f),
                    ) {
                        FreeTextTagsEditor(
                            tags = draft.ehExcludeTags,
                            placeholder = "female:ntr or tag to exclude…",
                            fieldDescription = AppStrings.addEhentaiExcludeTag,
                            testTag = "source-filter-eh-exclude-tags",
                            namespacePrefixes = EhTagNamespacePrefixes,
                            onChange = { if (searchCardsEnabled) draft = draft.copy(ehExcludeTags = it) },
                        )
                    }
                    FilterCard(
                        title = AppStrings.minimumRating,
                        modifier = Modifier.alpha(if (searchCardsEnabled) 1f else 0.45f),
                    ) {
                        EhMinRatingChipRow(
                            selectedRating = draft.ehMinRating,
                            enabled = searchCardsEnabled,
                            onChange = { draft = draft.copy(ehMinRating = it) },
                        )
                    }
                    FilterCard(
                        title = AppStrings.pagesHeading,
                        modifier = Modifier.alpha(if (searchCardsEnabled) 1f else 0.45f),
                    ) {
                        PagesRangeEditor(
                            minPages = draft.ehMinPages,
                            maxPages = draft.ehMaxPages,
                            onMinChange = { if (searchCardsEnabled) draft = draft.copy(ehMinPages = it) },
                            onMaxChange = { if (searchCardsEnabled) draft = draft.copy(ehMaxPages = it) },
                        )
                    }
                    FilterCard(
                        title = AppStrings.timeJump,
                        modifier = Modifier.alpha(if (searchCardsEnabled) 1f else 0.45f),
                    ) {
                        EhJumpChipRow(
                            selectedJump = draft.ehJump,
                            enabled = searchCardsEnabled,
                            onChange = { draft = draft.copy(ehJump = it) },
                        )
                    }
                    FilterCard(
                        title = tr("Options", "选项", "選項", "オプション"),
                        modifier = Modifier.alpha(if (searchCardsEnabled) 1f else 0.45f),
                    ) {
                        Column {
                            ScopeToggleRow(
                                label = AppStrings.onlyTorrents,
                                tag = "source-filter-toggle-onlyTorrents",
                                checked = draft.onlyTorrents,
                                onCheckedChange = { if (searchCardsEnabled) draft = draft.copy(onlyTorrents = it) },
                            )
                            ScopeToggleRow(
                                label = AppStrings.includeExpunged,
                                tag = "source-filter-toggle-ehShowExpunged",
                                checked = draft.ehShowExpunged,
                                onCheckedChange = { if (searchCardsEnabled) draft = draft.copy(ehShowExpunged = it) },
                            )
                        }
                    }
                    PclTextButton(
                        onClick = { draft = resetEhentaiFilters(draft) },
                        modifier = Modifier.testTag("source-filter-reset"),
                    ) { Text(AppStrings.resetFilters) }
                }
                SourceFilterSheetShape.NHENTAI -> {
                    val legacyLang = draft.nhLanguage
                    val effectiveNhLanguages: Set<NhLanguage> = when {
                        draft.nhLanguages.isNotEmpty() -> draft.nhLanguages
                        legacyLang != null -> setOf(legacyLang)
                        else -> emptySet()
                    }
                    FilterCard(title = tr("Sort", "排序", "排序", "並び順")) {
                        NhSortChipRow(
                            selected = draft.nhSort,
                            onChange = { draft = draft.copy(nhSort = it) },
                        )
                    }
                    FilterCard(title = tr("Language", "语言", "語言", "言語")) {
                        NhLanguageChipRow(
                            selected = effectiveNhLanguages,
                            onChange = { next ->
                                draft = draft.copy(
                                    nhLanguage = next.singleOrNull(),
                                    nhLanguages = next,
                                )
                            },
                        )
                    }
                    FilterCard(title = tr("Category", "分类", "分類", "カテゴリ")) {
                        NhCategoryChipRow(
                            categories = draft.nhCategories,
                            onChange = { draft = draft.copy(nhCategories = it) },
                        )
                    }
                    FilterCard(title = AppStrings.includeTags) {
                        FreeTextTagsEditor(
                            tags = draft.includeTags,
                            placeholder = "Add tags to include…",
                            fieldDescription = AppStrings.addIncludeTag,
                            testTag = "source-filter-include-tags",
                            namespacePrefixes = NhTagNamespacePrefixes,
                            onChange = { draft = draft.copy(includeTags = it) },
                        )
                    }
                    FilterCard(title = AppStrings.excludeTags) {
                        FreeTextTagsEditor(
                            tags = draft.excludeTags,
                            placeholder = "Add tags to exclude…",
                            fieldDescription = AppStrings.addExcludeTag,
                            testTag = "source-filter-exclude-tags",
                            namespacePrefixes = NhTagNamespacePrefixes,
                            onChange = { draft = draft.copy(excludeTags = it) },
                        )
                    }
                    FilterCard(title = AppStrings.pagesHeading) {
                        PagesRangeEditor(
                            minPages = draft.minPages,
                            maxPages = draft.maxPages,
                            onMinChange = { draft = draft.copy(minPages = it) },
                            onMaxChange = { draft = draft.copy(maxPages = it) },
                        )
                    }
                    FilterCard(title = AppStrings.uploadedWithin) {
                        UploadedWithinChipRow(
                            selectedDays = draft.uploadedWithinDays,
                            onChange = { draft = draft.copy(uploadedWithinDays = it) },
                        )
                    }
                    PclTextButton(
                        onClick = { draft = resetNhentaiFilters(draft) },
                        modifier = Modifier.testTag("source-filter-reset"),
                    ) { Text(AppStrings.resetFilters) }
                }
                SourceFilterSheetShape.ZAIMANHUA, SourceFilterSheetShape.JM -> {
                    val presentation = serializedFilterPresentation(shape, draft, query, genres)
                    presentation.notices.forEach { notice ->
                        Text(notice, style = MaterialTheme.typography.bodySmall, color = palette.muted)
                    }
                    presentation.cards.forEach { filterCard ->
                        FilterCard(
                            title = filterCard.title,
                            modifier = Modifier.alpha(if (filterCard.enabled) 1f else 0.45f),
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                filterCard.choices.forEach { choice ->
                                    FilterChip(
                                        selected = choice.selected,
                                        enabled = filterCard.enabled,
                                        onClick = { draft = choice.select(draft) },
                                        label = { Text(choice.label) },
                                        modifier = Modifier.testTag("source-filter-${filterCard.id}-${choice.id}"),
                                    )
                                }
                            }
                        }
                    }
                    PclTextButton(
                        onClick = { draft = resetSerializedFilters(shape, draft) },
                        enabled = shape != SourceFilterSheetShape.ZAIMANHUA || query.isBlank(),
                        modifier = Modifier.testTag("source-filter-reset"),
                    ) { Text(AppStrings.resetFilters) }
                }
                SourceFilterSheetShape.NONE -> Unit
            }

            PclButton(
                onClick = { onApply(draft) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("source-filter-done"),
            ) {
                Text(AppStrings.done)
            }
        }
    }
}

private val EhTagNamespacePrefixes = listOf(
    "female:",
    "male:",
    "parody:",
    "character:",
    "artist:",
    "group:",
    "other:",
)

private val NhTagNamespacePrefixes = listOf(
    "tag:",
    "parody:",
    "character:",
    "artist:",
    "group:",
)

/** E-Hentai shape (#E113): Feed / Sort mode chip row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EhFeedModeChipRow(
    selected: EhFeedMode,
    onChange: (EhFeedMode) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("source-filter-eh-feed-row"),
    ) {
        EhFeedMode.entries.forEach { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { onChange(mode) },
                label = { Text(mode.displayLabel()) },
                modifier = Modifier.testTag("source-filter-eh-feed-${mode.name}"),
            )
        }
    }
}

private val EhMinRatingOptions: List<Pair<String, Int?>> = listOf(
    "Any" to null,
    "2★+" to 2,
    "3★+" to 3,
    "4★+" to 4,
    "5★" to 5,
)

/** E-Hentai shape (#E113): minimum rating chip row (`f_sr=on&f_srdd=2..5`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EhMinRatingChipRow(
    selectedRating: Int?,
    enabled: Boolean,
    onChange: (Int?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("source-filter-eh-rating-row"),
    ) {
        EhMinRatingOptions.forEach { (label, rating) ->
            FilterChip(
                selected = selectedRating == rating,
                enabled = enabled,
                onClick = { onChange(rating) },
                label = { Text(if (rating == null) tr("Any", "任意", "不限", "指定なし") else label) },
                modifier = Modifier.testTag("source-filter-eh-rating-${rating ?: "any"}"),
            )
        }
    }
}

/** E-Hentai shape (#E113): time jump chip row (`jump=1d|3d|1w|1m|6m|1y`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EhJumpChipRow(
    selectedJump: EhJumpPreset?,
    enabled: Boolean,
    onChange: (EhJumpPreset?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("source-filter-eh-jump-row"),
    ) {
        FilterChip(
            selected = selectedJump == null,
            enabled = enabled,
            onClick = { onChange(null) },
            label = { Text(AppStrings.now) },
            modifier = Modifier.testTag("source-filter-eh-jump-now"),
        )
        EhJumpPreset.entries.forEach { preset ->
            FilterChip(
                selected = selectedJump == preset,
                enabled = enabled,
                onClick = { onChange(if (selectedJump == preset) null else preset) },
                label = { Text(preset.displayLabel()) },
                modifier = Modifier.testTag("source-filter-eh-jump-${preset.param}"),
            )
        }
    }
}

/** E-Hentai shape: category grid with colour dots (spec §3 分类掩码）. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EhentaiCategoryGrid(
    categories: Set<BrowseCategory>,
    onChange: (Set<BrowseCategory>) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PclTextButton(onClick = { onChange(BrowseCategory.entries.toSet()) }) {
            Text(AppStrings.selectAll)
        }
        PclTextButton(onClick = { onChange(emptySet()) }) {
            Text(AppStrings.clear)
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("source-filter-category-grid"),
    ) {
        BrowseCategory.entries.forEach { category ->
            val selected = categories.contains(category)
            FilterChip(
                selected = selected,
                onClick = {
                    onChange(
                        if (selected) categories - category else categories + category,
                    )
                },
                label = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(category.dotColor()),
                        )
                        Text(category.displayLabel())
                    }
                },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

/** nhentai shape (A19): the three real nhentai categories, multi-select. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NhCategoryChipRow(
    categories: Set<NhCategory>,
    onChange: (Set<NhCategory>) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("source-filter-nh-category-row"),
    ) {
        NhCategory.entries.forEach { category ->
            val selected = categories.contains(category)
            FilterChip(
                selected = selected,
                onClick = {
                    onChange(
                        if (selected) categories - category else categories + category,
                    )
                },
                label = { Text(category.displayLabel()) },
            )
        }
    }
}

/**
 * nhentai shape (#E113): multi-select language chip row — selecting 1 of 3
 * emits `language:x`, selecting 2 of 3 emits `-language:<missing>`, and
 * selecting 0 or 3 is unfiltered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NhLanguageChipRow(
    selected: Set<NhLanguage>,
    onChange: (Set<NhLanguage>) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("source-filter-nh-language-row"),
    ) {
        NhLanguage.entries.forEach { language ->
            val isSelected = selected.contains(language)
            FilterChip(
                selected = isSelected,
                onClick = {
                    onChange(
                        if (isSelected) selected - language else selected + language,
                    )
                },
                label = { Text(language.displayLabel()) },
            )
        }
    }
}

/** nhentai shape (A19): single-select sort chip row (mapped to the `sort` URL param). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NhSortChipRow(
    selected: NhSort,
    onChange: (NhSort) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("source-filter-sort-row"),
    ) {
        NhSort.entries.forEach { sort ->
            FilterChip(
                selected = selected == sort,
                onClick = { onChange(sort) },
                label = { Text(sort.displayLabel()) },
            )
        }
    }
}

/** "Uploaded within" preset options: label to day-count (null = unfiltered). */
private val UploadedWithinOptions: List<Pair<String, Int?>> = listOf(
    "Any" to null,
    "1 day" to 1,
    "7 days" to 7,
    "30 days" to 30,
    "1 year" to 365,
)

/** nhentai shape (A19): single-select "uploaded within" chip row (`uploaded:<Nd`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UploadedWithinChipRow(
    selectedDays: Int?,
    onChange: (Int?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("source-filter-uploaded-row"),
    ) {
        UploadedWithinOptions.forEach { (label, days) ->
            FilterChip(
                selected = selectedDays == days,
                onClick = { onChange(days) },
                label = { Text(if (days == null) tr("Any", "任意", "不限", "指定なし") else when (days) {
                    1 -> tr("1 day", "1 天", "1 日", "1日")
                    7 -> tr("7 days", "7 天", "7 日", "7日")
                    30 -> tr("30 days", "30 天", "30 日", "30日")
                    else -> tr("1 year", "1 年", "1 年", "1年")
                }) },
            )
        }
    }
}

/**
 * nhentai shape (A19): inclusive min/max page-count bounds
 * (`NhentaiQueryMapper` encodes these as `pages:>(min-1)` / `pages:<(max+1)`
 * since nhentai's own operators are strictly exclusive).
 */
@Composable
private fun PagesRangeEditor(
    minPages: Int?,
    maxPages: Int?,
    onMinChange: (Int?) -> Unit,
    onMaxChange: (Int?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NumericFilterField(
            modifier = Modifier.weight(1f),
            value = minPages,
            placeholder = tr("Min", "最小", "最小", "最小"),
            testTag = "source-filter-pages-min",
            fieldDescription = AppStrings.minimumPages,
            onChange = onMinChange,
        )
        NumericFilterField(
            modifier = Modifier.weight(1f),
            value = maxPages,
            placeholder = tr("Max", "最大", "最大", "最大"),
            testTag = "source-filter-pages-max",
            fieldDescription = AppStrings.maximumPages,
            onChange = onMaxChange,
        )
    }
}

@Composable
private fun NumericFilterField(
    modifier: Modifier = Modifier,
    value: Int?,
    placeholder: String,
    testTag: String,
    fieldDescription: String,
    onChange: (Int?) -> Unit,
) {
    val palette = quietShelfPalette()
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    BasicTextField(
        value = text,
        onValueChange = { input ->
            val digitsOnly = input.filter { it.isDigit() }
            text = digitsOnly
            onChange(digitsOnly.toIntOrNull())
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = palette.ink),
        cursorBrush = SolidColor(palette.statusOk),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = modifier
            .heightIn(min = 40.dp)
            .testTag(testTag)
            .semantics { contentDescription = fieldDescription },
        decorationBox = { inner ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = palette.card,
                border = BorderStroke(1.dp, palette.line),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (text.isEmpty()) {
                            Text(placeholder, style = MaterialTheme.typography.bodySmall, color = palette.muted)
                        }
                        inner()
                    }
                }
            }
        },
    )
}

/**
 * nhentai shape (A19): freeform include/exclude tag editor — same chip-input
 * pattern as [LanguageTagsEditor] but with no fixed suggestion list (nhentai
 * tags are user-typed, unbounded) and list-ordering (not set-ordering)
 * preserved, since [SourceFilterState.includeTags]/[excludeTags] are Lists.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FreeTextTagsEditor(
    tags: List<String>,
    placeholder: String,
    fieldDescription: String,
    testTag: String,
    namespacePrefixes: List<String> = emptyList(),
    onChange: (List<String>) -> Unit,
) {
    val palette = quietShelfPalette()
    var draftInput by remember { mutableStateOf("") }

    fun commitInput() {
        val normalized = draftInput.trim()
        if (normalized.isEmpty() || normalized.endsWith(":") || tags.contains(normalized)) {
            draftInput = ""
            return
        }
        onChange(tags + normalized)
        draftInput = ""
    }

    if (tags.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            tags.forEach { tag ->
                FilterChip(
                    selected = true,
                    onClick = { onChange(tags - tag) },
                    label = { Text(tag) },
                )
            }
        }
    }

    BasicTextField(
        value = draftInput,
        onValueChange = { draftInput = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = palette.ink),
        cursorBrush = SolidColor(palette.statusOk),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commitInput() }),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .testTag(testTag)
            .semantics { contentDescription = fieldDescription },
        decorationBox = { inner ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = palette.card,
                border = BorderStroke(1.dp, palette.line),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (draftInput.isEmpty()) {
                            Text(placeholder, style = MaterialTheme.typography.bodySmall, color = palette.muted)
                        }
                        inner()
                    }
                }
            }
        },
    )

    if (namespacePrefixes.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            namespacePrefixes.forEach { prefix ->
                FilterChip(
                    selected = draftInput.startsWith(prefix, ignoreCase = true),
                    onClick = {
                        val stripped = draftInput.substringAfter(':', draftInput).trimStart()
                        draftInput = if (draftInput.startsWith(prefix, ignoreCase = true)) {
                            stripped
                        } else {
                            prefix + stripped
                        }
                    },
                    label = { Text(prefix) },
                )
            }
        }
    }
}

@Composable
private fun ScopeToggleRow(
    label: String,
    tag: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Switch(checked = checked, onCheckedChange = null)
        Text(label)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageTagsEditor(
    tags: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    val palette = quietShelfPalette()
    var draftInput by remember { mutableStateOf("") }

    fun commitInput() {
        val normalized = normalizeLanguageTag(draftInput) ?: return
        onChange(tags + normalized)
        draftInput = ""
    }

    if (tags.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            tags.sorted().forEach { tag ->
                FilterChip(
                    selected = true,
                    onClick = { onChange(tags - tag) },
                    label = { Text(tag) },
                )
            }
        }
    }

    BasicTextField(
        value = draftInput,
        onValueChange = { draftInput = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = palette.ink),
        cursorBrush = SolidColor(palette.statusOk),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commitInput() }),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .semantics { contentDescription = AppStrings.addLanguageTag },
        decorationBox = { inner ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = palette.card,
                border = BorderStroke(1.dp, palette.line),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (draftInput.isEmpty()) {
                            Text(
                                tr("Add language tags…", "添加语言标签…", "新增語言標籤…", "言語タグを追加…"),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.muted,
                            )
                        }
                        inner()
                    }
                }
            }
        },
    )

    val suggestions = SuggestedBrowseLanguageTags.filterNot { tags.contains(it) }.take(12)
    if (suggestions.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            suggestions.forEach { suggestion ->
                FilterChip(
                    selected = false,
                    onClick = { onChange(tags + suggestion) },
                    label = { Text(suggestion) },
                )
            }
        }
    }
}

@Composable
private fun FilterCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = quietShelfPalette()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = palette.card,
        border = BorderStroke(1.dp, palette.line),
        modifier = Modifier.fillMaxWidth().then(modifier),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = palette.muted,
            )
            content()
        }
    }
}
