package com.easemic.app.feature.settings

import com.easemic.app.ui.PreferenceMap

/** "Check followed series in the background". Default on. */
class BackgroundRefreshEnabledStore(
    private val prefs: PreferenceMap,
) {
    fun read(): Boolean = readSettingsFlag(prefs, KEY, DEFAULT)

    fun write(enabled: Boolean) {
        prefs.putString(KEY, if (enabled) "1" else "0")
    }

    companion object {
        const val KEY = "background.refresh.enabled"
        const val DEFAULT = true
    }
}
