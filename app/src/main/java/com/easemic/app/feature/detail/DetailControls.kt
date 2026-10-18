package com.easemic.app.feature.detail

import com.easemic.app.feature.browse.CollectIcon
import com.easemic.app.feature.browse.DetailSwitchFacts
import com.easemic.app.feature.browse.DetailSwitchState
import com.easemic.app.feature.browse.DownloadIcon
import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.FetchFailureClass
import com.easemic.app.feature.download.label

/**
 * A32 §3 — what a [DetailSubject.Local] may honestly offer. Pure, so the
 * matrix is a table test rather than a screenshot.
 *
 * Two rules do the work:
 *  - Collect is offered only when there is an upstream to collect
 *    (`acquisitionKey != null`). An imported folder has none, and hiding is
 *    honest where disabling would be decorative.
 *  - A downloaded catalog row exposes one delete verb from its solid download
 *    switch. The confirmation dialog is the only destructive step; the old
 *    overflow menu is no longer a second path to the same action.
 */
data class DetailControls(
    val primaryLabel: String,
    val startOrdinal: Int,
    val primaryEnabled: Boolean,
    /** null ⇒ the switch is not rendered at all. */
    val collectIcon: CollectIcon?,
    /** null ⇒ the switch is not rendered at all. */
    val downloadIcon: DownloadIcon?,
    val downloadInteractive: Boolean,
    val downloadLocked: Boolean = false,
    val deleteDownloadVisible: Boolean,
    /**
     * null ⇒ no helper line at all. A30 §2.4: when the files left the phone,
     * "Stored on this device." would contradict the block right above it.
     */
    val helper: String?,
    /**
     * P-a — set only when this download SEALED short of its pinned page count.
     * The row is complete as far as it goes and the shelf shows it, so the one
     * honest thing left to do is say how far it got and why it stopped.
     */
    val truncationNote: String? = null,
) {
    companion object {
        /**
         * @param collectTargetResolved whether the caller found a real collect
         *   target for this row (a download locator carrying the upstream URL,
         *   see `detailCollectFeedItem`). False ⇒ no Collect switch: J1's
         *   synthesised-from-identity item wrote bookmarks nothing else in the
         *   app could match, and a switch that writes a broken bookmark is
         *   worse than no switch. Defaults to true so the pure matrix rows below
         *   read as the spec table; every production caller passes it.
         * @param cloudQueued C4 §2.1: this comic's upload is in flight
         *   (`cloudUploadInFlight` over the live sync rows). The download
         *   switch keeps its truthful SOLID icon but stops being a verb —
         *   its only tap is "Delete download?", and deleting now unlinks the
         *   catalog tree the running upload is still reading.
         */
        fun of(
            subject: DetailSubject.Local,
            cloudBound: Boolean,
            collectTargetResolved: Boolean = true,
            cloudQueued: Boolean = false,
        ): DetailControls {
            val item = subject.item
            val startOrdinal = item.progressOrdinal.coerceAtLeast(0)
            // A30 §2.4: the files-gone block already says the comic is not here.
            // A lost grant is different — the files are presumed fine — so the
            // helper stays true and stays.
            val filesGone = !item.isAvailable && !subject.sourceAccessLost
            if (!subject.isCatalogDownload) {
                return DetailControls(
                    primaryLabel = LocalDetailCopy.readLabel(startOrdinal),
                    startOrdinal = startOrdinal,
                    primaryEnabled = item.isAvailable,
                    collectIcon = null,
                    downloadIcon = null,
                    downloadInteractive = false,
                    deleteDownloadVisible = false,
                    helper = LocalDetailCopy.display(LocalDetailCopy.IMPORT_HELPER).takeUnless { filesGone },
                )
            }
            val switchState = DetailSwitchState.from(
                DetailSwitchFacts(
                    cloudBound = cloudBound,
                    collected = subject.collected || subject.inCloud,
                    uploaded = subject.inCloud,
                    downloadState = subject.downloadState,
                    cloudQueued = cloudQueued,
                    delistedAtSource = subject.delistedAtSource,
                ),
            )
            return DetailControls(
                primaryLabel = LocalDetailCopy.readLabel(startOrdinal),
                startOrdinal = startOrdinal,
                primaryEnabled = item.isAvailable,
                // J2: an OPDS acquisition is not http, so the cloud-ingest guard
                // refuses it downstream -- but only after a bookmark was already
                // written. Two independent gates, both honest: the namespace must
                // support collecting, and the caller must have a real target.
                collectIcon = switchState.collectIcon.takeIf {
                    collectTargetResolved && isCollectableNamespace(subject.acquisitionNamespace)
                },
                // Always SOLID: this subject exists because the pages are on disk.
                downloadIcon = DownloadIcon.SOLID,
                downloadInteractive = !switchState.downloadLocked,
                downloadLocked = switchState.downloadLocked,
                deleteDownloadVisible = true,
                helper = LocalDetailCopy.display(LocalDetailCopy.DOWNLOAD_HELPER).takeUnless { filesGone },
                // J7: `fetched` is the row's REAL page count, never the job's
                // recorded truncation point -- if the two ever disagree, the
                // pages on disk are the fact and the job record is a memory.
                truncationNote = subject.truncatedSeal?.let { seal ->
                    LocalDetailCopy.truncatedSealLine(
                        fetched = item.pageCount,
                        expected = seal.expected,
                    ).takeIf { item.pageCount in 1 until seal.expected }
                },
            )
        }
    }
}

