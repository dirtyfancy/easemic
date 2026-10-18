package com.easemic.app.feature.history

import com.easemic.app.feature.browse.BrowseFeedItem

data class HistoryReopenTarget(
    val item: BrowseFeedItem,
    val chapterId: String?,
    val initialOrdinal: Int?,
)

/** Rebuild a minimal feed row from persisted visit fields when the live feed misses. */
fun HistoryEntry.toBrowseFeedItemOrNull(): BrowseFeedItem? {
    val url = acquisitionUrl ?: return null
    return BrowseFeedItem(
        id = id,
        title = title,
        secondaryLine = secondaryLine,
        providerId = providerId,
        acquisitionUrl = url,
        coverUrl = coverUrl,
        pageCount = pageCount,
        pageStreamUrl = pageStreamUrl,
        upstreamAcquisitionUrl = upstreamAcquisitionUrl,
    )
}

/** Prefer a live feed hit by id; else reopen from stored acquisition payload. */
fun resolveHistoryOpen(
    entry: HistoryEntry,
    browseResults: List<BrowseFeedItem>,
): BrowseFeedItem? =
    resolveHistoryOpenTarget(entry, browseResults)?.item

fun resolveHistoryOpenTarget(
    entry: HistoryEntry,
    browseResults: List<BrowseFeedItem>,
): HistoryReopenTarget? {
    val item = browseResults.firstOrNull {
        if (entry.isSerializedHistory()) {
            it.providerId == entry.providerId && it.id == entry.id
        } else {
            it.id == entry.id && (entry.providerId.isBlank() || it.providerId.isBlank() || it.providerId == entry.providerId)
        }
    } ?: entry.toBrowseFeedItemOrNull()
    return item?.let {
        HistoryReopenTarget(
            item = it,
            chapterId = entry.lastReadChapterId,
            initialOrdinal = entry.lastReadPageOrdinal,
        )
    }
}
