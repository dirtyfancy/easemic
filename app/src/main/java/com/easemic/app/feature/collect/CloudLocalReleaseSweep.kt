package com.easemic.app.feature.collect

import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.shouldReleaseLocalPagesAfterUpload
import kotlinx.coroutines.CancellationException

/** What one [CloudLocalReleaseSweep.run] pass did. Logged by the caller; `released == 0 && failed == 0` stays silent. */
data class CloudLocalReleaseSweepSummary(
    val released: Int,
    val failed: Int,
)

/**
 * A44 (owner: "上云之后为什么不删本地"): sweeps the device pages of every
 * cloud-only job the durable sync ledger already proves reached the CURRENTLY
 * bound bucket.
 *
 * Why a sweep is needed at all: [com.easemic.app.feature.download.RoomDownloadJobStore.releaseLocalPagesAfterCloud]
 * works correctly, but it only ever ran INLINE from `AppGraph`'s `onSynced`
 * hook. An upload that completed before that inline release existed, or one
 * whose release never ran (process death, crash, an error path), was never
 * revisited — [com.easemic.app.AppGraph.syncLedgerWithMirror]
 * backfills the ledger but releases nothing. So the leak was permanent and
 * grew: measured on the owner's phone, 13 SEALED cloud-only jobs and 18
 * ledger rows still sitting on 344 MB of `download-ready-pages` across 9
 * galleries.
 *
 * Every "should these files go" rule is DELEGATED, not re-derived here:
 *  - [shouldReleaseLocalPagesAfterUpload] decides intent + state (a
 *    dual-intent job keeps its pages — the user explicitly asked for a local
 *    copy, so releasing it is data loss; only SEALED/FINISH cloud-only
 *    qualifies), and
 *  - [release] is `releaseLocalPagesAfterCloud` itself, which re-reads
 *    `intentLocal` one last time before deleting and owns the READY-tree /
 *    Library-availability / catalog-tree ordering.
 *
 * [knownSyncedToCurrentBucket] must be evidence about the bucket bound RIGHT
 * NOW, and must be false when nothing is bound. A ledger row proving the pages
 * exist in bucket A says nothing about bucket B; acting on it while B is bound
 * would delete what may be the only copy. That check therefore stays the
 * caller's (`AppGraph.isKnownSyncedToCurrentBucket`, which resolves the
 * fingerprint per call) rather than being cached here.
 *
 * Idempotent: a released job lands on NONE, which
 * [shouldReleaseLocalPagesAfterUpload] refuses, so later passes skip it. One
 * job whose delete throws is counted and logged, never rethrown — the same
 * "one bad job must not strand the rest" rule as
 * [com.easemic.app.AppGraph.repairCloudHandoffs].
 */
class CloudLocalReleaseSweep(
    private val loadJobs: suspend () -> List<DownloadJob>,
    private val knownSyncedToCurrentBucket: suspend (DownloadJob) -> Boolean,
    private val release: suspend (DownloadJob) -> DownloadJob,
    /**
     * #C20 audit HIGH-2: true while an upload is READING this job's pages.
     * Neither release sweep consulted `cloudUploadInFlight` — the C4 §2.1
     * predicate `AppGraph.deleteDownloadedComic` applies — so a release could
     * unlink a tree the pump was still reading lazily and the row then failed
     * `CLOUD_INGEST_PAGES_MISSING_POST_SEAL` with the comic in neither place.
     * Deferring is free: the sweep runs again on the next foreground.
     */
    private val uploadInFlight: suspend (DownloadJob) -> Boolean = { false },
    private val releaseAfterUploadEnabled: () -> Boolean = { false },
    /** Real problems only (WARN in production): a release that threw. */
    private val log: (String) -> Unit = {},
    /** Routine outcome (INFO in production): pages actually reclaimed. */
    private val logInfo: (String) -> Unit = log,
) {
    suspend fun run(): CloudLocalReleaseSweepSummary {
        var released = 0
        var failed = 0
        for (job in loadJobs()) {
            // Cheap local rules first: this skips dual-intent, local-only and
            // already-NONE jobs without spending a ledger read on them.
            if (!shouldReleaseLocalPagesAfterUpload(job, releaseAfterUploadEnabled())) continue
            if (uploadInFlight(job)) {
                logInfo("release sweep kept pages job=${job.id} reason=upload in flight")
                continue
            }
            if (!knownSyncedToCurrentBucket(job)) continue
            try {
                val after = release(job)
                if (after.state == DownloadJobState.NONE) {
                    released++
                } else {
                    // The store declined — e.g. its own last-moment
                    // intentLocal re-check saw a Download tap land in this
                    // window. That is the store working as designed.
                    logInfo("release sweep kept pages job=${job.id} reason=store left state=${after.state}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed++
                log("release sweep FAILED job=${job.id} msg=${error.message ?: error.javaClass.simpleName}")
            }
        }
        if (released > 0 || failed > 0) {
            logInfo("release sweep: released=$released failed=$failed")
        }
        return CloudLocalReleaseSweepSummary(released = released, failed = failed)
    }
}
