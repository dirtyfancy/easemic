package com.easemic.app.sync

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * #C51: high-resolution pages travel as STORED zips of consecutive ordinals
 * (`comics/<id>/pages/pack-NNNN.zip`, NNNN = the pack INDEX). The reader never parses
 * the zip: the manifest records every page's pack, data offset and length, and a page is
 * an HTTP Range of its pack. The zip framing is there so a pack is still a plain archive
 * for any tool, and so a server that ignores `Range` costs one pack, never a whole comic.
 *
 * Determinism: fixed entry time inside the DOS range, no extra fields, STORED with
 * pre-declared size/crc → the same pages always give the same SIZE, which is what the
 * presence check on resume compares (spec amendment "deterministic by SIZE, not by bytes";
 * the DOS timestamp bytes depend on the writer's timezone and do not change the size).
 *
 * Offsets are MEASURED by a counting stream read right after `putNextEntry` — the local
 * header is written there — never computed from `30 + nameLength` arithmetic.
 */
object PagePack {
    /** A pack closes at ~32 MiB; see [group]. */
    const val TARGET_BYTES: Long = 32L shl 20

    const val MIME: String = "application/zip"

    /**
     * 1980-06-15 12:00 local — inside the DOS range. A time before 1980 makes `ZipEntry`
     * fall back to an extended-timestamp extra field, which would change the pack's size
     * for reasons that have nothing to do with the pages.
     */
    private val FIXED_ENTRY_TIME: Long =
        java.util.GregorianCalendar(1980, 5, 15, 12, 0, 0).timeInMillis

    /** A pack's consecutive ordinal range `[first, first + count)`. */
    data class Group(val first: Int, val count: Int)

    /** What [write] produced: the whole pack object's size and each entry's data offset inside it. */
    data class Written(val bytes: Long, val offsets: List<Long>)

    /**
     * Consecutive ordinals; a pack closes when the NEXT page would exceed [targetBytes]
     * and it already holds one page — so a page larger than the target is a pack of its
     * own and a page is never split.
     */
    fun group(pageBytes: List<Long>, targetBytes: Long = TARGET_BYTES): List<Group> {
        require(targetBytes > 0) { "targetBytes must be > 0" }
        val groups = ArrayList<Group>()
        var first = 0
        var count = 0
        var size = 0L
        pageBytes.forEachIndexed { ordinal, bytes ->
            if (count > 0 && size + bytes > targetBytes) {
                groups += Group(first, count)
                first = ordinal
                count = 0
                size = 0L
            }
            count++
            size += bytes
        }
        if (count > 0) groups += Group(first, count)
        return groups
    }

    /** The index of the pack holding [ordinal]; a linear scan over ≤ ~20 packs. */
    fun packIndexOf(groups: List<Group>, ordinal: Int): Int {
        val i = groups.indexOfFirst { ordinal >= it.first && ordinal < it.first + it.count }
        check(i >= 0) { "ordinal $ordinal is in none of ${groups.size} packs" }
        return i
    }

    /** The pack's file name — the same last segment as [CloudCollectionKeys.pagePackKey], spelled once. */
    fun fileName(packIndex: Int): String = "pack-${requireOrdinal(packIndex)}.zip"

    fun entryName(ordinal: Int, ext: String): String = "${requireOrdinal(ordinal)}.${requireObjectExt(ext)}"

    /**
     * Streams [pages] (consecutive ordinals from [firstOrdinal]) into [out]; never holds a
     * page in memory beyond one 64 KiB buffer — six lanes × 32 MiB of RAM is not acceptable
     * on the owner's phone, which is why packs are built on disk and PUT with `uploadFile`.
     */
    fun write(out: File, firstOrdinal: Int, pages: List<File>, exts: List<String>): Written {
        require(pages.isNotEmpty() && pages.size == exts.size) { "pages/exts mismatch" }
        val offsets = ArrayList<Long>(pages.size)
        val counting = CountingOutputStream(FileOutputStream(out))
        ZipOutputStream(counting).use { zip ->
            zip.setMethod(ZipOutputStream.STORED)
            val buffer = ByteArray(64 * 1024)
            pages.forEachIndexed { i, file ->
                val crc = CRC32()
                FileInputStream(file).use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        crc.update(buffer, 0, n)
                    }
                }
                val entry = ZipEntry(entryName(firstOrdinal + i, exts[i])).apply {
                    method = ZipEntry.STORED
                    size = file.length()
                    compressedSize = file.length()
                    this.crc = crc.value
                    time = FIXED_ENTRY_TIME
                }
                zip.putNextEntry(entry)
                // The local header has been written straight through to `counting`
                // (STORED bypasses the deflater), so the next byte is the page's first.
                offsets += counting.count
                FileInputStream(file).use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                    }
                }
                zip.closeEntry()
            }
        }
        return Written(bytes = out.length(), offsets = offsets)
    }

    private class CountingOutputStream(private val inner: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            inner.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            inner.write(b, off, len)
            count += len
        }

        override fun flush() = inner.flush()

        override fun close() = inner.close()
    }
}
