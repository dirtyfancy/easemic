package com.easemic.app.importing.opds

import com.easemic.app.importing.ehentai.EhentaiClient
import com.easemic.app.importing.ehentai.StallWatchdog
import com.easemic.app.net.FirstByteDeadline
import com.easemic.app.net.SourceHttp
import com.easemic.app.net.stallReadTimeoutMs
import java.io.BufferedInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

data class OpdsHttpResponse(
    val code: Int,
    val body: ByteArray,
)

data class OpdsHttpRequest(
    val url: String,
    val authorizationHeader: String? = null,
    /**
     * #C12 audit B2: an upper bound on the response body this request will
     * read, in bytes. A liveness probe wants the feed's first entries, not a
     * multi-hundred-MB archive a mislabeled acquisition URL might return — so
     * the probe caps its reads. null means "read the whole body" (the catalog
     * feed reads that drive the UI stay uncapped).
     */
    val maxBytes: Int? = null,
)

sealed class OpdsCatalogException(message: String) : Exception(message) {
    class Http(val code: Int) : OpdsCatalogException("OPDS_HTTP_$code")
    class Parse(message: String = "OPDS_PARSE") : OpdsCatalogException(message)
}

fun interface OpdsHttpGet {
    fun get(request: OpdsHttpRequest): OpdsHttpResponse
}

data class OpdsFileTransferResponse(
    val code: Int,
    val bytesWritten: Long,
    val expectedBytes: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val bodySample: ByteArray = ByteArray(0),
)

/** Synchronous file sink used by the acquisition path; it never materializes the body. */
fun interface OpdsHttpFileGet {
    fun getToFile(
        request: OpdsHttpRequest,
        destination: Path,
        firstByteMs: Long,
        floorWindowMs: Long,
        floorBytes: Long,
    ): OpdsFileTransferResponse
}

