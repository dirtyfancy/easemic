package com.easemic.app.feature.browse

import com.easemic.app.importing.nhentai.NhentaiListQuery

/**
 * Maps one source's filter state to an nhentai search query.
 *
 * nhentai's `/api/v2/search` query grammar has **no OR / grouping operator**.
 * `(a OR b)` is parsed as two literal AND-ed tokens and silently collapses
 * results to near-zero — curl-verified 2026-09-04:
 *  - `(tag:"doujinshi" OR tag:"manga")` → `total=1`, vs `tag:"doujinshi"`
 *    alone → `total=262` and `tag:"manga"` alone → `total=538`.
 *  - `(language:"english" OR language:"japanese")` → `total=2`, vs
 *    `language:"english"` alone → `total=146450`.
 * AND (space-separated terms, native) and negation (`-tag:"x"`, curl-verified
 * working) both work, so every clause below is built from those only.
 *
 * ## Legacy vs. nh-field path (A19)
 *
 * [SourceFilterState] carries both the original E-Hentai-shaped fields
 * (`languageTags` / `categories`, reused across sources before A19) and the
 * new nhentai-specific fields added for A19's tailored filter sheet
 * (`nhCategories` / `nhLanguage` / `includeTags` / `excludeTags` / `minPages`
 * / `maxPages` / `nhSort` / `uploadedWithinDays`). [from] picks exactly one
 * path:
 *  - any nh-specific field away from its default → the **nh-field path**
 *    ([fromNhFields]), which the new nhentai filter sheet exclusively writes
 *    to from now on;
 *  - otherwise → the **legacy path** ([fromLegacyFields]), preserved
 *    byte-for-byte from the pre-A19 implementation so every already-persisted
 *    [SourceFilterState] (and this file's original 17 tests) keeps mapping
 *    exactly as before.
 *
 * Legacy-path behaviour (`languageTags` / `categories`):
 *  - **Languages**: multi-select would require OR, which is unsupported, so
 *    a clause is only emitted when exactly one language is selected; zero or
 *    2+ selected languages emit no clause at all (documented limitation:
 *    nhentai Browse can only narrow by a single language at a time).
 *  - **Categories**: nhentai's `category:` namespace does not exist — it is
 *    silently ignored server-side (curl-verified: `query=category:doujinshi`
 *    behaves identically to an unfiltered query). Only three of the ten
 *    [BrowseCategory] values are real nhentai categories — doujinshi / manga
 *    / misc — and every gallery carries exactly one of the three, so
 *    selecting exactly two of three is expressible without OR as excluding
 *    the third (`-tag:"x"`); selecting exactly one is a direct `tag:"x"`;
 *    selecting all three (or none of the ten Browse categories) is
 *    unrestricted. Selecting zero of the three real categories (while still
 *    having some of the other seven selected) cannot be expressed without OR
 *    either and is treated the same as "no clause" for this dimension
 *    (documented limitation). The remaining six Browse categories
 *    (artist cg / game cg / western / non-h / image set / cosplay) are
 *    ordinary tags, not `category:`-typed on nhentai; at most one can be
 *    expressed at a time (no OR), so a clause is only emitted when exactly
 *    one of them is selected — two or more selected together are ignored
 *    (documented limitation). ASIAN_PORN has no nhentai equivalent and never
 *    contributes to the query.
 *
 * nh-field-path behaviour ([fromNhFields]):
 *  - **nhCategories**: same exclusion-complement rule as the legacy real
 *    categories above, over exactly the three real [NhCategory] values (1 of
 *    3 → `tag:"x"`; 2 of 3 → `-tag:"missing"`; 0 or 3 → unrestricted).
 *  - **nhLanguage**: single-select by construction (a nullable enum, not a
 *    set) → `language:x` when set, nothing when null.
 *  - **includeTags** / **excludeTags**: one always-quoted `tag:"x"` /
 *    `-tag:"x"` clause per entry, AND-ed together (never OR — see class doc).
 *  - **minPages** / **maxPages**: nhentai's `pages:>N` / `pages:<N` operators
 *    are *strictly* exclusive of `N` — curl-verified 2026-09-04:
 *    `pages:>19` → `num_pages=19550`, `pages:>20` → `18732`, `pages:20`
 *    (exact) → `819`. An **inclusive** minPages/maxPages bound is therefore
 *    encoded as `pages:>(min-1)` / `pages:<(max+1)`.
 *  - **uploadedWithinDays**: `uploaded:<Nd` (curl-verified working, e.g.
 *    `uploaded:<7d` → `num_pages=86`).
 *  - **nhSort**: mapped to nhentai's `sort` URL param via [sortParam] (not
 *    part of the `query=` DSL at all) and carried on [NhentaiListQuery.sort].
 *    Curl-verified 2026-09-04: `/api/v2/search?query=&sort=popular` → HTTP
 *    400 ("query must have at least 1 character"); `/api/v2/galleries` (the
 *    "latest" endpoint) silently *ignores* `sort` entirely (identical first
 *    result with/without it). So when every other nh-field clause is empty
 *    but a non-default sort is set, the search endpoint must still be used —
 *    with a `*` wildcard query, curl-verified to statistically match the
 *    unfiltered latest feed (num_pages 25730 vs. the latest endpoint's
 *    25679, identical first result id) while actually honouring `sort`.
 */
