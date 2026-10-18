package com.easemic.app.feature.browse

import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr

data class SerializedFilterChoice(
    val id: String,
    val label: String,
    val selected: Boolean,
    val select: (SourceFilterState) -> SourceFilterState,
)

data class SerializedFilterCard(
    val id: String,
    val title: String,
    val enabled: Boolean,
    val choices: List<SerializedFilterChoice>,
)

data class SerializedFilterPresentation(
    val cards: List<SerializedFilterCard>,
    val chips: List<ActiveFilterChip>,
    val notices: List<String>,
    val zaimanhuaSignInNotice: Boolean = false,
)

private fun <T : FilterLabel> card(
    id: String,
    title: String,
    enabled: Boolean,
    values: List<T>,
    selected: T?,
    key: (T) -> String,
    display: (T) -> String = { it.label },
    change: (SourceFilterState, T) -> SourceFilterState,
) = SerializedFilterCard(
    id = id,
    title = title,
    enabled = enabled,
    choices = values.map { value ->
        SerializedFilterChoice(key(value), display(value), value == selected) { state -> change(state, value) }
    },
)

fun resetSerializedFilters(shape: SourceFilterSheetShape, state: SourceFilterState): SourceFilterState = when (shape) {
    SourceFilterSheetShape.ZAIMANHUA -> state.copy(zaimanhua = ZaimanhuaFilters())
    SourceFilterSheetShape.JM -> state.copy(jm = JmFilters())
    else -> state
}