class OpdsCatalogClient(
    private val httpGet: OpdsHttpGet = OpdsHttpGet { request -> defaultGet(request) },
    private val parser: OpdsAtomParser = OpdsAtomParser(),
) {
    fun fetchFeed(
        url: String,
        username: String? = null,
        password: String? = null,
    ): OpdsFeed {
        val response = httpGet.get(
            OpdsHttpRequest(
                url = url,
                authorizationHeader = basicAuthorizationHeader(username, password),
            ),
        )
        if (response.code !in 200..299) {
            throw OpdsCatalogException.Http(response.code)
        }
        return try {
            parser.parse(response.body)
        } catch (error: Exception) {
            throw OpdsCatalogException.Parse(error.message ?: "OPDS_PARSE")
        }
    }

    /**
     * Fetches the OpenSearch description at [url] and returns its OPDS search
     * URL template (`.../series?query={searchTerms}`), or null when the document
     * advertises none. Same auth as [fetchFeed].
     */
    fun fetchOpenSearchTemplate(
        url: String,
        username: String? = null,
        password: String? = null,
    ): String? {
        val response = httpGet.get(
            OpdsHttpRequest(
                url = url,
                authorizationHeader = basicAuthorizationHeader(username, password),
            ),
        )
        if (response.code !in 200..299) {
            throw OpdsCatalogException.Http(response.code)
        }
        return OpenSearchDescription.parseTemplate(response.body)
    }

    companion object {
        private val AUTH_KEY_IN_PATH = Regex(
            """(?<=/api/opds/)[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""",
        )
        private val LONG_TOKEN = Regex("""(?<=/api/opds/)[^/.?#]{8,}""")

        /**
         * #C52 round 3: `_api_opds_` is the on-disk form of `/api/opds/` —
         * [com.easemic.app.feature.download.DownloadLocatorStore.fileFor]
         * and `DownloadLibraryAssociation` replace `/` with `_` when an
         * acquisition URL becomes a filesystem path segment, so an
         * IOException/ENOENT message ("File name too long", etc.) carries the
         * auth key in this mangled shape rather than the slash-delimited one.
         */
        private val MANGLED_TOKEN = Regex("""(?<=_api_opds_)[^_/.?#]{8,}""")

        /**
         * #C74: a key in the QUERY string (`?apikey=` / `access_token=` / `token=`, the keys
         * [com.easemic.app.feature.providers.OpdsLibraryIdentity] strips from identity).
         */
        private val QUERY_TOKEN = Regex("""(?i)([?&](?:apikey|api_key|access_token|token)=)[^&#\s"'<>]+""")

        fun redactUrl(url: String): String =
            AUTH_KEY_IN_PATH.replace(url, "<redacted>")
                .let { LONG_TOKEN.replace(it) { match ->
                    if (match.value == "<redacted>") match.value else "<redacted>"
                } }
                .let { MANGLED_TOKEN.replace(it) { match ->
                    if (match.value == "<redacted>") match.value else "<redacted>"
                } }
                .let { QUERY_TOKEN.replace(it) { match -> match.groupValues[1] + "<redacted>" } }

        fun basicAuthorizationHeader(username: String?, password: String?): String? {
            if (username.isNullOrBlank() || password == null) return null
            val token = Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
            return "Basic $token"
        }

        /**
         * #C12 audit H3: does an OPDS provider's [catalogUrl] own the origin of
         * [requestUrl], so its Basic credentials may ride along? A path-prefix
         * match is the plain case; the fallback compares the URL AUTHORITY —
         * scheme, host AND port — so a provider bound to `http://host:5001`
         * never leaks its credentials to a different server the user also runs
         * at `http://host:6001` (or an `https` look-alike). Host-equality alone
         * (the pre-audit rule) did leak across ports on the same host.
         */
        fun opdsAuthorityMatches(requestUrl: String, catalogUrl: String): Boolean {
            if (requestUrl.startsWith(catalogUrl.trimEnd('/'), ignoreCase = true)) return true
            return runCatching {
                val request = java.net.URI(requestUrl)
                val catalog = java.net.URI(catalogUrl)
                val requestHost = request.host?.lowercase()
                val catalogHost = catalog.host?.lowercase()
                requestHost != null &&
                    requestHost == catalogHost &&
                    request.scheme?.lowercase() == catalog.scheme?.lowercase() &&
                    effectivePort(request) == effectivePort(catalog)
            }.getOrDefault(false)
        }

        private fun effectivePort(uri: java.net.URI): Int =
            if (uri.port != -1) {
                uri.port
            } else {
                when (uri.scheme?.lowercase()) {
                    "https" -> 443
                    "http" -> 80
                    else -> -1
                }
            }

        /**
         * #C12 audit B2: read at most [maxBytes] from [input] (the whole stream
         * when null), always closing it. A liveness probe passes a small cap so
         * a mislabeled acquisition URL that streams a huge archive cannot pin
         * the pump on a multi-hundred-MB read.
         */
        fun readCapped(input: java.io.InputStream, maxBytes: Int?): ByteArray = input.use { stream ->
            if (maxBytes == null) return stream.readBytes()
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var total = 0
            while (total < maxBytes) {
                val read = stream.read(chunk, 0, minOf(chunk.size, maxBytes - total))
                if (read == -1) break
                buffer.write(chunk, 0, read)
                total += read
            }
            buffer.toByteArray()
        }

        /**
         * The shared OPDS client (#E19): seeded by [SourceHttp] so the
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
         * Catalog GET on the shared [sourceClient] unless a client is injected —
         * the #E19 Dns seam. Redirects are followed, as before; OkHttp's
         * transparent gzip stays on, as HttpURLConnection's was. The [OpdsHttpRequest.maxBytes]
         * cap bounds the read either way.
         */
        fun defaultGet(
            request: OpdsHttpRequest,
            client: OkHttpClient = sourceClient,
        ): OpdsHttpResponse {
            val builder = Request.Builder().url(request.url)
            request.authorizationHeader?.let { builder.header("Authorization", it) }
            return client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.byteStream()?.let { readCapped(it, request.maxBytes) } ?: ByteArray(0)
                OpdsHttpResponse(code = response.code, body = body)
            }
        }

        /**
         * Blocking wrapper for callers that already own no coroutine.
         * Production acquisition goes through [transferToFile] so cancellation
         * closes this connection.
         */
        fun defaultGetToFile(
            request: OpdsHttpRequest,
            destination: Path,
            firstByteMs: Long = DEFAULT_FIRST_BYTE_MS,
            floorWindowMs: Long = StallWatchdog.DEFAULT_WINDOW_MS,
            floorBytes: Long = StallWatchdog.DEFAULT_MIN_BYTES_PER_WINDOW,
            client: OkHttpClient = sourceClient,
        ): OpdsFileTransferResponse = runBlocking {
            transferToFile(request, destination, firstByteMs, floorWindowMs, floorBytes, client)
        }

        /**
         * Acquisition-body GET that writes directly to [destination]. The
         * body is governed by the same first-byte, rolling-floor and
         * Content-Length-derived budget as source page transfers. Cancelling
         * the caller cancels the call once, which closes the socket. Transfers
         * send `Accept-Encoding: identity` — OkHttp's transparent gzip would
         * strip Content-Length and break the expected-size check.
         */
        @OptIn(InternalCoroutinesApi::class)
        suspend fun transferToFile(
            request: OpdsHttpRequest,
            destination: Path,
            firstByteMs: Long = DEFAULT_FIRST_BYTE_MS,
            floorWindowMs: Long = StallWatchdog.DEFAULT_WINDOW_MS,
            floorBytes: Long = StallWatchdog.DEFAULT_MIN_BYTES_PER_WINDOW,
            client: OkHttpClient = sourceClient,
        ): OpdsFileTransferResponse = withContext(Dispatchers.IO) {
            val attemptClient = client.newBuilder()
                .readTimeout(stallReadTimeoutMs(firstByteMs, floorWindowMs), TimeUnit.MILLISECONDS)
                .build()
            val call = attemptClient.newCall(buildAcquisitionRequest(request))
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
            val deadline = FirstByteDeadline("OPDS_STALL", firstByteMs, sawBodyByte) { closeOnce() }
            try {
                call.execute().use { response ->
                    val code = response.code
                    val headers = flattenHeaders(response.headers)
                    val body = response.body
                        ?: return@use OpdsFileTransferResponse(code, 0L, headers = headers)
                    if (code !in 200..299) {
                        return@use OpdsFileTransferResponse(
                            code = code,
                            bytesWritten = 0L,
                            headers = headers,
                            bodySample = readSample(body.byteStream()),
                        )
                    }
                    val expected = headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0L }
                    Files.createDirectories(destination.parent)
                    val budgetMs = EhentaiClient.transferBudgetMs(expected, floorWindowMs, floorBytes)
                    val watchdog = StallWatchdog(floorWindowMs, floorBytes, firstByteMs)
                    val startedAt = System.nanoTime()
                    var written = 0L
                    BufferedInputStream(body.byteStream()).use { input ->
                        Files.newOutputStream(
                            destination,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE,
                        ).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = try {
                                    input.read(buffer)
                                } catch (error: IOException) {
                                    deadline.stallIfDue(error)?.let { throw it }
                                    if (error is SocketTimeoutException) {
                                        watchdog.onProgress(System.currentTimeMillis(), 0)?.let {
                                            throw IOException("OPDS_STALL: $it", error)
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
                                    throw IOException("OPDS_STALL: $it")
                                }
                                if ((System.nanoTime() - startedAt) / 1_000_000L > budgetMs) {
                                    throw IOException("OPDS acquisition exceeded ${budgetMs}ms")
                                }
                            }
                        }
                    }
                    if (expected != null && written < expected) {
                        throw IOException("OPDS acquisition disconnected at $written of $expected bytes")
                    }
                    OpdsFileTransferResponse(code, written, expected, headers)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                if (cancelled.get() || !currentCoroutineContext().isActive) {
                    throw CancellationException("OPDS acquisition cancelled", error)
                }
                deadline.stallIfDue(error)?.let { throw it }
                throw error
            } finally {
                deadline.close()
                cancellation?.dispose()
                closeOnce()
            }
        }

        /** The acquisition's verbatim request shape: `Authorization` plus `Accept-Encoding: identity`. */
        private fun buildAcquisitionRequest(request: OpdsHttpRequest): Request {
            val builder = Request.Builder().url(request.url)
                .header("Accept-Encoding", "identity")
            request.authorizationHeader?.let { builder.header("Authorization", it) }
            return builder.build()
        }

        private fun flattenHeaders(headers: Headers): Map<String, String> =
            headers.names().associate { name -> name.lowercase() to headers.values(name).joinToString(", ") }

        private fun readSample(input: java.io.InputStream): ByteArray = input.use { stream ->
            val sample = ByteArray(8 * 1024)
            val read = stream.read(sample)
            if (read > 0) sample.copyOf(read) else ByteArray(0)
        }

        private const val DEFAULT_FIRST_BYTE_MS = 30_000L
    }
}
