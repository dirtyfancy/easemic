package com.easemic.app.data.room

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Durable per-comic sync ledger (S3 Task 1, owner bug C3/A3). One row per
 * comic known to be uploaded to a specific bucket, written on upload success
 * and consulted by the catch-up sweep (and the collect pre-flight) BEFORE any
 * live-R2-list logic.
 *
 * ROOT CAUSE this fixes: "synced" used to be re-derived from a LIVE R2 re-list
 * on every cold start. A momentary "R2 unreachable" at launch — or a card
 * whose `acquisitionRef` is blank — made an already-uploaded comic look absent
 * and got it re-collected and re-uploaded. This ledger is the durable source
 * of truth: a comic known-synced to the CURRENT bucket is never re-uploaded
 * just because one startup read failed.
 *
 * Scoped by [bucketFingerprint] (like [CloudMirrorCardEntity]) so a rebind to
 * a different bucket cannot leak sync knowledge across libraries. The
 * [acquisitionKey] is the token-free canonical dedup key
 * ([com.easemic.app.feature.library.galleryDedupKey]) so a
 * bookmark URL carrying an E-Hentai token still matches the ledger entry
 * written from the (token-stripped, or differently-tokened) cloud acquisition.
 */
@Entity(
    tableName = "cloud_sync_ledger",
    primaryKeys = ["bucketFingerprint", "acquisitionKey", "chapterIdentity"],
    indices = [Index(value = ["bucketFingerprint", "cloudComicId", "chapterIdentity"])],
)
data class CloudSyncLedgerEntity(
    val bucketFingerprint: String,
    val acquisitionKey: String,
    val cloudComicId: String,
    val generation: Int,
    val syncedAtMs: Long,
    @ColumnInfo(defaultValue = "legacy:0")
    val chapterIdentity: String = "legacy:0",
)

/**
 * DAO for [CloudSyncLedgerEntity]. Every query is scoped by
 * `bucketFingerprint` so a rebind cannot read another library's ledger.
 */
@Dao
interface CloudSyncLedgerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CloudSyncLedgerEntity)

    @Query(
        "SELECT * FROM cloud_sync_ledger WHERE bucketFingerprint = :bucketFingerprint AND acquisitionKey = :acquisitionKey AND chapterIdentity = :chapterIdentity",
    )
    suspend fun find(bucketFingerprint: String, acquisitionKey: String, chapterIdentity: String = "legacy:0"): CloudSyncLedgerEntity?

    @Query(
        "DELETE FROM cloud_sync_ledger WHERE bucketFingerprint = :bucketFingerprint AND acquisitionKey = :acquisitionKey AND chapterIdentity = :chapterIdentity",
    )
    suspend fun deleteOne(bucketFingerprint: String, acquisitionKey: String, chapterIdentity: String = "legacy:0")

    @Query("DELETE FROM cloud_sync_ledger WHERE bucketFingerprint = :bucketFingerprint AND acquisitionKey = :acquisitionKey")
    suspend fun deleteSeries(bucketFingerprint: String, acquisitionKey: String)

    @Query("SELECT chapterIdentity FROM cloud_sync_ledger WHERE bucketFingerprint = :bucketFingerprint AND acquisitionKey = :acquisitionKey")
    suspend fun chapterIds(bucketFingerprint: String, acquisitionKey: String): List<String>

    @Query("SELECT * FROM cloud_sync_ledger WHERE bucketFingerprint = :bucketFingerprint")
    suspend fun rows(bucketFingerprint: String): List<CloudSyncLedgerEntity>

    @Query("SELECT cloudComicId FROM cloud_sync_ledger WHERE bucketFingerprint = :bucketFingerprint AND acquisitionKey = :acquisitionKey ORDER BY chapterIdentity LIMIT 1")
    suspend fun cloudComicIdForSeries(bucketFingerprint: String, acquisitionKey: String): String?

    @Query("DELETE FROM cloud_sync_ledger WHERE bucketFingerprint = :bucketFingerprint")
    suspend fun deleteForBucket(bucketFingerprint: String)

    /** Rebind hygiene: drop every OTHER bucket's ledger, keep [bucketFingerprint]'s own. */
    @Query("DELETE FROM cloud_sync_ledger WHERE bucketFingerprint != :bucketFingerprint")
    suspend fun deleteAllExcept(bucketFingerprint: String)

    /** Disconnect wipe: drop every bucket's ledger. */
    @Query("DELETE FROM cloud_sync_ledger")
    suspend fun deleteAll()

    /**
     * A24-1 (owner bug sample: job 4169293, 29 local pages, gone from the
     * shelf, never re-uploaded): a ledger row must not outlive the mirror
     * card it was derived from. [CloudMirrorCardEntity] rows are pruned when
     * a hydration re-walk finds them gone upstream
     * ([com.easemic.app.feature.collect.CloudHydrationMirror.pruneStaleCards]),
     * but this ledger — written forward-only on upload success or
     * [com.easemic.app.feature.collect.backfillFromMirror] —
     * never heard about it, so every re-upload guard that consults it (the
     * catch-up sweep, the cold-start handoff repair) kept treating the comic
     * as still-synced and skipped it forever.
     *
     * A row is an orphan when its `cloudComicId` has no surviving
     * [CloudMirrorCardEntity] row for the same [fp] — trashed cards stay in
     * `cloud_mirror_cards` (`trashedAtEpochMs` set, not deleted), so a
     * trashed comic's ledger row is correctly NOT an orphan and survives.
     *
     * The `generation < :keepGeneration` guard exists purely to avoid a race
     * with an in-flight walk: an own upload calls `markSynced` with the
     * mirror state's CURRENT generation, which `putState` already bumped to
     * the new walk's generation at walk start — but that upload's
     * `upsertLiveCard` may not have landed in `cloud_mirror_cards` yet when
     * this query runs concurrently. Such a row is stamped with
     * `generation == keepGeneration` (the walk's own generation), so it is
     * deliberately excluded from deletion here regardless of whether its
     * card has appeared yet; only rows strictly OLDER than [keepGeneration]
     * are eligible, and those can only be genuinely stale rows a completed
     * walk already finished reconciling.
     *
     * Returns the number of rows deleted.
     */
    @Query(
        """
        DELETE FROM cloud_sync_ledger
        WHERE bucketFingerprint = :fp AND generation < :keepGeneration
          AND cloudComicId NOT IN (SELECT cloudComicId FROM cloud_mirror_cards WHERE bucketFingerprint = :fp)
        """,
    )
    suspend fun deleteOrphansBelowGeneration(fp: String, keepGeneration: Int): Int
}