fun serializedFilterPresentation(
    shape: SourceFilterSheetShape,
    state: SourceFilterState,
    query: String,
    genres: List<ZGenre>,
    notice: BrowseFeedNotice? = null,
): SerializedFilterPresentation {
    val searching = query.isNotBlank()
    val chips = mutableListOf<ActiveFilterChip>()
    val notices = mutableListOf<String>()
    fun chip(label: String, id: String, remove: (SourceFilterState) -> SourceFilterState) {
        chips += ActiveFilterChip(label, "browse-filter-chip-$id", remove)
    }
    val cards = when (shape) {
        SourceFilterSheetShape.ZAIMANHUA -> {
            val f = state.zaimanhua
            val enabled = !searching && f.ranking == null
            val choices = genres.toMutableList()
            if (choices.none { it.id == f.genre.id }) choices += f.genre
            val selectedGenre = choices.first { it.id == f.genre.id }
            if (searching) notices += AppStrings.browseFiltersPauseWhileSearching
            else if (f.ranking != null) {
                chip("${tr("Ranking: ", "排行：", "排行：", "ランキング：")}${f.ranking.displayLabel()}", "z-ranking") {
                    it.copy(zaimanhua = it.zaimanhua.copy(ranking = null))
                }
            } else {
                if (f.sort != ZSort.UPDATED) chip(f.sort.displayLabel(), "z-sort") {
                    it.copy(zaimanhua = it.zaimanhua.copy(sort = ZSort.UPDATED))
                }
                if (f.genre.id != 0L) chip("${tr("Genre: ", "题材：", "題材：", "ジャンル：")}${selectedGenre.label}", "z-genre") {
                    it.copy(zaimanhua = it.zaimanhua.copy(genre = ZGenre.ANY))
                }
                if (f.region != ZRegion.ANY) chip("${tr("Region: ", "地区：", "地區：", "地域：")}${f.region.displayLabel()}", "z-region") {
                    it.copy(zaimanhua = it.zaimanhua.copy(region = ZRegion.ANY))
                }
                if (f.audience != ZAudience.ANY) chip("${tr("Audience: ", "受众：", "受眾：", "対象：")}${f.audience.displayLabel()}", "z-audience") {
                    it.copy(zaimanhua = it.zaimanhua.copy(audience = ZAudience.ANY))
                }
                if (f.status != ZStatus.ANY) chip(f.status.displayLabel(), "z-status") {
                    it.copy(zaimanhua = it.zaimanhua.copy(status = ZStatus.ANY))
                }
            }
            listOf(
                card("ranking", tr("Rankings", "排行", "排行", "ランキング"), !searching, ZRanking.entries, f.ranking, { it.name }, { it.displayLabel() }) { s, v ->
                    s.copy(zaimanhua = s.zaimanhua.copy(ranking = v.takeUnless { s.zaimanhua.ranking == v }))
                },
                card("sort", tr("Sort", "排序", "排序", "並び順"), enabled, ZSort.entries, f.sort, { it.name }, { it.displayLabel() }) { s, v ->
                    s.copy(zaimanhua = s.zaimanhua.copy(sort = v))
                },
                card("genre", tr("Genre", "题材", "題材", "ジャンル"), enabled, choices, selectedGenre, { it.id.toString() }, { if (it.id == 0L) tr("Any", "任意", "不限", "指定なし") else it.label }) { s, v ->
                    s.copy(zaimanhua = s.zaimanhua.copy(genre = v))
                },
                card("region", tr("Region", "地区", "地區", "地域"), enabled, ZRegion.entries, f.region, { it.name }, { it.displayLabel() }) { s, v ->
                    s.copy(zaimanhua = s.zaimanhua.copy(region = v))
                },
                card("audience", tr("Audience", "受众", "受眾", "対象"), enabled, ZAudience.entries, f.audience, { it.name }, { it.displayLabel() }) { s, v ->
                    s.copy(zaimanhua = s.zaimanhua.copy(audience = v))
                },
                card("status", tr("Status", "状态", "狀態", "状態"), enabled, ZStatus.entries, f.status, { it.name }, { it.displayLabel() }) { s, v ->
                    s.copy(zaimanhua = s.zaimanhua.copy(status = v))
                },
            )
        }
        SourceFilterSheetShape.JM -> {
            val f = state.jm
            if (searching) notices += AppStrings.categoryPauseWhileSearching
            if (f.sort != JSort.VIEWS) notices += AppStrings.timeRangeMostViewed
            if (!searching && f.category != JCategory.ALL) chip(f.category.displayLabel(), "j-category") {
                it.copy(jm = it.jm.copy(category = JCategory.ALL))
            }
            if (f.sort != JSort.RECENT) chip(f.sort.displayLabel(), "j-sort") {
                it.copy(jm = it.jm.copy(sort = JSort.RECENT))
            }
            if (f.effectiveTime != JTime.ALL) chip(f.time.displayLabel(), "j-time") {
                it.copy(jm = it.jm.copy(time = JTime.ALL))
            }
            listOf(
                card("category", tr("Category", "分类", "分類", "カテゴリ"), !searching, JCategory.entries, f.category, { it.name }, { it.displayLabel() }) { s, v ->
                    s.copy(jm = s.jm.copy(category = v))
                },
                card("sort", tr("Sort", "排序", "排序", "並び順"), true, JSort.entries, f.sort, { it.name }, { it.displayLabel() }) { s, v ->
                    s.copy(jm = s.jm.copy(sort = v))
                },
                card("time", tr("Time range", "时间范围", "時間範圍", "期間"), f.sort == JSort.VIEWS, JTime.entries, f.time, { it.name }, { it.displayLabel() }) { s, v ->
                    s.copy(jm = s.jm.copy(time = v))
                },
            )
        }
        else -> emptyList()
    }
    if (shape == SourceFilterSheetShape.JM) {
        when (notice) {
            BrowseFeedNotice.ALBUM_MATCH -> {
                chips.clear()
                notices.clear()
                notices += AppStrings.albumMatchFiltersDoNotApply
            }
            is BrowseFeedNotice.JmScopedFallback -> {
                notices.clear()
                val scope = when (notice.scope) {
                    JmSearchScope.AUTHOR -> "author"
                    JmSearchScope.TAG -> "tag"
                }
                notices += AppStrings.noScopeResults(scope, notice.query)
            }
            BrowseFeedNotice.ZAIMANHUA_SIGNED_OUT, null -> Unit
        }
    }
    val zaimanhuaSignInNotice =
        shape == SourceFilterSheetShape.ZAIMANHUA && !searching &&
            notice == BrowseFeedNotice.ZAIMANHUA_SIGNED_OUT
    return SerializedFilterPresentation(cards, chips, notices, zaimanhuaSignInNotice)
}
