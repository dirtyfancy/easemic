package com.easemic.app.sync

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import com.easemic.app.storage.CacheBudget

/**
 * Bounded on-device cache of decrypted cloud page bytes (near-local Read).
 * Evicts oldest files by lastModified when [maxPages] is exceeded.
 *
 * A63 (b): eviction no longer walks the whole tree on every [put] — a 64+
 * file walk on the IO dispatcher was reader jank. The sweep runs at init
 * and then every [EVICTION_SWEEP_INTERVAL] puts, with the page count
 * tracked incrementally: counted once at init, then maintained by
 * put/delete, so the bound is enforced without any per-put tree walk.
 */
class CloudPageDiskCache(
    private val root: File,
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val budget: CacheBudget? = null,
) {
    private val stateLock = Any()

    init {
        require(maxPages > 0) { "maxPages must be > 0" }
        root.mkdirs()
        budget?.registerRoot(root)
    }

    /** Cached page-file count, maintained incrementally (init count + put − delete). */
    private var pageCount: Int = root.walkTopDown()
        .filter { it.isFile && !isInProgressFile(it) }
        .count()

    /** Puts since the last eviction sweep; test-visible via [evictionWalkCount]. */
    private var putsSinceSweep = 0

    /** Test seam: how many full-tree eviction walks have actually run. */
    var evictionWalkCount: Int = 0
        private set

    /** One in-flight [getOrPut] per page. Completed flights are removed so a failure can be retried. */
    private val flightLock = Any()
    private val flights = HashMap<Pair<String, Int>, CompletableFuture<File>>()

    init {
        // Audit fix (A63 adversarial review): enforce the bound at open, not
        // only on the every-8th-put tick — a fresh cache instance per reader
        // open means short sessions (<8 puts) never ticked the sweep, and the
        // disk could grow across sessions unbounded. This piggybacks on the
        // init-time count: the bound is re-asserted on every open, at the
        // cost of one walk only when the bound is actually exceeded.
        evictIfNeeded()
    }

    fun get(cloudComicId: String, ordinal: Int): ByteArray? {
        val read = {
            synchronized(stateLock) {
                val file = fileFor(cloudComicId, ordinal)
                if (!file.isFile) return@synchronized null
                file.setLastModified(System.currentTimeMillis())
                file.readBytes()
            }
        }
        return budget?.withRootLock(root, read) ?: read()
    }

    /**
     * The cached page file, or null. Touches [File.lastModified] under the same lock as [get]
     * so an LRU sweep still sees a page the reader is holding.
     */
    fun file(cloudComicId: String, ordinal: Int): File? {
        val read = {
            synchronized(stateLock) {
                val cached = fileFor(cloudComicId, ordinal)
                if (!cached.isFile) return@synchronized null
                cached.setLastModified(System.currentTimeMillis())
                cached
            }
        }
        return budget?.withRootLock(root, read) ?: read()
    }

    fun put(cloudComicId: String, ordinal: Int, bytes: ByteArray): File =
        put(cloudComicId, ordinal, bytes.size.toLong()) { part ->
            FileOutputStream(part).use { it.write(bytes) }
        }

    /**
     * The producer writes a same-directory `.part` outside the cache and budget locks.
     * Publication is an atomic rename after the byte count matches [expectedBytes]; a short
     * or failed producer leaves the previous file and no scratch.
     */
    fun put(cloudComicId: String, ordinal: Int, expectedBytes: Long, write: (File) -> Unit): File {
        val file = fileFor(cloudComicId, ordinal)
        file.parentFile?.mkdirs()
        val tmp = File.createTempFile("cloud-page-", ".part", file.parentFile)
        try {
            write(tmp)
            if (tmp.length() != expectedBytes) {
                throw IOException("short producer write: ${tmp.length()} of $expectedBytes")
            }
            val publish = {
                synchronized(stateLock) {
                    val isNewFile = !file.isFile
                    // Bytes already on disk under this key are not new bytes:
                    // report the delta so a re-published page does not
                    // double-count and buy the budget a needless sweep.
                    val previous = if (isNewFile) 0L else file.length()
                    if (!tmp.renameTo(file)) {
                        throw IOException("could not publish ${file.name}")
                    }
                    file.setLastModified(System.currentTimeMillis())
                    if (isNewFile) pageCount++
                    budget?.recordWrite(root, expectedBytes - previous)
                    if (++putsSinceSweep >= EVICTION_SWEEP_INTERVAL) {
                        putsSinceSweep = 0
                        evictIfNeeded()
                    }
                }
                file
            }
            return budget?.withRootLock(root, publish) ?: publish()
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Cache hit, or one shared producer for this page. [put] still replaces an existing file;
     * only this entry short-circuits a hit. The producer runs outside the flight lock.
     */
    fun getOrPut(cloudComicId: String, ordinal: Int, expectedBytes: Long, write: (File) -> Unit): File {
        file(cloudComicId, ordinal)?.let { return it }
        val key = cloudComicId to ordinal
        val future: CompletableFuture<File>
        val owner: Boolean
        synchronized(flightLock) {
            file(cloudComicId, ordinal)?.let { return it }
            val existing = flights[key]
            if (existing != null) {
                future = existing
                owner = false
            } else {
                future = CompletableFuture()
                flights[key] = future
                owner = true
            }
        }
        if (!owner) {
            try {
                return future.get()
            } catch (wrapped: ExecutionException) {
                throw wrapped.cause ?: wrapped
            }
        }
        try {
            val published = put(cloudComicId, ordinal, expectedBytes, write)
            synchronized(flightLock) {
                future.complete(published)
                flights.remove(key, future)
            }
            return published
        } catch (failure: Throwable) {
            synchronized(flightLock) {
                future.completeExceptionally(failure)
                flights.remove(key, future)
            }
            throw failure
        }
    }

    /**
     * Run one eviction sweep immediately (resets the puts-since-sweep
     * counter). Public so callers can enforce the bound at a quiet moment —
     * e.g. reader exit — instead of waiting for the every-8th-put tick.
     */
    fun evictNow() {
        val evict = {
            synchronized(stateLock) {
                putsSinceSweep = 0
                evictIfNeeded()
            }
        }
        budget?.withRootLock(root, evict) ?: evict()
    }

    private fun fileFor(cloudComicId: String, ordinal: Int): File =
        File(File(root, cloudComicId), "%04d.bin".format(ordinal))

    private fun evictIfNeeded() {
        if (pageCount <= maxPages) return
        evictionWalkCount++
        val files = root.walkTopDown()
            .filter { it.isFile && !isInProgressFile(it) }
            .toList()
        // Re-derive the count from the walk itself — the incremental counter
        // and disk can drift only in a direction this read corrects.
        pageCount = files.size
        if (files.size <= maxPages) return
        val overflow = files.size - maxPages
        files.sortedBy { it.lastModified() }.take(overflow).forEach {
            if (it.delete()) pageCount--
        }
    }

    private fun isInProgressFile(file: File): Boolean =
        file.name.endsWith(".part", ignoreCase = true) ||
            file.name.endsWith(".tmp", ignoreCase = true)

    companion object {
        const val DEFAULT_MAX_PAGES = 64

        /** A63 (b): run the eviction sweep every N puts, not on every put. */
        const val EVICTION_SWEEP_INTERVAL = 8
    }
}
