package com.easemic.app.feature.download

import com.easemic.app.data.room.DownloadReadyPageEntity

/**
 * Builds queue rows from one consistent snapshot of jobs, READY rows, and
 * locators. READY pages are counted only when their persisted revision is the
 * job's current [DownloadJob.downloadRevision].
 *
 * The function has no storage or clock access; callers provide all snapshot
 * inputs and may supply the transfer-meter lookup separately.
 */
fun downloadQueueRows(
    jobs: List<DownloadJob>,
    readyPages: List<DownloadReadyPageEntity>,
    locators: Map<GalleryIdentityV1, DownloadLocator?>,
    bytesPerSecond: (DownloadJob) -> Long? = { null },
): List<DownloadQueueRow> {
    return jobs.map { job ->
        val ready = readyPages.count { page ->
            page.jobId == job.id && page.downloadRevision == job.downloadRevision
        }
        downloadQueueRow(
            job = job,
            locator = locators[job.storageIdentity],
            readyPages = ready,
            bytesPerSecond = bytesPerSecond(job),
        )
    }
}
