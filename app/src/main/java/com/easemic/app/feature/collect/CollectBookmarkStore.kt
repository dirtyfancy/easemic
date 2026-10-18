package com.easemic.app.feature.collect

import com.easemic.app.feature.library.galleryDedupKey
import com.easemic.app.feature.library.collectedBookmarkDedupKey
import com.easemic.app.ui.PreferenceMap
import org.json.JSONArray
import org.json.JSONObject

data class CollectedBookmark(
    val id: String,
    val title: String,
    val acquisitionUrl: String,
    val providerId: String,
    val coverUrl: String? = null,
    /** #C12: the OPDS feed the comic was collected from (null for other sources / older bookmarks). */
    val feedUrl: String? = null,
    /** Serialized-series follows identify the series whose chapter rows sync to cloud. */
    val serialized: Boolean = false,
)

/**
 * Bookmarks that should be removed when uncollecting an item whose current
 * `acquisitionUrl` is [acquisition] and whose `upstreamAcquisitionUrl` (the
 * original acquisition it was collected under, before any cloud remap) is
 * [upstream].
 *
 * A cloud-shelf item's `acquisitionUrl` is `"cloud:<id>"` — never what the
 * bookmark was persisted under at collect time (the original acquisition,
 * e.g. a `ehentai://` URL) — so matching on [acquisition] alone misses
 * every bookmark for a comic opened from the Cloud shelf. Matching [upstream]
 * too finds it: `CloudCollectionFeedMapper.mapCard`/`applyMeta` thread the
 * original acquisition through as `upstreamAcquisitionUrl`, which is exactly
 * the value the bookmark's own `acquisitionUrl` was set to at collect time.
 * Leaving that bookmark behind is not just cosmetic — the cloud catch-up
 * pass re-scans every bookmark and re-collects any whose comic isn't already
 * active/trashed, so a stale surviving bookmark risks resurrecting a comic
 * the user just uncollected.
 */
fun bookmarksMatchingUncollect(
    all: List<CollectedBookmark>,
    acquisition: String?,
    upstream: String?,
): List<CollectedBookmark> = all.filter { bookmark ->
    !isSerializedBookmark(bookmark) &&
        ((acquisition != null && bookmark.acquisitionUrl == acquisition) ||
            (upstream != null && bookmark.acquisitionUrl == upstream))
}

