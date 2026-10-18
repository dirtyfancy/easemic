package com.easemic.app.feature.browse

object CloudDetailPreviewProgress {
    /**
     * Ceiling of the cloud Detail preview grid: at most this many thumb
     * ordinals are ever requested per comic. Historically the upload-side
     * 24-thumb cap. Since #C5 every upload carries one `thumbs/<ordinal>`
     * derivative per page (C41 reads them for every cell); a missing thumb
     * marks its slot with [MISSING_URI] rather than being skipped.
     * [initialCount]'s [thumbCount] (a manifest's real uploaded-thumb count)
     * overrides this when known; this stays as the fallback ceiling.
     */
    const val CLOUD_DETAIL_PREVIEW_LIMIT = 24

    /**
     * C41: sentinel appended by [appendSuccesses] for an ordinal whose
     * [uriFor][appendSuccesses] returned null under `stopOnFailure = false`,
     * so a missing/unreadable thumb marks its own slot instead of silently
     * dropping it (which shifted every later ordinal's uri one index left).
     */
    const val MISSING_URI = "cloud-thumb://missing"

    /**
     * S4 Task 1 review (M8): [thumbCount], when known (the v2
     * `pages.manifest`'s real uploaded-thumb count — see
     * [com.easemic.app.sync.CloudCollectionStore.thumbCount]),
     * replaces [uploadLimit] as the walk ceiling so a Detail preview strip
     * stops exactly at what was actually uploaded instead of guessing a
     * fixed 24/12 and 404ing past it. Null (the default) preserves the prior
     * fixed-[uploadLimit] behavior for a legacy card with no reachable
     * manifest.
     */
    fun initialCount(
        pageCount: Int,
        uploadLimit: Int = CLOUD_DETAIL_PREVIEW_LIMIT,
        initialCells: Int = 12,
        thumbCount: Int? = null,
    ): Int = minOf(pageCount.coerceAtLeast(0), thumbCount ?: uploadLimit, initialCells)

    suspend fun appendSuccesses(
        seed: List<String>,
        ordinals: IntRange,
        uriFor: (Int) -> String?,
        onPartial: suspend (List<String>) -> Unit,
        stopOnFailure: Boolean = false,
        isUsableUri: (String) -> Boolean = { true },
    ): List<String> {
        val acc = seed.takeWhile(isUsableUri).toMutableList()
        for (ordinal in ordinals) {
            val uri = uriFor(ordinal)
            if (uri == null) {
                if (stopOnFailure) break
                acc += MISSING_URI
                onPartial(acc.toList())
                continue
            }
            acc += uri
            onPartial(acc.toList())
        }
        return acc.toList()
    }
}
