package com.easemic.app.work

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.easemic.app.feature.collect.CloudIngestRunner
import com.easemic.app.feature.collect.CloudSyncQueue
import com.easemic.app.ui.SharedPreferencesMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drains [CloudSyncQueue] to completion off the UI lifecycle: pumps every
 * WAITING row, and any FAILED row eligible per `shouldAutoResumeFailedCloudSync`
 * (T3c bounded auto-resume — see `nextRunnableCloudSyncRow`), through
 * [CloudIngestRunner] until the queue is drained. A single unique work
 * request per kick: background replays ([kick]) use KEEP, so a burst of them
 * collapses to one drain and never spawns parallel pumps, while a user action
 * ([kickUser]) uses the preempting policy in [kickPolicy]. Within that one
 * pump, [runCloudIngestPumpLoop]'s own `maxConcurrent` fan-out (S6-A, sized
 * from `MaxConcurrentTransfersStore`) IS the effective upload concurrency;
 * the runner's
 * [com.easemic.app.feature.collect.CloudSyncIngestGate] is
 * only a fixed upper ceiling shared by every call site, not itself a source
 * of concurrency width.
 *
 * A pass that deferred a row as transiently store-unresolvable, or left a
 * runnable row undrained (T3b), must come back — but NOT via
 * `Result.retry()`: WorkManager would then escalate its default exponential
 * backoff all the way to five hours (C35-b). It returns `Result.success()`
 * and re-schedules ITSELF with [kickAfter], starting at [RETRY_DELAY_MS] and
 * doubling per consecutive transient pass up to [RETRY_DELAY_CAP_MS] (SF-3).
 */
