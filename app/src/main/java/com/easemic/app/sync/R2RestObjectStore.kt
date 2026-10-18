package com.easemic.app.sync

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

fun interface R2RestHttp {
    fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: R2RestBody?,
    ): R2RestHttpResponse
}

/**
 * #C51: what a REST request sends as its body. A page pack is built on disk and
 * must never be read into a `ByteArray` on the way out — six lanes × 32 MiB is
 * ~192 MiB transient on a phone — so the transport is handed the [FileBody] and
 * streams it. [R2RestHttp] is a `fun interface` (one abstract method), so the
 * two shapes live in the parameter instead of in a second overload.
 */
sealed interface R2RestBody {
    /** What `Content-Length` / `setFixedLengthStreamingMode` need, without reading the body. */
    val sizeBytes: Long

    /** An in-memory body: a manifest, a JSON payload, a small object. */
    data class Bytes(val bytes: ByteArray) : R2RestBody {
        override val sizeBytes: Long get() = bytes.size.toLong()

        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** A body that stays on disk and is streamed off it (#C51 page/thumb packs). */
    data class FileBody(val file: File) : R2RestBody {
        override val sizeBytes: Long get() = file.length()
    }
}

data class R2RestHttpResponse(
    val status: Int,
    val body: ByteArray,
    /** Header names as the server sent them — look up case-insensitively. */
    val headers: Map<String, String> = emptyMap(),
)

/** #E29: a GET whose successful body is streamed to [dest]. Errors carry no body. */
fun interface R2RestFileHttp {
    fun get(url: String, headers: Map<String, String>, dest: File): R2RestFileResponse
}

data class R2RestFileResponse(val status: Int, val headers: Map<String, String>)

/**
 * Bearer supply for the REST store (C37-c). [current] is read on EVERY
 * request. Blocking: implementations may park the caller for one token
 * exchange; never call on the main thread.
 *
 * [afterUnauthorized] is the single-flight recovery for a 401 and has three
 * possible outcomes:
 * - a DIFFERENT non-blank token than [usedToken]: the store retries once with it;
 * - the SAME token as [usedToken]: no newer token exists, so the store hands
 *   the original 401 response through untouched (an authorization failure,
 *   not a refresh failure);
 * - `null`: the source tried to refresh and failed (or is dead), surfaced as
 *   `CloudHttpException` carrying [R2RestObjectStore.OAUTH_REFRESH_FAILED_MESSAGE].
 */
interface R2BearerTokens {
    fun current(): String?
    fun afterUnauthorized(usedToken: String): String?

