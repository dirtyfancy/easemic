package com.easemic.app.feature.browse

import com.easemic.app.feature.collect.CloudSyncIngestAutoRetry
import com.easemic.app.feature.collect.CloudSyncQueueItem
import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.isSerializedChapter
import com.easemic.app.feature.download.SeriesIdentityV2

/**
 * A partial cloud series remains pending until all its chapter transfers finish.
 *
 * #E103: a queue row that has spent its whole [CloudSyncIngestAutoRetry.MAX_ATTEMPTS]
 * budget is NOT pending — nothing on earth re-drives it (no periodic work, no
 * cold-start revive; only a user Retry or a rebind). Counting it as pending made
 * the collect switch read "Uploading to cloud" forever with the failure visible
 * nowhere but the Activity tab — the owner's permanently "stuck" chapter. The
 * chapter row carries the honest state instead ("Cloud copy failed · tap to
 * retry").
 */
fun serializedSeriesTransferPending(
    jobs: List<DownloadJob>,
    syncRows: List<CloudSyncQueueItem>,
    series: SeriesIdentityV2,
) = jobs.any {
    it.isSerializedChapter && it.identity.providerNamespace == series.providerNamespace &&
        it.identity.canonicalItemId == series.canonicalSeriesId &&
        (it.state == DownloadJobState.WAIT || it.state == DownloadJobState.DOWNLOAD || it.state == DownloadJobState.FAILED)
} || syncRows.any {
    it.providerId == series.providerNamespace && it.canonicalItemId == series.canonicalSeriesId &&
        !cloudUploadRowStranded(it)
}

/** True when this row can no longer make progress without a user action. */
internal fun cloudUploadRowStranded(row: CloudSyncQueueItem): Boolean =
    row.stage == com.easemic.app.feature.collect.CloudSyncStage.FAILED &&
        row.failedAttempts >= CloudSyncIngestAutoRetry.MAX_ATTEMPTS
