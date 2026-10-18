package com.easemic.app.feature.bridge

import com.easemic.app.ui.PreferenceMap
import org.json.JSONObject

/**
 * Pending PKCE session for the Custom Tabs bounce. Keyed separately from
 * [BridgeOAuthDeviceSecretStore]. Never log the verifier.
 */
class BridgeOAuthPkceSessionStore(
    private val prefs: PreferenceMap,
) {
    fun save(session: CloudflarePkceSession) {
        val json = JSONObject()
            .put("v", FORMAT_VERSION)
            .put("authorizationUri", session.authorizationUri)
            .put("codeVerifier", session.codeVerifier)
            .put("state", session.state)
        prefs.putString(KEY, json.toString())
    }

    fun load(): CloudflarePkceSession? {
        val raw = prefs.getString(KEY, null)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        if (json.optInt("v", 0) != FORMAT_VERSION) return null
        val authorizationUri = json.optString("authorizationUri")
        val codeVerifier = json.optString("codeVerifier")
        val state = json.optString("state")
        if (authorizationUri.isBlank() || codeVerifier.isBlank() || state.isBlank()) {
            return null
        }
        return CloudflarePkceSession(
            authorizationUri = authorizationUri,
            codeVerifier = codeVerifier,
            state = state,
        )
    }

    fun clear() {
        prefs.putString(KEY, "")
    }

    companion object {
        const val KEY = "bridge_r2_oauth_pkce_session_v1"
        const val FORMAT_VERSION: Int = 2
    }
}
