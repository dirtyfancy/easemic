package com.easemic.app.feature.browse

import com.easemic.app.feature.providers.SourceKeyMigration
import com.easemic.app.ui.PreferenceMap

/**
 * Persists the Browse-shell navigation [BrowsePrefs] to the app's private
 * preference storage. Per-source filter state is stored by
 * [PreferenceMapSourceFilterStore], not here. A persisted source-id value
 * written by a censored build is mapped to the current id once via
 * [SourceKeyMigration.migrateLegacySourceIdValue].
 */
class BrowsePrefsStore(
    private val prefs: PreferenceMap,
    private val ehentaiEnabled: () -> Boolean = { false },
    private val exhentaiEnabled: () -> Boolean = { false },
    private val nhentaiEnabled: () -> Boolean = { false },
    private val zaimanhuaEnabled: () -> Boolean = { false },
    private val jmEnabled: () -> Boolean = { false },
) {
    fun read(): BrowsePrefs {
        val defaults = BrowsePrefs()
        val rawSourceId = SourceKeyMigration.migrateLegacySourceIdValue(
            prefs.getString(KEY_SOURCE_ID, defaults.sourceId) ?: defaults.sourceId,
        )
        val sourceId = normalizeBrowseSourceId(
            raw = rawSourceId,
            ehentaiEnabled = ehentaiEnabled(),
            exhentaiEnabled = exhentaiEnabled(),
            nhentaiEnabled = nhentaiEnabled(),
            zaimanhuaEnabled = zaimanhuaEnabled(),
            jmEnabled = jmEnabled(),
        )
        if (sourceId != rawSourceId) {
            prefs.putString(KEY_SOURCE_ID, sourceId)
        }
        return BrowsePrefs(
            sourceId = sourceId,
            advancedEnabled = prefs.getBooleanFlag(KEY_ADVANCED_ENABLED, defaults.advancedEnabled),
        )
    }

    fun write(value: BrowsePrefs) {
        prefs.putString(
            KEY_SOURCE_ID,
            normalizeBrowseSourceId(
                raw = value.sourceId,
                ehentaiEnabled = ehentaiEnabled(),
                exhentaiEnabled = exhentaiEnabled(),
                nhentaiEnabled = nhentaiEnabled(),
                zaimanhuaEnabled = zaimanhuaEnabled(),
                jmEnabled = jmEnabled(),
            ),
        )
        prefs.putBooleanFlag(KEY_ADVANCED_ENABLED, value.advancedEnabled)
    }

    private fun PreferenceMap.getBooleanFlag(key: String, default: Boolean): Boolean =
        when (getString(key, null)) {
            TRUE_VALUE -> true
            FALSE_VALUE -> false
            else -> default
        }

    private fun PreferenceMap.putBooleanFlag(key: String, value: Boolean) {
        putString(key, if (value) TRUE_VALUE else FALSE_VALUE)
    }

    companion object {
        private const val KEY_SOURCE_ID = "browse_prefs_source_id"
        private const val KEY_ADVANCED_ENABLED = "browse_prefs_advanced_enabled"
        private const val TRUE_VALUE = "true"
        private const val FALSE_VALUE = "false"
    }
}
