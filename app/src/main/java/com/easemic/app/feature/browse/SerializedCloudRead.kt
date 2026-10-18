package com.easemic.app.feature.browse

import com.easemic.app.sync.CloudCollectionCard
import com.easemic.app.sync.CloudCardLayout

/** Resolve the cloud acquisition before opening a serialized chapter session. */
fun serializedCloudReadItem(item: BrowseFeedItem, cards: List<CloudCollectionCard>): BrowseFeedItem? =
    item.takeIf { CloudCollectionFeedMapper.parseCloudComicId(it.acquisitionUrl) != null }
        ?: cards.firstOrNull {
            it.layoutKind == CloudCardLayout.SERIES &&
                it.providerId == item.providerId && it.providerSeriesId == item.id
        }?.let { CloudCollectionFeedMapper.mapCard(it) }
