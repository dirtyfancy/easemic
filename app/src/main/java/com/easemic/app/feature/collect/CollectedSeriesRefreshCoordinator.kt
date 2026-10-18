package com.easemic.app.feature.collect

import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.source.ChapterListCompleteness
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.ComicSource
import com.easemic.app.feature.source.SeriesPublicationStatus
import com.easemic.app.feature.source.SeriesCoverSource
import com.easemic.app.feature.source.valueOrNull
import com.easemic.app.feature.importing.SerializedSourceCoverFiles
import com.easemic.app.data.room.SerializedSeriesCoverCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

enum class RefreshDecision {
    SKIPPED_NOT_DUE,
    REFRESHED,
    NOT_COLLECTED,
    NEEDS_CONFIRMATION,
    FAILED,
    /** What the cloud already holds for this follow is unknown (see [followCanRefresh]); nothing was fetched or recorded. */
    WAITING_FOR_CLOUD,
}

/** Shared best-effort source-cover fetch seam for collect, refresh, and backfill. */
internal suspend fun ensureSerializedSourceCover(
    files: SerializedSourceCoverFiles,
    identity: SeriesIdentityV2,
    locator: String?,
    source: SeriesCoverSource,
): String? = try {
    files.ensure(identity, locator) { value, destination, maxBytes ->
        source.downloadSeriesCoverToFile(value, destination, maxBytes)
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

/** One foreground candidate snapshot, grouped by provider and serialized per source. */
internal suspend fun backfillSerializedSourceCoversOnce(
    candidates: List<SerializedSeriesCoverCandidate>,
    files: SerializedSourceCoverFiles,
    sourceFor: (String) -> SeriesCoverSource?,
    attempted: MutableSet<SeriesIdentityV2>,
    followedSeries: Set<SeriesIdentityV2>? = null,
) {
    candidates.groupBy { it.identity.providerNamespace }.forEach { (_, grouped) ->
        val source = sourceFor(grouped.first().identity.providerNamespace) ?: return@forEach
        grouped.forEach { candidate ->
            if (followedSeries != null && candidate.identity !in followedSeries) return@forEach
            if (!attempted.add(candidate.identity)) return@forEach
            ensureSerializedSourceCover(files, candidate.identity, candidate.coverLocator, source)
        }
    }
}

/**
 * #E130: whether a follow may refresh now. A refresh queues every chapter it does not know is saved, so it must be able
 * to tell. With no bucket, a follow that has no download jobs here came from a backup: its chapters live in the cloud
 * it was collected into, so it waits for that cloud rather than fetching them all from the source (galleries wait the
 * same way). A follow collected on this phone has jobs and keeps refreshing. With a bucket, the mirror's index walk
 * must be complete; after that its chapter rows are final (a series whose summary is missing in the bucket keeps none).
 */
fun followCanRefresh(
    bucketBound: Boolean,
    hasLocalChapterJobs: Boolean,
    mirrorComplete: Boolean,
): Boolean = if (bucketBound) mirrorComplete else hasLocalChapterJobs

/** Outcome of one series refresh, including chapter ids still waiting for confirmation. */
data class RefreshResult(
    val series: SeriesIdentityV2,
    val decision: RefreshDecision,
    val newChapterIds: Set<String> = emptySet(),
    val queuedChapterIds: List<String> = emptyList(),
    val removedChapterIds: Set<String> = emptySet(),
    val confirmationChapterCount: Int = 0,
    val pendingConfirmationChapterIds: Set<String> = emptySet(),
)

/** Records +N marks for new and pending-confirmation chapter ids from a refresh pass; detail open does not call this. */
fun recordNewChapterMarks(
    results: List<RefreshResult>,
    store: CollectedSeriesNewChapterStore,
    observedAtMs: Long = System.currentTimeMillis(),
) {
    results.forEach { result ->
        val ids = result.newChapterIds + result.pendingConfirmationChapterIds
        if (ids.isNotEmpty()) {
            store.add(result.series, ids, observedAtMs)
        }
    }
}

/** Applies serialized-series chapter lists on foreground and detail-open paths. */
class CollectedSeriesRefreshCoordinator(
    private val sourceFor: (String) -> ComicSource?,
    private val collectedSeries: () -> List<CollectedBookmark>,
    private val collector: SerializedSeriesCollector,
    private val store: CollectedSeriesRefreshStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val chapterTimes: suspend (SeriesIdentityV2) -> List<Long?> = { emptyList() },
    private val updates: ChapterUpdateRecorder? = null,
    private val smartState: SmartRefreshStateStore? = null,
    private val sourceCoverFiles: SerializedSourceCoverFiles? = null,
    /**
     * #E110: chapter ids the hydrated cloud mirror already owns for a series.
     * A mirrored chapter is not an update the reader needs to hear about — a
     * rebind onto a wiped device recorded every chapter of every mirrored
     * series as "new" (owner 2026-10-12: 96 rows for one series), so each
     * completed refresh prunes those rows (heals floods recorded earlier too).
     */
    private val mirroredChapterIds: suspend (SeriesIdentityV2) -> Set<String> = { emptySet() },
    /** #E48: runs after Room reconciled a series' chapters from a source list. */
    private val afterReconcile: suspend (SeriesIdentityV2) -> Unit = {},
    /** #E130: [followCanRefresh] for this series. */
    private val mirrorResolved: suspend (SeriesIdentityV2) -> Boolean = { true },
) {
    suspend fun refreshAllOnForeground(): List<RefreshResult> = refreshCollected(force = false)

    /** Pull to refresh. Ignores the smart-refresh skip and still records what the fetch found. */
    suspend fun refreshAllForced(): List<RefreshResult> = refreshCollected(force = true)

    suspend fun refreshAllForBackground(
        deadline: Long = Long.MAX_VALUE,
        monotonicClock: () -> Long = nowMs,
    ): List<RefreshResult> = refreshCollected(force = false, deadline, monotonicClock)

    private suspend fun refreshCollected(
        force: Boolean,
        deadline: Long = Long.MAX_VALUE,
        monotonicClock: () -> Long = nowMs,
    ): List<RefreshResult> {
        var previousSourceNamespace: String? = null
        val completed = ArrayList<RefreshResult>()
        fun record(result: RefreshResult): Boolean {
            completed += result
            return true
        }
        suspend fun refreshNext(bookmark: CollectedBookmark): Boolean {
            val series = seriesFor(bookmark)
            val source = sourceFor(bookmark.providerId)
            if (!isSerializedBookmark(bookmark) || source == null || !source.descriptor.serialized) {
                return record(RefreshResult(series, RefreshDecision.NOT_COLLECTED))
            }
            val now = nowMs()
            val last = store.lastCompleteRefreshAtMs(series)
            val status = smartState?.read(series)?.lastKnownStatus ?: SeriesPublicationStatus.UNKNOWN
            val times = if (force || last == null) emptyList() else chapterTimes(series)
            if (!smartRefreshDue(now, last, status, times, forced = force)) {
                return record(RefreshResult(series, RefreshDecision.SKIPPED_NOT_DUE))
            }
            if (!mirrorResolved(series)) {
                return record(RefreshResult(series, RefreshDecision.WAITING_FOR_CLOUD))
            }
            if (previousSourceNamespace == source.descriptor.namespace) {
                sleep(source.throttle.interRequestDelayMs)
            }
            previousSourceNamespace = source.descriptor.namespace
            if (monotonicClock() >= deadline) return false
            val fetched = try {
                source.chapters(series)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return record(RefreshResult(series, RefreshDecision.FAILED))
            }
            if (monotonicClock() >= deadline) return false
            // Once reconciliation starts, finish its Updates row and refresh clock together.
            // Record the result here too: the enclosing timeout can expire during apply.
            return withContext(NonCancellable) {
                record(apply(bookmark, series, fetched))
            }
        }
        for (bookmark in collectedSeries()) {
            val remaining = deadline - monotonicClock()
            if (remaining <= 0L) break
            val progressed = if (deadline == Long.MAX_VALUE) {
                refreshNext(bookmark)
            } else {
                withTimeoutOrNull(remaining) { refreshNext(bookmark) }
            } ?: false
            if (!progressed) break
        }
        return completed
    }

    suspend fun refreshOnDetailOpen(
        bookmark: CollectedBookmark,
        fetched: ChapterListResult,
        itemCoverLocator: String? = null,
    ): RefreshResult {
        val series = seriesFor(bookmark)
        val source = sourceFor(bookmark.providerId)
        if (!isSerializedBookmark(bookmark) || source == null || !source.descriptor.serialized) {
            return RefreshResult(series, RefreshDecision.NOT_COLLECTED)
        }
        return apply(bookmark, series, fetched, itemCoverLocator)
    }

    private suspend fun apply(
        bookmark: CollectedBookmark,
        series: SeriesIdentityV2,
        fetched: ChapterListResult,
        itemCoverLocator: String? = null,
    ): RefreshResult {
        if (!mirrorResolved(series)) return RefreshResult(series, RefreshDecision.WAITING_FOR_CLOUD)
        val source = sourceFor(bookmark.providerId)
        val coverLocator = fetched.metadata.cover.valueOrNull() ?: itemCoverLocator ?: bookmark.coverUrl
        val result = try {
            collector.collectFrom(
                series = GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId),
                title = bookmark.title,
                coverLocator = coverLocator,
                chapterList = fetched,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return RefreshResult(series, RefreshDecision.FAILED)
        }
        val mapped = when (result) {
            is SerializedCollectResult.Collected -> RefreshResult(
                series = series,
                decision = RefreshDecision.REFRESHED,
                newChapterIds = result.newChapterIds,
                queuedChapterIds = result.queuedChapterIds,
                removedChapterIds = result.removedChapterIds,
            )
            is SerializedCollectResult.NeedsConfirmation -> RefreshResult(
                series = series,
                decision = RefreshDecision.NEEDS_CONFIRMATION,
                confirmationChapterCount = result.chapterCount,
                pendingConfirmationChapterIds = result.chapterIds.toSet(),
            )
            is SerializedCollectResult.Blocked -> RefreshResult(series, RefreshDecision.FAILED)
        }
        if (result is SerializedCollectResult.Collected) {
            afterReconcile(series)
        }
        if (result is SerializedCollectResult.Collected && source != null) {
            val files = sourceCoverFiles
            val coverSource = source as? SeriesCoverSource
            if (files != null && coverSource != null) {
                ensureSerializedSourceCover(files, series, coverLocator, coverSource)
            }
        }
        // A complete or confirmed-empty list was checked, so the clock restarts.
        // That includes NEEDS_CONFIRMATION: otherwise every foreground refetches
        // the list until the prompt is answered. Confirmation records no updates.
        // Failure, partial, and unknown leave the clock and record nothing.
        val checked = mapped.decision == RefreshDecision.REFRESHED ||
            mapped.decision == RefreshDecision.NEEDS_CONFIRMATION
        if (checked &&
            (fetched.completeness == ChapterListCompleteness.COMPLETE ||
                fetched.completeness == ChapterListCompleteness.EMPTY_CONFIRMED)
        ) {
            val checkedAt = nowMs()
            if (mapped.decision == RefreshDecision.REFRESHED) {
                val mirrored = mirroredChapterIds(series)
                updates?.recordRefresh(
                    series = series,
                    seriesTitle = bookmark.title,
                    chapters = fetched.chapters,
                    newChapterIds = mapped.newChapterIds,
                    discoveredAtMillis = checkedAt,
                )
                updates?.pruneMirrored(series.providerNamespace, series.canonicalSeriesId, mirrored)
            }
            store.markCompleteRefresh(series, checkedAt)
            smartState?.write(series, SmartRefreshState(checkedAt, fetched.seriesStatus))
        }
        return mapped
    }

    private fun seriesFor(bookmark: CollectedBookmark) =
        SeriesIdentityV2(bookmark.providerId, bookmark.id)

    companion object {
        const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
