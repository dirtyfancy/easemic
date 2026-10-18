package com.easemic.app.feature.library

/**
 * Bug fix (owner-reported on-device 2026-09-04): the shelf body used to be
 * driven by a single inline boolean (`showEmpty` = filteredEntries.isEmpty()
 * && !isLoading && !searchMode), which conflated two very different states —
 * a genuinely empty library (nothing ever imported/collected/mirrored) and a
 * populated library whose active state filter (or search query) happens to
 * match zero entries. Both rendered the same "Private library / Import
 * comics" empty state, and that empty state replaces the whole shelf
 * *including the header row* (search + filter buttons), so a no-match filter
 * combination was a dead end — no way to see the filter was active or clear
 * it.
 *
 * [libraryShelfMode] is the single source of truth for which shelf body
 * [LibraryScreen] renders. It is pure and side-effect-free so every branch
 * is independently JVM-testable without Compose; [LibraryScreen] only maps
 * the resulting [LibraryShelfMode] onto composables.
 */
enum class LibraryShelfMode {
    /** Nothing has ever been imported/collected/cloud-mirrored: show the import CTA. */
    EMPTY_LIBRARY,

    /** Shelf hasn't hydrated yet: show the loading skeleton. */
    LOADING,

    /** The shelf has entries, but the active state filter matches none of them. */
    NO_FILTER_MATCH,

    /** In search mode with a non-blank query that matches nothing. */
    NO_SEARCH_MATCH,

    /** Normal case: render the list/grid of filtered entries. */
    CONTENT,
}

/**
 * @param shelfCount total merged shelf size (local + collected + cloud), before filter/search.
 * @param filteredCount shelf size after the state filter and (if active) the search query are applied.
 * @param filterActive whether [LibraryStateFilter.any] is true.
 * @param searchMode whether the header is currently in search mode.
 * @param searchQueryBlank whether the live search query is blank.
 * @param isLoading whether the shelf is still hydrating.
 */
fun libraryShelfMode(
    shelfCount: Int,
    filteredCount: Int,
    filterActive: Boolean,
    searchMode: Boolean,
    searchQueryBlank: Boolean,
    isLoading: Boolean,
): LibraryShelfMode = when {
    isLoading && shelfCount == 0 -> LibraryShelfMode.LOADING
    shelfCount == 0 && !searchMode -> LibraryShelfMode.EMPTY_LIBRARY
    filteredCount == 0 && searchMode && !searchQueryBlank -> LibraryShelfMode.NO_SEARCH_MATCH
    filteredCount == 0 && filterActive -> LibraryShelfMode.NO_FILTER_MATCH
    else -> LibraryShelfMode.CONTENT
}
