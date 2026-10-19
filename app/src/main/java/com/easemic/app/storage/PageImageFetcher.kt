package com.easemic.app.storage

import android.content.Context
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.gif.AnimatedImageDecoder
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.toUri
import coil3.util.Logger
import com.easemic.app.feature.reader.ReaderImageProgressTracker
import com.easemic.app.feature.reader.readerImageProgressAppInterceptor
import com.easemic.app.feature.reader.readerImageProgressNetworkInterceptor
import com.easemic.app.model.PageRef
import com.easemic.app.net.SourceHttp
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okio.buffer
import okio.source
import okio.Path.Companion.toPath

class CoilDiskCacheOwner(
    directory: File,
    maxSizeBytes: Long = CacheBudget.COIL_PARTITION_BYTES,
) {
    val diskCache: DiskCache = DiskCache.Builder()
        .directory(directory.apply { mkdirs() }.absolutePath.toPath())
        .maxSizeBytes(maxSizeBytes.coerceAtLeast(1L))
        .build()

    val size: Long
        get() = diskCache.size

    fun clear() {
        diskCache.clear()
    }
}

class PageImageRequest internal constructor(
    internal val page: PageRef,
) {
    override fun equals(other: Any?): Boolean =
        other is PageImageRequest &&
            page.sourceId == other.page.sourceId &&
            page.locationId == other.page.locationId &&
            page.revisionId == other.page.revisionId &&
            page.ordinal == other.page.ordinal &&
            page.locatorKind == other.page.locatorKind

    override fun hashCode(): Int {
        var result = page.sourceId.hashCode()
        result = 31 * result + page.locationId.hashCode()
        result = 31 * result + page.revisionId.hashCode()
        result = 31 * result + page.ordinal
        result = 31 * result + page.locatorKind.hashCode()
        return result
    }

    override fun toString(): String =
        "PageImageRequest(" +
            "source=${page.sourceId.value}," +
            "location=${page.locationId.value}," +
            "revision=${page.revisionId.value}," +
            "ordinal=${page.ordinal}," +
            "kind=${page.locatorKind.name}" +
            ")"
}

class PageImageKeyer : Keyer<PageImageRequest> {
    override fun key(data: PageImageRequest, options: Options): String =
        pageImageCacheKeyString(data.page)
}

/**
 * The same opaque cache-key string [PageImageKeyer] computes for Coil, built
 * directly from a [PageRef] so callers that only have a page (not a full
 * [PageImageRequest]/[Options] pair) can compute it, e.g. to evict a specific
 * page from [ImageLoader.memoryCache] once it stops needing to be served from
 * cache.
 */
internal fun pageImageCacheKeyString(page: PageRef): String =
    buildString {
        append("private-page:")
        append(page.sourceId.value)
        append(':')
        append(page.locationId.value)
        append(':')
        append(page.revisionId.value)
        append(':')
        append(page.ordinal)
        append(':')
        append(page.locatorKind.name)
    }

/**
 * The [MemoryCache.Key] Coil computes internally for [buildPageImageRequest]
 * of [page] (no transformations are ever applied to page requests, so no
 * size extra is added to the key — see
 * `docs/research/2026-08-01-reader-coil-memory-cache-mechanism.md`).
 */
internal fun pageImageMemoryCacheKey(page: PageRef): MemoryCache.Key =
    MemoryCache.Key(pageImageCacheKeyString(page))

