package com.easemic.app.feature.download

/**
 * Local-only Download action surface used by Detail (Task 7).
 *
 * Collect / entitlement / Cloud Collection hooks are accepted only so tests can
 * prove they are **never** invoked on this path. Production wiring must leave
 * them as no-ops (or omit callers entirely).
 */
class LocalDownloadActions(
    private val enqueue: (GalleryIdentityV1, Int?) -> DownloadJob,
    private val cancel: (String) -> DownloadJob,
    private val retry: (String) -> DownloadJob,
    private val deleteFinished: (String, () -> Boolean) -> DownloadJob,
    private val onCollect: () -> Unit = {},
    private val onEntitlementCheck: () -> Boolean = { true },
    private val onCloudMutation: () -> Unit = {},
) {
    /** True after any local Download API was used without collect/cloud hooks. */
    var usedLocalOnlyPath: Boolean = false
        private set

    fun enqueueLocal(identity: GalleryIdentityV1, expectedPageCount: Int? = null): DownloadJob {
        usedLocalOnlyPath = true
        // Intentionally do not call onCollect / onEntitlementCheck / onCloudMutation.
        return enqueue(identity, expectedPageCount)
    }

    fun cancelLocal(jobId: String): DownloadJob {
        usedLocalOnlyPath = true
        return cancel(jobId)
    }

    fun retryLocal(jobId: String): DownloadJob {
        usedLocalOnlyPath = true
        return retry(jobId)
    }

    fun deleteFinishedLocal(jobId: String, deleteOwnedMedia: () -> Boolean): DownloadJob {
        usedLocalOnlyPath = true
        return deleteFinished(jobId, deleteOwnedMedia)
    }
}
