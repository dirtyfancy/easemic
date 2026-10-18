package com.easemic.app.feature.settings.backup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupFileNames {
    /** `easemic-backup-2026-09-27.pclarchive` — the date is the phone's local day. */
    fun archive(nowMs: Long): String =
        "easemic-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs)) + ".pclarchive"
}
