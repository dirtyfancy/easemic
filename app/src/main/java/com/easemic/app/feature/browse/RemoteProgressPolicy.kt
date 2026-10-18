package com.easemic.app.feature.browse

import com.easemic.app.feature.download.SeriesIdentityV2

/**
 * A42 remainder (spec 2026-09-08-a42-remote-progress-design.md D1/D2): the
 * pure key + resume decisions for remote reading progress. No I/O — callers
 * own the store.
 *
 * KEY DIALECT: [BrowseFeedItem.id] is the feed's stable per-source identity and
 * is provider-namespaced by every mapper that builds one:
 *  - Front (E-Hentai): `"ehentai:<gid>"` — BrowseFeedRepository.mapFrontEntry.
 *    The item's `acquisitionUrl` carries the per-view gallery token
 *    (`ehentai://<gid>/<token>`) which rotates per fetch, so the URL is NOT a
 *    stable progress key; the gid-based id is.
 *  - Index (nhentai): `"nhentai:<gid>"` — mapIndexEntry.
 *  - Cloud collection: `"cloud_collection:<cloudComicId>"` —
 *    CloudCollectionFeedMapper.mapCard (bucket-durable id).
 *  - OPDS: `entry.id ?: "<providerId>:<title>"` — a raw entry id that can
 *    collide across providers, so providerId must disambiguate.
 * Collected bookmarks persist `id = item.id`/`providerId = item.providerId`
 * at collect time, so a shelf tile rebuilt from a bookmark produces the same
 * key the reader wrote.
 *
 * Hence key = `providerId + "\u0000" + id`.
 */
object RemoteProgressPolicy {
    /**
     * Stable progress key for one serialized chapter.
     *
     * Gallery progress deliberately remains the two-part
     * `providerId + NUL + id` dialect used by [keyFor] and [keyForIds]. The
     * chapter key has a third part so a serialized chapter can never collide
     * with a legacy gallery record for the same series.
     */
    fun keyForChapter(series: SeriesIdentityV2, chapterId: String): String =
        series.providerNamespace + "\u0000" + series.canonicalSeriesId + "\u0000" + chapterId

    fun keyFor(item: BrowseFeedItem): String? {
        val id = item.id.trim()
        val providerId = item.providerId.trim()
        if (id.isEmpty() || providerId.isEmpty()) return null
        return keyForIds(providerId, id)
    }

    /**
     * The same key from raw halves, for callers that hold no [BrowseFeedItem] —
     * #C19-F12's bookmark rewrite has to migrate the entry it is about to
     * orphan and must spell the key exactly as [keyFor] does.
     */
    fun keyForIds(providerId: String, id: String): String = "$providerId\u0000$id"

    /**
     * Resume precedence for a remote reader open (spec D2):
     *  1. an EXPLICIT route-saved [explicitInitialOrdinal] (a preview-cell
     *     deep link) wins when non-null;
     *  2. otherwise the persisted [stored] progress is the source of truth;
     *  3. 0 (open at page 1) when there is neither.
     */
    fun resumeOrdinal(explicitInitialOrdinal: Int?, stored: RemoteReadingProgress?): Int =
        explicitInitialOrdinal ?: stored?.ordinal ?: 0

    /**
     * Tile-bar fraction with the SAME geometry the local shelf bar uses
     * (`libraryProgressFraction`): last page = full bar, single page = no bar.
     */
    fun progressFraction(progress: RemoteReadingProgress?): Float {
        if (progress == null || progress.pageCount <= 1) return 0f
        val denominator = (progress.pageCount - 1).coerceAtLeast(1)
        return (progress.ordinal.toFloat() / denominator).coerceIn(0f, 1f)
    }
}
