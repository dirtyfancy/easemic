package com.easemic.app.feature.download

import com.easemic.app.feature.providers.EXHENTAI_UNAUTHORIZED_DETAIL
import com.easemic.app.feature.providers.NhentaiCookie
import com.easemic.app.importing.ehentai.EhentaiClient
import com.easemic.app.importing.ehentai.EhentaiException
import com.easemic.app.importing.ehentai.EhentaiHttpResponse
import com.easemic.app.importing.ehentai.EhentaiHttpTransfer
import com.easemic.app.importing.ehentai.EhentaiTransferResponse
import com.easemic.app.importing.ehentai.EhentaiTransferProtocolException
import com.easemic.app.importing.ehentai.StallWatchdog
import com.easemic.app.importing.nhentai.NhentaiClient
import com.easemic.app.importing.nhentai.NhentaiException
import com.easemic.app.importing.nhentai.NhentaiHttpTransfer
import com.easemic.app.importing.nhentai.NhentaiHttpResponse
import com.easemic.app.importing.nhentai.NhentaiSlowNodeException
import com.easemic.app.importing.nhentai.NhentaiTransferProtocolException
import com.easemic.app.importing.nhentai.NhentaiTransferResponse
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.CancellationException

/**
 * Pure mapping functions extracted from the AppGraph image-get / manifest
 * boundary so they can be unit-tested without an Android context. The e-h
 * image-quota sentinel is served as an HTTP 200 whose final URL ends
 * `/509.gif` (or `/509s.gif`) — this must be caught here, before the bytes
 * are accepted as a comic page, because the URL is only visible at this
 * boundary (the loop only ever sees decoded bytes otherwise).
 *
 * Every thrown path funnels into [FetchTransportException] so
 * [DownloadWorkerLoop] can later call [classifyFetchFailure] uniformly,
 * regardless of whether the failure came from the raw HTTP response or a
 * laundered client exception (`EhentaiException` / `NhentaiException`).
 */

private const val FRONT_509_SUFFIX_A = "/509.gif"
private const val FRONT_509_SUFFIX_B = "/509s.gif"

/** A page image getter that also exposes the disk-backed transfer operation. */
class PageImageGet(
    private val bytes: (String) -> ByteArray,
    private val transfer: suspend (String, Path, Long, Boolean) -> PageTransferResult,
) : (String) -> ByteArray {
    /** Sources that do not re-roll (nhentai, most tests) ignore [allowSlowSwitch]. */
    constructor(
        bytes: (String) -> ByteArray,
        transfer: suspend (String, Path, Long) -> PageTransferResult,
    ) : this(bytes, { url, destination, offset, _ -> transfer(url, destination, offset) })

    override operator fun invoke(url: String): ByteArray = bytes(url)

    suspend fun transferTo(
        url: String,
        destination: Path,
        offset: Long,
        allowSlowSwitch: Boolean = false,
    ): PageTransferResult = transfer(url, destination, offset, allowSlowSwitch)
}

/** H@H file id embedded in image URLs, when the URL is well formed. */
data class HathFileId(val sha1: String, val bytes: Long)

fun parseHathFileId(url: String): HathFileId? {
    val match = Regex("/h/([0-9a-fA-F]{40})-(\\d+)-").find(url) ?: return null
    val bytes = match.groupValues[2].toLongOrNull() ?: return null
    return HathFileId(match.groupValues[1].lowercase(), bytes.takeIf { it >= 0L } ?: return null)
}

/**
 * Stream one HTTP image into [destination]. Range responses are accepted only
 * when their Content-Range starts at [offset] and carries the expected total;
 * a resume 200 truncates and consumes that response, while invalid 206/416
 * responses restart once from a fresh `.part`.
 */
