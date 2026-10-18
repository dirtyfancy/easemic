package com.easemic.app.feature.browse

import com.easemic.app.feature.source.ChapterListCompleteness
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.SeriesDate
import com.easemic.app.feature.source.SeriesDetailSnapshot
import com.easemic.app.feature.source.SeriesPublicationStatus
import com.easemic.app.feature.source.valueOrNull
import com.easemic.app.i18n.tr
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
        sourceKnown -> tr("$count $noun", "$count 章", "$count 章", "$count チャプター")
        partial -> tr("$count $noun loaded", "已加载 $count 章", "已載入 $count 章", "$count チャプターを読み込み済み")
        else -> tr("$count saved $noun", "已保存 $count 章", "已儲存 $count 章", "保存済み $count チャプター")
    }
    val status = when (snapshot?.status) {
        SeriesPublicationStatus.ONGOING -> tr("Ongoing", "连载中", "連載中", "連載中")
        SeriesPublicationStatus.COMPLETED -> tr("Completed", "已完结", "已完結", "完結")
        else -> null
    }
    val pages = snapshot?.metadata?.totalPages?.valueOrNull()?.takeIf { it > 0 }
    val singleJm = sourceName == "禁漫天堂" && sourceKnown && count == 1
    val line = listOfNotNull(
        sourceName,
        status,
        countText,
        pages?.takeIf { singleJm }?.let {
            tr("$it ${if (it == 1) "page" else "pages"}", "$it 页", "$it 頁", "$it ページ")
        },
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
        "${if (updated != null) tr("Updated", "更新于", "更新於", "更新") else tr("Published", "发布于", "發佈於", "公開")} · " +
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(it)
    }
    val removed = removedIds.intersect(savedIds).size
    return SerializedDetailFacts(
        line = line,
        dateLine = dateLine,
        removedLine = removed.takeIf { it > 0 }?.let {
            tr(
                "$it removed ${if (it == 1) "chapter" else "chapters"} kept",
                "已保留 $it 个已移除章节",
                "已保留 $it 個已移除章節",
                "削除済みチャプター $it 件を保存中",
            )
        },
    )
}
