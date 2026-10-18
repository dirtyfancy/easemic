package com.easemic.app.feature.activity

import com.easemic.app.data.room.SerializedChapterJob
import com.easemic.app.feature.collect.CloudSyncQueueItem
import com.easemic.app.feature.collect.CloudSyncStage
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.GalleryIdentityV2
import com.easemic.app.feature.download.DownloadQueueRow
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.downloadFailureReason
import com.easemic.app.feature.download.downloadRowParked

/**
 * The tail after "{n} chapters failed" for a cloud series row. Galleries show
 * [ActivityCopy.syncAttentionLine] whole; this keeps that sentence and only
 * drops a leading "failed · " so the count can take its place.
 */
internal fun serializedCloudAttentionDetail(raw: String): String {
    val spoken = ActivityCopy.syncAttentionLine(raw)
    val prefix = "failed · "
    return if (spoken.startsWith(prefix)) spoken.removePrefix(prefix) else spoken
}

/**
 * The one-row Activity projection for one serialized series. Chapter jobs are
 * counted in [failedJobIds]; cloud Retry uses [retryableCloudRows] so missing
 * inputs are never replayed. Gallery jobs never enter this projection.
 *
 * [isDownloading] is an implementation detail of the current-chapter copy:
 * when false, the current chapter came from WAIT and the line ends in
 * `· waiting`.
 */
data class SerializedActivitySummary(
    val providerId: String,
    val seriesId: String,
    val title: String,
    val coverUrl: String?,
    val totalChapters: Int,
    val savedChapters: Int,
    val currentChapterLabel: String?,
    val currentReady: Int,
    val currentExpected: Int?,
    val failedJobIds: List<String>,
    val failureReason: String?,
    /** True when this is the per-series cloud-upload row, rather than a local download row. */
    val isCloudSyncing: Boolean = false,
    /** Current cloud queue stage used by the foreground notification copy. */
    val currentCloudStage: CloudSyncStage? = null,
    internal val isDownloading: Boolean = false,
    val failedCloudRows: List<CloudSyncQueueItem> = emptyList(),
    /** UI-safe gallery wording for a chapter waiting out a source pause (challenge, rate limit or quota). */
    val sourcePauseReason: String? = null,
) {
    val retryableCloudRows: List<CloudSyncQueueItem>
        get() = failedCloudRows.filter { it.stage == CloudSyncStage.FAILED && syncRowRetryable(it, cloudBound = true) }
}

/** The pump's source-wide verdicts: each parks the chapter until its window ends. JITTER is a short backoff and stays quiet. */
private val SOURCE_PAUSE_CLASSES = setOf("PACED", "QUOTA", "ABUSE_STOP")

/**
 * Groups serialized Activity rows by their stable provider/series identity.
 * The input order is the queue's first-enqueue order, so the first row for a
 * series supplies its position and the first non-empty cover supplies its
 * artwork. Totals and saved chapters come from the same identity population;
 * in-memory callers without a population derive one from their rows.
 */
