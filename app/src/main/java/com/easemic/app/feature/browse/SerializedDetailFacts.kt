package com.easemic.app.feature.browse

import com.easemic.app.feature.source.ChapterListCompleteness
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.SeriesDate
import com.easemic.app.feature.source.SeriesDetailSnapshot
import com.easemic.app.feature.source.SeriesPublicationStatus
import com.easemic.app.feature.source.valueOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class SerializedDetailFacts(val line: String, val dateLine: String?, val removedLine: String?)

fun serializedDetailFacts(
    sourceName: String,
    snapshot: SeriesDetailSnapshot?,
    source: ChapterListResult?,
    sourceUnavailable: Boolean,
    savedIds: Set<String>,
    removedIds: Set<String>,
    zone: ZoneId,
    locale: Locale,
): SerializedDetailFacts {
    val sourceIds = source?.chapters?.map { it.identity.canonicalChapterId }?.toSet().orEmpty()
    val sourceKnown = !sourceUnavailable && source != null && source.completeness in setOf(
        ChapterListCompleteness.COMPLETE,
        ChapterListCompleteness.EMPTY_CONFIRMED,
    )
    val partial = !sourceUnavailable && source?.completeness == ChapterListCompleteness.PARTIAL
    val count = if (sourceKnown || partial) sourceIds.size else savedIds.size
    val noun = if (count == 1) "chapter" else "chapters"
    val countText = when {
        sourceKnown -> "$count $noun"
        partial -> "$count $noun loaded"
        else -> "$count saved $noun"
    }
    val status = when (snapshot?.status) {
        SeriesPublicationStatus.ONGOING -> "Ongoing"
        SeriesPublicationStatus.COMPLETED -> "Completed"
        else -> null
    }
    val pages = snapshot?.metadata?.totalPages?.valueOrNull()?.takeIf { it > 0 }
    val singleJm = sourceName == "禁漫天堂" && sourceKnown && count == 1
    val line = listOfNotNull(
        sourceName,
        status,
        countText,
        pages?.takeIf { singleJm }?.let { "$it ${if (it == 1) "page" else "pages"}" },
    ).joinToString(" · ")
    val updated = snapshot?.metadata?.updated?.valueOrNull()
    val published = snapshot?.metadata?.published?.valueOrNull()
    val date = updated ?: published
    val dateValue = when (date) {
        is SeriesDate.EpochSeconds -> Instant.ofEpochSecond(date.value).atZone(zone).toLocalDate()
        is SeriesDate.CalendarDate -> date.value
        null -> null
    }
    val dateLine = dateValue?.let {
        "${if (updated != null) "Updated" else "Published"} · " +
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(it)
    }
    val removed = removedIds.intersect(savedIds).size
    return SerializedDetailFacts(
        line = line,
        dateLine = dateLine,
        removedLine = removed.takeIf { it > 0 }?.let {
            "$it removed ${if (it == 1) "chapter" else "chapters"} kept"
        },
    )
}
