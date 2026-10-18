package com.easemic.app.feature.importing

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONObject

/** Small durable negative records, separate from the evictable image cache and temporary transfer parts. */
internal class CloudSeriesCoverAbsence(private val root: File, private val clock: () -> Long) {
    fun suppressed(id: String, summary: CloudCoverSummary): Boolean = runCatching {
        val record = JSONObject(file(id, summary).readText())
        val at = record.getLong("at")
        val now = clock()
        record.getString("summary") == summaryDigest(summary) && now >= at && now - at < 86_400_000
    }.getOrDefault(false)

    fun record(id: String, summary: CloudCoverSummary, observedGeneration: Int) {
        root.mkdirs()
        val dest = file(id, summary)
        val part = Files.createTempFile(root.toPath(), "absence-", ".part")
        try {
            Files.write(part, JSONObject().put("summary", summaryDigest(summary)).put("at", clock())
                .put("observedGeneration", observedGeneration).toString().toByteArray(Charsets.UTF_8))
            Files.move(part, dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(part) }
    }

    fun forget(id: String, summary: CloudCoverSummary) { Files.deleteIfExists(file(id, summary).toPath()) }

    private fun file(id: String, summary: CloudCoverSummary) = File(root, digest(listOf(summary.bucketFingerprint, id)) + ".json")
    private fun summaryDigest(summary: CloudCoverSummary) = digest(listOf(summary.generation.toString(), summary.title,
        summary.seriesMetaObjectKey, summary.chapterCount.toString()))
    private fun digest(fields: List<String>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
