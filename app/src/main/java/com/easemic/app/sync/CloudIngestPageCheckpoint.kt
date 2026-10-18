package com.easemic.app.sync

import java.io.File
import org.json.JSONObject

/** Durable per-comic ingest work directory under [workRoot]. */
fun cloudIngestWorkDir(workRoot: File, cloudComicId: String): File =
    File(workRoot, cloudComicId)

/** Format-2 retry scope: one durable checkpoint per series chapter. */
fun cloudIngestChapterWorkDir(workRoot: File, seriesCloudId: String, chapterKey: String): File {
    val safe = chapterKey.trim().lowercase()
    require(safe.isNotEmpty() && safe.matches(Regex("[a-z0-9-]+"))) { "bad chapterKey" }
    return File(cloudIngestWorkDir(workRoot, seriesCloudId), safe)
}

fun cloudIngestChapterPagesDir(workRoot: File, seriesCloudId: String, chapterKey: String): File =
    File(cloudIngestChapterWorkDir(workRoot, seriesCloudId, chapterKey), "pages")

fun cloudIngestPagesDir(workRoot: File, cloudComicId: String): File =
    File(cloudIngestWorkDir(workRoot, cloudComicId), "pages")

fun cloudIngestPageFile(pagesDir: File, ordinal: Int): File =
    File(pagesDir, "%04d.bin".format(ordinal))

fun cloudIngestPagePresent(pagesDir: File, ordinal: Int): Boolean {
    val file = cloudIngestPageFile(pagesDir, ordinal)
    return file.isFile && file.length() > 0L
}

/**
 * First missing/empty page ordinal in `[0, expectedPages)`, or [expectedPages]
 * when the durable checkpoint already holds every page.
 */
fun cloudIngestNextFetchIndex(pagesDir: File, expectedPages: Int): Int {
    require(expectedPages >= 0) { "expectedPages must be >= 0" }
    for (ordinal in 0 until expectedPages) {
        if (!cloudIngestPagePresent(pagesDir, ordinal)) {
            return ordinal
        }
    }
    return expectedPages
}

fun cloudIngestCheckpointComplete(pagesDir: File, expectedPages: Int): Boolean =
    expectedPages > 0 && cloudIngestNextFetchIndex(pagesDir, expectedPages) == expectedPages

fun cloudIngestClearWorkDir(workRoot: File, cloudComicId: String) {
    cloudIngestWorkDir(workRoot, cloudComicId).deleteRecursively()
}

/**
 * Sidecar progress for UI and upload resume across mid-job failures.
 * [fetchedCount] is advisory only — durable page files under `pages/` are the
 * source of truth for fetch resume ([cloudIngestNextFetchIndex]).
 * [uploadedCount] drives upload resume (next ordinal to PUT).
 */
data class CloudIngestCheckpoint(
    val fetchedCount: Int = 0,
    val uploadedCount: Int = 0,
)

private const val CHECKPOINT_FILE = "checkpoint.json"

fun cloudIngestCheckpointFile(workDir: File): File = File(workDir, CHECKPOINT_FILE)

fun writeCloudIngestCheckpoint(workDir: File, checkpoint: CloudIngestCheckpoint) {
    workDir.mkdirs()
    val json = JSONObject()
        .put("fetchedCount", checkpoint.fetchedCount.coerceAtLeast(0))
        .put("uploadedCount", checkpoint.uploadedCount.coerceAtLeast(0))
    cloudIngestCheckpointFile(workDir).writeText(json.toString())
}

fun readCloudIngestCheckpoint(workDir: File): CloudIngestCheckpoint? {
    val file = cloudIngestCheckpointFile(workDir)
    if (!file.isFile || file.length() <= 0L) return null
    return try {
        val root = JSONObject(file.readText())
        CloudIngestCheckpoint(
            fetchedCount = root.optInt("fetchedCount", 0).coerceAtLeast(0),
            uploadedCount = root.optInt("uploadedCount", 0).coerceAtLeast(0),
        )
    } catch (_: Exception) {
        null
    }
}
