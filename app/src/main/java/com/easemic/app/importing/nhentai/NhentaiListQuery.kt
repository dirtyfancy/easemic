package com.easemic.app.importing.nhentai

/**
 * List/search query for nhentai API v2.
 * [searchQuery] null/blank means unfiltered latest galleries.
 * [sort] is a separate URL query param (not part of the `query=` DSL) — see
 * `NhentaiQueryMapper` doc for why it forces the search endpoint even when
 * [searchQuery] would otherwise be empty (nhentai's "latest" endpoint
 * silently ignores `sort`).
 */
data class NhentaiListQuery(
    val searchQuery: String? = null,
    val pageIndex: Int = 0,
    val sort: String? = null,
) {
    val useLatest: Boolean get() = searchQuery.isNullOrBlank()
}