/**
 * P-a — a download that stopped short because the source withdrew the rest.
 * [expected] is the count pinned at enqueue. [fetched] is what the job recorded
 * as its truncation point; it is kept for diagnosis only — the rendered line
 * counts the Library row's real pages instead (J7), so the screen can never
 * report a number the shelf and the reader would contradict.
 */
data class DetailTruncatedSeal(val fetched: Int, val expected: Int)

/**
 * P-a — the one place a [DownloadJob] is read as "this download is short, and
 * the source is why". Deliberately narrow:
 *  - SEALED only. A FINISH job fetched everything; a WAIT/DOWNLOAD job is still
 *    trying, and calling it short would be a verdict the pump has not reached.
 *  - `failureClass == GONE` only. Both truncation wind-down sites in
 *    `DownloadWorkerLoop` stamp the D1 double-404 verdict onto the seal, so a
 *    seal blaming anything else has no source statement to quote.
 *  - a real count on both sides, and `fetched < expected`. Without the pinned
 *    count there is nothing to compare against, and equal counts are complete.
 *
 * **Reachability (A36).** This returns non-null only for a SEALED job, which
 * reaches a Detail screen only when that job owns a Library row. A36 is what
 * gives it one: `RoomDownloadJobStore.sealTruncatedWithLocalPages` commits the
 * k pages the GONE wind-down did fetch as an INCOMPLETE row, so this note is
 * live copy on the owner's Kyouei-shaped case (22 of 43). It stays narrow —
 * a SEALED job with no row (a cloud-only fetch) still has no Detail screen to
 * render on, and nothing else may assume a SEALED job has a row.
 */
fun detailTruncatedSeal(job: DownloadJob?): DetailTruncatedSeal? {
    if (job == null || job.state != DownloadJobState.SEALED) return null
    if (job.failureClass != FetchFailureClass.GONE.label()) return null
    val fetched = job.sealedTruncatedAt ?: return null
    val expected = job.expectedPageCount ?: return null
    if (fetched >= expected) return null
    return DetailTruncatedSeal(fetched = fetched, expected = expected)
}

/**
 * A36 — which download-job states the Detail ··· "Delete download" may act on.
 *
 * FINISH is the ordinary complete download. SEALED joins it because a
 * truncated wind-down now commits its k pages as an INCOMPLETE Library row
 * (`RoomDownloadJobStore.sealTruncatedWithLocalPages`): the comic is on the
 * shelf and readable, so A30 §2.3's single file-deleting verb has to be able
 * to take it back off. Anything else — still fetching, FAILED, GONE, already
 * NONE — has no committed row for this verb to remove, and the store's own
 * guard refuses a SEALED job that does not own one, so a cloud-only seal is
 * never deleted through a verb its Detail screen never offered.
 */
fun isDeletableDownloadJobState(state: DownloadJobState): Boolean =
    state == DownloadJobState.FINISH || state == DownloadJobState.SEALED
