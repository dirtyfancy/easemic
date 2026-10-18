package com.easemic.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo

/**
 * Shared foreground notification for the background pumps (cloud ingest,
 * local downloads): one low-importance channel, same shape as
 * [ImportForegroundInfo] but decoupled from scan-specific run/request ids —
 * the pumps have no single "run" to key a persistent notification id off of,
 * so a stable per-pump id is used instead.
 */
class PumpForegroundInfo(private val context: Context) {
    fun create(notificationId: Int, text: String): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Background sync", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(text)
            .setOngoing(true)
            .build()
        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val CHANNEL_ID = "background-pumps"
        const val CLOUD_INGEST_NOTIFICATION_ID = 4_201
        const val DOWNLOAD_NOTIFICATION_ID = 4_202
        const val HYDRATION_NOTIFICATION_ID = 4_203
        const val CLOUD_INGEST_TEXT = "Syncing to cloud"
        const val DOWNLOAD_TEXT = "Downloading"
        const val HYDRATION_TEXT = "Importing cloud library"
    }
}
