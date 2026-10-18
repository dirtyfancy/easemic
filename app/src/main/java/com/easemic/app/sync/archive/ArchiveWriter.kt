package com.easemic.app.sync.archive

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException

/**
 * #D7: writes a `.pclarchive` — a ZIP ([ArchiveLayout]) streamed straight into [ArchiveCipher], so neither a page
 * nor the whole archive is ever held in memory. Pages are STORED (images are already compressed); the JSON records
 * are deflated. Cancelling between pages closes and deletes the partial file.
 */
object ArchiveWriter {
    /** A comic and its page files, in the same order as [ArchiveComic.pageFiles]. */
    data class ComicSource(val comic: ArchiveComic, val pageFiles: List<File>) {
        init {
            require(comic.pageFiles.size == pageFiles.size) { "page names and files disagree" }
        }
    }

    data class ChapterSource(val chapter: ArchiveChapter, val pageFiles: List<File>) {
        init {
            require(chapter.pageFiles.size == pageFiles.size) { "chapter page names and files disagree" }
        }
    }

    data class SeriesSource(
        val series: ArchiveSeries,
        val coverFile: File? = null,
        val chapters: List<ChapterSource>,
    ) {
        init {
            require(series.chapters == chapters.map { it.chapter }) { "series chapters and sources disagree" }
            require((series.coverFileName == null) == (coverFile == null)) {
                "series cover name and file disagree"
            }
            if (coverFile != null) require(coverFile.isFile) { "series cover file missing" }
        }
    }

    /** Writes to [out]; a partial file is deleted on cancel or failure. */
    fun write(
        out: File,
        passphrase: CharArray,
        state: ArchiveState,
        appVersion: String,
        comics: List<ComicSource>,
        onPage: (comicIndex: Int, bytesWritten: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        nowMs: Long = System.currentTimeMillis(),
        series: List<SeriesSource> = emptyList(),
    ) {
        var ok = false
        try {
            write(FileOutputStream(out), passphrase, state, appVersion, comics, onPage, isCancelled, nowMs, series)
            ok = true
        } finally {
            if (!ok) out.delete()
        }
    }

    /**
     * Writes straight into [output] (closed on return) — e.g. the document the user picked — so a multi-GB export
     * never needs a second copy in the app's cache. On cancel or failure the caller discards the partial document.
     */
    fun write(
        output: OutputStream,
        passphrase: CharArray,
        state: ArchiveState,
        appVersion: String,
        comics: List<ComicSource>,
        onPage: (comicIndex: Int, bytesWritten: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        nowMs: Long = System.currentTimeMillis(),
        series: List<SeriesSource> = emptyList(),
    ) {
        val formatVersion = if (series.isEmpty()) ArchiveManifest.FORMAT_VERSION_V1 else ArchiveManifest.FORMAT_VERSION_V2
        val manifest = ArchiveManifest(
            formatVersion = formatVersion,
            appVersion = appVersion,
            createdAtMs = nowMs,
            comicCount = comics.size,
            pageBytes = comics.sumOf { c -> c.pageFiles.sumOf { it.length() } } +
                series.sumOf { s -> (s.coverFile?.length() ?: 0L) + s.chapters.sumOf { c -> c.pageFiles.sumOf { it.length() } } },
            includesDownloads = comics.isNotEmpty() || series.isNotEmpty(),
            comicIds = comics.map { ArchiveComicId(it.comic.providerNamespace, it.comic.canonicalItemId) },
            seriesCount = series.size,
            seriesIds = series.map { ArchiveSeriesId(it.series.providerId, it.series.providerSeriesId) },
        )
        output.use { raw ->
            ZipOutputStream(ArchiveCipher.sealingStream(passphrase, raw.buffered(1 shl 16))).use { zip ->
                zip.putDeflated(ArchiveLayout.MANIFEST, manifest.encode())
                zip.putDeflated(ArchiveLayout.STATE, state.encode())
                var written = 0L
                comics.forEachIndexed { index, source ->
                    zip.putDeflated(ArchiveLayout.comic(index), source.comic.encode())
                    source.comic.pageFiles.zip(source.pageFiles).forEach { (name, file) ->
                        if (isCancelled()) throw CancellationException("export cancelled")
                        written += zip.putStored(ArchiveLayout.page(index, name), file)
                        onPage(index, written)
                    }
                }
                series.forEachIndexed { seriesIndex, source ->
                    zip.putDeflated(ArchiveLayout.series(seriesIndex), source.series.encode())
                    source.coverFile?.let { cover ->
                        val name = requireNotNull(source.series.coverFileName)
                        zip.putStored(ArchiveLayout.seriesCover(seriesIndex, name), cover)
                    }
                    source.chapters.forEachIndexed { chapterIndex, chapterSource ->
                        zip.putDeflated(ArchiveLayout.chapter(seriesIndex, chapterIndex), chapterSource.chapter.encode())
                        chapterSource.chapter.pageFiles.zip(chapterSource.pageFiles).forEach { (name, file) ->
                            if (isCancelled()) throw CancellationException("export cancelled")
                            written += zip.putStored(ArchiveLayout.chapterPage(seriesIndex, chapterIndex, name), file)
                            onPage(comics.size + seriesIndex, written)
                        }
                    }
                }
            }
        }
    }

    private fun ZipOutputStream.putDeflated(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.putStored(name: String, file: File): Long {
        // A STORED entry needs its size and CRC before its bytes: one pass to checksum, one to copy.
        val crc = CRC32()
        var bytes = 0L
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
                bytes += n
            }
        }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes
            compressedSize = bytes
            this.crc = crc.value
        }
        putNextEntry(entry)
        file.inputStream().use { it.copyTo(this, 1 shl 16) }
        closeEntry()
        return bytes
    }
}
