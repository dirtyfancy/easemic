package com.easemic.app.sync.adfilter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * #E118: 64-bit Difference Hash (dHash) perceptual image fingerprint and
 * bounded JPEG normalizer for TypeSafe Jev image evaluation.
 *
 * - **64-bit dHash**: Resizes the image to a 9×8 luminance grid and compares
 *   adjacent horizontal pixels (`col` vs `col + 1`) across 8 rows × 8 columns
 *   to produce an unsigned 64-bit fingerprint encoded as 16 lowercase hex
 *   characters. Tolerates recompression and aspect/resolution scaling across
 *   scanlation releases (`hammingDistance <= DEFAULT_MAX_HAMMING_DISTANCE`).
 * - **Jev Image Normalizer**: TypeSafe System One requires PNG/JPEG with each
 *   side in `256..1568` pixels and total area `>= 78,400` (`280×280`). This
 *   helper scales any comic page into `320..1024` pixels per side and encodes
 *   it as JPEG quality 85.
 */
object PerceptualImageHash {
    const val DEFAULT_MAX_HAMMING_DISTANCE = 5
    private const val DHASH_WIDTH = 9
    private const val DHASH_HEIGHT = 8
    private const val JEV_MIN_SIDE_PX = 320
    private const val JEV_MAX_SIDE_PX = 1024
    private const val JEV_JPEG_QUALITY = 85

    /**
     * Computes a 16-character lowercase hex 64-bit dHash from raw luminance
     * values of a 9×8 grid (`luminance9x8.size == 72`). Pure JVM function so
     * unit tests can verify bit packing and gradient polarity without Android
     * `Bitmap`.
     */
    fun computeDHashFromLuminanceGrid(luminance9x8: IntArray): Long {
        require(luminance9x8.size == DHASH_WIDTH * DHASH_HEIGHT) {
            "Expected ${DHASH_WIDTH * DHASH_HEIGHT} luminance samples, got ${luminance9x8.size}"
        }
        var hash = 0L
        for (row in 0 until DHASH_HEIGHT) {
            val rowOffset = row * DHASH_WIDTH
            for (col in 0 until DHASH_HEIGHT) {
                val left = luminance9x8[rowOffset + col]
                val right = luminance9x8[rowOffset + col + 1]
                hash = (hash shl 1) or if (left > right) 1L else 0L
            }
        }
        return hash
    }

    fun formatHex(hash: Long): String = java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')

    fun parseHexOrNull(hex: String): Long? {
        val cleaned = hex.trim().lowercase()
        if (cleaned.length != 16) return null
        if (!cleaned.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return runCatching { java.lang.Long.parseUnsignedLong(cleaned, 16) }.getOrNull()
    }

    fun hammingDistance(a: Long, b: Long): Int = (a xor b).countOneBits()

    fun hammingDistanceHex(hexA: String, hexB: String): Int? {
        val a = parseHexOrNull(hexA) ?: return null
        val b = parseHexOrNull(hexB) ?: return null
        return hammingDistance(a, b)
    }

    /**
     * Computes the 16-char hex dHash of an on-disk image [file], or null if
     * the file cannot be decoded as a still bitmap.
     */
    fun computeFileDHashHex(file: File): String? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 128, 128)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
            try {
                val scaled = Bitmap.createScaledBitmap(decoded, DHASH_WIDTH, DHASH_HEIGHT, true)
                try {
                    val pixels = IntArray(DHASH_WIDTH * DHASH_HEIGHT)
                    scaled.getPixels(pixels, 0, DHASH_WIDTH, 0, 0, DHASH_WIDTH, DHASH_HEIGHT)
                    val lum = IntArray(pixels.size) { idx ->
                        val c = pixels[idx]
                        val r = Color.red(c)
                        val g = Color.green(c)
                        val b = Color.blue(c)
                        // ITU-R BT.601 integer approximation: (299*R + 587*G + 114*B) / 1000
                        (299 * r + 587 * g + 114 * b) / 1000
                    }
                    formatHex(computeDHashFromLuminanceGrid(lum))
                } finally {
                    if (scaled !== decoded) scaled.recycle()
                }
            } finally {
                decoded.recycle()
            }
        }.getOrNull()
    }

    /**
     * Normalizes [file] into a JPEG byte array meeting TypeSafe Jev's image
     * constraints (`256..1568` px per side, `>= 78,400` total pixels).
     */
    fun encodeNormalizedJpegForJev(file: File): ByteArray? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val origW = bounds.outWidth
            val origH = bounds.outHeight
            if (origW <= 0 || origH <= 0) return null
            val sampleSize = calculateInSampleSize(origW, origH, JEV_MAX_SIDE_PX, JEV_MAX_SIDE_PX)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
            try {
                val (targetW, targetH) = computeJevTargetDimensions(decoded.width, decoded.height)
                val scaled = if (decoded.width == targetW && decoded.height == targetH) {
                    decoded
                } else {
                    Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
                }
                try {
                    val out = ByteArrayOutputStream(64 * 1024)
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, JEV_JPEG_QUALITY, out)) {
                        return null
                    }
                    out.toByteArray().takeIf { it.isNotEmpty() }
                } finally {
                    if (scaled !== decoded) scaled.recycle()
                }
            } finally {
                decoded.recycle()
            }
        }.getOrNull()
    }

    internal fun computeJevTargetDimensions(width: Int, height: Int): Pair<Int, Int> {
        val safeW = max(1, width)
        val safeH = max(1, height)
        val maxSide = max(safeW, safeH)
        val scaleDown = min(1.0, JEV_MAX_SIDE_PX.toDouble() / maxSide.toDouble())
        val w = (safeW * scaleDown).roundToInt().coerceIn(JEV_MIN_SIDE_PX, JEV_MAX_SIDE_PX)
        val h = (safeH * scaleDown).roundToInt().coerceIn(JEV_MIN_SIDE_PX, JEV_MAX_SIDE_PX)
        return w to h
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return max(1, inSampleSize)
    }
}
