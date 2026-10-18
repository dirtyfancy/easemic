package com.easemic.app.work

import com.easemic.app.feature.collect.HydrationOutcome
import com.easemic.app.sync.CloudSignIn

/** What [HydrationWorker] should do after one [CloudHydrationEngine.hydrateStep] call. */
sealed interface HydrationPumpAction {
    /** Run another step immediately (loop continues). */
    data object Continue : HydrationPumpAction

    /** Nothing left to do this run — end cleanly. */
    data object ExitSuccess : HydrationPumpAction

    /** Too many consecutive transient failures — end this run and rekick after [delayMs]. */
    data class ExitRetryLater(val delayMs: Long) : HydrationPumpAction
}

/**
 * Pump exit rule for one [HydrationOutcome]. `Progress(done=false)` keeps
 * looping; `Progress(done=true)`/[HydrationOutcome.Idle] end the run cleanly
 * (hydration finished, or no bucket bound). [HydrationOutcome.Blocked] (a
 * locked/unreadable library) also ends the run cleanly rather than spinning
 * against a bucket the engine cannot currently read — the next kick (unlock,
 * rebind, cold start) retries. [HydrationOutcome.Failed] (transient) keeps
 * looping until [consecutiveFailures] — which the caller tracks and passes in
 * as the count *before* this outcome, incrementing it only on a `Failed`
 * result — plus this failure reaches [maxConsecutiveFailures], at which point
 * the run ends and reschedules itself after [retryDelayMs] instead of
 * hot-looping against a broken network/store.
 */
fun hydrationPumpNextAction(
    outcome: HydrationOutcome,
    consecutiveFailures: Int,
    maxConsecutiveFailures: Int = 3,
    retryDelayMs: Long = 60_000L,
): HydrationPumpAction = when (outcome) {
    is HydrationOutcome.Progress ->
        if (outcome.done) HydrationPumpAction.ExitSuccess else HydrationPumpAction.Continue
    HydrationOutcome.Idle -> HydrationPumpAction.ExitSuccess
    is HydrationOutcome.Blocked -> HydrationPumpAction.ExitSuccess
    // #D16: a refused sign-in does not get better by asking again; like Blocked, end the run cleanly — a renewal or
    // rebind (onBucketBound kicks hydration), a reconnect or the next foreground runs it again.
    is HydrationOutcome.Failed ->
        if (CloudSignIn.rejected(outcome.message)) {
            HydrationPumpAction.ExitSuccess
        } else if (consecutiveFailures + 1 >= maxConsecutiveFailures) {
            HydrationPumpAction.ExitRetryLater(retryDelayMs)
        } else {
            HydrationPumpAction.Continue
        }
}
