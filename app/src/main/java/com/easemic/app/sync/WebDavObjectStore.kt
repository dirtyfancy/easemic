package com.easemic.app.sync

import com.easemic.app.ProductIdentity
import com.easemic.app.net.SourceHttp
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * #C50: where a WebDAV library lives. [folder] is the user's optional sub-folder; the
 * library itself is always `<baseUrl>/<folder>/<ProductIdentity.LIBRARY_FOLDER_NAME>/`.
 */
data class WebDavBinding(
    val baseUrl: String,
    val username: String,
    val password: String,
    val folder: String = "",
) {
    override fun toString(): String =
        "WebDavBinding(baseUrl=$baseUrl, username=$username, password=<redacted>, folder=$folder)"
}

/**
 * #C50 (spec §4.1): the user's WebDAV drive as an [ObjectStore]. Flat keys map to paths
 * under the library folder, one segment per `/`.
 *
 * Every behaviour here answers a fact measured on hacdias/webdav, 123pan and a Synology
 * NAS (spec §2.2): parents are created only when a PUT says 409, or 404 on 123pan (which
 * creates a single missing level itself), and are then remembered; MKCOL "already exists"
 * arrives as 405 or 201;
 * Synology answers one spurious 424 right after any 409; listings use `Depth: 1` only
 * (Synology forbids `infinity`); 123pan answers GET with a 302 to its CDN, which can lag
 * a PUT by about a second, and serves exact `206` ranges there. Credentials never follow
 * a redirect to another host.
 *
 * OkHttp, not `HttpURLConnection`: the platform connection refuses `PROPFIND` and
 * `MKCOL` as request methods.
 */
