package com.easemic.app.feature.browse

import com.easemic.app.storage.CacheBudget
import com.easemic.app.sync.CloudCollectionCard
import java.io.File

/**
 * #C1 completion: the ONE writer for `cacheDir/cloud-thumbs` — detail-strip
 * cells (`<id>-<ordinal>.bin`) and the shelf/recycle cover fallbacks
 * (`<id>-cover.bin`, `<id>-recycle-cover.bin`; C5 audit BLOCKING-1: the
 * cover used to be `<id>-0.bin`, the SAME name as the strip's ordinal-0
 * cell, so opening a detail overwrote the full-res cover body with the
 * 320 px thumb and the shelf's "missing cover" fill then skipped the card
 * forever — a blurry cover, latched). The directory is a registered
 * [CacheBudget] root: every write is published by temp file + atomic rename
 * under the budget's root lock and reports its byte delta, so the budget
 * evicts oldest-first across all roots and "Clear cache" empties it. Readers
 * (`coverUrlFromCacheOrNull`, `cloudThumbCachePath`, the strip's cached
 * `previewUrls`) keep reading by path; a missing file is re-fetched on the
 * next look, which is what makes the directory a cache rather than storage.
 */
object CloudThumbCache {
    const val DIR_NAME = "cloud-thumbs"

    fun root(cacheDir: File): File = File(cacheDir, DIR_NAME)

    /** The shelf / Browse cover fallback: the FULL-RES page-0 body, never a strip cell. */
    fun coverFile(root: File, cloudComicId: String): File = File(root, "$cloudComicId-cover.bin")

    /** The recycle bin's cover fallback. */
    fun recycleCoverFile(root: File, cloudComicId: String): File = File(root, "$cloudComicId-recycle-cover.bin")

    /** One detail-strip cell: the ordinal's 320 px thumb. */
    fun cellFile(root: File, cloudComicId: String, ordinal: Int): File = File(root, "$cloudComicId-$ordinal.bin")

    /**
     * A card's cover already on this device, as a `file:` URI: the durable
     * `filesDir/cloud-covers` file when one was persisted, else the cached
     * [coverFile] — both only when they are real, non-empty files. Null means
     * "fetch it". The one rule every per-card cover loop reads before
     * downloading a full-res page-0 object (C5 audit HIGH-1).
     */
    fun coverUrlOnDiskOrNull(root: File, card: CloudCollectionCard): String? {
        val durable = card.coverPath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile && it.length() > 0 }
        if (durable != null) return durable.toURI().toString()
        val cached = coverFile(root, card.cloudComicId).takeIf { it.isFile && it.length() > 0 } ?: return null
        touch(cached)
        return cached.toURI().toString()
    }

    /**
     * Marks a cached file as DISPLAYED. The budget evicts oldest `lastModified`
     * first across every root, and every other root refreshes mtime on a cache
     * hit; readers of this directory read by path, so without this a shelf
     * cover shown on every app open still carried its write-day mtime and was
     * the first thing every sweep deleted (C1 audit HIGH-2). Failure to set the
     * time is ignored — it only costs the file its recency.
     */
    fun touch(file: File) {
        runCatching { if (file.isFile) file.setLastModified(System.currentTimeMillis()) }
    }

    /** [touch] for a `file:` URI handed to the UI; anything else is ignored. */
    fun touchUri(uri: String) {
        if (!uri.startsWith("file:", ignoreCase = true)) return
        runCatching { File(java.net.URI(uri)) }.getOrNull()?.let(::touch)
    }

    /**
     * What the Library shelf's write-once cover memo records after a cover
     * fill: the DURABLE cover (`filesDir/cloud-covers/<id>.jpg`, never
     * evicted) when one was persisted, else the volatile cache file. Recording
     * the cache file while a durable one existed meant one "Clear cache"
     * blanked every tile for the rest of the session (C1 audit HIGH-1).
     */
    fun coverMemoUri(durableCoverPath: String?, cacheFile: File): String =
        durableCoverPath?.takeIf { it.isNotBlank() }?.let { File(it).toURI().toString() }
            ?: cacheFile.toURI().toString()

    /** Cover staging happens outside this evictable root. Only rename and accounting hold the budget lock. */
    fun publishFile(cacheRoot: File, dest: File, budget: CacheBudget?, publish: () -> String?): String? {
        val action = {
            val previous = if (dest.isFile) dest.length() else 0L
            publish()?.also {
                budget?.recordWrite(cacheRoot, dest.length() - previous)
                touch(dest)
            }
        }
        return if (budget == null) action() else budget.withRootLock(cacheRoot) { action() }
    }

    fun write(cacheRoot: File, fileName: String, bytes: ByteArray, budget: CacheBudget?): String {
        require(fileName.isNotBlank() && !fileName.contains('/') && !fileName.contains("..")) { "bad cache file name" }
        val dest = File(cacheRoot, fileName)
        val publish: () -> Long = {
            cacheRoot.mkdirs()
            val previous = if (dest.isFile) dest.length() else 0L
            val tmp = File.createTempFile("cloud-thumb-", ".part", cacheRoot)
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true)
            } finally {
                tmp.delete()
            }
            bytes.size.toLong() - previous
        }
        if (budget == null) {
            publish()
            return dest.toURI().toString()
        }
        return budget.withRootLock(cacheRoot) {
            val delta = publish()
            budget.recordWrite(cacheRoot, delta)
            dest.toURI().toString()
        }
    }
}
