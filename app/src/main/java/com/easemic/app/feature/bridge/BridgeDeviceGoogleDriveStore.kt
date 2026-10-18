package com.easemic.app.feature.bridge

import com.easemic.app.ui.PreferenceMap
import org.json.JSONObject

/**
 * #C50: a Google Drive library — the pclRoot-tagged folder this app created. Google does not
 * rotate refresh tokens, so the record only changes on a new sign-in.
 */
data class GoogleDriveBinding(
    val refreshToken: String,
    val rootFolderId: String,
    val account: String,
) {
    override fun toString(): String = "GoogleDriveBinding(refreshToken=<redacted>, rootFolderId=$rootFolderId, account=$account)"
}

class BridgeDeviceGoogleDriveStore(private val prefs: PreferenceMap) {
    fun save(binding: GoogleDriveBinding) = prefs.putString(KEY, encode(binding, null))

    /** Same contract as [BridgeDeviceOneDriveStore.accessCache]: dies with the grant on [save] / [clear]. */
    val accessCache: DriveAccessTokenCache = object : DriveAccessTokenCache {
        override fun load(): PersistedAccessToken? {
            if (this@BridgeDeviceGoogleDriveStore.load() == null) return null
            return runCatching { JSONObject(prefs.getString(KEY, null).orEmpty()) }.getOrNull()?.let(DriveAccessTokenJson::read)
        }
        override fun save(token: PersistedAccessToken) {
            val current = this@BridgeDeviceGoogleDriveStore.load() ?: return
            prefs.putString(KEY, encode(current, token))
        }
        override fun clear() {
            val current = this@BridgeDeviceGoogleDriveStore.load() ?: return
            prefs.putString(KEY, encode(current, null))
        }
    }

    private fun encode(binding: GoogleDriveBinding, access: PersistedAccessToken?): String =
        JSONObject().put("v", 1).put("refreshToken", binding.refreshToken).put("rootFolderId", binding.rootFolderId)
            .put("account", binding.account).also { DriveAccessTokenJson.write(it, access) }.toString()

    fun load(): GoogleDriveBinding? {
        val raw = prefs.getString(KEY, null)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.optInt("v", 0) != 1) return null
        return GoogleDriveBinding(json.optString("refreshToken"), json.optString("rootFolderId"), json.optString("account"))
            .takeIf { it.refreshToken.isNotBlank() && it.rootFolderId.isNotBlank() }
    }

    fun clear() = prefs.putString(KEY, "")

    companion object {
        const val KEY: String = "bridge_gdrive_binding"
    }
}