class WebDavObjectStore(
    private val binding: WebDavBinding,
    allowPlainHttpForTests: Boolean = false,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val client: OkHttpClient = sharedClient,
    /** Total ceiling for a non-PUT request; a PUT body never has one (#D12). Tests shrink it. */
    private val requestDeadlineMs: Long = TOTAL_DEADLINE_MS,
) : ObjectStore {
    private val rootUrl: HttpUrl
    private val authorization = Credentials.basic(binding.username, binding.password, Charsets.UTF_8)

    /** Directory keys ("" = the library folder itself) this store created or saw exist. */
    private val knownDirs: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    /** #E110: set once a PUT came back 409/404 for missing parents — see [put]. */
    @Volatile private var putNeedsExplicitParents = false
    private val dirLock = Any()

    init {
        val base = binding.baseUrl.trim()
        require(base.startsWith("https://") || (allowPlainHttpForTests && base.startsWith("http://"))) {
            "WebDAV needs an https:// address"
        }
        val segments = binding.folder.split('/').map { it.trim() }.filter { it.isNotEmpty() } +
            ProductIdentity.LIBRARY_FOLDER_NAME
        rootUrl = (base.trimEnd('/') + "/" + segments.joinToString("/") { encodeSegment(it) } + "/").toHttpUrl()
    }

    override val supportsEfficientRange: Boolean = true

    /** A home NAS behind a tunnel took 43 s for 16 MiB and failed on 32 MiB (Cloudflare's 100 s window, 2026-09-26). */
    override val preferredPackBytes: Long = 8L shl 20

    override fun upload(objectKey: String, bytes: ByteArray, mime: String): Int =
        put(objectKey, bytes.size.toLong()) { bytes.toRequestBody(mime.toMediaType()) }

    override fun uploadFile(objectKey: String, file: File, mime: String): Int =
        put(objectKey, file.length()) { file.asRequestBody(mime.toMediaType()) }

    override fun download(objectKey: String): ByteArray =
        get(objectKey, range = null).use { response ->
            if (response.code !in 200..299) throw statusFailure(response)
            response.body!!.bytes()
        }

    override fun downloadRange(objectKey: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "offset must be >= 0" }
        require(length > 0) { "length must be > 0" }
        val last = offset + length - 1
        return get(objectKey, range = offset..last).use { response ->
            when (response.code) {
                206 -> {
                    val body = response.body!!.bytes()
                    if (body.size != length ||
                        !contentRangeMatchesRequest(response.header("Content-Range"), offset, last)
                    ) {
                        throw IOException("short range body: ${body.size} of $length")
                    }
                    body
                }
                // A server that answers a Range with the whole object would turn every
                // page read into a 32 MiB pack download. Fail loud; never degrade silently.
                200 -> throw CloudHttpException(RANGE_IGNORED)
                else -> throw statusFailure(response)
            }
        }
    }

    override fun downloadRangeToFile(objectKey: String, offset: Long, length: Int, objectSize: Long, dest: File) {
        require(offset >= 0) { "offset must be >= 0" }
        require(length > 0) { "length must be > 0" }
        val last = offset + length - 1
        get(objectKey, range = offset..last).use { response ->
            when (response.code) {
                206 -> {
                    if (!contentRangeMatchesRequest(response.header("Content-Range"), offset, last)) {
                        throw IOException("short range body: 0 of $length")
                    }
                    val stream = response.body?.byteStream() ?: throw IOException("short range body: 0 of $length")
                    stream.use { input -> copyRangeBody(input, dest, 0, length, wholeBody = false) }
                }
                200 -> throw CloudHttpException(RANGE_IGNORED)
                else -> throw statusFailure(response)
            }
        }
    }

    override fun downloadToFile(objectKey: String, dest: File) {
        get(objectKey, range = null).use { response ->
            if (response.code !in 200..299) throw statusFailure(response)
            response.body!!.byteStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        }
    }

    override fun listKeys(): List<String> = listObjects("").map { it.key }

    override fun listKeys(prefix: String): List<String> = listObjects(prefix).map { it.key }

    override fun listObjects(prefix: String): List<ObjectSummary> {
        val out = ArrayList<ObjectSummary>()
        walk(prefix.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }, prefix, out)
        return out
    }

    override fun listUsage(): R2BucketUsage {
        val objects = listObjects("")
        return R2BucketUsage(objectCount = objects.size, totalBytes = objects.sumOf { it.sizeBytes.coerceAtLeast(0L) })
    }

    override fun delete(objectKey: String): Int {
        val request = authed(keyUrl(objectKey)).delete().build()
        execute(request).use { response ->
            return when {
                response.code in 200..299 -> response.code
                response.code == 404 -> 204
                else -> throw statusFailure(response)
            }
        }
    }

    // ---- PUT + parents ----

    private fun put(objectKey: String, bodyBytes: Long, body: () -> RequestBody): Int {
        val parent = parentDir(objectKey)
        // #E110: 123pan accepts the ENTIRE body before answering 404 for a
        // missing parent (owner phone 2026-10-12: fully-sent packs of up to
        // 8 MiB refused with "Not Found", then re-sent after MKCOL), so for
        // such a server the blind PUT paid the uplink twice for every new
        // chapter directory's first pack. Only a server we have SEEN refuse a
        // PUT for missing parents gets the chain created up front — a server
        // that auto-creates parents never pays a MKCOL (pinned by test).
        if (putNeedsExplicitParents && parent !in knownDirs) {
            synchronized(dirLock) { ensureDirs(parent) }
        }
        var code = sendPut(objectKey, bodyBytes, body)
        // 409 is RFC 4918's "parent missing"; 123pan says 404 instead once two or more
        // levels are missing (it creates a single missing level itself). A PUT has no
        // other reason to 404, so both mean: create the parents, send once more.
        if (code == 409 || code == 404) {
            putNeedsExplicitParents = true
            // One lane creates the chain; the others wait here and then find every
            // directory known. Six lanes each re-creating the same folders turned one
            // comic's first upload into 24 MKCOLs, and 123pan answered 429 (owner
            // phone, 2026-09-26).
            synchronized(dirLock) { ensureDirs(parent) }
            code = sendPut(objectKey, bodyBytes, body)
        }
        if (code in 200..299) {
            knownDirs += parent
            return code
        }
        throw statusFailure(code, null)
    }

    private fun sendPut(objectKey: String, bodyBytes: Long, body: () -> RequestBody): Int {
        val request = authed(keyUrl(objectKey)).put(body()).build()
        // A NAS behind a tunnel (spec §2.2): the edge swallows the body at LAN speed and
        // then forwards it over the home link, so the answer can come long after the
        // last byte left (measured: a 32 MiB PUT outlived a flat 20 s header wait) —
        // the same shape as #C53. The header wait grows with the body.
        //
        // #D12: no total-time ceiling while the body is moving. The write side already
        // fails after STALL_MS with no progress; a slow but moving uplink (owner phone,
        // 140-210 KB/s shared by the lanes) must be allowed to finish, because 123pan's
        // WebDAV cannot resume and every cut-off PUT throws away all it sent. The call
        // ceiling stays only as a guard against a server that never answers (a day).
        val bodyWaitMs = bodyBytes * 1_000L / MIN_UPLOAD_BYTES_PER_SEC
        val call = client.newBuilder()
            .readTimeout(STALL_MS + bodyWaitMs, TimeUnit.MILLISECONDS)
            .callTimeout(PUT_HUNG_SERVER_MS, TimeUnit.MILLISECONDS)
            .build()
            .newCall(request)
        call.execute().use { response ->
            if (response.code in RATE_LIMITED) throw CloudRateLimitedException.fromHeaders(response.headerMap())
            if (response.code !in 200..299) {
                // #E110: a bare status hid the provider's actual refusal for
                // hours — the body carries the reason and costs one peekBody.
                val reason = runCatching {
                    response.peekBody(512).bytes().toString(Charsets.UTF_8).replace("\n", " ").trim()
                }.getOrNull().orEmpty().ifEmpty { "<no body>" }
                DiagLog.w("WebDav", "PUT refused code=${response.code} bytes=$bodyBytes reason=$reason")
            }
            return response.code
        }
    }

    /** MKCOL every directory from the library folder down to [dir], skipping known ones. */
    private fun ensureDirs(dir: String) {
        val chain = ArrayList<String>()
        chain += ""
        var acc = ""
        dir.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            acc += "$segment/"
            chain += acc
        }
        // The user's own folder above the library may not exist yet either.
        val above = binding.folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if ("" !in knownDirs && above.isNotEmpty()) {
            var url = binding.baseUrl.trim().trimEnd('/')
            above.forEach { segment ->
                url += "/" + encodeSegment(segment)
                mkcol((url + "/").toHttpUrl(), null)
            }
        }
        chain.filter { it !in knownDirs }.forEach { mkcol(dirUrl(it), it) }
    }

    private fun mkcol(url: HttpUrl, dirKey: String?) {
        var spurious = 0
        while (true) {
            var code = 0
            var reason: String? = null
            execute(authed(url).method("MKCOL", null).build()).use { response ->
                code = response.code
                // #E110: same evidence rule as PUT — the body says why.
                if (code != 201 && code != 405 && code !in 200..299 && code != 424 && code !in RATE_LIMITED) {
                    reason = runCatching {
                        response.peekBody(256).bytes().toString(Charsets.UTF_8).replace("\n", " ").trim()
                    }.getOrNull()
                }
            }
            when {
                // 201 = created (123pan also says 201 for "already there"); 405 = exists.
                code == 201 || code == 405 || code in 200..299 -> {
                    dirKey?.let { knownDirs += it }
                    return
                }
                // Synology answers 424 to the next MKCOL after ANY 409 — including 409s other
                // lanes' PUTs are still producing — so a few 424s in a row are normal. Bounded.
                code == 424 && spurious < MAX_SPURIOUS_424 -> {
                    spurious++
                    sleeper(SPURIOUS_424_PAUSE_MS * spurious)
                }
                code in RATE_LIMITED -> throw CloudRateLimitedException()
                else -> {
                    if (existsAsCollection(url)) {
                        dirKey?.let { knownDirs += it }
                        return
                    }
                    DiagLog.w("WebDav", "MKCOL refused code=$code reason=${reason ?: "<no body>"}")
                    throw statusFailure(code, null)
                }
            }
        }
    }

    private fun existsAsCollection(url: HttpUrl): Boolean = propfindDepthZero(url) == 207

    /**
     * #D15: whether [WebDavBinding.baseUrl] itself is a WebDAV folder — one `PROPFIND` with `Depth: 0` on the address the
     * user typed, not the library folder. False only for 404 (nothing there: 123pan's bare host answers every path 404)
     * and 405 (the server does not speak WebDAV). Any other answer is left to the library-folder probe, which already
     * reads 401/403, a web page and a redirect (measured 2026-09-30: 123pan's `/webdav/` and Nutstore's `/dav/` answer
     * 401 without a login; the owner's NAS answers 403 on its base to an account that only reaches a sub-folder). A base
     * that answers 404/405 while the user's own folder answers otherwise is a server hiding its base: that counts too.
     * Throws on a network failure or a rate-limit answer, like every request here.
     */
    fun addressAnswersWebDav(): Boolean {
        val base = binding.baseUrl.trim().trimEnd('/')
        if (answersWebDav(propfindDepthZero("$base/".toHttpUrl()))) return true
        val folder = binding.folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (folder.isEmpty()) return false
        return answersWebDav(propfindDepthZero((base + "/" + folder.joinToString("/") { encodeSegment(it) } + "/").toHttpUrl()))
    }

    private fun answersWebDav(code: Int): Boolean = code != 404 && code != 405

    private fun propfindDepthZero(url: HttpUrl): Int =
        execute(
            authed(url).header("Depth", "0").method("PROPFIND", PROPFIND_BODY.toRequestBody(XML)).build(),
        ).use { it.code }

    // ---- GET with redirects ----

    private fun get(objectKey: String, range: LongRange?): Response {
        val origin = keyUrl(objectKey)
        var lagRetries = 0
        while (true) {
            var url = origin
            var sendAuth = true
            var redirected = false
            var hops = 0
            while (true) {
                val builder = Request.Builder().url(url).get()
                if (sendAuth) builder.header("Authorization", authorization)
                if (range != null) builder.header("Range", "bytes=${range.first}-${range.last}")
                val response = execute(builder.build())
                if (response.code in REDIRECTS) {
                    val location = response.header("Location")
                    response.close()
                    val next = location?.let { url.resolve(it) } ?: throw CloudHttpException("CLOUD_HTTP_${response.code}")
                    if (++hops > MAX_REDIRECTS) throw CloudHttpException("CLOUD_HTTP_${response.code}")
                    // Credentials never travel to another host (the 123pan CDN is one).
                    sendAuth = sendAuth && sameOrigin(next, origin)
                    url = next
                    redirected = true
                    continue
                }
                // 123pan: the CDN can answer 404 for about a second after a PUT, and one bad
                // CDN node answers 403 ("s err") to a valid signed URL (#D14) — the origin's
                // next redirect usually lands elsewhere. The origin's own 404/403 is never retried.
                if (response.code in CDN_RETRYABLE && redirected && lagRetries < REDIRECT_404_RETRIES) {
                    response.close()
                    lagRetries++
                    sleeper(REDIRECT_404_RETRY_MS)
                    break
                }
                return response
            }
        }
    }

    // ---- listing ----

    private fun walk(dir: String, prefix: String, out: MutableList<ObjectSummary>) {
        val url = dirUrl(dir)
        val request = authed(url)
            .header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .build()
        val entries = execute(request).use { response ->
            when (response.code) {
                207 -> WebDavPropfind.parse(response.body!!.string())
                404 -> return
                else -> throw statusFailure(response)
            }
        }
        knownDirs += dir
        val selfPath = url.encodedPath.let { decodePath(it) }.trimEnd('/')
        for (entry in entries) {
            if (entry.href.trimEnd('/') == selfPath) continue
            val name = entry.href.trimEnd('/').substringAfterLast('/')
            if (name.isEmpty()) continue
            if (entry.isCollection) {
                val child = "$dir$name/"
                if (child.startsWith(prefix) || prefix.startsWith(child)) walk(child, prefix, out)
            } else {
                val key = dir + name
                if (key.startsWith(prefix)) out += ObjectSummary(key, entry.sizeBytes)
            }
        }
    }

    // ---- plumbing ----

    private fun authed(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("Authorization", authorization)

    private val requestClient: OkHttpClient by lazy {
        if (requestDeadlineMs == TOTAL_DEADLINE_MS) client else client.newBuilder().callTimeout(requestDeadlineMs, TimeUnit.MILLISECONDS).build()
    }

    private fun execute(request: Request): Response {
        val response = requestClient.newCall(request).execute()
        if (response.code in RATE_LIMITED) {
            response.use { throw CloudRateLimitedException.fromHeaders(it.headerMap()) }
        }
        return response
    }

    private fun keyUrl(objectKey: String): HttpUrl {
        require(objectKey.isNotBlank()) { "objectKey required" }
        require(!objectKey.split('/').any { it == ".." }) { "bad objectKey" }
        return (rootUrl.toString() + objectKey.trimStart('/').split('/').joinToString("/") { encodeSegment(it) }).toHttpUrl()
    }

    private fun dirUrl(dirKey: String): HttpUrl =
        if (dirKey.isEmpty()) rootUrl else keyUrl(dirKey.trimEnd('/')).newBuilder().addPathSegment("").build()

    private fun statusFailure(response: Response): Exception = response.use { statusFailure(it.code, it.headerMap()) }

    private fun statusFailure(code: Int, headers: Map<String, String>?): Exception = when (code) {
        in RATE_LIMITED -> CloudRateLimitedException.fromHeaders(headers.orEmpty())
        507 -> CloudHttpException(QUOTA_FULL)
        else -> CloudHttpException("CLOUD_HTTP_$code")
    }

    companion object {
        const val RANGE_IGNORED: String = "CLOUD_RANGE_IGNORED"
        const val QUOTA_FULL: String = "CLOUD_QUOTA_FULL"

        /** rclone's retry set minus the 5xx that mean "broken", not "busy".
         *  #E110: 504 joined after 123pan's gateway timed out on large PUTs
         *  ("The server didn't respond in time", bodies captured on the owner
         *  phone) — transient, and failing the row threw the whole body away. */
        private val RATE_LIMITED = setOf(423, 425, 429, 503, 504, 509)
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private const val MAX_REDIRECTS = 5
        private const val REDIRECT_404_RETRIES = 5
        private const val REDIRECT_404_RETRY_MS = 500L
        private val CDN_RETRYABLE = setOf(403, 404)
        private const val MAX_SPURIOUS_424 = 4
        private const val SPURIOUS_424_PAUSE_MS = 150L
        private const val TOTAL_DEADLINE_MS = 180_000L
        /** #D12: a PUT whose body keeps moving is never cut off; this only ends a server that never answers. */
        private const val PUT_HUNG_SERVER_MS = 24L * 60 * 60 * 1000
        private const val STALL_MS = 20_000L
        private const val MIN_UPLOAD_BYTES_PER_SEC = 64L * 1024
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop>""" +
                """<d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""

        /**
         * Connect 30 s and a 20 s stall window per read/write (the values expo-app measured on
         * the same servers). Seeded from [SourceHttp] (#E19 Task 7) so the family-preference
         * Dns and its connect-evidence listener apply to cloud transfers; every setting in the
         * chain below is carried over verbatim from the pre-#E19 builder.
         */
        internal fun seededBuilder(): OkHttpClient.Builder =
            SourceHttp.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(STALL_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(STALL_MS, TimeUnit.MILLISECONDS)
                .callTimeout(TOTAL_DEADLINE_MS, TimeUnit.MILLISECONDS)

        private val sharedClient: OkHttpClient by lazy {
            seededBuilder().build()
        }

        internal fun encodeSegment(segment: String): String =
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")

        private fun decodePath(path: String): String =
            java.net.URLDecoder.decode(path.replace("+", "%2B"), Charsets.UTF_8.name())

        private fun parentDir(objectKey: String): String =
            objectKey.trimStart('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

        private fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
            a.scheme == b.scheme && a.host == b.host && a.port == b.port

        private fun Response.headerMap(): Map<String, String> = headers.associate { it.first to it.second }
    }
}