suspend fun streamHttpPageToFile(
    url: String,
    destination: Path,
    offset: Long = 0L,
    cookieHeader: String? = null,
    firstByteMs: Long = 5_000L,
    floorWindowMs: Long = StallWatchdog.DEFAULT_WINDOW_MS,
    floorBytes: Long = StallWatchdog.DEFAULT_MIN_BYTES_PER_WINDOW,
): PageTransferResult {
    val response = try {
        EhentaiClient.defaultTransferWithStallWatchdog(
            url = url,
            destination = destination,
            offset = offset,
            cookieHeader = cookieHeader,
            firstByteMs = firstByteMs,
            floorWindowMs = floorWindowMs,
            floorBytes = floorBytes,
            beforeBody = ::validateFrontTransferHeaders,
        )
    } catch (error: EhentaiTransferProtocolException) {
        throw InvalidPageTransferException(error.message ?: "invalid page transfer protocol", error)
    } catch (error: CancellationException) {
        throw error
    } catch (error: IOException) {
        throw ResumablePageTransferException("page connection interrupted", error)
    }
    val fileId = parseHathFileId(url)
    return PageTransferResult(
        bytesWritten = response.bytesWritten,
        expectedBytes = fileId?.bytes ?: response.expectedBytes,
        expectedSha1 = fileId?.sha1,
        resolvedUrl = response.resolvedUrl,
        transferredBytes = response.transferredBytes,
    )
}

private fun validateFrontTransferHeaders(
    code: Int,
    resolvedUrl: String,
    headers: Map<String, String>,
) {
    if (resolvedUrl.endsWith(FRONT_509_SUFFIX_A) || resolvedUrl.endsWith(FRONT_509_SUFFIX_B)) {
        throw FetchTransportException(
            statusCode = code,
            resolvedUrl = resolvedUrl,
            bodySample = ByteArray(0),
            headers = headers,
            message = "e-h 509 sentinel",
        )
    }
    if (code !in 200..299) {
        throw FetchTransportException(
            statusCode = code,
            resolvedUrl = resolvedUrl,
            bodySample = ByteArray(0),
            headers = headers,
            message = "Front image HTTP $code",
        )
    }
}

/**
 * Front (e-h) image-get response -> bytes, or throws [FetchTransportException]
 * for a non-2xx response or a 200 resolving to the /509(.s)?.gif quota sentinel.
 */
fun frontImageResponseToBytesOrThrow(response: EhentaiHttpResponse): ByteArray {
    val url = response.resolvedUrl.orEmpty()
    if (url.endsWith(FRONT_509_SUFFIX_A) || url.endsWith(FRONT_509_SUFFIX_B)) {
        throw FetchTransportException(
            statusCode = response.code,
            resolvedUrl = response.resolvedUrl,
            bodySample = sampledBody(response.body),
            headers = response.headers,
            message = "e-h 509 sentinel",
        )
    }
    if (response.code !in 200..299) {
        throw FetchTransportException(
            statusCode = response.code,
            resolvedUrl = response.resolvedUrl,
            bodySample = sampledBody(response.body),
            headers = response.headers,
            message = "Front image HTTP ${response.code}",
        )
    }
    return response.body
}

/** Converts a laundered [EhentaiException] back into a classifiable carrier. */
fun ehentaiErrorToTransport(error: EhentaiException): FetchTransportException =
    when (error) {
        is EhentaiException.ExhentaiUnauthorized -> FetchTransportException(
            // The sad-panda refusal is a 200 at heart: preserve the shape so
            // the persisted failure detail can carry the honest sign-in-again
            // copy (EXHENTAI_UNAUTHORIZED_DETAIL) downstream rather than
            // reading as a network-layer blip.
            statusCode = 200,
            resolvedUrl = null,
            bodySample = ByteArray(0),
            message = EXHENTAI_UNAUTHORIZED_DETAIL,
            cause = error,
        )
        is EhentaiException.Http -> FetchTransportException(
            statusCode = error.code,
            resolvedUrl = null,
            bodySample = ByteArray(0),
            message = error.message,
            cause = error,
        )
        is EhentaiException.RateLimited -> FetchTransportException(
            statusCode = 429,
            resolvedUrl = null,
            bodySample = ByteArray(0),
            message = error.message,
            cause = error,
        )
        is EhentaiException.Parse -> FetchTransportException(
            statusCode = 0,
            resolvedUrl = null,
            bodySample = ByteArray(0),
            message = error.message,
            cause = error,
        )
        is EhentaiException.Interstitial -> FetchTransportException(
            statusCode = error.response.code,
            resolvedUrl = error.response.resolvedUrl,
            bodySample = sampledBody(error.response.body),
            headers = error.response.headers,
            message = error.message,
            cause = error,
        )
    }