object NhentaiQueryMapper {
    /** Converts one literal metadata value into nhentai's native search DSL. */
    fun tagQuery(rawTag: String): String {
        val raw = rawTag.trim()
        val colon = raw.indexOf(':')
        val namespace = if (colon > 0) raw.substring(0, colon).trim() else "tag"
        val value = if (colon > 0) raw.substring(colon + 1).trim() else raw
        val literal = value.removeSurrounding("\"")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return "$namespace:\"$literal\""
    }

    /** The three BrowseCategory values that map to nhentai's real `category:`-typed tags. */
    private val REAL_CATEGORY_TOKENS: Map<BrowseCategory, String> = linkedMapOf(
        BrowseCategory.DOUJINSHI to "doujinshi",
        BrowseCategory.MANGA to "manga",
        BrowseCategory.MISC to "misc",
    )

    /** The remaining mappable Browse categories, which are ordinary nhentai tags. */
    private val TAG_ONLY_CATEGORY_TOKENS: Map<BrowseCategory, String> = linkedMapOf(
        BrowseCategory.ARTIST_CG to "artist cg",
        BrowseCategory.GAME_CG to "game cg",
        BrowseCategory.WESTERN to "western",
        BrowseCategory.NON_H to "non-h",
        BrowseCategory.IMAGE_SET to "image set",
        BrowseCategory.COSPLAY to "cosplay",
    )

    /** [NhCategory] → nhentai tag token, in the fixed exclusion-complement order. */
    private val NH_CATEGORY_TOKENS: Map<NhCategory, String> = linkedMapOf(
        NhCategory.DOUJINSHI to "doujinshi",
        NhCategory.MANGA to "manga",
        NhCategory.MISC to "misc",
    )

    /** Match-everything query used when nh-field clauses are all empty but a sort is set (see class doc). */
    private const val WILDCARD_QUERY = "*"

    fun from(state: SourceFilterState, query: String, pageIndex: Int = 0): NhentaiListQuery =
        if (state.hasNhSpecificFilters()) {
            fromNhFields(state, query, pageIndex)
        } else {
            fromLegacyFields(state, query, pageIndex)
        }

    private fun fromLegacyFields(state: SourceFilterState, query: String, pageIndex: Int): NhentaiListQuery {
        val parts = listOfNotNull(
            query.trim().takeIf { it.isNotEmpty() },
            languageSearchClause(state.languageTags).takeIf { it.isNotEmpty() },
            categorySearchClause(state.categories).takeIf { it.isNotEmpty() },
        )
        return NhentaiListQuery(
            searchQuery = parts.joinToString(" ").takeIf { it.isNotEmpty() },
            pageIndex = pageIndex,
        )
    }

    private fun fromNhFields(state: SourceFilterState, query: String, pageIndex: Int): NhentaiListQuery {
        val effectiveNhLanguages = when {
            state.nhLanguages.isNotEmpty() -> state.nhLanguages
            state.nhLanguage != null -> setOf(state.nhLanguage)
            else -> emptySet()
        }
        val parts = listOfNotNull(
            query.trim().takeIf { it.isNotEmpty() },
            nhCategoryClause(state.nhCategories).takeIf { it.isNotEmpty() },
            nhLanguageClause(effectiveNhLanguages).takeIf { it.isNotEmpty() },
            includeTagsClause(state.includeTags).takeIf { it.isNotEmpty() },
            excludeTagsClause(state.excludeTags).takeIf { it.isNotEmpty() },
            state.minPages?.let { "pages:>${it - 1}" },
            state.maxPages?.let { "pages:<${it + 1}" },
            state.uploadedWithinDays?.let { "uploaded:<${it}d" },
        )
        val sort = sortParam(state.nhSort)
        val joined = parts.joinToString(" ")
        val effectiveQuery = when {
            joined.isNotEmpty() -> joined
            sort != null -> WILDCARD_QUERY
            else -> null
        }
        return NhentaiListQuery(searchQuery = effectiveQuery, pageIndex = pageIndex, sort = sort)
    }

    private fun SourceFilterState.hasNhSpecificFilters(): Boolean =
        nhCategories.isNotEmpty() ||
            nhLanguage != null ||
            nhLanguages.isNotEmpty() ||
            includeTags.isNotEmpty() ||
            excludeTags.isNotEmpty() ||
            minPages != null ||
            maxPages != null ||
            nhSort != NhSort.RECENT ||
            uploadedWithinDays != null

