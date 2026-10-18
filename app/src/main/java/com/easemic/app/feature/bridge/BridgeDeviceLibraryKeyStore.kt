package com.easemic.app.feature.bridge

import com.easemic.app.ui.PreferenceMap
import org.json.JSONObject
import java.util.Base64

/**
 * Per-device cache of the unlocked library DEK (spec §4.5: the password is
 * asked once per device, not per launch). Cleared on Disconnect and on any
 * bind replacement. Password changes re-wrap the keyslot around the SAME DEK,
 * so this cache survives them.
 *
 * Stored plaintext in prefs beside the R2 credentials: the encryption's
 * adversary is the storage provider, not this device — see gate's delete()
 * note.
 */
class BridgeDeviceLibraryKeyStore(private val prefs: PreferenceMap) {
    fun save(dek: ByteArray) {
        val json = JSONObject()
            .put("v", FORMAT_VERSION)
            .put("dek_b64", Base64.getEncoder().encodeToString(dek))
        prefs.putString(KEY, json.toString())
    }

    fun load(): ByteArray? {
        val raw = prefs.getString(KEY, null)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            val json = JSONObject(raw)
            if (json.optInt("v", 0) != FORMAT_VERSION) return null
            Base64.getDecoder().decode(json.getString("dek_b64"))
                .takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        prefs.putString(KEY, "")
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val KEY = "bridge_library_dek_v1"
    }
}
