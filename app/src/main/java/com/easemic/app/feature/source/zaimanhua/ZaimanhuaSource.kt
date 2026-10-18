package com.easemic.app.feature.source.zaimanhua

import com.easemic.app.feature.browse.BrowseFeedResult
import com.easemic.app.feature.browse.BrowseFeedNotice
import com.easemic.app.feature.browse.BrowsePrefs
import com.easemic.app.feature.browse.SourceFilterSheetShape
import com.easemic.app.feature.browse.SourceFilterState
import com.easemic.app.feature.browse.ZGenre
import com.easemic.app.feature.download.DownloadPageBytesAdapter
import com.easemic.app.feature.download.FetchFailureClass
import com.easemic.app.feature.download.FetchRetryContext
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.Liveness
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.SourceThrottlePolicy
import com.easemic.app.feature.download.classifyFetchFailure
import com.easemic.app.feature.download.parseSerializedChapterLocatorKey
import com.easemic.app.feature.source.ChapterListCompleteness
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.ComicSource
import com.easemic.app.feature.source.over
import com.easemic.app.feature.source.SourceCapabilities
import com.easemic.app.feature.source.SourceDescriptor
import com.easemic.app.feature.source.SeriesCoverSource
import com.easemic.app.feature.providers.ZaimanhuaAccountStore
import com.easemic.app.importing.CatalogRequestGate
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** One constant keeps "listed by default" vs "opt-in" a single-line change. */
internal const val ZAIMANHUA_LISTED_IN_BROWSE = true

internal const val ZAIMANHUA_DISPLAY_NAME = "再漫画"

internal class ZaimanhuaSource(
    client: OkHttpClient,
    apiHost: HttpUrl = "https://v4api.zaimanhua.com".toHttpUrl(),
    pcHost: HttpUrl = "https://manhua.zaimanhua.com".toHttpUrl(),
    gate: CatalogRequestGate = CatalogRequestGate(
        minIntervalMillis = SourceThrottlePolicy.ZAIMANHUA.interRequestDelayMs,
    ),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val accountStore: ZaimanhuaAccountStore? = null,
    private val api: ZaimanhuaApi = ZaimanhuaApi(client, apiHost, pcHost, gate, ioDispatcher, accountStore),
    private val filterStateFor: (String) -> SourceFilterState = { SourceFilterState() },
) : ComicSource, SeriesCoverSource {
    override val descriptor = SourceDescriptor(
        namespace = GalleryIdentityV1.ZAIMANHUA,
        displayName = ZAIMANHUA_DISPLAY_NAME,
        serialized = true,
    )

    override val capabilities = SourceCapabilities(filterSheet = SourceFilterSheetShape.ZAIMANHUA)

    override val throttle: SourceThrottlePolicy = SourceThrottlePolicy.ZAIMANHUA

    override val pages: DownloadPageBytesAdapter = ZaimanhuaPages(api)

    override suspend fun downloadSeriesCoverToFile(locator: String, destination: java.nio.file.Path, maxBytes: Long): Long =
        api.imageToFile(locator, destination, maxBytes)

    override suspend fun browse(prefs: BrowsePrefs, query: String, continuation: String?): BrowseFeedResult {
        val page = continuation?.toIntOrNull()?.takeIf { it > 0 } ?: if (continuation == null) 1 else
            return BrowseFeedResult.Error("Couldn't reach $ZAIMANHUA_DISPLAY_NAME.")
        val filters = filterStateFor(descriptor.namespace).zaimanhua
        return try {
            val request = ZaimanhuaQueryMapper.from(filters, query, page)
            val body = api.browse(request)
            val result = when (request.route) {
                ZBrowseRoute.LATEST -> ZaimanhuaDecoder.latest(body, page)
                ZBrowseRoute.FILTER -> ZaimanhuaDecoder.filtered(body, page)
                ZBrowseRoute.SEARCH -> ZaimanhuaDecoder.search(body, page)
                ZBrowseRoute.RANK -> ZaimanhuaDecoder.ranking(body, page)
            }
            if (request.route == ZBrowseRoute.SEARCH || accountStore?.read()?.isSignedIn(clock()) == true) {
                result
            } else {
                result.copy(notice = BrowseFeedNotice.ZAIMANHUA_SIGNED_OUT)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            BrowseFeedResult.Error("Couldn't reach $ZAIMANHUA_DISPLAY_NAME.")
        }
    }

    suspend fun genres(): List<ZGenre> = ZaimanhuaDecoder.classify(api.classify())

    override suspend fun chapters(series: SeriesIdentityV2): ChapterListResult {
        val id = series.canonicalSeriesId.toLongOrNull() ?: zaimanhuaFailure("detail", "decode")
        val fetchedAt = clock()
        return when (val opened = ZaimanhuaDecoder.openDetail(api.detail(id), id, fetchedAt)) {
            is ZaimanhuaDecoder.Opened.Ready -> opened.result
            is ZaimanhuaDecoder.Opened.PcFallback -> {
                val pc = ZaimanhuaDecoder.pcDetail(api.pcDetail(id), id, fetchedAt)
                pc.copy(
                    metadata = pc.metadata.over(opened.metadata),
                    seriesStatus = if (pc.statusSupplied) pc.seriesStatus else opened.status,
                    statusSupplied = pc.statusSupplied || opened.statusSupplied,
                )
            }
        }
    }

    override suspend fun liveness(acquisitionUrl: String, feedUrl: String?): Liveness {
        val parsed = parseSerializedChapterLocatorKey(acquisitionUrl)
        if (parsed == null || parsed.first.providerNamespace != GalleryIdentityV1.ZAIMANHUA) {
            return Liveness.UNKNOWN
        }
        val (series, chapterId) = parsed
        return try {
            val list = chapters(series)
            when (list.completeness) {
                ChapterListCompleteness.EMPTY_CONFIRMED -> Liveness.DEAD
                ChapterListCompleteness.COMPLETE ->
                    if (list.chapters.any { it.identity.canonicalChapterId == chapterId }) {
                        Liveness.ALIVE
                    } else {
                        Liveness.DEAD
                    }
                else -> Liveness.UNKNOWN
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Liveness.UNKNOWN
        }
    }

    override fun classifyFailure(
        statusCode: Int,
        resolvedUrl: String?,
        responseBody: ByteArray,
        errorMessage: String?,
        responseHeaders: Map<String, String>,
        retryContext: FetchRetryContext,
    ): FetchFailureClass {
        val text = responseBody.decodeToString(throwOnInvalidSequence = false)
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json != null && json.has("errno") && json.optInt("errno") == 3) {
            return FetchFailureClass.GONE
        }
        if (MEMBERS_ONLY.containsMatchIn(text)) return FetchFailureClass.JITTER
        val errorInfo = responseHeaders.entries
            .firstOrNull { it.key.equals("Error-Info", ignoreCase = true) }
            ?.value
            .orEmpty()
        if (statusCode == 403 && (
                text.contains("time expire", ignoreCase = true) ||
                    errorInfo.contains("time expire", ignoreCase = true) ||
                    errorMessage?.contains("time expire", ignoreCase = true) == true
                )
        ) {
            return FetchFailureClass.JITTER
        }
        return classifyFetchFailure(
            providerNamespace = GalleryIdentityV1.ZAIMANHUA,
            statusCode = statusCode,
            resolvedUrl = resolvedUrl,
            responseBody = responseBody,
            errorMessage = errorMessage,
            responseHeaders = responseHeaders,
            retryContext = retryContext,
        )
    }

    private companion object {
        val MEMBERS_ONLY = Regex(""""canRead"\s*:\s*false""")
    }
}
