package com.easemic.app.feature.providers

import com.easemic.app.ui.PreferenceMap

/**
 * Settings opt-in for the anonymous nhentai (nhentai.net) provider.
 * Default off. [SOURCE_ID] is the user-facing Browse source id. Installs
 * upgraded from a censored build keep their choice: the first read migrates
 * the retired censored-build persistence key forward via
 * [SourceKeyMigration].
 * Unofficial client — not affiliated with the site.
 */
class NhentaiEnableStore(
    private val prefs: PreferenceMap,
) {
    fun isEnabled(): Boolean =
        when (SourceKeyMigration.readMigrated(prefs, KEY_ENABLED, LegacyKeys.NHENTAI_ENABLED, null)) {
            TRUE_VALUE -> true
            else -> false
        }

    fun setEnabled(enabled: Boolean) {
        prefs.putString(KEY_ENABLED, if (enabled) TRUE_VALUE else FALSE_VALUE)
    }

    companion object {
        const val SOURCE_ID = "nhentai"
        const val DISPLAY_NAME = "nhentai"
        private const val KEY_ENABLED = EhentaiStoreKeys.NHENTAI_ENABLED_KEY
        private const val TRUE_VALUE = "true"
        private const val FALSE_VALUE = "false"
    }
}
