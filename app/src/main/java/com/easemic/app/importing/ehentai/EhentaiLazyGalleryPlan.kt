package com.easemic.app.importing.ehentai

/**
 * #E11: a gallery plan that loads thumbnail grids only when an interactive
 * consumer (detail, preview warm-through, online read) needs an ordinal in
 * them, instead of [EhentaiClient.fetchGallery]'s full walk of every grid.
 * Page 0 is fetched at construction, so metadata and the declared count never
 * need more than one request.
 *
 * Callers pass their own [EhentaiClient] to [pathFor] and [thumbsThrough] so
 * each keeps its own request gate (the reader's `Interactive` gate must not
 * queue behind ingest on `Shared`); the plan holds only the resolved data and
 * the lock that makes a grid request single-flight.
 *
 * Ordinals come from the page number every page-view path carries
 * (`/s/<token>/<gid>-<page>`), never from a cell's offset in its grid, so a
 * grid served with an unexpected size can only leave an ordinal missing —
 * which falls back to one full walk — never map it to the wrong page.
 */
class EhentaiLazyGalleryPlan(
    firstPageClient: EhentaiClient,
    private val galleryId: Long,
    private val token: String,
) {
    /** Page-0 metadata, including the first grid's paths and thumbnails. */
    val metadata: EhentaiGalleryPlan = firstPageClient.fetchGalleryGridPage(galleryId, token, 0)

    /** Cells served by the first grid (the user's grid size); never assumed to be 40. */
    val perGrid: Int = metadata.pageViewPaths.size

    /** Declared gallery length, with the first grid as the only fallback. */
    val pageCount: Int = metadata.declaredPageCount ?: perGrid

    private val lock = Any()
    private val pathsByOrdinal = HashMap<Int, String>()
    private val thumbsByOrdinal = HashMap<Int, EhentaiPreviewThumb>()
    private val loadedGridIndices = mutableSetOf(0)
    private var fullPlanLoaded = false

    init {
        merge(metadata, gridIndex = 0)
    }

    /** Resolves one page-view path, loading at most its grid (or, if that grid lacks it, one full walk). */
    fun pathFor(ordinal: Int, client: EhentaiClient): String {
        require(ordinal >= 0) { "ordinal must be >= 0" }
        synchronized(lock) {
            pathsByOrdinal[ordinal]?.let { return it }
            if (perGrid > 0) {
                ensureGridLoaded(ordinal / perGrid, client)
                pathsByOrdinal[ordinal]?.let { return it }
            }
            ensureFullPlanLoaded(client)
            return pathsByOrdinal[ordinal]
                ?: throw EhentaiException.Parse("EHENTAI_PAGE_PATH_$ordinal")
        }
    }

    /**
     * Preview thumbs below [endExclusive] (clamped to [pageCount]) in ordinal
     * order, loading only the grids that cover that range.
     */
    fun thumbsThrough(endExclusive: Int, client: EhentaiClient): List<EhentaiPreviewThumb> {
        require(endExclusive >= 0) { "endExclusive must be >= 0" }
        // A prefetch window past the last page must not turn into a full walk.
        val end = minOf(endExclusive, pageCount)
        if (end == 0) return emptyList()
        synchronized(lock) {
            if (perGrid > 0) {
                for (gridIndex in 0..(end - 1) / perGrid) ensureGridLoaded(gridIndex, client)
            }
            if ((0 until end).any { it !in thumbsByOrdinal }) ensureFullPlanLoaded(client)
            return (0 until end).mapNotNull { thumbsByOrdinal[it] }
        }
    }

    private fun ensureGridLoaded(gridIndex: Int, client: EhentaiClient) {
        if (fullPlanLoaded || !loadedGridIndices.add(gridIndex)) return
        try {
            merge(client.fetchGalleryGridPage(galleryId, token, gridIndex), gridIndex)
        } catch (error: Throwable) {
            loadedGridIndices.remove(gridIndex)
            throw error
        }
    }

    private fun ensureFullPlanLoaded(client: EhentaiClient) {
        if (fullPlanLoaded) return
        merge(client.fetchGallery(galleryId, token), gridIndex = 0)
        fullPlanLoaded = true
    }

    /**
     * Records each path (and the thumb in the same cell) at the ordinal its own
     * page number names. A path without a parsable page number falls back to
     * its cell position from [gridIndex] — the full plan is merged with
     * gridIndex 0, where position and ordinal coincide.
     */
    private fun merge(page: EhentaiGalleryPlan, gridIndex: Int) {
        page.pageViewPaths.forEachIndexed { cell, path ->
            val ordinal = ordinalOf(path) ?: (gridIndex * perGrid + cell)
            pathsByOrdinal[ordinal] = path
            page.previewThumbs.getOrNull(cell)?.let { thumbsByOrdinal[ordinal] = it }
        }
    }

    private companion object {
        private val PAGE_NUMBER = Regex("""/s/[0-9a-zA-Z]+/\d+-(\d+)""")

        fun ordinalOf(pageViewPath: String): Int? =
            PAGE_NUMBER.find(pageViewPath)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)?.takeIf { it >= 0 }
    }
}
