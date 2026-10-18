package com.easemic.app.feature.providers

import com.easemic.app.ui.PreferenceMap

/** Anonymous adult source: Browse opt-in only; saved media and follows have independent lifetimes. */
class JmEnableStore(private val prefs: PreferenceMap) {
    fun isEnabled(): Boolean = prefs.getString(KEY_ENABLED, null) == "true"
    fun setEnabled(enabled: Boolean) {
        prefs.putString(KEY_ENABLED, enabled.toString())
    }

    companion object {
        const val SOURCE_ID = "jm"
        const val DISPLAY_NAME = "禁漫天堂"
        private const val KEY_ENABLED = "jm_browse_enabled"
    }
}
