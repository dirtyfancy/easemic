package com.easemic.app.sync

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * One 64 KiB buffer. A 206 body is already the requested span: copy [length] and reject
 * an early EOF or one extra byte. A 200 body is the whole object: skip [offset] by reading,
 * then copy [length]. Neither path builds a byte array of the object.
 */
internal fun copyRangeBody(
    input: InputStream,
    dest: File,
    offset: Long,
    length: Int,
    wholeBody: Boolean,
) {
    require(offset >= 0) { "offset must be >= 0" }
    require(length > 0) { "length must be > 0" }
    val buffer = ByteArray(64 * 1024)
    if (wholeBody) {
        var skip = offset
        while (skip > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), skip).toInt())
            if (n < 0) throw IOException("range past the end of the object: $offset+$length")
            skip -= n
        }
    }
    FileOutputStream(dest).use { output ->
        var left = length
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size, left))
            if (n < 0) throw IOException("short range body: ${length - left} of $length")
            output.write(buffer, 0, n)
            left -= n
        }
    }
    if (!wholeBody) {
        val extra = input.read()
        if (extra >= 0) throw IOException("long range body: more than $length bytes")
    }
}
