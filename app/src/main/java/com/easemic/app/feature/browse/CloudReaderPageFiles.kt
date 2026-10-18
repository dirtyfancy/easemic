package com.easemic.app.feature.browse

import com.easemic.app.sync.CloudCollectionStore
import com.easemic.app.sync.CloudObjectLayout
import com.easemic.app.sync.CloudPageDiskCache
import java.io.File

/**
 * One loader for a reader session. Large pages go to the file route through [CloudCollectionStore.downloadPageToFile].
 * Small pages and pages with no recorded size keep today's byte read. [CloudPageDiskCache.getOrPut] single-flights
 * one page, and the call blocks until that download finishes.
 */
class CloudReaderPageFiles(
    private val store: CloudCollectionStore,
    private val cache: CloudPageDiskCache,
    private val layout: CloudObjectLayout,
    private val cacheId: String,
) {
    fun load(ordinal: Int, focused: Boolean = false): File {
        cache.file(cacheId, ordinal)?.let { return it }
        val expected = store.pageBytesOrNull(layout, ordinal)
        if (expected == null) return remembered(ordinal, focused)
        return cache.getOrPut(cacheId, ordinal, expected) { part ->
            if (expected >= CloudCollectionStore.PAGE_WINDOW_BYTES) {
                store.downloadPageToFile(layout, ordinal, part, focused)
            } else {
                part.outputStream().use { it.write(pageBytes(ordinal, focused)) }
            }
        }
    }

    private fun remembered(ordinal: Int, focused: Boolean): File =
        cache.file(cacheId, ordinal) ?: cache.put(cacheId, ordinal, pageBytes(ordinal, focused))

    private fun pageBytes(ordinal: Int, focused: Boolean): ByteArray =
        if (focused) store.downloadFocusedPage(layout, ordinal) else store.downloadPage(layout, ordinal)
}