/**
 * Builds the production `imageGet` callback wired into [FrontDownloadPageAdapter]
 * for the page-IMAGE fetch (SEND_BACK item 4, AppGraph scope extension —
 * owner bug backlog): routes through [EhentaiClient.defaultGetWithStallWatchdog],
 * not the plain [EhentaiClient.defaultGet] every OTHER e-h caller (imports,
 * gallery/list/page-view HTML) keeps — so a live-but-stalled H@H connection
 * is aborted (`EHENTAI_STALL`) well before the download worker's own outer
 * deadline, instead of trickling for the full 30s.
 *
 * Exception shape is unchanged from the pre-extension inline AppGraph
 * lambda: only [EhentaiException] is laundered into [FetchTransportException]
 * here via [ehentaiErrorToTransport]. A raw stall [java.io.IOException]
 * thrown by [EhentaiClient.defaultGetWithStallWatchdog] is NOT an
 * [EhentaiException] (it never was) and is left to propagate exactly as
 * before — [DownloadWorkerLoop]'s existing "non-transport throwable ->
 * JITTER" fallback for per-page failures already classifies it, so this
 * adds no new classification path.
 *
 * Extracted out of `AppGraph.kt` (Android-bound: `Context`, Room, ... — not
 * directly unit-testable) purely so this wiring choice — the watchdog entry
 * point, not the plain one — has JVM coverage. [stallWindowMs],
 * [stallMinBytesPerWindow], [httpGet] and [httpTransfer] are test-only override points:
 * AppGraph never supplies them, so production always resolves to the real
 * [EhentaiClient.defaultGetWithStallWatchdog] at its own defaults.
 */
fun frontPageImageGet(
    cookie: () -> String?,
    beforeRequest: () -> Unit = {},
    stallWindowMs: Long = StallWatchdog.DEFAULT_WINDOW_MS,
    stallMinBytesPerWindow: Long = StallWatchdog.DEFAULT_MIN_BYTES_PER_WINDOW,
    noFirstByteMs: Long = StallWatchdog.DEFAULT_NO_FIRST_BYTE_MS,
    httpGet: (url: String, cookieHeader: String?) -> EhentaiHttpResponse = { url, cookieHeader ->
        EhentaiClient.defaultGetWithStallWatchdog(
            url,
            cookieHeader = cookieHeader,
            stallWindowMs = stallWindowMs,
            stallMinBytesPerWindow = stallMinBytesPerWindow,
            noFirstByteMs = noFirstByteMs,
        )
    },
    httpTransfer: EhentaiHttpTransfer? = null,
): PageImageGet {
    val bytes: (String) -> ByteArray = { url ->
        beforeRequest()
        try {
            frontImageResponseToBytesOrThrow(httpGet(url, cookie()))
        } catch (error: EhentaiException) {
            throw ehentaiErrorToTransport(error)
        }
    }
    return PageImageGet(
        bytes = bytes,
        transfer = { url, destination, offset, allowSlowSwitch ->
            beforeRequest()
            val response = if (httpTransfer != null) {
                httpTransfer(
                    url,
                    destination,
                    offset,
                    cookie(),
                    noFirstByteMs,
                    stallWindowMs,
                    stallMinBytesPerWindow,
                )
            } else {
                // Flag off → the transport skips the slow check entirely.
                EhentaiClient.defaultTransferWithStallWatchdog(
                    url = url,
                    destination = destination,
                    offset = offset,
                    cookieHeader = cookie(),
                    firstByteMs = noFirstByteMs,
                    floorWindowMs = stallWindowMs,
                    floorBytes = stallMinBytesPerWindow,
                    beforeBody = ::validateFrontTransferHeaders,
                    allowSlowSwitch = allowSlowSwitch,
                )
            }
            transferResponseToPageResult(url, response)
        },
    )
}

