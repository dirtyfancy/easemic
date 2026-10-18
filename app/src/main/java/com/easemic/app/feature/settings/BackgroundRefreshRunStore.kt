package com.easemic.app.feature.settings

import com.easemic.app.ui.PreferenceMap

data class BackgroundRefreshRun(
    val lastFinishedAtMillis: Long?,
    val scheduleSinceMillis: Long?,
    val dismissedAtMillis: Long?,
    val lastFoundCount: Int,
    val lastSavedCount: Int,
)

/**
 * One installation-wide record for the background check.
 * The worker's finished-run write uses [PreferenceMap.putStringSync].
 * Cancelling the schedule clears [scheduleSinceMillis] and leaves the rest.
 */
class BackgroundRefreshRunStore(
    private val prefs: PreferenceMap,
) {
    fun read(): BackgroundRefreshRun = BackgroundRefreshRun(
        lastFinishedAtMillis = readOptionalLong(prefs, KEY_FINISHED),
        scheduleSinceMillis = readOptionalLong(prefs, KEY_SCHEDULE_SINCE),
        dismissedAtMillis = readOptionalLong(prefs, KEY_DISMISSED),
        lastFoundCount = prefs.getString(KEY_FOUND, null)?.toIntOrNull() ?: 0,
        lastSavedCount = prefs.getString(KEY_SAVED, null)?.toIntOrNull() ?: 0,
    )

    fun writeScheduleSinceIfAbsent(nowMillis: Long) {
        if (read().scheduleSinceMillis != null) return
        prefs.putString(KEY_SCHEDULE_SINCE, nowMillis.toString())
    }

    fun clearScheduleSince() {
        prefs.putString(KEY_SCHEDULE_SINCE, "")
    }

    fun dismiss(nowMillis: Long) {
        prefs.putString(KEY_DISMISSED, nowMillis.toString())
    }

    fun recordFinished(nowMillis: Long, foundCount: Int, savedCount: Int) {
        prefs.putStringSync(KEY_FINISHED, nowMillis.toString())
        prefs.putStringSync(KEY_FOUND, foundCount.toString())
        prefs.putStringSync(KEY_SAVED, savedCount.toString())
    }

    companion object {
        const val KEY_FINISHED = "background.refresh.last_finished_at_ms"
        const val KEY_SCHEDULE_SINCE = "background.refresh.schedule_since_ms"
        const val KEY_DISMISSED = "background.refresh.dismissed_at_ms"
        const val KEY_FOUND = "background.refresh.last_found_count"
        const val KEY_SAVED = "background.refresh.last_saved_count"
    }
}
