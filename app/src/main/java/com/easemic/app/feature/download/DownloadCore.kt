package com.easemic.app.feature.download

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process download domain core (Task 2–4, 8).
 *
 * Enqueue is a transactional upsert keyed by [GalleryIdentityV1] plus
 * [DownloadJob.chapterIdentity]: duplicate calls return the existing job and
 * never create a Library association.
 * [expectedPageCount] pins at enqueue or first manifest; FINISH requires every
 * expected ordinal READY and a Library association in the same commit
 * (location + download revision via [DownloadLibraryCommitter]).
 *
 * Cancel / FAILED / delete transitions follow spec §§4.2–4.4, 4.7. This
 * in-memory core remains the JVM unit-test oracle; durable persistence is
 * [RoomDownloadJobStore] (Task 5). No Collect / R2 / entitlement side effects.
 */
class DownloadCore(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val libraryCommitter: DownloadLibraryCommitter = InMemoryDownloadLibraryCommitter(),
) {
    private val lock = Any()
    private val jobs = mutableListOf<DownloadJob>()
    private val readyByJob = ConcurrentHashMap<String, MutableSet<Int>>()

    /**
     * Test hook: override [clock] without constructing a new core. Used to pin
     * distinct enqueue timestamps for FIFO assertions.
     */
    @Volatile
    var clockMs: Long? = null

    /**
     * Test hook: when true, [tryFinish] throws before committing FINISH + Library,
     * leaving the job non-FINISH with no association.
     */
    @Volatile
    var crashBeforeFinishCommit: Boolean = false

    private fun nowMillis(): Long = clockMs ?: clock()

    /**
     * Upsert enqueue → [DownloadJobState.WAIT]. Same identity returns the
     * existing job (no second record, no Library association). A [NONE] job
     * (post-cancel or successful delete) is revived to WAIT; revision bumps
     * only when no READY pages remain (delete-to-NONE path).
     */
    fun enqueue(
        identity: GalleryIdentityV1,
        expectedPageCount: Int? = null,
        intentLocal: Boolean = false,
        intentCloud: Boolean = false,
        chapterIdentity: String = GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID,
    ): DownloadJob = synchronized(lock) {
        val index = jobs.indexOfFirst {
            it.identity == identity && it.chapterIdentity == chapterIdentity
        }
        if (index >= 0) {
            val existing = jobs[index]
            // A36 review N1: mirrors [DownloadDao.upsertEnqueueJob]. A SEALED
            // row with nothing on the shelf is a fetch whose pages the user
            // cannot reach, so a Download tap must actually re-run it — merging
            // intents would be a no-op that also stamps intentLocal on a
            // cloud-only seal, blocking its release and its discard forever.
            val revivesSealed = existing.state == DownloadJobState.SEALED &&
                intentLocal &&
                libraryCommitter.onDeviceAssociationCount(identity) == 0
            if (existing.state != DownloadJobState.NONE && !revivesSealed) {
                return existing
            }
            // A36 review O1: mirrors [RoomDownloadJobStore.adoptSealedLocalPages].
            // The pages are usually still here (a cloud-only seal keeps its
            // device copy until the upload is released), so commit those
            // rather than making the pump fetch them again — which would also
            // hand a pending cloud handoff a fresh fetch instead of the bytes
            // it was queued for. The job stays terminal.
            if (revivesSealed) {
                val onDevice = readyByJob[existing.id].orEmpty()
                if (onDevice.isNotEmpty()) {
                    val pinned = existing.expectedPageCount
                    val complete = pinned == null || onDevice.size >= pinned
                    libraryCommitter.commitOnFinish(
                        identity = identity,
                        downloadLocationUri = defaultDownloadLocationUri(
                            identity,
                            existing.downloadRevision,
                        ),
                        downloadRevision = existing.downloadRevision,
                    )
                    val adopted = existing.copy(
                        state = if (complete) {
                            DownloadJobState.FINISH
                        } else {
                            DownloadJobState.SEALED
                        },
                        intentLocal = true,
                        intentCloud = existing.intentCloud || intentCloud,
                        sealedTruncatedAt = if (complete) null else onDevice.size,
                        failureClass = if (complete) null else existing.failureClass,
                        failureDetail = if (complete) null else existing.failureDetail,
                    )
                    jobs[index] = adopted
                    return adopted
                }
            }
            val ready = readyByJob[existing.id].orEmpty()
            val revision = if (ready.isEmpty()) {
                existing.downloadRevision + 1
            } else {
                existing.downloadRevision
            }
            val revived = existing.copy(
                state = DownloadJobState.WAIT,
                expectedPageCount = expectedPageCount ?: existing.expectedPageCount,
                downloadRevision = revision,
                intentLocal = existing.intentLocal || intentLocal,
                intentCloud = existing.intentCloud || intentCloud,
                // P1 + the pump's queue filter + O2's stale truncation point
                // — see the DAO's revive branch.
                failureClass = null,
                failureDetail = null,
                resumeNotBeforeMs = 0L,
                sealedTruncatedAt = null,
                // Mirrors the DAO revive branch, which stamps updatedAtMillis.
                updatedAtMillis = nowMillis(),
                // C9 audit M1: mirrors the DAO — a revived row starts undismissed.
                dismissedAtMillis = 0L,
            )
            jobs[index] = revived
            return revived
        }
        val job = DownloadJob(
            id = UUID.randomUUID().toString(),
            identity = identity,
            state = DownloadJobState.WAIT,
            expectedPageCount = expectedPageCount,
            downloadRevision = 1,
            enqueuedAtMillis = nowMillis(),
            updatedAtMillis = nowMillis(),
            intentLocal = intentLocal,
            intentCloud = intentCloud,
            chapterIdentity = chapterIdentity,
        )
        jobs += job
        job
    }

    fun jobsFor(identity: GalleryIdentityV1): List<DownloadJob> = synchronized(lock) {
        jobs.filter { it.identity == identity }
    }

    /** All persisted jobs regardless of state. Mirrors [RoomDownloadJobStore.loadAllJobs]. */
    fun allJobs(): List<DownloadJob> = synchronized(lock) { jobs.toList() }

    fun requireJob(jobId: String): DownloadJob = synchronized(lock) {
        jobs.first { it.id == jobId }
    }

    fun isInConsumptionQueue(jobId: String): Boolean = synchronized(lock) {
        jobs.first { it.id == jobId }.state.isInConsumptionQueue()
    }

    fun consumptionQueue(): List<DownloadJob> = synchronized(lock) {
        filterConsumptionQueueFifo(jobs, nowMillis())
    }

    fun libraryAssociationCount(identity: GalleryIdentityV1): Int = synchronized(lock) {
        libraryCommitter.onDeviceAssociationCount(identity)
    }

    fun libraryAssociation(identity: GalleryIdentityV1): DownloadLibraryAssociation? =
        synchronized(lock) {
            libraryCommitter.associationFor(identity)
        }

    fun markPageReady(jobId: String, ordinal: Int) {
        readyByJob.getOrPut(jobId) { ConcurrentHashMap.newKeySet() }.add(ordinal)
    }

    fun readyOrdinals(jobId: String): Set<Int> =
        readyByJob[jobId]?.toSet().orEmpty()

    /** WAIT → DOWNLOAD when the gallery becomes the active consumer. */
    fun markDownloading(jobId: String): DownloadJob = synchronized(lock) {
        updateJob(jobId) { job ->
            require(job.state == DownloadJobState.WAIT || job.state == DownloadJobState.DOWNLOAD) {
                "markDownloading requires WAIT or DOWNLOAD, was ${job.state}"
            }
            job.copy(state = DownloadJobState.DOWNLOAD)
        }
    }

    /**
     * Blocking failure exhausted retry/backoff (spec §4.3 FAILED entry). Leaves
     * the consumption queue; READY pages remain for Detail Retry.
     */
    /**
     * Blocking failure exhausted retry/backoff (spec §4.3 FAILED entry). Leaves
     * the consumption queue; READY pages remain for Detail Retry.
     * [failureClass]/[failureDetail] optionally stamp the reason in the same
     * transition (Task 8, owner bug #6) — when omitted (the pre-existing
     * 1-arg call sites), any previously recorded reason is left untouched.
     */
    fun markFailed(
        jobId: String,
        failureClass: String? = null,
        failureDetail: String? = null,
    ): DownloadJob = synchronized(lock) {
        updateJob(jobId) { job ->
            require(job.state == DownloadJobState.WAIT || job.state == DownloadJobState.DOWNLOAD) {
                "markFailed requires WAIT or DOWNLOAD, was ${job.state}"
            }
            // S3 Task 9 fifth defect (owner bug backlog): every markFailed
            // call site today already passes an explicit reason — this is a
            // defensive backstop so a FUTURE call site that forgets to pass
            // one can never again leave failureClass/failureDetail null on a
            // hard-fail (mirrors RoomDownloadJobStore.markFailed). SEND_BACK
            // item 5: the fallback detail text must fire ONLY when the class
            // is ALSO unresolved — otherwise a caller that correctly passes
            // a failureClass but no detail (e.g. JITTER_EXHAUSTED, which is
            // self-explanatory) got stuck with the accusatory "called with
            // no reason" text even though a reason WAS supplied.
            val resolvedClass = failureClass ?: job.failureClass
            val resolvedDetail = failureDetail ?: job.failureDetail ?: if (resolvedClass == null) {
                "no failure reason recorded"
            } else {
                null
            }
            job.copy(
                state = DownloadJobState.FAILED,
                failureClass = resolvedClass ?: "UNSPECIFIED",
                failureDetail = resolvedDetail,
            )
        }
    }

    /**
     * WAIT/DOWNLOAD → SEALED: fetch complete (or truncation wind-down declared
     * complete). [truncatedAt] records pages actually fetched when sealing via
     * truncation; null means a full, untruncated seal. Mirrors
     * [RoomDownloadJobStore.markSealed] so [InMemoryDownloadWorkerHost] stays
     * behaviorally faithful for JVM unit tests.
     *
     * P1 (polish backlog): [failureClass]/[failureDetail] REPLACE whatever
     * reason the row carried — they are the reason this job sealed, not an
     * addition to the running record. Both null (a full completion) therefore
     * CLEARS it: a seal reached by fetching every page is a success and must
     * not keep the last transient JITTER. A truncation wind-down passes the
     * verdict that caused it (today always [FetchFailureClass.GONE] plus the
     * 404 detail) so the row stops claiming "transient, will retry" while
     * being terminally sealed.
     */
    fun markSealed(
        jobId: String,
        truncatedAt: Int? = null,
        failureClass: String? = null,
        failureDetail: String? = null,
    ): DownloadJob = synchronized(lock) {
        updateJob(jobId) { job ->
            require(job.state == DownloadJobState.WAIT || job.state == DownloadJobState.DOWNLOAD) {
                "markSealed requires WAIT or DOWNLOAD, was ${job.state}"
            }
            job.copy(
                state = DownloadJobState.SEALED,
                sealedTruncatedAt = truncatedAt,
                failureClass = failureClass,
                failureDetail = failureDetail,
            )
        }
    }

    /**
     * A36: the GONE truncation wind-down of a job that carries
     * [DownloadJob.intentLocal] and has at least one READY page. Same SEALED
     * transition as [markSealed] — state, [DownloadJob.sealedTruncatedAt] and
     * the recorded verdict are identical, the job state machine is untouched —
     * plus the Library association the k fetched pages have earned, so they
     * reach the shelf instead of sitting unreadable in the download tree.
     * Mirrors [RoomDownloadJobStore.sealTruncatedWithLocalPages] so
     * [InMemoryDownloadWorkerHost] stays a faithful JVM oracle.
     */
    fun sealTruncatedWithLocalPages(
        jobId: String,
        truncatedAt: Int,
        failureClass: String?,
        failureDetail: String?,
        downloadLocationUri: String? = null,
    ): DownloadJob = synchronized(lock) {
        val index = jobs.indexOfFirst { it.id == jobId }
        require(index >= 0) { "unknown job $jobId" }
        val job = jobs[index]
        require(job.state == DownloadJobState.WAIT || job.state == DownloadJobState.DOWNLOAD) {
            "sealTruncatedWithLocalPages requires WAIT or DOWNLOAD, was ${job.state}"
        }
        val location = downloadLocationUri
            ?: defaultDownloadLocationUri(job.identity, job.downloadRevision)
        libraryCommitter.commitOnFinish(
            identity = job.identity,
            downloadLocationUri = location,
            downloadRevision = job.downloadRevision,
        )
        val sealed = job.copy(
            state = DownloadJobState.SEALED,
            sealedTruncatedAt = truncatedAt,
            failureClass = failureClass,
            failureDetail = failureDetail,
        )
        jobs[index] = sealed
        sealed
    }

    /**
     * WAIT/DOWNLOAD → GONE: source confirmed gone with zero pages fetched.
     * Mirrors [RoomDownloadJobStore.markGone], including the P1 rule that
     * [failureClass]/[failureDetail] REPLACE the row's recorded reason.
     */
    fun markGone(
        jobId: String,
        failureClass: String? = null,
        failureDetail: String? = null,
    ): DownloadJob = synchronized(lock) {
        updateJob(jobId) { job ->
            require(job.state == DownloadJobState.WAIT || job.state == DownloadJobState.DOWNLOAD) {
                "markGone requires WAIT or DOWNLOAD, was ${job.state}"
            }
            job.copy(
                state = DownloadJobState.GONE,
                failureClass = failureClass,
                failureDetail = failureDetail,
            )
        }
    }

    /**
     * Records fetch-failure metadata and the QUOTA/ban idle window without
     * changing [DownloadJob.state] — routing/state transition is the caller's
     * decision, this only persists the classification for UI + retry gating.
     * Mirrors [RoomDownloadJobStore.recordFailure]. Guarded to WAIT/DOWNLOAD
     * like [markSealed]/[markGone] — a terminal job (FINISH/SEALED/GONE/FAILED)
     * must not be re-stamped with failure metadata after the fact.
     */
    fun recordFailure(
        jobId: String,
        failureClass: String,
        detail: String?,
        resumeNotBeforeMs: Long,
    ): DownloadJob = synchronized(lock) {
        updateJob(jobId) { job ->
            require(job.state == DownloadJobState.WAIT || job.state == DownloadJobState.DOWNLOAD) {
                "recordFailure requires WAIT or DOWNLOAD, was ${job.state}"
            }
            job.copy(
                failureClass = failureClass,
                failureDetail = detail,
                resumeNotBeforeMs = resumeNotBeforeMs,
            )
        }
    }

    /**
     * Detail/Activity Retry: same job id, back to WAIT, keep verified READY
     * pages and pin; zero the auto-revive counters and the resume window
     * (mirrors [RoomDownloadJobStore.retry]).
     */
    fun retry(jobId: String): DownloadJob = synchronized(lock) {
        updateJob(jobId) { job ->
            require(job.state == DownloadJobState.FAILED) {
                "retry requires FAILED, was ${job.state}"
            }
            job.copy(
                state = DownloadJobState.WAIT,
                resumeNotBeforeMs = 0L,
                autoRetryAttempts = 0,
                lastAutoRetryAtMs = 0L,
            )
        }
    }

    /**
     * Cancel from WAIT or DOWNLOAD → honest [NONE], leave queue, keep READY.
     * When [readyPageStore] is provided, orphan `.part` temps for this job's
     * revision are removed (spec §4.7).
     */
    fun cancel(jobId: String, readyPageStore: ReadyPageStore? = null): DownloadJob =
        synchronized(lock) {
            val index = jobs.indexOfFirst { it.id == jobId }
            require(index >= 0) { "unknown job $jobId" }
            val job = jobs[index]
            require(job.state == DownloadJobState.WAIT || job.state == DownloadJobState.DOWNLOAD) {
                "cancel requires WAIT or DOWNLOAD, was ${job.state}"
            }
            readyPageStore?.cleanPartTemps(job.identity, job.downloadRevision)
            val cancelled = job.copy(state = DownloadJobState.NONE)
            jobs[index] = cancelled
            cancelled
        }

    /**
     * FINISH Detail delete of download-owned local media only. Success → [NONE]
     * and clears Library association + READY ordinals (re-enqueueable). Failure
     * leaves FINISH and media/association intact (spec §4.2). Does not touch
     * bookmarks, cloud archives, or history.
     */
    fun deleteFinished(
        jobId: String,
        deleteOwnedMedia: () -> Boolean,
    ): DownloadJob = synchronized(lock) {
        val index = jobs.indexOfFirst { it.id == jobId }
        require(index >= 0) { "unknown job $jobId" }
        val job = jobs[index]
        require(job.state == DownloadJobState.FINISH) {
            "deleteFinished requires FINISH, was ${job.state}"
        }
        if (!deleteOwnedMedia()) {
            return job
        }
        libraryCommitter.removeDownloadOwned(job.identity)
        readyByJob.remove(jobId)
        val cleared = job.copy(state = DownloadJobState.NONE)
        jobs[index] = cleared
        cleared
    }

    /**
     * First successful manifest may pin [DownloadJob.expectedPageCount] when
     * still null. Once pinned, a later upstream disagreement no longer hard-
     * fails unconditionally (S3 Task 9 fifth defect, owner bug backlog: a job
     * used to hard-fail with `failureClass`/`failureDetail` both left null
     * forever, making the failure invisible in logs/UI — this mirrors
     * [RoomDownloadJobStore.applyUpstreamPageCount]'s full policy so JVM
     * tests exercise the same behavior as production):
     *  - zero READY pages for this job: nothing fetched yet to protect, so
     *    just re-pin to the new upstream count and continue.
     *  - upstreamCount > pinned: the gallery grew; already-fetched pages
     *    under the old (smaller) pin stay valid — extend the pin and continue.
     *  - upstreamCount < pinned with pages already on device: a suspicious
     *    manifest (could be a transient partial/interstitial read, not a real
     *    shrink). The FIRST such short count parks the job (same state, just
     *    delayed via `resumeNotBeforeMs`, marked via [PAGE_COUNT_MISMATCH_PENDING])
     *    and retries the manifest fetch; only a SECOND CONSECUTIVE short count
     *    hard-fails with `failureClass = "PAGE_COUNT_MISMATCH"` and
     *    `failureDetail = "pinned=<p> upstream=<u>"`.
     */
    fun applyUpstreamPageCount(jobId: String, upstreamCount: Int): DownloadJob = synchronized(lock) {
        val index = jobs.indexOfFirst { it.id == jobId }
        require(index >= 0) { "unknown job $jobId" }
        val job = jobs[index]
        val pinned = job.expectedPageCount
        if (pinned == null) {
            val updated = job.copy(expectedPageCount = upstreamCount)
            jobs[index] = updated
            return updated
        }
        if (pinned == upstreamCount) {
            if (job.failureClass == PAGE_COUNT_MISMATCH_PENDING) {
                // A good read resolved the earlier short-count strike.
                val cleared = job.copy(failureClass = null, failureDetail = null)
                jobs[index] = cleared
                return cleared
            }
            return job
        }
        if (job.state != DownloadJobState.WAIT && job.state != DownloadJobState.DOWNLOAD) {
            return job
        }

        val detail = "pinned=$pinned upstream=$upstreamCount"
        val readyCount = readyByJob[jobId].orEmpty().size

        if (readyCount == 0) {
            val updated = job.copy(
                expectedPageCount = upstreamCount,
                failureClass = null,
                failureDetail = null,
            )
            jobs[index] = updated
            return updated
        }

        if (upstreamCount > pinned) {
            val updated = job.copy(
                expectedPageCount = upstreamCount,
                failureClass = null,
                failureDetail = null,
            )
            jobs[index] = updated
            return updated
        }

        // upstreamCount < pinned with pages already on device.
        if (job.failureClass == PAGE_COUNT_MISMATCH_PENDING) {
            val failed = job.copy(
                state = DownloadJobState.FAILED,
                failureClass = "PAGE_COUNT_MISMATCH",
                failureDetail = detail,
            )
            jobs[index] = failed
            return failed
        }
        val policy = SourceThrottlePolicy.forNamespace(job.identity.providerNamespace)
        // #C17 audit A2: a retry park reads the BACKOFF base, never the healthy-path spacing.
        val pending = job.copy(
            failureClass = PAGE_COUNT_MISMATCH_PENDING,
            failureDetail = detail,
            resumeNotBeforeMs = nowMillis() + policy.jitterBackoffBaseMs,
        )
        jobs[index] = pending
        return pending
    }

    /**
     * Attempt FINISH. Succeeds only when every expected ordinal is READY; then
     * commits job state FINISH and Library association (location + revision)
     * together via [libraryCommitter]. Crash before commit leaves the job
     * non-FINISH with no association.
     */
    fun tryFinish(
        jobId: String,
        downloadLocationUri: String? = null,
    ): DownloadJob = synchronized(lock) {
        val index = jobs.indexOfFirst { it.id == jobId }
        require(index >= 0) { "unknown job $jobId" }
        val job = jobs[index]
        if (job.state != DownloadJobState.WAIT && job.state != DownloadJobState.DOWNLOAD) {
            return job
        }
        val expected = job.expectedPageCount
        if (expected == null || expected <= 0) {
            return job
        }
        val ready = readyByJob[jobId].orEmpty()
        val allReady = (0 until expected).all { it in ready }
        if (!allReady) {
            return job
        }

        if (crashBeforeFinishCommit) {
            // Do not leave FINISH or Library association — crash before commit.
            throw IllegalStateException("simulated crash before FINISH commit completed")
        }

        val location = downloadLocationUri
            ?: defaultDownloadLocationUri(job.identity, job.downloadRevision)
        libraryCommitter.commitOnFinish(
            identity = job.identity,
            downloadLocationUri = location,
            downloadRevision = job.downloadRevision,
        )
        val finished = job.copy(
            state = DownloadJobState.FINISH,
            // P1 (polish backlog): FINISH is a success — drop whatever
            // transient class the last blip left behind (mirrors
            // [RoomDownloadJobStore.tryFinish]).
            failureClass = null,
            failureDetail = null,
            // A36 review O2: a FINISH fetched everything, so it has no
            // truncation point. Belt-and-braces against any path that reaches
            // FINISH from a row that once sealed short.
            sealedTruncatedAt = null,
        )
        jobs[index] = finished
        finished
    }

    private fun updateJob(jobId: String, transform: (DownloadJob) -> DownloadJob): DownloadJob {
        val index = jobs.indexOfFirst { it.id == jobId }
        require(index >= 0) { "unknown job $jobId" }
        val updated = transform(jobs[index])
        jobs[index] = updated
        return updated
    }
}
