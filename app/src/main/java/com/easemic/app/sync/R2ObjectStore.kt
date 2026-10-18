package com.easemic.app.sync

import com.easemic.app.feature.bridge.BridgeR2Binding
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

fun interface R2HttpPut {
    fun put(
        url: String,
        body: ByteArray,
        contentType: String,
        authorization: String,
        extraHeaders: Map<String, String>,
    ): Int
}

fun interface R2HttpPutFile {
    fun put(
        url: String,
        body: File,
        contentType: String,
        authorization: String,
        extraHeaders: Map<String, String>,
    ): Int
}

fun interface R2HttpGet {
    fun get(
        url: String,
        authorization: String,
        extraHeaders: Map<String, String>,
    ): ByteArray
}

/**
 * #C51: a GET carrying `Range: bytes=<offset>-<offset+length-1>`. Returns exactly
 * [length] bytes — a 206 body, or the slice of a whole body when the server ignored
 * the header. Separate from [R2HttpGet] so the range path can be faked in tests
 * without touching the plain GET seam.
 */
fun interface R2HttpGetRange {
    fun getRange(
        url: String,
        authorization: String,
        extraHeaders: Map<String, String>,
        offset: Long,
        length: Int,
    ): ByteArray
}

/** #E29: the range GET streamed to [dest]. No response body is returned. */
fun interface R2HttpGetRangeFile {
    fun getRangeToFile(
        url: String,
        authorization: String,
        extraHeaders: Map<String, String>,
        offset: Long,
        length: Int,
        dest: File,
    )
}

fun interface R2HttpGetText {
    fun getText(
        url: String,
        authorization: String,
        extraHeaders: Map<String, String>,
    ): String
}

fun interface R2HttpGetFile {
    fun getToFile(
        url: String,
        authorization: String,
        dest: File,
        extraHeaders: Map<String, String>,
    )
}

fun interface R2HttpDelete {
    fun delete(
        url: String,
        authorization: String,
        extraHeaders: Map<String, String>,
    ): Int
}

/**
 * Path-style R2 Put/Get/Delete/List using SigV4. Secrets stay on the device.
 *
 * SigV4 here signs only `host;x-amz-content-sha256;x-amz-date`, so the
 * `Content-Type` sent on a PUT is outside the signature and can carry the
 * caller's real mime without invalidating it.
 */