/** Bookmark-only Collect store (no page bytes). */
class CollectBookmarkStore(
    private val prefs: PreferenceMap,
) {
    // #C56 send-back R1: every method below is a readAll()->writeAll() pass
    // over ONE prefs JSON blob, and adoptAll (a hydration-completion write,
    // off the WorkManager thread) now races the UI's upsert/remove/sweep-
    // drop. Guard every compound op with the same lock idiom CloudSyncQueue
    // uses (CloudSyncQueue.kt ~line 118/135). Kotlin's `synchronized` is
    // reentrant on the JVM monitor, so a locked method (e.g. upsert) calling
    // another locked method (readAll) does not deadlock.
    private val lock = Any()

    fun readAll(): List<CollectedBookmark> = synchronized(lock) {
        val raw = prefs.getString(KEY, null) ?: return@synchronized emptyList()
        try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        CollectedBookmark(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            acquisitionUrl = obj.getString("acquisitionUrl"),
                            providerId = obj.optString("providerId"),
                            coverUrl = obj.optString("coverUrl").takeIf { it.isNotBlank() },
                            feedUrl = obj.optString("feedUrl").takeIf { it.isNotBlank() },
                            serialized = obj.optBoolean("serialized", false) ||
                                obj.optString("providerId") in setOf("zaimanhua", "jm"),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun upsert(bookmark: CollectedBookmark) {
        synchronized(lock) {
            val normalized = bookmark.copy(serialized = isSerializedBookmark(bookmark))
            val all = readAll()
            // #E59: serialized identity is provider + raw id. A raw id is not
            // globally unique: 再漫画/12345 and JM/12345 are two follows.
            // Gallery rows retain the historical id/acquisition alias behavior.
            val existing = all.firstOrNull { sameBookmarkIdentity(it, normalized) }
            // #C12 audit H1: a re-collect from a path that does not carry the feed
            // must not wipe a feedUrl Browse already recorded.
            val merged = if (normalized.feedUrl == null && existing?.feedUrl != null) {
                normalized.copy(feedUrl = existing.feedUrl)
            } else {
                normalized
            }
            writeAll(all.filterNot { existing != null && it == existing } + merged)
        }
    }

    fun remove(id: String, providerId: String) {
        synchronized(lock) {
            writeAll(readAll().filterNot { it.id == id && it.providerId == providerId })
        }
    }

    /**
     * #C56 send-back R3: a conditional remove for the sweep's DROP_BOOKMARK
     * branch. [expected] is the sweep's own snapshot of the row being
     * dropped; the row is removed only if the CURRENTLY stored row with that
     * id is still exactly [expected] — so a bookmark the user re-created (a
     * Collect tap) between the sweep's read and this write is left alone.
     * Returns whether a row was actually removed.
     */
    fun removeIfUnchanged(expected: CollectedBookmark): Boolean = synchronized(lock) {
        val all = readAll()
        if (all.firstOrNull { it.id == expected.id && it.providerId == expected.providerId } != expected) {
            return@synchronized false
        }
        writeAll(all.filterNot { it.id == expected.id && it.providerId == expected.providerId })
        true
    }

    /**
     * #C56: a hydration-completion pass into the collection. Adds a bookmark
     * for each [candidates] whose gallery ([galleryDedupKey]) is not already
     * held by an existing bookmark, deduping inside the batch too. Returns
     * how many were added; one read and (if any were added) one write, so a
     * second pass over the same bucket is a no-op.
     */
    fun adoptAll(candidates: List<CollectedBookmark>): Int = synchronized(lock) {
        val existing = readAll()
        val existingGalleryKeys = existing
            .filterNot { isSerializedBookmark(it) }
            .mapNotNullTo(mutableSetOf()) { galleryDedupKey(it.acquisitionUrl) }
        val existingSeriesKeys = existing
            .filter { isSerializedBookmark(it) }
            .mapNotNullTo(mutableSetOf()) { collectedBookmarkDedupKey(it) }
        val toAdd = mutableListOf<CollectedBookmark>()
        for (candidate in candidates) {
            val key = if (isSerializedBookmark(candidate)) {
                collectedBookmarkDedupKey(candidate)
            } else {
                galleryDedupKey(candidate.acquisitionUrl)
            } ?: continue
            val added = if (isSerializedBookmark(candidate)) existingSeriesKeys.add(key) else existingGalleryKeys.add(key)
            if (added) {
                toAdd += candidate.copy(serialized = isSerializedBookmark(candidate))
            }
        }
        if (toAdd.isEmpty()) return@synchronized 0
        writeAll(existing + toAdd)
        toAdd.size
    }

    /**
     * True when some bookmark still points at [acquisitionUrl]. Uncollect
     * removes by bookmark id, not acquisition — this lets callers that only
     * know the acquisition (e.g. AppGraph's cloud-upload handoff guard, spec
     * whole-slice review GAP 2) ask "is this still Collected?" without a
     * separate id lookup.
     *
     * Matched on [galleryDedupKey], like every other identity comparison in
     * this feature ([bookmarksMatchingUncollect]'s callers, `DetailCollectTarget`,
     * `CloudSyncLedgerStore`): an E-Hentai acquisition carries a per-view token
     * (`ehentai://<gid>/<token>`), so a raw string compare read the same gallery
     * seen from a different listing as "not collected" — which at the Download
     * tap sites silently drops this comic's cloud intent. Input with no key at
     * all (blank) falls back to the exact compare, i.e. still no match.
     */
    fun containsAcquisition(acquisitionUrl: String): Boolean {
        val key = galleryDedupKey(acquisitionUrl)
            ?: return readAll().any { !isSerializedBookmark(it) && it.acquisitionUrl == acquisitionUrl }
        return readAll().any { !isSerializedBookmark(it) && galleryDedupKey(it.acquisitionUrl) == key }
    }

    fun exportRaw(): String = prefs.getString(KEY, null) ?: "[]"

    fun importRaw(json: String) {
        synchronized(lock) {
            prefs.putString(KEY, json.ifBlank { "[]" })
        }
    }

    private fun sameBookmarkIdentity(
        left: CollectedBookmark,
        right: CollectedBookmark,
    ): Boolean {
        if (isSerializedBookmark(left) || isSerializedBookmark(right)) {
            return isSerializedBookmark(left) && isSerializedBookmark(right) &&
                left.providerId == right.providerId && left.id == right.id
        }
        return left.id == right.id ||
            (galleryDedupKey(left.acquisitionUrl) != null &&
                galleryDedupKey(left.acquisitionUrl) == galleryDedupKey(right.acquisitionUrl))
    }

    private fun writeAll(next: List<CollectedBookmark>) {
        val array = JSONArray()
        next.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("acquisitionUrl", item.acquisitionUrl)
                    .put("providerId", item.providerId)
                    .put("coverUrl", item.coverUrl ?: "")
                    .put("feedUrl", item.feedUrl ?: "")
                    .put("serialized", item.serialized),
            )
        }
        prefs.putString(KEY, array.toString())
    }

    companion object {
        private const val KEY = "collect_bookmarks_v1"
    }
}