private fun transferResponseToPageResult(
    url: String,
    response: EhentaiTransferResponse,
): PageTransferResult {
    validateFrontTransferHeaders(
        code = response.code,
        resolvedUrl = response.resolvedUrl.orEmpty(),
        headers = response.headers,
    )
    val fileId = parseHathFileId(url)
    return PageTransferResult(
        bytesWritten = response.bytesWritten,
        expectedBytes = fileId?.bytes ?: response.expectedBytes,
        expectedSha1 = fileId?.sha1,
        resolvedUrl = response.resolvedUrl,
        transferredBytes = response.transferredBytes,
    )
}

/**
 * Index (nhentai) image-get response -> bytes, or throws [FetchTransportException]
 * for a non-2xx response. Index has no known 200-carries-error sentinel today.
 */
fun indexImageResponseToBytesOrThrow(response: NhentaiHttpResponse): ByteArray {
    if (response.code !in 200..299) {
        throw FetchTransportException(
            statusCode = response.code,
            resolvedUrl = response.resolvedUrl,
            bodySample = sampledBody(response.body),
            headers = response.headers,
            message = "Index image HTTP ${response.code}",
        )
    }
    return response.body
}

/** Disk-transfer counterpart of [indexImageResponseToBytesOrThrow]. */
fun indexTransferResponseToPageResult(response: NhentaiTransferResponse): PageTransferResult {
    if (response.code !in 200..299) {
        throw FetchTransportException(
            statusCode = response.code,
            resolvedUrl = response.resolvedUrl,
            bodySample = response.bodySample,
            headers = response.headers,
            message = "Index image HTTP ${response.code}",
        )
    }
    return PageTransferResult(
        bytesWritten = response.bytesWritten,
        expectedBytes = response.expectedBytes,
        resolvedUrl = response.resolvedUrl,
        transferredBytes = response.transferredBytes,
    )
}

/** Converts a laundered [NhentaiException] back into a classifiable carrier. */
fun nhentaiErrorToTransport(error: NhentaiException): FetchTransportException =
    when (error) {
        is NhentaiException.Http -> FetchTransportException(
            statusCode = error.code,
            resolvedUrl = null,
            bodySample = ByteArray(0),
            message = error.message,
            cause = error,
        )
        is NhentaiException.RateLimited -> FetchTransportException(
            statusCode = 429,
            resolvedUrl = null,
            bodySample = ByteArray(0),
            message = error.message,
            cause = error,
        )
        is NhentaiException.ServerError -> FetchTransportException(
            statusCode = error.code,
            resolvedUrl = null,
            bodySample = sampledBody(error.body),
            headers = error.headers,
            message = error.message,
            cause = error,
        )
        is NhentaiException.Parse -> FetchTransportException(
            statusCode = 0,
            resolvedUrl = null,
            bodySample = ByteArray(0),
            message = error.message,
            cause = error,
        )
    }

