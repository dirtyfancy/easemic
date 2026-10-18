package com.easemic.app.feature.settings

/**
 * Snapshot of the two background-work risk factors behind owner bug A14
 * ("后台下载/上云不稳定"). Phone-verified root cause on HONOR PGT-AN20
 * (Android 16): both download/upload pumps run as a proper `dataSync`
 * foreground service, but the app never requests runtime
 * `POST_NOTIFICATIONS` — so on Android 13+ the notification is silently
 * denied, the foreground service is invisible, and plain Doze without a
 * battery-optimization exemption kills the invisible background work once
 * the screen turns off.
 *
 * Owner bug A51: there is deliberately no manufacturer input. Both checks
 * apply to every Android device, so no phone is ever shown its own vendor's
 * name. The HONOR reference above is engineering provenance, not copy.
 *
 * This is a plain snapshot; see `work/BackgroundPermissions.kt` for how it's
 * read from the real Android APIs.
 */
data class BackgroundHealth(
    val notificationsGranted: Boolean,
    val ignoringBatteryOptimizations: Boolean,
)

/**
 * One actionable remediation surfaced on the Settings "Background tasks" row.
 * Sealed so the UI layer can exhaustively render every case.
 */
sealed class BackgroundAdvice {
    /** POST_NOTIFICATIONS was never granted — the pump's FGS is invisible. */
    data object GrantNotifications : BackgroundAdvice()

    /** Not exempt from Doze/App Standby battery optimization. */
    data object IgnoreBatteryOptimizations : BackgroundAdvice()
}

/**
 * Derives the ordered advice list for [health]: notifications first (the
 * verified root cause), then the battery-optimization exemption. Ordering is
 * deterministic and intentional — notifications is the single fix that was
 * actually missing on the reference device, so it always leads.
 */
fun backgroundHealthAdvice(health: BackgroundHealth): List<BackgroundAdvice> {
    val advice = mutableListOf<BackgroundAdvice>()
    if (!health.notificationsGranted) {
        advice += BackgroundAdvice.GrantNotifications
    }
    if (!health.ignoringBatteryOptimizations) {
        advice += BackgroundAdvice.IgnoreBatteryOptimizations
    }
    return advice
}
