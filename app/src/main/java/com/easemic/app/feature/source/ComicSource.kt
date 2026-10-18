package com.easemic.app.feature.source

import com.easemic.app.feature.browse.BrowseFeedResult
import com.easemic.app.feature.browse.BrowsePrefs
import com.easemic.app.feature.browse.SourceFilterSheetShape
import com.easemic.app.feature.download.DownloadPageBytesAdapter
import com.easemic.app.feature.download.FetchFailureClass
import com.easemic.app.feature.download.FetchRetryContext
import com.easemic.app.feature.download.Liveness
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.SourceThrottlePolicy

interface ComicSource {
    val descriptor: SourceDescriptor
    val capabilities: SourceCapabilities
    val throttle: SourceThrottlePolicy
    suspend fun browse(prefs: BrowsePrefs, query: String, continuation: String?): BrowseFeedResult
    suspend fun chapters(series: SeriesIdentityV2): ChapterListResult
    val pages: DownloadPageBytesAdapter
    suspend fun liveness(acquisitionUrl: String, feedUrl: String?): Liveness
    fun classifyFailure(
        statusCode: Int,
        resolvedUrl: String?,
        responseBody: ByteArray,
        errorMessage: String?,
        responseHeaders: Map<String, String>,
        retryContext: FetchRetryContext,
    ): FetchFailureClass
}

data class SourceDescriptor(
    val namespace: String,
    val displayName: String,
    val serialized: Boolean,
)

data class SourceCapabilities(
    val filterSheet: SourceFilterSheetShape,
)
