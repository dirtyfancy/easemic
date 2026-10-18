package com.easemic.app.feature.browse

import com.easemic.app.importing.ehentai.EhentaiListQuery

/**
 * Maps one source's filter state to an E-Hentai list query (language OR +
 * category hide mask).
 */
object EhentaiQueryMapper {
    fun exactTagQuery(rawTag: String): String {
        val raw = rawTag.trim()
        val colon = raw.indexOf(':')
        val namespace = raw.substringBefore(':', "").trim()
        val value = if (colon >= 0) raw.substring(colon + 1).trim() else raw
        val alreadyExact = value.length >= 2 &&
            value.startsWith("\"") &&
            value.endsWith("\"") &&
            value.drop(1).dropLast(1).endsWith("$")
        if (alreadyExact) return raw
        if (colon >= 0 && value.endsWith("$") && !value.startsWith("\"")) return raw
        val literal = value.removeSurrounding("\"")
            .removeSuffix("$")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val exact = "\"$literal$\""
        return if (namespace.isEmpty()) exact else "$namespace:$exact"
    }

    fun from(
        state: SourceFilterState,
        query: String,
        pageIndex: Int = 0,
        seekNextGid: Long? = null,
    ): EhentaiListQuery {
        val user = query.trim()
        if (user.isEmpty() && state.ehFeedMode != EhFeedMode.LATEST) {
            return when (state.ehFeedMode) {
                EhFeedMode.POPULAR -> EhentaiListQuery(popular = true)
                EhFeedMode.TOP_YESTERDAY,
                EhFeedMode.TOP_MONTH,
                EhFeedMode.TOP_YEAR,
                EhFeedMode.TOP_ALL_TIME,
                -> EhentaiListQuery(
                    toplistCode = state.ehFeedMode.toplistCode,
                    pageIndex = pageIndex,
                )
                EhFeedMode.LATEST -> EhentaiListQuery()
            }
        }
        val parts = listOfNotNull(
            user.takeIf { it.isNotEmpty() },
            languageSearchClause(state.languageTags).takeIf { it.isNotEmpty() },
            includeTagsClause(state.ehIncludeTags).takeIf { it.isNotEmpty() },
            excludeTagsClause(state.ehExcludeTags).takeIf { it.isNotEmpty() },
        )
        return EhentaiListQuery(
            fSearch = parts.joinToString(" ").takeIf { it.isNotEmpty() },
            fCats = categoryHideMask(state.categories),
            pageIndex = pageIndex,
            seekNextGid = seekNextGid,
            onlyTorrents = state.onlyTorrents,
            showExpunged = state.ehShowExpunged,
            minRating = state.ehMinRating?.takeIf { it in 2..5 },
            minPages = state.ehMinPages,
            maxPages = state.ehMaxPages,
            jumpParam = state.ehJump?.param,
        )
    }

    fun includeTagsClause(tags: List<String>): String =
        tags.map { it.trim().removePrefix("+").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { exactTagQuery(it) }

    fun excludeTagsClause(tags: List<String>): String =
        tags.map { it.trim().removePrefix("-").removePrefix("\u2212").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "-${exactTagQuery(it)}" }

    fun languageSearchClause(tags: Set<String>): String {
        val normalized = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted()
        if (normalized.isEmpty()) return ""
        if (normalized.size == 1) return "language:${normalized.single()}$"
        return normalized.joinToString(" ") { "~language:$it$" }
    }

    /**
     * Returns null when there is no category filter (empty set or all ten selected).
     * Otherwise OR of EHWiki hide bits for categories not in [selected].
     */
    fun categoryHideMask(selected: Set<BrowseCategory>): Int? {
        if (selected.isEmpty() || selected.containsAll(BrowseCategory.entries)) {
            return null
        }
        var mask = 0
        for (category in BrowseCategory.entries) {
            if (category !in selected) {
                mask = mask or hideBit(category)
            }
        }
        return mask.takeIf { it != 0 }
    }

    fun hideBit(category: BrowseCategory): Int = when (category) {
        BrowseCategory.MISC -> 1
        BrowseCategory.DOUJINSHI -> 2
        BrowseCategory.MANGA -> 4
        BrowseCategory.ARTIST_CG -> 8
        BrowseCategory.GAME_CG -> 16
        BrowseCategory.IMAGE_SET -> 32
        BrowseCategory.COSPLAY -> 64
        BrowseCategory.ASIAN_PORN -> 128
        BrowseCategory.NON_H -> 256
        BrowseCategory.WESTERN -> 512
    }
}