    companion object {
        /** A fixed bearer (API token, probes, tests): never refreshes; a 401 is always the bucket's. */
        fun fixed(token: String): R2BearerTokens = object : R2BearerTokens {
            override fun current(): String = token
            override fun afterUnauthorized(usedToken: String): String = token
        }
    }
}

/**
 * Cloudflare R2 REST object I/O. The bearer is read through [R2BearerTokens]
 * on every request; a short-lived OAuth access token's refresh token stays in
 * [com.easemic.app.feature.bridge.BridgeOAuthDeviceSecrets].
 */
class R2RestObjectStore(
    private val accountId: String,
    private val bucket: String,
    private val tokens: R2BearerTokens,
    private val http: R2RestHttp = defaultHttp(),
    private val fileHttp: R2RestFileHttp = defaultFileHttp(),
) : ObjectStore {
    override val supportsEfficientRange: Boolean = false

    /** Fixed-bearer convenience (probes, admin, tests). */
    constructor(
        accountId: String,
        bucket: String,
        accessToken: String,
        http: R2RestHttp = defaultHttp(),
        fileHttp: R2RestFileHttp = defaultFileHttp(),
    ) : this(accountId, bucket, R2BearerTokens.fixed(accessToken), http, fileHttp)

    override fun toString(): String =
        "R2RestObjectStore(accountId=$accountId, bucket=$bucket, tokens=<redacted>)"

    // #C54: the one-entry whole-object memo + per-key single-flight that make a
    // Range-ignoring REST GET (200, whole body) cost one request per pack instead of
    // one per window. [memoLock] guards only the map/memo bookkeeping below -- never
    // the HTTP call or a waiter's wait, both of which happen outside it.
    private val memoLock = Any()
    private var wholeMemo: WholeMemo? = null
    private val inFlight = HashMap<String, InFlightWhole>()

    private class WholeMemo(val key: String, val bytes: ByteArray)

    /**
     * #C54: per-key single-flight state for a whole-object GET. [dirty] is set by a
     * write (upload/uploadFile/delete) that overlaps the flight (spec A1): the flight's
     * bytes still resolve the leader and every waiter, but never enter [wholeMemo].
     */
    private class InFlightWhole {
        val latch = java.util.concurrent.CountDownLatch(1)
        @Volatile var bytes: ByteArray? = null
        @Volatile var hasWholeBody: Boolean = false
        @Volatile var error: Throwable? = null
        @Volatile var dirty: Boolean = false
    }

    /** #C54: drop the memo entry and mark an in-flight whole GET for this key dirty. Called before AND after a write's own request so a flight overlapping the write on either side never fills the memo with stale bytes (spec A1). */
    private fun invalidate(objectKey: String) {
        synchronized(memoLock) {
            wholeMemo?.let { if (it.key == objectKey) wholeMemo = null }
            inFlight[objectKey]?.dirty = true
        }
    }

    private fun sliceOrThrow(whole: ByteArray, offset: Long, length: Int): ByteArray {
        if (offset + length > whole.size) {
            throw java.io.IOException("range past the end of the object: $offset+$length of ${whole.size}")
        }
        return whole.copyOfRange(offset.toInt(), (offset + length).toInt())
    }

    override fun upload(objectKey: String, bytes: ByteArray, mime: String): Int {
        invalidate(objectKey)
        // #C54/R1: the post-invalidation must run even when the request THROWS (bytes may
        // have landed server-side while the response wait timed out) -- otherwise a whole
        // GET that started during this PUT memoises the OLD bytes forever.
        try {
            val response = authorizedRequest(
                "PUT",
                objectUrl(objectKey),
                contentType = mime,
                body = R2RestBody.Bytes(bytes),
            )
            return response.status
        } finally {
            invalidate(objectKey)
        }
    }

    /**
     * #C51: a 32 MiB pack must never be materialised; the digest streams, the body streams.
     *
     * (This transport signs nothing — the bearer is the credential — so "the digest
     * streams" is the S3 store's half of the same rule, [R2ObjectStore.uploadFile].
     * Here the file goes down as an [R2RestBody.FileBody] and [defaultHttp] copies it
     * off disk in 64 KiB chunks; `upload(objectKey, file.readBytes(), mime)` used to
     * hold the whole pack twice over — once for the array, once for the request body.)
     */
    override fun uploadFile(objectKey: String, file: File, mime: String): Int {
        invalidate(objectKey)
        // #C54/R1: same as [upload] -- the post-invalidation must survive a throw.
        try {
            val response = authorizedRequest(
                "PUT",
                objectUrl(objectKey),
                contentType = mime,
                body = R2RestBody.FileBody(file),
            )
            return response.status
        } finally {
            invalidate(objectKey)
        }
    }

    override fun download(objectKey: String): ByteArray {
        val response = authorizedRequest("GET", objectUrl(objectKey), body = null)
        requireOk(response.status)
        return response.body
    }

    /**
     * #C51/#C54: bytes `[offset, offset + length)` through an HTTP `Range` GET — the read
     * a v5 page pack is addressed with. `206` is the range, handled as before and never
     * touches the memo. Cloudflare's REST object GET ignores `Range` (measured
     * 2026-09-21): a `200` means the body is the whole object, so it is kept in a
     * one-entry memo (one pack, <= ~32 MiB) keyed by [objectKey] and every later window of
     * the SAME key is sliced from it with no request — unless the window covered at least
     * half the object on the unsized API (#E15 T4: avoid pinning a single-page body). Sized pack
     * reads keep any partial body so a larger first page can still serve smaller neighbours. A different key replaces the entry,
     * and `upload`/`uploadFile`/`delete` drop it. Concurrent windows of one key
     * single-flight the whole GET (per key — a second pack in flight gets its own entry)
     * so N callers cost one GET, not N; a write overlapping the flight marks it dirty so
     * its bytes still resolve the leader and every waiter but never enter the memo (spec
     * A1). Anything else takes the existing [CloudHttpException] path. #C54/R2: a failed
     * leader does not fail its waiters outright -- each rejoins [downloadRangeInternal]
     * once instead (see [leaderFetch]).
     */
    override fun downloadRange(objectKey: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "offset must be >= 0" }
        require(length > 0) { "length must be > 0" }
        return downloadRangeInternal(objectKey, offset, length, mayRejoin = true, memoisePartialBody = false)
    }

