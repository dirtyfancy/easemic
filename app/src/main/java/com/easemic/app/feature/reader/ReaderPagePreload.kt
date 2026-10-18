package com.easemic.app.feature.reader

/** Computes the bounded preload window around the settled reader page. */
object ReaderPagePreload {
    const val DEFAULT_BEHIND: Int = 1
    /** Widen forward preload past the immediate next page. */
    const val DEFAULT_AHEAD: Int = 3

    fun adjacentOrdinals(
        settled0Based: Int,
        pageCount: Int,
        behind: Int = DEFAULT_BEHIND,
        ahead: Int = DEFAULT_AHEAD,
    ): List<Int> {
        if (settled0Based !in 0 until pageCount) return emptyList()
        val result = ArrayList<Int>(behind + ahead)
        for (delta in behind downTo 1) {
            val ordinal = settled0Based - delta
            if (ordinal >= 0) result += ordinal
        }
        for (delta in 1..ahead) {
            val ordinal = settled0Based + delta
            if (ordinal < pageCount) result += ordinal
        }
        return result
    }

    fun keepOrdinals(
        settled0Based: Int,
        pageCount: Int,
        behind: Int = DEFAULT_BEHIND,
        ahead: Int = DEFAULT_AHEAD,
    ): Set<Int> {
        if (settled0Based !in 0 until pageCount) return emptySet()
        return buildSet {
            add(settled0Based)
            addAll(adjacentOrdinals(settled0Based, pageCount, behind, ahead))
        }
    }
}