    /**
     * nhentai search has no OR operator, so multi-language filtering cannot
     * be expressed. A clause is only emitted for exactly one selected
     * language; zero or 2+ selected languages → no clause (documented
     * limitation, see class doc).
     */
    fun languageSearchClause(tags: Set<String>): String {
        val normalized = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (normalized.size != 1) return ""
        return "language:${normalized.single()}"
    }

    /**
     * Empty set or all ten Browse categories → no clause (unfiltered).
     * See the class doc for the exclusion-complement rule used for the three
     * real categories, and the single-selection-only rule for the rest.
     */
    fun categorySearchClause(selected: Set<BrowseCategory>): String {
        if (selected.isEmpty() || selected.containsAll(BrowseCategory.entries)) {
            return ""
        }
        val realSelected = REAL_CATEGORY_TOKENS.keys.filter { it in selected }
        val tagOnlySelected = TAG_ONLY_CATEGORY_TOKENS.keys.filter { it in selected }

        val clauses = mutableListOf<String>()
        when (realSelected.size) {
            1 -> clauses += "tag:${formatCategoryValue(REAL_CATEGORY_TOKENS.getValue(realSelected.single()))}"
            2 -> {
                val excluded = REAL_CATEGORY_TOKENS.keys.first { it !in realSelected }
                clauses += "-tag:${formatCategoryValue(REAL_CATEGORY_TOKENS.getValue(excluded))}"
            }
            // 0 selected: can't express "none of the 3" without OR reasoning
            // that would be ambiguous here → ignored (documented limitation).
            // 3 selected: unrestricted on this dimension → nothing to emit.
        }
        if (tagOnlySelected.size == 1) {
            clauses += "tag:${formatCategoryValue(TAG_ONLY_CATEGORY_TOKENS.getValue(tagOnlySelected.single()))}"
        }
        // 2+ tag-only categories selected together would need OR → ignored
        // (documented limitation, see class doc).
        return clauses.joinToString(" ")
    }

    /**
     * Empty set or all three [NhCategory] values → no clause (unfiltered).
     * Exactly one selected → direct inclusion; exactly two → exclusion of
     * the third (every gallery carries exactly one of the three, so the
     * union of two is the complement of one — no OR required).
     */
    fun nhCategoryClause(selected: Set<NhCategory>): String {
        if (selected.isEmpty() || selected.size == NhCategory.entries.size) return ""
        return when (selected.size) {
            1 -> "tag:${formatCategoryValue(NH_CATEGORY_TOKENS.getValue(selected.single()))}"
            2 -> {
                val excluded = NhCategory.entries.first { it !in selected }
                "-tag:${formatCategoryValue(NH_CATEGORY_TOKENS.getValue(excluded))}"
            }
            else -> ""
        }
    }

    /**
     * Empty set or all three [NhLanguage] values → no clause (unfiltered).
     * Exactly one selected → `language:x`; exactly two → `-language:<missing>`
     * (every nhentai gallery carries one of the three languages, so the union
     * of two is the complement of the third — live-verified 2026-10-14).
     */
    fun nhLanguageClause(selected: Set<NhLanguage>): String {
        if (selected.isEmpty() || selected.size == NhLanguage.entries.size) return ""
        return when (selected.size) {
            1 -> "language:${selected.single().name.lowercase()}"
            2 -> {
                val excluded = NhLanguage.entries.first { it !in selected }
                "-language:${excluded.name.lowercase()}"
            }
            else -> ""
        }
    }

    /** One namespace-aware `ns:"x"` clause per include tag, AND-ed (never OR — see class doc). */
    fun includeTagsClause(tags: List<String>): String =
        tags.map { it.trim().removePrefix("+").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { tagQuery(it) }

    /** One namespace-aware `-ns:"x"` clause per exclude tag, AND-ed (never OR — see class doc). */
    fun excludeTagsClause(tags: List<String>): String =
        tags.map { it.trim().removePrefix("-").removePrefix("\u2212").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "-${tagQuery(it)}" }

    /**
     * Maps [NhSort] to nhentai's `sort` URL query param (not part of the
     * `query=` DSL). [NhSort.RECENT] is the default and maps to no param.
     */
    fun sortParam(sort: NhSort): String? = when (sort) {
        NhSort.RECENT -> null
        NhSort.POPULAR -> "popular"
        NhSort.POPULAR_TODAY -> "popular-today"
        NhSort.POPULAR_WEEK -> "popular-week"
        NhSort.POPULAR_MONTH -> "popular-month"
    }

    /**
     * Maps a Browse category to its nhentai tag token (without `tag:`
     * prefix or quoting). Null when there is no nhentai equivalent
     * (ASIAN_PORN).
     */
    fun categoryToken(category: BrowseCategory): String? =
        REAL_CATEGORY_TOKENS[category] ?: TAG_ONLY_CATEGORY_TOKENS[category]

    /** nhentai tag values are always double-quoted, single word or not. */
    private fun formatCategoryValue(token: String): String = "\"$token\""
}
