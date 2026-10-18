package com.easemic.app.feature.download

import androidx.room.withTransaction
import com.easemic.app.data.DownloadLibraryAvailability
import com.easemic.app.data.room.AppDatabase
import com.easemic.app.data.room.DownloadDao
import com.easemic.app.data.room.DownloadJobEntity
import com.easemic.app.data.room.DownloadReadyPageEntity
import com.easemic.app.data.room.SerializedChapterJob
import com.easemic.app.data.room.SerializedSeriesJobCount
import com.easemic.app.model.DocumentRef
import com.easemic.app.sync.DiagLog
import com.easemic.app.sync.redactIdentity
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Room-backed download job store (Task 5 + Task 8 FINISH→Library wiring).
 *
 * Keeps [DownloadCore] in-memory for JVM unit tests. Process restart restores
 * WAIT/DOWNLOAD (and READY ordinals) from this store without duplicating
 * `(identityVersion, providerNamespace, canonicalItemId)`.
 *
 * [tryFinish] commits job FINISH and Library local-availability in one Room
 * transaction via [DownloadLibraryAvailability] — never Collect/R2.
 */
class RoomDownloadJobStore(
    private val dao: DownloadDao,
    private val database: AppDatabase? = null,
    private val libraryAvailability: DownloadLibraryAvailability? = null,
    private val readyPageStore: ReadyPageStore? = null,
    /**
     * A36 review O1: root of the catalog-download tree, so a Download tap on a
     * SEALED row can materialise the pages it ALREADY holds instead of making
     * the pump re-fetch them. Null ⇒ that path is unavailable and such a tap
     * falls back to the DAO's revive, which is the pre-O1 behaviour.
     */
    private val filesDir: java.io.File? = null,
    /**
     * A36 review O1: the catalog facts for that adopt-time commit. The pump
     * gets them from the locator it is holding; this happens at the tap, so
     * the store has to ask. Defaults to "nothing known", which commits exactly
     * as a locator written before A32 does.
     */
    private val commitFactsFor: suspend (GalleryIdentityV1) -> DownloadCommitFacts = {
        DownloadCommitFacts()
    },
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * How [tryFinish] gets its atomic scope. Defaults to the real Room
     * transaction on [database], which is the ONLY thing production ever
     * uses. Exists so JVM unit tests (no Robolectric, no androidx.room.testing
     * on the unit-test classpath) can still pin what [tryFinish] WRITES —
     * notably the P1 clearing of `failureClass`/`failureDetail`. A test
     * substituting a pass-through runner proves nothing about atomicity;
     * that stays covered by `RoomDownloadPersistenceTest` (androidTest) and
     * `DownloadFinishAtomicityTest`.
     */
    private val runInFinishTransaction: suspend (suspend () -> DownloadJob) -> DownloadJob = { block ->
        val db = database ?: error("RoomDownloadJobStore.tryFinish requires AppDatabase")
        db.withTransaction { block() }
    },
    /**
     * #C16 re-review item 1: fired when a job leaves the queue OUTSIDE the
     * pump (the UI's cancel / uncollect). The pump releases the adapter's
     * cached plan on every exit it drives itself; this is the one exit it
     * cannot see, and for OPDS the plan is the whole decompressed archive.
     */
    private val onJobLeftQueue: (GalleryIdentityV1) -> Unit = {},
) {
    /**
     * Records a chapter restored from a device archive as a completed download.
     * The identity index makes restore idempotent: an existing row is returned
     * unchanged, including its existing intents.
     */
    suspend fun insertRestoredFinish(
        identity: GalleryIdentityV1,
        chapterIdentity: String,
        expectedPageCount: Int,
        downloadRevision: Int,
        intentCloud: Boolean,
    ): DownloadJob = runInFinishTransaction {
        val existing = dao.jobByIdentity(
            identityVersion = GalleryIdentityV1.IDENTITY_VERSION,
            providerNamespace = identity.providerNamespace,
            canonicalItemId = identity.canonicalItemId,
            chapterIdentity = chapterIdentity,
        )
        if (existing != null) return@runInFinishTransaction existing.toDomain()
        val now = clock()
        val row = DownloadJobEntity(
            id = UUID.randomUUID().toString(),
            identityVersion = GalleryIdentityV1.IDENTITY_VERSION,
            providerNamespace = identity.providerNamespace,
            canonicalItemId = identity.canonicalItemId,
            chapterIdentity = chapterIdentity,
            fallbackUrlDerived = identity.fallbackUrlDerived,
            state = DownloadJobState.FINISH.name,
            expectedPageCount = expectedPageCount,
            downloadRevision = downloadRevision,
            enqueuedAtMillis = now,
            updatedAtMillis = now,
            intentLocal = true,
            intentCloud = intentCloud,
        )
        dao.insertJob(row)
        row.toDomain()
    }

    suspend fun upsertEnqueue(
        identity: GalleryIdentityV1,
        expectedPageCount: Int? = null,
        intentLocal: Boolean = false,
        intentCloud: Boolean = false,
        chapterIdentity: String = GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID,
    ): DownloadJob {
        val now = clock()
        val candidate = DownloadJobEntity(
            id = UUID.randomUUID().toString(),
            identityVersion = GalleryIdentityV1.IDENTITY_VERSION,
            providerNamespace = identity.providerNamespace,
            canonicalItemId = identity.canonicalItemId,
            chapterIdentity = chapterIdentity,
            fallbackUrlDerived = identity.fallbackUrlDerived,
            state = DownloadJobState.WAIT.name,
            expectedPageCount = expectedPageCount,
            downloadRevision = 1,
            enqueuedAtMillis = now,
            updatedAtMillis = now,
            intentLocal = intentLocal,
            intentCloud = intentCloud,
        )
        // A36 review N1: the DAO decides whether a SEALED row is revived by a
        // Download tap, and that turns on whether the comic is ALREADY on the
        // shelf. Same question, same source, as the store's own delete guard
        // and the Detail's "on device" glyph: an AVAILABLE association, not a
        // bare provenance claim.
        //
        // #E2 Slice 4a: a serialized chapter counts as on the shelf, so its
        // enqueue only merges — it never revives a SEALED chapter and never
        // reaches the adoption below, which commits through the gallery-shaped
        // Library path.
        val onShelf = chapterIdentity != GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID ||
            (libraryAvailability?.downloadOnDeviceAssociationCount(identity) ?: 0) > 0
        if (intentLocal && !onShelf) {
            // A36 review O1: before reviving anything, see whether the pages
            // are already here. They usually are — a cloud-only seal keeps its
            // device copy until the upload is confirmed and released — and
            // re-fetching them would burn the source, and hand a pending cloud
            // handoff a fresh fetch in place of the very bytes it was queued
            // for. Adopting is both cheaper and more honest.
            adoptSealedLocalPages(identity, candidateIntentCloud = intentCloud)?.let { return it }
        }
        return dao.upsertEnqueueJob(
            candidate = candidate,
            nowMillis = now,
            libraryRowAvailable = onShelf,
        ).toDomain()
    }

    /**
     * A36 review O1 — a Download tap on a SEALED row whose pages are still on
     * the device: commit what is here rather than re-running the fetch.
     *
     * The job stays terminal. Every pinned page present ⇒ this is a completed
     * download that simply never had a Library row (a cloud-only fetch), so it
     * commits Complete and becomes FINISH. Short of the pin ⇒ the truncated
     * shape A36 already knows: an INCOMPLETE row, SEALED, `sealedTruncatedAt`
     * restated from the pages actually adopted, and the seal's own verdict
     * left standing because it is still the reason the comic is short.
     *
     * Returns null when there is nothing to adopt — no SEALED row, no seams
     * wired, or (review O3) no READY marker backed by a real file — and the
     * caller then falls back to the DAO's revive, which re-fetches.
     */
    private suspend fun adoptSealedLocalPages(
        identity: GalleryIdentityV1,
        candidateIntentCloud: Boolean,
    ): DownloadJob? {
        val library = libraryAvailability ?: return null
        val root = filesDir ?: return null
        val pages = readyPageStore ?: return null
        val existing = dao.jobByIdentity(
            identityVersion = GalleryIdentityV1.IDENTITY_VERSION,
            providerNamespace = identity.providerNamespace,
            canonicalItemId = identity.canonicalItemId,
            chapterIdentity = GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID,
        ) ?: return null
        if (existing.state != DownloadJobState.SEALED.name) return null
        val revision = existing.downloadRevision
        // Review O3: a READY row can outlive its file. Only markers hydrate
        // actually admits are real pages; if none are, there is nothing to
        // adopt and the revive re-fetches instead.
        val roomReady = dao.readyOrdinals(existing.id, revision)
        val usable = pages.hydrateTrustedOrdinals(
            identity = identity,
            revision = revision,
            ordinals = roomReady,
        ).toList()
        if (usable.isEmpty()) {
            // Review P1: nothing on disk, but Room still lists rows — a
            // release that died between `deletePublishedRevision` and
            // `deleteReadyPagesForJob`. Handing those to the revive is worse
            // than useless: `upsertEnqueueJob` sees a non-empty READY set,
            // keeps the revision, and the job resumes against a revision whose
            // files are gone — and a later GONE seal would then try to
            // materialise pages that do not exist and could never terminate.
            // Drop the dead rows (and their in-memory keys) first, so the
            // revive bumps the revision and starts honestly from nothing.
            if (roomReady.isNotEmpty()) {
                DiagLog.w(
                    "DownloadPump",
                    "dropping ${roomReady.size} READY row(s) with no file for " +
                        "${redactIdentity(identity)} r$revision " +
                        "before re-fetching (A36 P1)",
                )
                dao.deleteReadyPagesForJob(existing.id)
                pages.deletePublishedRevision(identity, revision)
            }
            return null
        }
        val facts = commitFactsFor(identity)
        val (location, documents) = materializeCatalogDownloadPages(
            filesDir = root,
            identity = identity,
            downloadRevision = revision,
            readyPagePaths = usable.map { pages.readyPath(identity, revision, it) },
        )
        if (documents.isEmpty()) return null
        val pinned = existing.expectedPageCount
        val complete = pinned == null || documents.size >= pinned
        val committed = runInFinishTransaction {
            val fresh = dao.jobById(existing.id) ?: error("unknown job ${existing.id}")
            // The tap raced the pump (a release, a discard, a re-enqueue):
            // leave whatever won alone rather than re-sealing over it.
            if (fresh.state != DownloadJobState.SEALED.name) {
                return@runInFinishTransaction fresh.toDomain()
            }
            library.commitDownloadLocalAvailability(
                identity = identity,
                downloadLocationUri = location,
                downloadRevision = revision,
                title = facts.title?.takeIf { it.isNotBlank() } ?: identity.canonicalItemId,
                pages = documents,
                metadata = facts.metadata,
                completeness = if (complete) {
                    DownloadCommitCompleteness.Complete
                } else {
                    DownloadCommitCompleteness.Truncated(checkNotNull(pinned))
                },
            )
            val adopted = fresh.copy(
                state = if (complete) {
                    DownloadJobState.FINISH.name
                } else {
                    DownloadJobState.SEALED.name
                },
                intentLocal = true,
                // Review P2: the tap can carry a cloud intent too (a
                // collect+download in one gesture). Merging both mirrors
                // [DownloadCore.enqueue] and every other enqueue path — an
                // adoption must never be the one route that silently drops
                // half of what the user asked for.
                intentCloud = fresh.intentCloud || candidateIntentCloud,
                // Review O2: a complete adoption has no truncation point; a
                // short one restates it from the pages actually committed.
                sealedTruncatedAt = if (complete) null else documents.size,
                // A completion is a success and says nothing about failure; a
                // short one keeps the verdict that made it short.
                failureClass = if (complete) null else fresh.failureClass,
                failureDetail = if (complete) null else fresh.failureDetail,
                updatedAtMillis = clock(),
            )
            dao.updateJob(adopted)
            DiagLog.i(
                "DownloadPump",
                "adopted ${documents.size} on-device page(s) for ${redactIdentity(identity)} " +
                    "r$revision as ${adopted.state} (A36 O1)",
            )
            adopted.toDomain()
        }
        if (committed.state == DownloadJobState.FINISH) reclaimStagingForFinishedJob(committed.id)
        return committed
    }

    /**
     * Hard-fail transition (historically unguarded — mirrors the pre-existing
     * `updateState(jobId, FAILED)` call sites, several of which fire from
     * states other than WAIT/DOWNLOAD e.g. after a manifest count
     * disagreement). [failureClass]/[failureDetail] optionally stamp the
     * reason in the same write (Task 8, owner bug #6: previously a hard-fail
     * left both null forever, so nothing on-device explained why a job died).
     * Both default to null so pre-existing callers that only pass [jobId]
     * behave exactly as before and never clobber an already-recorded reason.
     */
    suspend fun markFailed(
        jobId: String,
        failureClass: String? = null,
        failureDetail: String? = null,
    ): DownloadJob {
        val existing = dao.jobById(jobId) ?: error("unknown job $jobId")
        // S3 Task 9 fifth defect (owner bug backlog): every markFailed call
        // site today already passes an explicit reason — this is a
        // defensive backstop so a FUTURE call site that forgets to pass one
        // can never again leave failureClass/failureDetail null on a
        // hard-fail. SEND_BACK item 5: the fallback detail text must fire
        // ONLY when the class is ALSO unresolved — otherwise a caller that
        // correctly passes a failureClass but no detail (e.g.
        // JITTER_EXHAUSTED, which is self-explanatory) got stuck with the
        // accusatory "called with no reason" text even though a reason WAS
        // supplied.
        val resolvedClass = failureClass ?: existing.failureClass
        if (resolvedClass == null) {
            DiagLog.w("DownloadPump", "job $jobId hard-failed with no failureClass supplied — stamping UNSPECIFIED")
        }
        val resolvedDetail = failureDetail ?: existing.failureDetail ?: if (resolvedClass == null) {
            "no failure reason recorded"
        } else {
            null
        }
        val updated = existing.copy(
            state = DownloadJobState.FAILED.name,
            failureClass = resolvedClass ?: "UNSPECIFIED",
            failureDetail = resolvedDetail,
            updatedAtMillis = clock(),
        )
        dao.updateJob(updated)
        return updated.toDomain()
    }

    suspend fun updateState(jobId: String, state: DownloadJobState): DownloadJob {
        val existing = dao.jobById(jobId) ?: error("unknown job $jobId")
        val updated = existing.copy(
            state = state.name,
            updatedAtMillis = clock(),
        )
        dao.updateJob(updated)
        return updated.toDomain()
    }

    suspend fun markPageReady(jobId: String, revision: Int, ordinal: Int) {
        require(dao.jobById(jobId) != null) { "unknown job $jobId" }
        dao.insertReadyPage(
            DownloadReadyPageEntity(
                jobId = jobId,
                downloadRevision = revision,
                ordinal = ordinal,
                readyAtMillis = clock(),
            ),
        )
    }

    suspend fun readyOrdinals(jobId: String, revision: Int): Set<Int> =
        dao.readyOrdinals(jobId, revision).toSet()

    suspend fun requireJob(jobId: String): DownloadJob =
        dao.jobById(jobId)?.toDomain() ?: error("unknown job $jobId")

    suspend fun loadAllJobs(): List<DownloadJob> =
        dao.allJobs().map { it.toDomain() }

    /**
     * Spec §4.3: exactly WAIT + DOWNLOAD, FIFO by enqueue time, excluding jobs
     * still inside a QUOTA/ban idle window ([DownloadJob.resumeNotBeforeMs] in
     * the future relative to [clock]).
     */
    suspend fun consumptionQueue(): List<DownloadJob> =
        dao.consumptionQueueJobs(now = clock()).map { it.toDomain() }

    /** Live queue membership for More → Download queue (spec §4.3). */
    fun observeQueue(): Flow<List<DownloadJob>> =
        dao.observeConsumptionQueueJobs().map { rows -> rows.map { it.toDomain() } }

    /**
     * A20: Activity-tab rows — WAIT/DOWNLOAD/FAILED (see
     * [DownloadDao.observeActivityJobs]). Not for the legacy DownloadQueue
     * route or the CF banner, which keep [observeQueue].
     */
    fun observeActivityJobs(): Flow<List<DownloadJob>> =
        dao.observeActivityJobs().map { rows -> rows.map { it.toDomain() } }

    /** Chapter identities and states used to build Activity's shared population. */
    fun observeSerializedChapterJobs(): Flow<List<SerializedChapterJob>> =
        dao.observeSerializedChapterJobs()

    /** Live active serialized chapter totals for Activity's series summaries. */
    fun observeSerializedSeriesCounts(): Flow<List<SerializedSeriesJobCount>> =
        dao.observeSerializedSeriesCounts()

    /** #C9: see [DownloadDao.observeDelistedJobs]. */
    fun observeDelistedJobs(): Flow<List<DownloadJob>> =
        dao.observeDelistedJobs().map { rows -> rows.map { it.toDomain() } }

    fun observeReadyPages(): Flow<List<DownloadReadyPageEntity>> = dao.observeReadyPages()

    /** Live Detail job for one [GalleryIdentityV1] (null when no row). */
    fun observeJob(
        identity: GalleryIdentityV1,
        chapterIdentity: String = GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID,
    ): Flow<DownloadJob?> =
        dao.observeJobByIdentity(
            identityVersion = GalleryIdentityV1.IDENTITY_VERSION,
            providerNamespace = identity.providerNamespace,
            canonicalItemId = identity.canonicalItemId,
            chapterIdentity = chapterIdentity,
        ).map { rows -> rows.firstOrNull()?.toDomain() }

    /** Live serialized detail jobs, including every chapter row in the series, with live READY page counts. */
    fun observeSeriesJobs(identity: GalleryIdentityV1): Flow<List<DownloadJob>> =
        combine(
            dao.observeJobsForSeries(
                identityVersion = GalleryIdentityV1.IDENTITY_VERSION,
                providerNamespace = identity.providerNamespace,
                canonicalItemId = identity.canonicalItemId,
            ),
            dao.observeReadyCountsForSeries(
                identityVersion = GalleryIdentityV1.IDENTITY_VERSION,
                providerNamespace = identity.providerNamespace,
                canonicalItemId = identity.canonicalItemId,
            ),
        ) { rows, counts ->
            val readyByJob = counts.associate { it.jobId to it.readyCount }
            rows.map { row -> row.toDomain().copy(readyPageCount = readyByJob[row.id] ?: 0) }
        }

    /** One-shot serialized-series enumeration used by the local delete verb. */
    suspend fun jobsForSeries(identity: GalleryIdentityV1): List<DownloadJob> =
        dao.jobsForSeries(
            identityVersion = GalleryIdentityV1.IDENTITY_VERSION,
            providerNamespace = identity.providerNamespace,
            canonicalItemId = identity.canonicalItemId,
        ).map { row ->
            row.toDomain().copy(
                readyPageCount = dao.readyOrdinals(row.id, row.downloadRevision).size,
            )
        }

    suspend fun jobFor(
        identity: GalleryIdentityV1,
        chapterIdentity: String = GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID,
    ): DownloadJob? =
        dao.jobByIdentity(
            identityVersion = GalleryIdentityV1.IDENTITY_VERSION,
            providerNamespace = identity.providerNamespace,
            canonicalItemId = identity.canonicalItemId,
            chapterIdentity = chapterIdentity,
        )?.toDomain()

    /** Cancel WAIT/DOWNLOAD → NONE (spec §4.7). */
    suspend fun cancel(jobId: String): DownloadJob {
        val existing = dao.jobById(jobId) ?: error("unknown job $jobId")
        require(
            existing.state == DownloadJobState.WAIT.name ||
                existing.state == DownloadJobState.DOWNLOAD.name,
        ) { "cancel requires WAIT or DOWNLOAD, was ${existing.state}" }
        val job = existing.toDomain()
        readyPageStore?.cleanPartTemps(job.storageIdentity, existing.downloadRevision)
        onJobLeftQueue(job.adapterIdentity)
        return updateState(jobId, DownloadJobState.NONE)
    }

    /**
     * A31 (owner bug backlog): drops the cloud fetch intent for a dual-intent
     * job whose Collect bookmark was removed while the local download is
     * still wanted (see [com.easemic.app.feature.collect.UncollectJobOutcome.DROP_CLOUD_INTENT]).
     * Unlike [cancel], not guarded by state — a dual-intent job can be
     * uncollected while WAIT/DOWNLOAD or already terminal.
     */
    suspend fun dropCloudIntent(jobId: String) {
        dao.clearIntentCloud(jobId, clock())
    }

    /**
     * A31 (owner bug backlog, controller review follow-up): discards a
     * cloud-only job whose Collect bookmark is gone
     * ([com.easemic.app.feature.collect.UncollectJobOutcome.DISCARD])
     * once it has reached a TERMINAL state (SEALED/FINISH/FAILED/GONE) —
     * [cancel] only covers WAIT/DOWNLOAD. Mirrors [releaseLocalPagesAfterCloud]'s
     * full local-pages release (READY files via
     * [ReadyPageStore.deletePublishedRevision], READY rows, Library
     * local-availability, and the caller-owned [deleteCatalogTree]) instead.
     * For serialized rows the callback receives [DownloadJob.storageIdentity]
     * for chapter bytes; the Library guard/removal still uses the series
     * identity. This avoids the bare state flip that used to leave the job's
     * pages as orphans on disk forever. Never touches intents/bookmarks/ledger — those are
     * the caller's responsibility. Logs (and returns the unchanged job)
     * whenever nothing was actually discarded, same as
     * [releaseLocalPagesAfterCloud].
     */
    suspend fun discardCloudOnlyJob(
        jobId: String,
        deleteCatalogTree: (identity: GalleryIdentityV1, downloadRevision: Int) -> Boolean,
    ): DownloadJob {
        val existing = dao.jobById(jobId) ?: error("unknown job $jobId")
        // Controller review HIGH 1 (data loss): FINISH deliberately excluded —
        // a cloud-only job never reaches FINISH (it seals), so FINISH is
        // proof of a real local download; [uncollectJobOutcome] already
        // routes it to DROP_CLOUD_INTENT instead, this is defense in depth.
        val terminalStates = setOf(
            DownloadJobState.SEALED.name,
            DownloadJobState.FAILED.name,
            DownloadJobState.GONE.name,
        )
        if (existing.state !in terminalStates) {
            DiagLog.w("CloudSync", "discard skipped: job $jobId is ${existing.state}, not a terminal state")
            return existing.toDomain()
        }
        val job = existing.toDomain()
        // A serialized row's Room identity is the series, while READY files
        // and catalog revisions use its opaque chapter storage identity.
        // Gallery rows have identical values for both, so this keeps the
        // existing gallery path unchanged and makes terminal chapter cleanup
        // reach the actual files.
        val identity = job.identity
        val storageIdentity = job.storageIdentity
        // Second layer of the same defense in depth: even if the state check
        // above somehow let a real download through, never delete one that
        // the Library still has an on-device association for.
        if ((libraryAvailability?.downloadOnDeviceAssociationCount(identity) ?: 0) > 0) {
            DiagLog.w("CloudSync", "discard skipped: job $jobId has a Library association, refusing to delete")
            return existing.toDomain()
        }
        if (!deleteCatalogTree(storageIdentity, existing.downloadRevision)) {
            DiagLog.w("CloudSync", "discard skipped: catalog tree delete failed job=$jobId r${existing.downloadRevision}")
            return existing.toDomain()
        }
        readyPageStore?.deletePublishedRevision(storageIdentity, existing.downloadRevision)
        libraryAvailability?.removeDownloadLocalAvailability(identity)
        dao.deleteReadyPagesForJob(jobId)
        DiagLog.i(
            "CloudSync",
            "discarded cloud-only job=$jobId ${redactIdentity(identity)} r${existing.downloadRevision}",
        )
        return updateState(jobId, DownloadJobState.NONE)
    }

    /**
     * #E101: budget eviction of a terminal job's download staging (READY files via
     * [ReadyPageStore.deletePublishedRevision], plus READY rows). NOT an uncollect:
     * the row keeps its state — a FAILED row stays FAILED and [retry] still works, it just
     * re-downloads from scratch. No catalog tree is touched (a FAILED/GONE/NONE row never
     * committed one). Defense in depth, same as [discardCloudOnlyJob]: a Library on-device
     * association declines the eviction.
     */
    suspend fun evictTerminalStaging(jobId: String): Boolean {
        val existing = dao.jobById(jobId) ?: return false
        // FAILED/GONE/NONE only: WAIT/DOWNLOAD are active work, and SEALED/FINISH
        // are the Library's copy. NONE is here because a dismissed/cancelled row
        // with no Library association is invisible everywhere yet still holds bytes.
        val evictableStates = setOf(
            DownloadJobState.FAILED.name,
            DownloadJobState.GONE.name,
            DownloadJobState.NONE.name,
        )
        if (existing.state !in evictableStates) {
            DiagLog.w("DownloadPump", "staging evict skipped: job $jobId is ${existing.state}, not a terminal-failure state")
            return false
        }
        val job = existing.toDomain()
        // Same identity split as [discardCloudOnlyJob]: a serialized row's Room
        // identity is the series while READY files use its opaque chapter storage
        // identity. The Library guard stays on the series identity — that is what
        // the association is keyed on; the files come from storageIdentity.
        if ((libraryAvailability?.downloadOnDeviceAssociationCount(job.identity) ?: 0) > 0) {
            DiagLog.w("DownloadPump", "staging evict skipped: job $jobId has a Library association, refusing to delete")
            return false
        }
        // By directory, NOT by READY rows: the #E101 reproduction proved files
        // can outlive their rows (a FAILED row ends readyPageCount=0 while its
        // file remains), so the directory delete is what actually frees the bytes.
        readyPageStore?.deletePublishedRevision(job.storageIdentity, existing.downloadRevision)
        dao.deleteReadyPagesForJob(jobId)
        DiagLog.i(
            "DownloadPump",
            "staging evicted job=$jobId ${redactIdentity(job.identity)} r${existing.downloadRevision}",
        )
        return true
    }

    /**
     * Manual Retry: FAILED → WAIT; revision, pin, recorded reason and READY
     * pages retained (spec §4.2). A11/A20 (owner bug backlog, Kyouei at
     * `autoRetryAttempts=4`): a human retry also zeroes the bounded
     * auto-revive bookkeeping ([DownloadJob.autoRetryAttempts],
     * [DownloadJob.lastAutoRetryAtMs]) so the sweep treats the job as fresh
     * if it fails again, and clears [DownloadJob.resumeNotBeforeMs] so the
     * very next pump kick can pick it up instead of waiting out a stale
     * QUOTA/ban window the user has just decided to override.
     */
    suspend fun retry(jobId: String): DownloadJob {
        val existing = dao.jobById(jobId) ?: error("unknown job $jobId")
        require(existing.state == DownloadJobState.FAILED.name) {
            "retry requires FAILED, was ${existing.state}"
        }
        val updated = existing.copy(
            state = DownloadJobState.WAIT.name,
            resumeNotBeforeMs = 0L,
            autoRetryAttempts = 0,
            lastAutoRetryAtMs = 0L,
            updatedAtMillis = clock(),
        )
        dao.updateJob(updated)
        return updated.toDomain()
    }

    /**
     * Bounded auto-revive of a FAILED cloud-intent job (S3 Task 4, owner bug
     * A6): same FAILED → WAIT transition as [retry], plus increments
     * [DownloadJob.autoRetryAttempts] so [downloadJobsToRevive]'s sweep
     * eventually stops retrying a permanently-broken source.
     *
     * Deliberately a no-op (returns the unchanged job) on a non-FAILED job,
     * unlike [retry]'s throwing precondition: this is driven by a background
     * sweep (cold-start replay and [com.easemic.app.work.CloudConnectivityReKick])
     * that can race a concurrent state change — e.g. a manual UI retry or the
     * pump itself already having advanced the job past FAILED by the time
     * this runs. Matches the existing no-op idempotent pattern used by
     * [tryFinish] and [applyUpstreamPageCount] for the same reason.
     */
    suspend fun autoRetry(jobId: String): DownloadJob {
        val existing = dao.jobById(jobId) ?: error("unknown job $jobId")
        if (existing.state != DownloadJobState.FAILED.name) {
            return existing.toDomain()
        }
        val now = clock()
        val updated = existing.copy(
            state = DownloadJobState.WAIT.name,
            autoRetryAttempts = existing.autoRetryAttempts + 1,
            lastAutoRetryAtMs = now,
            updatedAtMillis = now,
        )
        dao.updateJob(updated)
        return updated.toDomain()
    }

    /**
     * A45 / C9: the Dismiss half of "Needs attention". The section used to
     * offer ONLY Retry, so a row whose work can never succeed — a delisted
     * gallery, a permanent 404, a vanished source — sat in the user's Activity
     * feed forever with no way out.
     *
     * FAILED → NONE is a QUEUE-ROW removal, not a delete. NONE is outside
     * [DownloadDao.observeActivityJobs] so the row leaves the feed durably, and
     * outside [downloadJobsToRevive]'s `state == FAILED` filter so the bounded
     * auto-revive sweep cannot resurrect it. Every already-downloaded page,
     * READY row and Library association is left untouched — unlike
     * [discardCloudOnlyJob] or [releaseLocalPagesAfterCloud], nothing on disk
     * is deleted. The pages the user already fetched are theirs.
     *
     * C9: a GONE row is a Dismiss-only trace. Its state must survive — NONE
     * would be revived by the next enqueue and would drop the shelf's
     * DELISTED projection — so a GONE dismissal only stamps
     * [DownloadJobEntity.dismissedAtMillis]; [DownloadDao.observeActivityJobs]
     * filters on it.
     *
     * Driven by a UI tap that can race the pump, so an ineligible state is a
     * logged no-op rather than a throw — same reason as [autoRetry].
     */
    suspend fun dismissAttentionRow(jobId: String): DownloadJob {
        val existing = dao.jobById(jobId) ?: error("unknown job $jobId")
        val now = clock()
        val updated = when (existing.state) {
            DownloadJobState.FAILED.name -> existing.copy(
                state = DownloadJobState.NONE.name,
                updatedAtMillis = now,
            )
            DownloadJobState.GONE.name -> existing.copy(
                dismissedAtMillis = now,
                updatedAtMillis = now,
            )
            else -> {
                DiagLog.w("DownloadPump", "dismiss skipped: job $jobId is ${existing.state}, not FAILED/GONE")
                return existing.toDomain()
            }
        }
        dao.updateJob(updated)
        DiagLog.i("DownloadPump", "job $jobId dismissed from Needs attention (${existing.state}); pages and shelf pointer kept")
        return updated.toDomain()
    }

    /**
     * WAIT/DOWNLOAD → SEALED: fetch complete (or truncation wind-down declared
     * complete). [truncatedAt] records pages actually fetched when sealing via
     * truncation; null means a full, untruncated seal.
     *
     * P1 (polish backlog, owner principle "honest state on every row"):
     * [failureClass]/[failureDetail] REPLACE the row's recorded reason rather
     * than adding to it — they describe why THIS seal happened. Both null (a
     * full completion) therefore CLEARS the record, so a seal that fetched
     * every page stops advertising the last transient JITTER; a truncation
     * wind-down passes the verdict that caused it (today always
     * [FetchFailureClass.GONE] plus the 404 detail from
     * [DownloadWorkerLoop]'s D1 double-404 path), so the row no longer reads
     * `JITTER / EHENTAI_HTTP_404` — "transient, will retry" — while being
     * terminally sealed. Both values stay inside the closed
     * [FetchFailureClass.label] vocabulary that
     * [downloadFailureReason] renders.
     */
    suspend fun markSealed(
        jobId: String,
        truncatedAt: Int? = null,
        failureClass: String? = null,
        failureDetail: String? = null,
    ): DownloadJob {
        val existing = dao.jobById(jobId) ?: error("unknown job $jobId")
        require(
            existing.state == DownloadJobState.WAIT.name ||
                existing.state == DownloadJobState.DOWNLOAD.name,
        ) { "markSealed requires WAIT or DOWNLOAD, was ${existing.state}" }
        val sealed = existing.copy(
            state = DownloadJobState.SEALED.name,
            sealedTruncatedAt = truncatedAt,
            failureClass = failureClass,
            failureDetail = failureDetail,
            updatedAtMillis = clock(),
        )
        dao.updateJob(sealed)
        return sealed.toDomain()
    }

    /**
     * A36 (owner bug backlog; owner's phone: Kyouei 22 of 43) — the GONE
     * truncation wind-down of a job that carries [DownloadJob.intentLocal] and
     * has at least one READY page.
     *
     * The job row is sealed EXACTLY as [markSealed] seals it: SEALED,
     * [DownloadJob.sealedTruncatedAt] = [truncatedAt], the GONE verdict in
     * [failureClass]/[failureDetail]. The state machine is deliberately
     * unchanged (S4b/P1 semantics stay). What is new is that the k pages the
     * app already holds are committed to the Library in the SAME transaction,
     * as an INCOMPLETE row ([DownloadCommitCompleteness.Truncated]) whose
     * expected count is the one pinned at enqueue. Before this, a truncated
     * seal produced no row at all — `commitDownloadLocalAvailability` ran only
     * from [tryFinish] (every expected ordinal READY) — so the fetched pages
     * were unreadable on the shelf, the Activity row was their only trace, and
     * `detailTruncatedSeal`'s note was unreachable copy.
     *
     * Atomic for the same reason [tryFinish] is: a row without the seal (or a
     * seal without the row) is a state the rest of the app has no name for.
     * Idempotent like [tryFinish] — a job no longer WAIT/DOWNLOAD is returned
     * unchanged rather than re-sealed, since a pump pass can race a cancel.
     * Callers must pass at least one page; [DownloadWorkerLoop] only reaches
     * here with `readyCount > 0`, and an empty list would mean committing a
     * Library row with nothing behind it.
     */
    suspend fun sealTruncatedWithLocalPages(
        jobId: String,
        downloadLocationUri: String,
        truncatedAt: Int,
        title: String,
        pages: List<DocumentRef>,
        failureClass: String? = null,
        failureDetail: String? = null,
        metadata: DownloadCommitMetadata = DownloadCommitMetadata(),
    ): DownloadJob {
        val library = libraryAvailability
            ?: error("RoomDownloadJobStore.sealTruncatedWithLocalPages requires DownloadLibraryAvailability")
        require(pages.isNotEmpty()) {
            "a truncated seal commits the pages it DID fetch; an empty list is a plain markSealed"
        }
        return runInFinishTransaction {
            val existing = dao.jobById(jobId) ?: error("unknown job $jobId")
            if (existing.state != DownloadJobState.WAIT.name &&
                existing.state != DownloadJobState.DOWNLOAD.name
            ) {
                return@runInFinishTransaction existing.toDomain()
            }
            val identity = GalleryIdentityV1(
                providerNamespace = existing.providerNamespace,
                canonicalItemId = existing.canonicalItemId,
                fallbackUrlDerived = existing.fallbackUrlDerived,
            )
            // The pin is what makes "22 of 43" sayable. Without one there
            // is no honest expectation to state, so fall back to the
            // pages actually on device rather than inventing a number.
            //
            // Review M3: and only when the row really IS short. A seal
            // holding every pinned page is a complete comic; marking it
            // INCOMPLETE would badge it with no explanation, since the
            // Detail note needs `pages < pinned` to say anything.
            // [DownloadWorkerLoop] already routes that case to FINISH —
            // this is the store's own guard against a future caller.
            val completeness = existing.expectedPageCount
                ?.takeIf { pages.size < it }
                ?.let { DownloadCommitCompleteness.Truncated(it) }
                ?: DownloadCommitCompleteness.Complete
            if (!existing.isSerializedChapter) {
                library.commitDownloadLocalAvailability(
                    identity = identity,
                    downloadLocationUri = downloadLocationUri,
                    downloadRevision = existing.downloadRevision,
                    title = title,
                    pages = pages,
                    metadata = metadata,
                    completeness = completeness,
                )
            } else {
                library.commitChapterDownloadLocalAvailability(
                    series = identity,
                    chapterId = existing.chapterIdentity,
                    downloadLocationUri = downloadLocationUri,
                    downloadRevision = existing.downloadRevision,
                    title = title,
                    pages = pages,
                    completeness = completeness,
                )
            }
            val sealed = existing.copy(
                state = DownloadJobState.SEALED.name,
                sealedTruncatedAt = truncatedAt,
                failureClass = failureClass,
                failureDetail = failureDetail,
                updatedAtMillis = clock(),
            )
            dao.updateJob(sealed)
            sealed.toDomain()
        }
    }

    /**
     * WAIT/DOWNLOAD → GONE: source confirmed gone with zero pages fetched.
     *
     * P1 (polish backlog): same rule as [markSealed] —
     * [failureClass]/[failureDetail] REPLACE the row's recorded reason. A
     * GONE row reached through the D1 double-404 path used to keep the FIRST
     * 404's `JITTER / EHENTAI_HTTP_404` record, i.e. a terminal "source is
     * gone" row that still explained itself as "temporary network issue,
     * will retry". Both null clears the record.
     */
    suspend fun markGone(
        jobId: String,
        failureClass: String? = null,
        failureDetail: String? = null,
    ): DownloadJob {
        val existing = dao.jobById(jobId) ?: error("unknown job $jobId")
        require(
            existing.state == DownloadJobState.WAIT.name ||
                existing.state == DownloadJobState.DOWNLOAD.name,
        ) { "markGone requires WAIT or DOWNLOAD, was ${existing.state}" }
        val gone = existing.copy(
            state = DownloadJobState.GONE.name,
            failureClass = failureClass,
            failureDetail = failureDetail,
            updatedAtMillis = clock(),
        )
        dao.updateJob(gone)
        return gone.toDomain()
    }

    /**
     * Records fetch-failure metadata and the QUOTA/ban idle window without
     * changing [DownloadJob.state] — routing/state transition is the caller's
     * decision, this only persists the classification for UI + retry gating.
     * Guarded to WAIT/DOWNLOAD like [markSealed]/[markGone] — a terminal job
     * (FINISH/SEALED/GONE/FAILED) must not be re-stamped after the fact.
     */
    suspend fun recordFailure(
        jobId: String,
        failureClass: String,
        detail: String?,
        resumeNotBeforeMs: Long,
    ): DownloadJob {
        val existing = dao.jobById(jobId) ?: error("unknown job $jobId")
        require(
            existing.state == DownloadJobState.WAIT.name ||
                existing.state == DownloadJobState.DOWNLOAD.name,
        ) { "recordFailure requires WAIT or DOWNLOAD, was ${existing.state}" }
        val updated = existing.copy(
            failureClass = failureClass,
            failureDetail = detail,
            resumeNotBeforeMs = resumeNotBeforeMs,
            updatedAtMillis = clock(),
        )
        dao.updateJob(updated)
        return updated.toDomain()
    }

    /**
     * Clears the parked resume window for every still-runnable ABUSE_STOP job
     * (nh Cloudflare challenge, e-h IP ban, or any future ABUSE_STOP source) —
     * called after a successful Cloudflare-challenge WebView solve so the very
     * next [com.easemic.app.work.DownloadPumpWorker.kick]
     * treats the parked job(s) as immediately runnable instead of sitting out
     * the rest of a 1h idle window. A plain kick alone is not enough:
     * [consumptionQueue] (and Room's matching `consumptionQueueJobs` query)
     * filters on [DownloadJob.resumeNotBeforeMs], so a job parked in the
     * future would stay invisible to the pump until that window naturally
     * elapses.
     */
    suspend fun clearResumeWindowForAbuseStop() {
        dao.clearResumeWindowForAbuseStop()
    }

    /**
     * Detail delete of download-owned local media only. Success → NONE,
     * clears READY ordinals, and removes Library local-availability for this
     * identity. Failure leaves the job as it was (spec §4.2).
     * Bookmarks/cloud/history are never touched.
     *
     * A36: accepts FINISH **or** a SEALED job that owns a Library row — the
     * truncated wind-down ([sealTruncatedWithLocalPages]) puts a real, readable
     * INCOMPLETE row on the shelf, and A30 §2.3's one file-deleting verb must
     * be able to take it back off. The Library association is what decides it,
     * not the state name: a SEALED job WITHOUT a row is a cloud-only fetch
     * whose pages the user never asked to keep on device, and deleting through
     * this path would be a verb the Detail screen never offered.
     */
    suspend fun deleteFinished(
        jobId: String,
        deleteOwnedMedia: () -> Boolean,
    ): DownloadJob {
        val existing = dao.jobById(jobId) ?: error("unknown job $jobId")
        // #E2 Slice 4a: removeDownloadLocalAvailability below works on the
        // whole download item, which for a chapter is the series. Deleting a
        // chapter is 4b/Slice 5 work.
        require(!existing.isSerializedChapter) { "deleteFinished is only valid for gallery jobs" }
        val identity = GalleryIdentityV1(
            providerNamespace = existing.providerNamespace,
            canonicalItemId = existing.canonicalItemId,
            fallbackUrlDerived = existing.fallbackUrlDerived,
        )
        val sealedWithRow = existing.state == DownloadJobState.SEALED.name &&
            (libraryAvailability?.downloadOnDeviceAssociationCount(identity) ?: 0) > 0
        require(existing.state == DownloadJobState.FINISH.name || sealedWithRow) {
            "deleteFinished requires FINISH or a SEALED job owning a Library row, was ${existing.state}"
        }
        if (!deleteOwnedMedia()) {
            return existing.toDomain()
        }
        readyPageStore?.deletePublishedRevision(identity, existing.downloadRevision)
        libraryAvailability?.removeDownloadLocalAvailability(identity)
        dao.deleteReadyPagesForJob(jobId)
        return updateState(jobId, DownloadJobState.NONE)
    }

    /**
     * Serialized-series sibling of [deleteFinished]. The gallery-only guard
     * above stays intact; this path deliberately keeps the chapter tombstone
     * so a follow refresh cannot revive an explicit local delete.
     */
    suspend fun deleteSerializedChapter(
        jobId: String,
        deleteOwnedMedia: () -> Boolean,
    ): Boolean {
        val existing = dao.jobById(jobId) ?: return false
        require(existing.isSerializedChapter) { "deleteSerializedChapter requires a serialized chapter job" }
        if (existing.state == DownloadJobState.NONE.name) return true
        if (!deleteOwnedMedia()) return false
        val storageIdentity = existing.toDomain().storageIdentity
        readyPageStore?.deletePublishedRevision(storageIdentity, existing.downloadRevision)
        dao.deleteReadyPagesForJob(jobId)
        updateState(jobId, DownloadJobState.NONE)
        return true
    }

    /**
     * S4 Task 3 ("remote hosting", owner bug C2): release the device copy of
     * a job's pages once its upload is confirmed in the cloud. Same cleanup
     * as [deleteFinished] — READY files ([ReadyPageStore.deletePublishedRevision]),
     * READY rows, Library local-availability
     * ([DownloadLibraryAvailability.removeDownloadLocalAvailability] for a gallery,
     * [DownloadLibraryAvailability.removeChapterLocalAvailability] for one serialized
     * chapter, a
     * no-op for a SEALED job that has no Library row — since A36 a truncated
     * intentLocal seal DOES have one, but such a job also carries intentLocal
     * and is therefore refused by both [shouldReleaseLocalPagesAfterUpload]
     * and the intent re-read below, so it never reaches the removal) and the
     * caller-owned catalog tree — minus the manual FINISH gate: a pure-cloud
     * job completes as SEALED (never FINISH), so SEALED and FINISH both
     * qualify. Success → NONE, exactly like [deleteFinished]: a later local
     * Download tap then re-enqueues through `DownloadDao.upsertEnqueueJob`'s
     * NONE-revive path with a fresh revision instead of finding a SEALED row
     * with no pages behind it, and the durable sync ledger (S3 Task 1) — not
     * this row — is what stops a re-upload.
     *
     * Intents are left exactly as they were (intentCloud stays true), and that
     * is safe because the row's NEXT attempt does not inherit them: C4 §2.2
     * made `DownloadDao.upsertEnqueueJob`'s NONE-revive take the CANDIDATE's
     * intents, so the tap that revives this row is what decides what the new
     * attempt is for. A pull-back ("give me this cloud comic on device again")
     * is therefore a pure-LOCAL job, which `shouldReleaseLocalPagesAfterUpload`
     * refuses under either setting — the sweep can never mistake the copy the
     * user just fetched for a cloud-hosted one and delete it again. A Collect
     * that revives the same row brings its own cloud intent and behaves as
     * before. So do NOT "fix" this by clearing intents here: the fix belongs on
     * the revive, and clearing them here would only lose the record of what
     * this finished attempt was.
     *
     * The intent decision (dual-intent keeps its pages) is NOT made here —
     * see [shouldReleaseLocalPagesAfterUpload]; this method only guards the
     * state so it can never strip a job still fetching, and re-reads
     * [DownloadJobEntity.intentLocal] one last time so a Download tap landing
     * inside the caller's decision→call window still keeps its pages. Driven
     * from a background hook (AppGraph's `onSynced`), so like [autoRetry] it
     * is a no-op (returns the unchanged job) instead of throwing on an
     * ineligible state or when [deleteCatalogTree] reports failure — every
     * skip is logged; nothing here is silent.
     */
    suspend fun releaseLocalPagesAfterCloud(
        jobId: String,
        releaseAfterUpload: Boolean = false,
        deleteCatalogTree: (identity: GalleryIdentityV1, downloadRevision: Int) -> Boolean,
    ): DownloadJob {
        val existing = dao.jobById(jobId) ?: error("unknown job $jobId")
        if (existing.state != DownloadJobState.SEALED.name &&
            existing.state != DownloadJobState.FINISH.name
        ) {
            DiagLog.w("CloudSync", "release skipped: job $jobId is ${existing.state}, not SEALED/FINISH")
            return existing.toDomain()
        }
        // A28 review follow-up: the caller decided to release
        // (CloudHostedLocalRelease -> shouldReleaseLocalPagesAfterUpload) and
        // then made another round trip before landing here. A Download tap in
        // that window OR-merges intentLocal onto this very row — the user has
        // just asked for these pages on device, so re-read the intent right
        // before acting on it instead of trusting the caller's older snapshot.
        //
        // #C4a: the release-after-upload setting overrides that protection. A
        // dual-intent job (downloaded AND collected) is exactly what the ON
        // case must clear, so under `releaseAfterUpload` we do NOT bail on
        // intentLocal. With the setting OFF (the default, and every A44 call
        // site) the guard is byte-for-byte the old behaviour.
        if (existing.intentLocal && !releaseAfterUpload) {
            DiagLog.i("CloudSync", "release skipped: job $jobId gained a local download intent — keeping pages")
            return existing.toDomain()
        }
        val identity = GalleryIdentityV1(
            providerNamespace = existing.providerNamespace,
            canonicalItemId = existing.canonicalItemId,
            fallbackUrlDerived = existing.fallbackUrlDerived,
        )
        // #E25: a serialized chapter's job identity is the series. Gallery jobs
        // keep the series-wide removal and the series identity for bytes.
        // A chapter removes only its own availability and deletes under storageIdentity.
        val job = existing.toDomain()
        val bytesIdentity = job.storageIdentity
        // A28 review follow-up: the shelf pointer goes FIRST. Deleting the
        // catalog tree first left a window in which Library still advertised
        // an on-device copy of files that were already gone. Removing
        // availability up front means a later failure can only understate
        // availability (files still there, shelf silent) — recoverable, and
        // never the direction that shows the user a broken row.
        if (job.isSerializedChapter) {
            libraryAvailability?.removeChapterLocalAvailability(identity, setOf(job.chapterIdentity))
        } else {
            libraryAvailability?.removeDownloadLocalAvailability(identity)
        }
        if (!deleteCatalogTree(bytesIdentity, existing.downloadRevision)) {
            DiagLog.w(
                "CloudSync",
                "release aborted: catalog tree delete failed job=$jobId r${existing.downloadRevision}; " +
                    "pages kept on disk, Library local-availability already removed",
            )
            return existing.toDomain()
        }
        readyPageStore?.deletePublishedRevision(bytesIdentity, existing.downloadRevision)
        dao.deleteReadyPagesForJob(jobId)
        DiagLog.i("CloudSync", "released local pages job=$jobId ${redactIdentity(identity)} r${existing.downloadRevision}")
        return updateState(jobId, DownloadJobState.NONE)
    }

    /**
     * First successful manifest may pin [DownloadJob.expectedPageCount] when
     * still null. Once pinned, a later upstream disagreement produces FAILED
     * (spec §4.4) — never a silently shrunken FINISH or overwritten pin.
     */
    /**
     * First successful manifest may pin [DownloadJob.expectedPageCount] when
     * still null. Once pinned, a later upstream disagreement no longer
     * hard-fails unconditionally (S3 Task 9 fifth defect, owner bug backlog:
     * a mismatch used to write `state=FAILED` with `failureClass`/
     * `failureDetail` both left null forever — invisible in logs/UI, and
     * [DownloadWorkerLoop.processJob] just returns on seeing FAILED, so the
     * job died with no trace):
     *  - zero READY pages for the current revision: nothing fetched yet to
     *    protect, so just re-pin to the new upstream count and continue.
     *  - upstreamCount > pinned: the gallery grew; already-fetched pages
     *    under the old (smaller) pin stay valid — extend the pin and continue.
     *  - upstreamCount < pinned with pages already on device: a suspicious
     *    manifest read (could be a transient partial/interstitial page, not a
     *    real shrink). The FIRST such short count parks the job — same state,
     *    marked with [PAGE_COUNT_MISMATCH_PENDING] and a short
     *    `resumeNotBeforeMs` push — so [DownloadWorkerLoop] retries the
     *    manifest fetch instead of proceeding with a short/stale plan this
     *    tick; only a SECOND CONSECUTIVE short count (this marker still
     *    present) hard-fails with `failureClass = "PAGE_COUNT_MISMATCH"` and
     *    `failureDetail = "pinned=<p> upstream=<u>"`. Every non-agreement path
     *    logs via `DiagLog.w("DownloadPump", …)` so this can never be silent
     *    again.
     */
    suspend fun applyUpstreamPageCount(jobId: String, upstreamCount: Int): DownloadJob {
        val existing = dao.jobById(jobId) ?: error("unknown job $jobId")
        val pinned = existing.expectedPageCount
        if (pinned == null) {
            val updated = existing.copy(
                expectedPageCount = upstreamCount,
                updatedAtMillis = clock(),
            )
            dao.updateJob(updated)
            return updated.toDomain()
        }
        if (pinned == upstreamCount) {
            if (existing.failureClass == PAGE_COUNT_MISMATCH_PENDING) {
                // A good read resolved the earlier short-count strike — clear
                // it so a LATER unrelated short count starts fresh instead of
                // being treated as an already-second consecutive strike.
                val cleared = existing.copy(
                    failureClass = null,
                    failureDetail = null,
                    updatedAtMillis = clock(),
                )
                dao.updateJob(cleared)
                return cleared.toDomain()
            }
            return existing.toDomain()
        }
        if (existing.state != DownloadJobState.WAIT.name &&
            existing.state != DownloadJobState.DOWNLOAD.name
        ) {
            return existing.toDomain()
        }

        val detail = "pinned=$pinned upstream=$upstreamCount"
        val readyCount = dao.readyOrdinals(jobId, existing.downloadRevision).size

        if (readyCount == 0) {
            DiagLog.w("DownloadPump", "job $jobId re-pinning expectedPageCount: $detail (0 READY pages)")
            val updated = existing.copy(
                expectedPageCount = upstreamCount,
                failureClass = null,
                failureDetail = null,
                updatedAtMillis = clock(),
            )
            dao.updateJob(updated)
            return updated.toDomain()
        }

        if (upstreamCount > pinned) {
            DiagLog.w("DownloadPump", "job $jobId extending expectedPageCount: $detail (gallery grew)")
            val updated = existing.copy(
                expectedPageCount = upstreamCount,
                failureClass = null,
                failureDetail = null,
                updatedAtMillis = clock(),
            )
            dao.updateJob(updated)
            return updated.toDomain()
        }

        // upstreamCount < pinned with pages already on device.
        if (existing.failureClass == PAGE_COUNT_MISMATCH_PENDING) {
            DiagLog.w("DownloadPump", "job $jobId FAILED PAGE_COUNT_MISMATCH: $detail (repeat short count)")
            val failed = existing.copy(
                state = DownloadJobState.FAILED.name,
                failureClass = "PAGE_COUNT_MISMATCH",
                failureDetail = detail,
                updatedAtMillis = clock(),
            )
            dao.updateJob(failed)
            return failed.toDomain()
        }
        DiagLog.w("DownloadPump", "job $jobId manifest short count, retrying once: $detail")
        val policy = SourceThrottlePolicy.forNamespace(existing.providerNamespace)
        // #C17 audit A2: a retry park reads the BACKOFF base, never the healthy-path spacing.
        val pending = existing.copy(
            failureClass = PAGE_COUNT_MISMATCH_PENDING,
            failureDetail = detail,
            resumeNotBeforeMs = clock() + policy.jitterBackoffBaseMs,
            updatedAtMillis = clock(),
        )
        dao.updateJob(pending)
        return pending.toDomain()
    }

    /**
     * Atomic FINISH: all expected ordinals READY + Library local-availability
     * commit in the same Room transaction (spec §§4.4, 4.6). Returns the
     * unchanged job when FINISH is not yet allowed.
     */
    suspend fun tryFinish(
        jobId: String,
        downloadLocationUri: String,
        title: String,
        pages: List<DocumentRef>,
        /** A32 §2: catalog facts from the locator; empty ⇒ commit exactly as before. */
        metadata: DownloadCommitMetadata = DownloadCommitMetadata(),
    ): DownloadJob {
        val library = libraryAvailability
            ?: error("RoomDownloadJobStore.tryFinish requires DownloadLibraryAvailability")
        val committed = runInFinishTransaction {
            val existing = dao.jobById(jobId) ?: error("unknown job $jobId")
            if (existing.state != DownloadJobState.WAIT.name &&
                existing.state != DownloadJobState.DOWNLOAD.name
            ) {
                return@runInFinishTransaction existing.toDomain()
            }
            val expected = existing.expectedPageCount
            if (expected == null || expected <= 0) {
                return@runInFinishTransaction existing.toDomain()
            }
            val ready = dao.readyOrdinals(jobId, existing.downloadRevision).toSet()
            val allReady = (0 until expected).all { it in ready }
            if (!allReady) {
                return@runInFinishTransaction existing.toDomain()
            }
            require(pages.size >= expected) {
                "FINISH page list must cover pinned expectedPageCount"
            }
            val identity = GalleryIdentityV1(
                providerNamespace = existing.providerNamespace,
                canonicalItemId = existing.canonicalItemId,
                fallbackUrlDerived = existing.fallbackUrlDerived,
            )
            if (!existing.isSerializedChapter) {
                library.commitDownloadLocalAvailability(
                    identity = identity,
                    downloadLocationUri = downloadLocationUri,
                    downloadRevision = existing.downloadRevision,
                    title = title,
                    pages = pages.take(expected),
                    metadata = metadata,
                )
            } else {
                library.commitChapterDownloadLocalAvailability(
                    series = identity,
                    chapterId = existing.chapterIdentity,
                    downloadLocationUri = downloadLocationUri,
                    downloadRevision = existing.downloadRevision,
                    title = title,
                    pages = pages.take(expected),
                    completeness = DownloadCommitCompleteness.Complete,
                )
            }
            val finished = existing.copy(
                state = DownloadJobState.FINISH.name,
                // P1 (polish backlog): FINISH is a success. The row used to
                // keep whatever transient class the last blip recorded (e.g.
                // JITTER from a page that was retried successfully right
                // after), so a completed download still explained itself as
                // "temporary network issue" forever.
                failureClass = null,
                failureDetail = null,
                // A36 review O2: a FINISH fetched every pinned page, so it has
                // no truncation point. A row revived from a truncated seal
                // would otherwise carry the old `k` into the Detail note and
                // into the cloud upload row's CloudComicMeta.
                sealedTruncatedAt = null,
                updatedAtMillis = clock(),
            )
            dao.updateJob(finished)
            finished.toDomain()
        }
        if (committed.state == DownloadJobState.FINISH) reclaimStagingForFinishedJob(committed.id)
        return committed
    }

    /** C2 compensation: no cloud dependency, and no filesystem side effects
     * inside the FINISH transaction. A complete formal revision is required,
     * not merely a directory or stale FINISH state. */
    suspend fun reclaimStagingForFinishedJob(jobId: String): Boolean {
        val root = filesDir ?: return false
        val pages = readyPageStore ?: return false
        return try {
            val job = dao.jobById(jobId)?.toDomain() ?: return false
            if (job.state != DownloadJobState.FINISH) return false
            val expected = job.expectedPageCount ?: return false
            val committed = if (job.isSerializedChapter) {
                libraryAvailability?.chapterDownloadCommitted(
                    series = job.identity,
                    chapterId = job.chapterIdentity,
                    downloadRevision = job.downloadRevision,
                ) == true
            } else {
                (libraryAvailability?.downloadOnDeviceAssociationCount(job.identity) ?: 0) > 0
            }
            if (!committed) return false
            val formal = resolveLocalCatalogPagesForIngest(root, job.storageIdentity, job.downloadRevision, expected)
                ?: return false
            // Recheck after the suspending Library lookup, before unlinking.
            val fresh = dao.jobById(jobId) ?: return false
            if (fresh.state != DownloadJobState.FINISH.name || fresh.downloadRevision != job.downloadRevision) return false
            pages.releaseStagingAfterCommit(job.storageIdentity, job.downloadRevision, formal.map { it.toPath() })
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DiagLog.w("DownloadPump", "C2 staging cleanup deferred; formal download remains intact", error)
            false
        }
    }

    suspend fun reclaimStagingForFinishedJobs(): Int {
        var released = 0
        for (job in dao.allJobs()) {
            if (job.state == DownloadJobState.FINISH.name && reclaimStagingForFinishedJob(job.id)) released++
        }
        return released
    }

    /**
     * #C19-F12: remove a job ROW outright (not NONE). Only the
     * `opds:opds:` double-namespace sweep ([OpdsDoubleNamespaceCleanup]) uses
     * it; the files and the Library association are the caller's to drop
     * first, per row, the way `AppGraph.deleteDownloadedComic` does.
     */
    suspend fun purgeJob(jobId: String) {
        dao.deleteReadyPagesForJob(jobId)
        dao.deleteJobById(jobId)
    }

    /** The row-level twin of [DownloadJob.isSerializedChapter]. */
    private val DownloadJobEntity.isSerializedChapter: Boolean
        get() = chapterIdentity != GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID

    private fun DownloadJobEntity.toDomain(): DownloadJob = DownloadJob(
        id = id,
        identity = GalleryIdentityV1(
            providerNamespace = providerNamespace,
            canonicalItemId = canonicalItemId,
            fallbackUrlDerived = fallbackUrlDerived,
        ),
        state = DownloadJobState.valueOf(state),
        expectedPageCount = expectedPageCount,
        downloadRevision = downloadRevision,
        enqueuedAtMillis = enqueuedAtMillis,
        updatedAtMillis = updatedAtMillis,
        intentLocal = intentLocal,
        intentCloud = intentCloud,
        sealedTruncatedAt = sealedTruncatedAt,
        failureClass = failureClass,
        failureDetail = failureDetail,
        resumeNotBeforeMs = resumeNotBeforeMs,
        autoRetryAttempts = autoRetryAttempts,
        lastAutoRetryAtMs = lastAutoRetryAtMs,
        dismissedAtMillis = dismissedAtMillis,
        chapterIdentity = chapterIdentity,
    )
}
