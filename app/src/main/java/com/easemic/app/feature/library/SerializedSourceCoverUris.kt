package com.easemic.app.feature.library

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.CloudCollectionFeedMapper
import com.easemic.app.feature.collect.CollectedBookmark
import com.easemic.app.feature.collect.isSerializedBookmark
import com.easemic.app.feature.download.SeriesIdentityV2

/**
 * The serialized identities the Library shelf may show a source cover for:
 * every followed series reached through a cloud series card plus every
 * serialized collected bookmark. Gallery cloud cards and non-serialized
 * bookmarks never carry source covers, so they are excluded here.
 */
fun serializedSourceCoverIdentities(
    cloudItems: List<BrowseFeedItem>,
    bookmarks: List<CollectedBookmark>,
): Set<SeriesIdentityV2> = buildSet {
    cloudItems
        .filter(CloudCollectionFeedMapper::isSeries)
        .forEach { add(SeriesIdentityV2(it.providerId, it.id)) }
    bookmarks
        .filter(::isSerializedBookmark)
        .forEach { add(SeriesIdentityV2(it.providerId, it.id)) }
}

/**
 * Resolves each identity's published source cover through [uriFor], keeping
 * only identities that have one. [uriFor] stats and opens files, so callers
 * run this off the composition thread.
 */
fun serializedSourceCoverUris(
    identities: Set<SeriesIdentityV2>,
    uriFor: (SeriesIdentityV2) -> String?,
): Map<SeriesIdentityV2, String> = identities.mapNotNull { identity ->
    uriFor(identity)?.let { identity to it }
}.toMap()
