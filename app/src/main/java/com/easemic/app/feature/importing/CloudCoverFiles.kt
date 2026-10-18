package com.easemic.app.feature.importing

import com.easemic.app.sync.CloudCollectionStore
import com.easemic.app.sync.DiagLog
import com.easemic.app.sync.SeriesCoverAbsentException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.concurrent.Semaphore

data class CloudShelfCover(val uri: String, val durablePath: String?)

/** Publishes cloud covers without retaining their encoded bytes in the heap. */
class CloudCoverFiles(private val stagingRoot: File, private val clock: () -> Long = System::currentTimeMillis,
    private val validImage: (File) -> Boolean) {
    private val permits = Semaphore(2)
    private val absent = CloudSeriesCoverAbsence(File(stagingRoot.parentFile, "${stagingRoot.name}-absence"), clock)

    init {
        if (!stagingRoot.isDirectory && !stagingRoot.mkdirs()) {
            DiagLog.w("CloudCover", "stage=staging id=<startup>", IOException("cover staging directory unavailable"))
        }
        stagingRoot.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { cleanPart("<orphan>", it) }
    }

    /**
     * [transform] (null = identity, the staged bytes are kept unchanged) is an
     * optional post-write re-encode: the staged file goes in and its
     * replacement bytes come back; null from the transform fails the
     * publication so undecodable bytes are never persisted. Declared BEFORE
     * [publishGuard] so existing trailing-lambda guard call sites keep binding
     * to [publishGuard]; production passes `transform =` by name.
     */
    fun download(store: CloudCollectionStore, id: String, key: String, dest: File,
        transform: ((File) -> ByteArray?)? = null,
        publishGuard: ((() -> String?) -> String?) = { it() }): String? =
        publish(id, dest, "download", publishGuard, transform = transform) { store.downloadCoverToFile(key, id, it) }

    fun download(store: CloudCollectionStore, id: String, key: String, dest: File, summary: CloudCoverSummary?,
        transform: ((File) -> ByteArray?)? = null,
        publishGuard: ((() -> String?) -> String?) = { it() }): String? {
        if (summary == null) return download(store, id, key, dest, transform, publishGuard)
        if (key.isBlank() && absent.suppressed(id, summary)) return null
        val result = publish(id, dest, "download", publishGuard, onAbsent = { fact ->
            if (fact.seriesCloudId == id) {
                runCatching { absent.record(id, summary, fact.generation) }
                    .onFailure { logFailure("absence", id, it as? Exception ?: IOException("absence record failed")) }
            }
        }, transform = transform) { store.downloadCoverToFile(key, id, it) }
        if (result != null) runCatching { absent.forget(id, summary) }
        return result
    }

    fun copy(id: String, source: File, dest: File): String? =
        publish(id, dest, "copy", { it() }) { copyFile(source, it) }

    /**
     * #E102: publish already-derived cover bytes (e.g. from
     * [encodeCloudCoverDerivative]) through the SAME staged, validated,
     * atomically-moved publication as [copy] — the durable copy is a
     * re-encoded derivative now, but a half-written cover must still never
     * become visible.
     */
    fun copyBytes(id: String, bytes: ByteArray, dest: File): String? =
        publish(id, dest, "copyBytes", { it() }) { staged -> FileOutputStream(staged).use { it.write(bytes) } }

    /**
     * [transform] (null = identity, the staged bytes are kept unchanged) is an
     * optional post-write re-encode: the staged file goes in and its
     * replacement bytes come back; null from the transform fails the
     * publication so undecodable bytes are never persisted.
     */
    private fun publish(id: String, dest: File, writeStage: String, guard: ((() -> String?) -> String?),
        onAbsent: ((SeriesCoverAbsentException) -> Unit)? = null, transform: ((File) -> ByteArray?)? = null,
        write: (File) -> Unit): String? {
        permits.acquire() // java Semaphore.acquire is interruptible.
        var part: File? = null
        var stage = "staging"
        try {
            val staged = File.createTempFile("cover-", ".part", stagingRoot)
            part = staged
            if (Thread.currentThread().isInterrupted) throw InterruptedException("cover publication interrupted")
            stage = writeStage
            write(staged)
            if (transform != null) {
                // #E102: the derivative is computed from the staged file and
                // replaces it before validation — what gets validated and
                // published is exactly what gets persisted.
                stage = "derive"
                val derived = transform(staged) ?: throw IOException("cover derivative unavailable")
                FileOutputStream(staged).use { it.write(derived) }
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedException("cover publication interrupted")
            stage = "empty"
            if (staged.length() == 0L) throw IOException("cover file is empty")
            stage = "validation"
            if (!validImage(staged)) throw IOException("cover image validation failed")
            stage = "publish"
            return guard {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("cover publication interrupted")
                dest.parentFile?.mkdirs()
                Files.move(staged.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                dest.absolutePath
            }
        } catch (missing: SeriesCoverAbsentException) {
            if (onAbsent != null) onAbsent(missing) else logFailure(stage, id, missing)
            return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (failure: Exception) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("cover transfer interrupted").also { it.initCause(failure) }
            }
            logFailure(stage, id, failure)
            return null
        } finally {
            try { part?.let { cleanPart(id, it) } } finally { permits.release() }
        }
    }

    private fun cleanPart(id: String, part: File) {
        try {
            if (part.exists() && !part.delete()) throw IOException("cover temporary file could not be removed")
        } catch (failure: Exception) {
            logFailure("cleanup", id, failure)
        }
    }

    private fun logFailure(stage: String, id: String, failure: Exception) {
        // Transport messages and causes may contain object keys or URLs. Keep the type and stack only.
        val diagnostic = Exception(failure.javaClass.simpleName).also { it.stackTrace = failure.stackTrace }
        DiagLog.w("CloudCover", "stage=$stage id=$id", diagnostic)
    }

    private fun copyFile(source: File, dest: File) {
        require(source.isFile) { "cover source missing" }
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("cover copy interrupted")
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    }
}
