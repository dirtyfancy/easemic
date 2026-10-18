package com.easemic.app.feature.history

import com.easemic.app.i18n.tr

import com.easemic.app.feature.browse.CloudCollectionFeedMapper
import com.easemic.app.feature.providers.CloudCollectionSource
import com.easemic.app.feature.providers.OpdsProviderConfig
import com.easemic.app.feature.source.sourceDisplayNameFor

/**
 * Display name for a visit's source. Recorded as a snapshot on the entry;
 * resolved live only for known official ids and OPDS display names. A gallery
 * opened from its cloud card carries the cloud collection's id, so its
 * [upstreamAcquisitionUrl] names the source instead.
 */
fun historySourceNameFor(
    providerId: String,
    opdsProviders: List<OpdsProviderConfig> = emptyList(),
    upstreamAcquisitionUrl: String? = null,
): String {
    val upstream = CloudCollectionFeedMapper.upstreamSourceId(upstreamAcquisitionUrl)
        .takeIf { providerId == CloudCollectionSource.SOURCE_ID }
    return sourceDisplayNameFor(upstream ?: providerId, opdsProviders)
}

/** Singular at 1 (e.g. "1 hour ago"), plural otherwise -- same rule FolderRowCopy.relativeTime uses. */
private fun agoUnit(count: Long, unit: String): String {
    val en = if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"
    val (zh, zht, ja) = when (unit) {
        "hour" -> Triple("小时", "小時", "時間")
        "day" -> Triple("天", "天", "日")
        "month" -> Triple("个月", "個月", "か月")
        else -> Triple("年", "年", "年")
    }
    return tr(en, "$count ${zh}前", "$count ${zht}前", "$count ${ja}前")
}

/** English-only relative visit age (dev stage), same bucketing as the other Quiet Shelf relative lines. */
fun formatHistoryVisitAge(visitedAtEpochMs: Long, nowEpochMs: Long): String {
    val delta = (nowEpochMs - visitedAtEpochMs).coerceAtLeast(0L)
    val minutes = delta / 60_000L
    val hours = delta / 3_600_000L
    val days = delta / 86_400_000L
    val months = days / 30
    return when {
        minutes < 1 -> tr("just now", "刚刚", "剛剛", "たった今")
        minutes < 60 -> tr("$minutes min ago", "$minutes 分钟前", "$minutes 分鐘前", "$minutes 分前")
        hours < 24 -> agoUnit(hours, "hour")
        days < 30 -> agoUnit(days, "day")
        months < 12 -> agoUnit(months, "month")
        else -> agoUnit(days / 365, "year")
    }
}

fun historyResolvedSourceName(entry: HistoryEntry): String =
    entry.sourceName
        .takeUnless { it.isBlank() || it == CloudCollectionSource.DISPLAY_NAME }
        ?: historySourceNameFor(entry.providerId, upstreamAcquisitionUrl = entry.upstreamAcquisitionUrl)
            .ifBlank { entry.sourceName }

fun historyReadingPositionBadge(entry: HistoryEntry): String? {
    val chapter = entry.lastReadChapterLabel?.takeIf { it.isNotBlank() }
    val pageOneBased = entry.lastReadPageOrdinal?.takeIf { it >= 0 }?.plus(1)
    return when {
        chapter != null && pageOneBased != null -> "$chapter · p.$pageOneBased"
        chapter != null -> chapter
        pageOneBased != null -> "Page $pageOneBased"
        else -> null
    }
}

/**
 * Row meta line: `<source name> · <relative time>` (either side alone when the
 * other is unknown). Legacy entries recorded before the row upgrade carry no
 * stamped fields and keep their pre-upgrade secondary line.
 */
fun historyMetaLine(entry: HistoryEntry, nowEpochMs: Long): String {
    val source = historyResolvedSourceName(entry)
    val age = entry.visitedAtEpochMs?.let { formatHistoryVisitAge(it, nowEpochMs) }
    val chapter = entry.lastReadChapterLabel?.takeIf { it.isNotBlank() }
    return listOfNotNull(source.ifBlank { null }, chapter, age)
        .joinToString(" · ")
        .ifBlank { entry.secondaryLine }
}
