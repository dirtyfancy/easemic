package com.easemic.app.feature.library

data class LibraryStateFilter(
    val collected: Boolean = false,
    val downloaded: Boolean = false,
    val imported: Boolean = false,
) {
    val any: Boolean get() = collected || downloaded || imported

    fun matches(f: ShelfFacts): Boolean = when {
        !any -> true
        collected && !f.collected -> false
        downloaded && !f.downloaded -> false
        imported && !f.imported -> false
        else -> true
    }
}

data class ShelfFacts(val collected: Boolean, val downloaded: Boolean, val imported: Boolean)
