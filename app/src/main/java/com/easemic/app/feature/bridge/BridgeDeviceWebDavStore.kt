package com.easemic.app.feature.bridge

import com.easemic.app.sync.WebDavBinding
import com.easemic.app.ui.PreferenceMap
import org.json.JSONObject

/**
 * #C50: the single on-device WebDAV binding. Same record style as
 * [BridgeDeviceR2SecretStore]; the password never leaves this device and
 * [WebDavBinding.toString] redacts it.
 */
class BridgeDeviceWebDavStore(
    private val prefs: PreferenceMap,
) {
    fun save(binding: WebDavBinding) {
        val json = JSONObject()
            .put("v", FORMAT_VERSION)
            .put("baseUrl", binding.baseUrl)
            .put("username", binding.username)
            .put("password", binding.password)
            .put("folder", binding.folder)
        prefs.putString(KEY, json.toString())
    }

    fun load(): WebDavBinding? {
        val raw = prefs.getString(KEY, null)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        if (json.optInt("v", 0) != FORMAT_VERSION) return null
        val baseUrl = json.optString("baseUrl").trim()
        val username = json.optString("username")
        if (baseUrl.isEmpty() || username.isEmpty()) return null
        return WebDavBinding(
            baseUrl = baseUrl,
            username = username,
            password = json.optString("password"),
            folder = json.optString("folder"),
        )
    }

    fun clear() {
        prefs.putString(KEY, "")
    }

    companion object {
        const val KEY: String = "bridge_webdav_binding"
        private const val FORMAT_VERSION = 1
    }
}