class CloudIngestWorker(
    appContext: Context,
    params: WorkerParameters,
    private val queue: CloudSyncQueue?,
    private val runner: CloudIngestRunner?,
    private val foregroundInfo: (suspend () -> ForegroundInfo)?,
    /**
     * S3 Task 6 (owner bug 2026-09-04): the durable sync ledger's `isSynced`
     * lookup — see [runCloudIngestPumpLoop]'s doc for the zombie-row guard
     * this drives. `null` (both the 3-arg legacy constructor below and every
     * pre-existing call site) preserves today's unconditional-run pump
     * behavior unchanged.
     */
    private val isKnownSynced: (suspend (String) -> Boolean)? = null,
    /**
     * S6-A (owner bug A7): live reader of the shared "downloads at once"
     * cap (`MaxConcurrentTransfersStore.read`), sampled once per [doWork] so
     * the fan-out width follows the Settings value on the pump's next run.
     * `null` (the 2-arg legacy constructor and every pre-existing call site)
     * means 1 — today's sequential drain.
     */
    private val maxConcurrent: (() -> Int)? = null,
    /** C43 test seam: replaces [setForeground] so a denied FGS start can be injected. */
    private val foregroundSetter: (suspend (ForegroundInfo) -> Unit)? = null,
    /** C43 test seam: replaces the drain pass; production always drives [runner]. */
    private val pass: (suspend () -> WorkerResultKind)? = null,
    /**
     * #C72 test seam: after a normal `setForeground`, is a foreground service actually held? Production
     * reads [ForegroundServiceProbe]; a test that injects [foregroundSetter] alone is treated as held.
     */
    private val foregroundHeld: (suspend () -> Boolean)? = null,
    private val repairSeriesIndexes: suspend (Boolean) -> Boolean = { true },
) : CoroutineWorker(appContext, params) {
    constructor(appContext: Context, params: WorkerParameters) :
        this(appContext, params, null, null, null, null)

    override suspend fun doWork(): Result {
        val runPass: suspend () -> WorkerResultKind = pass ?: run {
            val q = queue ?: return Result.failure()
            val r = runner ?: return Result.failure()
            val block: suspend () -> WorkerResultKind = {
                val slots = maxConcurrent?.invoke()?.coerceAtLeast(1) ?: 1
                Log.i(TAG, "CloudIngestWorker pump slots=$slots")
                runCloudIngestPumpLoop(
                    queue = q,
                    runner = r,
                    isKnownSynced = isKnownSynced,
                    maxConcurrent = slots,
                    repairSeriesIndexes = repairSeriesIndexes,
                )
            }
            block
        }
        // C43: see DownloadPumpWorker — a background restart denied its FGS is
        // recorded, skipped and retried shortly; never run unprotected.
        if (foregroundInfo != null) {
            try {
                // Audit M1: the ForegroundInfo CONSTRUCTION stays inside the catch.
                val info = foregroundInfo.invoke()
                foregroundSetter?.invoke(info) ?: setForeground(info)
                val held = foregroundHeld ?: if (foregroundSetter != null) ({ true }) else ({ ForegroundServiceProbe.isProtected(applicationContext) })
                if (!held()) {
                    // #C72: the dataSync budget is spent — WorkManager swallowed "Time limit already
                    // exhausted". No self-retry: only a foreground visit resets the budget, and
                    // AppGraph.onAppForeground REPLACE-kicks a pump with a marker.
                    recordForegroundDenied(IllegalStateException("no dataSync foreground service held after setForeground (budget spent)"))
                    return Result.success()
                }
                recordForegroundGranted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                when (foregroundFailureAction(failure)) {
                    ForegroundFailureAction.SKIP_PASS_AND_RETRY -> {
                        val streak = recordForegroundDenied(failure)
                        if (deniedSelfRetryAllowed(streak)) {
                            kickAfter(applicationContext, FGS_DENIED_RETRY_MS)
                        } else {
                            Log.w(TAG, "CloudIngestWorker foreground start denied $streak× in a row — self-retry budget spent; waiting for foreground return / connectivity / cold start")
                        }
                        return Result.success()
                    }
                    ForegroundFailureAction.CONTINUE_UNPROTECTED ->
                        // log-redaction: reviewed setForeground throws a WorkManager/platform exception (ForegroundServiceStartNotAllowedException / IllegalStateException) whose message is built from framework constants only -- no acquisition URL or on-disk path can reach it, and the stack is the diagnostic.
                        Log.w(TAG, "CloudIngestWorker setForeground failed (${failure.javaClass.simpleName}); pumping without notification", failure)
                }
            }
        }
        return try {
            val kind = runPass()
            // C35-b: this worker NEVER returns Result.retry(). WorkManager
            // would then apply its default EXPONENTIAL backoff (30 s -> 60 ->
            // ... -> 5 h) to a pump whose whole job is to drain a queue, and
            // the user's next Collect would sit behind it — the C35 stall.
            // An outstanding transient condition re-schedules a fresh pump
            // itself instead. SF-3: consecutive transient passes escalate the
            // delay (a flat 30 s poll against a half-hour outage is 60 wakes
            // and 60 failures on battery), but stop at RETRY_DELAY_CAP_MS,
            // and the counter resets the moment a pass drains cleanly.
            val delayMs = if (kind == WorkerResultKind.RETRY) {
                retryScheduleFor(kind, transientPasses.getAndIncrement())
            } else {
                transientPasses.set(0)
                retryScheduleFor(kind, 0)
            }
            delayMs?.let { kickAfter(applicationContext, it) }
            Result.success()
        } catch (cancelled: CancellationException) {
            recordSystemStop()
            // B1 (C35 audit): cancelling this coroutine does NOT stop the
            // blocking ingest it is driving. CloudIngestRunner's own
            // markInterrupted therefore lands only when that detached upload
            // finally returns — minutes later — long after the REPLACE-issued
            // (or re-enqueued) pump has drained an empty queue and exited. The
            // row then sits WAITING with no pump alive, which is exactly the
            // "tapped Collect, nothing ever happened" report. Leave a kick
            // behind: KEEP is a no-op while a replacement is still
            // pending/running, and starts a fresh drain when there is none.
            kick(applicationContext)
            throw cancelled
        }
    }

    /**
     * Owner bug A14 (phone-verified on HONOR PGT-AN20): when the system stops
     * this worker out from under it (constraints lost, OEM app-launch
     * management, Doze, etc.), `CoroutineWorker` cancels the coroutine job
     * backing [doWork] — which surfaces here as a [CancellationException] at
     * the next suspension point, not as a distinct callback. Note:
     * `CoroutineWorker.onStopped()` is `final` in work-runtime 2.11.2
     * (confirmed via `javap` — `ListenableWorker.onStopped()` is `open`, but
     * `CoroutineWorker` finalizes it to drive its own job cancellation), so a
     * literal `override fun onStopped()` does not compile here; this
     * catch-and-check-[isStopped] path is the actual interception point
     * available in this AndroidX version. Best-effort record of
     * [LAST_STOPPED_PREF_KEY] so a future Activity-tab surface can show "被系统
     * 中断" instead of leaving the row looking merely idle; this only makes
     * the interruption observable. B1: the actual resume is driven by the
     * [kick] the caller issues right after this returns — waiting on a cold
     * start, a connectivity regain or the next Collect was the bug, because
     * nothing guarantees any of those ever arrive. The caller always rethrows
     * [cancelled] so structured concurrency is unaffected.
     */
    private fun recordSystemStop() {
        if (!isStopped) return
        // C43: name the reason via the existing mapper (see DownloadPumpWorker).
        val stopReason = platformStopReasonOrNull()
        val kind = mapStopReason(workInfoPresent = true, state = null, stopReason = stopReason)
        try {
            val prefs = SharedPreferencesMap.from(applicationContext)
            val latestGrantedAtMs = prefs.getString(LAST_FGS_GRANTED_PREF_KEY, null)?.toLongOrNull()
            if (!shouldRecordSystemStop(kind, ownMarkMs, latestGrantedAtMs, lastAppCancelMarkMs)) {
                Log.i(TAG, "CloudIngestWorker stopped (reason=$kind/$stopReason) — app cancel or superseded by a newer granted pump; not recorded")
                return
            }
            Log.w(TAG, "CloudIngestWorker stopped by system (isStopped, reason=$kind/$stopReason) — will resume on next kick")
            prefs.putString(LAST_STOPPED_PREF_KEY, System.currentTimeMillis().toString())
            prefs.putString(LAST_STOP_REASON_PREF_KEY, kind.name)
        } catch (_: Exception) {
            // Best-effort only; must never throw from a cancellation path.
        }
    }

    /** `ListenableWorker.getStopReason()` is API 31+; below that WorkManager has no platform reason to report. */
    private fun platformStopReasonOrNull(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stopReason else null

    /** This worker's own start stamp; a granted start writes it, a stale stop compares against it (audit H1). */
    private val ownMarkMs: Long = System.currentTimeMillis()

    private fun recordForegroundGranted() {
        try {
            clearPauseMarkers(applicationContext)
            SharedPreferencesMap.from(applicationContext)
                .putString(LAST_FGS_GRANTED_PREF_KEY, ownMarkMs.toString())
        } catch (_: Exception) {
        }
    }

    /** Records the denial; returns the consecutive-denial streak including this one. */
    private fun recordForegroundDenied(failure: Exception): Int {
        Log.w(TAG, "CloudIngestWorker foreground start DENIED (${failure.javaClass.simpleName}: ${failure.message}) — pass skipped; foreground return re-kicks")
        return try {
            val prefs = SharedPreferencesMap.from(applicationContext)
            val streak = (prefs.getString(FGS_DENIED_STREAK_PREF_KEY, null)?.toIntOrNull() ?: 0) + 1
            prefs.putString(LAST_FGS_DENIED_PREF_KEY, System.currentTimeMillis().toString())
            prefs.putString(FGS_DENIED_STREAK_PREF_KEY, streak.toString())
            streak
        } catch (_: Exception) {
            FGS_DENIED_MAX_SELF_RETRIES + 1
        }
    }

    companion object {
        const val UNIQUE_NAME = "cloud-ingest-pump"
        private const val TAG = "BgWork"

        /** Read by [com.easemic.app.AppGraph.lastCloudIngestStoppedAtMs]. */
        const val LAST_STOPPED_PREF_KEY = "bg_work.cloud_ingest.last_stopped_at_ms"

        /** C43: [PlatformStopKind] name of the last system stop (blank = none). */
        const val LAST_STOP_REASON_PREF_KEY = "bg_work.cloud_ingest.last_stop_reason"

        /** C43: when a background restart was last denied its FGS (blank = none / cleared on a granted start). */
        const val LAST_FGS_DENIED_PREF_KEY = "bg_work.cloud_ingest.last_fgs_denied_at_ms"

        /** C43 audit B1: consecutive denials (blank/0 = none); bounds the self-retry, reset on a granted start. */
        const val FGS_DENIED_STREAK_PREF_KEY = "bg_work.cloud_ingest.fgs_denied_streak"

        /** C43 audit H1: start stamp of the last pump that was granted its FGS; a stop from an older pump is stale. */
        const val LAST_FGS_GRANTED_PREF_KEY = "bg_work.cloud_ingest.last_fgs_granted_at_ms"

        /** Clears the denial/stop markers (the Activity line) and the denial streak. */
        fun clearPauseMarkers(context: Context) {
            SharedPreferencesMap.from(context).apply {
                putString(LAST_FGS_DENIED_PREF_KEY, "")
                putString(FGS_DENIED_STREAK_PREF_KEY, "")
                putString(LAST_STOPPED_PREF_KEY, "")
                putString(LAST_STOP_REASON_PREF_KEY, "")
            }
        }

        private fun pumpRequest(initialDelayMs: Long = 0L) = OneTimeWorkRequestBuilder<CloudIngestWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .apply {
                if (initialDelayMs > 0L) setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            }
            .build()

        /**
         * C35-b: how long the FIRST transient outage waits before the pump
         * reschedules itself. [CloudIngestWorker] deliberately never returns
         * `Result.retry()`: WorkManager's default retry backoff is
         * EXPONENTIAL (30 s -> 60 -> 120 -> ... -> 5 h), and a queue-draining
         * pump that climbs it ends up asleep for minutes while the user is
         * tapping Collect — the C35 stall, observed live as
         * `ENQUEUED run_attempt_count=4 backoff 30000 ms exponential`. A dead
         * grant is already parked terminal (C37-g) and an unbound bucket
         * already maps to SUCCESS, so the only thing left polling at this
         * cadence is a genuinely transient outage.
         *
         * Correction (SF-3 audit): an earlier version of this doc claimed the
         * self-scheduled request only lives as long as the process. It does
         * not — a delayed `OneTimeWorkRequest` is persisted in WorkManager's
         * database and survives process death exactly like any other enqueued
         * request. The real difference from `Result.retry()` is the CAP
         * ([RETRY_DELAY_CAP_MS]) and the fact that [kickUser] can preempt it.
         */
        const val RETRY_DELAY_MS = 30_000L

        /**
         * SF-3 (C35 audit): the ceiling on that self-scheduled delay. A flat
         * 30 s is not a backoff — a two-hour outage costs 240 wakes, 240 store
         * resolutions and 240 failures on battery, and asking a twentieth time
         * in 30 s cannot fix what nineteen tries could not. The delay doubles
         * per CONSECUTIVE transient pass ([transientPasses]) and stops here,
         * which is the whole point: WorkManager's own chain would keep going
         * to five hours. A user [kickUser] preempts any pending delay, so this
         * cap bounds background recovery only, never a tap.
         */
        const val RETRY_DELAY_CAP_MS = 300_000L

        /**
         * SF-3: consecutive passes that ended [WorkerResultKind.RETRY], used
         * as the exponent for [retryScheduleFor]. Reset by the first pass that
         * drains cleanly. Process-lifetime on purpose: a process death already
         * means the device rebooted or the app was killed, and starting the
         * next recovery from 30 s again is the friendly answer — the persisted
         * delayed request that was already scheduled still fires regardless.
         */
        private val transientPasses = AtomicInteger(0)

        /**
         * C35-a: which [ExistingWorkPolicy] a kick should use.
         *
         * - Background replays (cold start, connectivity regain, repair
         *   sweeps, a download's completion handoff, and an interrupted
         *   pump's own comeback kick) -> KEEP: a burst collapses to one
         *   drain, and a helper that runs in a loop must not chain N drains.
         *   SF-2: a finishing download is NOT a tap. N downloads completing
         *   within a second would otherwise REPLACE-cancel the pump N times,
         *   and each cancel costs an in-flight upload its progress.
         * - A user action with NO pump running -> REPLACE. This is the point
         *   of C35-a: an ENQUEUED/BLOCKED request sitting in backoff (or on a
         *   [kickAfter] delay) is CANCELLED and a fresh pump starts now.
         *   Neither KEEP (a silent no-op) nor APPEND_OR_REPLACE (chains
         *   BLOCKED behind the backed-off request and waits it out) can do
         *   that, which is why the user's Collect used to be swallowed for
         *   minutes. It is safe because an interrupted ingest now parks its
         *   row WAITING with its checkpoint ([CloudSyncQueue.markInterrupted])
         *   instead of deleting it: the residual read-then-enqueue race
         *   (ENQUEUED flipping to RUNNING between [kickUser]'s WorkInfo read
         *   and its enqueue) costs a resumable interruption, not a lost row.
         * - A user action WHILE a pump is RUNNING -> APPEND_OR_REPLACE: the
         *   drain is chained after the current one, so an active upload is
         *   never interrupted, and the kick is still never a no-op.
         * - C43 audit M2: the foreground return (`AppGraph.onAppForeground`,
         *   every onStart) is user-initiated — it must REPLACE a pump parked
         *   by a denied background FGS start — but it is not NEW work, so
         *   with [chainBehindRunning] false a RUNNING pump is a KEEP no-op
         *   instead of one chained redundant drain per rotation/dialog.
         */
        fun kickPolicy(userInitiated: Boolean, anyRunning: Boolean, chainBehindRunning: Boolean = true): ExistingWorkPolicy = when {
            !userInitiated -> ExistingWorkPolicy.KEEP
            anyRunning -> if (chainBehindRunning) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
            else -> ExistingWorkPolicy.REPLACE
        }

        /**
         * [kickPolicy] for a user kick whose WorkInfo read may have FAILED
         * (`anyRunning == null`): the kick must then still be "never a no-op
         * and never cancels an upload", i.e. APPEND_OR_REPLACE, whatever
         * [chainBehindRunning] says (re-audit M-1 — assuming "running" would
         * make the foreground-return kick a KEEP no-op).
         */
        fun userKickPolicy(anyRunning: Boolean?, chainBehindRunning: Boolean): ExistingWorkPolicy =
            if (anyRunning == null) {
                ExistingWorkPolicy.APPEND_OR_REPLACE
            } else {
                kickPolicy(userInitiated = true, anyRunning = anyRunning, chainBehindRunning = chainBehindRunning)
            }

        /**
         * Re-audit M-2: wall-clock stamp of the last REPLACE this process
         * issued on the pump chain. Below API 31 a REPLACE-cancelled pump
         * cannot see `STOP_REASON_CANCELLED_BY_APP`, so [recordSystemStop]
         * compares its own construction stamp against this instead.
         */
        @Volatile
        private var lastAppCancelMarkMs: Long = 0L

        fun kick(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                kickPolicy(userInitiated = false, anyRunning = false),
                pumpRequest(),
            )
        }

        /**
         * C35-a: the kick behind a user action (Collect, manual Retry, bind).
         * Never swallowed, and never waits out a backoff — see [kickPolicy].
         *
         * `suspend` on purpose: deciding between REPLACE and APPEND_OR_REPLACE
         * needs the pump's current [WorkInfo], and
         * `getWorkInfosForUniqueWork(...).get()` is a BLOCKING
         * `ListenableFuture.get()`. Several callers are compose click handlers
         * on the main thread, so the query runs on [Dispatchers.IO] here
         * rather than leaving every call site to remember.
         */
        suspend fun kickUser(context: Context, chainBehindRunning: Boolean = true) {
            val workManager = WorkManager.getInstance(context)
            val anyRunning: Boolean? = withContext(Dispatchers.IO) {
                try {
                    workManager.getWorkInfosForUniqueWork(UNIQUE_NAME).get()
                        .any { it.state == WorkInfo.State.RUNNING }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Unreadable WorkInfo: take the policy that is never a
                    // no-op and can never cancel an upload (APPEND_OR_REPLACE).
                    // log-redaction: reviewed getWorkInfosForUniqueWork throws a WorkManager ExecutionException/InterruptedException over its own internal state; no app-supplied string (let alone an acquisition URL) reaches its message.
                    Log.w(TAG, "CloudIngestWorker kickUser could not read WorkInfo — appending", error)
                    null
                }
            }
            val policy = userKickPolicy(anyRunning, chainBehindRunning)
            if (policy == ExistingWorkPolicy.REPLACE) lastAppCancelMarkMs = System.currentTimeMillis()
            workManager.enqueueUniqueWork(UNIQUE_NAME, policy, pumpRequest())
        }

        /** Historical name for [kickUser]; every call site is a user action. */
        suspend fun kickNow(context: Context) = kickUser(context)

        /**
         * C35-b: reschedules the drain after [delayMs] — the pump's bounded
         * replacement for `Result.retry()`'s unbounded escalation. Mirrors
         * [DownloadPumpWorker.kickAfter]: APPEND_OR_REPLACE so a still-RUNNING
         * pump is never cancelled by its own re-kick.
         *
         * Correction (SF-3 audit): APPEND_OR_REPLACE does NOT make a fresh
         * wake "win over" a stale delayed request — it CHAINS the new request
         * behind whatever is pending. That is harmless here only because this
         * is called from inside a running `doWork`, i.e. the unique chain's
         * leaf is this very worker, so there is no stale leaf to queue behind.
         * Do not call it from anywhere else without re-deriving that.
         */
        fun kickAfter(context: Context, delayMs: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                pumpRequest(initialDelayMs = delayMs.coerceAtLeast(0L)),
            )
        }
    }
}
