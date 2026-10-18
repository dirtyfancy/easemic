package com.easemic.app.feature.collect

import com.easemic.app.data.SerializedSeriesLibrary
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.DownloadLocator
import com.easemic.app.feature.download.DownloadLocatorStore
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.RoomDownloadJobStore
import com.easemic.app.feature.download.serializedChapterLocatorKey
import com.easemic.app.feature.download.serializedChapterStorageIdentity
import com.easemic.app.feature.download.putKeepingCatalogFacts
import com.easemic.app.feature.source.ComicSource
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.asSeriesIdentityV2
import com.easemic.app.feature.importing.SerializedSourceCoverFiles
import com.easemic.app.feature.source.SeriesCoverSource
import kotlinx.coroutines.CancellationException

/** Result of collecting a serialized series into the local library. */
sealed interface SerializedCollectResult {
    data class Collected(
        val itemId: Long,
        val queuedChapterIds: List<String>,
        val newChapterIds: Set<String>,
        val removedChapterIds: Set<String> = emptySet(),
    ) : SerializedCollectResult

    data class NeedsConfirmation(
        val chapterCount: Int,
        val estimatedBytes: Long?,
        val chapterIds: List<String> = emptyList(),
    ) : SerializedCollectResult

    data class Blocked(val reason: String) : SerializedCollectResult
}

/** Whether cancelling a confirmation should undo the user's Collect tap. */
fun shouldRemoveSerializedBookmarkOnConfirmationCancel(fromCollectTap: Boolean): Boolean = fromCollectTap

/**
 * Owns the local side of a serialized follow. It creates/reconciles the
 * series before queueing any chapter, writes one resume locator per chapter,
 * and gives each readable chapter both local and cloud intent. The injected
 * [SerializedChapterQueuePolicy] keeps cloud-known chapters out of the source
 * fetch while leaving the local reconciliation intact.
 */
class SerializedSeriesCollector(
    private val library: SerializedSeriesLibrary,
    private val downloads: RoomDownloadJobStore,
    private val locators: DownloadLocatorStore,
    private val chapterQueuePolicy: SerializedChapterQueuePolicy = SerializedChapterQueuePolicy(
        localJobExists = { series, chapterId ->
            downloads.jobFor(
                GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId),
                chapterId,
            ) != null
        },
        ledgerHasChapter = { _, _ -> false },
        mirrorHasChapter = { _, _ -> false },
        localJobDeleted = { series, chapterId ->
            downloads.jobFor(
                GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId),
                chapterId,
            )?.state == DownloadJobState.NONE
        },
    ),
    private val sourceCoverFiles: SerializedSourceCoverFiles? = null,
) {
    suspend fun collect(
        source: ComicSource,
        series: GalleryIdentityV1,
        title: String,
        coverLocator: String?,
        confirmed: Boolean = false,
    ): SerializedCollectResult {
        if (!source.descriptor.serialized) {
            return SerializedCollectResult.Blocked("Source does not provide serialized chapters.")
        }
        val sourceSeries = SeriesIdentityV2(series.providerNamespace, series.canonicalItemId)
        val chapterList = try {
            source.chapters(sourceSeries)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SerializedCollectResult.Blocked("Unable to load chapters.")
        }
        return collectFrom(series, title, coverLocator, chapterList, confirmed)
    }

    /** Best-effort source-cover publication for the source-backed collect path. */
    suspend fun ensureSourceCover(
        identity: SeriesIdentityV2,
        locator: String?,
        source: ComicSource,
    ): String? {
        val files = sourceCoverFiles ?: return null
        val coverSource = source as? SeriesCoverSource ?: return null
        return ensureSerializedSourceCover(files, identity, locator, coverSource)
    }

    /** Applies a chapter-list response after its source fetch has completed. */
    suspend fun collectFrom(
        series: GalleryIdentityV1,
        title: String,
        coverLocator: String?,
        chapterList: ChapterListResult,
        confirmed: Boolean = false,
    ): SerializedCollectResult {
        val sourceSeries = series.asSeriesIdentityV2()
        val toQueue = chapterList.chapters
            .filter { it.canRead && !it.isFee }
            .filter {
                // #E110: a Collect adopts chapters that already have local jobs
                // (OR-merge cloud intent) — only ledger/mirror-known chapters
                // are done everywhere.
                chapterQueuePolicy.shouldAdoptForCloud(sourceSeries, it.identity.canonicalChapterId)
            }
            .map { it.identity.canonicalChapterId }
        if (!confirmed && toQueue.size > MAX_BATCH) {
            // Preserve supplied text even while the user decides whether to
            // queue a large series. No unconfirmed chapters are reconciled.
            try {
                library.updateSeriesDetailSnapshot(sourceSeries, chapterList)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The confirmation remains actionable; the normal confirmed
                // path will retry the complete persistence operation.
            }
            return SerializedCollectResult.NeedsConfirmation(toQueue.size, null, toQueue)
        }

        val outcome = try {
            library.reconcileSeries(series, title, coverLocator, chapterList)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SerializedCollectResult.Blocked("Unable to save series.")
        }
        val queued = ArrayList<String>()
        val newIds = outcome.plan.newChapterIds
        for (chapter in outcome.plan.units.sortedBy { it.ordinal }) {
            if (!chapter.readable || chapter.sourceState != ACTIVE_STATE) continue
            val chapterId = chapter.sourceChapterId
            // #E110: same adoption gate as the pre-count above.
            if (!chapterQueuePolicy.shouldAdoptForCloud(sourceSeries, chapterId)) continue
            val storageIdentity = serializedChapterStorageIdentity(series.asSeriesIdentityV2(), chapterId)
            locators.putKeepingCatalogFacts(
                storageIdentity,
                DownloadLocator(
                    acquisitionUrl = serializedChapterLocatorKey(sourceSeries, chapterId),
                    title = chapter.label,
                    coverUrl = coverLocator,
                ),
            )
            downloads.upsertEnqueue(
                identity = series,
                expectedPageCount = null,
                intentLocal = true,
                intentCloud = true,
                chapterIdentity = chapterId,
            )
            val job = downloads.jobFor(series, chapterId)
            if (job?.state == DownloadJobState.WAIT || job?.state == DownloadJobState.DOWNLOAD) {
                queued += chapterId
            }
        }
        return SerializedCollectResult.Collected(outcome.itemId, queued, newIds, outcome.plan.removedChapterIds)
    }

    companion object {
        private const val MAX_BATCH = 50
        private const val ACTIVE_STATE = "ACTIVE"
    }
}
