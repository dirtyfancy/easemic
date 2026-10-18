package com.easemic.app.feature.source.jm

import com.easemic.app.feature.browse.JmFilters
import com.easemic.app.feature.download.FetchFailureClass
import com.easemic.app.feature.download.FetchRetryContext
import com.easemic.app.feature.download.FetchTransportException
import com.easemic.app.sync.ImageContentType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

// Public behavior reference recorded in docs/research/2026-10-01-jm-source-feasibility.md.
internal data class JmChapterPlan(val chapter: JmChapter, val scrambleId: Long, val generation: Long)

// The exact string the 2026-10-06 Task 0 probes sent (evidence: docs/superpowers/evidence/2026-10-06-e2-slice7-jm-protocol.md).
internal const val JM_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36"

/** Anonymous transport. Each attempt captures seconds once, and holds its gate through body close. */
internal class JmApi(
    client: OkHttpClient,
    internal val domains: JmDomains,
    private val gate: JmRequestGate,
    private val seconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val coverLimiter: JmCoverLimiter = JmCoverLimiter(domains),
) {
    private val http = client.newBuilder().apply {
        cookieJar(CookieJar.NO_COOKIES)
        authenticator(Authenticator.NONE)
        proxyAuthenticator(Authenticator.NONE)
        // JM owns its explicit two-origin budget; transparent HTTP/connection retries would bypass it.
        retryOnConnectionFailure(false)
        followRedirects(false)
        followSslRedirects(false)
        eventListenerFactory { call ->
            object : okhttp3.EventListener() {
                override fun connectFailed(call: Call, inetSocketAddress: java.net.InetSocketAddress,
                    proxy: java.net.Proxy, protocol: okhttp3.Protocol?, ioe: IOException) {
                    call.request().tag(ConnectionState::class.java)?.failed = true
                }
            }
        }
        // Preserve DNS/socket/TLS configuration, but do not inherit other sources' credentials/hooks.
        interceptors().clear()
        networkInterceptors().clear()
        addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // OkHttp retries 503/Retry-After:0 even with connection recovery disabled.
            // Surface these statuses before its follow-up layer can issue another wire call.
            if (response.code in listOf(408, 421, 503)) {
                val headers = response.headers.names().associate { it.lowercase() to response.headers.values(it).joinToString(", ") }
                val error = failure(response.code, headers)
                response.close()
                throw WireFailure(error)
            }
            response
        }
        // Album covers share the four-permit budget used by Coil's cover path;
        // API and chapter requests remain governed by [gate] below.
        addNetworkInterceptor(coverLimiter.interceptor)
    }.build()
    suspend fun setting(): JmPayload = get(JmEndpoint.SETTING, listOf("app_img_shunt" to "1", "express" to ""))
    suspend fun latest(page: Int, filters: JmFilters = JmFilters()): JmPayload =
        get(JmEndpoint.CATALOGUE, JmQueryMapper.parameters(filters, "", page))
    suspend fun search(query: String, page: Int, filters: JmFilters = JmFilters(), scope: com.easemic.app.feature.browse.JmSearchScope? = null): JmPayload =
        get(JmEndpoint.SEARCH, JmQueryMapper.parameters(filters, query, page, scope))
    suspend fun album(id: String): JmPayload = get(JmEndpoint.ALBUM, listOf("id" to JmDecoder.id(id)))
    suspend fun chapter(id: String): JmPayload = get(JmEndpoint.CHAPTER, listOf("id" to JmDecoder.id(id)))
    suspend fun template(id: String): Long = request(JmEndpoint.TEMPLATE, listOf("id" to JmDecoder.id(id), "mode" to "vertical", "page" to "0", "app_img_shunt" to "1", "express" to "off")) { text, _ -> JmDecoder.template(text) }
    private suspend fun get(endpoint: JmEndpoint, query: List<Pair<String, String>>): JmPayload =
        request(endpoint, query) { text, timestamp -> JmDecoder.envelope(text, timestamp, endpoint) }

    private suspend fun <T> request(endpoint: JmEndpoint, query: List<Pair<String, String>>, decode: (String, Long) -> T): T =
        domains.attempt(JmRole.API) { origin ->
            gate.wire {
                val timestamp = seconds()
                val url = origin.newBuilder().addPathSegments(endpoint.path).apply {
                    query.forEach { (name, value) -> addQueryParameter(name, value) }
                    if (endpoint == JmEndpoint.TEMPLATE) addQueryParameter("v", timestamp.toString())
                }.build()
                if (!domains.owns(JmRole.API, url)) JmFailure.invalid()
                val request = Request.Builder().url(url).get().tag(ConnectionState::class.java, ConnectionState())
                    .header("User-Agent", JM_USER_AGENT).header("Accept", "*/*")
                    .header("token", JmCrypto.token(timestamp, endpoint == JmEndpoint.TEMPLATE))
                    .header("tokenparam", "$timestamp,${JmCrypto.VERSION}").build()
                read(http.newCall(request)) { response ->
                    val headers = response.headers.names().filterNot { it.equals(JmFailure.PROVENANCE_HEADER, true) }
                        .associate { it.lowercase() to response.headers.values(it).joinToString(", ") }
                    val code = response.code
                    if (code != 200 || JmFailure.classify(code, ByteArray(0), headers, FetchRetryContext()) is FetchFailureClass.ABUSE_STOP) {
                        // Keep no raw body in diagnostics. Status and safe protocol headers suffice here.
                        throw failure(code, headers)
                    }
                    val body = response.body ?: throw failure(code, headers)
                    if (body.contentLength() > BODY_LIMIT) throw failure(code, headers, "JM response exceeds size limit")
                    val output = ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size().toLong() + count > BODY_LIMIT) throw failure(code, headers, "JM response exceeds size limit")
                            output.write(buffer, 0, count)
                        }
                    }
                    try {
                        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
                        decode(text.removePrefix("\uFEFF"), timestamp)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: FetchTransportException) {
                        if (JmFailure.isErrorEnvelope(error)) throw error
                        throw failure(code, headers)
                    } catch (_: Exception) { throw failure(code, headers) }
                }
            }
        }

    /** Streams image bytes with the same wire gate; a permit lives through body close. */
    suspend fun imageToFile(photoId: String, filename: String, destination: java.nio.file.Path): Long {
        imageOrigin()
        val context = kotlinx.coroutines.currentCoroutineContext()
        return domains.attempt(JmRole.IMAGE, onConnectionFailure = { failed ->
            domains.refreshImage(failed, ::settingImageOrigin)
        }) { origin ->
            gate.wire {
                val url = origin.newBuilder().addPathSegments("media/photos")
                    .addPathSegment(photoId).addPathSegment(filename).build()
                // Another lane may have refreshed the pool while this request waited for its permit.
                // Treat the obsolete selection as a local connection miss; the bounded attempt adopts the new pool.
                if (!domains.owns(JmRole.IMAGE,url)) throw failure(0, emptyMap())
                val request = Request.Builder().url(url).get().tag(ConnectionState::class.java, ConnectionState()).header("User-Agent",JM_USER_AGENT)
                    .header("Accept","image/webp,image/*;q=0.9,*/*;q=0.8").build()
                read(http.newCall(request)) { response ->
                    val headers = response.headers.names().filterNot { it.equals(JmFailure.PROVENANCE_HEADER,true) }
                        .associate { it.lowercase() to response.headers.values(it).joinToString(", ") }
                    if (response.code != 200 || JmFailure.classify(response.code,ByteArray(0),headers,FetchRetryContext()) is FetchFailureClass.ABUSE_STOP) {
                        throw failure(response.code,headers)
                    }
                    val body = response.body ?: throw failure(response.code,headers)
                    val expected = body.contentLength()
                    var received = 0L
                    body.byteStream().use { input ->
                        java.nio.file.Files.newOutputStream(destination).use { output ->
                            val buffer = ByteArray(64*1024)
                            while (true) {
                                context.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received = Math.addExact(received,count.toLong())
                                output.write(buffer,0,count)
                            }
                        }
                    }
                    if (received == 0L || (expected >= 0 && received != expected)) throw failure(200,headers,"JM page length mismatch")
                    received
                }
            }
        }
    }

    /** Streams a bounded album cover without consuming the API request gate. */
    suspend fun coverToFile(albumId: String, destination: Path, maxBytes: Long): Long {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val id = JmDecoder.id(albumId)
        imageOrigin()
        var success = false
        try {
            return domains.attempt(JmRole.IMAGE, onConnectionFailure = { failed ->
                domains.refreshImage(failed, ::settingImageOrigin)
            }) { origin ->
                val url = origin.newBuilder().addPathSegments("media/albums")
                    .addPathSegment("${id}_3x4.jpg").build()
                if (!domains.owns(JmRole.IMAGE, url)) throw failure(0, emptyMap())
                val request = Request.Builder().url(url).get()
                    .tag(ConnectionState::class.java, ConnectionState())
                    .header("User-Agent", JM_USER_AGENT)
                    .header("Accept", "image/webp,image/*;q=0.9,*/*;q=0.8")
                    .build()
                read(http.newCall(request)) { response ->
                    val headers = response.headers.names()
                        .filterNot { it.equals(JmFailure.PROVENANCE_HEADER, true) }
                        .associate { it.lowercase() to response.headers.values(it).joinToString(", ") }
                    if (response.code != 200 || JmFailure.classify(
                            response.code, ByteArray(0), headers, FetchRetryContext(),
                        ) is FetchFailureClass.ABUSE_STOP
                    ) {
                        throw failure(response.code, headers)
                    }
                    val body = response.body ?: throw failure(response.code, headers)
                    if (body.contentLength() > maxBytes) throw failure(response.code, headers, "JM cover exceeds size limit")
                    val parent = destination.toAbsolutePath().parent
                    if (parent != null) Files.createDirectories(parent)
                    val head = ByteArray(64)
                    var headCount = 0
                    var received = 0L
                    body.byteStream().use { input ->
                        Files.newOutputStream(destination).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                received = Math.addExact(received, count.toLong())
                                if (received > maxBytes) throw failure(response.code, headers, "JM cover exceeds size limit")
                                if (headCount < head.size) {
                                    val copied = minOf(count, head.size - headCount)
                                    buffer.copyInto(head, headCount, 0, copied)
                                    headCount += copied
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    val type = ImageContentType.sniff(head.copyOf(headCount))
                    val declared = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                    if (received <= 0L || type == ImageContentType.UNKNOWN ||
                        (declared != null && declared != type.mime)
                    ) throw failure(response.code, headers, "JM cover has invalid image bytes")
                    success = true
                    received
                }
            }
        } finally {
            if (!success) runCatching { Files.deleteIfExists(destination) }
        }
    }
    private suspend fun settingImageOrigin(): String {
        val payload = (setting() as? JmPayload.Present)?.json ?: JmFailure.invalid()
        return payload.opt("img_host") as? String ?: JmFailure.invalid()
    }

    suspend fun imageOrigin() = domains.imageOrigin(::settingImageOrigin)

    /** Async body read on OkHttp's dispatcher; cancelling closes headers or a blocked body immediately. */
    internal suspend fun <T> read(call: Call, decode: (Response) -> T): T = withContext(Dispatchers.IO) {
        val closed = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        try {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(
                                (e as? WireFailure)?.failure ?: failure(
                                    if (e is UnknownHostException || e is ConnectException || e is NoRouteToHostException || e is SSLException ||
                                        (e is SocketTimeoutException && call.request().tag(ConnectionState::class.java)?.failed == true)) 0 else 200,
                                    emptyMap(),
                                ),
                            ))
                        } finally { closed.complete(Unit) }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = runCatching {
                                response.use {
                                    if (!continuation.isActive) return
                                    decode(it)
                                }
                            }.fold(
                                onSuccess = { Result.success(it) },
                                onFailure = { Result.failure<T>(if (it is FetchTransportException || it is CancellationException) it else failure(response.code, emptyMap())) },
                            )
                            if (continuation.isActive) continuation.resumeWith(result)
                        } finally { closed.complete(Unit) }
                    }
                })
            }
        } finally {
            // Cancellation resumes the suspension before OkHttp unwinds a blocked body read.
            call.cancel()
            withContext(NonCancellable) { withTimeoutOrNull(5000) { closed.await() } }
        }
    }
    private class ConnectionState { @Volatile var failed = false }
    private class WireFailure(val failure: FetchTransportException) : IOException("JM request unavailable")

    private fun failure(status: Int, headers: Map<String, String>, message: String = "JM request unavailable") = FetchTransportException(
        status, null, ByteArray(0), headers.filterKeys { it in SAFE_HEADERS }, message,
    )
    private val planLock = Mutex()
    private val cacheLock = Any()
    private val plans = LinkedHashMap<String, JmChapterPlan>(32, 0.75f, true)
    private var invalidation = 0L

    suspend fun chapterPlan(series: String, id: String): JmChapterPlan = planLock.withLock {
        JmDecoder.id(series); JmDecoder.id(id)
        val key = "$series\u0000$id"
        val generation = domains.generation
        synchronized(cacheLock) { plans[key]?.takeIf { it.generation == generation } }?.let { return@withLock it }
        val revision = synchronized(cacheLock) { invalidation }
        // Distinct calls each acquire the shared wire gate; the plan holds no wire permit itself.
        val result = coroutineScope {
            val metadata = async {
                when (val payload = chapter(id)) {
                    is JmPayload.Present -> JmDecoder.chapter(payload.json, series, id)
                    JmPayload.Missing -> throw JmFailure.missing()
                }
            }
            val scramble = async { template(id) }
            JmChapterPlan(metadata.await(), scramble.await(), generation)
        }
        if (generation != domains.generation) JmFailure.invalid()
        synchronized(cacheLock) {
            if (revision == invalidation) {
                plans[key] = result
                while (plans.size > 32) plans.remove(plans.keys.first())
            }
        }
        result
    }
    fun invalidateChapter(series: String, id: String) {
        synchronized(cacheLock) { invalidation++; plans.remove("$series\u0000$id") }
    }

    companion object {
        const val BODY_LIMIT = 4 * 1024 * 1024
        private val SAFE_HEADERS = setOf("cf-mitigated", "retry-after")
    }
}
