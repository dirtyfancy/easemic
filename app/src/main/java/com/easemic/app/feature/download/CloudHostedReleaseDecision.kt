package com.easemic.app.feature.download

import com.easemic.app.feature.browse.BrowseFeedRepository

/**
 * S4 Task 3 ("remote hosting", owner bug C2) + #C4a (owner 2026-09-10): once a
 * comic's upload is confirmed (the S3-T1 sync ledger row is present), whether
 * the device copy of its pages is released.
 *
 * `intentCloud` and a terminal "pages are on device" state — SEALED
 * (pure-cloud completion) or FINISH — are hard requirements under BOTH
 * settings. The FINISH branch is defensive only: `DownloadWorkerLoop` never
 * FINISHes a job without intentLocal, so today a pure-cloud job always arrives
 * here SEALED. A job still fetching, FAILED, GONE, or already NONE is never
 * touched, which is also what makes a re-run of either sweep a no-op.
 *
 * [releaseAfterUpload] is the user's `CloudReleaseAfterUploadStore` value, and
 * the only thing it changes is how a DUAL-INTENT job is treated:
 *
 * | intentCloud | intentLocal | setting | released |
 * |-------------|-------------|---------|----------|
 * | true        | false       | OFF     | yes      |
 * | true        | false       | ON      | yes      |
 * | true        | true        | OFF     | **no**   |
 * | true        | true        | ON      | **yes**  |
 * | false       | any         | either  | no       |
 *
 * OFF (the default) is byte-identical to the pre-#C4a behavior: a dual-intent
 * job keeps its pages, because tapping Collect physically downloads first and
 * then uploads, so the comic really is in both places and both icons are
 * solid. ON means the user asked for cloud-only storage — that local intent is
 * HOW the upload got its bytes, not a standing request to keep them — so the
 * copy goes and only the cloud icon is ever shown.
 *
 * The bottom row is the invariant the setting must never cross: a comic with
 * no cloud intent was never uploaded from here, so releasing it would delete
 * the only copy. Binding a cloud library does not upload a download-only
 * comic, and this setting does not clear one.
 *
 * Defaults to OFF so a caller that has not been migrated to pass the setting
 * keeps today's keep-the-pages behavior instead of silently inheriting a delete.
 */
fun shouldReleaseLocalPagesAfterUpload(
    job: DownloadJob?,
    releaseAfterUpload: Boolean = false,
): Boolean {
    if (job == null) return false
    if (!job.intentCloud) return false
    if (job.intentLocal && !releaseAfterUpload) return false
    return job.state == DownloadJobState.SEALED || job.state == DownloadJobState.FINISH
}

/**
 * Resolves the download job identity behind a cloud sync row's acquisition
 * (`CloudSyncQueueItem.id`, the value `CloudIngestRunner` passes to
 * `onSynced` as `sourceRowAcquisition`): `ehentai://<gid>/<token>` →
 * EHENTAI/gid (token dropped, as in `AppGraph.identityForCloudIngest`),
 * `nhentai://<gid>` → NHENTAI/gid, `r2source://<fp>/<oldId>` →
 * `r2SourceNamespace(fp)`/oldId (slice-C2 migration rows, as in
 * `AppGraph.r2SourceLocalPagesForIngest`). Null for anything else.
 *
 * OPDS (#C4a, closed 2026-09-18): an OPDS acquisition is a plain `http(s)://`
 * URL, and the namespace half of its identity — `opds:<providerConfigId>`, a
 * generated UUID — appears nowhere in it, so the URL alone never resolves one.
 * The upload row carries that half instead: `cloudUploadRowForJob` copies the
 * job identity's `providerNamespace`/`canonicalItemId` onto the row and
 * `CloudIngestRunner` forwards both into `onSynced`, so the [providerNamespace]
 * arm below rebuilds the identity exactly — device-proven by
 * `C20OpdsDownloadedCloudIngestTest`. Guessing a namespace from the URL stays out.
 */
fun downloadJobIdentityForCloudAcquisition(
    acquisition: String,
    providerNamespace: String? = null,
    canonicalItemId: String? = null,
): GalleryIdentityV1? {
    val trimmed = acquisition.trim()
    if (trimmed.isEmpty()) return null
    if (providerNamespace?.startsWith("opds:") == true && !canonicalItemId.isNullOrBlank()) {
        return GalleryIdentityV1(
            providerNamespace = providerNamespace,
            canonicalItemId = canonicalItemId,
        )
    }
    R2SourceAcquisition.parse(trimmed)?.let { (oldFingerprint, oldCloudComicId) ->
        return GalleryIdentityV1(
            providerNamespace = GalleryIdentityV1.r2SourceNamespace(oldFingerprint),
            canonicalItemId = oldCloudComicId,
        )
    }
    BrowseFeedRepository.parseEhentaiAcquisition(trimmed)?.let { (galleryId, _) ->
        return GalleryIdentityV1(
            providerNamespace = GalleryIdentityV1.EHENTAI,
            canonicalItemId = galleryId.toString(),
        )
    }
    BrowseFeedRepository.parseNhentaiAcquisition(trimmed)?.let { galleryId ->
        return GalleryIdentityV1(
            providerNamespace = GalleryIdentityV1.NHENTAI,
            canonicalItemId = galleryId.toString(),
        )
    }
    return null
}
