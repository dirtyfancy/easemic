package com.easemic.app.feature.download

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.runBlocking

/**
 * OPDS page adapter. Production uses [loadPageFiles], which extracts an
 * acquisition into disk-backed page files. The one-argument [loadPages]
 * constructor remains a compatibility seam for readers and existing tests;
 * it is never selected by the production AppGraph.
 */
class OpdsDownloadPageAdapter(
    private val loadPageFiles: (suspend (acquisitionUrl: String, workDir: Path) -> List<Path>)? = null,
    private val workRoot: Path? = null,
    private val loadPages: ((acquisitionUrl: String) -> List<ByteArray>)? = null,
) : DownloadPageBytesAdapter {
    init {
        require((loadPages == null) != (loadPageFiles == null)) {
            "exactly one OPDS acquisition loader is required"
        }
        // Nothing of this process is in flight yet. acquisition-* left under
        // the stable work root are crash leftovers; a live extract is created
        // only after this returns.
        sweepStaleAcquisitions()
    }

    /** One future is one acquisition generation, shared by all page callers. */
    private val cache = ConcurrentHashMap<String, CompletableFuture<CachedPages>>()

    // The acquisition URL contains the provider's access token, so invalidation
    // must find the current URL from the stable identity without logging it.
    private val acquisitionUrlByIdentity = ConcurrentHashMap<GalleryIdentityV1, String>()

    override fun usesDiskBackedTransfer(identity: GalleryIdentityV1, locator: DownloadLocator): Boolean =
        supports(identity) && loadPageFiles != null

    /** OPDS acquisition itself has its own progress-aware body deadline. */
    override fun usesProgressAwareManifest(identity: GalleryIdentityV1, locator: DownloadLocator): Boolean =
        supports(identity) && loadPageFiles != null

    override fun supports(identity: GalleryIdentityV1): Boolean =
        identity.providerNamespace.startsWith("opds:")

    override fun fetchManifest(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): DownloadPageManifest = runBlocking {
        fetchManifestForWorker(identity, locator)
    }

    override suspend fun fetchManifestForWorker(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): DownloadPageManifest {
        require(supports(identity))
        val pages = pagesFor(identity, locator.acquisitionUrl)
        return DownloadPageManifest(
            expectedPageCount = pages.size,
            title = locator.title,
        )
    }

    override fun fetchPageBytes(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
    ): ByteArray {
        require(supports(identity))
        val pages = runBlocking { pagesFor(identity, locator.acquisitionUrl) }
        require(ordinal in pages.indices) {
            "OPDS ordinal $ordinal out of ${pages.size}"
        }
        return pages.bytes?.get(ordinal) ?: Files.readAllBytes(pages.files!![ordinal])
    }

    override suspend fun fetchPageToFile(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
        destination: Path,
        offset: Long,
    ): PageTransferResult {
        require(supports(identity))
        val pages = pagesFor(identity, locator.acquisitionUrl)
        require(ordinal in pages.indices) {
            "OPDS ordinal $ordinal out of ${pages.size}"
        }
        val source = pages.files?.get(ordinal)
        if (source == null) {
            val bytes = pages.bytes!![ordinal]
            Files.createDirectories(destination.parent)
            Files.newOutputStream(
                destination,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { it.write(bytes) }
            return PageTransferResult(bytesWritten = bytes.size.toLong(), transferredBytes = bytes.size.toLong())
        }

        // The archive has already been fully extracted to a private file. A
        // retry may copy the source from a retained local prefix, but never
        // needs to materialize the page as a ByteArray.
        val expected = Files.size(source)
        val existing = if (Files.isRegularFile(destination)) Files.size(destination) else 0L
        val append = offset > 0L && existing == offset && offset < expected
        Files.createDirectories(destination.parent)
        var copied = 0L
        Files.newInputStream(source).use { input ->
            if (append) skipExactly(input, offset)
            Files.newOutputStream(
                destination,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                if (append) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING,
            ).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        copied += read
                    }
                }
            }
        }
        return PageTransferResult(
            bytesWritten = Files.size(destination),
            expectedBytes = expected,
            transferredBytes = copied,
        )
    }

    /** #C16 audit probe: how many acquisitions this adapter still holds. */
    internal fun cachedAcquisitionCount(): Int = cache.size

    override fun invalidatePlan(identity: GalleryIdentityV1) {
        if (!supports(identity)) return
        val acquisitionUrl = acquisitionUrlByIdentity.remove(identity) ?: return
        cache.remove(acquisitionUrl)?.let(::cleanupWhenDone)
    }

    private suspend fun pagesFor(identity: GalleryIdentityV1, acquisitionUrl: String): CachedPages {
        val previous = acquisitionUrlByIdentity.put(identity, acquisitionUrl)
        if (previous != null && previous != acquisitionUrl) {
            cache.remove(previous)?.let(::cleanupWhenDone)
        }

        val candidate = CompletableFuture<CachedPages>()
        val existing = cache.putIfAbsent(acquisitionUrl, candidate)
        val flight = existing ?: candidate
        if (existing == null) {
            try {
                val loaded = load(acquisitionUrl)
                require(loaded.size > 0) { "OPDS acquisition produced no pages" }
                // invalidatePlan and a URL rotation remove this future while
                // extract is still running. cleanupWhenDone only deletes a
                // future that has already completed, so the leader deletes
                // its own tree when the cache no longer owns this future.
                // A failed load already deleted the tree inside load().
                if (cache[acquisitionUrl] !== candidate) cleanup(loaded)
                candidate.complete(loaded)
                if (cache[acquisitionUrl] !== candidate) cleanup(loaded)
            } catch (error: Throwable) {
                cache.remove(acquisitionUrl, candidate)
                candidate.completeExceptionally(error)
            }
        }
        return flight.awaitPages()
    }

    private suspend fun load(acquisitionUrl: String): CachedPages {
        val fileLoader = loadPageFiles
        if (fileLoader != null) {
            val root = workRoot?.also { Files.createDirectories(it) }
            val workDir = if (root == null) {
                Files.createTempDirectory("opds-download-")
            } else {
                Files.createTempDirectory(root, "acquisition-")
            }
            return try {
                val files = fileLoader.invoke(acquisitionUrl, workDir)
                require(files.isNotEmpty()) { "OPDS acquisition produced no pages" }
                require(files.all { Files.isRegularFile(it) && Files.size(it) > 0L }) {
                    "OPDS acquisition produced an invalid page file"
                }
                CachedPages(files = files, workDir = workDir)
            } catch (error: Throwable) {
                deleteTree(workDir)
                throw error
            }
        }
        return CachedPages(bytes = requireNotNull(loadPages).invoke(acquisitionUrl))
    }

    private fun sweepStaleAcquisitions() {
        val root = workRoot ?: return
        if (!Files.isDirectory(root)) return
        Files.list(root).use { stream ->
            stream
                .filter { Files.isDirectory(it) && it.fileName.toString().startsWith("acquisition-") }
                .forEach { deleteTree(it) }
        }
    }

    private fun cleanupWhenDone(future: CompletableFuture<CachedPages>) {
        if (future.isDone && !future.isCompletedExceptionally && !future.isCancelled) {
            future.getNow(null)?.let { cleanup(it) }
        }
    }

    private fun cleanup(cached: CachedPages) {
        cached.workDir?.let(::deleteTree)
    }

    private fun deleteTree(root: Path) {
        runCatching {
            Files.walk(root).use { stream ->
                stream.sorted(compareByDescending<Path> { it.nameCount })
                    .forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun skipExactly(input: java.io.InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            if (input.read() < 0) error("OPDS source ended before resume offset")
            remaining--
        }
    }

    private data class CachedPages(
        val bytes: List<ByteArray>? = null,
        val files: List<Path>? = null,
        val workDir: Path? = null,
    ) {
        val size: Int get() = files?.size ?: bytes?.size ?: 0
        val indices: IntRange get() = 0 until size
    }

    private fun CompletableFuture<CachedPages>.awaitPages(): CachedPages = try {
        get()
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw error
    } catch (error: ExecutionException) {
        val cause = error.cause ?: error
        if (cause is InterruptedException) Thread.currentThread().interrupt()
        throw cause
    }
}
