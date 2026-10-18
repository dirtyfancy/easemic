package com.easemic.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.easemic.app.feature.importing.ImportProgressStatus
import com.easemic.app.model.ScanRunId
import com.easemic.app.model.WorkRequestId

/**
 * A30 §1/§3: the scan notification states progress and offers nothing. There
 * is no user-facing pause — a parked run is picked back up by "Rescan now"
 * or pull-to-refresh — so the notification carries no action.
 */
class ImportForegroundInfo(private val context: Context) {
    fun create(runId: ScanRunId, requestId: WorkRequestId, processedCount: Int): ForegroundInfo =
        create(runId, requestId, ImportWorkProgress(completed = processedCount, total = null))

    fun create(runId: ScanRunId, requestId: WorkRequestId, progress: ImportWorkProgress): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Local library import", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Updating local library")
            .setContentText(
                ImportProgressStatus.notificationText(progress.completed, progress.total),
            )
            .setOngoing(true)
            .build()
        return ForegroundInfo(
            runId.value.hashCode(),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private companion object {
        const val CHANNEL_ID = "local_library_import"
    }
}
