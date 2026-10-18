package com.easemic.app.feature.library

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.collect.CollectedBookmark
import com.easemic.app.feature.collect.isSerializedBookmark

data class LibraryRemoteShelfCard(
    val id: String,
    val title: String,
    val secondaryLine: String,
    val coverUrl: String?,
    val coverPlaceholderTestTag: String,
    val coverImageTestTag: String,
    val contentDescription: String? = null,
    val newChapterCount: Int = 0,
)

object LibraryRemoteShelfCards {
    fun fromCollected(
        bookmarks: List<CollectedBookmark>,
        newChapterMarks: Map<String, Int> = emptyMap(),
    ): List<LibraryRemoteShelfCard> =
        bookmarks.map { bookmark ->
            LibraryRemoteShelfCard(
                id = bookmark.id,
                title = bookmark.title,
                secondaryLine = bookmark.providerId.ifBlank { "Collected" },
                coverUrl = bookmark.coverUrl,
                coverPlaceholderTestTag = "collected-cover-placeholder",
                coverImageTestTag = "collected-cover-image",
                newChapterCount = if (isSerializedBookmark(bookmark)) {
                    collectedBookmarkDedupKey(bookmark)?.let { newChapterMarks[it] } ?: 0
                } else {
                    0
                },
            )
        }

    fun fromCloud(items: List<BrowseFeedItem>): List<LibraryRemoteShelfCard> =
        items.map { item ->
            LibraryRemoteShelfCard(
                id = item.id,
                title = item.title,
                secondaryLine = item.secondaryLine.ifBlank { LibraryCloudSegmentCopy.segmentLabel },
                coverUrl = item.coverUrl,
                coverPlaceholderTestTag = "cloud-cover-placeholder",
                coverImageTestTag = "cloud-cover-image",
                contentDescription = "Cloud collection card ${item.title}",
            )
        }

    fun fromOpds(items: List<BrowseFeedItem>): List<LibraryRemoteShelfCard> =
        items.map { item ->
            LibraryRemoteShelfCard(
                id = item.id,
                title = item.title,
                secondaryLine = item.secondaryLine,
                coverUrl = item.coverUrl,
                coverPlaceholderTestTag = "opds-cover-placeholder",
                coverImageTestTag = "opds-cover-image",
            )
        }
}
