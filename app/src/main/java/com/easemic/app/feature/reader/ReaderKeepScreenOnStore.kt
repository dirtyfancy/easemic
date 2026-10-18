package com.easemic.app.feature.reader

import com.easemic.app.ui.PreferenceMap

/**
 * Persists whether readers keep the screen awake. Default true matches the
 * historical always-on behavior.
 */
class ReaderKeepScreenOnStore(
    private val prefs: PreferenceMap,
) {
    fun read(): Boolean {
        val raw = prefs.getString(KEY, null) ?: return DEFAULT
        return when (raw) {
            "1", "true", "TRUE", "yes" -> true
            "0", "false", "FALSE", "no" -> false
            else -> DEFAULT
        }
    }

    fun write(enabled: Boolean) {
        prefs.putString(KEY, if (enabled) "1" else "0")
    }

    companion object {
        const val KEY = "reader.keep_screen_on"
        const val DEFAULT = true
    }
}
