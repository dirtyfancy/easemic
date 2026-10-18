package com.easemic.app.feature.importing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.easemic.app.importing.opds.OpdsCatalogClient
import com.easemic.app.sync.DiagLog
import java.io.ByteArrayOutputStream
import java.io.File

/** Same tag AppGraph's cloud-cover decode/write paths log under, so one logcat filter covers both. */
private const val TAG_CLOUD_COVER = "AppGraphCloudCover"

/**
 * Longest-edge bound for durable shelf covers (#E102): sharp on a phone shelf
 * and detail header, a fraction of the original's bytes. Deliberately larger
 * than [THUMBNAIL_MAX_EDGE] — the 320px thumbs serve grid cells, this serves
 * shelf cards and the detail header.
 */
internal const val COVER_MAX_EDGE = 720

/**
 * #E102: an existing durable cover at or under this many bytes is never worth
 * a re-encode pass ([isCoverReencodeNeeded] also keeps files whose pixels
 * already fit [COVER_MAX_EDGE], so a re-encoded file can never be selected
 * again — the sweep is idempotent by construction).
 */
internal const val REENCODE_SKIP_BYTES = 256 * 1024L

/**
 * #E102: pure skip decision for the durable-cover re-encode sweep. True only
 * when the file is over [REENCODE_SKIP_BYTES] and its pixels overflow
 * [COVER_MAX_EDGE]; a file that does not decode (`width`/`height` <= 0) is
 * never selected — the sweep leaves it untouched and logs it instead.
 */
internal fun isCoverReencodeNeeded(length: Long, width: Int, height: Int): Boolean {
    if (length <= REENCODE_SKIP_BYTES) return false
    if (width <= 0 || height <= 0) return false
    return maxOf(width, height) > COVER_MAX_EDGE
}

/**
 * #E102: re-encode an on-device cover source into a bounded JPEG-80 derivative
 * (longest edge <= [COVER_MAX_EDGE], same downscale math as the 320px thumbs
 * pipeline — [thumbnailDecodeSampleSize] then [thumbnailTargetSize], scaled
 * against [COVER_MAX_EDGE] instead of [THUMBNAIL_MAX_EDGE]). Animated sources
 * contribute their first frame only — the durable cover exists to render
 * shelf/detail previews, not to play. Returns null when the source does not
 * decode (BitmapFactory failure) or the encode fails — callers then skip the
 * durable copy rather than persisting undecodable bytes; each failure is
 * logged once here under [TAG_CLOUD_COVER].
 */
internal fun encodeCloudCoverDerivative(source: File): ByteArray? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        DiagLog.w(TAG_CLOUD_COVER, "cover source does not decode: ${OpdsCatalogClient.redactUrl(source.name)}")
        null
    } else {
        val options = BitmapFactory.Options().apply {
            inSampleSize = thumbnailDecodeSampleSize(bounds.outWidth, bounds.outHeight, COVER_MAX_EDGE)
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, options)
        if (decoded == null) {
            DiagLog.w(TAG_CLOUD_COVER, "cover source did not decode at sample size: ${OpdsCatalogClient.redactUrl(source.name)}")
            null
        } else {
            val (targetW, targetH) = thumbnailTargetSize(decoded.width, decoded.height, COVER_MAX_EDGE)
            val bitmap = if (targetW == decoded.width && targetH == decoded.height) {
                decoded
            } else {
                decoded.scale(targetW, targetH).also { if (it !== decoded) decoded.recycle() }
            }
            try {
                ByteArrayOutputStream().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)) {
                        DiagLog.w(TAG_CLOUD_COVER, "cover derivative encode failed: ${OpdsCatalogClient.redactUrl(source.name)}")
                        null
                    } else {
                        output.toByteArray()
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
} catch (error: Exception) {
    DiagLog.w(TAG_CLOUD_COVER, "failed deriving cloud cover from ${OpdsCatalogClient.redactUrl(source.name)}", error)
    null
}
