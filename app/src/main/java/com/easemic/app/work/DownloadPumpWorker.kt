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
import com.easemic.app.feature.download.DownloadWorkerLoop
import com.easemic.app.ui.SharedPreferencesMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Refreshes a download foreground notice without touching the C43 start bookkeeping. */
internal suspend fun refreshForegroundNotice(
    previousText: String?,
    foregroundText: (suspend () -> String)?,
    foregroundInfo: (suspend () -> ForegroundInfo)?,
    foregroundSetter: (suspend (ForegroundInfo) -> Unit)?,
    setForeground: suspend (ForegroundInfo) -> Unit,
): String? {
    if (foregroundInfo == null || foregroundText == null) return previousText
    val currentText = try {
        foregroundText.invoke()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return previousText
    }
    if (currentText == previousText) return previousText
    val info = try {
        foregroundInfo.invoke()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return previousText
    }
    return try {
        foregroundSetter?.invoke(info) ?: setForeground(info)
        currentText
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        previousText
    }
}

/**
 * Drains [DownloadWorkerLoop] off the Application coroutine scope: ticks back
 * to back with no delay while jobs are runnable (mirroring
 * [DownloadWorkerLoop.runForever], which has no inter-tick delay on
 * productive ticks either), then on an unproductive tick waits 1s before
 * retrying so a job enqueued moments after the queue drains is still picked
 * up, and only finishes doWork() after two consecutive idle ticks
 * ([downloadPumpShouldStop]). A single unique KEEP-policy work request per
 * [kick] call — a re-kick while one is already running/enqueued is a no-op.
 */
