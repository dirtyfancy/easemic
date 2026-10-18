package com.easemic.app.feature.collect

import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.source.SeriesPublicationStatus
import com.easemic.app.ui.PreferenceMap
import org.json.JSONObject

data class SmartRefreshState(
    val lastCompleteCheckAtMillis: Long?,
    val lastKnownStatus: SeriesPublicationStatus,
)

/** Last complete check and last known publication status. Separate from the six-hour timestamp key. */
class SmartRefreshStateStore(
    private val prefs: PreferenceMap,
) {
    fun read(series: SeriesIdentityV2): SmartRefreshState {
        val entry = readAll().optJSONObject(keyFor(series)) ?: return SmartRefreshState(null, SeriesPublicationStatus.UNKNOWN)
        val last = try {
            if (!entry.has("lastCompleteCheckAtMillis") || entry.isNull("lastCompleteCheckAtMillis")) {
                null
            } else {
                entry.getLong("lastCompleteCheckAtMillis")
            }
        } catch (_: Exception) {
            null
        }
        val status = try {
            SeriesPublicationStatus.valueOf(entry.getString("lastKnownStatus"))
        } catch (_: Exception) {
            SeriesPublicationStatus.UNKNOWN
        }
        return SmartRefreshState(last, status)
    }

    fun write(series: SeriesIdentityV2, state: SmartRefreshState) {
        val values = readAll()
        val entry = JSONObject()
        if (state.lastCompleteCheckAtMillis != null) {
            entry.put("lastCompleteCheckAtMillis", state.lastCompleteCheckAtMillis)
        }
        entry.put("lastKnownStatus", state.lastKnownStatus.name)
        values.put(keyFor(series), entry)
        prefs.putStringSync(KEY, values.toString())
    }

    fun clear(series: SeriesIdentityV2) {
        val values = readAll()
        values.remove(keyFor(series))
        prefs.putStringSync(KEY, values.toString())
    }

    private fun readAll(): JSONObject {
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
        const val KEY = "smart_refresh_state_v1"
    }
}
