package com.easemic.app.feature.update

/** What the app offers for a newer release: always the More row; the dialog only until the user picks Later. */
data class UpdateOffer(val release: AppRelease, val showDialog: Boolean)

object UpdateCheckPolicy {
    const val CHECK_INTERVAL_MS: Long = 24 * 60 * 60 * 1000L

    /** A clock moved backwards (now before the last check) counts as due, so checks can never stop for good. */
    fun shouldCheck(enabled: Boolean, lastCheckAtMs: Long, nowMs: Long): Boolean =
        enabled && (nowMs < lastCheckAtMs || nowMs - lastCheckAtMs >= CHECK_INTERVAL_MS)

    fun offer(installedCode: Int, release: AppRelease?, dismissedCode: Int): UpdateOffer? =
        release?.takeIf { it.versionCode > installedCode }
            ?.let { UpdateOffer(it, showDialog = it.versionCode > dismissedCode) }
}
