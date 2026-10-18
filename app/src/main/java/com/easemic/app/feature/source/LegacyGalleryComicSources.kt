package com.easemic.app.feature.source

import com.easemic.app.feature.browse.BrowseFeedResult
import com.easemic.app.feature.browse.BrowsePrefs
import com.easemic.app.feature.browse.EhentaiBrowseSource
import com.easemic.app.feature.browse.ExhentaiBrowseSource
import com.easemic.app.feature.browse.NhentaiBrowseSource
import com.easemic.app.feature.browse.OpdsBrowseSource
import com.easemic.app.feature.browse.SourceFilterSheetShape
import com.easemic.app.feature.download.ChapterIdentityV2
import com.easemic.app.feature.download.DownloadPageBytesAdapter
import com.easemic.app.feature.download.FetchFailureClass
import com.easemic.app.feature.download.FetchRetryContext
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.GalleryIdentityV2
import com.easemic.app.feature.download.Liveness
import com.easemic.app.feature.download.OpdsExistenceCheck
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.SourceLivenessOracle
import com.easemic.app.feature.download.SourceThrottlePolicy
import com.easemic.app.feature.download.classifyFetchFailure
import com.easemic.app.feature.providers.CloudCollectionSource
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.providers.ExhentaiEnableStore
import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.feature.providers.OpdsProviderConfig

private fun legacyGalleryChapters(series: SeriesIdentityV2): ChapterListResult =
    ChapterListResult(
        chapters = listOf(
            ChapterSummary(
                identity = ChapterIdentityV2(series, GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID),
                // SeriesIdentityV2 has no display title; use its stable ID for the synthetic row.
                label = series.canonicalSeriesId,
                group = null,
                groupOrder = 0,
                readingOrderInGroup = 0,
                sourceUpdatedAtMillis = null,
                canRead = true,
                isFee = false,
            ),
        ),
        fetchedAtMillis = System.currentTimeMillis(),
        completeness = ChapterListCompleteness.COMPLETE,
    )

/**
 * The cloud collection as a [ComicSource]: pages through the existing cloud adapter. Browse lists nothing — the Library
 * shelf shows cloud comics from the hydration mirror (3ccec44e), and Browse has not offered this source since
 * 3d866d6c (`normalizeBrowseSourceId` maps its id away). Liveness is [Liveness.UNKNOWN] because the download loop never
 * asks upstream about a cloud copy.
 */
internal class CloudCollectionComicSource(
    override val pages: DownloadPageBytesAdapter,
) : ComicSource {
    override val descriptor = SourceDescriptor(
        namespace = CloudCollectionSource.SOURCE_ID,
        displayName = CloudCollectionSource.DISPLAY_NAME,
        serialized = false,
    )

    override val capabilities = SourceCapabilities(
        filterSheet = SourceFilterSheetShape.NONE,
    )

    override val throttle: SourceThrottlePolicy = SourceThrottlePolicy.CLOUD

    override suspend fun browse(
        prefs: BrowsePrefs,
        query: String,
        continuation: String?,
    ): BrowseFeedResult = BrowseFeedResult.Ok(emptyList())

    override suspend fun chapters(series: SeriesIdentityV2): ChapterListResult = legacyGalleryChapters(series)

    override suspend fun liveness(acquisitionUrl: String, feedUrl: String?): Liveness =
        Liveness.UNKNOWN

    override fun classifyFailure(
        statusCode: Int,
        resolvedUrl: String?,
        responseBody: ByteArray,
        errorMessage: String?,
        responseHeaders: Map<String, String>,
        retryContext: FetchRetryContext,
    ): FetchFailureClass = classifyFetchFailure(
        providerNamespace = GalleryIdentityV1.CLOUD_COLLECTION,
        statusCode = statusCode,
        resolvedUrl = resolvedUrl,
        responseBody = responseBody,
        errorMessage = errorMessage,
        responseHeaders = responseHeaders,
        retryContext = retryContext,
    )
}