internal class PageImageFetcher internal constructor(
    private val data: PageImageRequest,
    private val options: Options,
    private val opener: PageContentOpener,
    private val imageSourceFactory: (InputStream, Options) -> ImageSource = { stream, requestOptions ->
        ImageSource(
            source = stream.source().buffer(),
            fileSystem = requestOptions.fileSystem,
        )
    },
    private val imageLoader: ImageLoader? = null,
) : Fetcher {
    override suspend fun fetch(): SourceFetchResult = when (val content = opener.openForImage(data.page)) {
        is PageContent.File -> {
            // The opener holds no stream here. Coil opens and closes the file source itself.
            val source = ImageSource(content.file.absolutePath.toPath(), options.fileSystem)
            try {
                currentCoroutineContext().ensureActive()
                SourceFetchResult(source, mimeType = null, dataSource = DataSource.DISK)
            } catch (error: Throwable) {
                rethrowAfterCleanup(error, source::close)
            }
        }
        is PageContent.ContentUri -> fetchContentUri(content)
        is PageContent.Stream -> fetchStream(content.stream)
    }

    private suspend fun fetchContentUri(content: PageContent.ContentUri): SourceFetchResult {
        val loader = checkNotNull(imageLoader)
        var source: ImageSource? = null
        return try {
            // Delegate to Coil's content fetcher to retain ContentMetadata(uri, descriptor).
            // AnimatedImageDecoder uses that descriptor with ImageDecoder.createSource on API 29+.
            // The opener holds no stream here; the delegated ImageSource owns Coil's descriptor.
            val fetcher = checkNotNull(loader.components.newFetcher(content.uri.toUri(), options, loader)).first
            val result = fetcher.fetch() as SourceFetchResult
            source = result.source
            // Coil opens a descriptor stream for header probing. Preserve the opener's read
            // error mapping and #E13 cancellation close, while retaining ContentMetadata so
            // the animated decoder can use the descriptor instead of copying the page.
            val descriptorStream = object : FilterInputStream(result.source.source().inputStream()) {
                override fun close() = result.source.close()
            }
            val stream = CancellationClosingInputStream(
                LocalPageContentOpener.SafeDocumentInputStream(descriptorStream),
                currentCoroutineContext()[Job],
            )
            val checkedSource = ImageSource(stream.source().buffer(), options.fileSystem, result.source.metadata)
            source = checkedSource
            currentCoroutineContext().ensureActive()
            SourceFetchResult(checkedSource, result.mimeType, result.dataSource)
        } catch (error: Throwable) {
            cleanupIgnoringFailure({ source?.close() })
            content.onFailure(error)
        }
    }

    private suspend fun fetchStream(input: InputStream): SourceFetchResult {
        val stream = CancellationClosingInputStream(
            input = input,
            job = currentCoroutineContext()[Job],
        )
        val source = try {
            imageSourceFactory(stream, options)
        } catch (error: Throwable) {
            rethrowAfterCleanup(error, stream::close)
        }
        return try {
            // Coil owns the ImageSource after this return. Check the hand-off
            // boundary so a cancellation observed between source construction
            // and SourceFetchResult creation cannot strand the opener stream.
            currentCoroutineContext().ensureActive()
            SourceFetchResult(
                source = source,
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        } catch (error: Throwable) {
            rethrowAfterCleanup(error, source::close, stream::close)
        }
    }

    class Factory(
        private val opener: PageContentOpener,
    ) : Fetcher.Factory<PageImageRequest> {
        override fun create(
            data: PageImageRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = PageImageFetcher(data, options, opener, imageLoader = imageLoader)
    }
}

/**
 * Coil's decoder factory probes a stream synchronously before it has entered
 * its cancellable decode block. Keep the transferred stream tied to the fetch
 * Job before constructing the ImageSource so source construction and decoder
 * probing both have a cancellation close path.
 */
@OptIn(InternalCoroutinesApi::class)
private class CancellationClosingInputStream(
    input: InputStream,
    job: Job?,
) : FilterInputStream(input) {
    // The cancellation handler and Coil's own close can race on two threads;
    // the opener stream (and its archive lease) is closed exactly once.
    private val closed = AtomicBoolean(false)

    init {
        job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { closeQuietly() }
    }

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        if (closed.compareAndSet(false, true)) runCatching { super.close() }
    }
}

/**
 * @param sessionCookie supplies the current E-Hentai session on each request,
 *   read fresh so a sign-in mid-scroll takes effect without rebuilding the
 *   loader. It is attached only for the hosts that own it — see
 *   [sessionCookieForImageHost].
 */
internal fun buildPageImageLoader(
    context: Context,
    opener: PageContentOpener,
    logger: Logger? = null,
    progressTracker: ReaderImageProgressTracker = ReaderImageProgressTracker.shared,
    sessionCookie: () -> String? = { null },
    diskCacheMaxSizeBytes: Long = CacheBudget.DEFAULT_BYTES,
    diskCache: DiskCache? = null,
    coverInterceptor: okhttp3.Interceptor? = null,
    authorizationFor: (String) -> String? = { null },
): ImageLoader {
    val networkClient = pageImageNetworkClient(
        sessionCookie = sessionCookie,
        progressTracker = progressTracker,
        coverInterceptor = coverInterceptor,
        authorizationFor = authorizationFor,
    )
    val ownedDiskCache = diskCache ?: DiskCache.Builder()
        .directory(File(context.cacheDir, "coil3_disk_cache").apply { mkdirs() }.absolutePath.toPath())
        .maxSizeBytes(diskCacheMaxSizeBytes.coerceAtLeast(1L))
        .build()
    val builder = ImageLoader.Builder(context)
        .diskCache(ownedDiskCache)
        .components {
        add(PageImageKeyer())
        add(PageImageFetcher.Factory(opener))
        // Coil 3 does not ship network fetching; HTTPS Browse covers need this.
        add(OkHttpNetworkFetcherFactory(callFactory = { networkClient }))
        // #E10: animated GIF/WebP pages. coil-gif also registers this decoder through ServiceLoader on API 28+
        // (minSdk is 29); registering it here keeps animation independent of service discovery.
        add(AnimatedImageDecoder.Factory())
    }
    if (logger != null) {
        builder.logger(logger)
    }
    return builder.build()
}

/**
 * Memory-cache policy for [buildPageImageRequest]. Extracted as a pure
 * function (no [Context] involved) so the default-disabled / opt-in-enabled
 * decision is directly JVM-testable — see
 * `docs/superpowers/specs/2026-08-01-reader-only-coil-memory-cache-design.md`.
 */
internal fun pageImageMemoryCachePolicy(memoryCacheEnabled: Boolean): CachePolicy =
    if (memoryCacheEnabled) CachePolicy.ENABLED else CachePolicy.DISABLED

/**
 * The reader's image transport (#E19 Task 6): seeded from [SourceHttp] like every other
 * source-transfer client, so the family-preference Dns (IPv4 first on the current network)
 * and its connect-evidence listener apply to reader page loads too. Everything the old
 * builder set is carried over verbatim: the UA/Accept/Cookie rewrite, the progress
 * interceptors in their original order, and no explicit timeouts or redirect settings.
 */
internal fun pageImageNetworkClient(
    sessionCookie: () -> String?,
    progressTracker: ReaderImageProgressTracker = ReaderImageProgressTracker.shared,
    coverInterceptor: okhttp3.Interceptor? = null,
    /**
     * Basic credentials of the OPDS catalog that owns a URL, or null. Komga serves cover thumbnails only with them.
     * Added before OkHttp follows redirects, and OkHttp drops Authorization on a redirect to another host.
     */
    authorizationFor: (String) -> String? = { null },
): OkHttpClient =
    SourceHttp.newBuilder()
        // Check each actual wire host, including redirects, before taking a cover permit.
        .apply { coverInterceptor?.let(::addNetworkInterceptor) }
        .addInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
                .header("User-Agent", PAGE_IMAGE_USER_AGENT)
                .header("Accept", "image/*,*/*")
            sessionCookieForImageHost(request.url.host, sessionCookie())?.let {
                builder.header("Cookie", it)
            }
            if (request.header("Authorization") == null) {
                authorizationFor(request.url.toString())?.let { builder.header("Authorization", it) }
            }
            chain.proceed(builder.build())
        }
        .addInterceptor(readerImageProgressAppInterceptor(progressTracker))
        .addNetworkInterceptor(readerImageProgressNetworkInterceptor(progressTracker))
        .build()

