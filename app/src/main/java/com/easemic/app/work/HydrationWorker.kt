package com.easemic.app.work

import android.content.Context
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
import com.easemic.app.feature.collect.CloudHydrationEngine
import com.easemic.app.feature.collect.HydrationOutcome
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Drains [CloudHydrationEngine] one [CloudHydrationEngine.hydrateStep] per
 * loop iteration off the UI lifecycle: the third unique foreground pump
 * (loyal-pipelines Slice B §2), alongside [CloudIngestWorker] and
 * [DownloadPumpWorker]. Cold start and the foreground return use
 * [kickWhenIdle] (C29 audit MEDIUM-2: REPLACE while nothing is RUNNING, so a
 * walk parked in WorkManager's 30 s post-kill backoff cannot swallow the
 * kick; KEEP while one IS running); a bind or unlock uses [kickNow]
 * (APPEND_OR_REPLACE — a user action must preempt any [kickAfter] backoff
 * wait already enqueued, per loyal-pipelines Slice B review hygiene). [kick]
 * itself (plain KEEP) remains the passive reconnect re-kick
 * ([com.easemic.app.work.CloudConnectivityReKick]).
 *
 * A [HydrationOutcome.Blocked] result (locked/unreadable library) ends the
 * run without spinning against a bucket the engine cannot currently read —
 * the next unlock/rebind/cold-start kick retries. Repeated transient
 * [HydrationOutcome.Failed] results end the run and reschedule via
 * [kickAfter] once [hydrationPumpNextAction] decides enough is enough.
 *
 * No `rekickAfterCurrent` (unlike the ingest/download pumps): nothing
 * external enqueues new hydration work mid-run — a bind that lands while
 * this worker is running only changes what the engine's `fingerprint()`
 * lambda returns on the *next* step, and this same loop already picks that
 * up on its next iteration.
 */
