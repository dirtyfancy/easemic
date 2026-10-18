package com.easemic.app.work

import com.easemic.app.feature.bridge.ByoCloudIoCopy
import com.easemic.app.feature.collect.CloudIngestOutcome
import com.easemic.app.feature.collect.CloudIngestRunner
import com.easemic.app.feature.collect.CloudSyncFailurePolicy
import com.easemic.app.feature.collect.CloudSyncIngestAutoRetry
import com.easemic.app.feature.collect.CloudSyncQueue
import com.easemic.app.feature.collect.CloudSyncQueueItem
import com.easemic.app.feature.collect.CloudSyncStage
import com.easemic.app.feature.collect.shouldAutoResumeFailedCloudSync
import com.easemic.app.sync.CloudSignIn
import com.easemic.app.sync.LibraryLockedException
import com.easemic.app.sync.WebDavObjectStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which row the pump should run next; null = drained.
 *
 * A WAITING row is always preferred over a FAILED one — it is either fresh
 * or already mid-resume, closer to completion than a bounded FAILED retry.
 * Once no WAITING row remains, the first FAILED row [shouldAutoResumeFailedCloudSync]
 * says is eligible right now (bounded attempts + backoff window) is picked.
 *
 * Owner bug A3/C5 fix (T3c): before this, a FAILED row was NEVER returned
 * here — [CloudIngestWorker]'s pump loop only ever drains WAITING rows, so a
 * row that failed (e.g. a transient post-reopen R2 hiccup) sat forever
 * showing "retrying · resumes automatically" in the UI while nothing ever
 * actually re-drove it.
 */
fun nextRunnableCloudSyncRow(
    rows: List<CloudSyncQueueItem>,
    nowMs: Long = System.currentTimeMillis(),
): CloudSyncQueueItem? {
    rows.firstOrNull { it.stage == CloudSyncStage.WAITING && !it.preflightPending }?.let { return it }
    return rows.firstOrNull { row ->
        !row.preflightPending && CloudSyncFailurePolicy.canRetry(row.errorMessage) && shouldAutoResumeFailedCloudSync(
            stage = row.stage,
            failedAttempts = row.failedAttempts,
            failedAtEpochMs = row.failedAtEpochMs,
            nowMs = nowMs,
        )
    }
}

/**
 * Whether a pass that has just exited still leaves work for a later pump.
 *
 * SF-1 (C35 audit): this is NOT the same question as
 * [nextRunnableCloudSyncRow]. That selector asks "is a row runnable RIGHT
 * NOW", and the pump's exit check runs microseconds after the slot's own
 * [CloudSyncQueue.markFailed] — so a row whose backoff window
 * ([CloudSyncIngestAutoRetry.backoffDelayMs], 1-8 s) has obviously not
 * elapsed yet reads as "nothing to do". The pass returned
 * [WorkerResultKind.SUCCESS], nothing rescheduled the pump, and the row sat
 * in the Activity tab saying "retrying · resumes automatically" with nothing
 * on earth about to retry it.
 *
 * A FAILED row that still has retry budget WILL be eligible in seconds, so it
 * counts as work remaining and the pass maps to [WorkerResultKind.RETRY] ->
 * [CloudIngestWorker.kickAfter]. Bounded on purpose: a row that has spent all
 * [CloudSyncIngestAutoRetry.MAX_ATTEMPTS] — including one parked terminal by
 * [CloudSyncQueue.markFailedTerminal] on a dead grant (C37-g) — is NOT work
 * remaining, because only a user Retry or a rebind ever revives it, and
 * rescheduling for it would be an unbounded wake loop.
 */
fun cloudIngestWorkRemains(
    rows: List<CloudSyncQueueItem>,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (nextRunnableCloudSyncRow(rows, nowMs) != null) return true
    return rows.any { row ->
        !row.preflightPending &&
            row.stage == CloudSyncStage.FAILED &&
            CloudSyncFailurePolicy.canRetry(row.errorMessage) &&
            row.failedAttempts < CloudSyncIngestAutoRetry.MAX_ATTEMPTS
    }
}

