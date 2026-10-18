package com.easemic.app.feature.collect

import com.easemic.app.sync.CloudCollectAction
import com.easemic.app.sync.CloudCollectLifecycle

/**
 * Outcome of the per-bookmark catch-up sweep decision (loyal-pipelines spec
 * review fix for the slice C1 HIGH regression: the non-empty rebind gate
 * over-blocked the WHOLE catch-up sweep, stranding Slice-A's FAILED
 * cloud-sync-row recovery on any established, non-empty bucket).
 */
enum class CloudCatchUpSweepAction { SKIP, RECOLLECT, DROP_BOOKMARK }

/**
 * Per-chapter catch-up decision for a serialized follow. Failed queue rows are
 * retried on their own, while an absent row is recreated only when the shared
 * cloud-aware chapter predicate says that neither local nor cloud state exists.
 * The callback is evaluated for this chapter only, so a sibling's cloud copy
 * cannot suppress its retry or repair.
 */
suspend fun serializedChapterCatchUpAction(
    queueStage: CloudSyncStage?,
    ingestJobActive: Boolean,
    dismissed: Boolean = false,
    shouldQueueChapter: suspend () -> Boolean,
): CloudCatchUpSweepAction {
    if (dismissed || ingestJobActive) return CloudCatchUpSweepAction.SKIP
    if (queueStage == CloudSyncStage.FAILED) return CloudCatchUpSweepAction.RECOLLECT
    if (queueStage != null) return CloudCatchUpSweepAction.SKIP
    return if (shouldQueueChapter()) {
        CloudCatchUpSweepAction.RECOLLECT
    } else {
        CloudCatchUpSweepAction.SKIP
    }
}

/**
 * Pure per-bookmark decision that the catch-up sweep used to make inline in
 * one coupled loop (`PrivateComicLibraryApp.runCloudCatchUpSweep`). Splits
 * what were really two independent jobs:
 *
 *  - **Job A — FAILED-row retry.** A bookmark whose current cloud-sync queue
 *    row is [CloudSyncStage.FAILED] (and not already relaunching) is always
 *    safe to retry: that row is current-bucket by construction — only this
 *    device's own ingest against the CURRENTLY bound bucket ever writes it.
 *    Safe on ANY routing, including an unresolved/foreign rebind target.
 *  - **Job B — absent-bookmark reconcile.** Uploads bookmarks genuinely
 *    missing from the bucket's authoritative index (or restores ones sitting
 *    in its trash). Only safe against a bucket this device has adopted as
 *    its own — an un-adopted/foreign bucket would otherwise get flooded with
 *    the device's entire collected set (smoke finding #13).
 *
 * [failedRowsOnly] selects job A alone; the caller is expected to pass
 * `true` for any not-yet-adopted/foreign routing and `false` only for a
 * full reconcile against an adopted bucket.
 *
 * [knownSyncedToBucket] is the durable per-comic sync ledger short-circuit
 * (S3 Task 1, owner bug C3/A3): when the caller has a durable record that this
 * comic is already uploaded to the CURRENTLY bound bucket, the answer is
 * SKIP — before every other rule, including an `presentInIndex=false`
 * caused by a transient cold-start R2 re-list failure and including a lingering
 * FAILED queue row — EXCEPT the trash rule in job B's scope (#C56 send-back 3,
 * spec 4.3 third amendment 2026-09-22, found by the emulator E2E `tombstone`
 * phase): a fresh [hasTrash] hit is a positive fact from the same listing
 * that fed [presentInIndex], not the transient read failure this
 * short-circuit exists to tolerate, so it now precedes this rule instead of
 * being shadowed by it — see the [hasTrash] paragraph below. A known-synced
 * comic must never be re-collected/re-uploaded just because one startup read
 * failed; a stale FAILED row is a zombie for a later task to clear, not a
 * reason to re-upload.
 *
 * [dismissed] is A45's durable suppression ([CloudSyncDismissalStore]):
 * the user explicitly cleared this row from "Needs attention". Dropping the
 * queue row alone would not hold, because a dismissed row then looks exactly
 * like an absent bookmark (`queueStage = null`) and job B would re-collect it
 * on the next launch — the very resurrection the dismissal is meant to prevent.
 * An explicit dismissal therefore outranks every rule below it, including job
 * A's always-safe FAILED-row retry. It suppresses the failed attempt, not the
 * comic: any explicit new user action clears the entry and a genuinely new row
 * is free to appear.
 *
 * #C56 send-back R2 (spec 4.3 amended 2026-09-22): [hasTrash] is a
 * TOMBSTONE, not a restore signal, and now outranks EVEN a still-[presentInIndex]
 * card — with adoption, every device bound to this bucket holds a bookmark
 * for every comic, so a comic trashed on device A must not keep restoring on
 * device B just because device B's own index snapshot still shows it present
 * (a lag, not a live fact). [CloudCatchUpSweepAction.DROP_BOOKMARK] removes
 * the local bookmark instead of job B's old RESTORE/re-upload. Superseded:
 * the previous "known limit" here (hasTrash + presentInIndex still restored)
 * is gone — `fullReconcileRestoresAnUnqueuedTrashedBookmarkEvenIfAlsoPresent`
 * is now `fullReconcileDropsAnUnqueuedTrashedBookmarkEvenIfAlsoPresent`.
 *
 * #C56 send-back 2 S2 (spec 4.3, second amendment 2026-09-22): trash now
 * ALSO outranks job A's own FAILED-row retry — a comic uncollected elsewhere
 * must not keep being retried by this device's queue just because
 * [failedRowsOnly] restricts the rest of job B. This is decided by a
 * dedicated `hasTrash && isFailedRow` check ABOVE both the [failedRowsOnly]
 * gate and the plain FAILED-row rule, so it fires in either mode.
 * `failedRowsOnly=true` still deliberately never runs job B's
 * absent-bookmark reconcile — that guarantee is preserved.
 *
 * #C56 send-back 3 (spec 4.3, third amendment 2026-09-22): the unconditional
 * job-B trash rule that used to sit below, after the [failedRowsOnly] gate,
 * has MOVED above [knownSyncedToBucket] (see that paragraph above) so a
 * fresh trash hit also drops a known-synced bookmark — the emulator E2E
 * `tombstone` phase found a comic THIS device uploaded and another device
 * then uncollected was never dropped otherwise. It stays conditioned on
 * `!failedRowsOnly`, exactly as its old position already implied (it used to
 * be unreachable whenever [failedRowsOnly] was true, because that gate
 * returned first): moving it unconditionally would flip
 * `failedRowsOnlyNeverUploadsAnAbsentBookmark`'s
 * `queueStage=null, hasTrash=true, failedRowsOnly=true` case from SKIP to
 * DROP_BOOKMARK — a second, un-authorized pre-existing test this brief does
 * not name.
 */
