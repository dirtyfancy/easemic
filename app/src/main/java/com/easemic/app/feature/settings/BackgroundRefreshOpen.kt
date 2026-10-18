package com.easemic.app.feature.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Explicit components only. Never `HSM_STARTUPAPP_MANAGER` or `HSM_BOOTAPP_MANAGER`:
 * on the owner's Honor (WKL-AN20, API 37, systemmanager 170000452, read 2026-10-05)
 * those actions resolve to `StartupAppControlActivity`, which is exported but guarded
 * by `com.hihonor.systemmanager.permission.START_MODULE_UI` and throws SecurityException.
 * The usable screen is `startupmgr.ui.StartupNormalAppListActivity`.
 */
fun resolveStartupComponents(context: Context): List<StartupComponentResolve> =
    BackgroundRefreshStartupScreens.candidates.map { (packageName, className) ->
        val intent = Intent().setComponent(ComponentName(packageName, className))
        val info = context.packageManager.resolveActivity(intent, 0)?.activityInfo
        val permission = info?.permission?.takeIf { it.isNotEmpty() }
        StartupComponentResolve(
            requestedPackage = packageName,
            requestedClass = className,
            resolvedPackage = info?.packageName,
            resolvedClass = info?.name,
            exported = info?.exported == true,
            permission = permission,
            appHoldsPermission = permission != null &&
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
        )
    }

fun launchBackgroundRefreshOpen(context: Context) {
    val target = chooseBackgroundRefreshOpenTarget(resolveStartupComponents(context))
    val vendor = (target as? BackgroundRefreshOpenTarget.Vendor)?.let { chosen ->
        Intent().setComponent(ComponentName(chosen.packageName, chosen.className)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (vendor != null) {
        try {
            context.startActivity(vendor)
            return
        } catch (_: SecurityException) {
            // Fall through to the app's own details screen.
        } catch (_: ActivityNotFoundException) {
            // Fall through to the app's own details screen.
        }
    }
    context.startActivity(backgroundRefreshAppDetailsIntent(context))
}

fun backgroundRefreshAppDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData("package:${context.packageName}".toUri())
        .addFlags(if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK)

fun backgroundRefreshHintLine(
    context: Context,
    settingEnabled: Boolean,
    hasSerializedFollows: Boolean,
    lastFinishedAtMillis: Long?,
    scheduleSinceMillis: Long,
    dismissedAtMillis: Long?,
    nowMillis: Long,
): String? {
    if (!shouldShowBackgroundRefreshHint(
            settingEnabled = settingEnabled,
            hasSerializedFollows = hasSerializedFollows,
            lastFinishedAtMillis = lastFinishedAtMillis,
            scheduleSinceMillis = scheduleSinceMillis,
            dismissedAtMillis = dismissedAtMillis,
            nowMillis = nowMillis,
        )
    ) {
        return null
    }
    val days = backgroundRefreshHintDays(
        nowMillis = nowMillis,
        clockStartMillis = backgroundRefreshHintClockStart(
            lastFinishedAtMillis,
            scheduleSinceMillis,
            dismissedAtMillis,
        ),
    ).coerceAtLeast(2)
    val vendor = chooseBackgroundRefreshOpenTarget(resolveStartupComponents(context)) is
        BackgroundRefreshOpenTarget.Vendor
    return backgroundRefreshHintCopy(days, vendor)
}