/**
 * [CloudIngestWorker.doWork] result (T3b).
 *
 * [RETRY] does NOT mean `Result.retry()` (C35-b): it means the pass drained
 * what it could but a transient condition left work outstanding, so the pump
 * must re-schedule ITSELF, via [retryScheduleFor] /
 * [CloudIngestWorker.kickAfter]. Handing WorkManager a `Result.retry()`
 * instead let its default EXPONENTIAL backoff climb to five HOURS on a worker
 * whose entire job is to drain a queue; that was the C35 stall. The
 * self-scheduled delay escalates too (SF-3) but stops at five minutes, and a
 * user kick preempts it outright — neither of which a WorkManager retry chain
 * can do.
 */
enum class WorkerResultKind { SUCCESS, RETRY }

/**
 * How long [CloudIngestWorker.doWork] should wait before re-kicking itself
 * for [kind], or null when nothing needs rescheduling. Pure, so the cadence is
 * unit-testable without a WorkManager/Robolectric harness.
 *
 * SF-3 (C35 audit): [consecutiveTransient] is how many passes in a row have
 * already ended [WorkerResultKind.RETRY] (0 for the first). A flat 30 s was
 * not a backoff at all: an outage lasting an hour bought 120 wakes, 120 store
 * resolutions and 120 failures on battery, and a condition that has failed
 * twenty times running is by definition not fixed by asking again in 30 s.
 * The delay doubles per consecutive transient pass and is CAPPED at
 * [CloudIngestWorker.RETRY_DELAY_CAP_MS] — that cap is the whole difference
 * from `Result.retry()`, whose default exponential chain keeps climbing to
 * five HOURS (the C35 stall). The cap only ever delays a BACKGROUND recovery:
 * [CloudIngestWorker.kickUser] preempts whatever delay is pending, so a
 * user's Collect never waits on it.
 */
fun retryScheduleFor(kind: WorkerResultKind, consecutiveTransient: Int): Long? = when (kind) {
    // Kotlin masks a Long shift distance to its low 6 bits, so `shl 64` would
    // silently mean `shl 0` and hand back the BASE delay on the 65th failure.
    // Coerce first; anything past the cap answers the cap anyway.
    WorkerResultKind.RETRY ->
        (CloudIngestWorker.RETRY_DELAY_MS shl consecutiveTransient.coerceIn(0, 16))
            .coerceAtMost(CloudIngestWorker.RETRY_DELAY_CAP_MS)
    WorkerResultKind.SUCCESS -> null
}

/**
 * Pure decision for what [CloudIngestWorker.doWork] should return.
 *
 * Owner bug A3/C5 fix (T3b): `doWork()` used to unconditionally return
 * `Result.success()` even when a row was deferred this pass because the
 * object store was only transiently unavailable ([deferredTransient] — see
 * `CloudIngestOutcome.Deferred` / T3a), or when a row remains runnable but
 * the pump's own drain loop exited without running it ([waitingRemains] — a
 * pre-existing race where a new row lands between the loop's natural exit
 * check and this final check). `Result.success()` tells WorkManager nothing
 * is left to do, so neither case was ever revisited; [WorkerResultKind.RETRY]
 * re-runs the pump instead — since C35-b via a flat-delay [CloudIngestWorker.kickAfter]
 * re-kick (the request's `NetworkType.CONNECTED` constraint still gates it on
 * connectivity), not via WorkManager's escalating retry backoff.
 *
 * A27 (owner bug backlog, ledger 2026-09-05): [parkedUnbound] is the
 * opposite kind of override — it FORCES [WorkerResultKind.SUCCESS] even
 * when [waitingRemains] is true, because the only reason a row is still
 * WAITING in that case is that no cloud storage is bound at all
 * (`CloudIngestOutcome.Unbound`), a condition WorkManager retry cannot fix.
 * The one thing that ever revives those rows is the user binding a bucket
 * ([com.easemic.app.AppGraph.onBucketBound]), so looping
 * on [WorkerResultKind.RETRY] here would just burn battery/network on a
 * scheduled re-run that is guaranteed to park again. [deferredTransient]
 * still wins over [parkedUnbound] when both are true in the same pass (a
 * real transient failure is worth retrying even if some other row also
 * happened to be unbound).
 */