class DownloadPumpWorker(
    appContext: Context,
    params: WorkerParameters,
    private val loop: DownloadWorkerLoop?,
    private val foregroundInfo: (suspend () -> ForegroundInfo)?,
    private val checkCfChallenge: (suspend () -> Unit)? = null,
    /** C43 test seam: replaces [setForeground] so a denied FGS start can be injected. */
    private val foregroundSetter: (suspend (ForegroundInfo) -> Unit)? = null,
    /** C43 test seam: replaces the pool pass; production always drives [loop]. */
    private val pass: (suspend () -> Boolean)? = null,
    /**
     * #C72 test seam: after a normal `setForeground`, is a foreground service actually held? Production
     * reads [ForegroundServiceProbe]; a test that injects [foregroundSetter] alone is treated as held.
     */
    private val foregroundHeld: (suspend () -> Boolean)? = null,
    /** Current foreground notice text; used only to detect a changed refresh title. */
    private val foregroundText: (suspend () -> String)? = null,
    /** How often a running pass re-reads [foregroundText] (one pool pass can drain the whole queue). */
    private val noticeRefreshMs: Long = NOTICE_REFRESH_MS,
) : CoroutineWorker(appContext, params) {
    constructor(appContext: Context, params: WorkerParameters) :
        this(appContext, params, null, null, null)

    /** Set once the foreground notice is up; only then is its text kept current. */
    @Volatile private var foregroundStarted = false

    /** The notice's current text; written by the in-pass refresher and between passes. */
    @Volatile private var lastForegroundText: String? = null

    override suspend fun doWork(): Result {
        val l = loop
        val runPass: suspend () -> Boolean = pass ?: (l?.let { { it.runDownloadPool() } } ?: return Result.failure())
        // C43: a pump (re)started while the app is backgrounded is DENIED its
        // foreground service on Android 12+. Running the pass anyway leaves
        // this worker in a cached process with no FGS holding it, where the
        // OS freezes/kills it and progress silently stops — the owner's
        // "switched apps, it stopped". So a denied start is recorded (the
        // Activity tab shows it), NOT run, and retried shortly; the next
        // foreground moment (AppGraph.onAppForeground) kicks it for real.
        if (foregroundInfo != null) {
            try {
                // Audit M1: the ForegroundInfo CONSTRUCTION stays inside the
                // catch (as pre-C43) — a null NotificationManager or an OEM
                // channel/builder throw is not a denied FGS start.
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
                foregroundStarted = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                when (foregroundFailureAction(failure)) {
                    ForegroundFailureAction.SKIP_PASS_AND_RETRY -> {
                        val streak = recordForegroundDenied(failure)
                        if (deniedSelfRetryAllowed(streak)) {
                            kickAfter(applicationContext, FGS_DENIED_RETRY_MS)
                        } else {
                            Log.w(TAG, "DownloadPumpWorker foreground start denied $streak× in a row — self-retry budget spent; waiting for foreground return / connectivity / cold start")
                        }
                        return Result.success()
                    }
                    ForegroundFailureAction.CONTINUE_UNPROTECTED ->
                        // log-redaction: reviewed same as CloudIngestWorker's twin -- setForeground throws a WorkManager/platform exception whose message is built from framework constants only and can carry no path/URL.
                        Log.w(TAG, "DownloadPumpWorker setForeground failed (${failure.javaClass.simpleName}); pumping without notification", failure)
                }
            }
        }
        if (foregroundStarted) {
            lastForegroundText = readForegroundTextSafely()
        }
        return try {
            var idleTicks = 0
            while (true) {
                val advanced = try {
                    // #C39: drain via the job-level pool, not tick(): the pool
                    // keeps up to maxActiveGalleries galleries in flight AND
                    // admits a gallery enqueued mid-pass into a free slot,
                    // instead of running the running gallery's whole pass first.
                    runPassRefreshingNotice(runPass)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // runDownloadPool already maps its own per-page/job failures
                    // onto the job store, but guard against an unexpected throw
                    // anyway: an uncaught exception here must not spin the worker
                    // forever on the same broken state, so treat the pass as
                    // unproductive instead of crash-looping.
                    false
                }
                try {
                    checkCfChallenge?.invoke()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Surfacing the Cloudflare-challenge notification must never
                    // crash the pump — worst case it just doesn't (re)notify this
                    // tick and tries again next tick.
                }
                if (advanced) {
                    idleTicks = 0
                    lastForegroundText = refreshForegroundIfChanged(lastForegroundText)
                    continue
                }
                idleTicks++
                if (downloadPumpShouldStop(idleTicks)) break
                delay(1_000L)
            }
            // Final queue check: if a job was enqueued during our exit decision,
            // re-kick so it's not orphaned (APPEND_OR_REPLACE, since KEEP would
            // no-op against this worker's still-RUNNING state).
            if (runPassRefreshingNotice(runPass)) {
                lastForegroundText = refreshForegroundIfChanged(lastForegroundText)
                rekickAfterCurrent(applicationContext)
            } else {
                // App-open windowed resume (whole-slice review GAP 1): the queue is
                // empty but not necessarily exhausted — a job parked on a
                // QUOTA/PACED/ABUSE_STOP resume window drops out of the
                // consumption queue until it lapses, and nothing else re-checks
                // that window while the app stays open (only cold start via
                // AppGraph did). Schedule our own delayed wake so it resumes
                // in-process instead of sitting inert until the next cold start.
                l?.earliestResumeDelayMs()?.let { delayMs -> kickAfter(applicationContext, delayMs) }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            recordSystemStop()
            // C43: mirror CloudIngestWorker — a system-stopped pump must leave
            // a kick behind, or its job waits for a cold start / a network
            // transition / a manual tap to be driven again. KEEP is a no-op
            // while a replacement is pending and starts a fresh drain when
            // there is none.
            kick(applicationContext)
            throw cancelled
        }
    }

    /**
     * Runs one pass while re-reading the notice text every [noticeRefreshMs]. One pool pass drains the whole queue, so
     * refreshing only between passes would show the opening text until every chapter was saved.
     */
    private suspend fun runPassRefreshingNotice(runPass: suspend () -> Boolean): Boolean {
        if (!foregroundStarted) return runPass()
        return coroutineScope {
            val refresher = launch {
                while (true) {
                    delay(noticeRefreshMs)
                    lastForegroundText = refreshForegroundIfChanged(lastForegroundText)
                }
            }
            try {
                runPass()
            } finally {
                refresher.cancel()
            }
        }
    }

    /** Reads refresh text without entering the initial foreground-denial bookkeeping path. */
    private suspend fun readForegroundTextSafely(): String? = try {
        foregroundText?.invoke()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /** Refreshes the same foreground notice only when its text changed. */
    private suspend fun refreshForegroundIfChanged(previousText: String?): String? =
        refreshForegroundNotice(
            previousText = previousText,
            foregroundText = foregroundText,
            foregroundInfo = foregroundInfo,
            foregroundSetter = foregroundSetter,
            setForeground = { info -> setForeground(info) },
        )

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
        Log.w(TAG, "DownloadPumpWorker foreground start DENIED (${failure.javaClass.simpleName}: ${failure.message}) — pass skipped; foreground return re-kicks")
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

    /**
     * Owner bug A14: see [CloudIngestWorker.recordSystemStop] for why this
     * exists — same phone-verified root cause (invisible FGS on Android 13+
     * without a POST_NOTIFICATIONS grant, killed by OEM app-launch
     * management/Doze) and the same reason it lives here rather than in an
     * `override fun onStopped()`: `CoroutineWorker.onStopped()` is `final` in
     * work-runtime 2.11.2 (confirmed via `javap`), so the system stop is only
     * observable as a [CancellationException] surfacing out of the pump loop
     * above. Best-effort only; the caller always rethrows afterward.
     */
    private fun recordSystemStop() {
        if (!isStopped) return
        // C43: name the reason, not just the time — WorkManager 2.11 exposes
        // the platform stop reason; the existing mapper turns it into a
        // PlatformStopKind the Activity tab / Settings can show.
        val stopReason = platformStopReasonOrNull()
        val kind = mapStopReason(workInfoPresent = true, state = null, stopReason = stopReason)
        try {
            val prefs = SharedPreferencesMap.from(applicationContext)
            val latestGrantedAtMs = prefs.getString(LAST_FGS_GRANTED_PREF_KEY, null)?.toLongOrNull()
            if (!shouldRecordSystemStop(kind, ownMarkMs, latestGrantedAtMs, lastAppCancelMarkMs)) {
                Log.i(TAG, "DownloadPumpWorker stopped (reason=$kind/$stopReason) — app cancel or superseded by a newer granted pump; not recorded")
                return
            }
            Log.w(TAG, "DownloadPumpWorker stopped by system (isStopped, reason=$kind/$stopReason) — will resume on next kick")
            prefs.putString(LAST_STOPPED_PREF_KEY, System.currentTimeMillis().toString())
            prefs.putString(LAST_STOP_REASON_PREF_KEY, kind.name)
        } catch (_: Exception) {
            // Best-effort only; must never throw from a cancellation path.
        }
    }

    companion object {
        /** #E2 4b Task D: the download notice's text is re-read this often while a pass runs. */
        const val NOTICE_REFRESH_MS: Long = 5_000L
        const val UNIQUE_NAME = "download-pump"
        private const val TAG = "BgWork"

        /** Read by [com.easemic.app.AppGraph.lastDownloadPumpStoppedAtMs]. */
        const val LAST_STOPPED_PREF_KEY = "bg_work.download_pump.last_stopped_at_ms"

        /** C43: [PlatformStopKind] name of the last system stop (blank = none). */
        const val LAST_STOP_REASON_PREF_KEY = "bg_work.download_pump.last_stop_reason"

        /** C43: when a background restart was last denied its FGS (blank = none / cleared on a granted start). */
        const val LAST_FGS_DENIED_PREF_KEY = "bg_work.download_pump.last_fgs_denied_at_ms"

        /** C43 audit B1: consecutive denials (blank/0 = none); bounds the self-retry, reset on a granted start. */
        const val FGS_DENIED_STREAK_PREF_KEY = "bg_work.download_pump.fgs_denied_streak"

        /** C43 audit H1: start stamp of the last pump that was granted its FGS; a stop from an older pump is stale. */
        const val LAST_FGS_GRANTED_PREF_KEY = "bg_work.download_pump.last_fgs_granted_at_ms"

        /** Clears the denial/stop markers (the Activity line) and the denial streak. */
        fun clearPauseMarkers(context: Context) {
            SharedPreferencesMap.from(context).apply {
                putString(LAST_FGS_DENIED_PREF_KEY, "")
                putString(FGS_DENIED_STREAK_PREF_KEY, "")
                putString(LAST_STOPPED_PREF_KEY, "")
                putString(LAST_STOP_REASON_PREF_KEY, "")
            }
        }

        private fun pumpRequest(initialDelayMs: Long = 0L) = OneTimeWorkRequestBuilder<DownloadPumpWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .apply {
                if (initialDelayMs > 0L) setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            }
            .build()

        fun kick(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                pumpRequest(),
            )
        }

        /**
         * C43: the user-initiated kick — today only the foreground return in
         * `AppGraph.onAppForeground`. Reuses [CloudIngestWorker.kickPolicy] —
         * REPLACE an idle/backed-off request so a pump parked by a denied
         * background FGS start runs NOW, inside the foreground window where
         * the start is allowed. With [chainBehindRunning] a RUNNING pump gets
         * a run APPENDed (a genuine new piece of work, as a tap would be);
         * without it a RUNNING pump is a KEEP no-op (audit M2: the foreground
         * return is not new work, and it fires on every onStart).
         * `suspend`: reading the pump's WorkInfo blocks, so it runs on IO.
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
                    // log-redaction: reviewed getWorkInfosForUniqueWork throws a WorkManager ExecutionException/InterruptedException over its own internal state; no app-supplied string (let alone an acquisition URL) reaches its message.
                    Log.w(TAG, "DownloadPumpWorker kickUser could not read WorkInfo — appending", error)
                    null
                }
            }
            val policy = CloudIngestWorker.userKickPolicy(anyRunning, chainBehindRunning)
            if (policy == ExistingWorkPolicy.REPLACE) lastAppCancelMarkMs = System.currentTimeMillis()
            workManager.enqueueUniqueWork(UNIQUE_NAME, policy, pumpRequest())
        }

        /** Re-audit M-2: see [CloudIngestWorker]'s field of the same name — the download chain has its own. */
        @Volatile
        private var lastAppCancelMarkMs: Long = 0L

        /**
         * Startup replay for a resume-windowed cold start (spec §1.4 FIX2): the
         * immediate consumption queue can be empty solely because every
         * WAIT/DOWNLOAD job is still inside a QUOTA/ban idle window
         * ([com.easemic.app.feature.download.earliestResumeAfter]),
         * not because there is no runnable work — nothing else re-checks
         * `resumeNotBeforeMs` once it lapses, so a plain cold start would
         * otherwise never wake the pump back up.
         *
         * Policy is APPEND_OR_REPLACE, and (C43 audit L4, correcting an older
         * claim here) that does NOT make a fresh wake win over a stale delayed
         * request — it CHAINS behind whatever is pending. That is harmless from
         * the pump's own exit path (the chain's leaf is the calling worker) and
         * from a cold start (nothing of this process is pending yet). Do not
         * call it from a repeating foreground path; use [kickAfterUnlessPending].
         */
        fun kickAfter(context: Context, delayMs: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                pumpRequest(initialDelayMs = delayMs.coerceAtLeast(0L)),
            )
        }

        /**
         * C43 audit H3: the foreground-return variant of [kickAfter]. A denied
         * background run CONSUMES the delayed request the exit path left for a
         * QUOTA/ban resume window, so the foreground moment has to restore it —
         * but it fires on every onStart, and APPEND_OR_REPLACE would chain one
         * node per app open. KEEP enqueues the delayed wake only when nothing
         * (running, enqueued, or delayed) is pending for the pump.
         */
        fun kickAfterUnlessPending(context: Context, delayMs: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                pumpRequest(initialDelayMs = delayMs.coerceAtLeast(0L)),
            )
        }

        /**
         * Exit-path re-kick: APPEND_OR_REPLACE chains the new run after the
         * current one instead of KEEP-dropping it while this worker is still
         * RUNNING (the enqueue is evaluated async and can land before our
         * RUNNING→SUCCEEDED transition commits). Normal kicks stay KEEP so
         * bursts of user actions never chain redundant runs.
         */
        fun rekickAfterCurrent(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                pumpRequest(),
            )
        }
    }
}