class R2ObjectStore(
    private val binding: BridgeR2Binding,
    /**
     * Hard wall-clock ceiling on a single R2 request (all five HTTP verbs),
     * from connect through the whole handshake/transfer/response-read. A
     * TLS-handshake socket read (Conscrypt `doHandshake`) is bounded by
     * neither `connectTimeout` nor `readTimeout` on this Android stack, so
     * this watchdog is the only thing that can defeat a stalled connection.
     * Exposed as a constructor param so tests can inject a tiny value.
     */
    private val totalRequestDeadlineMs: Long = TOTAL_REQUEST_DEADLINE_MS,
    private val httpPut: R2HttpPut = defaultHttpPut(totalRequestDeadlineMs),
    private val httpGet: R2HttpGet = defaultHttpGet(totalRequestDeadlineMs),
    private val httpGetRange: R2HttpGetRange = defaultHttpGetRange(totalRequestDeadlineMs),
    private val httpGetText: R2HttpGetText = defaultHttpGetText(totalRequestDeadlineMs),
    private val httpDelete: R2HttpDelete = defaultHttpDelete(totalRequestDeadlineMs),
    private val httpPutFile: R2HttpPutFile = defaultHttpPutFile(totalRequestDeadlineMs),
    private val httpGetFile: R2HttpGetFile = defaultHttpGetFile(totalRequestDeadlineMs),
    private val clockIso: () -> String = { utcAmzDate() },
    private val httpGetRangeFile: R2HttpGetRangeFile = defaultHttpGetRangeFile(totalRequestDeadlineMs),
) : ObjectStore {
    override val supportsEfficientRange: Boolean = true

    override fun upload(objectKey: String, bytes: ByteArray, mime: String): Int {
        val url = objectUrl(objectKey)
        val signed = sign("PUT", url, bytes)
        return rateLimitChecked(
            httpPut.put(
                url,
                bytes,
                mime,
                signed.authorization,
                signed.headers,
            ),
        )
    }

    /**
     * #C51: a 32 MiB pack must never be materialised; the digest streams, the body streams.
     *
     * SigV4 needs `x-amz-content-sha256` over the whole payload, which used to mean
     * `file.readBytes()` — 32 MiB per lane, six lanes, ~192 MiB transient on a phone
     * for a hash that [AwsSigV4.sha256HexOfFile] already computes over a 64 KiB
     * window. The body itself was never the problem: [R2HttpPutFile] streams through
     * `FileInputStream` + `writeCounted`. So the hash goes in precomputed and the
     * bytes never leave the disk.
     */
    override fun uploadFile(objectKey: String, file: File, mime: String): Int {
        val url = objectUrl(objectKey)
        val signed = signHashedPayload("PUT", url, AwsSigV4.sha256HexOfFile(file))
        return rateLimitChecked(
            httpPutFile.put(
                url,
                file,
                mime,
                signed.authorization,
                signed.headers,
            ),
        )
    }

    override fun download(objectKey: String): ByteArray {
        val url = objectUrl(objectKey)
        val signed = sign("GET", url)
        return httpGet.get(url, signed.authorization, signed.headers)
    }

    /**
     * #C51: bytes `[offset, offset + length)` of the object, through an HTTP `Range` GET
     * — the read a v5 page pack is addressed with.
     *
     * The `Range` header is OUTSIDE the SigV4 signature BY DESIGN: [sign] signs only
     * `host;x-amz-content-sha256;x-amz-date`, so adding a request header cannot
     * invalidate the authorization (the same reason `Content-Type` can carry the
     * caller's real mime on a PUT). A server that ignores the header answers 200 and
     * the seam slices the whole body — correct, slower, logged once per process.
     */
    override fun downloadRange(objectKey: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "offset must be >= 0" }
        require(length > 0) { "length must be > 0" }
        val url = objectUrl(objectKey)
        val signed = sign("GET", url)
        return httpGetRange.getRange(url, signed.authorization, signed.headers, offset, length)
    }

    override fun downloadRangeToFile(objectKey: String, offset: Long, length: Int, objectSize: Long, dest: File) {
        require(offset >= 0) { "offset must be >= 0" }
        require(length > 0) { "length must be > 0" }
        val url = objectUrl(objectKey)
        val signed = sign("GET", url)
        httpGetRangeFile.getRangeToFile(url, signed.authorization, signed.headers, offset, length, dest)
    }

    override fun downloadToFile(objectKey: String, dest: File) {
        val url = objectUrl(objectKey)
        val signed = sign("GET", url)
        httpGetFile.getToFile(url, signed.authorization, dest, signed.headers)
    }

    override fun listKeys(): List<String> = listAllPages().map { it.key }

    override fun listKeys(prefix: String): List<String> = listAllPages(prefix).map { it.key }

    /** #C51: the sizes the S3 listing already parses — what the pack presence-by-size resume compares against. */
    override fun listObjects(prefix: String): List<ObjectSummary> =
        listAllPages(prefix).map { ObjectSummary(it.key, it.sizeBytes) }

    override fun listUsage(): R2BucketUsage {
        val objects = listAllPages()
        return R2BucketUsage(
            objectCount = objects.size,
            totalBytes = objects.sumOf { it.sizeBytes },
        )
    }

    private fun listAllPages(prefix: String? = null): List<S3ListedObject> {
        val objects = ArrayList<S3ListedObject>()
        var continuation: String? = null
        var lastToken: String? = null
        repeat(MAX_LIST_PAGES) {
            val url = listUrl(continuation, prefix)
            val signed = sign("GET", url)
            val page = S3ListObjectsV2.parsePage(
                httpGetText.getText(url, signed.authorization, signed.headers),
            )
            objects.addAll(page.objects)
            if (!page.isTruncated) return objects
            val token = page.nextContinuationToken?.takeIf { it.isNotBlank() } ?: return objects
            if (token == lastToken) return objects
            lastToken = token
            continuation = token
        }
        return objects
    }

    private fun listUrl(continuation: String?, prefix: String? = null): String {
        val base = bucketUrl()
        // Query params in canonical (sorted) order: continuation-token, list-type, prefix.
        val prefixParam = prefix?.takeIf { it.isNotBlank() }?.let { p ->
            "&prefix=" + URLEncoder.encode(p, Charsets.UTF_8.name()).replace("+", "%20")
        }.orEmpty()
        if (continuation.isNullOrBlank()) return "$base?list-type=2$prefixParam"
        val encoded = URLEncoder.encode(continuation, Charsets.UTF_8.name())
            .replace("+", "%20")
        return "$base?continuation-token=$encoded&list-type=2$prefixParam"
    }

    override fun delete(objectKey: String): Int {
        val url = objectUrl(objectKey)
        val signed = sign("DELETE", url)
        return rateLimitChecked(httpDelete.delete(url, signed.authorization, signed.headers))
    }

    private fun objectUrl(objectKey: String): String {
        require(objectKey.isNotBlank()) { "objectKey required" }
        require(!objectKey.contains("..")) { "bad objectKey" }
        return "${bucketUrl()}/${objectKey.trimStart('/')}"
    }

    private fun bucketUrl(): String {
        val base = binding.endpoint.trimEnd('/')
        val bucket = binding.bucket.trim('/')
        return "$base/$bucket"
    }

    private fun sign(method: String, url: String, payload: ByteArray = EMPTY_PAYLOAD): AwsSigV4Signed =
        signHashedPayload(method, url, AwsSigV4.sha256Hex(payload))

    /**
     * #C51: [sign] for a caller that already has the payload hash — the only way a
     * multi-megabyte body can be signed without being held in memory. Everything
     * else about the signature is identical, and `x-amz-content-sha256` carries the
     * hash verbatim (never the UNSIGNED-PAYLOAD sentinel: strict S3 implementations
     * such as MinIO verify it, and Cloudflare R2 accepts it just the same).
     */
    private fun signHashedPayload(method: String, url: String, payloadHash: String): AwsSigV4Signed =
        AwsSigV4.sign(
            method = method,
            url = url,
            accessKeyId = binding.accessKeyId,
            secretAccessKey = binding.secretAccessKey,
            region = signingRegion(binding),
            service = "s3",
            amzDate = clockIso(),
            payloadHash = payloadHash,
        )

    companion object {
        /** The PUT/DELETE seams surface only a status to the caller, so no Retry-After can be read here: default to the five-minute window. */
        private fun rateLimitChecked(code: Int): Int {
            if (code == 429) throw CloudRateLimitedException()
            return code
        }

        private const val MAX_LIST_PAGES = 10_000
        private val EMPTY_PAYLOAD = ByteArray(0)
        private val WASABI_REGIONAL_HOST =
            Regex("""^s3\.([a-z0-9-]+)\.wasabisys\.com$""")
        private val WASABI_REGION_ALIASES = mapOf(
            "nl-1" to "eu-central-1",
            "de-1" to "eu-central-2",
            "uk-1" to "eu-west-1",
            "fr-1" to "eu-west-2",
            "uk-2" to "eu-west-3",
            "it-1" to "eu-south-1",
        )

        /**
         * R2 uses `auto`; Wasabi requires the bucket's region. The global
         * Wasabi endpoint is an alias for US East 1, while regional endpoints
         * encode the region in their host name.
         */
        internal fun signingRegion(endpoint: String): String {
            val host = runCatching { URI(endpoint.trim()).host?.lowercase() }.getOrNull()
            return when {
                host == "s3.wasabisys.com" -> "us-east-1"
                else -> host?.let {
                    WASABI_REGIONAL_HOST.matchEntire(it)?.groupValues?.get(1)?.let { region ->
                        WASABI_REGION_ALIASES[region] ?: region
                    }
                } ?: "auto"
            }
        }

        /**
         * An explicitly entered S3 region wins for generic endpoints. `auto`
         * remains a compatibility sentinel that delegates to endpoint mapping,
         * including Wasabi's documented aliases.
         *
         * #C27 audit MEDIUM-1 (2026-09-20): the region is free text typed into
         * an unvalidated field and the bind reports "saved" without a live
         * probe, so it is normalised (trim + lowercase) here — the inferred
         * path is always lowercase, and a verbatim `US-EAST-1` would fail every
         * later request with 400 AuthorizationHeaderMalformed, the exact error
         * #C25 says must never be misread as "slow / rate-limited".
         */
        internal fun signingRegion(binding: BridgeR2Binding): String {
            val explicit = binding.region.trim().lowercase()
            return if (explicit.isNotEmpty() && !explicit.equals("auto", ignoreCase = true)) {
                explicit
            } else {
                signingRegion(binding.endpoint)
            }
        }
        private val AMZ_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

        fun utcAmzDate(): String =
            ZonedDateTime.now(ZoneOffset.UTC).format(AMZ_DATE)

        /**
         * C44: how long a GET may sit with ZERO bytes arriving before it is given up.
         * This is the socket's SO_TIMEOUT (`HttpURLConnection.readTimeout`), which on
         * this stack governs the TLS handshake read, the wait for response headers,
         * and every body read — the measured two-minute page was a handshake read
         * (`ConscryptEngineSocket.doHandshake` ← `SocketTimeoutException: Read timed
         * out`) sitting on the old 120 s value. A per-read window, so a slow but
         * flowing 500 KB page never trips it. The C38 D7 pattern
         * (`EhentaiClient.defaultGetWithStallWatchdog`), applied to the bucket.
         * [TOTAL_REQUEST_DEADLINE_MS] stays as the outer bound.
         */
        const val SILENT_SOCKET_TIMEOUT_MS: Long = 10_000L

        /**
         * #C46 D4: a PUT is given up after this long with NO byte progress on
         * the request body. A slow upstream that keeps draining is never cut
         * off — the owner's rule: 上行字节有进展就继续，静默 10 s 才放弃.
         * #C53: the response after the last body byte is no longer awaited
         * for this same flat window — see [responseWaitAfterBodyMs]. R2 only
         * acknowledges after RECEIVING the last byte, which on a slow uplink
         * is up to backlog/uplink later than the app's `write` returning.
         */
        const val SILENT_PROGRESS_MS: Long = SILENT_SOCKET_TIMEOUT_MS

        /**
         * DELETE's response wait (no body to watch). Was also the PUT response
         * wait until #C46 D4 made the body progress-aware.
         */
        const val BODY_READ_TIMEOUT_MS: Long = 120_000L

        /** The largest default `tcp_wmem[max]` seen on Android devices; on a PLAIN-http socket the buffer is unreachable through HttpURLConnection, so assume it. */
        const val MAX_UNSENT_BACKLOG_BYTES: Long = 4L shl 20

        /** #C53 round 2: what the app asks for; the kernel doubles it (see [SEND_BUFFER_BOUND_BYTES]). */
        const val SEND_BUFFER_REQUEST_BYTES: Long = 128L shl 10
        /** The most the kernel can still hold after `write` returns on a TLS socket the store opened. */
        const val SEND_BUFFER_BOUND_BYTES: Long = SEND_BUFFER_REQUEST_BYTES * 2
        /** The body rule's own floor: one 64 KiB chunk per [SILENT_PROGRESS_MS] (10 s) ⇒ 6.4 KiB/s per lane. */
        const val MIN_DRAIN_BYTES_PER_SEC: Long = (64L shl 10) * 1_000L / SILENT_PROGRESS_MS
        /** Round 1's assumed rate, kept ONLY for plain-http endpoints (no socket hook; the lab MinIO). */
        const val UNBOUNDED_DRAIN_BYTES_PER_SEC: Long = 50L shl 10

        /**
         * #C53 round 2: the ONE shared bounded factory, so the platform's connection pool (keyed on the
         * factory identity) keeps reusing sockets across requests. See [BoundedSendBufferSocketFactory].
         */
        val boundedSendBufferFactory: SSLSocketFactory by lazy {
            BoundedSendBufferSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory())
        }

        /**
         * #C53 round 1, now the PLAIN-HTTP fallback only (see [postBodyWaitMs]): the window after the app wrote the
         * LAST body byte. `write` returns when the KERNEL took the bytes; up to a send-buffer's worth may still be in
         * flight, and the server cannot answer before it arrives. On a 500 KB/s uplink shared by six lanes a 1 MiB
         * backlog needs 12 s — the flat [silentMs] gave every large page up although the network never stopped moving
         * (the #C46 D4 note "R2 acknowledges within ~1 s" was true of a fast link only). Both the backlog
         * ([MAX_UNSENT_BACKLOG_BYTES]) and the rate ([UNBOUNDED_DRAIN_BYTES_PER_SEC]) are ASSUMPTIONS here; on TLS the
         * store bounds the backlog itself and [postBodyWaitMs] uses the known number instead.
         */
        fun responseWaitAfterBodyMs(bodyBytes: Long, silentMs: Long): Long =
            silentMs + minOf(bodyBytes.coerceAtLeast(0L), MAX_UNSENT_BACKLOG_BYTES) * 1_000L / UNBOUNDED_DRAIN_BYTES_PER_SEC

        /** The bytes that may still be unsent after the app's last `write` returns: KNOWN when we bounded the socket, assumed otherwise. */
        fun backlogBoundBytes(bodyBytes: Long, bounded: Boolean): Long =
            minOf(bodyBytes.coerceAtLeast(0L), if (bounded) SEND_BUFFER_BOUND_BYTES else MAX_UNSENT_BACKLOG_BYTES)

        /**
         * #C53 round 2: the wait after the last body byte. When WE bounded the socket: `silent + bound / MIN_DRAIN` — 50 s for ANY body
         * ≥ 256 KiB — shortened to `silent + 4 × bound / measuredRate` when the body was big enough (≥ 4 × bound) for
         * `bodyBytes / bodyWallMs` to be an honest lane rate (a body that fits the buffer measures memcpy speed, not the link).
         * Anything we did not bound — plain http (the MinIO lab), or a platform that refused `SO_SNDBUF` — keeps round 1
         * ([responseWaitAfterBodyMs]), whose backlog and rate are both assumptions.
         *
         * #C53 round-2 audit B4: the measured branch tolerates a **4×** post-body slowdown. A lane that slows by more than that
         * between its last write and the server's answer (cell handover, a sibling lane ramping, bufferbloat draining) is given up
         * and retried by the ladder — the accepted cost. Attempt 2 re-measures on the now-slow link and gets a long wait, and
         * `CloudCollectionStore.PUT_ATTEMPTS` is 3. The floor-based wait is the ceiling either way.
         */
        fun postBodyWaitMs(bodyBytes: Long, bounded: Boolean, silentMs: Long, bodyWallMs: Long = -1L): Long {
            if (!bounded) return responseWaitAfterBodyMs(bodyBytes, silentMs)
            val bound = backlogBoundBytes(bodyBytes, bounded = true)
            val floorWait = silentMs + bound * 1_000L / MIN_DRAIN_BYTES_PER_SEC
            if (bodyWallMs <= 0L || bodyBytes < 4L * SEND_BUFFER_BOUND_BYTES) return floorWait
            val measuredBytesPerSec = (bodyBytes * 1_000L / bodyWallMs).coerceAtLeast(1L)
            val measuredWait = silentMs + 4L * bound * 1_000L / measuredBytesPerSec
            return measuredWait.coerceIn(silentMs, floorWait)
        }

        /**
         * #C53 round-2 audit B3: the ONE place "is this socket's send buffer one WE bounded" is decided.
         *
         * It replaces two predicates that could disagree: the seams used to read `url.startsWith("https://")`
         * while [open] applied the factory on `connection is HttpsURLConnection`. Worse, the scheme test believed
         * the 256 KiB bound for ANY https URL — including one whose caller passed a factory that bounds nothing.
         * Only a [BoundedSendBufferSocketFactory] that has not yet been refused counts; everything else falls back
         * to round 1's longer, assumption-based window, which is the safe direction (too long merely fails slowly;
         * too short fails a healthy upload).
         */
        internal fun isBounded(connection: HttpURLConnection, factory: SSLSocketFactory): Boolean =
            connection is HttpsURLConnection &&
                factory is BoundedSendBufferSocketFactory &&
                !factory.boundRefused

        /** #C53 round 2: the hard ceiling grows with the body at the floor rate; the watchdog, not this, ends a silent request. */
        fun requestDeadlineMs(bodyBytes: Long, baseMs: Long = TOTAL_REQUEST_DEADLINE_MS): Long =
            baseMs + bodyBytes.coerceAtLeast(0L) * 1_000L / MIN_DRAIN_BYTES_PER_SEC

        fun defaultHttpPut(
            totalRequestDeadlineMs: Long = TOTAL_REQUEST_DEADLINE_MS,
            silentProgressMs: Long = SILENT_PROGRESS_MS,
            sslSocketFactory: SSLSocketFactory = boundedSendBufferFactory,
        ): R2HttpPut =
            R2HttpPut { url, body, contentType, authorization, extraHeaders ->
                // #C53: the read timeout (governs the response-header wait after the
                // last body byte) is the derived wait, not the flat silent window —
                // the kernel may still be draining a send backlog toward the peer.
                // Round 2: on TLS that backlog is BOUNDED, so the wait no longer
                // scales with the body. This one is always the FLOOR-based value —
                // a read timeout is fixed at connect time, before any body rate has
                // been measured; the measured shortening lives in the watchdog.
                val bodyBytes = body.size.toLong()
                // Round-2 audit B3: the transport fact is only knowable once the connection exists, so the
                // read timeout is derived AFTER `open()` — `readTimeout` may be changed any time before connect.
                val connection = open(url, "PUT", authorization, extraHeaders, silentProgressMs, sslSocketFactory)
                val bounded = isBounded(connection, sslSocketFactory)
                val responseWaitMs = postBodyWaitMs(bodyBytes, bounded, silentProgressMs)
                applyReadTimeout(connection, responseWaitMs)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", contentType)
                connection.setRequestProperty("Content-Length", body.size.toString())
                val progress = AtomicLong(0)
                try {
                    withRequestDeadline(connection, requestDeadlineMs(bodyBytes, totalRequestDeadlineMs)) {
                        withSilentProgressWatchdog(connection, silentProgressMs, progress, bodyBytes, bounded) {
                            connection.outputStream.use { writeCounted(it, body.inputStream(), progress) }
                            connection.responseCode.also { drainResponse(connection) }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }

        fun defaultHttpPutFile(
            totalRequestDeadlineMs: Long = TOTAL_REQUEST_DEADLINE_MS,
            silentProgressMs: Long = SILENT_PROGRESS_MS,
            sslSocketFactory: SSLSocketFactory = boundedSendBufferFactory,
        ): R2HttpPutFile =
            R2HttpPutFile { url, body, contentType, authorization, extraHeaders ->
                // #C53: see defaultHttpPut — the read timeout is the derived wait.
                val bodyBytes = body.length()
                val connection = open(url, "PUT", authorization, extraHeaders, silentProgressMs, sslSocketFactory)
                val bounded = isBounded(connection, sslSocketFactory)
                val responseWaitMs = postBodyWaitMs(bodyBytes, bounded, silentProgressMs)
                applyReadTimeout(connection, responseWaitMs)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bodyBytes)
                connection.setRequestProperty("Content-Type", contentType)
                connection.setRequestProperty("Content-Length", bodyBytes.toString())
                val progress = AtomicLong(0)
                try {
                    withRequestDeadline(connection, requestDeadlineMs(bodyBytes, totalRequestDeadlineMs)) {
                        withSilentProgressWatchdog(connection, silentProgressMs, progress, bodyBytes, bounded) {
                            java.io.FileInputStream(body).use { input ->
                                connection.outputStream.use { output -> writeCounted(output, input, progress) }
                            }
                            connection.responseCode.also { drainResponse(connection) }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }

        fun defaultHttpGet(
            totalRequestDeadlineMs: Long = TOTAL_REQUEST_DEADLINE_MS,
            silentSocketTimeoutMs: Long = SILENT_SOCKET_TIMEOUT_MS,
        ): R2HttpGet =
            R2HttpGet { url, authorization, extraHeaders ->
                val connection = open(url, "GET", authorization, extraHeaders, silentSocketTimeoutMs)
                try {
                    withRequestDeadline(connection, totalRequestDeadlineMs) {
                        val code = connection.responseCode
                        if (code !in 200..299) {
                            drainResponse(connection)
                            throw CloudHttpException("CLOUD_HTTP_$code")
                        }
                        connection.inputStream.use { it.readBytes() }
                    }
                } finally {
                    connection.disconnect()
                }
            }

        /**
         * #C51: [defaultHttpGet]'s shape plus `Range: bytes=<offset>-<offset+length-1>`,
         * set BEFORE connect. Three answers:
         * - `206` — the range; the body must be exactly [length] bytes, a shorter one is an
         *   [java.io.IOException] so the caller's retry re-fetches and nothing short is memoised;
         * - `200` — the server ignored `Range`: slice the whole body (correct, slower) and log
         *   ONE `CloudRange` line per process;
         * - anything else (`416` included) — drain + [CloudHttpException], the existing error path.
         *
         * Same silent-socket / total-deadline watchdogs as [defaultHttpGet].
         */
        fun defaultHttpGetRange(
            totalRequestDeadlineMs: Long = TOTAL_REQUEST_DEADLINE_MS,
            silentSocketTimeoutMs: Long = SILENT_SOCKET_TIMEOUT_MS,
        ): R2HttpGetRange =
            R2HttpGetRange { url, authorization, extraHeaders, offset, length ->
                val connection = open(url, "GET", authorization, extraHeaders, silentSocketTimeoutMs)
                connection.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
                try {
                    withRequestDeadline(connection, totalRequestDeadlineMs) {
                        when (val code = connection.responseCode) {
                            206 -> {
                                val body = connection.inputStream.use { it.readBytes() }
                                if (body.size != length) {
                                    throw java.io.IOException("short range body: ${body.size} of $length")
                                }
                                // #E15 T4: the right length at the wrong offset is the wrong bytes —
                                // WebDAV's exact-span rule; an absent header stays accepted.
                                val contentRange = connection.getHeaderField("Content-Range")
                                if (!contentRangeMatchesRequest(contentRange, offset, offset + length - 1L)) {
                                    throw java.io.IOException(
                                        "206 Content-Range names another span: \"$contentRange\", " +
                                            "wanted bytes $offset-${offset + length - 1}",
                                    )
                                }
                                body
                            }
                            200 -> {
                                val whole = connection.inputStream.use { it.readBytes() }
                                logRangeIgnoredOnce()
                                if (offset < 0 || offset + length > whole.size) {
                                    throw java.io.IOException(
                                        "range past the end of the object: $offset+$length of ${whole.size}",
                                    )
                                }
                                whole.copyOfRange(offset.toInt(), (offset + length).toInt())
                            }
                            else -> {
                                drainResponse(connection)
                                throw CloudHttpException("CLOUD_HTTP_$code")
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }

        fun defaultHttpGetRangeFile(
            totalRequestDeadlineMs: Long = TOTAL_REQUEST_DEADLINE_MS,
            silentSocketTimeoutMs: Long = SILENT_SOCKET_TIMEOUT_MS,
        ): R2HttpGetRangeFile =
            R2HttpGetRangeFile { url, authorization, extraHeaders, offset, length, dest ->
                val connection = open(url, "GET", authorization, extraHeaders, silentSocketTimeoutMs)
                connection.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
                try {
                    withRequestDeadline(connection, totalRequestDeadlineMs) {
                        when (val code = connection.responseCode) {
                            206 -> {
                                val contentRange = connection.getHeaderField("Content-Range")
                                if (!contentRangeMatchesRequest(contentRange, offset, offset + length - 1L)) {
                                    drainResponse(connection)
                                    throw java.io.IOException(
                                        "206 Content-Range names another span: \"$contentRange\", " +
                                            "wanted bytes $offset-${offset + length - 1}",
                                    )
                                }
                                try {
                                    connection.inputStream.use { input ->
                                        copyRangeBody(input, dest, offset, length, wholeBody = false)
                                    }
                                } catch (error: java.io.IOException) {
                                    java.io.FileOutputStream(dest).use { }
                                    throw error
                                }
                            }
                            200 -> {
                                logRangeIgnoredOnce()
                                try {
                                    connection.inputStream.use { input ->
                                        copyRangeBody(input, dest, offset, length, wholeBody = true)
                                    }
                                } catch (error: java.io.IOException) {
                                    java.io.FileOutputStream(dest).use { }
                                    throw error
                                }
                            }
                            else -> {
                                drainResponse(connection)
                                throw CloudHttpException("CLOUD_HTTP_$code")
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }

        /** #C51: a bucket that ignores `Range` turns every page read into a whole-pack read — say so ONCE. */
        private val rangeIgnoredLogged = AtomicBoolean(false)

        private fun logRangeIgnoredOnce() {
            if (rangeIgnoredLogged.compareAndSet(false, true)) {
                DiagLog.i("CloudRange", "server ignored Range; whole-object fallback")
            }
        }

        fun defaultHttpGetText(
            totalRequestDeadlineMs: Long = TOTAL_REQUEST_DEADLINE_MS,
            silentSocketTimeoutMs: Long = SILENT_SOCKET_TIMEOUT_MS,
        ): R2HttpGetText =
            R2HttpGetText { url, authorization, extraHeaders ->
                defaultHttpGet(totalRequestDeadlineMs, silentSocketTimeoutMs)
                    .get(url, authorization, extraHeaders)
                    .toString(Charsets.UTF_8)
            }

        fun defaultHttpGetFile(
            totalRequestDeadlineMs: Long = TOTAL_REQUEST_DEADLINE_MS,
            silentSocketTimeoutMs: Long = SILENT_SOCKET_TIMEOUT_MS,
        ): R2HttpGetFile =
            R2HttpGetFile { url, authorization, dest, extraHeaders ->
                val connection = open(url, "GET", authorization, extraHeaders, silentSocketTimeoutMs)
                try {
                    withRequestDeadline(connection, totalRequestDeadlineMs) {
                        val code = connection.responseCode
                        if (code !in 200..299) {
                            drainResponse(connection)
                            throw CloudHttpException("CLOUD_HTTP_$code")
                        }
                        connection.inputStream.use { input ->
                            java.io.FileOutputStream(dest).use { output -> input.copyTo(output) }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }

        fun defaultHttpDelete(totalRequestDeadlineMs: Long = TOTAL_REQUEST_DEADLINE_MS): R2HttpDelete =
            R2HttpDelete { url, authorization, extraHeaders ->
                val connection = open(url, "DELETE", authorization, extraHeaders, BODY_READ_TIMEOUT_MS)
                try {
                    withRequestDeadline(connection, totalRequestDeadlineMs) {
                        connection.responseCode.also { drainResponse(connection) }
                    }
                } finally {
                    connection.disconnect()
                }
            }

        /**
         * C37-a: consume whatever body the server sent before `disconnect()`.
         * Android's OkHttp-backed HttpURLConnection pays ~1 s of teardown for
         * every response whose body was left unread (measured on every 404 of
         * the cloud hot path); the S3 error/success bodies here are tiny XML
         * or empty, so draining is the cheap side of the trade.
         */
        private fun drainResponse(connection: HttpURLConnection) {
            val stream = connection.errorStream
                ?: runCatching { connection.inputStream }.getOrNull()
                ?: return
            runCatching {
                stream.use { input ->
                    // Read to EOF so `disconnect()` is a clean close, not a
                    // mid-stream reset (which is what costs ~1 s on Android).
                    // Bounded by the request watchdog above, not a byte cap:
                    // this is the user's own R2/S3 bucket, real error bodies
                    // are KB-sized XML, and a legitimate large GET is bounded
                    // the same way — a byte cap would only re-introduce the
                    // mid-stream reset for a body between the cap and the true
                    // size.
                    val buffer = ByteArray(64 * 1024)
                    while (input.read(buffer) >= 0) { /* discard */ }
                }
            }
        }

        private fun open(
            url: String,
            method: String,
            authorization: String,
            extraHeaders: Map<String, String>,
            readTimeoutMs: Long,
            sslSocketFactory: SSLSocketFactory = boundedSendBufferFactory,
        ): HttpURLConnection {
            // Checked BEFORE the URL is parsed: `R2ObjectStoreSilentSocketTest.
            // theReadTimeoutIsCheckedBeforeTheConnectionIsEvenOpened` pins that an invalid timeout is an
            // IllegalArgumentException, not whatever `URL(...)` happens to throw first.
            require(readTimeoutMs > 0) { "readTimeoutMs must be > 0 (0 means no timeout on HttpURLConnection)" }
            val connection = URL(url).openConnection() as HttpURLConnection
            // #C53 round 2: every HTTPS socket this store opens gets its send buffer bounded
            // (see [BoundedSendBufferSocketFactory]). GET/DELETE gain nothing from it — their
            // request bodies are a header block — but they lose nothing either, and one factory
            // for every connection keeps the platform's socket pool coherent.
            if (connection is HttpsURLConnection) {
                connection.sslSocketFactory = sslSocketFactory
            }
            connection.connectTimeout = 30_000
            applyReadTimeout(connection, readTimeoutMs)
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", authorization)
            extraHeaders.forEach { (name, value) ->
                if (!name.equals("host", ignoreCase = true)) {
                    connection.setRequestProperty(name, value)
                }
            }
            return connection
        }

        /**
         * The PUT seams re-derive their read timeout after [open] returns (they need the connection to know
         * whether its socket is one we bounded — audit B3), so the one `require` + clamp lives here.
         */
        private fun applyReadTimeout(connection: HttpURLConnection, readTimeoutMs: Long) {
            require(readTimeoutMs > 0) { "readTimeoutMs must be > 0 (0 means no timeout on HttpURLConnection)" }
            connection.readTimeout = readTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        /**
         * Total wall-clock ceiling on a single R2 request, from connect
         * through response-read. Generous for a legitimate large single-object
         * transfer on a slow link, but bounds a pathological TLS-handshake
         * hang (observed ~12min on-device) to a few minutes so the request
         * fails fast and the caller's retry pump gets a fresh connection.
         */
        internal const val TOTAL_REQUEST_DEADLINE_MS = 180_000L

        /**
         * Single daemon thread — never blocks app shutdown, never itself does IO.
         * S2 (audit): `removeOnCancelPolicy = true` so a request that completes
         * before its deadline drops its cancelled watchdog task from the queue
         * immediately, instead of letting it linger the full ~180 s (the
         * `Executors.newSingleThreadScheduledExecutor` wrapper hides the setter,
         * so the [ScheduledThreadPoolExecutor] is constructed directly).
         */
        private val watchdog = ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "r2-request-watchdog").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        /**
         * Runs [block] (a request's handshake+transfer+response-read) under a
         * hard total deadline. `HttpURLConnection.connectTimeout`/`readTimeout`
         * do NOT bound a stalled TLS-handshake socket read on this stack, but
         * disconnecting a connection blocked inside one closes its socket,
         * which throws an [java.io.IOException] out of [block] — turning an
         * unbounded hang into a fast, retryable failure. `connection.disconnect()`
         * is idempotent, so it is safe for both the watchdog and the caller's
         * own `finally` block to call it.
         */
        internal fun <T> withRequestDeadline(
            connection: HttpURLConnection,
            deadlineMs: Long,
            block: () -> T,
        ): T {
            val future = watchdog.schedule(
                { runCatching { connection.disconnect() } },
                deadlineMs,
                TimeUnit.MILLISECONDS,
            )
            try {
                return block()
            } finally {
                future.cancel(false)
            }
        }

        /**
         * #C46 D4: runs [block] (the body write + response read) under a
         * PROGRESS watchdog. The window is "[silentMs] with NO byte progress",
         * measured from the LAST byte: a tick every quarter window remembers
         * when the counter last moved and disconnects the connection (the same
         * lever as [withRequestDeadline]) once that is [silentMs] ago — which
         * throws an IOException out of the blocked write. A tick-to-tick
         * comparison at the full window would need TWO silent ticks and gave
         * up at 2× the window on the device (20 s for a 10 s window). Byte
         * progress moves the mark, so a slow-but-moving body is never cut.
         *
         * #C53: once [progress] reaches [bodyBytes] — the body is fully
         * written and the app is only awaiting the response — the window
         * widens to [postBodyWaitMs]: the KERNEL may still be
         * draining an unsent backlog toward the peer, and the server cannot
         * answer before that backlog actually arrives. Before that point, or
         * when [bodyBytes] is left at its default (no body to track), the
         * window stays the flat [silentMs].
         *
         * #C53 round 2: [bounded] says whether the store bounded this socket's
         * send buffer ([BoundedSendBufferSocketFactory]) — see [isBounded], which
         * is the one place that fact is derived. When it holds, the backlog is
         * a KNOWN [SEND_BUFFER_BOUND_BYTES], so the tail is body-size
         * independent AND may be shortened by the rate the body phase actually
         * measured. That wall time is taken from BEFORE the block ran, so it
         * includes connect + handshake, and it is sampled one tick late — both
         * make the computed rate LOWER than the real one, i.e. the wait LONGER,
         * never shorter; the floor-based wait is the ceiling either way. The
         * tail window is latched at the first tick that sees a complete body,
         * so a later tick cannot re-measure a different (longer) sample.
         */
        internal fun <T> withSilentProgressWatchdog(
            connection: HttpURLConnection,
            silentMs: Long,
            progress: AtomicLong,
            bodyBytes: Long = -1L,
            bounded: Boolean = false,
            block: () -> T,
        ): T {
            require(silentMs > 0) { "silentMs must be > 0" }
            var lastSeen = progress.get()
            val startNanos = System.nanoTime()
            var lastMovedNanos = startNanos
            var tailWaitMs = -1L
            // One give-up line per watchdog, not per tick. Written only from the single watchdog thread,
            // like `lastSeen`/`lastMovedNanos` above, so a plain var is enough.
            var loggedGiveUp = false
            val tickMs = (silentMs / 4).coerceAtLeast(1L)
            // scheduleWithFixedDelay, not scheduleAtFixedRate (lint DiscouragedApi):
            // a fixed-RATE task catches up all missed ticks in a burst when an
            // Android process flips from cached to uncached — hundreds of runs in
            // quick succession. This tick only polls a counter and compares
            // nanoTime deltas, so the delay-after-completion cadence is equivalent
            // for silence detection and never bursts.
            val future = watchdog.scheduleWithFixedDelay(
                {
                    val now = progress.get()
                    val nowNanos = System.nanoTime()
                    if (now != lastSeen) {
                        lastSeen = now
                        lastMovedNanos = nowNanos
                    } else {
                        if (bodyBytes >= 0 && now >= bodyBytes && tailWaitMs < 0) {
                            tailWaitMs = postBodyWaitMs(
                                bodyBytes,
                                bounded,
                                silentMs,
                                bodyWallMs = (nowNanos - startNanos) / 1_000_000L,
                            )
                        }
                        val allowedMs = if (tailWaitMs >= 0) tailWaitMs else silentMs
                        if (nowNanos - lastMovedNanos >= allowedMs * 1_000_000L) {
                            if (!loggedGiveUp) {
                                loggedGiveUp = true
                                // #C53 device-leg follow-up: a `SocketException: Socket closed` on an upload IS
                                // this disconnect, and without this line the logcat cannot say which branch fired
                                // (body-phase silence vs the tail wait) or how far the body had got. Numbers and
                                // two words only — never a URL, key or object name. `runCatching` because
                                // `DiagLog.w` also drives `fileSink`, and a throw here would cancel the whole
                                // scheduled tick (`scheduleWithFixedDelay` stops on exception), silently
                                // disarming the watchdog for the rest of the request.
                                runCatching {
                                    DiagLog.w(
                                        "CloudPut",
                                        "watchdog gave up phase=${if (tailWaitMs >= 0) "tail" else "body"} " +
                                            "silentMs=$silentMs allowedMs=$allowedMs progress=$now/$bodyBytes " +
                                            "elapsedMs=${(nowNanos - startNanos) / 1_000_000L} bounded=$bounded",
                                    )
                                }
                            }
                            runCatching { connection.disconnect() }
                        }
                    }
                },
                tickMs,
                tickMs,
                TimeUnit.MILLISECONDS,
            )
            try {
                return block()
            } finally {
                future.cancel(false)
            }
        }

        /** Writes [body] in 64 KB chunks, bumping [progress] after each chunk reaches the stream. */
        internal fun writeCounted(output: OutputStream, body: InputStream, progress: AtomicLong) {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = body.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
                progress.addAndGet(n.toLong())
            }
        }
    }
}
