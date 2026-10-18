package com.easemic.app.feature.browse

/**
 * How far ahead of the visible preview grid to warm thumbnails.
 *
 * Spec: when the viewport reaches row 2 (1-based), warm through row 5 —
 * i.e. [aheadRows] = 3 beyond the first visible row when looking one row ahead.
 */
object PreviewPrefetchWindow {
    const val COLUMNS = 3
    const val INITIAL_CELLS = 12
    const val AHEAD_ROWS = 3

    /**
     * Exclusive end cell index that should be warmed given the visible window.
     * Always at least [INITIAL_CELLS] (clamped to [totalCells]) once any content exists.
     */
    fun endExclusive(
        firstVisibleRow: Int,
        visibleRowCount: Int,
        totalCells: Int,
        aheadRows: Int = AHEAD_ROWS,
        columns: Int = COLUMNS,
    ): Int {
        if (totalCells <= 0) return 0
        val safeFirst = firstVisibleRow.coerceAtLeast(0)
        val safeVisible = visibleRowCount.coerceAtLeast(1)
        val lastVisibleRow = safeFirst + safeVisible - 1
        val warmThroughRow = lastVisibleRow + aheadRows.coerceAtLeast(0)
        val fromViewport = ((warmThroughRow + 1) * columns).coerceAtLeast(0)
        val floor = INITIAL_CELLS.coerceAtMost(totalCells)
        return maxOf(fromViewport, floor).coerceAtMost(totalCells)
    }
}
