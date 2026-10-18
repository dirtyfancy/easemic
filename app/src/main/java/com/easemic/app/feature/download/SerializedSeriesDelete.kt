package com.easemic.app.feature.download

import com.easemic.app.SerializedDeleteResult

/** Coordinates the local, chapter-scoped half of a serialized-series delete. */
class SerializedSeriesDelete(
    private val jobsForSeries: suspend (GalleryIdentityV1) -> List<DownloadJob>,
    private val acquisitionFor: (DownloadJob) -> String?,
    private val uploadInFlight: (Set<String>) -> Boolean,
    private val deleteCatalogTree: (GalleryIdentityV1, Int) -> Boolean,
    private val deleteChapter: suspend (String, () -> Boolean) -> Boolean,
    private val removeChapterAvailability: suspend (GalleryIdentityV1, Set<String>) -> Boolean,
    private val removeCloudQueue: (DownloadJob) -> Unit = {},
) {
    suspend fun delete(
        series: GalleryIdentityV1,
        chapterIds: Set<String>? = null,
    ): SerializedDeleteResult {
        val jobs = jobsForSeries(series).filter {
            it.isSerializedChapter && (chapterIds == null || it.chapterIdentity in chapterIds)
        }
        if (jobs.isEmpty()) return SerializedDeleteResult.NothingSaved
        val acquisitions = jobs.mapNotNull(acquisitionFor).toSet()
        if (jobs.all { it.state == DownloadJobState.NONE }) {
            if (uploadInFlight(acquisitions)) return SerializedDeleteResult.RefusedUpload
            jobs.forEach(removeCloudQueue)
            return SerializedDeleteResult.NothingSaved
        }
        if (jobs.any { it.state == DownloadJobState.WAIT || it.state == DownloadJobState.DOWNLOAD }) {
            return SerializedDeleteResult.RefusedActive
        }
        if (uploadInFlight(acquisitions)) return SerializedDeleteResult.RefusedUpload

        val deletedChapterIds = linkedSetOf<String>()
        for (job in jobs) {
            if (job.state == DownloadJobState.NONE) {
                removeCloudQueue(job)
                continue
            }
            val ownsPages = job.state == DownloadJobState.FINISH || job.readyPageCount > 0
            if (!ownsPages) continue
            val deleted = deleteChapter(job.id) {
                deleteCatalogTree(job.storageIdentity, job.downloadRevision)
            }
            if (!deleted) {
                if (deletedChapterIds.isNotEmpty()) {
                    removeChapterAvailability(series, deletedChapterIds)
                }
                return SerializedDeleteResult.Failed
            }
            removeCloudQueue(job)
            deletedChapterIds += job.chapterIdentity
        }
        if (deletedChapterIds.isEmpty()) return SerializedDeleteResult.NothingSaved
        removeChapterAvailability(series, deletedChapterIds)
        return SerializedDeleteResult.Deleted(deletedChapterIds.size)
    }
}
