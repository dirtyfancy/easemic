package com.easemic.app.work

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.easemic.app.feature.settings.BackgroundRefreshRunStore
import java.util.concurrent.TimeUnit

data class CollectedSeriesRefreshRequest(
    val uniqueName: String,
    val repeatIntervalMs: Long,
    val unmetered: Boolean,
    val batteryNotLow: Boolean,
)

interface PeriodicWorkGateway {
    fun update(request: CollectedSeriesRefreshRequest)
    fun cancel(uniqueName: String)
}

class WorkManagerPeriodicRefreshGateway(
    private val workManager: WorkManager,
) : PeriodicWorkGateway {
    override fun update(request: CollectedSeriesRefreshRequest) {
        workManager.enqueueUniquePeriodicWork(
            request.uniqueName,
            ExistingPeriodicWorkPolicy.UPDATE,
            collectedSeriesRefreshWorkRequest(request),
        )
    }

    override fun cancel(uniqueName: String) {
        workManager.cancelUniqueWork(uniqueName)
    }
}

fun collectedSeriesRefreshWorkRequest(request: CollectedSeriesRefreshRequest): PeriodicWorkRequest {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (request.unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
        )
        .setRequiresBatteryNotLow(request.batteryNotLow)
        .build()
    return PeriodicWorkRequestBuilder<CollectedSeriesRefreshWorker>(
        request.repeatIntervalMs,
        TimeUnit.MILLISECONDS,
    )
        .setConstraints(constraints)
        .addTag(CollectedSeriesRefreshScheduler.TAG)
        .build()
}

/**
 * Unique periodic work [UNIQUE_NAME], every 12 hours.
 * Wi-Fi-only uses [NetworkType.UNMETERED]; otherwise [NetworkType.CONNECTED].
 * Battery-not-low is always required. UPDATE so a constraint change replaces the request.
 * The first enqueue writes schedule-since; cancel clears it.
 */
class CollectedSeriesRefreshScheduler(
    private val enabled: () -> Boolean,
    private val wifiOnly: () -> Boolean,
    private val hasSerializedFollows: () -> Boolean,
    private val work: PeriodicWorkGateway,
    private val runs: BackgroundRefreshRunStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun reconcile() {
        if (!enabled() || !hasSerializedFollows()) {
            work.cancel(UNIQUE_NAME)
            runs.clearScheduleSince()
            return
        }
        val wifiOnlyOn = wifiOnly()
        work.update(
            CollectedSeriesRefreshRequest(
                uniqueName = UNIQUE_NAME,
                repeatIntervalMs = INTERVAL_MS,
                unmetered = wifiOnlyOn,
                batteryNotLow = true,
            ),
        )
        runs.writeScheduleSinceIfAbsent(nowMs())
    }

    companion object {
        const val UNIQUE_NAME = "collected-series-refresh"
        const val TAG = "collected-series-refresh"
        const val INTERVAL_MS = 12L * 60 * 60 * 1000
    }
}
