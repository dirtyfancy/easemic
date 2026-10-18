package com.easemic.app.feature.browse

/**
 * Deduped append and near-end trigger for Browse gallery infinite scroll.
 */
object BrowseFeedAppend {
    const val NEAR_END_REMAINING = 5

    fun merge(
        existing: List<BrowseFeedItem>,
        incoming: List<BrowseFeedItem>,
    ): List<BrowseFeedItem> {
        if (incoming.isEmpty()) return existing
        val seen = existing.mapTo(HashSet()) { remoteItemKey(it.providerId, it.id) }
        return existing + incoming.filter { seen.add(remoteItemKey(it.providerId, it.id)) }
    }

    fun shouldLoadMore(
        lastVisibleIndex: Int,
        totalItems: Int,
        nextContinuation: String?,
        loadingMore: Boolean,
    ): Boolean {
        if (loadingMore || nextContinuation == null || totalItems <= 0) return false
        if (lastVisibleIndex < 0) return false
        return lastVisibleIndex >= (totalItems - 1 - NEAR_END_REMAINING).coerceAtLeast(0)
    }
}