    /**
     * Sized pack reads share every partial whole-body response, including a page larger than half its pack.
     * The reader streams single-page packs separately; a multi-page pack still has other pages to serve.
     * Unsized callers retain the existing half-body memo rule.
     */
    override fun downloadRange(objectKey: String, offset: Long, length: Int, objectSize: Long): ByteArray {
        require(offset >= 0) { "offset must be >= 0" }
        require(length > 0) { "length must be > 0" }
        return downloadRangeInternal(objectKey, offset, length, mayRejoin = true, memoisePartialBody = true)
    }

    /**
     * #C54/R2: the validated body of [downloadRange]. [mayRejoin] bounds the rejoin-once
     * rule to exactly one hop per caller: a waiter whose leader failed does NOT rethrow
     * outright (that would fail every healthy waiter for one bad flight) and does NOT
     * issue its own independent GET (that would turn a single-flight failure into a herd
     * of N concurrent whole-pack GETs -- exactly what single-flight exists to prevent).
     * Instead it re-enters this method once with `mayRejoin = false`: one of the
     * re-entrants becomes the leader of a fresh flight, the rest wait on that one. A
     * waiter that rejoins and finds its NEW leader has also failed rethrows -- bounded,
     * never a loop.
     */
    private fun downloadRangeInternal(
        objectKey: String, offset: Long, length: Int, mayRejoin: Boolean, memoisePartialBody: Boolean,
    ): ByteArray {
        val entry: InFlightWhole
        val isLeader: Boolean
        synchronized(memoLock) {
            wholeMemo?.let { memo -> if (memo.key == objectKey) return sliceOrThrow(memo.bytes, offset, length) }
            val existing = inFlight[objectKey]
            if (existing != null) {
                entry = existing
                isLeader = false
            } else {
                entry = InFlightWhole()
                inFlight[objectKey] = entry
                isLeader = true
            }
        }

        if (!isLeader) {
            entry.latch.await()
            entry.error?.let { error ->
                return if (mayRejoin) {
                    downloadRangeInternal(objectKey, offset, length, mayRejoin = false, memoisePartialBody = memoisePartialBody)
                } else throw error
            }
            return if (entry.hasWholeBody) sliceOrThrow(entry.bytes!!, offset, length) else plainRangeGet(objectKey, offset, length)
        }
        return leaderFetch(entry, objectKey, offset, length, memoisePartialBody)
    }

    /**
     * #C54: the leader's own whole-object Range GET. Signals [entry] (fills the memo when
     * the key was not marked dirty) before validating the leader's own window, so one
     * caller's out-of-range window can never fail the others. A failure removes the
     * in-flight entry; nothing partial is ever memoised. #C54/R2: the failure is no longer
     * rethrown to every waiter untouched -- each waiter rejoins [downloadRangeInternal]
     * once (one becomes the new leader of a fresh flight, the rest wait on it), and only a
     * waiter that has already rejoined once rethrows the error.
     */
    private fun leaderFetch(
        entry: InFlightWhole, objectKey: String, offset: Long, length: Int, memoisePartialBody: Boolean,
    ): ByteArray {
        val response = try {
            authorizedRequest(
                "GET",
                objectUrl(objectKey),
                body = null,
                extraHeaders = mapOf("Range" to "bytes=$offset-${offset + length - 1}"),
            )
        } catch (t: Throwable) {
            failLeader(entry, objectKey, t)
            throw t
        }

        if (response.status == 206) {
            if (response.body.size != length) {
                val error = java.io.IOException("short range body: ${response.body.size} of $length")
                failLeader(entry, objectKey, error)
                throw error
            }
            // #E15 T4: the right length at the wrong offset is the wrong bytes — WebDAV's
            // exact-span rule; an absent header stays accepted.
            val contentRange = response.headers.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value
            if (!contentRangeMatchesRequest(contentRange, offset, offset + length - 1L)) {
                val error = java.io.IOException(
                    "206 Content-Range names another span: \"$contentRange\", " +
                        "wanted bytes $offset-${offset + length - 1}",
                )
                failLeader(entry, objectKey, error)
                throw error
            }
            synchronized(memoLock) { inFlight.remove(objectKey) }
            entry.hasWholeBody = false
            entry.latch.countDown()
            return response.body
        }

        try {
            requireOk(response.status)
        } catch (t: Throwable) {
            failLeader(entry, objectKey, t)
            throw t
        }

        val whole = response.body
        synchronized(memoLock) {
            // Sized multi-page pack reads can serve a second page even when the first page is most of the pack.
            // Judge reuse against the actual response size, not the possibly stale object-size hint.
            val reusable = if (memoisePartialBody) length < whole.size else length.toLong() * 2 < whole.size
            if (!entry.dirty && reusable) wholeMemo = WholeMemo(objectKey, whole)
            inFlight.remove(objectKey)
        }
        entry.bytes = whole
        entry.hasWholeBody = true
        entry.latch.countDown()
        return sliceOrThrow(whole, offset, length)
    }