fun serializedActivitySummaries(
    rows: List<DownloadQueueRow>,
    populations: Map<SeriesIdentityV2, SerializedChapterPopulation> = emptyMap(),
    seriesTitles: Map<Pair<String, String>, String> = emptyMap(),
    nowMs: Long = System.currentTimeMillis(),
): List<SerializedActivitySummary> {
    val grouped = LinkedHashMap<Pair<String, String>, MutableList<DownloadQueueRow>>()
    rows.asSequence()
        .filter { it.seriesCanonicalId != null && it.chapterId != null }
        .filter { it.chapterId != GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID }
        .filter { it.state in setOf(DownloadJobState.WAIT, DownloadJobState.DOWNLOAD, DownloadJobState.FINISH, DownloadJobState.FAILED) }
        .forEach { row ->
            val key = row.providerId to row.seriesCanonicalId!!
            grouped.getOrPut(key) { mutableListOf() }.add(row)
        }

    val rowPopulations = serializedChapterPopulations(rows.mapNotNull { row ->
        val seriesId = row.seriesCanonicalId ?: return@mapNotNull null
        val chapterId = row.chapterId ?: return@mapNotNull null
        SerializedChapterJob(row.providerId, seriesId, chapterId, row.state.name)
    })
    return grouped.map { (key, seriesRows) ->
        val (providerId, seriesId) = key
        val series = SeriesIdentityV2(providerId, seriesId)
        val population = populations[series] ?: rowPopulations.getValue(series)
        val failed = seriesRows.filter { it.state == DownloadJobState.FAILED }
        // A DOWNLOAD row wins even if a WAIT row was enqueued earlier. When
        // nothing is running, the first WAIT row is the honest current chapter.
        val current = seriesRows.firstOrNull { it.state == DownloadJobState.DOWNLOAD }
            ?: seriesRows.firstOrNull { it.state == DownloadJobState.WAIT }
        SerializedActivitySummary(
            providerId = providerId,
            seriesId = seriesId,
            title = seriesTitles[key] ?: seriesId,
            coverUrl = seriesRows.firstNotNullOfOrNull { it.coverUrl },
            totalChapters = population.all.size,
            savedChapters = population.saved.size,
            currentChapterLabel = current?.title,
            currentReady = current?.readyPages ?: 0,
            currentExpected = current?.expectedPages,
            failedJobIds = failed.map { it.jobId },
            failureReason = failed.firstOrNull()?.let(::downloadFailureReason),
            isDownloading = current?.state == DownloadJobState.DOWNLOAD,
            // A parked sibling still explains the series even when the current
            // chapter is running. It is resumable, not a FAILED retry target.
            sourcePauseReason = seriesRows.firstNotNullOfOrNull { row ->
                if (row.failureClass in SOURCE_PAUSE_CLASSES && downloadRowParked(row, nowMs)) {
                    downloadFailureReason(row)
                } else null
            },
        )
    }
}

/**
 * Groups serialized cloud queue rows by their provider series. Legacy gallery
 * rows use [GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID] and stay out of this
 * projection so the gallery Activity path remains one row per comic.
 */
fun serializedCloudActivitySummaries(
    rows: List<CloudSyncQueueItem>,
    populations: Map<SeriesIdentityV2, SerializedChapterPopulation> = emptyMap(),
    seriesTitles: Map<Pair<String, String>, String> = emptyMap(),
): List<SerializedActivitySummary> {
    val grouped = LinkedHashMap<Pair<String, String>, MutableList<CloudSyncQueueItem>>()
    rows.asSequence()
        .filter { !it.providerId.isNullOrBlank() && !it.canonicalItemId.isNullOrBlank() }
        .filter { it.chapterIdentity != GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID }
        .forEach { row ->
            val key = row.providerId!! to row.canonicalItemId!!
            grouped.getOrPut(key) { mutableListOf() }.add(row)
        }

    val rowPopulations = serializedChapterPopulations(emptyList(), rows)
    return grouped.map { (key, seriesRows) ->
        val (providerId, seriesId) = key
        val failed = seriesRows.filter { it.stage == CloudSyncStage.FAILED }
        val current = seriesRows.firstOrNull { it.stage == CloudSyncStage.UPLOADING }
            ?: seriesRows.firstOrNull { it.stage == CloudSyncStage.FETCHING }
            ?: seriesRows.firstOrNull { it.stage == CloudSyncStage.WAITING }
        val series = SeriesIdentityV2(providerId, seriesId)
        val population = populations[series] ?: rowPopulations.getValue(series)
        SerializedActivitySummary(
            providerId = providerId,
            seriesId = seriesId,
            title = seriesTitles[key] ?: seriesId,
            coverUrl = seriesRows.firstNotNullOfOrNull { it.coverUrl },
            totalChapters = population.all.size,
            savedChapters = population.inCloud.size,
            currentChapterLabel = current?.title,
            currentReady = current?.readyPages ?: 0,
            currentExpected = current?.expectedPages,
            failedJobIds = failed.map { it.id },
            // Repair explanations win over a transient failure, independent of enqueue order.
            failureReason = failed.filterNot { syncRowRetryable(it, cloudBound = true) }
                .mapNotNull { it.errorMessage }.sorted().firstOrNull()
                ?: failed.mapNotNull { it.errorMessage }.sorted().firstOrNull(),
            failedCloudRows = failed,
            isCloudSyncing = true,
            currentCloudStage = current?.stage,
        )
    }
}
