package com.easemic.app.feature.browse

/** Prefetch window for Cloud Collection layer-2 page batches (forward-biased). */
object CloudCollectionPageWindow {
    const val DEFAULT_BATCH_SIZE: Int = 24

    /**
     * Returns up to [batchSize] ordinals around [settled], biased forward
     * (~1/4 lookbehind, remainder lookahead including the settled page).
     */
    fun ordinals(
        settled: Int,
        pageCount: Int,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ): Set<Int> {
        if (pageCount <= 0) return emptySet()
        val last = pageCount - 1
        val size = batchSize.coerceAtLeast(1)
        val settledPage = settled.coerceIn(0, last)
        val behind = (size / 4).coerceAtMost(settledPage)
        var start = settledPage - behind
        var end = (start + size - 1).coerceAtMost(last)
        start = (end - size + 1).coerceAtLeast(0)
        return (start..end).toSet()
    }

    /**
     * The ADMISSION order of a window for the reader's prefetch pool
     * (#C45 [CloudReaderPrefetcher.focus]): the settled page first, then by
     * distance from it, and at equal distance the page AHEAD of the reader
     * before the one behind (the page about to be read beats the page just
     * left). Duplicates in [wanted] collapse (it is a Set); the result never
     * drops or invents an ordinal. The pool re-sorts on every settle, so this
     * order is only ever applied to what is still pending.
     */
    fun priorityOrdinals(
        settled: Int,
        wanted: Set<Int>,
    ): List<Int> {
        if (wanted.isEmpty()) return emptyList()
        return wanted
            .sortedWith(
                compareBy(
                    { kotlin.math.abs(it - settled) },
                    { if (it >= settled) 0 else 1 },
                    { it },
                ),
            )
            .let { ordered -> if (settled in wanted) listOf(settled) + ordered.filter { it != settled } else ordered }
    }
}
