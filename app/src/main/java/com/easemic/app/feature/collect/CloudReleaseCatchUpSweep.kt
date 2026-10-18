package com.easemic.app.feature.collect

import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.shouldReleaseLocalPagesAfterUpload
import kotlinx.coroutines.CancellationException

/**
 * What one [CloudReleaseCatchUpSweep.run] pass did. `released == 0 && failed == 0`
 * stays silent, matching [CloudLocalReleaseSweepSummary]'s convention.
 *
 * [keptWithoutLedger] is reported rather than merely skipped because it is the
 * interesting number for the owner's question "why is this comic still on my
 * phone?": it counts comics that LOOK uploaded (cloud intent, terminal state)
 * but carry no durable proof for the currently bound bucket.
 */
data class CloudReleaseCatchUpSweepSummary(
    val released: Int,
    val failed: Int,
    val keptWithoutLedger: Int,
    val keptByStore: Int,
    /**
     * #C20 audit HIGH-2: comics deferred because an upload is reading their
     * pages right now. LAST and defaulted so the positional construction in
     * `AppGraph.releasePreviouslyUploadedLocalCopies`'s "nothing bound" arm
     * keeps compiling.
     */
    val keptInFlight: Int = 0,
)

/**
 * #C4a (owner 2026-09-10): the ONE-TIME catch-up that runs when the user turns
 * "delete the local copy after uploading" ON and answers yes to the single
 * prompt "also clear the local copies of comics already uploaded?".
 *
 * The owner's precondition is the whole specification: clear a comic only when
 * it is **both on this device AND collected/uploaded**. This differs from
 * [CloudLocalReleaseSweep] (A44) in exactly one way — A44 sweeps only
 * cloud-ONLY jobs, whereas this deliberately includes DUAL-INTENT ones, since
 * a comic the user both downloaded and collected is precisely the case the
 * owner wants reclaimed. That widening is expressed by passing
 * `releaseAfterUpload = true` to the shared predicate rather than by
 * re-deriving any rule here; the predicate still requires cloud intent and a
 * terminal state, so:
 *
 *  - a **download-only** comic (`intentCloud == false`) is never released. It
 *    has no cloud copy — binding a cloud library does not upload it — so
 *    clearing it would destroy the only copy. This holds even if a stale
 *    ledger row exists, because the predicate is consulted BEFORE the ledger.
 *  - an in-flight job (DOWNLOAD/WAIT) is never released, and an already-NONE
 *    job is refused, which is what makes a second run a no-op.
 *
 * Ledger first, always: [knownSyncedToCurrentBucket] is the durable proof of
 * upload, exactly as [CloudHostedLocalRelease.afterConfirmedUpload] insists on
 * its `ledgerWritten`. It must be evidence about the bucket bound RIGHT NOW and
 * false when nothing is bound — a row proving the pages reached bucket A says
 * nothing while bucket B is bound, and acting on it would delete what may be
 * the only copy. Resolving the current fingerprint therefore stays the
 * caller's job (`AppGraph.isKnownSyncedToCurrentBucket`).
 *
 * Never throws: one comic whose delete fails is counted and logged, and the
 * sweep carries on with the rest — the same "one bad job must not strand the
 * batch" rule as [CloudLocalReleaseSweep].
 */
class CloudReleaseCatchUpSweep(
    private val loadJobs: suspend () -> List<DownloadJob>,
    private val knownSyncedToCurrentBucket: suspend (DownloadJob) -> Boolean,
    private val release: suspend (DownloadJob) -> DownloadJob,
    /**
     * #C20 audit HIGH-2: true while an upload is READING this job's pages.
     * Neither release sweep consulted `cloudUploadInFlight` — the C4 §2.1
     * predicate `AppGraph.deleteDownloadedComic` applies — so a release could
     * unlink a tree the pump was still reading lazily and the row then failed
     * `CLOUD_INGEST_PAGES_MISSING_POST_SEAL` with the comic in neither place.
     * Deferring is free: the user can run the catch-up again.
     */
    private val uploadInFlight: suspend (DownloadJob) -> Boolean = { false },
    /** Real problems only (WARN in production): a release that threw. */
    private val log: (String) -> Unit = {},
    /** Routine outcomes (INFO in production): pages reclaimed, or a decline. */
    private val logInfo: (String) -> Unit = log,
) {
    suspend fun run(): CloudReleaseCatchUpSweepSummary {
        var released = 0
        var failed = 0
        var keptWithoutLedger = 0
        var keptByStore = 0
        var keptInFlight = 0
        for (job in loadJobs()) {
            // Cheap local rules first: intent + state, with the #C4a setting ON
            // so dual-intent qualifies. This skips download-only, in-flight and
            // already-released jobs without spending a ledger read on them.
            if (!shouldReleaseLocalPagesAfterUpload(job, releaseAfterUpload = true)) continue
            if (uploadInFlight(job)) {
                keptInFlight++
                logInfo("catch-up kept pages job=${job.id} reason=upload in flight")
                continue
            }
            if (!knownSyncedToCurrentBucket(job)) {
                keptWithoutLedger++
                logInfo("catch-up kept pages job=${job.id} reason=no ledger row for the bound bucket")
                continue
            }
            try {
                val after = release(job)
                if (after.state == DownloadJobState.NONE) {
                    released++
                } else {
                    keptByStore++
                    logInfo("catch-up kept pages job=${job.id} reason=store left state=${after.state}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed++
                log("catch-up FAILED job=${job.id} msg=${error.message ?: error.javaClass.simpleName}")
            }
        }
        if (released > 0 || failed > 0) {
            logInfo(
                "release catch-up: released=$released failed=$failed noLedger=$keptWithoutLedger " +
                    "keptByStore=$keptByStore keptInFlight=$keptInFlight",
            )
        }
        return CloudReleaseCatchUpSweepSummary(
            released = released,
            failed = failed,
            keptWithoutLedger = keptWithoutLedger,
            keptByStore = keptByStore,
            keptInFlight = keptInFlight,
        )
    }
}
