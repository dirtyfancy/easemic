package com.easemic.app.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Shared with the JVM fake so a REPLACE insert is the same defect the Room annotation has. */
const val CHAPTER_UPDATE_INSERT_ON_CONFLICT = OnConflictStrategy.IGNORE

/** Newest rows kept after the 30-day cut. Bound into [ChapterUpdateDao.trimToNewest]. */
const val CHAPTER_UPDATE_RETAINED_ROWS = 300

@Dao
interface ChapterUpdateDao {
    @Insert(onConflict = CHAPTER_UPDATE_INSERT_ON_CONFLICT)
    suspend fun insertIfAbsent(row: ChapterUpdateEntity): Long

    @Query(
        """
        SELECT * FROM chapter_updates
        ORDER BY discoveredAtMillis DESC, providerNamespace, providerSeriesId, providerChapterId
        """,
    )
    suspend fun selectAll(): List<ChapterUpdateEntity>

    @Query(
        """
        SELECT * FROM chapter_updates
        ORDER BY discoveredAtMillis DESC, providerNamespace, providerSeriesId, providerChapterId
        """,
    )
    fun observeAll(): Flow<List<ChapterUpdateEntity>>

    @Query(
        """
        UPDATE chapter_updates SET seenAtMillis = :seenAtMillis
        WHERE providerNamespace = :namespace AND providerSeriesId = :seriesId
          AND providerChapterId = :chapterId AND seenAtMillis IS NULL
        """,
    )
    suspend fun markSeenReturningCount(namespace: String, seriesId: String, chapterId: String, seenAtMillis: Long): Int

    @Query("SELECT COUNT(*) FROM chapter_updates WHERE seenAtMillis IS NULL")
    suspend fun unseenCount(): Int

    @Query("DELETE FROM chapter_updates WHERE discoveredAtMillis < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    @Query(
        """
        DELETE FROM chapter_updates
        WHERE rowid IN (
            SELECT rowid FROM chapter_updates
            ORDER BY discoveredAtMillis DESC, providerNamespace, providerSeriesId, providerChapterId
            LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun trimToNewest(keep: Int): Int

    @Query("DELETE FROM chapter_updates WHERE providerNamespace = :namespace AND providerSeriesId = :seriesId")
    suspend fun deleteForSeries(namespace: String, seriesId: String): Int

    /** #E110: drops rows for chapters the cloud mirror already owns (flood self-heal). */
    @Query(
        """
        DELETE FROM chapter_updates
        WHERE providerNamespace = :namespace AND providerSeriesId = :seriesId
            AND providerChapterId IN (:chapterIds)
        """,
    )
    suspend fun deleteChapters(namespace: String, seriesId: String, chapterIds: List<String>): Int

    @Query("SELECT COUNT(*) FROM chapter_updates")
    suspend fun count(): Int
}
