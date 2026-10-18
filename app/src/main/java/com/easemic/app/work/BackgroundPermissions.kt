package com.easemic.app.work

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import androidx.core.app.NotificationManagerCompat
import com.easemic.app.feature.settings.BackgroundHealth

/**
 * Android-framework glue for owner bug A14 ("后台下载/上云不稳定"). The pure
 * decision logic lives in `feature/settings/BackgroundHealth.kt` — everything
 * here just reads the real platform state or builds an `Intent`, so none of
 * it is covered by JVM unit tests (no Robolectric in this project; the pure
 * `backgroundHealthAdvice` model is what *is* tested).
 */

/** Reads the current [BackgroundHealth] snapshot from live Android APIs. */
fun currentBackgroundHealth(context: Context): BackgroundHealth {
    val notificationsGranted = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val ignoringBatteryOptimizations =
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    return BackgroundHealth(
        notificationsGranted = notificationsGranted,
        ignoringBatteryOptimizations = ignoringBatteryOptimizations,
    )
}

/**
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for this app, scoped with a
 * `package:` URI. Requires `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in the
 * manifest (added alongside this — see `AndroidManifest.xml`). Google Play
 * policy frowns on this permission for general use; it is justified here by
 * the phone-verified root cause (an invisible, OEM-killed foreground service)
 * and is presented to the user as an opt-in row, never requested silently.
 */
fun batteryOptimizationIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = "package:${context.packageName}".toUri()
    }

/** Deep-links to this app's system notification settings page. */
fun appNotificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