/**
 * Builds the Coil request for [page]. Memory caching is **opt-in** via
 * [memoryCacheEnabled] (default `false`): only the reader's visible-page and
 * prefetch call sites pass `true`, so a page's decoded bitmap can survive a
 * forward pager transition there. Library/Detail covers keep the default so
 * a cover never stays resident in Coil's memory cache — leaving one resident
 * would let a later cover redisplay silently reuse a stale bitmap instead of
 * reopening through [PageContentOpener], bypassing cover-open reauth /
 * unavailable detection. Disk and network stay disabled unconditionally.
 */
internal fun buildPageImageRequest(
    context: Context,
    page: PageRef,
    memoryCacheEnabled: Boolean = false,
    targetWidthPx: Int? = null,
    targetHeightPx: Int? = null,
): ImageRequest {
    val builder = ImageRequest.Builder(context)
        .data(PageImageRequest(page))
        .memoryCachePolicy(pageImageMemoryCachePolicy(memoryCacheEnabled))
        .diskCachePolicy(CachePolicy.DISABLED)
        .networkCachePolicy(CachePolicy.DISABLED)
        .allowHardware(false)
    val width = targetWidthPx
    val height = targetHeightPx
    if (width != null && height != null && width > 0 && height > 0) {
        builder.size(width, height)
        builder.precision(Precision.INEXACT)
        builder.scale(Scale.FIT)
    }
    return builder.build()
}
