package com.easemic.app.sync.archive

import java.io.Closeable
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * #D7: reads a `.pclarchive` front to back in one stream — [manifest] and [state] first (a wrong password or a
 * foreign file fails here, before anything is written), then [forEachComic]. Entry names are checked against
 * [ArchiveLayout]; a comic's pages must follow its own record. A cut or altered file fails inside [ArchiveCipher]
 * when the damaged chunk is reached, so a caller that commits a comic only after reading all of its pages never
 * commits a damaged one.
 */
class ArchiveReader private constructor(
    private val plain: InputStream,
    private val zip: ZipInputStream,
) : Closeable {
    data class SeriesChapterEntry(val chapter: ArchiveChapter, val pages: Sequence<Pair<String, InputStream>>)
    data class SeriesEntry(val series: ArchiveSeries, val cover: Pair<String, InputStream>?, val chapters: Sequence<SeriesChapterEntry>)

    lateinit var manifest: ArchiveManifest
        private set
    lateinit var state: ArchiveState
        private set
    private var pending: java.util.zip.ZipEntry? = null

    private fun readHead() {
        manifest = ArchiveManifest.decode(textOf(expect(ArchiveLayout.Entry.Manifest)))
        state = ArchiveState.decode(textOf(expect(ArchiveLayout.Entry.State)))
    }

    private fun expect(kind: ArchiveLayout.Entry): java.util.zip.ZipEntry {
        val entry = zip.nextEntry ?: throw ArchiveFormatException("archive ends early")
        if (ArchiveLayout.parse(entry.name) != kind) throw ArchiveFormatException("archive entries out of order")
        return entry
    }

    private fun textOf(@Suppress("UNUSED_PARAMETER") entry: java.util.zip.ZipEntry): String =
        String(zip.readBytes(), Charsets.UTF_8)

    /**
     * Calls [block] once per comic, in archive order, with its record and a sequence of its pages
     * (name, stream). Each page stream is valid only until the sequence advances; pages not read are skipped.
     */
    suspend fun forEachComic(block: suspend (ArchiveComic, Sequence<Pair<String, InputStream>>) -> Unit) {
        var expectedIndex = 0
        var entry = pending ?: zip.nextEntry
        pending = null
        while (entry != null) {
            val kind = ArchiveLayout.parse(entry.name)
            if (kind is ArchiveLayout.Entry.Series) {
                pending = entry
                break
            }
            if (kind !is ArchiveLayout.Entry.Comic || kind.index != expectedIndex) {
                throw ArchiveFormatException("archive entries out of order")
            }
            val comic = ArchiveComic.decode(String(zip.readBytes(), Charsets.UTF_8))
            if (!comic.pageFiles.all(ArchiveLayout::isPageFile)) throw ArchiveFormatException("bad page name")
            var next: java.util.zip.ZipEntry? = zip.nextEntry
            val pages = sequence {
                for (name in comic.pageFiles) {
                    val current = next ?: throw ArchiveFormatException("comic ends early")
                    val page = ArchiveLayout.parse(current.name)
                    if (page != ArchiveLayout.Entry.Page(expectedIndex, name)) throw ArchiveFormatException("page out of order")
                    yield(name to (object : FilterInputStream(zip) { override fun close() {} } as InputStream))
                    next = zip.nextEntry
                }
            }
            block(comic, pages)
            // Skip whatever pages the caller did not pull.
            while (next != null && ArchiveLayout.parse(next!!.name).let { it is ArchiveLayout.Entry.Page && it.index == expectedIndex }) {
                next = zip.nextEntry
            }
            entry = next
            expectedIndex += 1
        }
        if (expectedIndex != manifest.comicCount) throw ArchiveFormatException("archive ends early")
        if (pending == null) finishAuthentication()
    }

    /**
     * Calls [block] once per format-2 series, in archive order, with its record, optional cover, and chapter sequence.
     * Each chapter exposes a sequence of page streams; a stream is valid only until its sequence advances, and
     * entries the caller does not consume are drained before the next series is delivered.
     */
    suspend fun forEachSeries(block: suspend (SeriesEntry) -> Unit) {
        var expectedIndex = 0
        var entry = pending ?: zip.nextEntry
        pending = null
        while (entry != null) {
            val kind = ArchiveLayout.parse(entry.name)
            if (kind !is ArchiveLayout.Entry.Series || kind.index != expectedIndex) throw ArchiveFormatException("archive entries out of order")
            val series = ArchiveSeries.decode(String(zip.readBytes(), Charsets.UTF_8))
            var latestEntry: java.util.zip.ZipEntry? = zip.nextEntry
            val advance = {
                latestEntry = zip.nextEntry
                latestEntry
            }
            val cover = if (latestEntry != null && ArchiveLayout.parse(latestEntry!!.name) is ArchiveLayout.Entry.SeriesCover) {
                val coverEntry = ArchiveLayout.parse(latestEntry!!.name) as ArchiveLayout.Entry.SeriesCover
                if (coverEntry.index != expectedIndex) throw ArchiveFormatException("cover out of order")
                // Covers are a single small metadata image; buffer just this entry so advancing to the first chapter
                // cannot invalidate the stream handed to the caller.
                val stream = ByteArrayInputStream(zip.readBytes()) as InputStream
                advance()
                coverEntry.file to stream
            } else null
            var seenChapterCount = 0
            val chapterIterator = sequence {
                while (latestEntry != null && ArchiveLayout.parse(latestEntry!!.name) is ArchiveLayout.Entry.Chapter) {
                    val chapterEntry = ArchiveLayout.parse(latestEntry!!.name) as ArchiveLayout.Entry.Chapter
                    if (chapterEntry.seriesIndex != expectedIndex || chapterEntry.index != seenChapterCount) throw ArchiveFormatException("chapter out of order")
                    val chapter = ArchiveChapter.decode(String(zip.readBytes(), Charsets.UTF_8))
                    advance()
                    val chapterIndex = seenChapterCount
                    seenChapterCount += 1
                    val pageIterator = sequence {
                        for (name in chapter.pageFiles) {
                            val current = latestEntry ?: throw ArchiveFormatException("chapter ends early")
                            if (ArchiveLayout.parse(current.name) != ArchiveLayout.Entry.ChapterPage(expectedIndex, chapterIndex, name)) {
                                throw ArchiveFormatException("chapter page out of order")
                            }
                            yield(name to (object : FilterInputStream(zip) { override fun close() {} } as InputStream))
                            advance()
                        }
                    }.iterator()
                    val pages = object : Sequence<Pair<String, InputStream>> {
                        override fun iterator(): Iterator<Pair<String, InputStream>> = pageIterator
                    }
                    yield(SeriesChapterEntry(chapter, pages))
                    while (latestEntry != null && ArchiveLayout.parse(latestEntry!!.name).let {
                            it is ArchiveLayout.Entry.ChapterPage && it.seriesIndex == expectedIndex && it.chapterIndex == chapterIndex
                        }) {
                        advance()
                    }
                }
            }.iterator()
            val chapters = object : Sequence<SeriesChapterEntry> {
                override fun iterator(): Iterator<SeriesChapterEntry> = chapterIterator
            }
            block(SeriesEntry(series, cover, chapters))
            // Continue the same iterator the callback used. A fresh sequence would restart at a chapter record while
            // [latestEntry] still points at that chapter's unread page.
            while (chapterIterator.hasNext()) chapterIterator.next()
            if (series.chapters.size != seenChapterCount) throw ArchiveFormatException("series chapter count disagrees")
            entry = latestEntry
            expectedIndex += 1
        }
        if (expectedIndex != manifest.seriesCount) throw ArchiveFormatException("archive series ends early")
        finishAuthentication()
    }

    private fun finishAuthentication() {
        // ZipInputStream stops at the central directory; drain the decrypted stream so its final chunk (which may
        // hold only that directory) is authenticated too — a damaged or cut end must not read as a whole archive.
        val sink = ByteArray(1 shl 16)
        while (plain.read(sink) >= 0) Unit
    }

    override fun close() = zip.close()

    companion object {
        fun open(file: File, passphrase: CharArray): ArchiveReader = open(file.inputStream(), passphrase)

        /** Reads straight from [source] (closed with the reader) — e.g. the document the user picked. */
        fun open(source: InputStream, passphrase: CharArray): ArchiveReader {
            val input: InputStream = source.buffered(1 shl 16)
            val reader = try {
                val plain = ArchiveCipher.openingStream(passphrase, input)
                ArchiveReader(plain, ZipInputStream(plain))
            } catch (t: Throwable) {
                input.close()
                throw t
            }
            try {
                reader.readHead()
            } catch (t: Throwable) {
                reader.close()
                throw t
            }
            return reader
        }
    }
}
