package com.easemic.app.work

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.work.impl.foreground.SystemForegroundService
import kotlinx.coroutines.delay

/**
 * #C72: does this app hold a dataSync foreground service right now, or is it visible? Read after a
 * pump's `setForeground` returned. WorkManager starts the service asynchronously, so a negative
 * answer is re-read for up to [waitMs] before it is believed. `getRunningServices` still reports the
 * caller's own services on every API level we target.
 */
object ForegroundServiceProbe {
    private const val STEP_MS = 50L

    suspend fun isProtected(context: Context, waitMs: Long = 1_500L): Boolean {
        if (Build.VERSION.SDK_INT < 35) return true
        val deadline = System.currentTimeMillis() + waitMs
        while (true) {
            val visible = appVisible(context)
            val held = holdsDataSyncForegroundService(context)
            if (pumpIsProtected(Build.VERSION.SDK_INT, visible, held)) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(STEP_MS)
        }
    }

    private fun appVisible(context: Context): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        // Only a real UI counts: IMPORTANCE_FOREGROUND_SERVICE (125) sorts below VISIBLE (200) and
        // would read "visible" for exactly the process this probe is about.
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    /**
     * Fails OPEN when the lookup itself is unavailable (review finding, decided): failing closed would
     * stop every background transfer on a device whose OEM blocks this read — strictly worse than the
     * pre-#C72 unprotected pass (same reasoning as C43 audit M4). The failure is logged so it shows.
     */
    @Suppress("DEPRECATION")
    private fun holdsDataSyncForegroundService(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return true
        val services = runCatching { am.getRunningServices(Int.MAX_VALUE) }
            .onFailure { android.util.Log.w("BgWork", "fgs probe unavailable (${it.javaClass.simpleName}); treating as protected") }
            .getOrNull() ?: return true
        val ours = SystemForegroundService::class.java.name
        // The manifest declares WorkManager's SystemForegroundService with dataSync as its only type,
        // so "that service is foreground" is "a dataSync FGS is held" (RunningServiceInfo has no type field).
        return services.any { s -> s.service.packageName == context.packageName && s.service.className == ours && s.foreground }
    }
}
