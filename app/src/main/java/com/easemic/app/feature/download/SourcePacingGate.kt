package com.easemic.app.feature.download

import android.util.Log
import com.easemic.app.sync.redactNamespace
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/**
 * SEND_BACK M1 (owner principle "rate limiting is per source/IP" — 2026-09-05
 * review of S6-A Task 3): [DownloadWorkerLoop] used to resolve
 * [SourceThrottlePolicy] per JOB and sleep a per-gallery timer AFTER each
 * page fetch. With N concurrent same-source galleries (S6-A's own fan-out
 * feature), that is N INDEPENDENT timers, so the aggregate request rate
 * against that source rose with the concurrency cap instead of staying
 * flat — and [SourceThrottlePolicy.maxConcurrentFetches] was never enforced
 * anywhere, and manifest fetches were never paced at all.
 *
 * This gate is the fix: ONE registry entry per `providerNamespace`, shared
 * by every concurrently-fetching gallery of that source. [paced] must wrap
 * EVERY upstream fetch (manifest AND each page); [DownloadWorkerLoop] no
 * longer sleeps its own per-gallery timer.
 *
 *  - A [Semaphore] sized [SourceThrottlePolicy.maxConcurrentFetches] bounds
 *    how many fetches to that source may be IN FLIGHT at once (e-h: 3 —
 *    three in flight, one shared start timer, so N e-h galleries still
 *    share ONE page/s).
 *  - A [Mutex]-guarded `lastStartAt` timestamp bounds how often a NEW fetch
 *    may START: no earlier than the previous start + interRequestDelayMs +
 *    jitter, regardless of which gallery is asking. Jitter is drawn exactly
 *    once per [paced] call — never recomputed/stacked in a retry loop.
 *
 * Different sources (distinct `providerNamespace` values, including
 * distinct `opds:<host>` instances — see [SourceThrottlePolicy.forNamespace])
 * get independent registry entries and never throttle each other:
 * concurrency only helps ACROSS sources (and for cloud uploads).
 *
 * Clock and delay are injected (defaulting to real time / real
 * [kotlinx.coroutines.delay]) so production callers get real pacing while
 * tests can reuse whatever fake `clockMs`/`delayMs` seams they already
 * inject into [DownloadWorkerLoop] for deterministic timing.
 */
class SourcePacingGate(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val delayMs: suspend (Long) -> Unit = { ms -> kotlinx.coroutines.delay(ms) },
    /** Real bounded random jitter; overridable so tests can pin it. */
    private val jitterMs: (Long) -> Long =
        { maxMs -> if (maxMs <= 0) 0L else kotlin.random.Random.nextLong(maxMs + 1) },
) {
    private class SourceState(policy: SourceThrottlePolicy) {
        // Sized at FIRST use of this namespace: a policy that became dynamic
        // (#C48 user-adjustable threads) would not resize this Semaphore until
        // process restart — and the re-base bound in `reserveStartSlot`
        // would desynchronise from a shrunk semaphore too.
        val semaphore = Semaphore(policy.maxConcurrentFetches)
        val mutex = Mutex()

        // Far enough in the past that the very first fetch on a fresh
        // registry entry never waits.
        var lastStartAt = Long.MIN_VALUE / 2

        // #C17 r3 item 3: the largest spacing ever reserved on this state
        // (base + jitter draw), tracked so the re-base bound in
        // reserveStartSlot can use the WORST-CASE spacing ever seen, not
        // whatever this call's own jitter draw happens to be.
        var maxSpacingSeen = 0L
    }

    private val states = ConcurrentHashMap<String, SourceState>()

    /**
     * Runs [block] as one paced upstream fetch against [namespace]: waits
     * for an in-flight permit for that source, waits (if needed) for the
     * shared per-source spacing rule to allow a new fetch to start, then
     * runs [block]. The permit is always released, even if [block] throws,
     * so a failed fetch never leaks a slot.
     */
    suspend fun <T> paced(namespace: String, label: String = "", spacing: Boolean = true, block: suspend () -> T): T {
        val policy = SourceThrottlePolicy.forNamespace(namespace)
        val state = states.computeIfAbsent(namespace) { SourceState(policy) }
        val queuedAt = nowMs()
        state.semaphore.acquire()
        try {
            // #C17 r3 item 8: inside the try — once the permit is held, any
            // exception (including from nowMs() itself) must still release it
            // via finally, so the timestamp read moved off the acquire/try
            // seam where an exception there would leak the permit.
            val permittedAt = nowMs()
            if (spacing) reserveStartSlot(state, policy)
            val startedAt = nowMs()
            // #C17 device leg: logcat-ONLY (never the diag ring). One start
            // line and one done line per paced fetch, labelled by the caller
            // (job + kind), so a run can be attributed per gallery, gaps can
            // exclude the manifest, and in-flight can be counted.
            Log.i("PageFetch", "start ns=${redactNamespace(namespace)} $label permitWaitMs=${permittedAt - queuedAt} spacingWaitMs=${startedAt - permittedAt} at=$startedAt")
            return block()
        } finally {
            Log.i("PageFetch", "done ns=${redactNamespace(namespace)} $label at=${nowMs()}")
            state.semaphore.release()
        }
    }

    /**
     * Reserves this fetch's start slot under [state]'s mutex: computing the
     * wait AND publishing the new `lastStartAt` happen atomically, so two
     * callers racing for the same source each get a distinct, correctly
     * spaced slot instead of both reading the same stale timestamp.
     */
    private suspend fun reserveStartSlot(state: SourceState, policy: SourceThrottlePolicy) {
        val waitMs = state.mutex.withLock {
            val now = nowMs()
            val spacing = policy.interRequestDelayMs + jitterMs(policy.interRequestJitterMs)
            state.maxSpacingSeen = maxOf(state.maxSpacingSeen, spacing)
            // #C17 audit A5 / re-review item 1: `lastStartAt` is a RESERVATION —
            // on the healthy path it is legitimately in the future, by at most
            // one spacing per permit holder (only permit holders reserve, and
            // each adds one spacing). A reservation further ahead than that can
            // only come from a backward wall-clock step (the seam is
            // System.currentTimeMillis, shared with the persisted
            // resumeNotBeforeMs); treat it as "the last start was now" so the
            // stall is one spacing, never the whole delta — and never rewind
            // the timer when lanes are merely queued, which would collapse
            // their spacing into a burst. #C17 r3 item 3: the bound uses the
            // largest spacing ever reserved on this state (and the policy's
            // own worst case), never this call's draw — a small draw must not
            // make an honest reservation look like a jump (#C17 r3 item 3).
            val maxLegitimateReservation =
                maxOf(policy.interRequestDelayMs + policy.interRequestJitterMs, state.maxSpacingSeen) *
                    policy.maxConcurrentFetches.coerceAtLeast(1)
            if (state.lastStartAt - now > maxLegitimateReservation) state.lastStartAt = now
            val earliestStart = state.lastStartAt + spacing
            val wait = (earliestStart - now).coerceAtLeast(0L)
            state.lastStartAt = now + wait
            wait
        }
        if (waitMs > 0) delayMs(waitMs)
    }
}