    private fun failLeader(entry: InFlightWhole, objectKey: String, error: Throwable) {
        synchronized(memoLock) { inFlight.remove(objectKey) }
        entry.error = error
        entry.latch.countDown()
    }

    /** #C54: a waiter's own request once the leader's whole GET turned out to be honoured (206) -- one direct Range GET, never re-entering the memo/single-flight. */
    private fun plainRangeGet(objectKey: String, offset: Long, length: Int): ByteArray {
        val response = authorizedRequest(
            "GET",
            objectUrl(objectKey),
            body = null,
            extraHeaders = mapOf("Range" to "bytes=$offset-${offset + length - 1}"),
        )
        if (response.status == 206) {
            if (response.body.size != length) {
                throw java.io.IOException("short range body: ${response.body.size} of $length")
            }
            // #E15 T4: same exact-span rule as the leader's 206 — a waiter must not
            // accept the wrong slice either; an absent header stays accepted.
            val contentRange = response.headers.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value
            if (!contentRangeMatchesRequest(contentRange, offset, offset + length - 1L)) {
                throw java.io.IOException(
                    "206 Content-Range names another span: \"$contentRange\", " +
                        "wanted bytes $offset-${offset + length - 1}",
                )
            }
            return response.body
        }
        requireOk(response.status)
        return sliceOrThrow(response.body, offset, length)
    }

    override fun downloadRangeToFile(
        objectKey: String,
        offset: Long,
        length: Int,
        objectSize: Long,
        dest: File,
    ) {
        require(offset >= 0) { "offset must be >= 0" }
        require(length > 0) { "length must be > 0" }
        val parent = dest.parentFile ?: dest.absoluteFile.parentFile
        parent?.mkdirs()
        val staging = File.createTempFile("e29-rest-", ".part", parent)
        try {
            val response = authorizedFileGet(
                objectUrl(objectKey),
                mapOf("Range" to "bytes=$offset-${offset + length - 1}"),
                staging,
            )
            when (response.status) {
                206 -> {
                    val contentRange = response.headers.entries
                        .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value
                    if (!contentRangeMatchesRequest(contentRange, offset, offset + length - 1L)) {
                        throw IOException(
                            "206 Content-Range names another span: \"$contentRange\", " +
                                "wanted bytes $offset-${offset + length - 1}",
                        )
                    }
                    if (staging.length() != length.toLong()) {
                        throw IOException("short range body: ${staging.length()} of $length")
                    }
                    moveOnto(staging, dest)
                }
                200 -> FileInputStream(staging).use { input ->
                    copyRangeBody(input, dest, offset, length, wholeBody = true)
                }
                else -> throw CloudHttpException("CLOUD_HTTP_${response.status}")
            }
        } catch (failure: Throwable) {
            truncate(dest)
            throw failure
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    override fun downloadToFile(objectKey: String, dest: File) {
        dest.parentFile?.mkdirs()
        try {
            val response = authorizedFileGet(objectUrl(objectKey), emptyMap(), dest)
            if (response.status !in 200..299) throw CloudHttpException("CLOUD_HTTP_${response.status}")
        } catch (failure: Throwable) {
            truncate(dest)
            throw failure
        }
    }

    private fun authorizedFileGet(
        url: String,
        extraHeaders: Map<String, String>,
        dest: File,
    ): R2RestFileResponse {
        val token = tokens.current() ?: throw CloudHttpException(OAUTH_REFRESH_FAILED_MESSAGE)
        val first = fileHttp.get(url, headers(token, extraHeaders = extraHeaders), dest)
        val response = if (first.status != 401) {
            first
        } else {
            val fresh = tokens.afterUnauthorized(token) ?: throw CloudHttpException(OAUTH_REFRESH_FAILED_MESSAGE)
            require(fresh.isNotBlank()) { "refreshed accessToken blank" }
            if (fresh == token) {
                first
            } else {
                truncate(dest)
                fileHttp.get(url, headers(fresh, extraHeaders = extraHeaders), dest)
            }
        }
        if (response.status == 429) throw CloudRateLimitedException.fromHeaders(response.headers)
        return response
    }

    private fun moveOnto(source: File, dest: File) {
        if (source.renameTo(dest)) return
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output, 64 * 1024) }
        }
    }

