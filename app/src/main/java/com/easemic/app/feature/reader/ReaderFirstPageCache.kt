package com.easemic.app.feature.reader

import java.io.File

/**
 * #E114 (Tencent `FirstImageCache` + `ComicDataLoader.o` pattern):
 * Caches the exact page source (`file://` path or resolved URL) that the user was viewing
 * when leaving a reader session, keyed by `(sessionKey, ordinal)`.
 *
 * When the user taps "Continue" on the shelf or detail screen (`LoadType.INIT`), the reader
 * checks [resolve] before waiting for blank-page resolution or network round-trips so the
 * resume page renders immediately (`< 5 ms` when backed by a local cache file).
 */
class ReaderFirstPageCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private data class Entry(
        val ordinal: Int,
        val source: String,
    )

    private val lock = Any()
    private val map = object : LinkedHashMap<String, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > maxEntries
    }

    fun record(sessionKey: String, ordinal: Int, source: String) {
        if (sessionKey.isBlank() || ordinal < 0 || source.isBlank()) return
        synchronized(lock) {
            map[sessionKey] = Entry(ordinal = ordinal, source = source)
        }
    }

    fun resolve(sessionKey: String, ordinal: Int): String? {
        if (sessionKey.isBlank() || ordinal < 0) return null
        val entry = synchronized(lock) { map[sessionKey] } ?: return null
        if (entry.ordinal != ordinal) return null
        val src = entry.source
        if (src.startsWith("/")) {
            val f = File(src)
            if (!f.isFile || f.length() <= 0L) return null
        } else if (src.startsWith("file://")) {
            val f = File(src.removePrefix("file://"))
            if (!f.isFile || f.length() <= 0L) return null
        }
        return src
    }

    fun clear() {
        synchronized(lock) {
            map.clear()
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 12
        val shared = ReaderFirstPageCache()
    }
}
