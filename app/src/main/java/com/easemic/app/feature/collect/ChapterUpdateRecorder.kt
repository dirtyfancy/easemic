package com.easemic.app.feature.collect

import com.easemic.app.data.room.CHAPTER_UPDATE_RETAINED_ROWS
import com.easemic.app.data.room.ChapterUpdateDao
import com.easemic.app.data.room.ChapterUpdateEntity
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.source.ChapterSummary
import kotlinx.coroutines.flow.Flow

private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000

/**
 * Writes the durable Updates log for chapters a refresh found.
 *
 * A re-reported id is ignored, so the first discovery time and seen mark stay.
 * Retention runs in the same transaction: drop anything older than 30 days
 * before the newest discovery, then keep the newest [CHAPTER_UPDATE_RETAINED_ROWS].
 */
class ChapterUpdateRecorder(
    private val dao: ChapterUpdateDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
) {
    suspend fun recordRefresh(
        series: SeriesIdentityV2,
        seriesTitle: String,
        chapters: List<ChapterSummary>,
        newChapterIds: Set<String>,
        discoveredAtMillis: Long,
    ) {
        inTransaction {
            val fresh = chapters
                .filter { chapter -> chapter.identity.canonicalChapterId in newChapterIds }
                .sortedWith(compareBy<ChapterSummary> { it.groupOrder }.thenBy { it.readingOrderInGroup })
            fresh.forEachIndexed { rank, chapter ->
                dao.insertIfAbsent(
                    ChapterUpdateEntity(
                        providerNamespace = series.providerNamespace,
                        providerSeriesId = series.canonicalSeriesId,
                        providerChapterId = chapter.identity.canonicalChapterId,
                        seriesTitle = seriesTitle,
                        chapterLabel = chapter.label,
                        groupLabel = chapter.group,
                        sourceUpdatedAtMillis = chapter.sourceUpdatedAtMillis,
                        discoveredAtMillis = discoveredAtMillis + rank,
                        seenAtMillis = null,
                    ),
                )
            }
            val storedMax = dao.selectAll().maxOfOrNull { it.discoveredAtMillis }
            val present = if (storedMax == null) discoveredAtMillis else maxOf(discoveredAtMillis, storedMax)
            dao.deleteOlderThan(present - THIRTY_DAYS_MS)
            dao.trimToNewest(CHAPTER_UPDATE_RETAINED_ROWS)
        }
    }

    fun observe(): Flow<List<ChapterUpdateEntity>> = dao.observeAll()

    suspend fun markSeen(namespace: String, seriesId: String, chapterId: String, seenAtMillis: Long): Boolean =
        dao.markSeenReturningCount(namespace, seriesId, chapterId, seenAtMillis) == 1

    suspend fun deleteForSeries(namespace: String, seriesId: String) {
        dao.deleteForSeries(namespace, seriesId)
    }

    /**
     * #E110: a chapter the hydrated cloud mirror already owns was never an
     * update — pruning its rows heals a rebind-time flood (every chapter of a
     * mirrored series re-recorded when the local baseline was wiped) on the
     * series' next completed refresh.
     */
    suspend fun pruneMirrored(namespace: String, seriesId: String, mirroredChapterIds: Collection<String>) {
        if (mirroredChapterIds.isEmpty()) return
        inTransaction { dao.deleteChapters(namespace, seriesId, mirroredChapterIds.toList()) }
    }
}