/**
 * Index (nhentai) image-get wiring, extracted from the AppGraph
 * `indexDownloadPageAdapter` lambda (#E19 Task 5, controller review 1 item 3)
 * so the wiring choice — the watchdog transfer, the per-fetch cookie read,
 * and WHAT REACHES THE ADAPTER TYPED — has JVM coverage. The old AppGraph
 * lambda wrapped every IOException in [ResumablePageTransferException], which
 * would have laundered both of the host-failover signals below and made every
 * failover test pass with a fake while never firing on the device:
 *
 *  - [NhentaiSlowNodeException] passes through unwrapped — the adapter keys
 *    its i-host switch (and the prefix resume) on that exact type;
 *  - the first-byte stall (`NHENTAI_STALL: no first byte…`) passes through
 *    unwrapped — the adapter switches hosts on exactly that message shape.
 *
 * Any other IOException keeps today's [ResumablePageTransferException]
 * wrapper (a disconnect mid-body is resumable, not a host problem), and the
 * protocol/`NhentaiException` laundering is unchanged. [httpGet] and
 * [httpTransfer] are test-only override points: AppGraph never supplies them,
 * so production always resolves to the real [NhentaiClient] entry points.
 */
fun indexPageImageGet(
    cookie: () -> NhentaiCookie?,
    noFirstByteMs: Long = NhentaiClient.DEFAULT_FIRST_BYTE_MS,
    stallWindowMs: Long = StallWatchdog.DEFAULT_WINDOW_MS,
    stallMinBytesPerWindow: Long = StallWatchdog.DEFAULT_MIN_BYTES_PER_WINDOW,
    httpGet: (url: String, cookieHeader: String?, userAgentOverride: String?) -> NhentaiHttpResponse =
        { url, cookieHeader, userAgentOverride ->
            NhentaiClient.defaultGet(url, cookieHeader = cookieHeader, userAgentOverride = userAgentOverride)
        },
    httpTransfer: NhentaiHttpTransfer? = null,
): PageImageGet {
    val bytes: (String) -> ByteArray = { url ->
        try {
            // Read the cookie store fresh per image fetch (not once at
            // construction) so a Cloudflare solve that lands mid-run is
            // picked up by the very next page fetch instead of only the next
            // gallery manifest.
            val current = cookie()
            indexImageResponseToBytesOrThrow(
                httpGet(url, current?.cookieHeader(), current?.userAgent),
            )
        } catch (error: NhentaiException) {
            throw nhentaiErrorToTransport(error)
        }
    }
    return PageImageGet(
        bytes = bytes,
        transfer = { url, destination, offset, allowSlowSwitch ->
            try {
                val current = cookie()
                val response = if (httpTransfer != null) {
                    httpTransfer(
                        url,
                        destination,
                        offset,
                        current?.cookieHeader(),
                        current?.userAgent,
                        noFirstByteMs,
                        stallWindowMs,
                        stallMinBytesPerWindow,
                    )
                } else {
                    // Flag off → the transport skips the slow check entirely.
                    NhentaiClient.defaultTransferWithStallWatchdog(
                        url = url,
                        destination = destination,
                        offset = offset,
                        cookieHeader = current?.cookieHeader(),
                        userAgentOverride = current?.userAgent,
                        firstByteMs = noFirstByteMs,
                        floorWindowMs = stallWindowMs,
                        floorBytes = stallMinBytesPerWindow,
                        allowSlowSwitch = allowSlowSwitch,
                    )
                }
                indexTransferResponseToPageResult(response)
            } catch (error: NhentaiSlowNodeException) {
                throw error
            } catch (error: NhentaiTransferProtocolException) {
                throw InvalidPageTransferException(
                    error.message ?: "invalid nhentai page transfer protocol",
                    error,
                )
            } catch (error: IOException) {
                if (error.message?.startsWith(NhentaiClient.FIRST_BYTE_STALL_PREFIX) == true) throw error
                throw ResumablePageTransferException("nhentai page connection interrupted", error)
            } catch (error: NhentaiException) {
                throw nhentaiErrorToTransport(error)
            }
        },
    )
}

private fun sampledBody(body: ByteArray): ByteArray =
    body.copyOf(minOf(body.size, FetchTransportException.BODY_SAMPLE_LIMIT))
