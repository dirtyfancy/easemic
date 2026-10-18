package com.easemic.app.feature.settings

import com.easemic.app.ui.PreferenceMap

/** "Only on Wi-Fi". Default on, and kept even while background checks are off. */
class BackgroundRefreshWifiOnlyStore(
    private val prefs: PreferenceMap,
) {
    fun read(): Boolean = readSettingsFlag(prefs, KEY, DEFAULT)

    fun write(wifiOnly: Boolean) {
        prefs.putString(KEY, if (wifiOnly) "1" else "0")
    }

    companion object {
        const val KEY = "background.refresh.wifi_only"
        const val DEFAULT = true
    }
}
