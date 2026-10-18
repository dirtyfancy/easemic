package com.easemic.app.feature.download

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One page a download lane is currently transferring. */
data class InFlightDownloadPage(
    val jobId: String,
    val ordinal: Int,
    val startedAtMs: Long,
)

/**
 * E15 Task 3a: process-local record of the page attempts currently in flight
 * across every download lane (E-Hentai, nhentai, OPDS, cloud — all funnel
 * through one [DownloadWorkerLoop.fetchOrdinalIntoReady]), observable so the
 * Activity row and the foreground notice can say which galleries are moving
 * slowly instead of freezing on a stale page count.
 *
 * Slowness is TIME ONLY: a page is "downloading slowly" once it has been in
 * flight for [SLOW_PAGE_AFTER_MS]. No rate is checked here — a page that
 * actually stalls below its source's rolling floor is cut by the transfer's
 * own floor watchdog, so this state only ever reports elapsed time.
 *
 * Exactly one [end] per [start]: the worker loop brackets the paced transfer
 * itself, after the namespace permit and the spacing slot, so a page that is
 * only queued for a permit is not recorded. Success, GONE, a terminal failure,
 * cancellation (including a worker stop) and any other exit of that transfer
 * each end the entry exactly once. Callers never fetch the same (jobId, ordinal)
 * on two lanes at once (the pass cursor hands out distinct ordinals), so keying
 * by the pair is unambiguous; a duplicate start for a pair already recorded
 * overwrites it (the freshest start wins) and its end still leaves the map
 * empty.
 */
class DownloadProgressState(
    /**
     * Injected so tests can cross [SLOW_PAGE_AFTER_MS] without waiting.
     * The default is monotonic ([System.nanoTime]), so a wall-clock step
     * cannot mark a page slow or hide one.
     */
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    companion object {
        /**
         * A page in flight at least this long is reported as "downloading
         * slowly". Long by design: a large animated page on a slow link
         * legitimately runs for many minutes (the E15 root cause), so 30 s is
         * the point where the row stops promising immediacy, not a failure
         * verdict.
         */
        const val SLOW_PAGE_AFTER_MS: Long = 30_000L
    }

    private val pages = ConcurrentHashMap<Pair<String, Int>, InFlightDownloadPage>()

    /**
     * Serializes [publish]. Concurrent lane starts used to read the map and
     * then assign [inFlight] out of order, so one assignment could drop a
     * page another lane had already recorded.
     */
    private val publishLock = Any()

    private val _inFlight = MutableStateFlow<Map<String, List<InFlightDownloadPage>>>(emptyMap())

    /**
     * Current in-flight pages keyed by jobId. Emits only on start/end — a page
     * turning slow is a pure function of time, so observers re-read
     * [slowPageCounts] on their own tick while this map is non-empty.
     */
    val inFlight: StateFlow<Map<String, List<InFlightDownloadPage>>> = _inFlight.asStateFlow()

    /** Records a page attempt's start at the injected clock's current time. */
    fun start(jobId: String, ordinal: Int) {
        synchronized(publishLock) {
            pages[jobId to ordinal] = InFlightDownloadPage(jobId, ordinal, clockMs())
            publish()
        }
    }

    /**
     * Ends the page attempt — the one clear for success, GONE, terminal
     * failure, cancellation and every other exit (the caller's finally).
     * No-op when nothing is recorded, so an extra end can never throw.
     */
    fun end(jobId: String, ordinal: Int) {
        synchronized(publishLock) {
            pages.remove(jobId to ordinal)
            publish()
        }
    }

    /** True while at least one page is in flight anywhere. */
    fun anyInFlight(): Boolean = pages.isNotEmpty()

    /**
     * Per-job count of in-flight pages in flight for at least [SLOW_PAGE_AFTER_MS]
     * at [nowMs]; jobs with none are omitted.
     */
    fun slowPageCounts(nowMs: Long = clockMs()): Map<String, Int> =
        _inFlight.value
            .mapValues { (_, inFlightPages) ->
                inFlightPages.count { nowMs - it.startedAtMs >= SLOW_PAGE_AFTER_MS }
            }
            .filterValues { it > 0 }

    private fun publish() {
        _inFlight.value = pages.values.groupBy { it.jobId }
    }
}
