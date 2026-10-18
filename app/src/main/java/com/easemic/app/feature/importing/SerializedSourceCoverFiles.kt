package com.easemic.app.feature.importing

import com.easemic.app.feature.download.SeriesIdentityV2
import java.io.File
import com.easemic.app.sync.ImageContentType
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SerializedSourceCoverFiles(
    private val root: File,
    private val maxBytes: Long = MAX_SOURCE_COVER_BYTES,
) {
    private val publishedRoot = File(root, "serialized-covers")
    private val stagingRoot = File(root, "serialized-cover-staging")
    private val epochState = MutableStateFlow(0L)
    // A coroutine Mutex, not a thread-owned lock: ensure holds it across the suspending fetch, which can resume on
    // another thread. A ReentrantLock released there threw, stayed held, and parked every later ensure (2026-10-08).
    private val identityLocks = ConcurrentHashMap<SeriesIdentityV2, Mutex>()

    val epoch: StateFlow<Long> = epochState.asStateFlow()

    init {
        publishedRoot.mkdirs()
        stagingRoot.mkdirs()
        stagingRoot.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".part") }
            .forEach { runCatching { it.delete() } }
    }

    fun file(identity: SeriesIdentityV2): File = File(publishedRoot, "${identityHash(identity)}.img")

    fun uriIfValid(identity: SeriesIdentityV2): String? = file(identity)
        .takeIf { it.isFile && validImage(it) }
        ?.toURI()
        ?.toString()

    suspend fun ensure(
        identity: SeriesIdentityV2,
        locator: String?,
        fetch: suspend (String, Path, Long) -> Long,
    ): String? {
        identityLocks.computeIfAbsent(identity) { Mutex() }.withLock {
            if (locator.isNullOrBlank()) return null
            val stage = try {
                if (!stagingRoot.isDirectory && !stagingRoot.mkdirs()) return null
                File.createTempFile("serialized-cover-", ".part", stagingRoot)
            } catch (_: Exception) {
                return null
            }
            try {
                val fetchedBytes = fetch(locator, stage.toPath(), maxBytes)
                if (fetchedBytes <= 0L || fetchedBytes > maxBytes) return null
                if (!stage.isFile || stage.length() != fetchedBytes || stage.length() <= 0L || stage.length() > maxBytes) return null
                if (!validImage(stage)) return null

                val destination = file(identity)
                if (!destination.parentFile.isDirectory && !destination.parentFile.mkdirs()) return null
                Files.move(
                    stage.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                epochState.update { it + 1L }
                return destination.toURI().toString()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { if (stage.exists()) stage.delete() }
            }
        }
    }

    suspend fun remove(identity: SeriesIdentityV2) {
        identityLocks.computeIfAbsent(identity) { Mutex() }.withLock {
            try {
                if (Files.deleteIfExists(file(identity).toPath())) epochState.update { it + 1L }
            } catch (_: Exception) {
                // A failed cleanup must not make the caller's unfollow operation fail.
            }
        }
    }

    private fun validImage(candidate: File): Boolean {
        if (!candidate.isFile || candidate.length() <= 0L || candidate.length() > maxBytes) return false
        val header = ByteArray(12)
        var offset = 0
        return try {
            FileInputStream(candidate).use { input ->
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    if (count < 0) break
                    offset += count
                }
            }
            ImageContentType.sniff(header.copyOf(offset)) != ImageContentType.UNKNOWN
        } catch (_: Exception) {
            false
        }
    }

    private fun identityHash(identity: SeriesIdentityV2): String {
        val input = buildString {
            append(identity.providerNamespace)
            append('\u0000')
            append(identity.canonicalSeriesId)
        }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        const val MAX_SOURCE_COVER_BYTES: Long = 8L * 1024L * 1024L
    }
}
