package com.easemic.app.feature.collect

/**
 * C4 §2.1 — true when one of [acquisitions] has a cloud sync row that is not
 * FAILED: the comic is queued for, fetching for, or in the middle of an upload.
 *
 * The one predicate behind both Detail routes' locked download switch AND
 * `AppGraph.deleteDownloadedComic`'s refusal, because a UI lock is not a lock.
 * A running upload reads the formal catalog tree lazily and a FINISH job holds
 * no lease (C2's lease covers SEALED only), so deleting the download mid-upload
 * unlinks the pages under the pump: the manifest/meta/index are never written,
 * the local copy is gone, and every auto-resume then fails
 * `CLOUD_INGEST_PAGES_MISSING_POST_SEAL` until the row parks FAILED.
 *
 * FAILED does not count: that row is parked, nothing is reading the pages, and
 * the user must stay free to delete a download whose upload gave up. When
 * [cloudBound] is false, WAITING is also safe: no worker can start it without
 * a bound destination, and the successful local delete removes that row.
 * Blank acquisitions never match — a row with no id would otherwise lock every
 * comic whose own acquisition is unknown.
 */
fun cloudUploadInFlight(
    rows: List<CloudSyncQueueItem>,
    acquisitions: Set<String>,
    cloudBound: Boolean = true,
): Boolean {
    val ids = acquisitions.filter { it.isNotBlank() }.toSet()
    if (ids.isEmpty()) return false
    return rows.any { row ->
        row.id in ids &&
            row.stage != CloudSyncStage.FAILED &&
            (cloudBound || row.stage != CloudSyncStage.WAITING)
    }
}
