package com.easemic.app.feature.activity

import com.easemic.app.data.room.SerializedChapterJob
import com.easemic.app.feature.collect.CloudSyncQueueItem
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.GalleryIdentityV2
import com.easemic.app.feature.download.SeriesIdentityV2

/** All Activity numbers describe the same collected chapter identities. */
data class SerializedChapterPopulation(
    val jobs: Set<String> = emptySet(),
    val finished: Set<String> = emptySet(),
    val inCloud: Set<String> = emptySet(),
    val queued: Set<String> = emptySet(),
) {
    val all: Set<String> get() = jobs + inCloud + queued
    val saved: Set<String> get() = finished + inCloud
}

/** Excludes cancelled, delisted, sealed and gallery jobs; every cloud queue stage contributes its identity. */
fun serializedChapterPopulations(
    jobs: List<SerializedChapterJob>,
    queue: List<CloudSyncQueueItem> = emptyList(),
    inCloud: Map<SeriesIdentityV2, Set<String>> = emptyMap(),
): Map<SeriesIdentityV2, SerializedChapterPopulation> {
    val eligible = jobs.filter {
        it.chapterIdentity != GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID &&
            it.state in setOf("WAIT", "DOWNLOAD", "FINISH", "FAILED")
    }.groupBy { SeriesIdentityV2(it.providerNamespace, it.canonicalItemId) }
    val queued = queue.filter {
        !it.providerId.isNullOrBlank() && !it.canonicalItemId.isNullOrBlank() &&
            it.chapterIdentity != GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID
    }.groupBy { SeriesIdentityV2(it.providerId!!, it.canonicalItemId!!) }
    return (eligible.keys + queued.keys + inCloud.keys).associateWith { series ->
        val seriesJobs = eligible[series].orEmpty()
        SerializedChapterPopulation(
            jobs = seriesJobs.map { it.chapterIdentity }.toSet(),
            finished = seriesJobs.filter { it.state == DownloadJobState.FINISH.name }.map { it.chapterIdentity }.toSet(),
            inCloud = inCloud[series].orEmpty().filterNot { it == GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID }.toSet(),
            queued = queued[series].orEmpty().map { it.chapterIdentity }.toSet(),
        )
    }
}
