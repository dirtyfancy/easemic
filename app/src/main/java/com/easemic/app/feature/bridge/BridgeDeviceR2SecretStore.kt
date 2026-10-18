package com.easemic.app.feature.bridge

import com.easemic.app.ui.PreferenceMap
import org.json.JSONObject

/**
 * Persists the single on-device R2 binding. Accountless plaintext cloud: there
 * is no content key to keep, so the record IS the binding (format v3). Records
 * written by older builds still load — missing region defaults to `auto`, and
 * any `contentPassphrase` field they carry is ignored.
 */
class BridgeDeviceR2SecretStore(
    private val prefs: PreferenceMap,
) {
    fun save(binding: BridgeR2Binding) {
        val json = JSONObject()
            .put("v", FORMAT_VERSION)
            .put("endpoint", binding.endpoint)
            .put("accessKeyId", binding.accessKeyId)
            .put("secretAccessKey", binding.secretAccessKey)
            .put("bucket", binding.bucket)
            .put("region", binding.region)
        prefs.putString(KEY, json.toString())
    }

    fun load(): BridgeR2Binding? {
        val raw = prefs.getString(KEY, null)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        if (json.optInt("v", 0) !in READABLE_VERSIONS) return null
        val endpoint = json.optString("endpoint")
        val accessKeyId = json.optString("accessKeyId")
        val secretAccessKey = json.optString("secretAccessKey")
        val bucket = json.optString("bucket")
        val region = json.optString("region", "auto").trim().ifEmpty { "auto" }
        if (endpoint.isEmpty() || accessKeyId.isEmpty() ||
            secretAccessKey.isEmpty() || bucket.isEmpty()
        ) {
            return null
        }
        return BridgeR2Binding(
            endpoint = endpoint,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            bucket = bucket,
            region = region,
        )
    }

    fun clear() {
        prefs.putString(KEY, "")
    }

    companion object {
        const val KEY = "bridge_r2_device_secrets_v1"
        const val FORMAT_VERSION: Int = 3

        /** v2 binds survive the plaintext migration without re-pasting. */
        private val READABLE_VERSIONS = setOf(2, FORMAT_VERSION)
    }
}
