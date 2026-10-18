package com.easemic.app.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the [CloudMirrorCardEntity]/[CloudMirrorDetailEntity]/[CloudMirrorStateEntity]
 * trio (loyal-pipelines Slice B data layer). Every query is scoped by
 * `bucketFingerprint` so a rebind to a different bucket cannot leak rows
 * across libraries.
 */
@Dao
interface CloudMirrorDao {
    // IGNORE + an explicit UPDATE at the store seam avoids Room's REPLACE
    // delete/insert cycle, which would cascade-delete chapter rows.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertCards(cards: List<CloudMirrorCardEntity>)

    @Update
    suspend fun updateCard(card: CloudMirrorCardEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDetail(detail: CloudMirrorDetailEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapters(chapters: List<CloudMirrorChapterEntity>)

    @Query(
        "DELETE FROM cloud_mirror_chapters WHERE bucketFingerprint = :fp AND cloudComicId = :id",
    )
    suspend fun deleteChapters(fp: String, id: String)

    @Query(
        "SELECT * FROM cloud_mirror_chapters WHERE bucketFingerprint = :fp AND cloudComicId = :id ORDER BY ordinal ASC",
    )
    suspend fun chapters(fp: String, id: String): List<CloudMirrorChapterEntity>

    @Query(
        "SELECT COUNT(*) FROM cloud_mirror_chapters WHERE bucketFingerprint = :fp AND cloudComicId = :id",
    )
    suspend fun chapterCount(fp: String, id: String): Int

    @Query(
        "UPDATE cloud_mirror_cards SET seriesGeneration = :seriesGeneration WHERE bucketFingerprint = :fp AND cloudComicId = :id",
    )
    suspend fun setSeriesGeneration(fp: String, id: String, seriesGeneration: Int)

    @Query(
        "UPDATE cloud_mirror_cards SET providerId = :providerId, providerSeriesId = :providerSeriesId WHERE bucketFingerprint = :fp AND cloudComicId = :id",
    )
    suspend fun setSeriesIdentity(fp: String, id: String, providerId: String?, providerSeriesId: String?)

    @Transaction
    suspend fun replaceChapters(
        fp: String,
        id: String,
        chapters: List<CloudMirrorChapterEntity>,
    ) {
        deleteChapters(fp, id)
        if (chapters.isNotEmpty()) upsertChapters(chapters)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: CloudMirrorStateEntity)

    @Query(
        """
        SELECT * FROM cloud_mirror_cards
        WHERE bucketFingerprint = :fp AND trashedAtEpochMs IS NULL
        ORDER BY collectedAtEpochMs DESC
        """,
    )
    fun observeActiveCards(fp: String): Flow<List<CloudMirrorCardEntity>>

    @Query(
        """
        SELECT * FROM cloud_mirror_cards
        WHERE bucketFingerprint = :fp AND trashedAtEpochMs IS NULL
        ORDER BY collectedAtEpochMs DESC
        """,
    )
    suspend fun activeCards(fp: String): List<CloudMirrorCardEntity>

    @Query("SELECT * FROM cloud_mirror_cards WHERE bucketFingerprint = :fp")
    suspend fun allCards(fp: String): List<CloudMirrorCardEntity>

    @Query("SELECT * FROM cloud_mirror_state WHERE bucketFingerprint = :fp")
    suspend fun state(fp: String): CloudMirrorStateEntity?

    /** Live form of [state] — drives the Cloud tab's "Importing · N/M" progress strip. */
    @Query("SELECT * FROM cloud_mirror_state WHERE bucketFingerprint = :fp")
    fun observeState(fp: String): Flow<CloudMirrorStateEntity?>

    @Query("SELECT * FROM cloud_mirror_cards WHERE bucketFingerprint = :fp AND cloudComicId = :id")
    suspend fun card(fp: String, id: String): CloudMirrorCardEntity?

    @Query("SELECT * FROM cloud_mirror_detail WHERE bucketFingerprint = :fp AND cloudComicId = :id")
    suspend fun detail(fp: String, id: String): CloudMirrorDetailEntity?

    @Query("DELETE FROM cloud_mirror_cards WHERE bucketFingerprint = :fp")
    suspend fun deleteCards(fp: String)

    @Query("DELETE FROM cloud_mirror_detail WHERE bucketFingerprint = :fp")
    suspend fun deleteDetail(fp: String)

    @Query("DELETE FROM cloud_mirror_state WHERE bucketFingerprint = :fp")
    suspend fun deleteState(fp: String)

    /**
     * Rebind-to-a-different-bucket wipe (loyal-pipelines Slice B pump task
     * §2): drops every OTHER bucket's rows, keeping [fp]'s own mirror intact
     * so a same-bucket rebind never loses its already-hydrated cards.
     */
    @Query("DELETE FROM cloud_mirror_cards WHERE bucketFingerprint != :fp")
    suspend fun deleteCardsExcept(fp: String)

    @Query("DELETE FROM cloud_mirror_detail WHERE bucketFingerprint != :fp")
    suspend fun deleteDetailExcept(fp: String)

    @Query("DELETE FROM cloud_mirror_state WHERE bucketFingerprint != :fp")
    suspend fun deleteStateExcept(fp: String)

    /** Disconnect wipe: drops every mirror row for every bucket. */
    @Query("DELETE FROM cloud_mirror_cards")
    suspend fun deleteAllCards()

    @Query("DELETE FROM cloud_mirror_detail")
    suspend fun deleteAllDetail()

    @Query("DELETE FROM cloud_mirror_state")
    suspend fun deleteAllState()

    @Query("DELETE FROM cloud_mirror_cards WHERE bucketFingerprint = :fp AND cloudComicId = :id")
    suspend fun deleteCard(fp: String, id: String)

    /**
     * Ghost-card prune: removes cards not stamped with [keepGeneration] — used
     * once a generation-bump re-walk finishes its last chunk, when every
     * surviving card has been re-stamped with the new generation and anything
     * still on the old generation is confirmed gone upstream.
     */
    @Query("DELETE FROM cloud_mirror_cards WHERE bucketFingerprint = :fp AND generation != :keepGeneration")
    suspend fun deleteCardsNotOfGeneration(fp: String, keepGeneration: Int)

    /** Cleans up detail rows left behind by [deleteCardsNotOfGeneration]'s card deletions. */
    @Query(
        """
        DELETE FROM cloud_mirror_detail
        WHERE bucketFingerprint = :fp AND cloudComicId NOT IN (
            SELECT cloudComicId FROM cloud_mirror_cards WHERE bucketFingerprint = :fp
        )
        """,
    )
    suspend fun deleteDetailNotInCards(fp: String)

    @Query(
        "UPDATE cloud_mirror_cards SET trashedAtEpochMs = :ts " +
            "WHERE bucketFingerprint = :fp AND cloudComicId = :id",
    )
    suspend fun setTrashed(fp: String, id: String, ts: Long?)

    /**
     * Tier-2 cursor: the next card still awaiting `meta.json` detail, oldest
     * chunk first so a resumed trickle finishes earlier-imported cards before
     * later ones. Null once every active-or-trashed card is hydrated.
     */
    @Query(
        """
        SELECT * FROM cloud_mirror_cards
        WHERE bucketFingerprint = :fp AND detailHydrated = 0 AND layoutKind = 'SINGLE_CHAPTER'
        ORDER BY chunkIndex ASC, cloudComicId ASC
        LIMIT 1
        """,
    )
    suspend fun nextUnhydratedCard(fp: String): CloudMirrorCardEntity?

    /**
     * Flips a card's tier-2 flag without writing a detail row — used to skip
     * a comic whose `meta.json` is confirmed missing so the trickle doesn't
     * retry it forever.
     */
    @Query(
        "UPDATE cloud_mirror_cards SET detailHydrated = 1 " +
            "WHERE bucketFingerprint = :fp AND cloudComicId = :id",
    )
    suspend fun markDetailHydrated(fp: String, id: String)

    /**
     * S4 Task 1 (owner bug C4): persists a just-written durable on-device
     * cover path for one card — called both at ingest's `onSynced` hook and
     * during tier-2 hydration for cards lacking a cover yet, and as a
     * one-time backfill after a local-first `downloadThumb` fallback read.
     */
    @Query(
        "UPDATE cloud_mirror_cards SET coverPath = :coverPath " +
            "WHERE bucketFingerprint = :fp AND cloudComicId = :id",
    )
    suspend fun setCoverPath(fp: String, id: String, coverPath: String)

    @Query(
        "UPDATE cloud_mirror_cards SET coverThumbObjectKey = :coverObjectKey " +
            "WHERE bucketFingerprint = :fp AND cloudComicId = :id",
    )
    suspend fun setCoverThumbObjectKey(fp: String, id: String, coverObjectKey: String)

    /**
     * A22 §4 atomic own-upload generation stamp (concurrency-reviewer
     * must-fix): moves the state row from [previousGeneration] to
     * [newGeneration] with a single conditional `UPDATE`, so a concurrent
     * hydration walk that rewrote this row to a DIFFERENT generation
     * in between [com.easemic.app.feature.collect.RoomCloudMirrorStore]'s
     * precondition read and this write can never be clobbered — the WHERE
     * clause re-validates the row as it stands AT WRITE TIME, not a
     * possibly-stale snapshot read earlier. Returns the number of rows
     * updated (0 or 1); the caller treats anything other than 1 as "did not
     * apply" and leaves the row for the next hydration walk to reconcile.
     */
    @Query(
        """
        UPDATE cloud_mirror_state
        SET generation = :newGeneration, totalCards = :totalCards
        WHERE bucketFingerprint = :fp AND generation = :previousGeneration AND hydrationComplete = 1
        """,
    )
    suspend fun stampGenerationIfAtPrevious(
        fp: String,
        previousGeneration: Int,
        newGeneration: Int,
        totalCards: Int,
    ): Int
}
