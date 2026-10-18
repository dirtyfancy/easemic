package com.easemic.app.feature.source.jm

import java.security.MessageDigest

internal data class JmBand(val sourceStart: Int, val destinationStart: Int, val height: Int)

/** Protocol arithmetic only. Filenames have already been validated by the chapter decoder. */
internal object JmBandPlan {
    fun count(scrambleId: Long, photoId: Long, filenameStem: String): Int {
        require(scrambleId >= 0 && photoId >= 0 && filenameStem.isNotEmpty())
        if (photoId < scrambleId) return 0
        if (photoId < 268850) return 10
        val digest = MessageDigest.getInstance("MD5").digest("$photoId$filenameStem".toByteArray(Charsets.UTF_8))
        val last = "0123456789abcdef"[digest.last().toInt() and 15]
        // The protocol uses the character's ASCII value, not its hexadecimal value.
        return 2 * (last.code % if (photoId >= 421926) 8 else 10) + 2
    }

    fun rows(height: Int, bands: Int): List<JmBand> {
        require(height > 0 && bands in 0..20)
        if (bands == 0) return listOf(JmBand(0,0,height))
        val q = height / bands
        val r = height % bands
        return (0 until bands).mapNotNull { i ->
            val length = q + if (i == 0) r else 0
            if (length == 0) null else JmBand(height - q*(i+1) - r, if (i == 0) 0 else q*i+r, length)
        }
    }
}