class HydrationWorker(
    appContext: Context,
    params: WorkerParameters,
    private val engine: CloudHydrationEngine?,
    private val foregroundInfo: (() -> ForegroundInfo)?,
    /** C43 test seam: replaces [setForeground] so a denied FGS start can be injected. */
    private val foregroundSetter: (suspend (ForegroundInfo) -> Unit)? = null,
) : CoroutineWorker(appContext, params) {
    constructor(appContext: Context, params: WorkerParameters) :
        this(appContext, params, null, null)

    override suspend fun doWork(): Result {
        val e = engine ?: return Result.failure()
        // C43: a background restart denied its FGS must not run unprotected.
        // Hydration is passive replay work with no user-visible row, so it
        // simply exits; the next foreground (AppGraph.onAppForeground) or
        // bind kick re-runs it inside a window where the start is allowed.
        if (foregroundInfo != null) {
            try {
                // Audit M1: the ForegroundInfo CONSTRUCTION stays inside the catch.
                val info = foregroundInfo.invoke()
                foregroundSetter?.invoke(info) ?: setForeground(info)
                // #C72: a spent dataSync budget leaves no FGS although setForeground returned.
                if (foregroundSetter == null && !ForegroundServiceProbe.isProtected(applicationContext)) {
                    Log.w(TAG, "HydrationWorker holds no foreground service (dataSync budget spent); exiting without a pass")
                    return Result.success()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                when (foregroundFailureAction(failure)) {
                    ForegroundFailureAction.SKIP_PASS_AND_RETRY -> {
                        Log.w(TAG, "HydrationWorker foreground start DENIED (${failure.javaClass.simpleName}); exiting without a pass")
                        return Result.success()
                    }
                    ForegroundFailureAction.CONTINUE_UNPROTECTED ->
                        // log-redaction: reviewed same as CloudIngestWorker's twin -- setForeground throws a WorkManager/platform exception whose message is built from framework constants only and can carry no path/URL.
                        Log.w(TAG, "HydrationWorker setForeground failed (${failure.javaClass.simpleName}); pumping without notification", failure)
                }
            }
        }
        var consecutiveFailures = 0
        while (true) {
            val outcome = try {
                e.hydrateStep()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // hydrateStep() already maps its own failures onto
                // HydrationOutcome.Failed, but guard against an unexpected
                // throw anyway: an uncaught exception here must not spin the
                // worker forever, so treat it the same as a mapped Failed.
                HydrationOutcome.Failed(error.message ?: "CLOUD_HYDRATION_FAILED")
            }
            val failedThisStep = outcome is HydrationOutcome.Failed
            when (val action = hydrationPumpNextAction(outcome, consecutiveFailures)) {
                HydrationPumpAction.Continue -> {
                    consecutiveFailures = if (failedThisStep) consecutiveFailures + 1 else 0
                    if (failedThisStep) {
                        // Politeness delay so a transient-failure loop does not
                        // hot-spin against a flaky network/store.
                        delay(2_000L)
                    }
                }
                HydrationPumpAction.ExitSuccess -> break
                is HydrationPumpAction.ExitRetryLater -> {
                    kickAfter(applicationContext, action.delayMs)
                    break
                }
            }
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "cloud-hydration-pump"
        private const val TAG = "BgWork"

        private fun pumpRequest(initialDelayMs: Long = 0L) = OneTimeWorkRequestBuilder<HydrationWorker>()
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
         * C29 audit MEDIUM-2: the policy for a kick that must never be
         * SWALLOWED. [kick]'s KEEP is a no-op against a request WorkManager is
         * holding — a run killed by process death is re-enqueued with
         * `run_attempt_count > 0` and sits in the default EXPONENTIAL 30 s
         * backoff (the #C46 shape), and [kickAfter] parks its own up-to-60 s
         * delayed request after repeated transient failures. Only REPLACE
         * cancels either, so "reopen the app" resumed the walk only once the
         * wait expired.
         *
         * The hydration twin of [CloudIngestWorker.userKickPolicy] with
         * `chainBehindRunning = false`: a RUNNING walk is already doing the
         * work this kick asks for (KEEP — never cancel it, never chain one
         * redundant walk per app open), an idle chain is preempted (REPLACE),
         * and an unreadable WorkInfo takes the policy that is never a no-op
         * and never cancels (APPEND_OR_REPLACE).
         */
        fun kickWhenIdlePolicy(anyRunning: Boolean?): ExistingWorkPolicy = when (anyRunning) {
            true -> ExistingWorkPolicy.KEEP
            false -> ExistingWorkPolicy.REPLACE
            null -> ExistingWorkPolicy.APPEND_OR_REPLACE
        }

        /**
         * Cold-start / foreground-return hydration kick (C29 audit MEDIUM-2).
         * Reads the unique chain's state off the main thread and enqueues with
         * [kickWhenIdlePolicy]; modelled on [CloudIngestWorker.kickUser].
         *
         * Review LOW-2: the read and the enqueue are not atomic — a request can
         * go ENQUEUED -> RUNNING in between, so the REPLACE branch can cancel a
         * walk that had just started. The window is one WorkInfo read wide and
         * the cost is nil: the replacement request runs immediately and
         * [CloudHydrationEngine] resumes from its checkpoint, so "never cancel a
         * RUNNING walk" is the policy's intent, not a guarantee it can make.
         */
        suspend fun kickWhenIdle(context: Context) {
            val workManager = WorkManager.getInstance(context)
            val anyRunning: Boolean? = withContext(Dispatchers.IO) {
                try {
                    workManager.getWorkInfosForUniqueWork(UNIQUE_NAME).get()
                        .any { it.state == WorkInfo.State.RUNNING }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "HydrationWorker kickWhenIdle could not read WorkInfo (${error.javaClass.simpleName}); appending")
                    null
                }
            }
            workManager.enqueueUniqueWork(UNIQUE_NAME, kickWhenIdlePolicy(anyRunning), pumpRequest())
        }

        /**
         * Bind/unlock rekick (loyal-pipelines Slice B review hygiene):
         * APPEND_OR_REPLACE, unlike [kick]'s KEEP. A bind or unlock is a
         * user action, not a passive replay check, and must not be
         * swallowed by [kickAfter]'s up-to-60s `ExitRetryLater` backoff wait
         * — KEEP would leave that already-enqueued delayed request in place
         * and drop this kick on the floor for up to a minute. Safe to call
         * when already hydrated: the next `hydrateStep()` just sees
         * `hydrationComplete` and exits immediately (Idle/ExitSuccess).
         */
        fun kickNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                pumpRequest(),
            )
        }

        /**
         * Delayed rekick after repeated transient failures
         * ([hydrationPumpNextAction]'s `ExitRetryLater`). APPEND_OR_REPLACE
         * (unlike [kick]'s KEEP) so a fresh delayed request always wins over a
         * stale one left behind by a previous run.
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