fun catchUpSweepAction(
    knownSyncedToBucket: Boolean,
    queueStage: CloudSyncStage?,
    ingestJobActive: Boolean,
    presentInIndex: Boolean,
    hasTrash: Boolean,
    failedRowsOnly: Boolean,
    dismissed: Boolean = false,
): CloudCatchUpSweepAction {
    if (dismissed) return CloudCatchUpSweepAction.SKIP
    if (ingestJobActive) return CloudCatchUpSweepAction.SKIP
    // #C56 send-back 3: job B's trash tombstone now precedes even the sync
    // ledger short-circuit below — see the kdoc paragraphs above. This is
    // the same unconditional job-B trash rule that used to live further
    // down (after the failedRowsOnly gate); it is redundant there now.
    if (!failedRowsOnly && hasTrash) return CloudCatchUpSweepAction.DROP_BOOKMARK
    if (knownSyncedToBucket) return CloudCatchUpSweepAction.SKIP
    val isFailedRow = queueStage == CloudSyncStage.FAILED
    // #C56 send-back 2 S2: trash tombstones a FAILED row job A would
    // otherwise retry, in EITHER mode — see the kdoc paragraph above.
    if (hasTrash && isFailedRow) return CloudCatchUpSweepAction.DROP_BOOKMARK
    if (failedRowsOnly) {
        return if (isFailedRow) CloudCatchUpSweepAction.RECOLLECT else CloudCatchUpSweepAction.SKIP
    }
    if (isFailedRow) return CloudCatchUpSweepAction.RECOLLECT
    if (queueStage != null) {
        // WAITING/FETCHING/UPLOADING: already in flight, never relaunch.
        return CloudCatchUpSweepAction.SKIP
    }
    val action = CloudCollectLifecycle.catchUpAction(
        bookmarked = true,
        hasActive = presentInIndex,
        hasTrash = hasTrash,
    )
    return if (action == CloudCollectAction.SKIP_INGEST) {
        CloudCatchUpSweepAction.SKIP
    } else {
        CloudCatchUpSweepAction.RECOLLECT
    }
}