fun cloudIngestWorkerResult(
    deferredTransient: Boolean,
    waitingRemains: Boolean,
    parkedUnbound: Boolean = false,
): WorkerResultKind = when {
    deferredTransient -> WorkerResultKind.RETRY
    parkedUnbound -> WorkerResultKind.SUCCESS
    waitingRemains -> WorkerResultKind.RETRY
    else -> WorkerResultKind.SUCCESS
}

/**
 * Drains [queue] through [runner] to completion — the transplanted body of
 * [CloudIngestWorker.doWork]'s pump loop, extracted so it is unit-testable
 * with a real [CloudSyncQueue]/[CloudIngestRunner] on plain JVM, no
 * Robolectric/WorkManager test harness required.
 *
 * S3 Task 6 (owner bug 2026-09-04 — "很多漫画已经橙云了，但依然在活动页出现、在跑同步"):
 * [isKnownSynced], when non-null, is the durable sync ledger's `isSynced`
 * lookup. A zombie WAITING/FAILED row for a comic the ledger already proves
 * reached the current bucket is removed WITHOUT ever reaching [runner];
 * `null` (the default) preserves every existing call site unchanged.
 *
 * S6-A (owner bug A7): [maxConcurrent] workers claim rows through the SAME
 * pure [nextRunnableCloudSyncRow] selector, each claim taken under a Mutex
 * against an in-flight set so a row (including a FAILED row the runner has
 * just flipped back to WAITING mid-retry) is never picked twice. A
 * [CloudIngestOutcome.Deferred] result stops all NEW claims (the store is
 * transiently unresolvable — a global condition, not per-row) while rows
 * already running finish on their own. `maxConcurrent = 1` (the default) is
 * exactly the previous sequential drain. Values < 1 are treated as 1; the
 * ceiling is owned by `MaxConcurrentTransfersStore` and the runner's own
 * [com.easemic.app.feature.collect.CloudSyncIngestGate],
 * which AppGraph sizes to that store's MAX.
 *
 * S6-A post-review fix (F1): production feeds the queue ONE row per
 * `upsert()` + `CloudIngestWorker.kick()` (a KEEP-policy worker — re-kicking
 * while one is already running/enqueued is a no-op), so a naive "exit when
 * nothing is claimable" slot loop only ever sees the fan-out width present
 * at the very first claim; slots 2..N would exit immediately and every later
 * row lands on the single surviving slot, sequentially. A slot now exits
 * only when nothing is claimable AND [inFlight][claims] is empty; if
 * something is still running and the pump hasn't stopped on
 * [deferredTransient], the slot parks on [wake] — bumped by every
 * [release] (a sibling finished, so a claim might now succeed) and by a
 * watcher on [CloudSyncQueue.rows] (a fresh row can be upserted mid-burst
 * while every slot is already busy, with no release firing at all) — and
 * retries. [wake] is a `StateFlow`, not a `Channel`: a `Channel` only wakes
 * ONE parked waiter per signal, which can strand other idle slots when more
 * than one is parked at once; a `StateFlow` broadcasts each bump to every
 * parked slot.
 *
 * S6-A post-review fix (F2): [isKnownSynced] now shares the SAME try/catch
 * as [runner]`.run()`. It used to sit outside that inner try, so a throwing
 * ledger lookup (production wires the Room-backed sync ledger, which can
 * throw a SQLite exception) was the one non-cancellation throw able to
 * escape a slot's `launch { }` — `coroutineScope` then cancels every sibling
 * slot's in-flight upload, which `CloudIngestRunner`'s own
 * `CancellationException` handling maps to `CloudSyncIngestFinish.Cancelled`
 * → (since C35-a) `queue.markInterrupted(id)`, throwing away every other
 * in-flight row's progress — and, before C35-a, its queue entry outright.
 * A throwing [isKnownSynced] is now handled exactly like any other
 * slot failure: [CloudSyncQueue.markFailed], row parked with backoff,
 * nothing escapes the slot.
 */
