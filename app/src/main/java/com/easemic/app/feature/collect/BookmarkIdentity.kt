package com.easemic.app.feature.collect

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.seriesIdentityFor
import com.easemic.app.feature.library.galleryDedupKey

private val KNOWN_SERIALIZED_PROVIDERS = setOf("zaimanhua", "jm")

fun isSerializedBookmark(bookmark: CollectedBookmark): Boolean =
    bookmark.serialized || bookmark.providerId in KNOWN_SERIALIZED_PROVIDERS

fun bookmarkMatchesItem(bookmark: CollectedBookmark, item: BrowseFeedItem): Boolean {
    if (isSerializedBookmark(bookmark) || isSerializedItem(item)) {
        return isSerializedBookmark(bookmark) &&
            bookmark.providerId == item.providerId &&
            bookmark.id == item.id
    }
    val bookmarkKey = galleryDedupKey(bookmark.acquisitionUrl)
    val itemKey = galleryDedupKey(item.acquisitionUrl ?: item.upstreamAcquisitionUrl)
    return when {
        bookmarkKey != null && itemKey != null -> bookmarkKey == itemKey
        else -> bookmark.providerId == item.providerId && bookmark.id == item.id
    }
}

fun bookmarksForItem(
    all: List<CollectedBookmark>,
    item: BrowseFeedItem,
): List<CollectedBookmark> = all.filter { bookmarkMatchesItem(it, item) }

fun bookmarkIdentityKey(bookmark: CollectedBookmark): String =
    if (isSerializedBookmark(bookmark)) {
        "serialized:${bookmark.providerId.length}:${bookmark.providerId}:${bookmark.id.length}:${bookmark.id}"
    } else {
        "gallery:${galleryDedupKey(bookmark.acquisitionUrl) ?: "${bookmark.providerId}\u0000${bookmark.id}"}"
    }

private fun isSerializedItem(item: BrowseFeedItem): Boolean =
    item.providerId in KNOWN_SERIALIZED_PROVIDERS ||
        item.acquisitionUrl?.substringBefore("://") in KNOWN_SERIALIZED_PROVIDERS
