package com.easemic.app.feature.reader

import com.easemic.app.ui.PreferenceMap

/**
 * Persists whether reader pages fill viewport width. Default false keeps the
 * historical contain (`ContentScale.Fit`) behavior.
 */
class ReaderFitWidthStore(
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
        const val KEY = "reader.fit_width"
        const val DEFAULT = false
    }
}