    private fun truncate(file: File) {
        FileOutputStream(file).use { }
    }

    override fun listKeys(): List<String> = listAllPages().map { it.key }

    override fun listKeys(prefix: String): List<String> = listAllPages(prefix).map { it.key }

    /** #C51: the sizes the REST listing already parses — what the pack presence-by-size resume compares against. */
    override fun listObjects(prefix: String): List<ObjectSummary> =
        listAllPages(prefix).map { ObjectSummary(it.key, it.sizeBytes) }

    override fun listUsage(): R2BucketUsage {
        val objects = listAllPages()
        return R2BucketUsage(
            objectCount = objects.size,
            totalBytes = objects.sumOf { it.sizeBytes },
        )
    }

    override fun delete(objectKey: String): Int {
        invalidate(objectKey)
        // #C54/R1: same as [upload] -- the post-invalidation must survive a throw.
        try {
            val response = authorizedRequest("DELETE", objectUrl(objectKey), body = null)
            return response.status
        } finally {
            invalidate(objectKey)
        }
    }

    private fun listAllPages(prefix: String? = null): List<ListedObject> {
        val objects = ArrayList<ListedObject>()
        var cursor: String? = null
        var lastToken: String? = null
        repeat(MAX_LIST_PAGES) {
            val response = authorizedRequest("GET", listUrl(cursor, prefix), body = null)
            requireOk(response.status)
            val page = parseListPage(String(response.body, StandardCharsets.UTF_8))
            objects.addAll(page.objects)
            if (!page.isTruncated) return objects
            val token = page.cursor?.takeIf { it.isNotBlank() } ?: return objects
            if (token == lastToken) return objects
            lastToken = token
            cursor = token
        }
        return objects
    }

    private fun authorizedRequest(
        method: String,
        url: String,
        contentType: String? = null,
        body: R2RestBody?,
        /** #C51: request headers outside the bearer/content-type pair (today: `Range`). */
        extraHeaders: Map<String, String> = emptyMap(),
    ): R2RestHttpResponse {
        val token = tokens.current() ?: throw CloudHttpException(OAUTH_REFRESH_FAILED_MESSAGE)
        val first = http.request(method, url, headers(token, contentType, extraHeaders), body)
        val response = if (first.status != 401) {
            first
        } else {
            val fresh = tokens.afterUnauthorized(token) ?: throw CloudHttpException(OAUTH_REFRESH_FAILED_MESSAGE)
            require(fresh.isNotBlank()) { "refreshed accessToken blank" }
            // Same token back = nothing newer exists: hand the 401 through untouched so
            // callers classify it as an authorization failure, not a refresh failure.
            if (fresh == token) first else http.request(method, url, headers(fresh, contentType, extraHeaders), body)
        }
        if (response.status == 429) throw CloudRateLimitedException.fromHeaders(response.headers)
        return response
    }

