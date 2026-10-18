package com.easemic.app.feature.settings

import com.easemic.app.ui.PreferenceMap

/**
 * Tracks whether [MainActivity][com.easemic.app.MainActivity]
 * has already shown the runtime `POST_NOTIFICATIONS` request once for this
 * install (owner bug A14). Without this, every cold start on Android 13+
 * would re-prompt a user who already dismissed it — naggy for a permission
 * most people decide on once. A user who declined still sees the
 * "Grant notifications" advice on the Settings "Background tasks" row, which
 * deep-links to the system notification settings instead of re-prompting.
 */
class BackgroundNotificationAskedStore(
    private val prefs: PreferenceMap,
) {
    fun hasAsked(): Boolean = prefs.getString(KEY, null) == "1"

    fun markAsked() {
        prefs.putString(KEY, "1")
    }

    companion object {
        const val KEY = "background.notification_permission_asked"
    }
}
