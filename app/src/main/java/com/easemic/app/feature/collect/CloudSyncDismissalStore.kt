package com.easemic.app.feature.collect

import com.easemic.app.ui.PreferenceMap
import org.json.JSONArray

/**
 * A45 (owner bug backlog): durable record of the cloud sync rows the user has
 * dismissed from "Needs attention".
 *
 * Removing the row from [CloudSyncQueue] is not enough on its own. The comic is
 * still a collected bookmark, and the per-bookmark catch-up sweep re-collects
 * any collected bookmark that has no queue row and is absent from the bucket
 * index (see `catchUpSweepAction`) — so a bare remove reappears on the next
 * launch, which is the exact "the row will not go away" complaint. This store
 * is the flag that sweep consults.
 *
 * Deliberately NOT an uncollect: the bookmark, the local comic and any object
 * already in the bucket all stay. Only the queue/attention row goes away.
 *
 * Serialized rows include their chapter identity in the stored key, so
 * dismissing one chapter never suppresses a sibling. [restore] exists so an
 * explicit new user action clears that chapter's dismissal and is allowed to
 * queue again.
 *
 * Writes commit synchronously (`PreferenceMap.putStringSync`) so a dismissal is
 * on disk before the call returns and cannot be undone by an immediate process
 * kill; call off the main thread.
 */
class CloudSyncDismissalStore(
    private val prefs: PreferenceMap,
) {
    fun all(): Set<String> {
        val raw = prefs.getString(KEY, null) ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        val array = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptySet()
        }
        return buildSet {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    fun isDismissed(id: String, chapterIdentity: String = LEGACY_CHAPTER_IDENTITY): Boolean =
        id.isNotBlank() && dismissalKey(id, chapterIdentity) in all()

    /**
     * A blank id is ignored rather than persisted: an empty-string entry would
     * be a row nothing can ever match or clear.
     */
    fun dismiss(id: String, chapterIdentity: String = LEGACY_CHAPTER_IDENTITY) {
        if (id.isBlank()) return
        val next = all() + dismissalKey(id, chapterIdentity)
        write(next)
    }

    fun restore(id: String, chapterIdentity: String = LEGACY_CHAPTER_IDENTITY) {
        if (id.isBlank()) return
        val current = all()
        val key = dismissalKey(id, chapterIdentity)
        if (key !in current) return
        write(current - key)
    }

    private fun write(ids: Set<String>) {
        val array = JSONArray()
        ids.forEach { array.put(it) }
        prefs.putStringSync(KEY, array.toString())
    }

    companion object {
        const val KEY = "cloud_sync_dismissed_v1"
        private const val LEGACY_CHAPTER_IDENTITY = "legacy:0"

        private fun dismissalKey(id: String, chapterIdentity: String): String =
            if (chapterIdentity.isBlank() || chapterIdentity == LEGACY_CHAPTER_IDENTITY) {
                id
            } else {
                "$id\u0000$chapterIdentity"
            }
    }
}
