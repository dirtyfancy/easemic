package com.easemic.app.feature.collect

import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.GalleryIdentityV1

/**
 * Safe migration for queue rows written before opaque-provider identity was
 * persisted. A row is backfilled only when exactly one terminal download job
 * has the same stored acquisition URL; no URL parsing or namespace guessing
 * is performed. Rows with no unambiguous match are returned unchanged and
 * remain eligible for the normal unsupported-source failure copy.
 */
fun backfillCloudQueueIdentities(
    rows: List<CloudSyncQueueItem>,
    jobs: List<DownloadJob>,
    acquisitionFor: (GalleryIdentityV1) -> String?,
): List<CloudSyncQueueItem> {
    // Only a terminal cloud-intent job can be the producer of a stranded
    // upload row.  Locator equality alone is not enough: old rows may share
    // an acquisition URL with an active local-only download, and backfilling
    // from either would incorrectly turn that row into an upload-only
    // handoff.  Keep the migration fail-closed for every non-terminal or
    // local-only job.
    val matches = jobs
        .asSequence()
        .filter { job ->
            job.intentCloud &&
                (job.state == DownloadJobState.SEALED || job.state == DownloadJobState.FINISH)
        }
        .mapNotNull { job -> acquisitionFor(job.identity)?.let { it to job.identity } }
        .groupBy({ it.first }, { it.second })
    return rows.map { row ->
        if (!row.providerId.isNullOrBlank() && !row.canonicalItemId.isNullOrBlank()) {
            row
        } else {
            matches[row.id]
                ?.singleOrNull()
                ?.takeIf { row.acceptsIdentityBackfill(it.providerNamespace, it.canonicalItemId) }
                ?.let { identity ->
                    row.copy(
                        providerId = identity.providerNamespace,
                        canonicalItemId = identity.canonicalItemId,
                    )
                }
                ?: row
        }
    }
}

/**
 * Startup handoff-repair replay (spec §1.4 FIX1): a crash between a
 * cloud-intent download job reaching a terminal completion (SEALED, or
 * FINISH for a dual-intent collect+download whose [DownloadJob.intentLocal]
 * and [DownloadJob.intentCloud] were OR-merged onto one row) and its
 * [CloudSyncQueue] upload row actually being enqueued would strand the
 * already-fetched pages: a terminal job never re-enters the consumption
 * queue, so nothing else re-derives that row once the process that would
 * have enqueued it dies first.
 *
 * Returns the subset of [jobs] whose upload row needs to be re-enqueued:
 * cloud-intent, terminal (SEALED or FINISH), whose acquisition URL is
 * resolvable ([acquisitionFor] returns non-null — a job with no stored
 * locator can't be repaired), which does not already have a row in the
 * cloud queue for that acquisition ([hasCloudRow]), and which the durable
 * sync ledger does not already prove reached the cloud ([knownSynced]).
 * Callers still need to confirm the job's pages are actually resolvable on
 * device before firing the handoff (see
 * [com.easemic.app.AppGraph.isJobResolvableForDirectCloudHandoff]) —
 * that check needs real file I/O so it deliberately isn't folded in here to
 * keep this decision pure and unit-testable.
 *
 * S3 Task 6 (owner bug 2026-09-04 — "很多漫画已经橙云了，但依然在活动页出现、在跑同步"):
 * [knownSynced] closes the bigger of the two root causes. Before this param
 * existed, cold start re-derived an upload row for EVERY terminal
 * cloud-intent job on EVERY launch — including ones the ledger already
 * proves synced — because [hasCloudRow] only checks the *transient* queue,
 * which is empty again after a successful upload removes its row. `{ false }`
 * (the default) preserves every pre-existing call site's behavior unchanged.
 */
