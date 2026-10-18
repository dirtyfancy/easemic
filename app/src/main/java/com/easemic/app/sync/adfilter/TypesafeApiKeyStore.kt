package com.easemic.app.sync.adfilter

import com.easemic.app.ui.PreferenceMap

/**
 * #E118: Persists the user's TypeSafe (Jev) API key on device.
 *
 * When both a cloud library is bound and this store holds a non-blank API key,
 * galleries carrying the `extraneous ads` tag run the trailing commercial ad
 * filter before cloud upload.
 */
class TypesafeApiKeyStore(
    private val prefs: PreferenceMap,
) {
    fun read(): String? =
        prefs.getString(KEY_API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun write(apiKey: String?) {
        val cleaned = apiKey?.trim().orEmpty()
        prefs.putString(KEY_API_KEY, cleaned)
    }

    fun clear() {
        prefs.putString(KEY_API_KEY, "")
    }

    fun isConfigured(): Boolean = read() != null

    companion object {
        const val KEY_API_KEY = "typesafe_jev_api_key_v1"
        const val TITLE = "TypeSafe AI (Jev) ad filter"
        const val SUBTITLE_UNCONFIGURED =
            "Not configured. Add a TypeSafe API key to trim trailing sponsor ads from extraneous-ads comics on cloud upload."

        fun maskedSubtitle(apiKey: String): String {
            val trimmed = apiKey.trim()
            val tail = if (trimmed.length > 6) "…${trimmed.takeLast(4)}" else "configured"
            return "Active ($tail) · Trims trailing sponsor ads on cloud upload"
        }
    }
}
