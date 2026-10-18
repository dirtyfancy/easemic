package com.easemic.app.storage

import android.graphics.BitmapFactory
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class ImageBounds(val width: Int, val height: Int, val mimeType: String)

enum class ImageRejection {
    INVALID_BOUNDS,
    DIMENSION_LIMIT,
    PIXEL_LIMIT,
    MALFORMED_HEADER,
    UNSUPPORTED_FORMAT,
    MALFORMED_IMAGE,
}

sealed interface ImageProbeResult {
    data class Accepted(val bounds: ImageBounds) : ImageProbeResult
    data class Unsupported(val reason: ImageRejection) : ImageProbeResult
}

data class ImageDecodeRequest(
    val targetWidth: Int,
    val targetHeight: Int,
)

class ImageProbe(
    private val decodeObserver: (ImageDecodeRequest) -> Unit = {},
    private val boundsDecoder: (InputStream) -> ImageBounds? = ::decodeBounds,
    private val sampleDecoder: (InputStream, Int) -> Boolean = ::decodeSample,
) {
    suspend fun probe(openStream: () -> InputStream): ImageProbeResult = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val bounds = openStream().use(boundsDecoder)
            ?: return@withContext ImageProbeResult.Unsupported(ImageRejection.MALFORMED_HEADER)
        val width = bounds.width
        val height = bounds.height
        val mimeType = bounds.mimeType
        if (width <= 0 || height <= 0) {
            return@withContext ImageProbeResult.Unsupported(ImageRejection.INVALID_BOUNDS)
        }
        val pixels = width.toLong() * height.toLong()
        if (pixels > MAX_PIXELS) {
            return@withContext ImageProbeResult.Unsupported(ImageRejection.PIXEL_LIMIT)
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            return@withContext ImageProbeResult.Unsupported(ImageRejection.DIMENSION_LIMIT)
        }
        if (mimeType !in SUPPORTED_MIME_TYPES) {
            return@withContext ImageProbeResult.Unsupported(ImageRejection.UNSUPPORTED_FORMAT)
        }

        DECODE_PERMIT.withPermit {
            coroutineContext.ensureActive()
            val sampleSize = calculateSampleSize(width, height)
            decodeObserver(
                ImageDecodeRequest(
                    targetWidth = maxOf(1, width / sampleSize),
                    targetHeight = maxOf(1, height / sampleSize),
                ),
            )
            if (!openStream().use { sampleDecoder(it, sampleSize) }) {
                return@withContext ImageProbeResult.Unsupported(ImageRejection.MALFORMED_IMAGE)
            }
            coroutineContext.ensureActive()
        }
        ImageProbeResult.Accepted(ImageBounds(width, height, mimeType))
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (ceilDiv(width, sample) > VALIDATION_SIDE || ceilDiv(height, sample) > VALIDATION_SIDE) {
            sample *= 2
        }
        return sample
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        (value + divisor - 1) / divisor

    companion object {
        const val MAX_DIMENSION = 32_768
        const val MAX_PIXELS = 150_000_000L
        const val VALIDATION_SIDE = 2_048

        private val SUPPORTED_MIME_TYPES = setOf(
            "image/gif",
            "image/png",
            "image/jpeg",
            "image/webp",
        )
        private val DECODE_PERMIT = Semaphore(1)

        private fun decodeBounds(input: InputStream): ImageBounds? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, options)
            val mimeType = options.outMimeType ?: return null
            return ImageBounds(options.outWidth, options.outHeight, mimeType)
        }

        private fun decodeSample(input: InputStream, sampleSize: Int): Boolean {
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeStream(input, null, options) ?: return false
            bitmap.recycle()
            return true
        }
    }
}
