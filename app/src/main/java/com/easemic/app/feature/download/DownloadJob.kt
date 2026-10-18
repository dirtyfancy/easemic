package com.easemic.app.feature.download

/**
 * Internal (non-[FetchFailureClass]) marker stamped into [DownloadJob.failureClass]
 * by `applyUpstreamPageCount` (both [DownloadCore] and [RoomDownloadJobStore])
 * while a job stays WAIT/DOWNLOAD after a short upstream page count vs. the
 * pinned value with pages already on device — S3 Task 9 fifth defect, owner
 * bug backlog: a manifest read can be transiently short (partial/interstitial
 * page), so the FIRST short count parks and retries the manifest once instead
 * of hard-failing immediately. Only a SECOND CONSECUTIVE short count (this
 * marker still present on the next call) escalates to a hard FAILED with
 * `failureClass = "PAGE_COUNT_MISMATCH"`. Deliberately distinct from the
 * [FetchFailureClass.label] vocabulary ("JITTER"/"PACED"/...) so an unrelated
 * per-page JITTER recorded via [DownloadWorkerHost.recordFailure] elsewhere on
 * the same job is never mistaken for a repeat page-count strike.
 */
const val PAGE_COUNT_MISMATCH_PENDING = "PAGE_COUNT_MISMATCH_PENDING"

/**
 * Persisted download job record.
 *
 * [expectedPageCount] is pinned at enqueue or first successful manifest and is
 * the only count FINISH may compare against. [downloadRevision] identifies the
 * current download attempt generation for READY page resolution.
 */
data class DownloadJob(
    val id: String,
    val identity: GalleryIdentityV1,
    val state: DownloadJobState,
    val expectedPageCount: Int?,
    /** READY pages observed with this job's current revision. */
    val readyPageCount: Int = 0,
    val downloadRevision: Int = 1,
    /** Wall-clock enqueue time; FIFO key for the consumption queue (spec §4.3). */
    val enqueuedAtMillis: Long = 0L,
    /** Fetch intents. A collect sets cloud=true; a local download sets local=true; both merge on re-enqueue. */
    val intentLocal: Boolean = false,
    val intentCloud: Boolean = false,
    /** Pages actually fetched when SEALED via truncation (source vanished mid-fetch); null = complete. */
    val sealedTruncatedAt: Int? = null,
    /** Last failure classification via [FetchFailureClass.label] + human detail; for UI + routing. */
    val failureClass: String? = null,
    val failureDetail: String? = null,
    /** Epoch ms before which this job must not be retried (QUOTA/ban idle window). 0 = immediately runnable. */
    val resumeNotBeforeMs: Long = 0L,
    /**
     * Count of bounded lifetime auto-revive attempts
     * ([downloadJobsToRevive]) applied to this FAILED job so far. A manual UI retry
     * ([com.easemic.app.feature.download.RoomDownloadJobStore.retry])
     * resets this to 0 (A11/A20) so a human is never blocked by the cap.
     * Caps unattended retries of a permanently-broken cloud-intent source
     * (S3 Task 4 gap A6).
     */
    val autoRetryAttempts: Int = 0,
    /**
     * Wall-clock time of the most recent [autoRetryAttempts] increment (via
     * [RoomDownloadJobStore.autoRetry]); 0 = never auto-retried. Retained for
     * diagnostics and migration compatibility; it does not reset the lifetime
     * cap.
     */
    val lastAutoRetryAtMs: Long = 0L,
    /** C9: see [com.easemic.app.data.room.DownloadJobEntity.dismissedAtMillis]. */
    val dismissedAtMillis: Long = 0L,
    /** One provider chapter of a serialized series; gallery jobs are `legacy:0`. */
    val chapterIdentity: String = GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID,
    /**
     * Wall-clock time of the most recent row mutation — the domain mirror of
     * `DownloadJobEntity.updatedAtMillis`, the one persisted column the domain
     * used not to carry. #E101's retention clock: the staging sweep evicts the
     * OLDEST terminal-failure row's staging first, by this field. Appended last
     * so every existing construction keeps its meaning.
     */
    val updatedAtMillis: Long = 0L,
)

/** One provider chapter of a serialized series; gallery jobs are `legacy:0`. */
val DownloadJob.isSerializedChapter: Boolean
    get() = chapterIdentity != GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID

/** What the page adapter receives: the gallery identity, or the chapter key. */
val DownloadJob.adapterIdentity: GalleryIdentityV1
    get() = if (isSerializedChapter) {
        serializedChapterAdapterIdentity(identity.asSeriesIdentityV2(), chapterIdentity)
    } else {
        identity
    }

/** What every byte store keys on: the gallery identity, or the chapter storage key. */
val DownloadJob.storageIdentity: GalleryIdentityV1
    get() = if (isSerializedChapter) {
        serializedChapterStorageIdentity(identity.asSeriesIdentityV2(), chapterIdentity)
    } else {
        identity
    }
