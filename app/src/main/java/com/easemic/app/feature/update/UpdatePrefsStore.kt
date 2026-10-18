package com.easemic.app.feature.update

import com.easemic.app.ui.PreferenceMap

/** #E1: the update check's switch (default on), last check time, last answer and the version whose dialog was dismissed. */
class UpdatePrefsStore(private val prefs: PreferenceMap) {
    fun isEnabled(): Boolean = prefs.getString(KEY_ENABLED, null) != "false"
    fun setEnabled(enabled: Boolean) = prefs.putString(KEY_ENABLED, enabled.toString())
    fun lastCheckAtMs(): Long = prefs.getString(KEY_LAST_CHECK, null)?.toLongOrNull() ?: 0L
    fun setLastCheckAtMs(value: Long) = prefs.putString(KEY_LAST_CHECK, value.toString())
    fun dismissedCode(): Int = prefs.getString(KEY_DISMISSED, null)?.toIntOrNull() ?: 0
    fun setDismissedCode(value: Int) = prefs.putString(KEY_DISMISSED, value.toString())
    fun cachedRelease(): AppRelease? = prefs.getString(KEY_RELEASE, null)?.let(AppRelease::parse)
    fun setCachedRelease(release: AppRelease) = prefs.putString(KEY_RELEASE, release.toJson())

    fun debugOffersAllowed(): Boolean = prefs.getString(KEY_DEBUG_OFFERS, null) == "true"
    fun setDebugOffersAllowed(allowed: Boolean) = prefs.putString(KEY_DEBUG_OFFERS, allowed.toString())

    private companion object {
        const val KEY_ENABLED = "app_update_check_enabled_v1"
        const val KEY_LAST_CHECK = "app_update_last_check_ms_v1"
        const val KEY_DISMISSED = "app_update_dismissed_code_v1"
        const val KEY_RELEASE = "app_update_cached_release_v1"
        const val KEY_DEBUG_OFFERS = "app_update_debug_offers_v1"
    }
}
