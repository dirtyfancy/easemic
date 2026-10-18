package com.easemic.app.feature.collect

import com.easemic.app.ui.PreferenceMap
import com.easemic.app.feature.download.GalleryIdentityV2
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable prefs-backed mirror of [CloudSyncQueue] rows, so a future
 * WorkManager pump can resume in-flight collect-to-cloud jobs after process
 * death instead of silently losing them.
 *
 * Speed ([CloudSyncQueueItem.bytesPerSecond]) is transient UI telemetry and
 * is intentionally NOT persisted. [CloudSyncQueueItem.readyPages] IS
 * persisted but the durable write is debounced on a fingerprint that
 * excludes it (see `CloudSyncQueue.publishLocked`), so its persisted value
 * can be stale by the time of a process death; that is acceptable because
 * [load] demotes in-flight stages (FETCHING/UPLOADING) back to WAITING on
 * replay and the resuming pump recomputes its own checkpoint from there.
 *
 * C36: that demotion also drops the count. `readyPages` under FETCHING and
 * UPLOADING is written by two different phases — the fetch count and the
 * per-PUT upload count — so a row caught mid-flight most often persists the
 * FETCH count (for an already-downloaded comic `announceLocalFetch` fires
 * `N/N` within the same frame the tap is accepted). A demoted row must not
 * replay a fetch count as upload progress: that is what made a relaunch show
 * `31/31` under Waiting before a single page had been uploaded, and it also
 * suppressed the only honest correction available, because
 * [com.easemic.app.feature.collect.CloudIngestRunner]'s
 * `seedProgressFromUploadCheckpoint` only seeds a count LARGER than what the
 * row already shows. Starting the demoted row at 0 makes that guard
 * meaningful: the durable checkpoint's real uploaded count is what restores
 * the row. WAITING/FAILED rows were never mid-flight and keep their count.
 *
 * [save] commits synchronously (`PreferenceMap.putStringSync`) so a freshly
 * enqueued row is on disk before the call returns and survives an immediate
 * process kill; callers hold the queue lock and run off-main, and the
 * fingerprint dirty-check above keeps these commits rare.
 */
class CloudSyncQueueStore(
    private val prefs: PreferenceMap,
) {
    fun save(rows: List<CloudSyncQueueItem>) {
        val array = JSONArray()
        rows.forEach { item ->
            val obj = JSONObject()
                .put("id", item.id)
                .put("title", item.title)
                .put("stage", item.stage.name)
                .put("readyPages", item.readyPages)
                .put("failedAttempts", item.failedAttempts)
                .put(
                    "chapterIdentity",
                    item.chapterIdentity.ifBlank { GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID },
                )
            item.coverUrl?.let { obj.put("coverUrl", it) }
            item.expectedPages?.let { obj.put("expectedPages", it) }
            item.errorMessage?.let { obj.put("errorMessage", it) }
            item.providerId?.let { obj.put("providerId", it) }
            item.canonicalItemId?.let { obj.put("canonicalItemId", it) }
            if (item.preflightPending) obj.put("preflightPending", true)
            if (item.finishing) obj.put("finishing", true)
            item.failedAtEpochMs?.let { obj.put("failedAtEpochMs", it) }
            item.pausedUntilMs?.let { obj.put("pausedUntilMs", it) }
            array.put(obj)
        }
        prefs.putStringSync(KEY, array.toString())
    }

    /**
     * Replays persisted rows. A row caught mid-flight (FETCHING/UPLOADING) is
     * demoted to WAITING — with its page count reset to 0, see this class's
     * doc — so a fresh pump resumes it cleanly; FAILED rows stay FAILED (with
     * their message and their true partial count) so failures remain honestly
     * visible.
     * Garbage / unparseable top-level JSON replays as an empty queue rather
     * than crashing startup; a single malformed *row* (bad stage name,
     * truncated fields) is dropped individually so it does not take down
     * the rest of an otherwise-healthy backlog.
     */
    fun load(): List<CloudSyncQueueItem> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        return buildList {
            for (i in 0 until array.length()) {
                runCatching {
                    val obj = array.getJSONObject(i)
                    val persistedStage = CloudSyncStage.valueOf(obj.getString("stage"))
                    // S2 (C36 review): exhaustive over CloudSyncStage, no
                    // `else` — a 5th stage added later must be classified
                    // here explicitly or the module fails to compile, instead
                    // of silently falling through as "not in flight" and
                    // replaying an in-flight row's stale count unreset.
                    val demoted = when (persistedStage) {
                        CloudSyncStage.FETCHING, CloudSyncStage.UPLOADING -> true
                        CloudSyncStage.WAITING, CloudSyncStage.FAILED -> false
                    }
                    val stage = if (demoted) CloudSyncStage.WAITING else persistedStage
                    CloudSyncQueueItem(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        coverUrl = obj.optString("coverUrl").takeIf { it.isNotBlank() },
                        stage = stage,
                        readyPages = if (demoted) 0 else obj.optInt("readyPages"),
                        expectedPages = if (obj.has("expectedPages")) obj.optInt("expectedPages") else null,
                        errorMessage = obj.optString("errorMessage").takeIf { it.isNotBlank() },
                        providerId = obj.optString("providerId").takeIf { it.isNotBlank() },
                        canonicalItemId = obj.optString("canonicalItemId").takeIf { it.isNotBlank() },
                        preflightPending = obj.optBoolean("preflightPending", false),
                        // #C46 D2: no pump is driving a tail after a restart.
                        finishing = false,
                        failedAttempts = obj.optInt("failedAttempts"),
                        failedAtEpochMs = if (obj.has("failedAtEpochMs")) obj.optLong("failedAtEpochMs") else null,
                        // C35-c: an absent field (every row written before it
                        // existed) and a window that already elapsed while the
                        // process was dead are the same thing — not paused.
                        // Replaying an expired stamp would render a countdown
                        // running backwards until the first progress tick
                        // cleared it.
                        pausedUntilMs = obj.optLong("pausedUntilMs", 0L)
                            .takeIf { it > System.currentTimeMillis() },
                        chapterIdentity = obj.optString("chapterIdentity")
                            .takeIf { it.isNotBlank() }
                            ?: GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID,
                    )
                }.onSuccess { add(it) }
            }
        }
    }

    companion object {
        const val KEY = "cloud_sync_queue_v1"
    }
}
