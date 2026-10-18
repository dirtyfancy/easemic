package com.easemic.app.feature.collect

import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.ui.PreferenceMap
import org.json.JSONObject

/** Durable timestamps for completed serialized-series refreshes. */
class CollectedSeriesRefreshStore(
    private val prefs: PreferenceMap,
) {
    fun lastCompleteRefreshAtMs(series: SeriesIdentityV2): Long? = try {
        read().getLong(keyFor(series))
    } catch (_: Exception) {
        null
    }

    fun clear(series: SeriesIdentityV2) {
        val values = read()
        values.remove(keyFor(series))
        prefs.putStringSync(KEY, values.toString())
    }

    fun markCompleteRefresh(series: SeriesIdentityV2, atMs: Long) {
        val values = read()
        values.put(keyFor(series), atMs)
        prefs.putStringSync(KEY, values.toString())
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

    private fun keyFor(series: SeriesIdentityV2): String =
        series.providerNamespace + "\u0000" + series.canonicalSeriesId

    companion object {
        const val KEY = "collected_series_refresh_v1"
    }
}
