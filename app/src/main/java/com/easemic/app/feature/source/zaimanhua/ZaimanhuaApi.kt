package com.easemic.app.feature.source.zaimanhua

import com.easemic.app.feature.download.FetchTransportException
import com.easemic.app.feature.providers.ZaimanhuaAccountStore
import com.easemic.app.importing.CatalogRequestGate
import com.easemic.app.sync.ImageContentType
import com.easemic.app.sync.DiagLog
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

internal const val ZAIMANHUA_USER_AGENT = "PrivateComicLibrary/1.0 (Zaimanhua; polite)"

internal class ZaimanhuaBytes(
    val code: Int,
    val body: ByteArray,
    val headers: Map<String, String>,
)

private val ZAIMANHUA_EMBEDDED_URL = Regex("""[A-Za-z][A-Za-z0-9+.-]*://[^\s"'\\<>]+""")
private val ZAIMANHUA_JWT = Regex("""(?<![A-Za-z0-9_-])[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_.-]+(?![A-Za-z0-9_-])""")

internal fun zaimanhuaBodySample(bytes: ByteArray, secrets: Collection<String> = emptyList()): ByteArray {
    if (bytes.isEmpty()) return bytes
    val limited = bytes.copyOf(minOf(bytes.size, FetchTransportException.BODY_SAMPLE_LIMIT))
    var text = String(limited, Charsets.UTF_8)
    secrets.filter { it.isNotBlank() }.forEach { secret -> text = text.replace(secret, "[redacted]") }
    text = ZAIMANHUA_EMBEDDED_URL.replace(text, "")
    text = ZAIMANHUA_JWT.replace(text, "[redacted]")
    return text.toByteArray(Charsets.UTF_8)
}

internal fun zaimanhuaHeaderMap(headers: Headers): Map<String, String> =
    headers.names().associate { name -> name.lowercase() to headers.values(name).joinToString(", ") }

internal fun zaimanhuaFailure(call: String, detail: String): Nothing {
    DiagLog.w("Zaimanhua", "$call $detail")
    throw IOException("zaimanhua $call: $detail")
}

/**
 * Anonymous 再漫画 HTTP. Every URL is built with [HttpUrl.Builder]. Failures throw
 * [IOException] whose message is the call name and the status, never a URL.
 */
