package com.easemic.app.feature.browse

import com.easemic.app.feature.download.SourceThrottlePolicy
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * #C45: the cloud reader's prefetch pool, owned by ONE reader session.
 *
 * The per-window helper this class replaced was rebuilt on every
 * settle: the swipe cancelled the lanes, the cancelled socket reads ran to
 * completion anyway (a thread interrupt does not stop an HttpURLConnection
 * read), their bytes were discarded, and the new window — which only knew
 * landed pages — fetched the same ordinals again. Measured: five times the
 * comic's bytes and a stall every few pages on a phone link.
 *
 * Here a swipe is [focus]: it re-sorts what is still PENDING. Nothing in
 * flight is cancelled, nothing is admitted twice (`landed` and `inFlight`
 * are excluded), and a load that finishes after the focus moved is still
 * delivered — its file is a valid page of this comic. [close] ends the
 * session: lanes stop admitting; a lane blocked inside `load` finishes that
 * load and drops the result. No interrupt is attempted: a blocked bucket
 * read is bounded by the store's socket timeout, not by us.
 *
 * Admission is a RUNWAY RAMP (D1-r, slow-start). Lanes add throughput only
 * while the link is RTT-bound; on a bandwidth-bound link parallel pages share
 * the link and land TOGETHER, so the page under the finger waited 5.8–7.5 s
 * behind five strangers (measured, 6 Mbps / 100 ms). The number of loads in
 * flight is therefore tied to the RUNWAY — the run of consecutive landed pages
 * starting AT the settled page: `inFlight < max(1, min(lanes, runway))`. A
 * cold open loads the settled page ALONE; once it lands the next page goes
 * alone; then two, then four, up to all six — a fast link reaches six in five
 * landings, a slow one keeps the near pages from sharing. Two exemptions keep
 * the reader's own pages ahead of stale prefetch after a jump: the settled
 * page is admitted at once whatever is in flight, and its successor as soon as
 * the settled page has landed. A lane that cannot admit the head parks (the
 * queue is priority-sorted — it must not skip to a farther page) and every
 * landed page sends one wake so a parked lane re-evaluates. Both exemptions
 * bypass the cap, and one spare worker beyond [lanes] carries them, so a
 * jump never waits for a stale load to finish: at most `lanes + 1` loads are
 * in flight, the extra one only through an exemption.
 *
 * [close] NARROWS, but cannot fully close, the window in which an in-flight
 * delivery reaches a callback: the closed flag is re-read immediately before
 * the callback, yet the callback itself runs outside the lock. Task 2's
 * callbacks hop to Main inside an already-cancelled job, which is what
 * actually drops them. A callback that throws is contained here rather than
 * killing its lane for the rest of the session — the reader's scope has no
 * CoroutineExceptionHandler, so an escaping throw would both degrade the pool
 * (6 lanes to 5 to none) and reach the process default handler.
 *
 * #C68: a load that fails is not necessarily shown to the reader. [retryPolicy]
 * classifies the throwable and, for a transient one, this pool schedules its
 * own re-admission after a delay ("cooling") instead of calling [onFailed] —
 * the reader never sees a failure column for a hiccup that clears itself.
 * [onFailed] only fires once the policy gives up (a terminal error, or the
 * retry budget spent). [onRetryScheduled] exists purely so the screen can
 * trace the attempt; it carries no data the pool itself needs.
 */
