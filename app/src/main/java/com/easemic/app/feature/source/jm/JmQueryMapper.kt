package com.easemic.app.feature.source.jm

import com.easemic.app.feature.browse.JCategory
import com.easemic.app.feature.browse.JSort
import com.easemic.app.feature.browse.JTime
import com.easemic.app.feature.browse.JmFilters
import com.easemic.app.feature.browse.JmSearchScope

internal object JmQueryMapper {
    fun parameters(f: JmFilters, query: String, page: Int, scope: JmSearchScope? = null): List<Pair<String, String>> {
        require(page > 0)
        val order = when (f.sort) {
            JSort.RECENT -> "mr"
            JSort.VIEWS -> "mv"
            JSort.LIKES -> "tf"
            JSort.PAGES -> "mp"
        }
        val time = when (f.effectiveTime) {
            JTime.ALL -> "a"
            JTime.TODAY -> "t"
            JTime.WEEK -> "w"
            JTime.MONTH -> "m"
        }
        if (query.isNotBlank()) {
            return listOf(
                "main_tag" to when (scope) {
                    JmSearchScope.TAG -> "3"
                    JmSearchScope.AUTHOR -> "2"
                    null -> "0"
                },
                "search_query" to query,
                "page" to page.toString(),
                "o" to order,
                "t" to time,
            )
        }
        val category = when (f.category) {
            JCategory.ALL -> "0"
            JCategory.DOUJINSHI -> "doujin"
            JCategory.STANDALONE -> "single"
            JCategory.SHORT_STORIES -> "short"
            JCategory.KOREAN -> "hanman"
            JCategory.WESTERN -> "meiman"
            JCategory.OTHER -> "another"
        }
        return listOf(
            "page" to page.toString(),
            "order" to "",
            "c" to category,
            "o" to if (time == "a") order else "${order}_$time",
        )
    }
}
