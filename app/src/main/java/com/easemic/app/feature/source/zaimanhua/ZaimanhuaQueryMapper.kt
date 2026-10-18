package com.easemic.app.feature.source.zaimanhua

import com.easemic.app.feature.browse.*

internal enum class ZBrowseRoute { LATEST, FILTER, SEARCH, RANK }
internal data class ZBrowseRequest(val route: ZBrowseRoute, val page: Int, val query: List<Pair<String, String>>)
internal object ZaimanhuaQueryMapper {
    fun from(f: ZaimanhuaFilters, query: String, page: Int): ZBrowseRequest {
        require(page > 0)
        if (query.isNotBlank()) return ZBrowseRequest(ZBrowseRoute.SEARCH, page,
            listOf("source" to "0", "size" to "20", "keyword" to query, "page" to page.toString()))
        f.ranking?.let { ranking ->
            val byTime = when (ranking) { ZRanking.TODAY -> "0"; ZRanking.WEEK -> "1"; ZRanking.MONTH -> "2" }
            return ZBrowseRequest(ZBrowseRoute.RANK, page,
                listOf("by_time" to byTime, "rank_type" to "0", "tag_id" to "0", "page" to page.toString()))
        }
        if (!f.hasCatalogFilters) return ZBrowseRequest(ZBrowseRoute.LATEST, page, emptyList())
        val status = when (f.status) { ZStatus.ANY -> "0"; ZStatus.ONGOING -> "2309"; ZStatus.COMPLETED -> "2310"; ZStatus.SHORT_STORIES -> "29205" }
        val cate = when (f.audience) { ZAudience.ANY -> "0"; ZAudience.SHONEN -> "3262"; ZAudience.SHOJO -> "3263"; ZAudience.SEINEN -> "3264"; ZAudience.JOSEI -> "13626" }
        val zone = when (f.region) {
            ZRegion.ANY -> "0"; ZRegion.JAPAN -> "2304"; ZRegion.KOREA -> "2305"; ZRegion.EUROPE_NORTH_AMERICA -> "2306"
            ZRegion.HONG_KONG_TAIWAN -> "2307"; ZRegion.MAINLAND_CHINA -> "2308"; ZRegion.OTHER -> "8435"
        }
        return ZBrowseRequest(ZBrowseRoute.FILTER, page, listOf("size" to "20", "page" to page.toString(),
            "sortType" to if (f.sort == ZSort.POPULAR) "2" else "1", "status" to status,
            "cate" to cate, "zone" to zone, "theme" to f.genre.id.toString()))
    }
}
