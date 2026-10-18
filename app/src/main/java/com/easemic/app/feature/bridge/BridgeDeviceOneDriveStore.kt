package com.easemic.app.feature.bridge

import com.easemic.app.ui.PreferenceMap
import org.json.JSONObject

/**
 * #C50: a OneDrive library — the signed-in account's app folder. [rootItemId] is the approot
 * item id (addressed by id, never by its localized "Apps/…" path). Microsoft rotates the
 * refresh token on every refresh; [BridgeDeviceOneDriveStore.saveRefreshTokenSync] writes the
 * new one before it is used.
 */
data class OneDriveBinding(
    val refreshToken: String,
    val driveId: String,
    val rootItemId: String,
    val account: String,
) {
    override fun toString(): String =
        "OneDriveBinding(refreshToken=<redacted>, driveId=$driveId, rootItemId=$rootItemId, account=$account)"
}

class BridgeDeviceOneDriveStore(private val prefs: PreferenceMap) {
    fun save(binding: OneDriveBinding) = prefs.putString(KEY, encode(binding))

    fun load(): OneDriveBinding? {
        val raw = prefs.getString(KEY, null)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.optInt("v", 0) != FORMAT_VERSION) return null
        val binding = OneDriveBinding(
            refreshToken = json.optString("refreshToken"),
            driveId = json.optString("driveId"),
            rootItemId = json.optString("rootItemId"),
            account = json.optString("account"),
        )
        return binding.takeIf { it.refreshToken.isNotBlank() && it.driveId.isNotBlank() && it.rootItemId.isNotBlank() }
    }

    /** The rotation must survive a process death right after the exchange: a synchronous write. */
    fun saveRefreshTokenSync(refreshToken: String) {
        val current = load() ?: return
        prefs.putStringSync(KEY, encode(current.copy(refreshToken = refreshToken), storedAccess()))
    }

    fun clear() = prefs.putString(KEY, "")

    /**
     * The access token rides in the same record: [save] (a new sign-in or renewal) and [clear]
     * drop it with the grant it belongs to; a rotation keeps it.
     */
    val accessCache: DriveAccessTokenCache = object : DriveAccessTokenCache {
        override fun load(): PersistedAccessToken? = if (this@BridgeDeviceOneDriveStore.load() == null) null else storedAccess()
        override fun save(token: PersistedAccessToken) {
            val current = this@BridgeDeviceOneDriveStore.load() ?: return
            prefs.putString(KEY, encode(current, token))
        }
        override fun clear() {
            val current = this@BridgeDeviceOneDriveStore.load() ?: return
            prefs.putString(KEY, encode(current))
        }
    }

    private fun storedAccess(): PersistedAccessToken? {
        val json = runCatching { JSONObject(prefs.getString(KEY, null).orEmpty()) }.getOrNull() ?: return null
        return DriveAccessTokenJson.read(json)
    }

    private fun encode(b: OneDriveBinding, access: PersistedAccessToken? = null): String = JSONObject()
        .put("v", FORMAT_VERSION)
        .put("refreshToken", b.refreshToken)
        .put("driveId", b.driveId)
        .put("rootItemId", b.rootItemId)
        .put("account", b.account)
        .also { DriveAccessTokenJson.write(it, access) }
        .toString()

    companion object {
        const val KEY: String = "bridge_onedrive_binding"
        private const val FORMAT_VERSION = 1
    }
}

/** The access-token fields of a drive binding record. */
internal object DriveAccessTokenJson {
    fun read(json: JSONObject): PersistedAccessToken? {
        val token = json.optString("accessToken").trim()
        val expires = json.optLong("accessExpiresAtMs", 0L)
        return if (token.isEmpty() || expires <= 0L) null else PersistedAccessToken(token, expires)
    }

    fun write(json: JSONObject, access: PersistedAccessToken?) {
        if (access == null) return
        json.put("accessToken", access.accessToken).put("accessExpiresAtMs", access.expiresAtMs)
    }
}
