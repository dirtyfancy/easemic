package com.easemic.app.work

import androidx.work.WorkInfo

enum class PlatformStopKind {
    FOREGROUND_SERVICE_TIMEOUT,
    QUOTA,
    CANCELLED_BY_APP,
    PLATFORM_STOPPED,
    PROCESS_LOST,
}

fun mapStopReason(
    workInfoPresent: Boolean,
    state: WorkInfo.State?,
    stopReason: Int?,
): PlatformStopKind {
    if (!workInfoPresent) return PlatformStopKind.PROCESS_LOST
    return when (stopReason) {
        WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT ->
            PlatformStopKind.FOREGROUND_SERVICE_TIMEOUT
        WorkInfo.STOP_REASON_QUOTA -> PlatformStopKind.QUOTA
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> PlatformStopKind.CANCELLED_BY_APP
        else -> if (state == WorkInfo.State.CANCELLED) {
            PlatformStopKind.CANCELLED_BY_APP
        } else {
            PlatformStopKind.PLATFORM_STOPPED
        }
    }
}
