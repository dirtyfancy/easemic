package com.easemic.app.feature.source.jm

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.BrowseFeedNotice
import com.easemic.app.feature.browse.BrowseFeedResult
import com.easemic.app.feature.browse.SourceFilterState
import com.easemic.app.feature.browse.BrowsePrefs
import com.easemic.app.feature.browse.SourceFilterSheetShape
import com.easemic.app.feature.browse.browseOk
import com.easemic.app.feature.download.DownloadPageBytesAdapter
import com.easemic.app.feature.download.FetchFailureClass
import com.easemic.app.feature.download.FetchRetryContext
import com.easemic.app.feature.download.FetchTransportException
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.Liveness
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.SourceThrottlePolicy
import com.easemic.app.feature.download.parseSerializedChapterLocatorKey
import com.easemic.app.feature.source.ChapterListCompleteness
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.ComicSource
import com.easemic.app.feature.source.SourceCapabilities
import com.easemic.app.feature.source.SourceDescriptor
import com.easemic.app.feature.source.SeriesCoverSource
import com.easemic.app.feature.source.SerializedDetailSeedSource
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * #E83 (owner-approved copy, 2026-10-10): the browse error line says WHY the
 * wire failed, from the exception's own facts — never echoing request material:
 *  - statusCode 0 (DNS/connect/TLS, no HTTP response) or a non-transport error
 *    → unreachable, check the connection;
 *  - HTTP 401 (observed shape: `{"code":401,…,"errorMsg":"請先登入會員"}`)
 *    → an account-only surface;
 *  - any other real HTTP status → the source refused, quoted with its code;
 *  - statusCode 200 shapes (protocol invalid / error envelope) → the source
 *    refused, but there is no HTTP code worth showing.
 */
internal fun jmBrowseErrorCopy(displayName: String, error: Exception): String {
    val transport = error as? FetchTransportException
    return when {
        transport == null || transport.statusCode == 0 ->
            "Couldn't reach $displayName. Check your connection and try again."
        transport.statusCode == 401 ->
            "This part of $displayName needs an account. Browse and search still work."
        transport.statusCode in 300..599 ->
            "$displayName returned an error (HTTP ${transport.statusCode}). Try again later."
        else ->
            "$displayName returned an error. Try again later."
    }
}

