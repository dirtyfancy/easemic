package com.easemic.app.feature.browse

import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.sync.CloudSeriesManifest

/** The provider series whose chapters and transfers a serialized detail presents. */
fun serializedDetailSeries(manifest: CloudSeriesManifest?, item: BrowseFeedItem): SeriesIdentityV2 =
    manifest?.let { SeriesIdentityV2(it.providerId, it.providerSeriesId ?: it.seriesAcquisitionRef) }
        ?: seriesIdentityFor(item)
