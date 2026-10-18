package com.easemic.app.feature.source.zaimanhua

import com.easemic.app.feature.download.DownloadLocator
import com.easemic.app.feature.download.DownloadPageBytesAdapter
import com.easemic.app.feature.download.DownloadPageManifest
import com.easemic.app.feature.download.FetchTransportException
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.InvalidPageTransferException
import com.easemic.app.feature.download.PageTransferResult
import com.easemic.app.feature.download.ResumablePageTransferException
import com.easemic.app.importing.ehentai.StallWatchdog
import com.easemic.app.net.FirstByteDeadline
import com.easemic.app.net.stallReadTimeoutMs
import com.easemic.app.sync.DiagLog
import java.io.BufferedInputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

internal data class ZaimanhuaChapterIds(val seriesId: Long, val chapterId: Long)

internal fun GalleryIdentityV1.zaimanhuaChapterIds(): ZaimanhuaChapterIds? {
    if (providerNamespace != GalleryIdentityV1.ZAIMANHUA) return null
    val parts = canonicalItemId.split('\u0000', limit = 2)
    if (parts.size != 2) return null
    val seriesId = parts[0].toLongOrNull() ?: return null
    val chapterId = parts[1].toLongOrNull() ?: return null
    if (seriesId <= 0L || chapterId <= 0L) return null
    return ZaimanhuaChapterIds(seriesId, chapterId)
}

/**
 * Online pages come from the in-memory page list. A saved chapter is one ZIP
 * next to the page files (`chapter.zip`), resumed from `chapter.zip.part`.
 * Those names are not canonical ordinals, so the ready-page store never
 * admits them as pages.
 */
