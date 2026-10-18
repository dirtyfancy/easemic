package com.easemic.app.feature.providers

import com.easemic.app.ui.PreferenceMap

/**
 * #E117: On-device preference for whether collecting or uncollecting an
 * E-Hentai / ExHentai gallery in Easemic also updates the signed-in user's
 * ExHentai account favorites (`gallerypopups.php?...&act=addfav`).
 *
 * Defaults to `true` once the user has a proven ExHentai session grant so
 * signed-in ExHentai users get seamless sync out of the box, while allowing
 * them to turn it off in `More → Sources → ExHentai`.
 *
 * External unfavorites (e.g. removing a favorite inside EhViewer or a browser)
 * are deliberately never polled or synced back into Easemic (YAGNI).
 */
class ExhentaiFavoritesSyncStore(
    private val prefs: PreferenceMap,
    private val accountStore: EhentaiAccountStore,
) {
    fun isEnabled(): Boolean {
        if (!hasAccess()) return false
        val stored = prefs.getString(KEY_SYNC_ENABLED, null) ?: return true
        return stored == TRUE_VALUE
    }

    fun setEnabled(enabled: Boolean): Boolean {
        if (!hasAccess()) {
            prefs.putString(KEY_SYNC_ENABLED, FALSE_VALUE)
            return false
        }
        prefs.putString(KEY_SYNC_ENABLED, if (enabled) TRUE_VALUE else FALSE_VALUE)
        return enabled
    }

    private fun hasAccess(): Boolean =
        accountStore.read().exhentaiAccess == ExhentaiAccessVerdict.Granted

    companion object {
        const val TITLE = "Sync favorites with ExHentai"
        const val SUBTITLE =
            "Collecting or uncollecting an E-Hentai or ExHentai gallery also updates your ExHentai favorites."
        const val IMPORT_TITLE = "ExHentai account favorites"
        const val IMPORT_SUBTITLE_IDLE =
            "Queue your ExHentai favorites into Library and cloud storage. Unavailable galleries are skipped quietly."
        const val IMPORT_BUTTON_LABEL = "Import favorites"
        private const val KEY_SYNC_ENABLED = "exhentai_favorites_sync_enabled_v1"
        private const val TRUE_VALUE = "true"
        private const val FALSE_VALUE = "false"
    }
}