internal class ZaimanhuaApi(
    private val client: OkHttpClient,
    private val apiHost: HttpUrl,
    private val pcHost: HttpUrl,
    private val gate: CatalogRequestGate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val accountStore: ZaimanhuaAccountStore? = null,
) {
    private val authHosts = setOf(apiHost.host, pcHost.host)
    suspend fun detail(id: Long): JSONObject = get(
        call = "detail",
        host = apiHost,
        path = "app/v1/comic/detail/$id",
        query = listOf("_v" to "2.2.5"),
        platformPc = true,
    )

    suspend fun pcDetail(id: Long): JSONObject = get(
        call = "pc",
        host = pcHost,
        path = "api/v1/comic2/comic/detail",
        query = listOf("id" to id.toString()),
        platformPc = true,
    )

    suspend fun browse(request: ZBrowseRequest): JSONObject = get(
        call = request.route.name.lowercase(),
        host = apiHost,
        path = when (request.route) {
            ZBrowseRoute.LATEST -> "app/v1/comic/update/list/0/${request.page}"
            ZBrowseRoute.FILTER -> "app/v1/comic/filter/list"
            ZBrowseRoute.SEARCH -> "app/v1/search/index"
            ZBrowseRoute.RANK -> "app/v1/comic/rank/list"
        },
        query = request.query,
        platformPc = false,
    )

    suspend fun classify(): JSONObject = get(
        call = "classify", host = apiHost, path = "app/v1/comic/filter/classify",
        query = emptyList(), platformPc = false,
    )

    suspend fun latest(page: Int): JSONObject = get(
        call = "latest",
        host = apiHost,
        path = "app/v1/comic/update/list/0/$page",
        query = emptyList(),
        platformPc = false,
    )

    private fun pageListRequest(seriesId: Long, chapterId: Long): Request = Request.Builder()
        .url(
            apiHost.newBuilder()
                .addPathSegments("app/v1/comic/chapter/$seriesId/$chapterId")
                .addQueryParameter("_v", "2.2.5")
                .build(),
        )
        .header("User-Agent", ZAIMANHUA_USER_AGENT)
        .header("Accept", "application/json")
        .header("Platform", "h5")
        .build()

    internal fun requestWithAuth(request: Request): Request {
        val token = accountStore
            ?.read()
            ?.token
            ?.takeIf { request.url.host in authHosts && it.isNotBlank() }
            ?: return request
        return request.newBuilder().header("Authorization", "Bearer $token").build()
    }

    /** Clears a rejected signed-in session; callers perform the one anonymous replay. */
    internal fun authRejected(request: Request, statusCode: Int, body: ByteArray): Boolean {
        if (request.header("Authorization") == null) return false
        val errno99 = runCatching {
            JSONObject(body.toString(Charsets.UTF_8)).optInt("errno") == 99
        }.getOrDefault(false)
        if (statusCode != 401 && !errno99) return false
        accountStore?.clear()
        return true
    }

    internal fun authSecret(request: Request): String? =
        request.header("Authorization")?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }

    internal fun acquireGate() {
        gate.acquire()
    }

    private suspend fun acquireGateInterruptibly() {
        runInterruptible { gate.acquire() }
    }

    @OptIn(InternalCoroutinesApi::class)
    suspend fun pageListForWorker(seriesId: Long, chapterId: Long): String = withContext(ioDispatcher) {
        acquireGateInterruptibly()
        val baseRequest = pageListRequest(seriesId, chapterId)
        var replayed = false
        while (true) {
            val request = requestWithAuth(baseRequest)
            val httpCall = client.newCall(request)
            val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { if (it != null) httpCall.cancel() }
            try {
                val response = read(request, "page", httpCall)
                ensureActive()
                if (!replayed && authRejected(request, response.code, response.body)) {
                    replayed = true
                    acquireGateInterruptibly()
                    continue
                }
                return@withContext pageListText(response, authSecret(request))
            } catch (error: IOException) {
                ensureActive()
                throw error
            } finally {
                cancellation?.dispose()
            }
        }
        error("unreachable")
    }

    fun pageList(seriesId: Long, chapterId: Long): String {
        gate.acquire()
        val baseRequest = pageListRequest(seriesId, chapterId)
        var replayed = false
        while (true) {
            val request = requestWithAuth(baseRequest)
            val response = try {
                read(request, "page")
            } catch (error: FetchTransportException) {
                DiagLog.w("Zaimanhua", "page transport")
                throw error
            }
            if (!replayed && authRejected(request, response.code, response.body)) {
                replayed = true
                gate.acquire()
                continue
            }
            return pageListText(response, authSecret(request))
        }
    }

    private fun pageListText(response: ZaimanhuaBytes, authSecret: String? = null): String {
        if (response.code != 200) {
            DiagLog.w("Zaimanhua", "page HTTP ${response.code}")
            throw FetchTransportException(
                statusCode = response.code,
                resolvedUrl = null,
                bodySample = zaimanhuaBodySample(response.body, listOfNotNull(authSecret)),
                headers = if (authSecret == null) response.headers else emptyMap(),
                message = "zaimanhua page: HTTP ${response.code}",
            )
        }
        return response.body.toString(Charsets.UTF_8)
    }

    fun image(url: String): ZaimanhuaBytes {
        val httpUrl = try {
            url.toHttpUrl()
        } catch (_: IllegalArgumentException) {
            throw FetchTransportException(
                statusCode = 0,
                resolvedUrl = null,
                bodySample = ByteArray(0),
                message = "zaimanhua image: invalid",
            )
        }
        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", ZAIMANHUA_USER_AGENT)
            .build()
        return read(request, "image")
    }

    /** Streams a source-owned series cover into the managed staging file. */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun imageToFile(url: String, destination: Path, maxBytes: Long): Long = withContext(ioDispatcher) {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val httpUrl = try {
            url.toHttpUrl()
        } catch (_: IllegalArgumentException) {
            throw FetchTransportException(0, null, ByteArray(0), emptyMap(), "zaimanhua image: invalid")
        }
        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", ZAIMANHUA_USER_AGENT)
            .build()
        val httpCall = client.newCall(request)
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { if (it != null) httpCall.cancel() }
        var success = false
        try {
            val response = try {
                httpCall.execute()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                throw FetchTransportException(0, null, ByteArray(0), emptyMap(), "zaimanhua image: transport")
            }
            response.use { open ->
                val headers = zaimanhuaHeaderMap(open.headers)
                if (open.code != 200) throw FetchTransportException(
                    open.code, null, ByteArray(0), headers, "zaimanhua image: HTTP ${open.code}",
                )
                val body = open.body ?: throw FetchTransportException(
                    open.code, null, ByteArray(0), headers, "zaimanhua image: empty",
                )
                if (body.contentLength() > maxBytes) throw FetchTransportException(
                    open.code, null, ByteArray(0), headers, "zaimanhua image: body too large",
                )
                val parent = destination.toAbsolutePath().parent
                if (parent != null) Files.createDirectories(parent)
                val head = ByteArray(64)
                var headCount = 0
                var received = 0L
                body.byteStream().use { input ->
                    try {
                        Files.newOutputStream(destination).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received = Math.addExact(received, count.toLong())
                                if (received > maxBytes) throw FetchTransportException(
                                    open.code, null, ByteArray(0), headers, "zaimanhua image: body too large",
                                )
                                if (headCount < head.size) {
                                    val copied = minOf(count, head.size - headCount)
                                    buffer.copyInto(head, headCount, 0, copied)
                                    headCount += copied
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    } catch (error: IOException) {
                        currentCoroutineContext().ensureActive()
                        throw FetchTransportException(0, null, ByteArray(0), headers, "zaimanhua image: transport")
                    }
                }
                val type = ImageContentType.sniff(head.copyOf(headCount))
                val declared = open.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                if (received <= 0L || type == ImageContentType.UNKNOWN ||
                    (declared != null && declared != type.mime)
                ) {
                    throw FetchTransportException(open.code, null, ByteArray(0), headers, "zaimanhua image: invalid body")
                }
                success = true
                received
            }
        } finally {
            cancellation?.dispose()
            if (!success) runCatching { Files.deleteIfExists(destination) }
        }
    }

    fun chapterZipUrl(seriesId: Long, chapterId: Long): HttpUrl =
        apiHost.newBuilder().addPathSegments("app/v1/comic/chapter/zip/$seriesId/$chapterId").build()

    internal val httpClient: OkHttpClient
        get() = client

    private fun read(
        request: Request,
        call: String,
        httpCall: okhttp3.Call = client.newCall(request),
    ): ZaimanhuaBytes {
        val response = try {
            httpCall.execute()
        } catch (_: IOException) {
            throw FetchTransportException(
                statusCode = 0,
                resolvedUrl = null,
                bodySample = ByteArray(0),
                message = "zaimanhua $call: transport",
            )
        }
        response.use { open ->
            val body = try {
                open.body?.bytes() ?: ByteArray(0)
            } catch (_: IOException) {
                throw FetchTransportException(
                    statusCode = 0,
                    resolvedUrl = null,
                    bodySample = ByteArray(0),
                    message = "zaimanhua $call: transport",
                )
            }
            return ZaimanhuaBytes(open.code, body, zaimanhuaHeaderMap(open.headers))
        }
    }

    suspend fun search(keyword: String, page: Int): JSONObject = get(
        call = "search",
        host = apiHost,
        path = "app/v1/search/index",
        query = listOf(
            "source" to "0",
            "size" to "20",
            "keyword" to keyword,
            "page" to page.toString(),
        ),
        platformPc = false,
    )

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun get(
        call: String,
        host: HttpUrl,
        path: String,
        query: List<Pair<String, String>>,
        platformPc: Boolean,
    ): JSONObject = withContext(ioDispatcher) {
        acquireGateInterruptibly()
        val url = host.newBuilder().apply {
            addPathSegments(path)
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val baseRequest = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", ZAIMANHUA_USER_AGENT)
            .header("Accept", "application/json")
            .apply { if (platformPc) header("Platform", "pc") }
            .build()
        var replayed = false
        while (true) {
            val request = requestWithAuth(baseRequest)
            val httpCall = client.newCall(request)
            // execute()/body.string() block on IO. Cancellation must close the call immediately,
            // before withContext waits for that blocked read to return.
            val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { if (it != null) httpCall.cancel() }
            try {
                val response = try {
                    httpCall.execute()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    ensureActive()
                    zaimanhuaFailure(call, "transport")
                }
                val result: JSONObject? = response.use { open ->
                    val code = open.code
                    val text = try {
                        open.body?.string()
                    } catch (_: IOException) {
                        ensureActive()
                        null
                    }
                    ensureActive()
                    val body = text?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                    if (!replayed && authRejected(request, code, body)) {
                        replayed = true
                        null
                    } else {
                        if (code != 200 || text == null) zaimanhuaFailure(call, "HTTP $code")
                        try {
                            JSONObject(text)
                        } catch (_: JSONException) {
                            zaimanhuaFailure(call, "HTTP $code")
                        }
                    }
                }
                if (result != null) return@withContext result
                acquireGateInterruptibly()
            } finally {
                cancellation?.dispose()
            }
        }
        error("unreachable")
    }

}
