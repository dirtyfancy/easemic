package com.easemic.app.feature.providers

import com.easemic.app.ui.PreferenceMap

/**
 * Settings opt-in for the ExHentai (exhentai.org) provider. Unlike the other
 * sources this one is session-gated: it can only be turned on while a signed-in
 * session carries an igneous cookie, and it reports disabled the moment that
 * session goes away. Unofficial client — not affiliated with the site.
 */
class ExhentaiEnableStore(
    private val prefs: PreferenceMap,
    private val accountStore: EhentaiAccountStore,
) {
    /** Enabled only when the opt-in is set AND the session still grants access. */
    fun isEnabled(): Boolean =
        prefs.getString(KEY_ENABLED, null) == TRUE_VALUE && hasAccess()

    /**
     * Applies the opt-in. Returns whether the source ended up enabled, so the
     * caller can tell "you asked to enable but there is no session" apart from
     * a successful toggle.
     */
    fun setEnabled(enabled: Boolean): Boolean {
        if (!enabled) {
            prefs.putString(KEY_ENABLED, FALSE_VALUE)
            return false
        }
        if (!hasAccess()) {
            prefs.putString(KEY_ENABLED, FALSE_VALUE)
            return false
        }
        prefs.putString(KEY_ENABLED, TRUE_VALUE)
        return true
    }

    /** Why the source is unavailable, for the Settings copy. */
    fun accessVerdict(): ExhentaiAccessVerdict = accountStore.read().exhentaiAccess

    private fun hasAccess(): Boolean =
        accessVerdict() == ExhentaiAccessVerdict.Granted

    companion object {
        const val SOURCE_ID = "exhentai_catalog"
        const val DISPLAY_NAME = "ExHentai"
        private const val KEY_ENABLED = "exhentai_catalog_enabled_v1"
        private const val TRUE_VALUE = "true"
        private const val FALSE_VALUE = "false"
    }
}
