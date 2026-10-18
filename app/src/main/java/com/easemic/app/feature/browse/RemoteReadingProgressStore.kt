package com.easemic.app.feature.browse

import com.easemic.app.ui.PreferenceMap
import org.json.JSONArray
import org.json.JSONObject

/** One persisted remote-read position, in 0-based page ordinals. */
data class RemoteReadingProgress(
    val ordinal: Int,
    val pageCount: Int,
    val updatedAtMillis: Long,
)

/**
 * A42 remainder (spec 2026-09-08-a42-remote-progress-design.md D1): durable
 * device-local reading progress for cloud/online reads — the remote-tile
 * progress bar and the read-online/cloud resume source of truth.
 *
 * Deliberately Room-free (a few hundred entries need no table) and follows the
 * established small-store convention ([PreferenceMap] + a small org.json
 * envelope, like `CloudSyncDismissalStore`): one prefs string key holding a
 * JSON array of per-comic records.
 *
 * Keys are the [BrowseFeedItem] identity dialect from [RemoteProgressPolicy]
 * (`providerId + NUL + id`) — one row per remote comic, capped at
 * [MAX_ENTRIES] and LRU-evicted by [RemoteReadingProgress.updatedAtMillis].
 *
 * This is device-local progress only: it is not part of cloud-library sync.
 * The device-to-device archive carries the store's raw records. It IS written
 * synchronously (`putStringSync`) so a settled page survives an immediate
 * process kill; call off the main thread.
 */
class RemoteReadingProgressStore(
    private val prefs: PreferenceMap,
) {
    fun get(key: String): RemoteReadingProgress? = all()[key]

    /** Returns the store's persisted JSON without changing its representation. */
    fun exportRaw(): String = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() } ?: "[]"

    /** Adds archive rows whose keys are absent locally; local rows always win. */
    fun mergeMissingRaw(raw: String): Int {
        val incoming = runCatching { JSONArray(raw) }.getOrNull() ?: return 0
        val current = all().toMutableMap()
        var added = 0
        for (i in 0 until incoming.length()) {
            val parsed = parse(incoming.optJSONObject(i)) ?: continue
            if (current.containsKey(parsed.first) || current.size >= MAX_ENTRIES) continue
            current[parsed.first] = parsed.second
            added++
        }
        if (added > 0) write(current)
        return added
    }

    fun all(): Map<String, RemoteReadingProgress> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyMap()
        }
        return buildMap {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                parse(obj)?.let { put(it.first, it.second) }
            }
        }
    }

    /**
     * Upserts one comic's settled position. A blank key or a non-positive page
     * count is ignored — neither would ever match a tile or render a bar.
     */
    fun record(
        key: String,
        ordinal: Int,
        pageCount: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (key.isBlank() || pageCount <= 0) return
        val current = all().toMutableMap()
        current[key] = RemoteReadingProgress(
            ordinal = ordinal.coerceIn(0, pageCount - 1),
            pageCount = pageCount,
            updatedAtMillis = nowMillis,
        )
        write(current)
    }

    /**
     * Moves one comic's position from [oldKey] to [newKey] — #C19-F12's
     * bookmark rewrite changes both halves of the key, so without this the
     * rewritten comic silently loses its place. A missing [oldKey] is a no-op;
     * an existing [newKey] is left alone (the canonical entry is the newer
     * truth, and the doubled one is what is being retired).
     */
    fun rekey(oldKey: String, newKey: String) {
        if (oldKey == newKey || oldKey.isBlank() || newKey.isBlank()) return
        val current = all().toMutableMap()
        val moved = current.remove(oldKey) ?: return
        if (!current.containsKey(newKey)) current[newKey] = moved
        write(current)
    }

    private fun write(records: Map<String, RemoteReadingProgress>) {
        val kept = records.entries
            .sortedBy { it.value.updatedAtMillis }
            .takeLast(MAX_ENTRIES)
        val array = JSONArray()
        kept.forEach { (key, progress) ->
            array.put(
                JSONObject()
                    .put(FIELD_KEY, key)
                    .put(FIELD_ORDINAL, progress.ordinal)
                    .put(FIELD_PAGE_COUNT, progress.pageCount)
                    .put(FIELD_UPDATED_AT, progress.updatedAtMillis),
            )
        }
        prefs.putStringSync(KEY, array.toString())
    }

    private fun parse(obj: JSONObject?): Pair<String, RemoteReadingProgress>? {
        obj ?: return null
        val key = obj.optString(FIELD_KEY).takeIf { it.isNotEmpty() } ?: return null
        val ordinal = obj.optInt(FIELD_ORDINAL, -1)
        val pageCount = obj.optInt(FIELD_PAGE_COUNT, 0)
        if (ordinal < 0 || pageCount <= 0) return null
        return key to RemoteReadingProgress(
            ordinal = ordinal,
            pageCount = pageCount,
            updatedAtMillis = obj.optLong(FIELD_UPDATED_AT, 0L),
        )
    }

    companion object {
        const val KEY = "remote_reading_progress_v1"
        const val MAX_ENTRIES = 200
        private const val FIELD_KEY = "k"
        private const val FIELD_ORDINAL = "o"
        private const val FIELD_PAGE_COUNT = "n"
        private const val FIELD_UPDATED_AT = "u"
    }
}
