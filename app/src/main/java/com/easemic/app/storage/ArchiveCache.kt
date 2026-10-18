package com.easemic.app.storage

import android.os.ParcelFileDescriptor
import com.easemic.app.importing.archive.ZipSafetyPolicy
import com.easemic.app.model.ArchiveFailure
import com.easemic.app.model.SourceId
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class ArchiveRevisionKey(
    val sourceId: SourceId,
    val documentId: String,
    val size: Long?,
    val lastModifiedMillis: Long?,
)

interface SeekableDocument : AutoCloseable {
    fun duplicate(): ParcelFileDescriptor
}

sealed interface ArchiveOpenResult {
    data class Seekable(val archive: SeekableDocument) : ArchiveOpenResult
    data class Cached(val file: File, val key: ArchiveRevisionKey) : ArchiveOpenResult
    class LeasedCached internal constructor(
        val file: File,
        val key: ArchiveRevisionKey,
        private val lease: AutoCloseable,
        private val evictExactEntry: () -> Unit,
    ) : ArchiveOpenResult, AutoCloseable {
        private val closeLock = Any()
        private var ownership = LeaseOwnership.AVAILABLE

        fun evict() {
            evictExactEntry()
        }

        internal fun takeLease(): AutoCloseable =
            synchronized(closeLock) {
                check(ownership == LeaseOwnership.AVAILABLE) {
                    "Archive cache lease has already been transferred or closed."
                }
                ownership = LeaseOwnership.TRANSFERRED
                lease
            }

        override fun close() {
            val shouldClose = synchronized(closeLock) {
                when (ownership) {
                    LeaseOwnership.AVAILABLE -> {
                        ownership = LeaseOwnership.CLOSED
                        true
                    }
                    LeaseOwnership.TRANSFERRED,
                    LeaseOwnership.CLOSED,
                    -> false
                }
            }
            if (shouldClose) {
                lease.close()
            }
        }

        private enum class LeaseOwnership {
            AVAILABLE,
            TRANSFERRED,
            CLOSED,
        }
    }
    data class RecoverableFailure(val reason: ArchiveFailure) : ArchiveOpenResult
}