    private fun listUrl(cursor: String?, prefix: String? = null): String {
        var base = "${apiBase()}/objects?per_page=1000"
        prefix?.takeIf { it.isNotBlank() }?.let { p ->
            base += "&prefix=" + URLEncoder.encode(p, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
        if (cursor.isNullOrBlank()) return base
        val encoded = URLEncoder.encode(cursor, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return "$base&cursor=$encoded"
    }

    private fun objectUrl(objectKey: String): String {
        require(objectKey.isNotBlank()) { "objectKey required" }
        require(!objectKey.contains("..")) { "bad objectKey" }
        val encoded = objectKey.split("/").joinToString("/") { segment ->
            require(segment.isNotEmpty()) { "objectKey required" }
            require(segment != "..") { "bad objectKey" }
            URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
        return "${apiBase()}/objects/$encoded"
    }

    private fun apiBase(): String {
        val account = accountId.trim()
        val bucketName = bucket.trim('/')
        return "$API_ROOT/accounts/$account/r2/buckets/$bucketName"
    }

    private fun headers(
        token: String,
        contentType: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map["Authorization"] = "Bearer $token"
        if (contentType != null) {
            map["Content-Type"] = contentType
        }
        // #C51: never allowed to overwrite the bearer.
        extraHeaders.forEach { (name, value) ->
            if (!name.equals("Authorization", ignoreCase = true)) map[name] = value
        }
        return map
    }

    private fun requireOk(status: Int) {
        if (status !in 200..299) {
            throw CloudHttpException("CLOUD_HTTP_$status")
        }
    }

    companion object {
        private const val API_ROOT = "https://api.cloudflare.com/client/v4"
        private const val MAX_LIST_PAGES = 10_000

        /**
         * The token source could not hand over a usable bearer (a refresh that
         * failed, or a dead grant). Surfaced as the [CloudHttpException] message
         * so callers classify it as a refresh failure, not an authorization one.
         */
        const val OAUTH_REFRESH_FAILED_MESSAGE = "OAUTH_REFRESH_FAILED"

        /** Same ceiling as the S3 transport: bounds a stalled handshake or body write, not a healthy transfer. */
        internal const val TOTAL_REQUEST_DEADLINE_MS = 180_000L

        /**
         * #C45/#C46/#C53: reads and writes give up on a silent socket; only DELETE
         * keeps the long wait. PUT's wait must also cover the kernel's unsent
         * backlog after the last body byte, so it depends on [bodyBytes] too.
         * #C53 round 2: and on whether the socket's send buffer was bounded
         * ([bounded], derived once by [R2ObjectStore.isBounded]) — the API host
         * always is, so the wait no longer scales with the body there; anything
         * unbounded keeps round 1's assumed backlog.
         */
        internal fun readTimeoutForMethod(method: String, bodyBytes: Long, bounded: Boolean): Long =
            when {
                method.equals("DELETE", ignoreCase = true) -> R2ObjectStore.BODY_READ_TIMEOUT_MS
                method.equals("PUT", ignoreCase = true) ->
                    R2ObjectStore.postBodyWaitMs(bodyBytes, bounded, R2ObjectStore.SILENT_SOCKET_TIMEOUT_MS)
                else -> R2ObjectStore.SILENT_SOCKET_TIMEOUT_MS
            }

        fun defaultFileHttp(
            totalRequestDeadlineMs: Long = TOTAL_REQUEST_DEADLINE_MS,
        ): R2RestFileHttp = R2RestFileHttp { url, requestHeaders, dest ->
            val connection = URL(url).openConnection() as HttpURLConnection
            val factory = R2ObjectStore.boundedSendBufferFactory
            if (connection is HttpsURLConnection) {
                connection.sslSocketFactory = factory
            }
            val bounded = R2ObjectStore.isBounded(connection, factory)
            connection.connectTimeout = 30_000
            connection.readTimeout = readTimeoutForMethod("GET", 0L, bounded).toInt()
            connection.requestMethod = "GET"
            requestHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            try {
                R2ObjectStore.withRequestDeadline(connection, totalRequestDeadlineMs) {
                    val status = connection.responseCode
                    val responseHeaders = LinkedHashMap<String, String>()
                    connection.headerFields?.forEach { (name, values) ->
                        if (name != null && values != null && values.isNotEmpty()) responseHeaders[name] = values.last()
                    }
                    if (status in 200..299) {
                        connection.inputStream.use { input ->
                            java.io.FileOutputStream(dest).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                    } else {
                        connection.errorStream?.use { input ->
                            val buffer = ByteArray(8 * 1024)
                            var left = 64 * 1024
                            while (left > 0) {
                                val n = input.read(buffer, 0, minOf(buffer.size, left))
                                if (n < 0) break
                                left -= n
                            }
                        }
                        java.io.FileOutputStream(dest).use { }
                    }
                    R2RestFileResponse(status, responseHeaders)
                }
            } finally {
                connection.disconnect()
            }
        }

        fun defaultHttp(
            totalRequestDeadlineMs: Long = TOTAL_REQUEST_DEADLINE_MS,
            silentProgressMs: Long = R2ObjectStore.SILENT_PROGRESS_MS,
        ): R2RestHttp = R2RestHttp { method, url, headers, body ->
            val bodyBytes = body?.sizeBytes ?: 0L
            val connection = URL(url).openConnection() as HttpURLConnection
            // #C53 round 2: bound this socket's send buffer, so the watchdog's
            // "no progress" means the LINK stalled, not that a 4 MiB kernel
            // buffer swallowed the whole body in one unblocking write.
            val factory = R2ObjectStore.boundedSendBufferFactory
            if (connection is HttpsURLConnection) {
                connection.sslSocketFactory = factory
            }
            // Round-2 audit B3: one derived fact, never the URL scheme (see R2ObjectStore.isBounded).
            val bounded = R2ObjectStore.isBounded(connection, factory)
            connection.connectTimeout = 30_000
            connection.readTimeout = readTimeoutForMethod(method, bodyBytes, bounded).toInt()
            connection.requestMethod = method
            headers.forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }
            try {
                R2ObjectStore.withRequestDeadline(connection, R2ObjectStore.requestDeadlineMs(bodyBytes, totalRequestDeadlineMs)) {
                    if (body != null) {
                        // #C46 D4: the body goes out in counted 64 KB chunks under the
                        // silent-progress watchdog — a slow upstream is never cut, a
                        // silent one is given up on after `silentProgressMs`.
                        // #C53 audit R7: the watchdog wraps only the body write here
                        // (`responseCode` and the response read sit outside it), so the
                        // widened TAIL in this store comes from `readTimeout` above.
                        // Passing `bodyBytes`/`bounded` in is still live: it covers the
                        // blocking `outputStream.close()` inside `.use {}`, which is
                        // where a full kernel buffer actually parks.
                        // #C51: a [R2RestBody.FileBody] is opened here and copied off
                        // disk in the same 64 KiB chunks — never read into an array.
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(bodyBytes)
                        val progress = java.util.concurrent.atomic.AtomicLong(0)
                        R2ObjectStore.withSilentProgressWatchdog(connection, silentProgressMs, progress, bodyBytes, bounded) {
                            openBody(body).use { input ->
                                connection.outputStream.use { R2ObjectStore.writeCounted(it, input, progress) }
                            }
                        }
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream ?: connection.inputStream
                    }
                    val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                    val responseHeaders = LinkedHashMap<String, String>()
                    connection.headerFields?.forEach { (name, values) ->
                        if (name != null && values != null && values.isNotEmpty()) responseHeaders[name] = values.last()
                    }
                    R2RestHttpResponse(status, bytes, responseHeaders)
                }
            } finally {
                connection.disconnect()
            }
        }

        /** #C51: the one place a request body becomes a stream — bytes from RAM, a pack straight off disk. */
        private fun openBody(body: R2RestBody): java.io.InputStream = when (body) {
            is R2RestBody.Bytes -> body.bytes.inputStream()
            is R2RestBody.FileBody -> java.io.FileInputStream(body.file)
        }

        private fun parseListPage(body: String): ListPage {
            val json = JSONObject(body)
            val result = json.optJSONArray("result")
            val listed = ArrayList<ListedObject>()
            if (result != null) {
                for (i in 0 until result.length()) {
                    val item = result.optJSONObject(i) ?: continue
                    val key = item.optString("key")
                    if (key.isEmpty()) continue
                    listed.add(ListedObject(key, item.optLong("size", 0L)))
                }
            }
            val info = json.optJSONObject("result_info")
            val truncated = info?.optBoolean("is_truncated", false) ?: false
            val next = info?.optString("cursor")?.takeIf { it.isNotBlank() }
            return ListPage(listed, truncated, next)
        }
    }

    private data class ListedObject(val key: String, val sizeBytes: Long)

    private data class ListPage(
        val objects: List<ListedObject>,
        val isTruncated: Boolean,
        val cursor: String?,
    )
}
