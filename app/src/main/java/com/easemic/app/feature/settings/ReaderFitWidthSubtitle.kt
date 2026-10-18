package com.easemic.app.feature.settings

object ReaderFitWidthSubtitle {
    fun format(enabled: Boolean): String =
        if (enabled) {
            "On · pages fill screen width"
        } else {
            "Off · fit entire page on screen"
        }
}
