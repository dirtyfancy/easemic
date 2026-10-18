package com.easemic.app.importing.nhentai

import com.easemic.app.feature.providers.NhentaiCookieStore
import com.easemic.app.importing.CatalogRequestGate
import com.easemic.app.importing.ehentai.StallWatchdog
import com.easemic.app.importing.ehentai.EhentaiClient
import com.easemic.app.net.FirstByteDeadline
import com.easemic.app.net.SourceHttp
import com.easemic.app.net.stallReadTimeoutMs
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

data class NhentaiHttpResponse(
    val code: Int,
    val body: ByteArray,
    /** Final URL after redirects. Null when unavailable. */
    val resolvedUrl: String? = null,
    /** Response headers, lowercased keys (for Retry-After, cf-mitigated, server). */
    val headers: Map<String, String> = emptyMap(),
)

/** Response metadata for the disk-backed nhentai image transfer. */
data class NhentaiTransferResponse(
    val code: Int,
    val bytesWritten: Long,
    val transferredBytes: Long = bytesWritten,
    val expectedBytes: Long? = null,
    val resolvedUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val bodySample: ByteArray = ByteArray(0),
)

class NhentaiTransferProtocolException(message: String) : IOException(message)

/**
 * Injectable transfer seam used by the index download adapter and its tests
 * (#E19 Task 5). Mirrors [com.easemic.app.importing.ehentai.EhentaiHttpTransfer]
 * plus the nhentai `userAgentOverride` pairing.
 */
typealias NhentaiHttpTransfer = suspend (
    url: String,
    destination: Path,
    offset: Long,
    cookieHeader: String?,
    userAgentOverride: String?,
    firstByteMs: Long,
    floorWindowMs: Long,
    floorBytes: Long,
) -> NhentaiTransferResponse

/**
 * #E19 Task 5: this attempt's own rate projects the bytes still to come past
 * [NhentaiClient.SLOW_PROJECTION_CAP_MS], so staying on this image host would
 * waste the lane for minutes. nhentai's i1–i4 image hosts are IPv4-only
 * mirrors with no quota, so the adapter answers by switching to the next host
 * and resuming from [bytesWritten] — the transfer is not cut by anything the
 * way an H@H node's 180 s session is, but a 12–24 KB/s path is exactly the
 * #E19 slow route the host list exists to escape. An [IOException], so if it
 * ever escapes the adapter it classifies as `statusCode=0` -> JITTER exactly
 * like any other network-layer failure.
 */
class NhentaiSlowNodeException(val bytesWritten: Long, message: String) : IOException(message)

fun interface NhentaiHttpGet {
    fun get(url: String): NhentaiHttpResponse
}

sealed class NhentaiException(message: String) : Exception(message) {
    class Http(val code: Int) : NhentaiException("NHENTAI_HTTP_$code")
    class RateLimited : NhentaiException("NHENTAI_HTTP_429")

    /**
     * A 5xx that carries the real response, so [classifyFetchFailure] can tell
     * an nh Cloudflare challenge (503 + `server: cloudflare` + challenge
     * markers) apart from a generic upstream outage instead of everything
     * getting laundered into [RateLimited] (429).
     */
    class ServerError(
        val code: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) : NhentaiException("NHENTAI_HTTP_$code")

    class Parse(message: String = "NHENTAI_PARSE") : NhentaiException(message)
}

/**
 * Anonymous nhentai HTTP client (public JSON API v2). Host constants are
 * compile-time only; UI never shows the upstream brand.
 */