suspend fun runCloudIngestPumpLoop(
    queue: CloudSyncQueue,
    runner: CloudIngestRunner,
    isKnownSynced: (suspend (acquisition: String) -> Boolean)? = null,
    maxConcurrent: Int = 1,
    repairSeriesIndexes: suspend (force: Boolean) -> Boolean = { true },
): WorkerResultKind {
    val slots = maxConcurrent.coerceAtLeast(1)
    val claims = Mutex()
    val inFlight = mutableSetOf<String>()
    fun claimKey(row: CloudSyncQueueItem): String = "${row.id}\u0000${row.chapterIdentity}"
    // Owner bug A3/C5 fix (T3b): resolveStore() failing THIS pass is usually
    // global — stop claiming immediately on the first Deferred outcome and let
    // the caller's retry + WorkManager's own backoff space the next attempt.
    var deferredTransient = false
    // A27 (owner bug backlog, ledger 2026-09-05): parallel to
    // deferredTransient above, but for CloudIngestOutcome.Unbound -- no
    // cloud storage bound at all is just as global a condition (stop
    // claiming new rows immediately), but must map to SUCCESS, not RETRY
    // (see cloudIngestWorkerResult's kdoc). C37-c review (F2b): a
    // Blocked(ByoCloudIoCopy.RECONNECT) -- the persisted OAuth grant was
    // revoked server-side -- sets this same flag, for the same reason: no
    // row can resolve a store, and only a user reconnect fixes it.
    var parkedUnbound = false
    // S6-A post-review fix (F1): bumped by release() (a slot finished a row,
    // whatever the outcome) and by the queue.rows watcher below (new work
    // landed while every slot was busy). A StateFlow, not a Channel: a
    // Channel signal wakes at most ONE parked waiter, but every idle slot
    // must be able to observe the same wakeup.
    val wake = MutableStateFlow(0)

    suspend fun claimNext(): CloudSyncQueueItem? = claims.withLock {
        if (deferredTransient || parkedUnbound) return@withLock null
        val candidates = queue.snapshot().filterNot { claimKey(it) in inFlight }
        nextRunnableCloudSyncRow(candidates)?.also { inFlight += claimKey(it) }
    }

    suspend fun release(row: CloudSyncQueueItem) {
        claims.withLock { inFlight -= claimKey(row) }
        wake.update { it + 1 }
    }

    coroutineScope {
        // S6-A post-review fix (F1): watches for new/changed rows landing
        // while every slot is busy (e.g. a fresh upsert() mid-burst) — a
        // condition release() alone can't signal, since no slot releases
        // until it next claims or exits. Cancelled once the drain workers
        // below finish, by the enclosing coroutineScope unwinding.
        val queueWatcher = launch {
            queue.rows.collect { wake.update { it + 1 } }
        }
        coroutineScope {
            repeat(slots) {
                launch {
                    var lastWake = wake.value
                    while (true) {
                        val row = claimNext()
                        if (row == null) {
                            // S6-A post-review fix (F1): nothing claimable right
                            // now doesn't mean nothing EVER will be — a sibling
                            // slot still running may release a row, or free up
                            // capacity. Only exit once no sibling is in flight
                            // (or the pump has stopped claiming on Deferred).
                            val keepWaiting = claims.withLock {
                                !deferredTransient && !parkedUnbound && inFlight.isNotEmpty()
                            }
                            if (!keepWaiting) break
                            wake.first { it != lastWake }
                            lastWake = wake.value
                            continue
                        }
                        var stopClaiming = false
                        try {
                            // S6-A post-review fix (F2): isKnownSynced now
                            // shares this try/catch with runner.run() — a
                            // throwing ledger lookup is handled like any other
                            // slot failure (markFailed) instead of escaping this
                            // launch { } and cancelling every sibling slot's
                            // in-flight upload (whose CancellationException
                            // handling in CloudIngestRunner would then remove
                            // their still-valid queue rows).
                            try {
                                val synced = isKnownSynced != null && isKnownSynced(row.id)
                                if (synced) {
                                    queue.remove(row.id, chapterIdentity = row.chapterIdentity)
                                } else {
                                    when (val outcome = runner.run(row)) {
                                        is CloudIngestOutcome.Deferred -> {
                                            claims.withLock { deferredTransient = true }
                                            stopClaiming = true
                                        }
                                        is CloudIngestOutcome.Unbound -> {
                                            claims.withLock { parkedUnbound = true }
                                            stopClaiming = true
                                        }
                                        // C37-c review (F2b): a revoked OAuth
                                        // grant is as global as Unbound — no
                                        // other row can resolve a store either,
                                        // and only a reconnect (which kicks the
                                        // pump through AppGraph.onBucketBound)
                                        // revives any of them.
                                        //
                                        // #C63: a locked library is the same
                                        // shape of global condition — every row
                                        // shares the one CloudLibraryGate, so
                                        // none of them can resolve a store
                                        // until the owner unlocks it (which
                                        // kicks the pump through
                                        // AppGraph.onBucketBound, same as
                                        // RECONNECT). Only the unreadable/
                                        // damaged library Blocked messages stay
                                        // per-row, since those are properties
                                        // of the individual row's stored
                                        // locator, not the shared gate.
                                        //
                                        // #C81: a full drive is global too — every
                                        // row writes to the same drive, so running
                                        // past it paid one whole upload per queued
                                        // comic. Retry, a rebind or the next start
                                        // kicks the pump again.
                                        //
                                        // #D16: a refused sign-in is global too —
                                        // every queued comic would ask the same
                                        // drive with the same refused password.
                                        is CloudIngestOutcome.Blocked ->
                                            if (outcome.message == ByoCloudIoCopy.RECONNECT ||
                                                outcome.message == LibraryLockedException.MESSAGE ||
                                                outcome.message == WebDavObjectStore.QUOTA_FULL ||
                                                outcome.message == CloudSignIn.KEY
                                            ) {
                                                claims.withLock { parkedUnbound = true }
                                                stopClaiming = true
                                            }
                                        else -> Unit
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                // runner.run() already maps its own failures onto the
                                // queue, but guard against an unexpected throw anyway:
                                // an uncaught exception here would leave the row stuck
                                // at WAITING and this worker would spin on it forever.
                            queue.markFailed(row.id, error.message ?: "CLOUD_INGEST_FAILED", chapterIdentity = row.chapterIdentity)
                            }
                        } finally {
                            release(row)
                        }
                        if (!stopClaiming) {
                            try {
                                repairSeriesIndexes(false)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // The final pass retains the existing retry schedule for dirty work.
                            }
                        }
                        if (stopClaiming) break
                    }
                }
            }
        }
        queueWatcher.cancel()
    }
    val indexPending = if (parkedUnbound) false else try {
        !repairSeriesIndexes(true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        true
    }
    val waitingRemains = cloudIngestWorkRemains(queue.snapshot())
    return cloudIngestWorkerResult(
        deferredTransient = deferredTransient || indexPending,
        waitingRemains = waitingRemains,
        parkedUnbound = parkedUnbound,
    )
}
