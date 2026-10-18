package com.easemic.app.feature.collect

import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.sync.CloudSeriesManifest

/** An empty upload pump can be a gap between chapter downloads, not the end of the series. */
internal fun shouldFlushSeriesIndex(
    series: CloudSeriesManifest,
    uploads: List<CloudSyncQueueItem>,
    downloads: List<DownloadJob>,
    pumpDrained: Boolean,
): Boolean {
    if (downloads.any {
        it.identity.providerNamespace == series.providerId &&
            it.identity.canonicalItemId == series.providerSeriesId && it.intentCloud &&
            it.state in setOf(DownloadJobState.WAIT, DownloadJobState.DOWNLOAD)
    }) return false
    return pumpDrained || uploads.none {
        it.providerId == series.providerId && it.canonicalItemId == series.providerSeriesId
    }
}
