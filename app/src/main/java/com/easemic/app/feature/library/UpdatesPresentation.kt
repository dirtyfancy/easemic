package com.easemic.app.feature.library

import com.easemic.app.data.room.ChapterUpdateEntity
import com.easemic.app.feature.browse.CloudChapterRow
import com.easemic.app.feature.browse.SerializedReadRoute
import com.easemic.app.feature.browse.chapterRowPresentation
import com.easemic.app.feature.browse.serializedReadRoute
import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.model.LibraryItem
import com.easemic.app.i18n.AppStrings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class UpdateDayGroup(
    val label: String,
    val rows: List<UpdateRowPresentation>,
)

data class UpdateRowPresentation(
    val stableKey: String,
    val namespace: String,
    val seriesId: String,
    val chapterId: String,
    val seriesTitle: String,
    val chapterLabel: String,
    val stateText: String?,
    val seen: Boolean,
    val opensDetail: Boolean,
    val route: SerializedReadRoute?,
    val coverUrl: String? = null,
    /** Shelf item whose cover is loaded with the Library card's page request. Null for a bookmark-only series. */
    val coverLibraryItem: LibraryItem? = null,
    val libraryItemId: Long? = null,
    val readingUnitId: Long? = null,
    val progressOrdinal: Int? = null,
)

fun updateStableKey(namespace: String, seriesId: String, chapterId: String): String =
    listOf(namespace, seriesId, chapterId).joinToString("\u0000")

fun updateStableKey(row: ChapterUpdateEntity): String =
    updateStableKey(row.providerNamespace, row.providerSeriesId, row.providerChapterId)

fun parseUpdateStableKey(key: String): Triple<String, String, String>? {
    val parts = key.split('\u0000')
    if (parts.size != 3 || parts.any { it.isEmpty() }) return null
    return Triple(parts[0], parts[1], parts[2])
}

/**
 * Groups an Updates log by the device day of [ChapterUpdateEntity.discoveredAtMillis].
 * Today, then Yesterday, then the device's short date. Newest day and newest row first.
 */
fun updateDayGroups(
    rows: List<ChapterUpdateEntity>,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<UpdateDayGroup> {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val yesterday = today.minusDays(1)
    val shortDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.getDefault())
    return rows
        .groupBy { row ->
            val discoveredDayMillis = row.discoveredAtMillis
            Instant.ofEpochMilli(discoveredDayMillis).atZone(zone).toLocalDate()
        }
        .entries
        .sortedByDescending { it.key }
        .map { (date, group) ->
            UpdateDayGroup(
                label = when (date) {
                    today -> AppStrings.today
                    yesterday -> AppStrings.yesterday
                    else -> shortDate.format(date)
                },
                rows = group
                    .sortedWith(
                        compareByDescending<ChapterUpdateEntity> { it.discoveredAtMillis }
                            .thenBy { it.providerNamespace }
                            .thenBy { it.providerSeriesId }
                            .thenBy { it.providerChapterId },
                    )
                    .map { entity ->
                        UpdateRowPresentation(
                            stableKey = updateStableKey(entity),
                            namespace = entity.providerNamespace,
                            seriesId = entity.providerSeriesId,
                            chapterId = entity.providerChapterId,
                            seriesTitle = entity.seriesTitle,
                            chapterLabel = entity.chapterLabel,
                            stateText = null,
                            seen = entity.seenAtMillis != null,
                            opensDetail = false,
                            route = null,
                        )
                    },
            )
        }
}

/**
 * Projects one Updates row from the persisted shelf, job, and cloud facts.
 * [chapterRowPresentation] runs only when a shelf or cloud row still exists.
 * LOCAL and CLOUD always open; ONLINE opens only while the shelf is ACTIVE.
 */
fun projectUpdateRow(
    update: ChapterUpdateEntity,
    shelf: ChapterShelfRow?,
    job: DownloadJob?,
    cloud: CloudChapterRow?,
): UpdateRowPresentation {
    val stored = UpdateRowPresentation(
        stableKey = updateStableKey(update),
        namespace = update.providerNamespace,
        seriesId = update.providerSeriesId,
        chapterId = update.providerChapterId,
        seriesTitle = update.seriesTitle,
        chapterLabel = update.chapterLabel,
        stateText = null,
        seen = update.seenAtMillis != null,
        opensDetail = true,
        route = null,
        libraryItemId = shelf?.itemId,
        readingUnitId = shelf?.readingUnitId,
        progressOrdinal = shelf?.progressOrdinal,
    )
    if (shelf == null && cloud == null) return stored
    val presentation = chapterRowPresentation(source = null, shelf = shelf, job = job, cloud = cloud)
    val decided = serializedReadRoute(presentation.localReadable, presentation.cloudReadable)
    val readerRoute = when (decided) {
        SerializedReadRoute.LOCAL, SerializedReadRoute.CLOUD -> decided
        SerializedReadRoute.ONLINE -> if (shelf?.sourceState == "ACTIVE") decided else null
    }
    val opensDetail = readerRoute == null
    return stored.copy(
        chapterId = presentation.chapterId,
        chapterLabel = presentation.label,
        stateText = presentation.stateText,
        opensDetail = opensDetail,
        route = readerRoute,
        readingUnitId = presentation.readingUnitId ?: shelf?.readingUnitId,
    )
}
