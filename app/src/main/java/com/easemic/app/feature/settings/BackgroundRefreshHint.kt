package com.easemic.app.feature.settings

/** Honor and Huawei 应用启动管理 screens. Read on the owner's Honor, 2026-10-05. */
object BackgroundRefreshStartupScreens {
    const val HONOR_PACKAGE = "com.hihonor.systemmanager"
    const val HONOR_CLASS = "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
    const val HUAWEI_PACKAGE = "com.huawei.systemmanager"
    const val HUAWEI_CLASS = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"

    val candidates: List<Pair<String, String>> = listOf(
        HONOR_PACKAGE to HONOR_CLASS,
        HUAWEI_PACKAGE to HUAWEI_CLASS,
    )
}

data class StartupComponentResolve(
    val requestedPackage: String,
    val requestedClass: String,
    val resolvedPackage: String?,
    val resolvedClass: String?,
    val exported: Boolean,
    val permission: String?,
    val appHoldsPermission: Boolean,
)

sealed class BackgroundRefreshOpenTarget {
    data class Vendor(val packageName: String, val className: String) : BackgroundRefreshOpenTarget()
    data object AppDetails : BackgroundRefreshOpenTarget()
}

const val BACKGROUND_REFRESH_HINT_MS = 48L * 60 * 60 * 1000
private const val DAY_MS = 24L * 60 * 60 * 1000

fun shouldShowBackgroundRefreshHint(
    settingEnabled: Boolean,
    hasSerializedFollows: Boolean,
    lastFinishedAtMillis: Long?,
    scheduleSinceMillis: Long,
    dismissedAtMillis: Long?,
    nowMillis: Long,
): Boolean {
    if (!settingEnabled || !hasSerializedFollows) return false
    val clock = backgroundRefreshHintClockStart(lastFinishedAtMillis, scheduleSinceMillis, dismissedAtMillis)
    return nowMillis - clock >= BACKGROUND_REFRESH_HINT_MS
}

fun backgroundRefreshHintClockStart(
    lastFinishedAtMillis: Long?,
    scheduleSinceMillis: Long,
    dismissedAtMillis: Long?,
): Long = maxOf(lastFinishedAtMillis ?: 0L, scheduleSinceMillis, dismissedAtMillis ?: 0L)

fun backgroundRefreshHintDays(nowMillis: Long, clockStartMillis: Long): Int =
    ((nowMillis - clockStartMillis) / DAY_MS).toInt()

fun backgroundRefreshHintCopy(days: Int, vendorScreenUsable: Boolean): String =
    if (vendorScreenUsable) {
        "Background checks have not run for $days days. In 应用启动管理, turn off 自动管理 for Easemic, then allow 自启动, 关联启动 and 后台活动 so new chapters are saved."
    } else {
        "Background checks have not run for $days days. Check that Easemic may run in the background."
    }

/**
 * A candidate is usable only when it resolves to exactly itself, is exported, and has no
 * permission (or one this app holds). `resolveActivity` alone is not enough: the Honor
 * `HSM_STARTUPAPP_MANAGER` action resolves to a permission-guarded activity.
 */
fun chooseBackgroundRefreshOpenTarget(
    resolves: List<StartupComponentResolve>,
): BackgroundRefreshOpenTarget {
    val usable = resolves.firstOrNull { resolve ->
        resolve.resolvedPackage == resolve.requestedPackage &&
            resolve.resolvedClass == resolve.requestedClass &&
            resolve.exported &&
            (resolve.permission == null || resolve.appHoldsPermission)
    }
    return if (usable == null) {
        BackgroundRefreshOpenTarget.AppDetails
    } else {
        BackgroundRefreshOpenTarget.Vendor(usable.requestedPackage, usable.requestedClass)
    }
}
