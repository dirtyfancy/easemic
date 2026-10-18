package com.easemic.app.feature.browse

import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.providers.CloudCollectionSource

/**
 * #E97: the item a serialized detail's "Read online" chapter action runs with.
 * A cloud-library card carries the cloud collection's own provider id, but its
 * chapters are read online from the SOURCE series the card was saved from
 * ([series]) — so that case is remapped to the source namespace. Any other item
 * already names its source and reads online unchanged.
 */
internal fun onlineReadItemFor(
    remoteItem: BrowseFeedItem,
    series: SeriesIdentityV2,
    originAcquisition: String? = null,
): BrowseFeedItem =
    if (remoteItem.providerId == CloudCollectionSource.SOURCE_ID) {
        remoteItem.copy(
            id = series.canonicalSeriesId,
            providerId = series.providerNamespace,
            acquisitionUrl = originAcquisition ?: remoteItem.upstreamAcquisitionUrl,
        )
    } else {
        remoteItem
    }
