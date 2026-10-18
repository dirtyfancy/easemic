package com.easemic.app.feature.collect

import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.ui.PreferenceMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Durable chapter IDs discovered by a serialized-series follow refresh. */
class CollectedSeriesNewChapterStore(
    private val prefs: PreferenceMap,
) {
    private val lock = Any()
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** Adds [ids] to the series mark, incrementing [changes] only when it grows. */
    fun add(
        series: SeriesIdentityV2,
        ids: Set<String>,
        observedAtMs: Long = System.currentTimeMillis(),
    ) {
        val incoming = ids.filterTo(linkedSetOf()) { it.isNotEmpty() }
        if (incoming.isEmpty()) return
        synchronized(lock) {
            val values = read()
            val key = keyFor(series)
            val clearedAtMs = values.optJSONObject(CLEARED_AT_KEY)?.optLong(key, Long.MIN_VALUE)
                ?: Long.MIN_VALUE
            if (clearedAtMs >= observedAtMs) return@synchronized
            val current = readIds(values.optJSONArray(key))
            val next = current + incoming
            if (next.size == current.size) return@synchronized
            values.put(key, JSONArray(next.toList()))
            write(values)
            _changes.value += 1
        }
    }

    fun count(series: SeriesIdentityV2): Int = synchronized(lock) {
        readIds(read().optJSONArray(keyFor(series))).size
    }

    /** Returns the persisted mark counts keyed by their stable series identity. */
    fun all(): Map<SeriesIdentityV2, Int> = synchronized(lock) {
        val values = read()
        buildMap {
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val separator = key.indexOf(KEY_SEPARATOR)
                if (separator <= 0 || separator == key.lastIndex) continue
                val count = readIds(values.optJSONArray(key)).size
                if (count > 0) {
                    put(
                        SeriesIdentityV2(
                            providerNamespace = key.substring(0, separator),
                            canonicalSeriesId = key.substring(separator + 1),
                        ),
                        count,
                    )
                }
            }
        }
    }

    /** Removes all pending chapter IDs for [series], if any. */
    fun clear(series: SeriesIdentityV2, clearedAtMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val values = read()
            val key = keyFor(series)
            val hadMark = values.has(key)
            values.remove(key)
            val cleared = values.optJSONObject(CLEARED_AT_KEY) ?: JSONObject()
            cleared.put(key, clearedAtMs)
            values.put(CLEARED_AT_KEY, cleared)
            write(values)
            if (hadMark) _changes.value += 1
        }
    }

    private fun read(): JSONObject {
        val raw = prefs.getString(KEY, null)
        if (raw.isNullOrBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun write(values: JSONObject) {
        prefs.putStringSync(KEY, values.toString())
    }

    private fun readIds(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        return buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
    }

    private fun keyFor(series: SeriesIdentityV2): String =
        series.providerNamespace + KEY_SEPARATOR + series.canonicalSeriesId

    companion object {
        const val KEY = "collected_series_new_chapters_v1"
        private const val CLEARED_AT_KEY = "cleared_at_ms"
        private const val KEY_SEPARATOR = "\u0000"
    }
}
