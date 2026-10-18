package com.easemic.app.feature.browse

import com.easemic.app.sync.CloudCollectionCard
import com.easemic.app.sync.CloudCardLayout
import com.easemic.app.feature.collect.matchesSeriesIdentity
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.sync.CloudCollectLifecycle

/** Detail's cloud-card comparison; gallery aliases are retained here. */
internal fun cloudItemMatches(
    card: CloudCollectionCard,
    item: BrowseFeedItem,
    upstream: String? = item.upstreamAcquisitionUrl,
    peekedCloudId: String? = null,
): Boolean {
    val cloudId = CloudCollectionFeedMapper.parseCloudComicId(item.acquisitionUrl)
    if (cloudId != null) return card.cloudComicId == cloudId
    if (card.layoutKind == CloudCardLayout.SERIES) {
        return card.providerId == item.providerId && card.providerSeriesId == item.id
    }
    return CloudCollectLifecycle.cardMatches(card, item.acquisitionUrl, upstream, peekedCloudId, cloudId)
}
