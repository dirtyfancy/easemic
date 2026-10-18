package com.easemic.app.data.room

import com.easemic.app.model.ShelfDownloadState

/**
 * #C9: the two shelf facts decoded from the projection's single joined
 * download-job row — the live download axis and whether the source has delisted
 * the comic.
 */
data class ShelfDownloadProjection(
    val downloadState: ShelfDownloadState,
    val delistedAtSource: Boolean,
)

/**
 * Decodes [ShelfRowEntity.downloadState] into the shelf's badge facts.
 *
 * Split out of the mapper so the rule is testable on the JVM: the end-to-end
 * SQL pin needs a device, but the decode runs on every build. Two rules carry
 * the weight:
 *
 *  - A63: `COMPLETE` still requires local pages, so a finished job whose files
 *    are gone stops claiming completeness.
 *  - #C9: 'DELISTED' (a GONE job) and 'COMPLETE_DELISTED' (a SEALED job whose
 *    failureClass is GONE — a truncated wind-down, owner ruling 2026-09-16)
 *    set [ShelfDownloadProjection.delistedAtSource]; the truncated one keeps
 *    its download axis. A FAILED job is a failure, not a takedown — the
 *    owner named both failure directions, and marking a live source dead
 *    forever is the worse one. A delisted comic with no local pages is also
 *    silent on the download axis: there is no live download left to
 *    announce.
 */
fun decodeShelfDownloadProjection(raw: String?, pageCount: Int): ShelfDownloadProjection = when (raw) {
    "QUEUED" -> ShelfDownloadProjection(ShelfDownloadState.QUEUED, delistedAtSource = false)
    "COMPLETE" -> ShelfDownloadProjection(
        downloadState = if (pageCount > 0) ShelfDownloadState.COMPLETE else ShelfDownloadState.NONE,
        delistedAtSource = false,
    )
    "COMPLETE_DELISTED" -> ShelfDownloadProjection(
        downloadState = if (pageCount > 0) ShelfDownloadState.COMPLETE else ShelfDownloadState.NONE,
        delistedAtSource = true,
    )
    "DELISTED" -> ShelfDownloadProjection(ShelfDownloadState.NONE, delistedAtSource = true)
    else -> ShelfDownloadProjection(ShelfDownloadState.NONE, delistedAtSource = false)
}
