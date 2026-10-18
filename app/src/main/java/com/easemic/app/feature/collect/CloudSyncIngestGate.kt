package com.easemic.app.feature.collect

import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.isSerializedChapter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Caps how many cloud Collect ingest jobs may be FETCHING/UPLOADING at once.
 * Catch-up historically launched every pending bookmark in parallel; that
 * thrashed CatalogRequestGate, memory, and made mid-job failures more likely.
 *
 * Default [maxActive] is 2. Waiting rows stay in [CloudSyncQueue] as WAITING
 * until a permit is available.
 */
class CloudSyncIngestGate(
    maxActive: Int = DEFAULT_MAX_ACTIVE,
) {
    init {
        require(maxActive >= 1) { "maxActive must be >= 1" }
    }

    private val semaphore = Semaphore(maxActive)
    private val lock = Mutex()
    private val holders = linkedSetOf<String>()

    val activeCount: Int get() = holders.size

    fun isHeld(id: String): Boolean = holders.contains(id)

    suspend fun <T> withPermit(id: String, block: suspend () -> T): T {
        return semaphore.withPermit {
            lock.withLock { holders.add(id) }
            try {
                block()
            } finally {
                lock.withLock { holders.remove(id) }
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ACTIVE = 2
    }
}

/** Bookmark gate shared by gallery and serialized cloud handoff paths. */
fun cloudSyncIngestAdmits(
    job: DownloadJob,
    seriesFollowExists: (GalleryIdentityV1) -> Boolean,
): Boolean = !job.isSerializedChapter || seriesFollowExists(job.identity)