/** nhentai as a [ComicSource]: Browse through the moved branch, pages through the download adapter, liveness through C14. */
internal class NhentaiComicSource(
    private val browseSource: NhentaiBrowseSource,
    override val pages: DownloadPageBytesAdapter,
    private val livenessOracle: SourceLivenessOracle,
) : ComicSource {
    override val descriptor = SourceDescriptor(
        namespace = NhentaiEnableStore.SOURCE_ID,
        displayName = NhentaiEnableStore.DISPLAY_NAME,
        serialized = false,
    )

    override val capabilities = SourceCapabilities(filterSheet = SourceFilterSheetShape.NHENTAI)

    override val throttle: SourceThrottlePolicy =
        SourceThrottlePolicy.forNamespace(GalleryIdentityV1.NHENTAI)

    override suspend fun browse(
        prefs: BrowsePrefs,
        query: String,
        continuation: String?,
    ): BrowseFeedResult = browseSource.browse(prefs, query, continuation)

    override suspend fun chapters(series: SeriesIdentityV2): ChapterListResult = legacyGalleryChapters(series)

    override suspend fun liveness(acquisitionUrl: String, feedUrl: String?): Liveness =
        livenessOracle.check(GalleryIdentityV1.NHENTAI, acquisitionUrl)

    override fun classifyFailure(
        statusCode: Int,
        resolvedUrl: String?,
        responseBody: ByteArray,
        errorMessage: String?,
        responseHeaders: Map<String, String>,
        retryContext: FetchRetryContext,
    ): FetchFailureClass = classifyFetchFailure(
        providerNamespace = GalleryIdentityV1.NHENTAI,
        statusCode = statusCode,
        resolvedUrl = resolvedUrl,
        responseBody = responseBody,
        errorMessage = errorMessage,
        responseHeaders = responseHeaders,
        retryContext = retryContext,
    )
}

/** E-Hentai as a [ComicSource]: Browse through the moved front branch, pages through the worker adapter. */
internal class EhentaiComicSource(
    private val browseSource: EhentaiBrowseSource,
    override val pages: DownloadPageBytesAdapter,
    private val livenessOracle: SourceLivenessOracle,
) : ComicSource {
    override val descriptor = SourceDescriptor(
        namespace = EhentaiEnableStore.SOURCE_ID,
        displayName = EhentaiEnableStore.DISPLAY_NAME,
        serialized = false,
    )

    override val capabilities = SourceCapabilities(filterSheet = SourceFilterSheetShape.EHENTAI)

    override val throttle: SourceThrottlePolicy =
        SourceThrottlePolicy.forNamespace(GalleryIdentityV1.EHENTAI)

    override suspend fun browse(
        prefs: BrowsePrefs,
        query: String,
        continuation: String?,
    ): BrowseFeedResult = browseSource.browse(prefs, query, continuation)

    override suspend fun chapters(series: SeriesIdentityV2): ChapterListResult = legacyGalleryChapters(series)

    override suspend fun liveness(acquisitionUrl: String, feedUrl: String?): Liveness =
        livenessOracle.check(GalleryIdentityV1.EHENTAI, acquisitionUrl)

    override fun classifyFailure(
        statusCode: Int,
        resolvedUrl: String?,
        responseBody: ByteArray,
        errorMessage: String?,
        responseHeaders: Map<String, String>,
        retryContext: FetchRetryContext,
    ): FetchFailureClass = classifyFetchFailure(
        providerNamespace = GalleryIdentityV1.EHENTAI,
        statusCode = statusCode,
        resolvedUrl = resolvedUrl,
        responseBody = responseBody,
        errorMessage = errorMessage,
        responseHeaders = responseHeaders,
        retryContext = retryContext,
    )
}

