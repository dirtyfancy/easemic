package com.easemic.app.feature.download

import com.easemic.app.feature.activity.SerializedActivitySummary
import com.easemic.app.feature.activity.serializedCloudAttentionDetail
import com.easemic.app.feature.collect.CloudSyncStage

/** The chapter noun follows the total, including when zero chapters are saved. */
fun chapterNoun(total: Int): String = if (total == 1) "chapter" else "chapters"

/** One line shared by the Activity series row and the foreground notice. */
fun serializedActivitySummaryText(
    summary: SerializedActivitySummary,
    includeTitle: Boolean = true,
    attention: Boolean = false,
): String {
    val body = if (attention) {
        val noun = if (summary.failedJobIds.size == 1) "chapter" else "chapters"
        buildString {
            append(summary.failedJobIds.size)
            append(' ')
            append(noun)
            append(" failed")
            val raw = summary.failureReason?.takeIf { it.isNotBlank() }
            val detail = if (summary.isCloudSyncing && raw != null) serializedCloudAttentionDetail(raw) else raw
            detail?.let {
                append(" · ")
                append(it)
            }
            val retryCount = summary.retryableCloudRows.size
            if (summary.isCloudSyncing && retryCount > 0 && retryCount < summary.failedCloudRows.size) {
                append(" · Retry retries ")
                append(retryCount)
                append(if (retryCount == 1) " chapter" else " chapters")
            }
        }
    } else if (summary.isCloudSyncing) {
        val chapter = when (summary.currentCloudStage) {
            CloudSyncStage.FETCHING -> summary.currentChapterLabel?.let { " · preparing $it" }.orEmpty()
            CloudSyncStage.WAITING -> " · waiting"
            CloudSyncStage.UPLOADING ->
                summary.currentChapterLabel?.let { " · uploading $it" }.orEmpty()
            CloudSyncStage.FAILED, null -> ""
        }
        "${summary.savedChapters} of ${summary.totalChapters} ${chapterNoun(summary.totalChapters)} in cloud$chapter"
    } else {
        val chapterTail = if (summary.sourcePauseReason != null) {
            " · ${summary.sourcePauseReason} · resumes automatically"
        } else if (summary.isDownloading && summary.currentChapterLabel != null) {
            val expected = summary.currentExpected?.toString() ?: "?"
            " · ${summary.currentChapterLabel} ${summary.currentReady} / $expected pages"
        } else if (!includeTitle) {
            " · waiting"
        } else {
            ""
        }
        "${summary.savedChapters} of ${summary.totalChapters} ${chapterNoun(summary.totalChapters)} saved$chapterTail"
    }
    return if (includeTitle) "${summary.title} · $body" else body
}

/**
 * The bare stage word a gallery row shows while its page count is still
 * unknown (no manifest yet). Shared by the Activity row and the foreground
 * notice so the two never drift.
 */
fun galleryStageLabel(state: DownloadJobState): String = when (state) {
    DownloadJobState.WAIT -> "Waiting"
    DownloadJobState.DOWNLOAD -> "Downloading"
    else -> state.name
}

/** Shared " · then cloud" tail for a cloud-bound gallery download. */
const val GALLERY_THEN_CLOUD_SUFFIX = " · then cloud"

/**
 * E15 Task 3a (review 3, final copy): the slow tail appended to a downloading
 * gallery line when at least one of its in-flight pages has been transferring
 * for [DownloadProgressState.SLOW_PAGE_AFTER_MS]. Zero slow pages append
 * nothing.
 */
fun slowPagesSuffix(slowPages: Int): String = when {
    slowPages <= 0 -> ""
    slowPages == 1 -> " · 1 page downloading slowly"
    else -> " · $slowPages pages downloading slowly"
}

/**
 * E15 Task 3a (review 3): the ONE gallery line — the Activity row and the
 * single-gallery foreground notice both call this, so the two surfaces can
 * never drift. `page R of E` when the page count is known, the stage word
 * when it is not; the slow tail, which describes the current stage; then
 * ` · then cloud` for a cloud-bound download. Parked and failed rows do not
 * come through here: they keep their existing attention lines ("paused ·
 * resumes automatically", "failed · tap Retry").
 */
fun galleryActivityLine(
    ready: Int,
    expected: Int?,
    stageLabel: String,
    cloudBound: Boolean = false,
    slowPages: Int = 0,
): String {
    val base = expected?.let { "page $ready of $it" } ?: stageLabel
    val withSlow = base + slowPagesSuffix(slowPages)
    return if (cloudBound) withSlow + GALLERY_THEN_CLOUD_SUFFIX else withSlow
}

/**
 * Formats the ongoing local-download foreground notice without exposing job
 * details. With exactly one active gallery and no active series, the notice
 * names that gallery and shows its page line (E15 review 3); any other
 * combination keeps the bounded series wording.
 */
fun downloadNotificationText(
    summaries: List<SerializedActivitySummary>,
    galleryActiveCount: Int,
    /** Today's active (WAIT/DOWNLOAD) non-serialized rows, used for the solo-gallery line. */
    activeGalleries: List<DownloadQueueRow> = emptyList(),
    /** jobId → in-flight pages of that job already slow ([DownloadProgressState]). */
    slowPagesByJobId: Map<String, Int> = emptyMap(),
): String {
    val activeSummaries = summaries.filter { it.currentChapterLabel != null }
    if (activeSummaries.isEmpty()) {
        val solo = activeGalleries.singleOrNull()
        if (solo != null && galleryActiveCount <= 1) {
            return "${solo.title} · " + galleryActivityLine(
                ready = solo.readyPages,
                expected = solo.expectedPages,
                stageLabel = galleryStageLabel(solo.state),
                slowPages = slowPagesByJobId[solo.jobId] ?: 0,
            )
        }
        return "Downloading"
    }
    if (activeSummaries.size == 1 && galleryActiveCount == 0) {
        return serializedActivitySummaryText(activeSummaries.single())
    }
    return buildString {
        append("Downloading · ")
        append(activeSummaries.size)
        append(" series")
        if (galleryActiveCount > 0) {
            append(" and ")
            append(galleryActiveCount)
            append(" more")
        }
    }
}
