package com.easemic.app.feature.collect

import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.downloadJobIdentityForCloudAcquisition
import com.easemic.app.feature.download.shouldReleaseLocalPagesAfterUpload
import com.easemic.app.importing.opds.OpdsCatalogClient
import com.easemic.app.sync.redactAcquisition
import com.easemic.app.sync.redactIdentity
import kotlinx.coroutines.CancellationException

/** What happened to the device pages after one confirmed upload (S4 Task 3). Logged verbatim by the caller. */
sealed interface CloudHostedReleaseOutcome {
    /** The durable ledger row was NOT present on this call — pages stay. Ledger first, always. */
    data object LedgerNotWritten : CloudHostedReleaseOutcome

    /**
     * #C52 round 3 B1: this outcome is logged verbatim by its caller
     * (`AppGraph`'s `outcome=$releaseOutcome`); the default data class
     * `toString()` would print [acquisition] raw, and an acquisition IS a
     * whole OPDS URL that can carry a catalog's auth key in its path.
     */
    data class NoJob(val acquisition: String) : CloudHostedReleaseOutcome {
        override fun toString(): String = "NoJob(acquisition=${redactAcquisition(acquisition)})"
    }
    data class Kept(val jobId: String, val reason: String) : CloudHostedReleaseOutcome {
        override fun toString(): String = "Kept(jobId=$jobId, reason=$reason)"
    }
    data class Released(val jobId: String) : CloudHostedReleaseOutcome {
        override fun toString(): String = "Released(jobId=$jobId)"
    }

    /**
     * #C52 round 3 B1: [message] comes from a caught [Exception.message],
     * which can itself embed an acquisition URL (a `MalformedURLException`
     * always does) — so it goes through [OpdsCatalogClient.redactUrl], not
     * verbatim, the same way `DownloadWorkerLoop`'s MANIFEST_ERROR detail is
     * redacted.
     */
    data class Failed(val jobId: String, val message: String) : CloudHostedReleaseOutcome {
        override fun toString(): String = "Failed(jobId=$jobId, message=${OpdsCatalogClient.redactUrl(message)})"
    }
}

/**
 * S4 Task 3 ("remote hosting", owner bug C2): the release step behind
 * `AppGraph.cloudIngestRunner`'s `onSynced`. Runs AFTER the mirror upsert
 * and the S3-T1 ledger write, and only when the caller proves that row is
 * present ([afterConfirmedUpload]'s `ledgerWritten`): a crash between the
 * ledger write and this call leaves pages on disk (harmless, the ledger
 * prevents a re-upload); the reverse — pages gone, no ledger row — can
 * never happen because this refuses to run without the row.
 *
 * The decision of WHETHER to release ([shouldReleaseLocalPagesAfterUpload])
 * and the acquisition→identity resolver ([downloadJobIdentityForCloudAcquisition])
 * are pure functions in `feature/download/CloudHostedReleaseDecision.kt`
 * (Task 1); this class only sequences ledger-check → identity → job lookup
 * → decision → release.
 *
 * Never throws: `CloudIngestRunner.run` wraps `onSynced` in its outer
 * `catch (error: Exception)`, which would flip an already-synced row to
 * FAILED; a local delete failure is reported as [CloudHostedReleaseOutcome.Failed]
 * and logged through [log] instead, so it is loud but not fatal.
 */
class CloudHostedLocalRelease(
    private val jobFor: suspend (GalleryIdentityV1) -> DownloadJob?,
    private val release: suspend (DownloadJob, expectedBucketFingerprint: String?) -> DownloadJob,
    private val identityFor: (String, String?, String?) -> GalleryIdentityV1? =
        ::downloadJobIdentityForCloudAcquisition,
    /** Real problems only (WARN in production): a missing ledger row, a failed delete. */
    private val log: (String) -> Unit = {},
    /**
     * Routine outcomes (INFO in production): pages kept because the job is
     * dual-intent, or no job behind an acquisition that never backed one.
     * Both used to go through [log] at WARN, which trains the reader to
     * ignore the tag (P4, owner principle "INFO for routine outcomes, WARN
     * only for real problems"). Defaults to [log] so a caller that supplies
     * only one sink keeps its current behaviour.
     */
    private val logInfo: (String) -> Unit = log,
    /**
     * #C4a: the user's "keep only the cloud copy" setting
     * (`CloudReleaseAfterUploadStore.read`), read per call so a mid-session
     * toggle takes effect on the next upload without rebuilding this object.
     * A lambda rather than the store itself so this stays JVM-testable and
     * `feature/collect` keeps not depending on `feature/settings`.
     *
     * Defaults to OFF, which is the pre-#C4a behaviour: it only widens the
     * release to dual-intent jobs — see [shouldReleaseLocalPagesAfterUpload]'s
     * truth table. Cloud intent and a terminal state are still required, and
     * the ledger row is still checked first, under either value.
     */
    private val releaseAfterUploadEnabled: () -> Boolean = { false },
) {
    suspend fun afterConfirmedUpload(
        sourceRowAcquisition: String,
        ledgerWritten: Boolean,
        providerNamespace: String? = null,
        canonicalItemId: String? = null,
        confirmedBucketFingerprint: String? = null,
    ): CloudHostedReleaseOutcome {
        if (!ledgerWritten) {
            log("release skipped (ledger not written) acquisition=${redactAcquisition(sourceRowAcquisition)}")
            return CloudHostedReleaseOutcome.LedgerNotWritten
        }
        val identity = identityFor(sourceRowAcquisition, providerNamespace, canonicalItemId)
        val job = identity?.let { jobFor(it) }
        if (job == null) {
            logInfo(
                "release skipped (no download job) acquisition=${redactAcquisition(sourceRowAcquisition)} " +
                    "identity=${identity?.let { redactIdentity(it) } ?: "none"}",
            )
            return CloudHostedReleaseOutcome.NoJob(sourceRowAcquisition)
        }
        val releaseAfterUpload = releaseAfterUploadEnabled()
        if (!shouldReleaseLocalPagesAfterUpload(job, releaseAfterUpload)) {
            val reason = if (job.intentLocal && !releaseAfterUpload) {
                "dual intent (local download kept)"
            } else {
                "state=${job.state} intentCloud=${job.intentCloud}"
            }
            logInfo("release kept pages job=${job.id} reason=$reason")
            return CloudHostedReleaseOutcome.Kept(job.id, reason)
        }
        return try {
            val after = release(job, confirmedBucketFingerprint)
            if (after.state == DownloadJobState.NONE) {
                CloudHostedReleaseOutcome.Released(job.id)
            } else {
                // The store declined (e.g. its own last-moment intentLocal
                // re-check saw a Download tap land in this window) — that is
                // the store working as designed, not a fault here.
                logInfo("release kept pages job=${job.id} reason=store left state=${after.state}")
                CloudHostedReleaseOutcome.Kept(job.id, "store left state=${after.state}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            log("release FAILED job=${job.id} msg=$message")
            CloudHostedReleaseOutcome.Failed(job.id, message)
        }
    }
}
