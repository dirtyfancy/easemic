package com.easemic.app.feature.browse

import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.downloadJobOwnsLocalPages

data class DetailSwitchFacts(
    val cloudBound: Boolean,
    val collected: Boolean,
    val uploaded: Boolean,
    val downloadState: DownloadJobState? = null,
    val cloudQueued: Boolean = false,
    /**
     * A durable GONE verdict from the source (C9: `DownloadJob.isDelistedAtSource()`
     * — a zero-page GONE seal or a truncated seal under the GONE verdict). Owner
     * ruling 2026-09-16: it renders the slashed cloud whenever the item is
     * collected in a bound library and not yet uploaded — pages on the device
     * do not hide it (a truncated download is both downloaded and delisted). An
     * uploaded copy still wins: the cloud copy is the product promise.
     */
    val delistedAtSource: Boolean = false,
    /**
     * A36 review M2: whether the job's pages are on THIS phone
     * ([downloadJobOwnsLocalPages]). Defaults to what the state alone can
     * prove — FINISH — so every pre-existing caller behaves exactly as before;
     * a caller that also knows the job's intent and whether a Library row
     * exists passes the predicate's answer and a truncated A36 seal then reads
     * as downloaded instead of "not downloaded".
     */
    val downloadOwnsLocalPages: Boolean = downloadJobOwnsLocalPages(downloadState),
)

enum class CollectIcon {
    HEART_HOLLOW,
    HEART_SOLID,
    CLOUD_HOLLOW,
    CLOUD_PENDING,
    CLOUD_SOLID,
    CLOUD_BLOCKED,
}
enum class DownloadIcon { HOLLOW, IN_FLIGHT, SOLID }
enum class ReadRoute { ONLINE, CLOUD, LOCAL_DISK }

data class DetailSwitchState(
    val collectIcon: CollectIcon,
    val downloadIcon: DownloadIcon,
    val readRoute: ReadRoute,
    /**
     * C4 §2.1 — this comic's upload is in flight, so the download switch is
     * inert and dimmed. Interactivity only: the icons above stay truthful
     * (the pages ARE on the phone), and only the delete/cancel tap is withheld
     * — taking the pages away now unlinks the tree the running upload is
     * reading.
     */
    val downloadLocked: Boolean = false,
) {
    companion object {
        fun from(f: DetailSwitchFacts): DetailSwitchState {
            val collectIcon = when {
                !f.cloudBound -> if (f.collected) CollectIcon.HEART_SOLID else CollectIcon.HEART_HOLLOW
                !f.collected -> CollectIcon.CLOUD_HOLLOW
                f.delistedAtSource && !f.uploaded -> CollectIcon.CLOUD_BLOCKED
                f.uploaded -> CollectIcon.CLOUD_SOLID
                // Collected + cloud-bound but not yet uploaded (queued OR merely
                // pending): always the pending glyph (rendered filled black), never
                // the hollow "uncollected" glyph. Fixes the A6 render half so a
                // collected item can never look uncollected.
                else -> CollectIcon.CLOUD_PENDING
            }
            val downloadIcon = when {
                f.downloadOwnsLocalPages -> DownloadIcon.SOLID
                f.downloadState == DownloadJobState.WAIT ||
                    f.downloadState == DownloadJobState.DOWNLOAD -> DownloadIcon.IN_FLIGHT
                else -> DownloadIcon.HOLLOW
            }
            val readRoute = when {
                // A36: read from the pages we hold. Routing a truncated seal
                // ONLINE would send the user back to the source that just
                // returned 404 twice, past a copy already on the phone.
                f.downloadOwnsLocalPages -> ReadRoute.LOCAL_DISK
                f.uploaded -> ReadRoute.CLOUD
                else -> ReadRoute.ONLINE
            }
            return DetailSwitchState(collectIcon, downloadIcon, readRoute, downloadLocked = f.cloudQueued)
        }
    }
}
