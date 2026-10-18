package com.easemic.app.feature.reader

import com.easemic.app.feature.download.ChapterIdentityV2
import com.easemic.app.feature.download.PageTransferResult
import com.easemic.app.storage.CacheBudget
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One app-owned cache. Transfers run outside the budget lock into private parts;
 * publication, LRU hits and reader leases run inside it. No URI memo survives eviction.
 */
class SerializedPageFileCache(private val root: File, private val budget: CacheBudget) {
    private class Flight {
        val mutex = Mutex()
        var callers = 0
    }
    private val flights = ConcurrentHashMap<String, Flight>()

    init {
        budget.registerRoot(root)
        budget.withRootLock(root) {
            // This owner is created once per process, before any of its transfers start.
            root.walkTopDown().filter { it.isFile && (it.name.endsWith(".part") || it.name.endsWith(".tmp")) }
                .forEach { Files.deleteIfExists(it.toPath()) }
        }
    }

    fun reader(chapter: ChapterIdentityV2, generation: Long): Reader = Reader(chapter, generation)

    inner class Reader internal constructor(private val chapter: ChapterIdentityV2, private val generation: Long) : AutoCloseable {
        // Accessed only while holding the budget root lock, including session close.
        private val leases = mutableMapOf<String, AutoCloseable>()
        private var closed = false

        suspend fun cachePageFile(ordinal: Int, transfer: suspend (Path) -> PageTransferResult): String {
            require(ordinal >= 0)
            val key = digest(chapter, ordinal, generation)
            val flight = flights.compute(key) { _, existing -> (existing ?: Flight()).also { it.callers++ } }!!
            try {
                return flight.mutex.withLock {
                    val target = File(root, "$key.page")
                    val hit = budget.withRootLock(root) {
                        check(!closed) { "serialized page reader is closed" }
                        if (target.isFile && target.length() > 0) leasedUri(key, target) else null
                    }
                    if (hit != null) return@withLock hit
                    val part = budget.withRootLock(root) {
                        check(!closed) { "serialized page reader is closed" }
                        root.mkdirs()
                        // Reserve a unique name without exposing a published cache file.
                        Files.createTempFile(root.toPath(), "$key-", ".part").also { Files.delete(it) }
                    }
                    try {
                        val result = transfer(part)
                        currentCoroutineContext().ensureActive()
                        val size = Files.size(part)
                        check(size > 0 && size == result.bytesWritten && (result.expectedBytes == null || size == result.expectedBytes)) {
                            "serialized page transfer size mismatch"
                        }
                        result.expectedSha1?.let { expected ->
                            val hash = MessageDigest.getInstance("SHA-1")
                            Files.newInputStream(part).use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    hash.update(buffer, 0, count)
                                }
                            }
                            check(hash.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)) {
                                "serialized page transfer checksum mismatch"
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        budget.withRootLock(root) {
                            check(!closed) { "serialized page reader is closed" }
                            val oldSize = target.length()
                            Files.move(part, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                            val uri = leasedUri(key, target)
                            budget.recordWrite(root, size - oldSize)
                            uri
                        }
                    } finally {
                        Files.deleteIfExists(part)
                    }
                }
            } finally {
                flights.computeIfPresent(key) { _, existing ->
                    check(existing === flight)
                    existing.callers--
                    existing.takeIf { it.callers > 0 }
                }
            }
        }

        private fun leasedUri(key: String, file: File): String {
            leases.getOrPut(key) { budget.retainFile(file) }
            check(file.setLastModified(System.currentTimeMillis())) { "serialized page LRU touch failed" }
            return file.toURI().toString()
        }

        override fun close() = budget.withRootLock(root) {
            if (!closed) {
                closed = true
                leases.values.forEach { it.close() }
                leases.clear()
                budget.evictIfOverBudget()
            }
        }
    }

    private fun digest(chapter: ChapterIdentityV2, ordinal: Int, generation: Long): String {
        val hash = MessageDigest.getInstance("SHA-256")
        // Length-delimited UTF-8 fields cannot collide through separators or punctuation.
        listOf(chapter.series.providerNamespace, chapter.series.canonicalSeriesId, chapter.canonicalChapterId,
            ordinal.toString(), generation.toString()).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
