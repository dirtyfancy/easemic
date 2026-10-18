package com.easemic.app.feature.browse

import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.GalleryIdentityV1

/**
 * C4 (found on the device, 2026-09-18): which download job a Browse / Cloud
 * detail drives — its switch, its Read route, its delete.
 *
 * A detail that came from the source (Browse) fetches under the ORIGIN
 * identity (`ehentai/<gid>`, `opds:<cfg>/<id>`, …). The moment its upload
 * lands the presence flips to CLOUD and the screen used to switch to the
 * `cloud_collection/<id>` identity (#15's stable-cloud rule) — under which no
 * job exists — so the pages it had just fetched read as "Not downloaded" and
 * the owner's OFF end state (solid cloud AND solid download) never showed;
 * the delete verb was aimed at a job that did not exist either.
 *
 * Rule: a job that still owns, or is still fetching, the pages keeps the
 * detail (FINISH / SEALED / WAIT / DOWNLOAD under the origin); a released
 * (NONE) or dead (FAILED / GONE) origin row hands the detail to the cloud
 * identity, so a later Download pulls the copy back from the cloud library
 * ("用户想拿回本地仍可点下载"). A live cloud job wins over a dead origin row.
 * Off the cloud route the cloud identity is never offered.
 */
object DetailDownloadIdentity {
    fun choose(
        originIdentity: GalleryIdentityV1?,
        cloudIdentity: GalleryIdentityV1?,
        originJob: DownloadJob?,
        cloudJob: DownloadJob?,
        presenceUsesCloud: Boolean,
    ): GalleryIdentityV1? {
        if (!presenceUsesCloud || cloudIdentity == null) return originIdentity ?: cloudIdentity
        if (cloudJob != null && cloudJob.state.holdsOrFetchesPages()) return cloudIdentity
        if (originIdentity != null && originJob != null && originJob.state.holdsOrFetchesPages()) return originIdentity
        return cloudIdentity
    }

    private fun DownloadJobState.holdsOrFetchesPages(): Boolean = when (this) {
        DownloadJobState.WAIT, DownloadJobState.DOWNLOAD, DownloadJobState.SEALED, DownloadJobState.FINISH -> true
        DownloadJobState.NONE, DownloadJobState.FAILED, DownloadJobState.GONE -> false
    }
}
