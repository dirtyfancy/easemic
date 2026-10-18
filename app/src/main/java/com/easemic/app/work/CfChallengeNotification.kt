package com.easemic.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.cfChallengeBannerSourceLabel
import com.easemic.app.feature.download.cfChallengeJob
import com.easemic.app.feature.download.shouldNotifyCfChallenge
import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.ui.SharedPreferencesMap
import com.easemic.app.ui.CloudflareChallengeActivity

/**
 * Dismissible "Solve" notification for a parked nh Cloudflare-challenge job
 * (T2b) — a separate, DEFAULT-importance channel from [PumpForegroundInfo]'s
 * ongoing (LOW-importance, non-dismissible) foreground-service notification,
 * so this one heads-up and the user can dismiss it without stopping the pump.
 *
 * [updateFor] is the single entry point, called by [DownloadPumpWorker] after
 * every tick: it posts at most once per distinct challenged job
 * ([shouldNotifyCfChallenge] dedupes against [notifiedForJobId], an in-memory
 * marker — acceptable since this only needs to survive a single process's
 * pump run, not a cold start) and cancels itself once the challenge clears.
 */
object CfChallengeNotification {
    const val NOTIFICATION_ID = 4_204
    private const val CHANNEL_ID = "attention"

    @Volatile
    private var notifiedForJobId: String? = null

    fun updateFor(context: Context, jobs: List<DownloadJob>) {
        val target = shouldNotifyCfChallenge(jobs, notifiedForJobId)
        if (target != null) {
            post(context)
            notifiedForJobId = target
            return
        }
        if (cfChallengeJob(jobs) == null && notifiedForJobId != null) {
            cancel(context)
            notifiedForJobId = null
        }
    }

    private fun post(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Needs attention", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val solveIntent = Intent(context, CloudflareChallengeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            solveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Name the real source when its enable store is on; only nhentai can
        // ever be the challenge target, but the banner is only ever posted
        // for a user who enabled it — fall back to a generic label instead of
        // leaking a source the user never turned on.
        val sourceLabel = cfChallengeBannerSourceLabel(
            nhentaiEnabled = NhentaiEnableStore(SharedPreferencesMap.from(context)).isEnabled(),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("$sourceLabel needs a quick check")
            .setContentText("Tap to solve the Cloudflare challenge and resume downloads")
            .setContentIntent(pending)
            .addAction(0, "Solve", pending)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
