package com.easemic.app.feature.browse

import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.ui.PreferenceMap
import org.json.JSONArray
import org.json.JSONObject

/** Display-only order, scoped by the complete provider series identity and group. */
class SerializedChapterOrderStore(private val prefs: PreferenceMap) {
    private companion object { const val KEY = "serialized_chapter_order" }

    private fun tuple(series: SeriesIdentityV2, group: String?): String = JSONArray()
        .put(series.providerNamespace)
        .put(series.canonicalSeriesId)
        .put(group ?: JSONObject.NULL)
        .toString()

    private fun read(): JSONObject = runCatching {
        JSONObject(prefs.getString(KEY, "{}").orEmpty())
    }.getOrElse { JSONObject() }

    fun newestFirst(series: SeriesIdentityV2, group: String?): Boolean =
        read().optBoolean(tuple(series, group), true)

    fun setNewestFirst(series: SeriesIdentityV2, group: String?, value: Boolean) {
        prefs.putString(KEY, read().put(tuple(series, group), value).toString())
    }
}