fun cloudHandoffRepairCandidates(
    jobs: List<DownloadJob>,
    hasCloudRow: (acquisition: String) -> Boolean,
    acquisitionFor: (identity: GalleryIdentityV1) -> String?,
    knownSynced: (job: DownloadJob) -> Boolean = { false },
): List<DownloadJob> = jobs.filter { job ->
    job.intentCloud &&
        (job.state == DownloadJobState.SEALED || job.state == DownloadJobState.FINISH) &&
        !knownSynced(job) &&
        acquisitionFor(job.identity)?.let { acquisition -> !hasCloudRow(acquisition) } == true
}

/**
 * Chapter-aware startup repair for serialized cloud handoffs. The gallery
 * repair above keys a row by acquisition alone; that is insufficient for a
 * series because every chapter shares the same series acquisition. Callers
 * resolve the locator from the complete [DownloadJob] (including its chapter
 * identity), and the cloud-row/ledger checks are then evaluated independently
 * for each chapter.
 */
fun cloudHandoffRepairChapterCandidates(
    jobs: List<DownloadJob>,
    hasCloudRow: (acquisition: String, chapterIdentity: String) -> Boolean,
    acquisitionFor: (job: DownloadJob) -> String?,
    knownSynced: (job: DownloadJob) -> Boolean = { false },
): List<DownloadJob> = jobs.filter { job ->
    job.intentCloud &&
        (job.state == DownloadJobState.SEALED || job.state == DownloadJobState.FINISH) &&
        !knownSynced(job) &&
        acquisitionFor(job)?.let { acquisition ->
            !hasCloudRow(acquisition, job.chapterIdentity)
        } == true
}

/** Result of [partitionUncollectedCloudHandoffCandidates]. */
data class CloudHandoffCandidatePartition(
    val collected: List<DownloadJob>,
    val notCollected: List<DownloadJob>,
)

/**
 * A31 (owner bug backlog): splits [candidates] (already filtered to
 * strandable terminal cloud-intent jobs by [cloudHandoffRepairCandidates])
 * into those still backed by a Collect bookmark ([CloudHandoffCandidatePartition.collected],
 * safe to hand to the normal [com.easemic.app.AppGraph.enqueueCloudUploadForJob]
 * flow) and those that are not ([CloudHandoffCandidatePartition.notCollected]).
 *
 * A candidate whose acquisition cannot even be resolved ([acquisitionFor]
 * returns null) is treated as not-collected too — [isCollected] is never
 * called without a resolvable acquisition, since there is nothing to check a
 * bookmark against.
 *
 * Before this fix, the cold-start repair sweep applied the SAME
 * `isAcquisitionCollected` gate as
 * [com.easemic.app.AppGraph.enqueueCloudUploadForJob] deep
 * inside [com.easemic.app.feature.collect.cloudUploadRowForJob]
 * — silently, on every single launch, forever, for a job whose Uncollect
 * should have discarded it (or dropped its cloud intent) the moment the
 * bookmark was removed. The caller is expected to apply
 * [applyUncollectJobOutcome] to [CloudHandoffCandidatePartition.notCollected]
 * BEFORE running the normal resolvable→enqueue flow on
 * [CloudHandoffCandidatePartition.collected], so the reconcile actually fixes
 * the stranded state instead of re-discovering it forever.
 */
fun partitionUncollectedCloudHandoffCandidates(
    candidates: List<DownloadJob>,
    acquisitionFor: (identity: GalleryIdentityV1) -> String?,
    isCollected: (job: DownloadJob, acquisition: String) -> Boolean,
): CloudHandoffCandidatePartition {
    val collected = mutableListOf<DownloadJob>()
    val notCollected = mutableListOf<DownloadJob>()
    for (job in candidates) {
        val acquisition = acquisitionFor(job.identity)
        if (acquisition != null && isCollected(job, acquisition)) {
            collected.add(job)
        } else {
            notCollected.add(job)
        }
    }
    return CloudHandoffCandidatePartition(collected = collected, notCollected = notCollected)
}
