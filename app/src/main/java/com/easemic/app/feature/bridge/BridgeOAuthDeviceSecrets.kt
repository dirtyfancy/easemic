package com.easemic.app.feature.bridge

import com.easemic.app.ui.PreferenceMap
import org.json.JSONObject

/**
 * Device-only Cloudflare OAuth bind secrets. Refresh token never leaves this
 * device. [toString] redacts [refreshToken].
 */
data class BridgeOAuthDeviceSecrets(
    val refreshToken: String,
    val accountId: String,
    val bucket: String,
) {
    override fun toString(): String =
        "BridgeOAuthDeviceSecrets(refreshToken=<redacted>, accountId=$accountId, " +
            "bucket=$bucket)"
}

/**
 * Persists [BridgeOAuthDeviceSecrets] for the single on-device profile. Sign-out
 * must not call [clear]; that is for uninstall-style wipe and tests only.
 *
 * Accountless plaintext cloud: no content key is written (format v3). Records
 * written by v2 builds still load — their `contentKey` field is ignored.
 */
class BridgeOAuthDeviceSecretStore(
    private val prefs: PreferenceMap,
) {
    fun save(secrets: BridgeOAuthDeviceSecrets) {
        prefs.putString(KEY, encode(secrets))
    }

    /**
     * Same as [save] but blocks until the write is durable (I1): a process
     * kill immediately after a rotated-refresh-token exchange must not be
     * able to lose the rotation, and [save]'s underlying `prefs.putString`
     * is an asynchronous `apply`.
     */
    fun saveSync(secrets: BridgeOAuthDeviceSecrets) {
        prefs.putStringSync(KEY, encode(secrets))
    }

    private fun encode(secrets: BridgeOAuthDeviceSecrets): String {
        require(secrets.refreshToken.isNotBlank()) { "refreshToken required" }
        require(secrets.accountId.isNotBlank()) { "accountId required" }
        require(secrets.bucket.isNotBlank()) { "bucket required" }
        return JSONObject()
            .put("v", FORMAT_VERSION)
            .put("refreshToken", secrets.refreshToken)
            .put("accountId", secrets.accountId.trim())
            .put("bucket", secrets.bucket.trim())
            .toString()
    }

    fun load(): BridgeOAuthDeviceSecrets? {
        val raw = prefs.getString(KEY, null)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        if (json.optInt("v", 0) !in READABLE_VERSIONS) return null
        val refreshToken = json.optString("refreshToken")
        val accountId = json.optString("accountId").trim()
        val bucket = json.optString("bucket").trim()
        if (refreshToken.isBlank() || accountId.isEmpty() || bucket.isEmpty()) {
            return null
        }
        return BridgeOAuthDeviceSecrets(
            refreshToken = refreshToken,
            accountId = accountId,
            bucket = bucket,
        )
    }

    fun clear() {
        prefs.putString(KEY, "")
    }

    companion object {
        const val KEY = "bridge_r2_oauth_secrets_v1"
        const val FORMAT_VERSION: Int = 3

        /** v2 binds survive the plaintext migration without re-connecting. */
        private val READABLE_VERSIONS = setOf(2, FORMAT_VERSION)
    }
}

/** RFC 6749 §6: persist a rotated refresh token; reuse the old one when omitted. */
fun persistRotatedRefresh(
    store: BridgeOAuthDeviceSecretStore,
    secrets: BridgeOAuthDeviceSecrets,
    newRefreshToken: String,
    sync: Boolean = false,
): BridgeOAuthDeviceSecrets {
    val next = newRefreshToken.trim()
    if (next.isEmpty() || next == secrets.refreshToken) return secrets
    val updated = secrets.copy(refreshToken = next)
    if (sync) store.saveSync(updated) else store.save(updated)
    return updated
}