/** Compiled anonymous source; constructors make no network requests. */
internal class JmSource(private val api: JmApi, override val pages: DownloadPageBytesAdapter = JmPages(api),    private val clock: () -> Long = System::currentTimeMillis,
    private val filterStateFor: (String) -> SourceFilterState = { SourceFilterState() },
    private val searchScopeFor: (String) -> com.easemic.app.feature.browse.JmSearchScope? = { null },
    private val enabled: () -> Boolean = { true },
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
) : ComicSource, SerializedDetailSeedSource, SeriesCoverSource {
    private data class RedirectSeed(val series: SeriesIdentityV2, val bornAt: Long, val result: ChapterListResult)
    private var redirectSeed: RedirectSeed? = null
    override val descriptor = SourceDescriptor(GalleryIdentityV1.JM, com.easemic.app.feature.providers.JmEnableStore.DISPLAY_NAME, true)
    override val capabilities = SourceCapabilities(SourceFilterSheetShape.JM)
    override val throttle = SourceThrottlePolicy.JM

    override suspend fun downloadSeriesCoverToFile(locator: String, destination: java.nio.file.Path, maxBytes: Long): Long =
        api.coverToFile(coverAlbumId(locator), destination, maxBytes)

    override suspend fun browse(prefs: BrowsePrefs, query: String, continuation: String?): BrowseFeedResult {
        synchronized(this) { redirectSeed = null }
        if (!enabled()) return browseOk(emptyList())
        val filters = filterStateFor(descriptor.namespace).jm
        // The root passes this source-owned token back verbatim, keeping fallback pages unscoped.
        val allResults = continuation?.startsWith("a") == true
        val scope = if (query.isNotBlank() && !allResults) searchScopeFor(query) else null
        return try {
            val page = if (continuation == null) 1 else continuation.removePrefix("a")
                .takeIf { Regex("[1-9][0-9]*").matches(it) }
                ?.toIntOrNull()
                ?: JmFailure.invalid()
            api.domains.refresh()
            val image = imageOrigin()
            fun decode(payload: JmPayload) = JmDecoder.catalogue(
                (payload as? JmPayload.Present)?.json ?: JmFailure.invalid(), page, image,
            )
            var catalogue = decode(if (query.isBlank()) api.latest(page, filters) else api.search(query, page, filters, scope))
            // A numeric redirect is an album match even though its catalogue rows are empty.
            val fallback = if (continuation == null && catalogue.redirect == null) {
                JmSearchFallback.decide(page, scope, catalogue.items.size) as? JmSearchFallbackDecision.Fallback
            } else null
            if (fallback != null) catalogue = decode(api.search(query, 1, filters, null))
            if (catalogue.redirect == null) {
                browseOk(
                    catalogue.items,
                    catalogue.next?.let { if (allResults || fallback != null) "a$it" else it },
                    catalogue.total,
                    notice = fallback?.let { BrowseFeedNotice.JmScopedFallback(it.scope, query) },
                )
            } else {
                val album = when (val result = api.album(catalogue.redirect)) {
                    is JmPayload.Present -> JmDecoder.album(result.json, catalogue.redirect)
                    JmPayload.Missing -> null
                }
                album?.let {
                    synchronized(this) {
                        redirectSeed = RedirectSeed(
                            SeriesIdentityV2(GalleryIdentityV1.JM, it.id),
                            monotonicClock(),
                            it.chapterList(clock()),
                        )
                    }
                }
                browseOk(
                    album?.let { listOf(albumItem(it, image)) }.orEmpty(),
                    totalItems = if (album == null) 0 else 1,
                    notice = BrowseFeedNotice.ALBUM_MATCH,
                )
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { BrowseFeedResult.Error(jmBrowseErrorCopy(descriptor.displayName, error)) }
    }

    @Synchronized
    override fun takeDetailSeed(series: SeriesIdentityV2): ChapterListResult? {
        val saved = redirectSeed ?: return null
        redirectSeed = null
        val age = monotonicClock() - saved.bornAt
        return saved.result.takeIf { saved.series == series && age in 0L..60_000L }
    }

    override suspend fun chapters(series: SeriesIdentityV2): ChapterListResult {
        // A fresh refresh or liveness check supersedes any earlier numeric-search handoff,
        // including when it fails. It must never let an old album revive removed chapters.
        synchronized(this) { redirectSeed = null }
        if (series.providerNamespace != GalleryIdentityV1.JM) JmFailure.invalid()
        val id = JmDecoder.id(series.canonicalSeriesId)
        api.domains.refresh()
        return when (val result = api.album(id)) {
            is JmPayload.Present -> JmDecoder.album(result.json, id).chapterList(clock())
            JmPayload.Missing -> ChapterListResult(emptyList(), clock(), ChapterListCompleteness.EMPTY_CONFIRMED)
        }
    }
    override suspend fun liveness(acquisitionUrl: String, feedUrl: String?): Liveness {
        val parsed = parseSerializedChapterLocatorKey(acquisitionUrl) ?: return Liveness.UNKNOWN
        if (parsed.first.providerNamespace != GalleryIdentityV1.JM) return Liveness.UNKNOWN
        return try {
            JmDecoder.id(parsed.second)
            // A separate album request is the liveness veto, never a cached page/CDN verdict.
            val result = chapters(parsed.first)
            when (result.completeness) {
                ChapterListCompleteness.EMPTY_CONFIRMED -> Liveness.DEAD
                ChapterListCompleteness.COMPLETE -> if (result.chapters.any { it.identity.canonicalChapterId == parsed.second }) Liveness.ALIVE else Liveness.DEAD
                else -> Liveness.UNKNOWN
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Liveness.UNKNOWN }
    }
    override fun classifyFailure(statusCode: Int, resolvedUrl: String?, responseBody: ByteArray,
        errorMessage: String?, responseHeaders: Map<String, String>, retryContext: FetchRetryContext): FetchFailureClass =
        JmFailure.classify(statusCode, responseBody,
            if (resolvedUrl == null) responseHeaders else responseHeaders.filterKeys { !it.equals(JmFailure.PROVENANCE_HEADER, true) },
            retryContext, clock())

    private suspend fun imageOrigin(): HttpUrl = api.imageOrigin()

    private fun coverAlbumId(locator: String): String {
        val url = runCatching { locator.toHttpUrl() }.getOrNull() ?: JmFailure.invalid()
        // The stored locator is canonical metadata and may point at an image
        // origin that JM has since rotated out. Only its album path is used;
        // JmApi resolves the current image origin and rotates it on failure.
        if (url.scheme != "http" && url.scheme != "https") JmFailure.invalid()
        val match = Regex("""/media/albums/([0-9]+)_3x4\.jpg""").matchEntire(url.encodedPath)
            ?: JmFailure.invalid()
        return JmDecoder.id(match.groupValues[1])
    }

    private fun albumItem(album: JmAlbum, image: HttpUrl) = BrowseFeedItem(
        id = album.id, title = album.name, secondaryLine = album.authors.joinToString(" · "),
        providerId = GalleryIdentityV1.JM, acquisitionUrl = "jm://album/${album.id}",
        coverUrl = JmDecoder.cover(image, album.id), pageCount = album.totalPhotos, artists = album.authors,
        author = album.authors.joinToString(", ").takeIf { it.isNotBlank() },
        tags = album.tags, uploadDateEpochSeconds = album.addedSeconds,
        seriesMetadata = album.metadata,
    )
}
