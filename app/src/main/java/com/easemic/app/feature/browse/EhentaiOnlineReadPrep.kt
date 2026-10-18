package com.easemic.app.feature.browse

/**
 * Front (E-Hentai) online read must not resolve every page image URL before
 * opening the reader — each page GET is rate-gated (~750ms), so N pages feel
 * like a multi-second freeze. Seed only the initial page; resolve blanks later.
 *
 * When a download job has a verified READY page for the initial ordinal, seed
 * that file:// URI instead of waiting on upstream (spec §4.5 READY first).
 */
object FrontOnlineReadPrep {
    fun seedImageUrls(
        pageCount: Int,
        initialOrdinal: Int,
        resolvedInitialUrl: String,
    ): List<String> {
        if (pageCount <= 0) return emptyList()
        val index = initialOrdinal.coerceIn(0, pageCount - 1)
        return List(pageCount) { ordinal ->
            if (ordinal == index) resolvedInitialUrl else ""
        }
    }

    /**
     * Prefer a verified READY display URI for the initial ordinal when present;
     * otherwise fall back to the upstream URL. Never seeds a `.part` path.
     */
    fun seedPreferringReady(
        pageCount: Int,
        initialOrdinal: Int,
        readyDisplayUri: String?,
        resolvedInitialUpstreamUrl: String,
    ): List<String> {
        val ready = readyDisplayUri?.trim().orEmpty()
        val seed = when {
            ready.isNotEmpty() && !ready.endsWith(".part") -> ready
            else -> resolvedInitialUpstreamUrl
        }
        return seedImageUrls(pageCount, initialOrdinal, seed)
    }
}
