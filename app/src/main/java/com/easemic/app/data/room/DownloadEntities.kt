package com.easemic.app.data.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted download job (spec §§4.1, 4.3, 4.7).
 *
 * Uniqueness is `(identityVersion, providerNamespace, canonicalItemId, chapterIdentity)`.
 * Consumption-queue membership is derived from [state] (WAIT|DOWNLOAD only).
 */
@Entity(
    tableName = "download_jobs",
    indices = [
        Index(
            value = ["identityVersion", "providerNamespace", "canonicalItemId", "chapterIdentity"],
            unique = true,
        ),
    ],
)
data class DownloadJobEntity(
    @PrimaryKey val id: String,
    val identityVersion: Int,
    val providerNamespace: String,
    val canonicalItemId: String,
    @ColumnInfo(defaultValue = "legacy:0") val chapterIdentity: String = "legacy:0",
    val fallbackUrlDerived: Boolean,
    val state: String,
    val expectedPageCount: Int?,
    val downloadRevision: Int,
    val enqueuedAtMillis: Long,
    val updatedAtMillis: Long,
    /** Fetch intents (v5). A collect sets cloud=true; a local download sets local=true. */
    val intentLocal: Boolean = false,
    val intentCloud: Boolean = false,
    /** Pages actually fetched when SEALED via truncation; null = complete (v5). */
    val sealedTruncatedAt: Int? = null,
    /** Last failure classification name + human detail; for UI + routing (v5). */
    val failureClass: String? = null,
    val failureDetail: String? = null,
    /** Epoch ms before which this job must not be retried (QUOTA/ban idle window; v5). */
    val resumeNotBeforeMs: Long = 0L,
    /** Bounded auto-revive attempt count for FAILED cloud-intent jobs (v10). */
    val autoRetryAttempts: Int = 0,
    /** Wall-clock time of the most recent auto-retry attempt increment; 0 = never (v11). */
    val lastAutoRetryAtMs: Long = 0L,
    /**
     * C9 (v15): epoch ms the user dismissed this row from Activity's "Needs
     * attention"; 0 = never. Only meaningful for GONE — a dismissed GONE row
     * keeps its state (the shelf projection and the re-enqueue no-op both read
     * it) and merely leaves [DownloadDao.observeActivityJobs].
     */
    val dismissedAtMillis: Long = 0L,
)

/**
 * Verified READY page ordinals for a job + download revision.
 * File bytes live in [com.easemic.app.feature.download.ReadyPageStore];
 * this table is the durable READY set for restart restore.
 */
@Entity(
    tableName = "download_ready_pages",
    primaryKeys = ["jobId", "downloadRevision", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = DownloadJobEntity::class,
            parentColumns = ["id"],
            childColumns = ["jobId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("jobId")],
)
data class DownloadReadyPageEntity(
    val jobId: String,
    val downloadRevision: Int,
    val ordinal: Int,
    val readyAtMillis: Long,
)
