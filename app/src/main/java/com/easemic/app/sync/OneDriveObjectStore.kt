package com.easemic.app.sync

import com.easemic.app.feature.bridge.DriveTokenSource
import com.easemic.app.net.SourceHttp
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * #C50 (spec §4.2): the user's OneDrive app folder as an [ObjectStore].
 *
 * Measured on the owner's account 2026-09-26: every key is addressed relative to the
 * approot item id (`/me/drive/items/{root}:/{key}:`) and a path PUT creates the missing
 * parents, so there is no folder step; `:/content` answers 302 to a pre-authenticated host
 * that serves exact `206` ranges and stays valid for later ranges, so the resolved URL is
 * reused per key; large bodies go through an upload session in fragments that are
 * multiples of 320 KiB. The bearer is sent to graph.microsoft.com only — never to the
 * download host, the upload URL, or a `@odata.nextLink` that points anywhere else.
 */
class OneDriveObjectStore(
    private val rootItemId: String,
    private val tokens: DriveTokenSource,
    private val graphBase: String = GRAPH_BASE,
    /** Only tests move the allowed nextLink origin; production pins graph.microsoft.com. */
    graphOriginOverride: String? = null,
    private val uploadFragmentBytes: Int = DEFAULT_FRAGMENT_BYTES,
    private val simplePutLimitBytes: Int = SIMPLE_PUT_LIMIT_BYTES,
    private val client: OkHttpClient = sharedClient,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : ObjectStore {
    private val graphOrigin: HttpUrl = (graphOriginOverride ?: graphBase).toHttpUrl()
    private val downloadUrls = ConcurrentHashMap<String, Pair<HttpUrl, Long>>()

    init {
        require(uploadFragmentBytes > 0 && uploadFragmentBytes % FRAGMENT_UNIT == 0) { "fragments must be multiples of 320 KiB" }
    }

    override val supportsEfficientRange: Boolean = true

    override fun upload(objectKey: String, bytes: ByteArray, mime: String): Int {
        downloadUrls.remove(objectKey)
        if (bytes.size > simplePutLimitBytes) return sessionUpload(objectKey, bytes.size.toLong()) { off, len -> bytes.copyOfRange(off.toInt(), (off + len).toInt()) }
        return graph { token ->
            Request.Builder().url(itemUrl(objectKey, ":/content")).header("Authorization", "Bearer $token")
                .put(bytes.toRequestBody(mime.toMediaType())).build()
        }.use { response -> okOrThrow(response).code }
    }

    override fun uploadFile(objectKey: String, file: File, mime: String): Int {
        downloadUrls.remove(objectKey)
        val size = file.length()
        if (size <= simplePutLimitBytes) {
            return graph { token ->
                Request.Builder().url(itemUrl(objectKey, ":/content")).header("Authorization", "Bearer $token")
                    .put(file.asRequestBody(mime.toMediaType())).build()
            }.use { response -> okOrThrow(response).code }
        }
        return RandomAccessFile(file, "r").use { raf ->
            sessionUpload(objectKey, size) { off, len ->
                ByteArray(len.toInt()).also { raf.seek(off); raf.readFully(it) }
            }
        }
    }

    override fun download(objectKey: String): ByteArray = content(objectKey, range = null).use { it.body!!.bytes() }

    override fun downloadRange(objectKey: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "offset must be >= 0" }
        require(length > 0) { "length must be > 0" }
        val last = offset + length - 1
        return content(objectKey, offset..last).use { response ->
            if (response.code != 206) throw CloudHttpException(WebDavObjectStore.RANGE_IGNORED)
            val body = response.body!!.bytes()
            val span = CONTENT_RANGE.find(response.header("Content-Range").orEmpty())
            val spanOk = span == null || (span.groupValues[1].toLong() == offset && span.groupValues[2].toLong() == last)
            if (body.size != length || !spanOk) throw IOException("short range body: ${body.size} of $length")
            body
        }
    }

    override fun downloadRangeToFile(objectKey: String, offset: Long, length: Int, objectSize: Long, dest: File) {
        require(offset >= 0) { "offset must be >= 0" }
        require(length > 0) { "length must be > 0" }
        val last = offset + length - 1
        content(objectKey, offset..last).use { response ->
            if (response.code != 206) throw CloudHttpException(WebDavObjectStore.RANGE_IGNORED)
            if (!contentRangeMatchesRequest(response.header("Content-Range"), offset, last)) {
                throw IOException("short range body: 0 of $length")
            }
            val stream = response.body?.byteStream() ?: throw IOException("short range body: 0 of $length")
            stream.use { input -> copyRangeBody(input, dest, 0, length, wholeBody = false) }
        }
    }

    override fun downloadToFile(objectKey: String, dest: File) {
        content(objectKey, range = null).use { response ->
            response.body!!.byteStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
    }

    override fun listKeys(): List<String> = listObjects("").map { it.key }

    override fun listKeys(prefix: String): List<String> = listObjects(prefix).map { it.key }

    override fun listObjects(prefix: String): List<ObjectSummary> {
        val out = ArrayList<ObjectSummary>()
        walk(prefix.substringBeforeLast('/', ""), prefix, out)
        return out
    }

    override fun listUsage(): R2BucketUsage {
        val objects = listObjects("")
        return R2BucketUsage(objectCount = objects.size, totalBytes = objects.sumOf { it.sizeBytes.coerceAtLeast(0L) })
    }

    override fun delete(objectKey: String): Int {
        downloadUrls.remove(objectKey)
        graph { token ->
            Request.Builder().url(itemUrl(objectKey, ":")).header("Authorization", "Bearer $token").delete().build()
        }.use { response ->
            return when {
                response.code in 200..299 -> response.code
                response.code == 404 -> 204
                else -> throw failure(response)
            }
        }
    }

    // ---- content ----

    /** A 2xx/206 response for [objectKey] from the download host; the caller closes it. */
    private fun content(objectKey: String, range: LongRange?): Response {
        repeat(2) { attempt ->
            val url = downloadUrl(objectKey, fresh = attempt > 0)
            val builder = Request.Builder().url(url).get()
            if (range != null) builder.header("Range", "bytes=${range.first}-${range.last}")
            val started = clockMs()
            val response = execute(builder.build())
            if (response.code in 200..299) {
                DiagLog.i("CloudDrive", "onedrive content-headers ms=${clockMs() - started} code=${response.code}")
                return response
            }
            response.close()
            downloadUrls.remove(objectKey)
            // A cached URL can outlive its file (overwrite, delete) or its signature: look it up once more.
            if (attempt == 0 && response.code in setOf(401, 403, 404, 410)) return@repeat
            throw statusFailure(response.code, emptyMap())
        }
        throw CloudHttpException("CLOUD_HTTP_404")
    }

    private fun downloadUrl(objectKey: String, fresh: Boolean): HttpUrl {
        if (!fresh) downloadUrls[objectKey]?.let { (url, at) -> if (clockMs() - at < DOWNLOAD_URL_TTL_MS) return url }
        val started = clockMs()
        graph { token ->
            Request.Builder().url(itemUrl(objectKey, ":/content")).header("Authorization", "Bearer $token").get().build()
        }.use { response ->
            val location = response.header("Location")
            if (response.code in 300..399 && location != null) {
                val url = response.request.url.resolve(location) ?: throw CloudHttpException("CLOUD_HTTP_${response.code}")
                downloadUrls[objectKey] = url to clockMs()
                // Counts only (no key, no URL): how much of a first page is the Graph hop.
                DiagLog.i("CloudDrive", "onedrive content-lookup ms=${clockMs() - started}")
                return url
            }
            throw failure(response)
        }
    }

    // ---- upload session ----

    private fun sessionUpload(objectKey: String, size: Long, slice: (Long, Long) -> ByteArray): Int {
        val uploadUrl = graph { token ->
            val body = JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "replace")).toString()
            Request.Builder().url(itemUrl(objectKey, ":/createUploadSession")).header("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON)).build()
        }.use { response ->
            val url = JSONObject(okOrThrow(response).body!!.string()).optString("uploadUrl")
            // The session URL carries its own authorization; it must at least be https (or the test loopback).
            url.takeIf { it.startsWith("https://") || it.startsWith(graphBase.substringBefore("/v1.0")) }?.toHttpUrl()
                ?: throw CloudHttpException("CLOUD_UPLOAD_SESSION_INVALID")
        }
        var offset = 0L
        var last = 0
        while (offset < size) {
            val len = minOf(uploadFragmentBytes.toLong(), size - offset)
            val request = Request.Builder().url(uploadUrl)
                .header("Content-Range", "bytes $offset-${offset + len - 1}/$size")
                .put(slice(offset, len).toRequestBody(OCTET))
                .build()
            execute(request).use { response ->
                if (response.code !in 200..299) throw failure(response)
                last = response.code
            }
            offset += len
        }
        return last
    }

    // ---- listing ----

    private fun walk(dir: String, prefix: String, out: MutableList<ObjectSummary>) {
        var url: HttpUrl? = childrenUrl(dir)
        var pages = 0
        while (url != null) {
            if (++pages > MAX_LIST_PAGES) throw CloudHttpException("CLOUD_LIST_UNBOUNDED")
            val page = url
            val json = graph { token ->
                Request.Builder().url(page).header("Authorization", "Bearer $token").get().build()
            }.use { response ->
                if (response.code == 404) return
                JSONObject(okOrThrow(response).body!!.string())
            }
            val value = json.optJSONArray("value")
            for (i in 0 until (value?.length() ?: 0)) {
                val item = value!!.getJSONObject(i)
                val name = item.optString("name").takeIf { it.isNotEmpty() } ?: continue
                val key = if (dir.isEmpty()) name else "$dir/$name"
                if (item.has("folder")) {
                    val child = "$key/"
                    if (child.startsWith(prefix) || prefix.startsWith(child)) walk(key, prefix, out)
                } else if (key.startsWith(prefix)) {
                    out += ObjectSummary(key, item.optLong("size", -1L))
                }
            }
            url = json.optString("@odata.nextLink").takeIf { it.isNotEmpty() }?.let { next ->
                val parsed = next.toHttpUrl()
                // The link carries the bearer's authority: never follow it off Graph.
                if (parsed.scheme != graphOrigin.scheme || parsed.host != graphOrigin.host || parsed.port != graphOrigin.port) {
                    throw CloudHttpException("CLOUD_NEXTLINK_OFF_GRAPH")
                }
                parsed
            }
        }
    }

    private fun childrenUrl(dir: String): HttpUrl =
        if (dir.isEmpty()) {
            "$graphBase/me/drive/items/$rootItemId/children?\$select=name,size,folder&\$top=1000".toHttpUrl()
        } else {
            (itemUrl(dir, ":/children").toString() + "?\$select=name,size,folder&\$top=1000").toHttpUrl()
        }

    // ---- plumbing ----

    /** One Graph call with the bearer; a 401 drops that token and retries once with a fresh one. */
    private fun graph(build: (String) -> Request): Response {
        val first = tokens.accessToken()
        val response = execute(build(first))
        if (response.code != 401) return response
        response.close()
        tokens.invalidate(first)
        val retry = execute(build(tokens.accessToken()))
        if (retry.code == 401) {
            retry.close()
            throw CloudHttpException(DriveTokenSource.REAUTH_REQUIRED)
        }
        return retry
    }

    private fun execute(request: Request): Response {
        val response = client.newCall(request).execute()
        if (response.code == 429 || response.code == 503) {
            response.use { throw CloudRateLimitedException.fromHeaders(it.headers.associate { h -> h.first to h.second }) }
        }
        return response
    }

    private fun okOrThrow(response: Response): Response {
        if (response.code in 200..299) return response
        throw failure(response)
    }

    private fun failure(response: Response): Exception = response.use {
        val code = runCatching { JSONObject(it.body!!.string()).optJSONObject("error")?.optString("code") }.getOrNull()
        if (code == "quotaLimitReached") CloudHttpException(WebDavObjectStore.QUOTA_FULL) else statusFailure(it.code, emptyMap())
    }

    private fun statusFailure(code: Int, headers: Map<String, String>): Exception = when (code) {
        429, 503 -> CloudRateLimitedException.fromHeaders(headers)
        507 -> CloudHttpException(WebDavObjectStore.QUOTA_FULL)
        else -> CloudHttpException("CLOUD_HTTP_$code")
    }

    private fun itemUrl(key: String, suffix: String): HttpUrl {
        require(key.isNotBlank()) { "objectKey required" }
        require(!key.split('/').any { it == ".." }) { "bad objectKey" }
        val path = key.trimStart('/').split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }
        return "$graphBase/me/drive/items/$rootItemId:/$path$suffix".toHttpUrl()
    }

    companion object {
        const val GRAPH_BASE: String = "https://graph.microsoft.com/v1.0"
        private const val FRAGMENT_UNIT = 320 * 1024
        /** 20 MiB = 64 × 320 KiB, well under Graph's 60 MiB per-fragment limit (expo-app, same value). */
        const val DEFAULT_FRAGMENT_BYTES: Int = 64 * FRAGMENT_UNIT
        /** Graph's simple-upload ceiling. */
        const val SIMPLE_PUT_LIMIT_BYTES: Int = 4 * 1024 * 1024
        private const val DOWNLOAD_URL_TTL_MS = 50L * 60 * 1000
        private const val MAX_LIST_PAGES = 10_000
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/""")
        private val JSON = "application/json".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()

        /** Seeded from [SourceHttp] (#E19 Task 7); the settings below are carried over verbatim. */
        internal fun seededBuilder(): OkHttpClient.Builder =
            SourceHttp.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.MINUTES)

        private val sharedClient: OkHttpClient by lazy {
            seededBuilder().build()
        }
    }
}
