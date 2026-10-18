package com.easemic.app.feature.download

import com.easemic.app.importing.isSupportedImageFilename
import com.easemic.app.importing.opds.OpdsCatalogClient
import com.easemic.app.importing.opds.OpdsHttpGet
import com.easemic.app.importing.opds.OpdsFileTransferResponse
import com.easemic.app.importing.opds.OpdsHttpFileGet
import com.easemic.app.importing.opds.OpdsHttpRequest
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking

/**
 * OPDS acquisition → ordered disk-backed page files for
 * [OpdsDownloadPageAdapter]. Archives are private to the acquisition
 * generation; callers never receive a page-sized byte-list API.
 */
object OpdsAcquisitionPageBytes {
    fun load(
        acquisitionUrl: String,
        httpGet: OpdsHttpGet? = null,
        authorizationHeader: String? = null,
        workDir: File,
        httpTransfer: OpdsHttpFileGet = OpdsHttpFileGet { request, destination, firstByteMs, floorWindowMs, floorBytes ->
            OpdsCatalogClient.defaultGetToFile(request, destination, firstByteMs, floorWindowMs, floorBytes)
        },
        firstByteMs: Long = 30_000L,
        floorWindowMs: Long = com.easemic.app.importing.ehentai.StallWatchdog.DEFAULT_WINDOW_MS,
        floorBytes: Long = com.easemic.app.importing.ehentai.StallWatchdog.DEFAULT_MIN_BYTES_PER_WINDOW,
    ): List<Path> {
        val transfer = httpGet?.let { getter ->
            OpdsHttpFileGet { request, destination, _, _, _ ->
                val response = getter.get(request)
                Files.createDirectories(destination.parent)
                Files.write(
                    destination,
                    response.body,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                OpdsFileTransferResponse(
                    code = response.code,
                    bytesWritten = response.body.size.toLong(),
                    expectedBytes = response.body.size.toLong(),
                    bodySample = response.body.copyOf(minOf(response.body.size, FetchTransportException.BODY_SAMPLE_LIMIT)),
                )
            }
        } ?: httpTransfer
        return loadToFiles(
            acquisitionUrl = acquisitionUrl,
            httpTransfer = transfer,
            authorizationHeader = authorizationHeader,
            workDir = workDir,
            firstByteMs = firstByteMs,
            floorWindowMs = floorWindowMs,
            floorBytes = floorBytes,
        )
    }

    /**
     * Streaming production acquisition. The archive is downloaded to a
     * private `.part`, each image entry is copied to its own `.part`, and only
     * a closed entry is atomically renamed to its ordinal. The returned paths
     * remain valid until the adapter invalidates the acquisition generation.
     */
    fun loadToFiles(
        acquisitionUrl: String,
        httpTransfer: OpdsHttpFileGet = OpdsHttpFileGet { request, destination, firstByteMs, floorWindowMs, floorBytes ->
            OpdsCatalogClient.defaultGetToFile(request, destination, firstByteMs, floorWindowMs, floorBytes)
        },
        authorizationHeader: String? = null,
        workDir: File,
        firstByteMs: Long = 30_000L,
        floorWindowMs: Long = com.easemic.app.importing.ehentai.StallWatchdog.DEFAULT_WINDOW_MS,
        floorBytes: Long = com.easemic.app.importing.ehentai.StallWatchdog.DEFAULT_MIN_BYTES_PER_WINDOW,
    ): List<Path> = runBlocking {
        loadToFilesSuspending(
            acquisitionUrl = acquisitionUrl,
            httpTransfer = httpTransfer,
            authorizationHeader = authorizationHeader,
            workDir = workDir,
            firstByteMs = firstByteMs,
            floorWindowMs = floorWindowMs,
            floorBytes = floorBytes,
        )
    }

    /**
     * Same extraction as [loadToFiles], but the default acquisition GET is the
     * cancellable transfer. A non-null [httpTransfer] stays synchronous for tests.
     */
    suspend fun loadToFilesSuspending(
        acquisitionUrl: String,
        httpTransfer: OpdsHttpFileGet? = null,
        authorizationHeader: String? = null,
        workDir: File,
        firstByteMs: Long = 30_000L,
        floorWindowMs: Long = com.easemic.app.importing.ehentai.StallWatchdog.DEFAULT_WINDOW_MS,
        floorBytes: Long = com.easemic.app.importing.ehentai.StallWatchdog.DEFAULT_MIN_BYTES_PER_WINDOW,
    ): List<Path> {
        workDir.mkdirs()
        val archivePart = workDir.toPath().resolve("acquisition.bin.part")
        val archive = workDir.toPath().resolve("acquisition.bin")
        runCatching { Files.deleteIfExists(archivePart) }
        runCatching { Files.deleteIfExists(archive) }
        val response = try {
            if (httpTransfer != null) {
                httpTransfer.getToFile(
                    request = OpdsHttpRequest(
                        url = acquisitionUrl,
                        authorizationHeader = authorizationHeader,
                    ),
                    destination = archivePart,
                    firstByteMs = firstByteMs,
                    floorWindowMs = floorWindowMs,
                    floorBytes = floorBytes,
                )
            } else {
                OpdsCatalogClient.transferToFile(
                    request = OpdsHttpRequest(
                        url = acquisitionUrl,
                        authorizationHeader = authorizationHeader,
                    ),
                    destination = archivePart,
                    firstByteMs = firstByteMs,
                    floorWindowMs = floorWindowMs,
                    floorBytes = floorBytes,
                )
            }
        } catch (malformed: java.net.MalformedURLException) {
            runCatching { Files.deleteIfExists(archivePart) }
            throw malformed
        } catch (networkError: java.io.IOException) {
            runCatching { Files.deleteIfExists(archivePart) }
            throw FetchTransportException(
                statusCode = 0,
                resolvedUrl = acquisitionUrl,
                bodySample = ByteArray(0),
                message = "OPDS acquisition connection error: " +
                    OpdsCatalogClient.redactUrl(networkError.message ?: networkError::class.java.simpleName),
                cause = networkError,
            )
        }
        if (response.code !in 200..299) {
            runCatching { Files.deleteIfExists(archivePart) }
            throw FetchTransportException(
                statusCode = response.code,
                resolvedUrl = acquisitionUrl,
                bodySample = response.bodySample,
                headers = response.headers,
                message = "OPDS_HTTP_${response.code}",
            )
        }
        require(response.bytesWritten > 0L && Files.size(archivePart) > 0L) { "OPDS acquisition empty" }
        atomicPublish(archivePart, archive)
        return try {
            if (looksLikeImageFile(acquisitionUrl, archive)) {
                listOf(publishPage(archive, workDir.toPath().resolve("0")))
            } else {
                if (looksLikeMarkupFile(archive)) {
                    throw FetchTransportException(
                        statusCode = response.code,
                        resolvedUrl = acquisitionUrl,
                        bodySample = readPrefix(archive, FetchTransportException.BODY_SAMPLE_LIMIT),
                        message = OPDS_NOT_A_COMIC,
                    )
                }
                extractZipImagePages(archive, workDir.toPath())
            }
        } finally {
            runCatching { Files.deleteIfExists(archive) }
        }
    }

    /** The failure message the loop routes to the OPDS existence check (#C12). */
    const val OPDS_NOT_A_COMIC = "OPDS_NOT_A_COMIC"

    private fun extractZipImagePages(archive: Path, workDir: Path): List<Path> {
        ZipFile(archive.toFile()).use { zip ->
            val names = zip.entries().asSequence()
                .filter { !it.isDirectory && isSupportedImageFilename(it.name) }
                .map { it.name }
                .sortedWith(com.easemic.app.importing.NaturalFilenameComparator)
                .toList()
            require(names.isNotEmpty()) { "OPDS archive has no image pages" }
            return names.mapIndexed { ordinal, name ->
                val part = workDir.resolve("$ordinal.part")
                val ready = workDir.resolve(ordinal.toString())
                runCatching { Files.deleteIfExists(part) }
                zip.getInputStream(zip.getEntry(name)).use { input ->
                    Files.newOutputStream(
                        part,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                    ).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0) output.write(buffer, 0, read)
                        }
                    }
                }
                require(Files.size(part) > 0L) { "OPDS archive entry is empty: $name" }
                atomicPublish(part, ready)
                ready
            }
        }
    }

    private fun looksLikeImageFile(url: String, path: Path): Boolean {
        val name = url.substringBefore('?').lowercase()
        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
            name.endsWith(".webp") || name.endsWith(".gif")
        ) return true
        val head = readPrefix(path, 8)
        return (head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) ||
            (head.size >= 8 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte())
    }

    private fun looksLikeMarkupFile(path: Path): Boolean {
        val head = String(readPrefix(path, 256), Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .lowercase()
        return head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<?xml")
    }

    private fun readPrefix(path: Path, maxBytes: Int): ByteArray =
        Files.newInputStream(path).use { input ->
            val output = ByteArray(maxBytes)
            var total = 0
            while (total < output.size) {
                val read = input.read(output, total, output.size - total)
                if (read < 0) break
                if (read == 0) continue
                total += read
            }
            output.copyOf(total)
        }

    private fun publishPage(source: Path, ready: Path): Path {
        val part = ready.resolveSibling("${ready.fileName}.part")
        atomicPublish(source, part)
        atomicPublish(part, ready)
        return ready
    }

    private fun atomicPublish(part: Path, ready: Path) {
        Files.createDirectories(ready.parent)
        try {
            Files.move(part, ready, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part, ready, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
