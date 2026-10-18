package com.easemic.app.feature.source.jm

import com.easemic.app.ui.PreferenceMap
import org.json.JSONArray
import org.json.JSONObject

/** Last validated origins, never account credentials; malformed local state falls back to bundled hosts. */
internal class JmPreferenceDomainStorage(private val prefs: PreferenceMap) : JmDomainStorage {
    override fun load(): JmDomainState? = runCatching {
        val json = JSONObject(prefs.getString(KEY, null) ?: return null)
        val hosts = json.getJSONArray("api")
        JmDomainState(List(hosts.length()) { hosts.getString(it) },
            if (json.isNull("image")) null else json.getString("image"), json.getLong("refreshedAt"),
            if (json.isNull("imageRefreshedAt")) null else json.getLong("imageRefreshedAt"))
    }.getOrNull()

    override fun save(state: JmDomainState) {
        prefs.putString(KEY, JSONObject().put("api", JSONArray(state.api))
            .put("image", state.image ?: JSONObject.NULL).put("refreshedAt", state.refreshedAt)
            .put("imageRefreshedAt", state.imageRefreshedAt ?: JSONObject.NULL).toString())
    }

    companion object { private const val KEY = "jm_validated_domains" }
}
