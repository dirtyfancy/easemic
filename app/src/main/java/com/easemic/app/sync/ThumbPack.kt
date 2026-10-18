package com.easemic.app.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * #C47-C: the per-page grid thumbnails of one comic travel as one STORED zip
 * per [SIZE] consecutive ordinals (`comics/<id>/thumbs/pack-NNNN.zip`, named by
 * the pack's first ordinal), entries named `NNNN.<ext>`. A cbz is exactly this
 * — a zip of images any tool can open — and STORED keeps the container
 * byte-transparent (JPEG/WebP/PNG do not deflate; sizes stay exact).
 *
 * Why 24: the detail preview strip paints 12 cells first, so a pack is two
 * initial windows — one round trip plus ~320 KB at the measured 13.5 KB median
 * thumb — and every further 24 cells is one more request; the owner's 610-page
 * comic uploads 26 thumbnail objects instead of 610. The size is recorded in
 * the manifest (`thumbPackSize`), so changing this constant never breaks a
 * comic already on the bucket — readers pass the manifest's own size.
 */
object ThumbPack {
    const val SIZE: Int = 24
    const val MIME: String = "application/zip"

    fun packCount(thumbCount: Int): Int {
        require(thumbCount >= 0) { "thumbCount must be >= 0" }
        return (thumbCount + SIZE - 1) / SIZE
    }

    fun packIndexOf(ordinal: Int, packSize: Int = SIZE): Int {
        require(ordinal >= 0) { "ordinal must be >= 0" }
        require(packSize > 0) { "packSize must be > 0" }
        return ordinal / packSize
    }

    fun firstOrdinal(packIndex: Int, packSize: Int = SIZE): Int {
        require(packIndex >= 0) { "packIndex must be >= 0" }
        require(packSize > 0) { "packSize must be > 0" }
        return packIndex * packSize
    }

    fun entryName(ordinal: Int, ext: String): String = "${requireOrdinal(ordinal)}.${requireObjectExt(ext)}"

    /** [thumbs]/[exts] are the pack's consecutive ordinals from [firstOrdinal]; 1..[SIZE] entries. */
    fun encode(firstOrdinal: Int, thumbs: List<ByteArray>, exts: List<String>): ByteArray {
        require(thumbs.size == exts.size) { "thumbs/exts size mismatch" }
        require(thumbs.size in 1..SIZE) { "a pack holds 1..$SIZE thumbs, got ${thumbs.size}" }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setMethod(ZipOutputStream.STORED)
            thumbs.forEachIndexed { index, bytes ->
                val entry = ZipEntry(entryName(firstOrdinal + index, exts[index])).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** The entry for [ordinal] (`NNNN.<ext>`), or null when the pack has no such entry or is not a zip. */
    fun extract(pack: ByteArray, ordinal: Int, ext: String): ByteArray? {
        val wanted = entryName(ordinal, ext)
        return try {
            ZipInputStream(ByteArrayInputStream(pack)).use { zip ->
                var found: ByteArray? = null
                while (found == null) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == wanted) found = zip.readBytes()
                    zip.closeEntry()
                }
                found
            }
        } catch (_: IOException) {
            null
        }
    }
}