internal class ZaimanhuaPages(
    private val api: ZaimanhuaApi,
) : DownloadPageBytesAdapter {
    private val lists = ConcurrentHashMap<String, ZaimanhuaDecoder.PageList.Ready>()
    private val listLocks = ConcurrentHashMap<String, Any>()
    private val archiveLocks = ConcurrentHashMap<String, Mutex>()
    /** #E109 fail-fast window per rejected archive path; see [ensureArchive]. */
    private val rejectedArchiveUntil = ConcurrentHashMap<String, Long>()

    override fun supports(identity: GalleryIdentityV1): Boolean = identity.zaimanhuaChapterIds() != null

    override fun usesDiskBackedTransfer(identity: GalleryIdentityV1, locator: DownloadLocator): Boolean =
        supports(identity)

    override fun usesProgressAwareManifest(identity: GalleryIdentityV1, locator: DownloadLocator): Boolean = false

    override fun fetchManifest(identity: GalleryIdentityV1, locator: DownloadLocator): DownloadPageManifest {
        val ready = readyList(identity)
        return DownloadPageManifest(
            expectedPageCount = ready.picnum,
            title = ready.title.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun fetchManifestForWorker(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): DownloadPageManifest {
        val ids = identity.zaimanhuaChapterIds() ?: pageFailure(200, ByteArray(0), "invalid")
        val ready = lists[identity.canonicalItemId]
            ?: decodeList(identity, ids, api.pageListForWorker(ids.seriesId, ids.chapterId))
        return DownloadPageManifest(ready.picnum, title = ready.title.takeIf { it.isNotBlank() })
    }

    override fun fetchPageBytes(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
    ): ByteArray {
        var refreshed = false
        while (true) {
            val ready = readyList(identity)
            val url = ready.urls.getOrNull(ordinal) ?: imageFailure(0, ByteArray(0), emptyMap(), "invalid")
            val response = try {
                api.image(url)
            } catch (error: FetchTransportException) {
                DiagLog.w("Zaimanhua", "image transport")
                throw error
            }
            if (response.code == 403 && isTimeExpire(response)) {
                if (refreshed) {
                    DiagLog.w("Zaimanhua", "image HTTP 403")
                    throw imageFailure(response.code, response.body, response.headers, "HTTP 403")
                }
                refreshed = true
                dropList(identity.canonicalItemId)
                continue
            }
            if (response.code != 200 || response.body.isEmpty()) {
                DiagLog.w("Zaimanhua", "image HTTP ${response.code}")
                throw imageFailure(response.code, response.body, response.headers, "HTTP ${response.code}")
            }
            return response.body
        }
    }

    override suspend fun fetchPageToFile(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
        destination: Path,
        offset: Long,
    ): PageTransferResult {
        val ready = withContext(Dispatchers.IO) { readyList(identity) }
        val ids = checkNotNull(identity.zaimanhuaChapterIds())
        val parent = destination.parent ?: throw InvalidPageTransferException("zaimanhua zip: invalid")
        Files.createDirectories(parent)
        // The page offset belongs to the extracted file. The archive resumes from its own part.
        val archive = parent.resolve(ARCHIVE_NAME)
        val part = parent.resolve(PART_NAME)
        val lock = archiveLocks.computeIfAbsent(archive.toAbsolutePath().normalize().toString()) { Mutex() }
        return lock.withLock {
            withContext(Dispatchers.IO) {
                val downloaded = ensureArchive(ids, archive, part, ready.picnum)
                val written = copyEntry(archive, ordinal, destination)
                PageTransferResult(
                    bytesWritten = written,
                    expectedBytes = written,
                    transferredBytes = downloaded,
                )
            }
        }
    }

    private fun readyList(identity: GalleryIdentityV1): ZaimanhuaDecoder.PageList.Ready {
        val ids = identity.zaimanhuaChapterIds() ?: pageFailure(200, ByteArray(0), "invalid")
        val key = identity.canonicalItemId
        lists[key]?.let { return it }
        synchronized(listLocks.computeIfAbsent(key) { Any() }) {
            lists[key]?.let { return it }
            return decodeList(identity, ids, api.pageList(ids.seriesId, ids.chapterId))
        }
    }

    private fun decodeList(
        identity: GalleryIdentityV1,
        ids: ZaimanhuaChapterIds,
        raw: String,
    ): ZaimanhuaDecoder.PageList.Ready {
        val sample = zaimanhuaBodySample(raw.toByteArray(Charsets.UTF_8))
        return when (val decoded = ZaimanhuaDecoder.pageList(raw, ids.seriesId, ids.chapterId)) {
            is ZaimanhuaDecoder.PageList.Ready -> {
                lists[identity.canonicalItemId] = decoded
                decoded
            }
            ZaimanhuaDecoder.PageList.MissingChapter -> {
                DiagLog.w("Zaimanhua", "page errno 3")
                throw FetchTransportException(200, null, sample, emptyMap(), "zaimanhua page: errno 3")
            }
            ZaimanhuaDecoder.PageList.MembersOnly -> {
                DiagLog.w("Zaimanhua", "page members")
                throw FetchTransportException(200, null, sample, emptyMap(), "zaimanhua page: members")
            }
            ZaimanhuaDecoder.PageList.Invalid -> {
                DiagLog.w("Zaimanhua", "page invalid")
                throw FetchTransportException(200, null, sample, emptyMap(), "zaimanhua page: invalid")
            }
        }
    }

    private fun dropList(key: String) {
        synchronized(listLocks.computeIfAbsent(key) { Any() }) {
            lists.remove(key)
        }
    }

    private suspend fun ensureArchive(
        ids: ZaimanhuaChapterIds,
        archive: Path,
        part: Path,
        picnum: Int,
    ): Long {
        // #E109: a rejected archive is deterministic until the source changes
        // it — every sibling ordinal re-downloading the same bad zip in one
        // pass burned ~3.2 MB each (200+ fetches observed on the owner's
        // phone). Fail fast inside the window; the next paced retry re-fetches.
        val archiveKey = archive.toAbsolutePath().normalize().toString()
        if (System.currentTimeMillis() < (rejectedArchiveUntil[archiveKey] ?: 0L)) {
            throw InvalidPageTransferException("zaimanhua zip: invalid")
        }
        if (Files.isRegularFile(archive)) {
            if (!archiveMatches(archive, picnum)) rejectArchive(archive, part)
            return 0L
        }
        val transferred = transferZip(ids, part)
        if (looksLikeMissingChapter(part)) {
            val sample = zaimanhuaBodySample(readPrefix(part, FetchTransportException.BODY_SAMPLE_LIMIT))
            Files.deleteIfExists(part)
            DiagLog.w("Zaimanhua", "zip errno 3")
            throw FetchTransportException(200, null, sample, emptyMap(), "zaimanhua zip: errno 3")
        }
        if (!archiveMatches(part, picnum)) rejectArchive(archive, part)
        moveAtomically(part, archive)
        return transferred
    }

    private fun rejectArchive(archive: Path, part: Path): Nothing {
        Files.deleteIfExists(archive)
        Files.deleteIfExists(part)
        DiagLog.w("Zaimanhua", "zip invalid")
        rejectedArchiveUntil[archive.toAbsolutePath().normalize().toString()] =
            System.currentTimeMillis() + REJECT_FAST_MS
        throw InvalidPageTransferException("zaimanhua zip: invalid")
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun transferZip(
        ids: ZaimanhuaChapterIds,
        part: Path,
        allowAuthReplay: Boolean = true,
    ): Long = withContext(Dispatchers.IO) {
        val start = if (Files.isRegularFile(part)) Files.size(part) else 0L
        val client = api.httpClient.newBuilder()
            .readTimeout(stallReadTimeoutMs(FIRST_BYTE_MS, StallWatchdog.DEFAULT_WINDOW_MS), TimeUnit.MILLISECONDS)
            .build()
        val baseRequest = Request.Builder()
            .url(api.chapterZipUrl(ids.seriesId, ids.chapterId))
            .header("User-Agent", ZAIMANHUA_USER_AGENT)
            .header("Accept-Encoding", "identity")
            .apply { if (start > 0L) header("Range", "bytes=$start-") }
            .build()
        val request = api.requestWithAuth(baseRequest)
        val call = client.newCall(request)
        val closed = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        fun closeOnce() {
            if (closed.compareAndSet(false, true)) runCatching { call.cancel() }
        }
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) {
            cancelled.set(true)
            closeOnce()
        }
        val sawBodyByte = AtomicBoolean(false)
        val deadline = FirstByteDeadline("ZAIMANHUA_STALL", FIRST_BYTE_MS, sawBodyByte) { closeOnce() }
        try {
            val opened = try {
                call.execute()
            } catch (error: IOException) {
                if (cancelled.get() || !isActive) throw CancellationException("zaimanhua zip: cancelled")
                deadline.stallIfDue(error)?.let { throw it }
                throw error
            }
            var authReplay = false
            val transferred = opened.use { response ->
                val code = response.code
                val headers = zaimanhuaHeaderMap(response.headers)
                val authProbe = if (code == 401 || code == 200) {
                    response.peekBody(64 * 1024L).bytes()
                } else {
                    ByteArray(0)
                }
                if (allowAuthReplay && api.authRejected(request, code, authProbe)) {
                    authReplay = true
                    return@use 0L
                }
                val range = parseContentRange(headers["content-range"])
                if (code == 416 || (code == 206 && (range == null || range.first != start || range.third == null))) {
                    Files.deleteIfExists(part)
                    DiagLog.w("Zaimanhua", "zip HTTP $code")
                    throw InvalidPageTransferException("zaimanhua zip: range")
                }
                if (code != 200 && code != 206) {
                    val sample = zaimanhuaBodySample(
                        response.body?.bytes() ?: ByteArray(0),
                        listOfNotNull(api.authSecret(request)),
                    )
                    DiagLog.w("Zaimanhua", "zip HTTP $code")
                    throw FetchTransportException(
                        code,
                        null,
                        sample,
                        if (api.authSecret(request) == null) headers else emptyMap(),
                        "zaimanhua zip: HTTP $code",
                    )
                }
                val append = code == 206
                val expectedTotal = if (code == 206) range?.third else headers["content-length"]?.toLongOrNull()
                Files.createDirectories(part.parent)
                val mode = if (append) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING
                var transferred = 0L
                val watchdog = StallWatchdog(
                    StallWatchdog.DEFAULT_WINDOW_MS,
                    StallWatchdog.DEFAULT_MIN_BYTES_PER_WINDOW,
                    FIRST_BYTE_MS,
                )
                val input = BufferedInputStream(requireNotNull(response.body).byteStream())
                Files.newOutputStream(
                    part,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    mode,
                ).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = try {
                            input.read(buffer)
                        } catch (error: IOException) {
                            deadline.stallIfDue(error)?.let { throw it }
                            if (error is SocketTimeoutException) {
                                watchdog.onProgress(System.currentTimeMillis(), 0)?.let { throw IOException("ZAIMANHUA_STALL") }
                                continue
                            }
                            throw error
                        }
                        if (read < 0) break
                        if (read == 0) continue
                        sawBodyByte.set(true)
                        output.write(buffer, 0, read)
                        transferred += read
                        watchdog.onProgress(System.currentTimeMillis(), read)?.let { throw IOException("ZAIMANHUA_STALL") }
                    }
                }
                val size = if (Files.isRegularFile(part)) Files.size(part) else 0L
                if (expectedTotal != null && size != expectedTotal) {
                    if (size in 1 until expectedTotal) throw IOException("zaimanhua zip: short")
                    Files.deleteIfExists(part)
                    DiagLog.w("Zaimanhua", "zip invalid")
                    throw InvalidPageTransferException("zaimanhua zip: range")
                }
                if (size <= 0L) {
                    Files.deleteIfExists(part)
                    DiagLog.w("Zaimanhua", "zip HTTP $code")
                    throw FetchTransportException(
                        code,
                        null,
                        ByteArray(0),
                        if (api.authSecret(request) == null) headers else emptyMap(),
                        "zaimanhua zip: HTTP $code",
                    )
                }
                transferred
            }
            if (authReplay) {
                api.acquireGate()
                return@withContext transferZip(ids, part, allowAuthReplay = false)
            }
            transferred
        } catch (error: CancellationException) {
            throw error
        } catch (error: InvalidPageTransferException) {
            throw error
        } catch (error: FetchTransportException) {
            throw error
        } catch (_: IOException) {
            if (cancelled.get() || !isActive) throw CancellationException("zaimanhua zip: cancelled")
            val size = runCatching { if (Files.isRegularFile(part)) Files.size(part) else 0L }.getOrDefault(0L)
            DiagLog.w("Zaimanhua", "zip transport")
            if (size > 0L) throw ResumablePageTransferException("zaimanhua zip: transport")
            throw FetchTransportException(0, null, ByteArray(0), emptyMap(), "zaimanhua zip: transport")
        } finally {
            deadline.close()
            cancellation?.dispose()
            closeOnce()
        }
    }

    private fun copyEntry(archive: Path, ordinal: Int, destination: Path): Long {
        val want = ordinal + 1
        ZipFile(archive.toFile()).use { zip ->
            var match: ZipEntry? = null
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entryNumber(entry.name) == want) {
                    match = entry
                    break
                }
            }
            val entry = match ?: throw InvalidPageTransferException("zaimanhua zip: ordinal")
            var count = 0L
            zip.getInputStream(entry).use { input ->
                Files.newOutputStream(
                    destination,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        count += read
                    }
                }
            }
            return count
        }
    }

    private fun isTimeExpire(response: ZaimanhuaBytes): Boolean {
        val body = response.body.toString(Charsets.UTF_8)
        if (body.contains("time expire", ignoreCase = true)) return true
        val header = response.headers.entries.firstOrNull { it.key.equals("error-info", ignoreCase = true) }?.value
        return header?.contains("time expire", ignoreCase = true) == true
    }

    private fun imageFailure(
        status: Int,
        body: ByteArray,
        headers: Map<String, String>,
        detail: String,
    ): Nothing = throw FetchTransportException(
        statusCode = status,
        resolvedUrl = null,
        bodySample = zaimanhuaBodySample(body),
        headers = headers,
        message = "zaimanhua image: $detail",
    )

    private fun pageFailure(status: Int, body: ByteArray, detail: String): Nothing {
        DiagLog.w("Zaimanhua", "page $detail")
        throw FetchTransportException(status, null, zaimanhuaBodySample(body), emptyMap(), "zaimanhua page: $detail")
    }

    private companion object {
        const val ARCHIVE_NAME = "chapter.zip"
        const val PART_NAME = "chapter.zip.part"
        const val FIRST_BYTE_MS = 30_000L
        const val REJECT_FAST_MS = 60_000L
        val ENTRY_NAME = Regex("""^(\d+)\.(jpg|jpeg|png|webp|gif)$""", RegexOption.IGNORE_CASE)
        val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

        fun archiveMatches(path: Path, picnum: Int): Boolean {
            if (picnum <= 0 || !Files.isRegularFile(path)) return false
            return try {
                ZipFile(path.toFile()).use { zip ->
                    val numbers = ArrayList<Int>()
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) return false
                        val number = entryNumber(entry.name) ?: return false
                        if (number <= 0) return false
                        numbers += number
                    }
                    if (numbers.toSet().size != numbers.size) return false
                    // #E109 (owner 2026-10-12): the page LIST is the page-count
                    // authority — the reader shows exactly 1..picnum, and only
                    // those pages are ever extracted or stored. The zip is a
                    // transport, and a real chapter (再漫画 65130/191265) shipped
                    // 17 packed pages against a 16-page list; rejecting the whole
                    // archive for the surplus left the chapter undownloadable
                    // forever. Surplus numbered entries are ignored; a gap in
                    // 1..picnum, a duplicate, or an unshaped name still means
                    // this archive is not this chapter's page set.
                    val covered = numbers.toHashSet()
                    (1..picnum).all { it in covered }
                }
            } catch (_: IOException) {
                false
            }
        }

        fun entryNumber(name: String): Int? {
            val match = ENTRY_NAME.matchEntire(name) ?: return null
            val digits = match.groupValues[1]
            if (digits.length > 1 && digits[0] == '0') return null
            return digits.toIntOrNull()
        }

        fun looksLikeMissingChapter(path: Path): Boolean {
            val text = readPrefix(path, 1024).toString(Charsets.UTF_8).trimStart()
            if (!text.startsWith("{")) return false
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
            return json.has("errno") && json.optInt("errno") == 3
        }

        fun readPrefix(path: Path, max: Int): ByteArray =
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(max)
                var total = 0
                while (total < buffer.size) {
                    val read = input.read(buffer, total, buffer.size - total)
                    if (read < 0) break
                    total += read
                }
                if (total == buffer.size) buffer else buffer.copyOf(total)
            }

        fun parseContentRange(value: String?): Triple<Long, Long, Long?>? {
            val match = CONTENT_RANGE.matchEntire(value?.trim().orEmpty()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].toLongOrNull()
            if (start < 0L || end < start) return null
            if (total != null && total <= end) return null
            return Triple(start, end, total)
        }

        fun moveAtomically(part: Path, archive: Path) {
            try {
                Files.move(part, archive, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(part, archive, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