class ArchiveCache(
    private val cacheDirectory: File,
    private val availableBytes: () -> Long,
    private val policy: ZipSafetyPolicy = ZipSafetyPolicy(),
) {
    private val lock = Any()
    private val cachedEntries = mutableMapOf<ArchiveRevisionKey, CacheEntry>()
    private val inFlightCopies = mutableMapOf<ArchiveRevisionKey, InFlightCopy>()

    fun retain(key: ArchiveRevisionKey): AutoCloseable {
        return synchronized(lock) {
            checkNotNull(cachedEntries[key]?.takeIf { it.file.isFile }) {
                "Cache key is not available."
            }
                .let(::retainEntryLocked)
        }
    }

    fun evict(key: ArchiveRevisionKey) {
        val fileToDelete = synchronized(lock) {
            retireEntryLocked(key)
        }
        fileToDelete?.delete()
    }

    suspend fun cache(
        key: ArchiveRevisionKey,
        displayName: String,
        openStream: suspend () -> InputStream,
    ): ArchiveOpenResult =
        cacheInternal(
            key,
            displayName,
            CopyMode.REUSE,
            retainResult = false,
            openStream = openStream,
        )

    suspend fun refresh(
        key: ArchiveRevisionKey,
        displayName: String,
        openStream: suspend () -> InputStream,
    ): ArchiveOpenResult =
        cacheInternal(
            key,
            displayName,
            CopyMode.FRESH,
            retainResult = false,
            openStream = openStream,
        )

    suspend fun cacheRetained(
        key: ArchiveRevisionKey,
        displayName: String,
        openStream: suspend () -> InputStream,
    ): ArchiveOpenResult =
        cacheInternal(
            key,
            displayName,
            CopyMode.REUSE,
            retainResult = true,
            openStream = openStream,
        )

    suspend fun refreshRetained(
        key: ArchiveRevisionKey,
        displayName: String,
        openStream: suspend () -> InputStream,
    ): ArchiveOpenResult =
        cacheInternal(
            key,
            displayName,
            CopyMode.FRESH,
            retainResult = true,
            openStream = openStream,
        )

    private suspend fun cacheInternal(
        key: ArchiveRevisionKey,
        displayName: String,
        mode: CopyMode,
        retainResult: Boolean,
        openStream: suspend () -> InputStream,
    ): ArchiveOpenResult {
        while (true) {
            var awaitedCopy: InFlightCopy? = null
            var ownedCopy: InFlightCopy? = null
            var retiredFile: File? = null
            synchronized(lock) {
                val inFlight = inFlightCopies[key]
                if (inFlight != null) {
                    awaitedCopy = inFlight
                } else {
                    cachedEntries[key]?.takeIf { it.file.isFile }?.let { entry ->
                        if (mode == CopyMode.REUSE) {
                            return resultForEntryLocked(key, entry, retainResult)
                        }
                    }
                    if (mode == CopyMode.FRESH) {
                        retiredFile = retireEntryLocked(key)
                    }
                    val copy = InFlightCopy(
                        mode = mode,
                        result = CompletableDeferred(),
                    )
                    inFlightCopies[key] = copy
                    ownedCopy = copy
                }
            }
            retiredFile?.delete()

            awaitedCopy?.let { inFlight ->
                val result = inFlight.result.await()
                if (mode == CopyMode.FRESH && inFlight.mode == CopyMode.REUSE) {
                    continue
                }
                if (retainResult && result is ArchiveOpenResult.Cached) {
                    val retained = synchronized(lock) {
                        cachedEntries[key]
                            ?.takeIf { it.file == result.file && it.file.isFile }
                            ?.let { resultForEntryLocked(key, it, retainResult = true) }
                    }
                    if (retained == null) {
                        continue
                    }
                    return retained
                }
                return result
            }

            val inFlight = checkNotNull(ownedCopy)
            return try {
                val publishedResult = copyToPrivateFile(key, displayName, openStream)
                var returnedResult = publishedResult
                var filesToDelete = emptyList<File>()
                if (publishedResult is ArchiveOpenResult.Cached) {
                    filesToDelete = synchronized(lock) {
                        val obsoleteKeys = cachedEntries.keys
                            .filter { cachedKey ->
                                cachedKey.sourceId == key.sourceId &&
                                    cachedKey.documentId == key.documentId
                            }
                        val removableFiles = obsoleteKeys.mapNotNull(::retireEntryLocked)
                        val entry = CacheEntry(publishedResult.file)
                        cachedEntries[key] = entry
                        returnedResult = resultForEntryLocked(key, entry, retainResult)
                        removableFiles
                    }
                }
                filesToDelete.forEach(File::delete)
                inFlight.result.complete(publishedResult)
                returnedResult
            } catch (error: Throwable) {
                inFlight.result.completeExceptionally(error)
                throw error
            } finally {
                synchronized(lock) {
                    inFlightCopies.remove(key, inFlight)
                }
            }
        }
    }

    private fun retireEntryLocked(key: ArchiveRevisionKey): File? {
        val entry = cachedEntries.remove(key) ?: return null
        entry.evictionPending = true
        return entry.file.takeIf { entry.readerCount == 0 }
    }

    private fun resultForEntryLocked(
        key: ArchiveRevisionKey,
        entry: CacheEntry,
        retainResult: Boolean,
    ): ArchiveOpenResult =
        if (retainResult) {
            ArchiveOpenResult.LeasedCached(
                file = entry.file,
                key = key,
                lease = retainEntryLocked(entry),
                evictExactEntry = { evictExact(key, entry) },
            )
        } else {
            ArchiveOpenResult.Cached(entry.file, key)
        }

    private fun retainEntryLocked(entry: CacheEntry): AutoCloseable {
        entry.readerCount += 1
        var closed = false
        return AutoCloseable {
            val fileToDelete = synchronized(lock) {
                if (closed) {
                    null
                } else {
                    closed = true
                    entry.readerCount -= 1
                    check(entry.readerCount >= 0) { "Archive cache lease count underflow." }
                    entry.file.takeIf { entry.readerCount == 0 && entry.evictionPending }
                }
            }
            fileToDelete?.delete()
        }
    }

    private fun evictExact(key: ArchiveRevisionKey, entry: CacheEntry) {
        val fileToDelete = synchronized(lock) {
            if (cachedEntries[key] === entry) {
                cachedEntries.remove(key)
            }
            entry.evictionPending = true
            entry.file.takeIf { entry.readerCount == 0 }
        }
        fileToDelete?.delete()
    }

    private class CacheEntry(
        val file: File,
        var readerCount: Int = 0,
        var evictionPending: Boolean = false,
    )

    private data class InFlightCopy(
        val mode: CopyMode,
        val result: CompletableDeferred<ArchiveOpenResult>,
    )

    private enum class CopyMode {
        REUSE,
        FRESH,
    }

    private suspend fun copyToPrivateFile(
        key: ArchiveRevisionKey,
        displayName: String,
        openStream: suspend () -> InputStream,
    ): ArchiveOpenResult {
        val declaredSize = key.size
        if (
            declaredSize != null &&
            (
                declaredSize > policy.maxArchiveCacheBytes ||
                    declaredSize > availableBytes()
                )
        ) {
            return ArchiveOpenResult.RecoverableFailure(ArchiveFailure.INSUFFICIENT_SPACE)
        }
        @Suppress("UNUSED_VARIABLE")
        val privateNameMustNotUseDisplayName = displayName
        val target = File(cacheDirectory, "${UUID.randomUUID()}.archive")
        val coroutineContext = currentCoroutineContext()
        var observedBytes = 0L
        var completed = false
        try {
            openStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.readCancellable(buffer)
                        if (read < 0) break
                        if (
                            observedBytes > Long.MAX_VALUE - read ||
                            observedBytes + read > policy.maxArchiveCacheBytes
                        ) {
                            return ArchiveOpenResult.RecoverableFailure(
                                ArchiveFailure.INSUFFICIENT_SPACE,
                            )
                        }
                        if (read > availableBytes()) {
                            return ArchiveOpenResult.RecoverableFailure(
                                ArchiveFailure.INSUFFICIENT_SPACE,
                            )
                        }
                        observedBytes += read
                        if (declaredSize != null && observedBytes > declaredSize) {
                            return ArchiveOpenResult.RecoverableFailure(
                                ArchiveFailure.SOURCE_CHANGED,
                            )
                        }
                        output.write(buffer, 0, read)
                        coroutineContext.ensureActive()
                    }
                }
            }
            completed = true
        } finally {
            if (!completed) target.delete()
        }
        if (declaredSize != null && observedBytes != declaredSize) {
            target.delete()
            return ArchiveOpenResult.RecoverableFailure(ArchiveFailure.SOURCE_CHANGED)
        }
        return ArchiveOpenResult.Cached(target, key)
    }

    private suspend fun InputStream.readCancellable(buffer: ByteArray): Int =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                runCatching { close() }
            }
            try {
                val read = read(buffer)
                if (continuation.isActive) {
                    continuation.resume(read)
                }
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }
}
