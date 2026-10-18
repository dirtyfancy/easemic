package com.easemic.app.feature.library

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.seriesIdentityFor
import com.easemic.app.feature.collect.CollectedBookmark
import com.easemic.app.feature.collect.isSerializedBookmark
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.acquisitionKey

/** Resolves a local serialized download back to the bookmark that owns its detail. */
@Suppress("UNUSED_PARAMETER")
fun serializedOpenTarget(
    itemId: Long,
    downloadIdentity: GalleryIdentityV1?,
    bookmarks: List<CollectedBookmark>,
): CollectedBookmark? {
    val identity = downloadIdentity ?: return null
    return bookmarks.firstOrNull { bookmark ->
        if (!isSerializedBookmark(bookmark)) return@firstOrNull false
        val series = seriesIdentityFor(
            BrowseFeedItem(
                id = bookmark.id,
                title = bookmark.title,
                providerId = bookmark.providerId,
                acquisitionUrl = bookmark.acquisitionUrl,
                coverUrl = bookmark.coverUrl,
                feedUrl = bookmark.feedUrl,
            ),
        )
        GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId) == identity
    }
}

/**
 * The serialized detail item for a local download that has no collect bookmark.
 *
 * #E73's download-only path deliberately writes no bookmark (the series is not
 * followed), so every "is this a serialized series?" gate that consulted the
 * bookmark store alone answered no and the row fell through to the gallery
 * detail shell. The identity itself is the fact that never depends on
 * collecting: its provider/id round-trips through `seriesIdentityFor` back to
 * the same series the chapter jobs and shelf rows are keyed by.
 */
fun localSerializedDetailItem(
    identity: GalleryIdentityV1,
    title: String,
    coverUrl: String?,
): BrowseFeedItem = BrowseFeedItem(
    id = identity.canonicalItemId,
    title = title.trim(),
    providerId = identity.providerNamespace,
    acquisitionUrl = identity.acquisitionKey(),
    coverUrl = coverUrl?.trim()?.takeIf { it.isNotEmpty() },
)