/** ExHentai as a [ComicSource]: its Browse id is distinct, but galleries still use E-Hentai identities. */
internal class ExhentaiComicSource(
    private val browseSource: ExhentaiBrowseSource,
    override val pages: DownloadPageBytesAdapter,
    private val livenessOracle: SourceLivenessOracle,
) : ComicSource {
    override val descriptor = SourceDescriptor(
        namespace = ExhentaiEnableStore.SOURCE_ID,
        displayName = ExhentaiEnableStore.DISPLAY_NAME,
        serialized = false,
    )

    // #9: ExHentai is E-Hentai's sister mirror — identical f_cats/f_search/
    // advsearch grammar (web-confirmed 2026-09-09; loadExhentaiPage already
    // maps filter state through the same EhentaiQueryMapper), so it reuses the
    // E-Hentai sheet shape rather than showing no funnel.
    override val capabilities = SourceCapabilities(filterSheet = SourceFilterSheetShape.EHENTAI)

    override val throttle: SourceThrottlePolicy =
        SourceThrottlePolicy.forNamespace(GalleryIdentityV1.EHENTAI)

    override suspend fun browse(
        prefs: BrowsePrefs,
        query: String,
        continuation: String?,
    ): BrowseFeedResult = browseSource.browse(prefs, query, continuation)

    override suspend fun chapters(series: SeriesIdentityV2): ChapterListResult = legacyGalleryChapters(series)

    override suspend fun liveness(acquisitionUrl: String, feedUrl: String?): Liveness =
        livenessOracle.check(GalleryIdentityV1.EHENTAI, acquisitionUrl)

    override fun classifyFailure(
        statusCode: Int,
        resolvedUrl: String?,
        responseBody: ByteArray,
        errorMessage: String?,
        responseHeaders: Map<String, String>,
        retryContext: FetchRetryContext,
    ): FetchFailureClass = classifyFetchFailure(
        providerNamespace = GalleryIdentityV1.EHENTAI,
        statusCode = statusCode,
        resolvedUrl = resolvedUrl,
        responseBody = responseBody,
        errorMessage = errorMessage,
        responseHeaders = responseHeaders,
        retryContext = retryContext,
    )
}

/**
 * One configured OPDS catalog as a [ComicSource]. Liveness asks the catalog through the app's one
 * [OpdsExistenceCheck] with the Basic header [authorization] resolves for the acquisition URL — the same resolver
 * the download loop uses (#C12), so a provider sharing a host never gets another provider's credentials.
 */
internal class OpdsComicSource(
    val provider: OpdsProviderConfig,
    override val pages: DownloadPageBytesAdapter,
    private val browseSource: OpdsBrowseSource,
    private val existenceCheck: OpdsExistenceCheck,
    private val authorization: (acquisitionUrl: String) -> String?,
) : ComicSource {
    override val descriptor = SourceDescriptor(
        namespace = GalleryIdentityV1.opdsNamespace(provider.id),
        displayName = provider.displayName,
        serialized = false,
    )

    override val capabilities = SourceCapabilities(
        filterSheet = SourceFilterSheetShape.NONE,
    )

    override val throttle: SourceThrottlePolicy = SourceThrottlePolicy.OPDS_DEFAULT

    override suspend fun browse(
        prefs: BrowsePrefs,
        query: String,
        continuation: String?,
    ): BrowseFeedResult = browseSource.browse(prefs, query, continuation)

    override suspend fun chapters(series: SeriesIdentityV2): ChapterListResult = legacyGalleryChapters(series)

    /**
     * Drilling into an OPDS subsection feed is catalog navigation no other source has, so it is not part of
     * [ComicSource]; BrowseFeedRepository is its one caller. An unknown [explicitProviderId] falls back to the first
     * provider inside [OpdsBrowseSource], as it always did.
     */
    fun browseFeed(
        prefs: BrowsePrefs,
        query: String,
        feedUrl: String,
        explicitProviderId: String?,
    ): BrowseFeedResult = browseSource.browse(
        prefs = prefs,
        query = query,
        explicitFeedUrl = feedUrl,
        explicitProviderId = explicitProviderId,
    )

    override suspend fun liveness(acquisitionUrl: String, feedUrl: String?): Liveness =
        existenceCheck.check(
            acquisitionUrl = acquisitionUrl,
            feedUrl = feedUrl,
            authorizationHeader = authorization(acquisitionUrl),
        )

    override fun classifyFailure(
        statusCode: Int,
        resolvedUrl: String?,
        responseBody: ByteArray,
        errorMessage: String?,
        responseHeaders: Map<String, String>,
        retryContext: FetchRetryContext,
    ): FetchFailureClass = classifyFetchFailure(
        providerNamespace = descriptor.namespace,
        statusCode = statusCode,
        resolvedUrl = resolvedUrl,
        responseBody = responseBody,
        errorMessage = errorMessage,
        responseHeaders = responseHeaders,
        retryContext = retryContext,
    )
}
