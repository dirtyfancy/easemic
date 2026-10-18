package com.easemic.app.sync

import com.easemic.app.ProductIdentity
import com.easemic.app.feature.bridge.DriveTokenSource
import com.easemic.app.net.SourceHttp
import java.io.File
import java.io.IOException
import java.util.UUID
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * #C50 (spec §4.3): the user's Google Drive as an [ObjectStore], under one visible folder
 * (`Easemic`, tagged `appProperties.pclRoot=1`; scope `drive.file` means the app
 * sees only what it created).
 *
 * Drive has ids, not paths, and allows two files with the same name in one folder (measured
 * 2026-09-26), so every key is resolved folder by folder and every write is find-then-update:
 * a create only happens when the name is absent, the newest copy wins a read, and a write
 * deletes any older duplicates it finds. Folder and file ids are remembered per process.
 * `alt=media` serves exact ranges; bodies above [resumableAboveBytes] go through a resumable
 * session whose URL carries its own authority (no bearer on it).
 */
class GoogleDriveObjectStore(
    private val rootFolderId: String,
    private val tokens: DriveTokenSource,
    private val origin: String = ORIGIN,
    private val resumableAboveBytes: Int = RESUMABLE_ABOVE_BYTES,
    private val client: OkHttpClient = sharedClient,
) : ObjectStore {
    /** "dir/" → folder id ("" = root). */
    private val folderIds = ConcurrentHashMap<String, String>().apply { put("", rootFolderId) }
    /** key → file id. */
    private val fileIds = ConcurrentHashMap<String, String>()
    private val folderLock = Any()

    override val supportsEfficientRange: Boolean = true

    override fun upload(objectKey: String, bytes: ByteArray, mime: String): Int =
        write(objectKey, bytes.size.toLong()) { bytes.toRequestBody(mime.toMediaType()) }

    override fun uploadFile(objectKey: String, file: File, mime: String): Int =
        write(objectKey, file.length()) { file.asRequestBody(mime.toMediaType()) }

    override fun download(objectKey: String): ByteArray = media(objectKey, null).use { it.body!!.bytes() }

    override fun downloadRange(objectKey: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "offset must be >= 0" }
        require(length > 0) { "length must be > 0" }
        val last = offset + length - 1
        return media(objectKey, offset..last).use { response ->
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
        media(objectKey, offset..last).use { response ->
            if (response.code != 206) throw CloudHttpException(WebDavObjectStore.RANGE_IGNORED)
            if (!contentRangeMatchesRequest(response.header("Content-Range"), offset, last)) {
                throw IOException("short range body: 0 of $length")
            }
            val stream = response.body?.byteStream() ?: throw IOException("short range body: 0 of $length")
            stream.use { input -> copyRangeBody(input, dest, 0, length, wholeBody = false) }
        }
    }

    override fun downloadToFile(objectKey: String, dest: File) {
        media(objectKey, null).use { r -> r.body!!.byteStream().use { input -> dest.outputStream().use { input.copyTo(it) } } }
    }

    override fun listKeys(): List<String> = listObjects("").map { it.key }

    override fun listKeys(prefix: String): List<String> = listObjects(prefix).map { it.key }

    override fun listObjects(prefix: String): List<ObjectSummary> {
        val out = ArrayList<ObjectSummary>()
        val startDir = prefix.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val startId = folderIdOrNull(startDir, create = false) ?: return out
        walk(startDir, startId, prefix, out)
        return out
    }

    override fun listUsage(): R2BucketUsage {
        val objects = listObjects("")
        return R2BucketUsage(objectCount = objects.size, totalBytes = objects.sumOf { it.sizeBytes.coerceAtLeast(0L) })
    }

    override fun delete(objectKey: String): Int {
        val ids = candidates(objectKey)
        fileIds.remove(objectKey)
        // A key may name a folder (a comic discard deletes `comics/<id>` in one call).
        val folder = if (ids.isEmpty()) folderIdOrNull(objectKey.trimEnd('/') + "/", create = false) else null
        (ids.map { it.first } + listOfNotNull(folder)).forEach { id ->
            api { token -> Request.Builder().url("$origin/drive/v3/files/$id").header("Authorization", "Bearer $token").delete().build() }
                .use { r -> if (r.code !in 200..299 && r.code != 404) throw failure(r) }
        }
        if (folder != null) folderIds.keys.removeIf { it.startsWith(objectKey.trimEnd('/') + "/") }
        return 204
    }

    // ---- write ----

    private fun write(objectKey: String, size: Long, body: () -> RequestBody): Int {
        val (dir, name) = split(objectKey)
        val parent = folderIdOrNull(dir, create = true)!!
        val existing = candidates(objectKey)
        val target = existing.firstOrNull()?.first
        val id = if (size > resumableAboveBytes) resumable(target, name, parent, body()) else multipart(target, name, parent, body())
        fileIds[objectKey] = id
        // Older duplicates (a race with another device) go, so the newest-wins read stays unambiguous.
        existing.drop(1).forEach { (dup, _) ->
            runCatching {
                api { token -> Request.Builder().url("$origin/drive/v3/files/$dup").header("Authorization", "Bearer $token").delete().build() }.close()
            }
        }
        return 200
    }

    private fun multipart(existingId: String?, name: String, parent: String, content: RequestBody): String {
        val boundary = "pcl" + UUID.randomUUID().toString().replace("-", "")
        val meta = if (existingId == null) JSONObject().put("name", name).put("parents", JSONArray().put(parent)) else JSONObject()
        val body = MultipartRelated(boundary, meta.toString(), content)
        val url = if (existingId == null) {
            "$origin/upload/drive/v3/files?uploadType=multipart&fields=id"
        } else {
            "$origin/upload/drive/v3/files/$existingId?uploadType=multipart&fields=id"
        }
        return api { token ->
            Request.Builder().url(url).header("Authorization", "Bearer $token")
                .method(if (existingId == null) "POST" else "PATCH", body).build()
        }.use { r -> JSONObject(okOrThrow(r).body!!.string()).getString("id") }
    }

    private fun resumable(existingId: String?, name: String, parent: String, content: RequestBody): String {
        val meta = if (existingId == null) JSONObject().put("name", name).put("parents", JSONArray().put(parent)) else JSONObject()
        val start = if (existingId == null) {
            "$origin/upload/drive/v3/files?uploadType=resumable&fields=id"
        } else {
            "$origin/upload/drive/v3/files/$existingId?uploadType=resumable&fields=id"
        }
        val session = api { token ->
            Request.Builder().url(start).header("Authorization", "Bearer $token")
                .method(if (existingId == null) "POST" else "PATCH", meta.toString().toRequestBody(JSON)).build()
        }.use { r ->
            okOrThrow(r).header("Location")?.let { r.request.url.resolve(it) } ?: throw CloudHttpException("CLOUD_UPLOAD_SESSION_INVALID")
        }
        // The session URL is its own authority; the bearer never goes with it.
        return execute(Request.Builder().url(session).put(content).build()).use { r ->
            JSONObject(okOrThrow(r).body!!.string()).getString("id")
        }
    }

    // ---- read ----

    private fun media(objectKey: String, range: LongRange?): Response {
        repeat(2) { attempt ->
            val id = if (attempt == 0) fileIds[objectKey] ?: candidates(objectKey).firstOrNull()?.first else candidates(objectKey).firstOrNull()?.first
            if (id == null) throw CloudHttpException("CLOUD_HTTP_404")
            fileIds[objectKey] = id
            val response = api { token ->
                val b = Request.Builder().url("$origin/drive/v3/files/$id?alt=media").header("Authorization", "Bearer $token")
                    .header("Accept-Encoding", "identity")
                if (range != null) b.header("Range", "bytes=${range.first}-${range.last}")
                b.build()
            }
            if (response.code in 200..299) return response
            response.close()
            fileIds.remove(objectKey)
            // A remembered id can be stale (another device replaced the file): look it up again, once.
            if (attempt == 0 && response.code == 404) return@repeat
            throw statusFailure(response.code, emptyMap())
        }
        throw CloudHttpException("CLOUD_HTTP_404")
    }

    /** Every file named like [objectKey]'s last segment in its folder, newest first (id, createdTime). */
    private fun candidates(objectKey: String): List<Pair<String, String>> {
        val (dir, name) = split(objectKey)
        val parent = folderIdOrNull(dir, create = false) ?: return emptyList()
        val q = "'$parent' in parents and name = '${escape(name)}' and mimeType != '$FOLDER' and trashed = false"
        return query(q, "files(id,createdTime)").map { it.getString("id") to it.optString("createdTime") }
            .sortedByDescending { it.second }
    }

    // ---- folders ----

    private fun folderIdOrNull(dir: String, create: Boolean): String? {
        folderIds[dir]?.let { return it }
        if (dir.isEmpty()) return rootFolderId
        val parentDir = dir.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val parentId = folderIdOrNull(parentDir, create) ?: return null
        val name = dir.trimEnd('/').substringAfterLast('/')
        if (!create) {
            val found = findFolder(parentId, name) ?: return null
            folderIds[dir] = found
            return found
        }
        // One lane creates a folder; the others find it (no duplicate folders from six upload lanes).
        synchronized(folderLock) {
            folderIds[dir]?.let { return it }
            val id = findFolder(parentId, name) ?: api { token ->
                val meta = JSONObject().put("name", name).put("mimeType", FOLDER).put("parents", JSONArray().put(parentId))
                Request.Builder().url("$origin/drive/v3/files?fields=id").header("Authorization", "Bearer $token")
                    .post(meta.toString().toRequestBody(JSON)).build()
            }.use { r -> JSONObject(okOrThrow(r).body!!.string()).getString("id") }
            folderIds[dir] = id
            return id
        }
    }

    private fun findFolder(parentId: String, name: String): String? =
        query("'$parentId' in parents and name = '${escape(name)}' and mimeType = '$FOLDER' and trashed = false", "files(id,createdTime)")
            .minByOrNull { it.optString("createdTime") }?.getString("id")

    private fun walk(dir: String, folderId: String, prefix: String, out: MutableList<ObjectSummary>) {
        for (item in query("'$folderId' in parents and trashed = false", "files(id,name,size,mimeType)")) {
            val name = item.getString("name")
            if (item.optString("mimeType") == FOLDER) {
                val child = "$dir$name/"
                folderIds[child] = item.getString("id")
                if (child.startsWith(prefix) || prefix.startsWith(child)) walk(child, item.getString("id"), prefix, out)
            } else {
                val key = dir + name
                if (key.startsWith(prefix)) {
                    fileIds.putIfAbsent(key, item.getString("id"))
                    out += ObjectSummary(key, item.optString("size").toLongOrNull() ?: -1L)
                }
            }
        }
    }

    private fun query(q: String, fields: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var pageToken: String? = null
        var pages = 0
        do {
            if (++pages > MAX_LIST_PAGES) throw CloudHttpException("CLOUD_LIST_UNBOUNDED")
            val url = "$origin/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("fields", "nextPageToken,$fields")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("spaces", "drive")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = api { token -> Request.Builder().url(url).header("Authorization", "Bearer $token").get().build() }
                .use { r -> JSONObject(okOrThrow(r).body!!.string()) }
            val files = json.optJSONArray("files")
            for (i in 0 until (files?.length() ?: 0)) out += files!!.getJSONObject(i)
            pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return out
    }

    // ---- plumbing ----

    private fun api(build: (String) -> Request): Response {
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
        val reason = runCatching {
            JSONObject(it.body!!.string()).optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")
        }.getOrNull()
        when {
            reason == "storageQuotaExceeded" -> CloudHttpException(WebDavObjectStore.QUOTA_FULL)
            reason == "userRateLimitExceeded" || reason == "rateLimitExceeded" -> CloudRateLimitedException()
            else -> statusFailure(it.code, emptyMap())
        }
    }

    private fun statusFailure(code: Int, headers: Map<String, String>): Exception = when (code) {
        429, 503 -> CloudRateLimitedException.fromHeaders(headers)
        else -> CloudHttpException("CLOUD_HTTP_$code")
    }

    private fun split(objectKey: String): Pair<String, String> {
        require(objectKey.isNotBlank()) { "objectKey required" }
        val key = objectKey.trimStart('/')
        require(!key.split('/').any { it == ".." || it.isEmpty() }) { "bad objectKey" }
        val dir = key.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        return dir to key.substringAfterLast('/')
    }

    /** `multipart/related`: a JSON metadata part, then the media part streamed from [content]. */
    private class MultipartRelated(private val boundary: String, private val meta: String, private val content: RequestBody) : RequestBody() {
        override fun contentType() = "multipart/related; boundary=$boundary".toMediaType()

        override fun contentLength(): Long {
            val len = content.contentLength()
            return if (len < 0) -1 else head().size + len + tail().size
        }

        private fun head() = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: ${content.contentType() ?: "application/octet-stream"}\r\n\r\n").toByteArray()

        private fun tail() = "\r\n--$boundary--".toByteArray()

        override fun writeTo(sink: okio.BufferedSink) {
            sink.write(head())
            content.writeTo(sink)
            sink.write(tail())
        }
    }

    companion object {
        const val ORIGIN: String = "https://www.googleapis.com"
        const val FOLDER: String = "application/vnd.google-apps.folder"
        const val RESUMABLE_ABOVE_BYTES: Int = 5 * 1024 * 1024
        private const val MAX_LIST_PAGES = 10_000
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/""")
        private val JSON = "application/json; charset=UTF-8".toMediaType()

        /** Seeded from [SourceHttp] (#E19 Task 7); the settings below are carried over verbatim. */
        internal fun seededBuilder(): OkHttpClient.Builder =
            SourceHttp.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.MINUTES)

        private val sharedClient: OkHttpClient by lazy {
            seededBuilder().build()
        }

        /** Drive query string literal: backslash and single quote are escaped. */
        fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

        /**
         * The library folder: the oldest non-trashed folder tagged `pclRoot=1` this app can see
         * (`drive.file`), else a new one at My Drive root. Blocking.
         */
        fun ensureRoot(tokens: DriveTokenSource, origin: String = ORIGIN, client: OkHttpClient = sharedClient): String {
            fun call(req: (String) -> Request): JSONObject {
                val token = tokens.accessToken()
                return client.newCall(req(token)).execute().use { r ->
                    if (r.code == 401) throw CloudHttpException(DriveTokenSource.REAUTH_REQUIRED)
                    if (r.code !in 200..299) throw CloudHttpException("CLOUD_HTTP_${r.code}")
                    JSONObject(r.body!!.string())
                }
            }
            val url = "$origin/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "appProperties has { key='pclRoot' and value='1' } and mimeType = '$FOLDER' and trashed = false")
                .addQueryParameter("fields", "files(id,createdTime)")
                .addQueryParameter("spaces", "drive")
                .build()
            val found = call { t -> Request.Builder().url(url).header("Authorization", "Bearer $t").get().build() }
                .optJSONArray("files")
            if (found != null && found.length() > 0) {
                return (0 until found.length()).map { found.getJSONObject(it) }.minByOrNull { it.optString("createdTime") }!!.getString("id")
            }
            val meta = JSONObject().put("name", ProductIdentity.LIBRARY_FOLDER_NAME).put("mimeType", FOLDER)
                .put("appProperties", JSONObject().put("pclRoot", "1"))
            return call { t ->
                Request.Builder().url("$origin/drive/v3/files?fields=id").header("Authorization", "Bearer $t")
                    .post(meta.toString().toRequestBody(JSON)).build()
            }.getString("id")
        }
    }
}