class CloudReaderPrefetcher(
    private val scope: CoroutineScope,
    private val load: (Int) -> File,
    private val loadSettled: ((Int) -> File)? = null,
    private val onLoaded: suspend (ordinal: Int, file: File) -> Unit,
    private val onFailed: suspend (ordinal: Int, error: Throwable) -> Unit,
    private val lanes: Int = LANES,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retryPolicy: CloudReaderRetryPolicy = CloudReaderRetryPolicy.DEFAULT,
    private val onRetryScheduled: (ordinal: Int, attempt: Int, delayMs: Long) -> Unit = { _, _, _ -> },
) {
    private val lock = Any()
    private val landed = HashSet<Int>()
    private val inFlight = HashSet<Int>()
    private val pending = ArrayDeque<Int>()
    private var settled = 0
    private var closed = false
    // #C68: per-ordinal failed-attempt count and the ordinals currently
    // "cooling" — scheduled to re-admit themselves after a delay, each job
    // cancellable so a retry() or close() during the wait can stop it cold.
    private val failures = HashMap<Int, Int>()
    private val cooling = HashMap<Int, Job>()
    private var lastWanted: Set<Int> = emptySet()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    // One spare worker beyond the lanes: normal admission is bounded by the
    // cap (≤ lanes), so the spare only ever carries the settled-page
    // exemption — a jump while every lane holds an uninterruptible stale load
    // must not wait a page-time for the page under the finger.
    private val workers: List<Job> = List(lanes.coerceAtLeast(1) + 1) { scope.launch(ioDispatcher) { lane() } }

    /** The reader settled on [settled] and wants [wanted]; re-sort the pending queue. */
    fun focus(settled: Int, wanted: Set<Int>) {
        synchronized(lock) {
            if (closed) return
            this.settled = settled
            this.lastWanted = wanted
            // #C45 follow-up (finding 2): the screen keeps files only for the window, so a
            // landed page the window has left is forgotten here too — a revisit reloads it
            // (from the page disk cache, normally) instead of finding it "landed" and blank.
            landed.retainAll(wanted)
            pending.clear()
            CloudCollectionPageWindow.priorityOrdinals(settled = settled, wanted = wanted)
                .forEach { if (it !in landed && it !in inFlight && it !in cooling) pending.addLast(it) }
        }
        wake.trySend(Unit)
    }

    /** The Retry button: load [ordinal] again, ahead of everything pending. */
    fun retry(ordinal: Int) {
        synchronized(lock) {
            if (closed) return
            landed.remove(ordinal)
            failures.remove(ordinal)
            cooling.remove(ordinal)?.cancel()
            if (ordinal !in inFlight) {
                pending.remove(ordinal)
                pending.addFirst(ordinal)
            }
        }
        wake.trySend(Unit)
    }

    /** Leaving the reader. Idempotent. */
    fun close() {
        synchronized(lock) {
            closed = true
            pending.clear()
            cooling.values.forEach { it.cancel() }
            cooling.clear()
        }
        wake.close()
        workers.forEach { it.cancel() }
    }

    private suspend fun lane() {
        while (true) {
            val admission = synchronized(lock) {
                if (closed) return
                val head = pending.firstOrNull()
                if (head == null || !admissible(head)) null
                else {
                    val ordinal = pending.removeFirst()
                    inFlight += ordinal
                    ordinal to (ordinal == settled)
                }
            }
            if (admission == null) {
                wake.receiveCatching().getOrNull() ?: return
                continue
            }
            val (ordinal, isSettled) = admission
            // Cascade the wake-up: one token wakes one lane; if more is pending, wake the next.
            if (synchronized(lock) { pending.isNotEmpty() }) wake.trySend(Unit)
            val result = try {
                Result.success(if (isSettled) loadSettled?.invoke(ordinal) ?: load(ordinal) else load(ordinal))
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    // The lane is going away; do not leave the ordinal pinned
                    // in `inFlight`, or no later focus could ever re-admit it.
                    synchronized(lock) { inFlight -= ordinal }
                    throw t
                }
                Result.failure(t)
            }

            val error = result.exceptionOrNull()
            if (error != null) {
                // #C68: classify before the reader ever finds out — a transient
                // failure is re-queued after a delay instead of reported.
                val scheduled = synchronized(lock) {
                    inFlight -= ordinal
                    if (closed) return
                    val attempt = (failures[ordinal] ?: 0) + 1
                    val delayMs = retryPolicy.delayBeforeRetryMs(attempt, error)
                    if (delayMs == null) {
                        failures -= ordinal
                        null
                    } else {
                        failures[ordinal] = attempt
                        cooling[ordinal] = scope.launch { cool(ordinal, delayMs) }
                        attempt to delayMs
                    }
                }
                if (scheduled != null) {
                    val (attempt, delayMs) = scheduled
                    try {
                        onRetryScheduled(ordinal, attempt, delayMs)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                    }
                } else if (!synchronized(lock) { closed }) {
                    try {
                        onFailed(ordinal, error)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        // Nothing to unwind: a failed load never landed.
                    }
                }
                continue
            }

            val file = result.getOrThrow()
            val delivered = synchronized(lock) {
                inFlight -= ordinal
                if (closed) return
                landed += ordinal
                failures -= ordinal
                true
            }
            if (!delivered) continue
            // A landing may have widened the runway: wake a lane parked on the cap.
            wake.trySend(Unit)
            if (synchronized(lock) { closed }) return
            try {
                onLoaded(ordinal, file)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // A callback that throws must not take a lane down for the whole
                // session; un-land the ordinal so the next focus can try it again.
                synchronized(lock) { landed -= ordinal }
            }
        }
    }

    /** #C68: fires once [delayMs] after a transient failure of [ordinal]; re-admits it if still wanted. */
    private suspend fun cool(ordinal: Int, delayMs: Long) {
        delay(delayMs)
        val readmit = synchronized(lock) {
            cooling -= ordinal
            if (closed || ordinal !in lastWanted || ordinal in landed || ordinal in inFlight) {
                false
            } else {
                // Back at its PRIORITY position, not the head: a lane only
                // ever looks at the head, so a far page put first would be
                // loaded ahead of the pages next to the reader (#C68 review).
                // Anything queued outside the window (a Retry tap, which heads the
                // queue on purpose) stays ahead of it.
                val queued = LinkedHashSet(pending).apply { add(ordinal) }
                val ordered = CloudCollectionPageWindow.priorityOrdinals(settled = settled, wanted = lastWanted)
                    .filter { it in queued }
                val outside = queued.filter { it !in ordered }
                pending.clear()
                pending.addAll(outside)
                pending.addAll(ordered)
                true
            }
        }
        if (readmit) wake.trySend(Unit)
    }

    /** Under [lock]: the head of the queue may go out now. */
    private fun admissible(head: Int): Boolean =
        head == settled ||
            (head == settled + 1 && settled in landed) ||
            inFlight.size < admissionCap()

    /** Under [lock]: `max(1, min(lanes, runway))` — as many in flight as are buffered ahead. */
    private fun admissionCap(): Int = maxOf(1, minOf(lanes, runway()))

    /** Under [lock]: consecutive landed ordinals starting AT the settled page, counted only up to [lanes]. */
    private fun runway(): Int {
        var n = 0
        while (n < lanes && (settled + n) in landed) n++
        return n
    }

    companion object {
        /** Same lane count as the download pump's cloud policy (6). */
        val LANES: Int get() = SourceThrottlePolicy.CLOUD.maxConcurrentFetches
    }
}
