package com.easemic.app.feature.download

import com.easemic.app.storage.CacheBudget
import kotlinx.coroutines.CancellationException

/**
 * #E101: bounded retention over the download staging (READY files + READY rows)
 * held by terminal-failure rows.
 *
 * The leak this exists for: a serialized chapter whose page 1 keeps failing
 * terminates FAILED (JITTER_EXHAUSTED) while its already-downloaded READY
 * prefix stays under `files/download-ready-pages` forever — Dismiss
 * (FAILED->NONE) deletes nothing by design, and nothing else sweeps terminal
 * rows. 13 such jobs held 344 MB on the owner's phone.
 *
 * Boundaries, all of them load-bearing:
 *  - WAIT/DOWNLOAD are active work and SEALED/FINISH are the Library's copy:
 *    those four states are NEVER candidates. Only FAILED/GONE/NONE are.
 *  - NONE is included because a dismissed/cancelled row with no Library
 *    association is invisible everywhere yet still holds bytes.
 *  - Eviction keeps the row and its state — a FAILED row stays FAILED and
 *    [RoomDownloadJobStore.retry] still works; it just re-downloads from
 *    scratch. This is budget hygiene, NOT an uncollect.
 *  - Fail closed: a job list that cannot be read ([loadJobs] returning null, or
 *    throwing) evicts nothing at all.
 *  - A declined [evict] keeps its bytes counted, so a row that refuses to go
 *    may keep the total above target — the sweep evicted all it was allowed to
 *    and then stopped asking.
 */
class FailedDownloadStagingSweep(
    private val loadJobs: suspend () -> List<DownloadJob>?,
    private val stagingBytes: suspend (DownloadJob) -> Long,
    private val evict: suspend (DownloadJob) -> Boolean,
    private val budgetBytes: Long = FAILED_STAGING_BUDGET_BYTES,
    private val log: (String) -> Unit = {},
) {
    /**
     * Number of jobs whose staging was evicted. Everything below is fail
     * closed: the count only ever reflects evictions that actually happened,
     * and nothing here propagates an I/O problem to the caller.
     */
    suspend fun run(): Int {
        val jobs = try {
            loadJobs()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return 0
        val candidates = jobs
            .filter { it.state in EVICTABLE_STATES }
            .map { job -> job to bytesOf(job) }
        var total = candidates.sumOf { it.second }
        if (total <= budgetBytes) return 0
        // Same low-water rule as CacheBudget's sweeps: 10% headroom, so one
        // eviction run buys room instead of evicting exactly the overflow.
        val target = budgetBytes - budgetBytes / CacheBudget.EVICTION_HEADROOM_DIVISOR
        var evicted = 0
        for ((job, bytes) in candidates.sortedBy { it.first.updatedAtMillis }) {
            if (total <= target) break
            val removed = try {
                evict(job)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Unknown outcome — the bytes stay counted, same as a decline.
                log("failed staging sweep: evict threw for job=${job.id}")
                continue
            }
            if (removed) {
                total -= bytes
                evicted++
            } else {
                log("failed staging sweep: evict declined job=${job.id}")
            }
        }
        if (evicted > 0) {
            log("failed staging sweep: evicted=$evicted of ${candidates.size} candidate(s), $total of ${candidates.sumOf { it.second }} bytes left")
        }
        return evicted
    }

    /** One job's staging bytes; an unreadable directory counts as none held. */
    private suspend fun bytesOf(job: DownloadJob): Long = try {
        stagingBytes(job)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        log("failed staging sweep: sizing threw for job=${job.id}")
        0L
    }

    companion object {
        /**
         * A quarter of [CacheBudget]'s default 512 MB cache budget. The
         * measured leak this bounds was 344 MB across 13 failed rows, so a
         * quarter is generous for legitimate "retry these later" staging while
         * still stopping unbounded growth.
         */
        const val FAILED_STAGING_BUDGET_BYTES: Long = 128L * 1024 * 1024

        /** FAILED/GONE/NONE only — see the class KDoc for why each is in. */
        private val EVICTABLE_STATES: Set<DownloadJobState> = setOf(
            DownloadJobState.FAILED,
            DownloadJobState.GONE,
            DownloadJobState.NONE,
        )
    }
}