class NhentaiClient(
    private val apiOrigin: String = DEFAULT_API_ORIGIN,
    private val imageOrigin: String = DEFAULT_IMAGE_ORIGIN,
    private val thumbOrigin: String = DEFAULT_THUMB_ORIGIN,
    private val httpGet: NhentaiHttpGet = NhentaiHttpGet { url -> defaultGet(url) },
    private val listParser: NhentaiListParser = NhentaiListParser(thumbOrigin = thumbOrigin),
    private val galleryParser: NhentaiGalleryParser = NhentaiGalleryParser(
        imageOrigin = imageOrigin,
        thumbOrigin = thumbOrigin,
    ),
    private val beforeRequest: () -> Unit = {},
    private val rateLimitSleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
) {
    fun fetchLatest(pageIndex: Int = 0): NhentaiListPage {
        val apiPage = pageIndex + 1
        return fetchList("/api/v2/galleries?page=$apiPage", pageIndex)
    }

    fun fetchSearch(query: String, pageIndex: Int = 0, sort: String? = null): NhentaiListPage {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val apiPage = pageIndex + 1
        val sortParam = sort?.let { "&sort=" + URLEncoder.encode(it, Charsets.UTF_8.name()) }.orEmpty()
        return fetchList("/api/v2/search?query=$encoded&page=$apiPage$sortParam", pageIndex)
    }

    fun fetchList(query: NhentaiListQuery): NhentaiListPage =
        if (query.useLatest) {
            fetchLatest(query.pageIndex)
        } else {
            fetchSearch(requireNotNull(query.searchQuery).trim(), query.pageIndex, query.sort)
        }

    fun fetchGallery(galleryId: Long): NhentaiGalleryPlan {
        val response = get("/api/v2/galleries/$galleryId")
        return try {
            galleryParser.parse(response.body)
        } catch (error: Exception) {
            throw NhentaiException.Parse(error.message ?: "NHENTAI_PARSE")
        }
    }

    private fun fetchList(path: String, pageIndex: Int): NhentaiListPage {
        val response = get(path)
        return try {
            listParser.parse(response.body, currentPageIndex = pageIndex)
        } catch (error: Exception) {
            throw NhentaiException.Parse(error.message ?: "NHENTAI_PARSE")
        }
    }

    private fun get(path: String): NhentaiHttpResponse {
        var attempt = 0
        while (true) {
            beforeRequest()
            val url = apiOrigin.trimEnd('/') + path
            val response = try {
                httpGet.get(url)
            } catch (error: Exception) {
                throw NhentaiException.Parse(redactUrl(error.message ?: "NHENTAI_NETWORK"))
            }
            when {
                response.code == 429 || response.code == 503 -> {
                    if (attempt >= 1) throw finalNonSuccessException(response)
                    attempt += 1
                    rateLimitSleeper(RATE_LIMIT_RETRY_MS)
                }
                response.code !in 200..299 -> throw finalNonSuccessException(response)
                else -> return response
            }
        }
    }

    /**
     * Terminal (no-more-retries) classification of a non-2xx response. A raw
     * 503 keeps its real headers/body via [NhentaiException.ServerError]
     * instead of being collapsed into [NhentaiException.RateLimited] —
     * nh's Cloudflare challenge arrives as 503, not 429, so laundering it into
     * a 429 here would hide the CF signal from the fetch-failure classifier.
     */
    private fun finalNonSuccessException(response: NhentaiHttpResponse): NhentaiException =
        when {
            response.code == 429 -> NhentaiException.RateLimited()
            response.code in 500..599 -> NhentaiException.ServerError(
                code = response.code,
                headers = response.headers,
                body = response.body,
            )
            else -> NhentaiException.Http(response.code)
        }

    companion object {
        const val DEFAULT_API_ORIGIN = "https://nhentai.net"
        const val DEFAULT_IMAGE_ORIGIN = "https://i.nhentai.net"
        const val DEFAULT_THUMB_ORIGIN = "https://t.nhentai.net"
        private const val RATE_LIMIT_RETRY_MS = 1_500L

        fun redactUrl(url: String): String =
            url.replace(Regex("""([?&]query=)[^&]+"""), "$1<redacted>")

        /**
         * Builds a client that sends the on-device `cf_clearance` cookie (and
         * its minting User-Agent — the cookie is UA-bound) on every request.
         * [store] is read lazily inside the request lambda, per request,
         * rather than once at construction — so a Cloudflare solve that lands
         * mid-run is picked up by this client's very next HTTP call instead
         * of requiring a fresh client instance.
         */
        fun fromCookie(
            store: NhentaiCookieStore,
            apiOrigin: String = DEFAULT_API_ORIGIN,
            beforeRequest: () -> Unit = { CatalogRequestGate.Shared.acquire() },
        ): NhentaiClient =
            NhentaiClient(
                apiOrigin = apiOrigin,
                httpGet = { url ->
                    val cookie = store.read()
                    defaultGet(url, cookieHeader = cookie?.cookieHeader(), userAgentOverride = cookie?.userAgent)
                },
                beforeRequest = beforeRequest,
            )

        /**
         * The shared nh transfer client (#E19): seeded by [SourceHttp] so the
         * family-preference Dns and its connect listener apply, with the exact
         * HttpURLConnection-era timeouts (connect 15s / read 30s). `callTimeout`
         * stays unset. Per-call read timeouts derive from it with `newBuilder()`,
         * which shares the connection pool.
         */
        private val sourceClient: OkHttpClient by lazy {
            SourceHttp.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Plain GET on the shared [sourceClient] unless a client is injected —
         * the #E19 Dns seam. Redirects are followed; [NhentaiHttpResponse.resolvedUrl]
         * is the final URL, as before. OkHttp's transparent gzip stays on, as
         * HttpURLConnection's was.
         */
        fun defaultGet(
            url: String,
            cookieHeader: String? = null,
            userAgentOverride: String? = null,
            client: OkHttpClient = sourceClient,
        ): NhentaiHttpResponse {
            val builder = Request.Builder().url(url)
                .header("User-Agent", userAgent(userAgentOverride))
                .header("Accept", "application/json,image/*,*/*")
            cookieHeader?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
            return client.newCall(builder.build()).execute().use { response ->
                NhentaiHttpResponse(
                    code = response.code,
                    body = response.body?.bytes() ?: ByteArray(0),
                    resolvedUrl = response.request.url.toString(),
                    headers = flattenHeaders(response.headers),
                )
            }
        }

        /**
         * Streams one nhentai image into [destination]. A retained prefix is
         * resumed only after a strict 206/Content-Range match; a 200 response
         * to a range request truncates and restarts from byte zero. Runs on
         * the shared [sourceClient] (per-call read timeout via `newBuilder()`,
         * pool shared) unless a client is injected — the #E19 Dns seam.
         *
         * #E19 Task 5: once per attempt, after [slowGraceMs] of transfer time,
         * an attempt whose rate is below [slowRateBytesPerSec] AND whose
         * projected remaining time exceeds [slowProjectionCapMs] throws
         * [NhentaiSlowNodeException] carrying the bytes on disk — the adapter
         * switches to the next i-host and resumes the prefix. The projection
         * counts THIS attempt's bytes only (a resumed prefix says nothing
         * about the new host's rate) over the bytes still to come; an unknown
         * Content-Length never triggers the check. The check runs only when
         * [allowSlowSwitch] is true: a caller that cannot switch hosts leaves
         * the attempt to finish or to be cut by the watchdog, exactly as
         * before #E19. [clockMs] drives this check and the transfer budget
         * only (monotonic by default); the stall watchdog and first-byte
         * deadline stay on the wall clock.
         */
        @OptIn(InternalCoroutinesApi::class)
        suspend fun defaultTransferWithStallWatchdog(
            url: String,
            destination: Path,
            offset: Long = 0L,
            cookieHeader: String? = null,
            userAgentOverride: String? = null,
            firstByteMs: Long = DEFAULT_FIRST_BYTE_MS,
            floorWindowMs: Long = StallWatchdog.DEFAULT_WINDOW_MS,
            floorBytes: Long = StallWatchdog.DEFAULT_MIN_BYTES_PER_WINDOW,
            client: OkHttpClient = sourceClient,
            slowGraceMs: Long = SLOW_GRACE_MS,
            slowRateBytesPerSec: Long = SLOW_RATE_BYTES_PER_SEC,
            slowProjectionCapMs: Long = SLOW_PROJECTION_CAP_MS,
            clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
            allowSlowSwitch: Boolean = false,
        ): NhentaiTransferResponse {
            suspend fun attempt(startOffset: Long, restarted: Boolean): NhentaiTransferResponse =
                withContext(Dispatchers.IO) {
                    val attemptClient = client.newBuilder()
                        .readTimeout(stallReadTimeoutMs(firstByteMs, floorWindowMs), TimeUnit.MILLISECONDS)
                        .build()
                    val call = attemptClient.newCall(
                        buildTransferRequest(url, startOffset, cookieHeader, userAgentOverride),
                    )
                    val closed = AtomicBoolean(false)
                    val cancelled = AtomicBoolean(false)
                    fun closeOnce() {
                        if (closed.compareAndSet(false, true)) {
                            runCatching { call.cancel() }
                        }
                    }
                    val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(
                        onCancelling = true,
                        invokeImmediately = true,
                    ) {
                        cancelled.set(true)
                        closeOnce()
                    }
                    val sawBodyByte = AtomicBoolean(false)
                    val deadline = FirstByteDeadline("NHENTAI_STALL", firstByteMs, sawBodyByte) { closeOnce() }
                    var restartFromZero = false
                    try {
                        val transferred = call.execute().use { response ->
                            val code = response.code
                            val headers = flattenHeaders(response.headers)
                            val resolvedUrl = response.request.url.toString()
                            val range = parseContentRange(headers["content-range"])
                            val contentLength = headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0L }
                            val rangeLengthMatches = range == null || contentLength == null ||
                                range.second - range.first + 1L == contentLength
                            val rangeIsValid = range?.let { (rangeStart, rangeEnd, rangeTotal) ->
                                rangeStart >= 0L && rangeEnd >= rangeStart &&
                                    rangeTotal != null && rangeTotal > rangeEnd && rangeLengthMatches
                            } == true
                            val resumeAccepted = if (startOffset == 0L) {
                                code in 200..299 && (code != 206 ||
                                    (rangeIsValid && range?.first == 0L))
                            } else {
                                code == 206 &&
                                    rangeIsValid && range?.first == startOffset
                            }

                            if (startOffset > 0L && !resumeAccepted && !restarted &&
                                (code == 206 || code == 416)
                            ) {
                                // Cancel this call before the restart: the rejected 206/416 response
                                // was not drained, so cancelling takes its connection out of the
                                // shared pool instead of leaving a half-read socket behind. The
                                // restarted call dials fresh.
                                closeOnce()
                                restartFromZero = true
                                null
                            } else {
                                if (code == 206 && !resumeAccepted) {
                                    throw NhentaiTransferProtocolException("nhentai HTTP 206 or invalid Content-Range")
                                }

                                if (code !in 200..299) {
                                    val bodySample = response.body?.byteStream()
                                        ?.let { readErrorSample(it) }
                                        ?: ByteArray(0)
                                    return@use NhentaiTransferResponse(
                                        code = code,
                                        bytesWritten = 0L,
                                        transferredBytes = 0L,
                                        expectedBytes = range?.third ?: contentLength,
                                        resolvedUrl = resolvedUrl,
                                        headers = headers,
                                        bodySample = bodySample,
                                    )
                                }

                                val input = BufferedInputStream(requireNotNull(response.body).byteStream())
                                Files.createDirectories(destination.parent)
                                val append = startOffset > 0L && code == 206
                                val outputOptions = arrayOf(
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    if (append) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING,
                                )
                                val attemptStart = if (append) startOffset else 0L
                                var written = attemptStart
                                val expected = range?.third ?: contentLength
                                val totalBudgetMs = EhentaiClient.transferBudgetMs(expected, floorWindowMs, floorBytes)
                                val startedAt = clockMs()
                                var slowNodeChecked = false
                                val watchdog = StallWatchdog(floorWindowMs, floorBytes, firstByteMs)
                                Files.newOutputStream(destination, *outputOptions).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val read = try {
                                            input.read(buffer)
                                        } catch (error: IOException) {
                                            deadline.stallIfDue(error)?.let { throw it }
                                            if (error is SocketTimeoutException) {
                                                watchdog.onProgress(System.currentTimeMillis(), 0)?.let {
                                                    throw IOException("NHENTAI_STALL: $it", error)
                                                }
                                                continue
                                            }
                                            throw error
                                        }
                                        if (read < 0) break
                                        if (read == 0) continue
                                        sawBodyByte.set(true)
                                        output.write(buffer, 0, read)
                                        written += read
                                        watchdog.onProgress(System.currentTimeMillis(), read)?.let {
                                            throw IOException("NHENTAI_STALL: $it")
                                        }
                                        val elapsedMs = clockMs() - startedAt
                                        // #E19 Task 5, once per attempt: an attempt whose rate projects
                                        // the bytes STILL TO COME past the cap is wasting the lane on a
                                        // slow route — abort so the caller can move to the next image
                                        // host, counting THIS attempt's bytes only (a resumed prefix
                                        // says nothing about the new host's rate).
                                        if (allowSlowSwitch &&
                                            !slowNodeChecked &&
                                            expected != null &&
                                            elapsedMs >= slowGraceMs
                                        ) {
                                            slowNodeChecked = true
                                            val transferredThisAttempt = written - attemptStart
                                            if (transferredThisAttempt > 0L) {
                                                val rateBytesPerSec = transferredThisAttempt * 1000L / elapsedMs
                                                val projectedRemainingMs =
                                                    elapsedMs * (expected - written) / transferredThisAttempt
                                                if (rateBytesPerSec < slowRateBytesPerSec &&
                                                    projectedRemainingMs > slowProjectionCapMs
                                                ) {
                                                    throw NhentaiSlowNodeException(
                                                        bytesWritten = written,
                                                        "node rate $rateBytesPerSec B/s projects " +
                                                            "${projectedRemainingMs}ms of the remaining bytes " +
                                                            "> cap ${slowProjectionCapMs}ms",
                                                    )
                                                }
                                            }
                                        }
                                        if (elapsedMs > totalBudgetMs) {
                                            throw IOException("nhentai page transfer exceeded ${totalBudgetMs}ms")
                                        }
                                    }
                                }
                                if (expected != null && written < expected) {
                                    throw IOException("nhentai page disconnected at $written of $expected bytes")
                                }
                                NhentaiTransferResponse(
                                    code = code,
                                    bytesWritten = written,
                                    transferredBytes = written - attemptStart,
                                    expectedBytes = expected,
                                    resolvedUrl = resolvedUrl,
                                    headers = headers,
                                )
                            }
                        }
                        if (restartFromZero) {
                            deadline.close()
                            Files.newOutputStream(
                                destination,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                            ).use { }
                            return@withContext attempt(0L, restarted = true)
                        }
                        transferred!!
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: IOException) {
                        if (cancelled.get() || !currentCoroutineContext().isActive) {
                            throw CancellationException("nhentai page transfer cancelled", error)
                        }
                        deadline.stallIfDue(error)?.let { throw it }
                        throw error
                    } finally {
                        deadline.close()
                        cancellation?.dispose()
                        closeOnce()
                    }
                }

            return attempt(offset.coerceAtLeast(0L), restarted = false)
        }

        /** The transfer's verbatim request shape: UA/Accept/Cookie plus `Range` on resume. */
        private fun buildTransferRequest(
            url: String,
            startOffset: Long,
            cookieHeader: String?,
            userAgentOverride: String?,
        ): Request {
            val builder = Request.Builder().url(url)
                .header("User-Agent", userAgent(userAgentOverride))
                .header("Accept", "application/json,image/*,*/*")
                // OkHttp's transparent gzip would strip Content-Length and Content-Range.
                .header("Accept-Encoding", "identity")
            cookieHeader?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
            if (startOffset > 0L) builder.header("Range", "bytes=$startOffset-")
            return builder.build()
        }

        private fun userAgent(userAgentOverride: String?): String =
            userAgentOverride?.trim().takeUnless { it.isNullOrEmpty() } ?: USER_AGENT

        private fun readErrorSample(input: InputStream): ByteArray = input.use { stream ->
            val sample = ByteArray(8 * 1024)
            val read = stream.read(sample)
            if (read > 0) sample.copyOf(read) else ByteArray(0)
        }

        private fun flattenHeaders(headers: Headers): Map<String, String> =
            headers.names().associate { name -> name.lowercase() to headers.values(name).joinToString(", ") }

        private fun parseContentRange(value: String?): Triple<Long, Long, Long?>? {
            val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
                .matchEntire(value?.trim().orEmpty()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].toLongOrNull()
            return Triple(start, end, total)
        }

        const val USER_AGENT = "PrivateComicLibrary/1.0 (nhentai; polite)"
        const val DEFAULT_FIRST_BYTE_MS = 30_000L

        /**
         * #E19 Task 5. nhentai's image hosts are same-path IPv4-only mirrors,
         * so the slow route is escaped by moving to another HOST, not another
         * address family. The same grace and rate floor as the E-Hentai slow
         * node switch; the cap bounds the remaining time this attempt's own
         * rate may project before the adapter is told to switch hosts (60 s of
         * trickle is where a page stops being worth the lane).
         */
        const val SLOW_GRACE_MS = 3_000L
        const val SLOW_RATE_BYTES_PER_SEC = 100L * 1024
        const val SLOW_PROJECTION_CAP_MS = 60_000L

        /**
         * Message head the first-byte deadline reports. The index adapter
         * host-switches on exactly this shape; a stall AFTER the first byte
         * (the rolling-floor watchdog) is the worker's retry job and never
         * switches.
         */
        const val FIRST_BYTE_STALL_PREFIX = "NHENTAI_STALL: no first byte"
    }
}
