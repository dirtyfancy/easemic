package com.easemic.app.feature.source.jm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import androidx.core.graphics.createBitmap
import com.easemic.app.feature.download.InvalidPageTransferException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal data class JmImageBounds(val width: Int, val height: Int)
internal fun interface JmPixels : AutoCloseable
internal interface JmImageCodec {
    fun bounds(source: Path): JmImageBounds
    fun decode(source: Path): JmPixels
    fun output(bounds: JmImageBounds): JmPixels
    fun draw(source: JmPixels, output: JmPixels, bounds: JmImageBounds, band: JmBand)
    fun encode(output: JmPixels, destination: Path)
}
internal data class JmTransformTiming(val width: Int, val height: Int, val decodeMs: Long, val encodeMs: Long)
internal fun interface JmReassembler {
    suspend fun reassemble(source: Path, output: Path, bands: Int)
    suspend fun reassembleMeasured(source: Path, output: Path, bands: Int, timing: (JmTransformTiming) -> Unit) {
        reassemble(source, output, bands)
    }
}

/** Only a prefix is read; suffixes and HTTP Content-Type never select the transform. */
internal enum class JmImageMagic { GIF, STATIC;
    companion object {
        fun read(source: Path): JmImageMagic {
            val prefix = ByteArray(12)
            val count = Files.newInputStream(source).use { it.read(prefix) }
            if (count >= 6 && String(prefix,0,6,Charsets.US_ASCII) in listOf("GIF87a","GIF89a")) return GIF
            val png = count >= 8 && prefix.take(8) == listOf(137.toByte(),80.toByte(),78.toByte(),71.toByte(),13.toByte(),10.toByte(),26.toByte(),10.toByte())
            val jpeg = count >= 3 && prefix[0] == 255.toByte() && prefix[1] == 216.toByte() && prefix[2] == 255.toByte()
            val webp = count >= 12 && String(prefix,0,4,Charsets.US_ASCII) == "RIFF" && String(prefix,8,4,Charsets.US_ASCII) == "WEBP"
            if (png || jpeg || webp) return STATIC
            throw InvalidPageTransferException("JM page is not a supported image")
        }
    }
}

/** One source + one output bitmap; the permit belongs to the process, never a source instance. */
internal class JmImageReassembler(
    private val codec: JmImageCodec = AndroidJmImageCodec(),
    private val heapLimit: () -> Long = { Runtime.getRuntime().maxMemory() },
    private val headroom: () -> Long = { Runtime.getRuntime().run { maxMemory() - (totalMemory() - freeMemory()) } },
) : JmReassembler {
    override suspend fun reassemble(source: Path, output: Path, bands: Int) = reassembleMeasured(source, output, bands) {}

    override suspend fun reassembleMeasured(source: Path, output: Path, bands: Int, timing: (JmTransformTiming) -> Unit): Unit = withContext(Dispatchers.IO) {
        require(bands in 0..20)
        try {
            currentCoroutineContext().ensureActive()
            val magic = JmImageMagic.read(source)
            if (magic == JmImageMagic.GIF || bands == 0) {
                Files.copy(source,output,StandardCopyOption.REPLACE_EXISTING)
                currentCoroutineContext().ensureActive()
                return@withContext
            }
            transformPermit.withPermit {
                val bounds = codec.bounds(source)
                admit(bounds)
                currentCoroutineContext().ensureActive()
                var decoded: JmPixels? = null
                var reconstructed: JmPixels? = null
                try {
                    val decodeStart = System.nanoTime()
                    decoded = codec.decode(source)
                    val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000
                    currentCoroutineContext().ensureActive()
                    reconstructed = codec.output(bounds)
                    for (band in JmBandPlan.rows(bounds.height,bands)) {
                        currentCoroutineContext().ensureActive()
                        codec.draw(decoded,reconstructed,bounds,band)
                    }
                    val encodeStart = System.nanoTime()
                    codec.encode(reconstructed,output)
                    val encodeMs = (System.nanoTime() - encodeStart) / 1_000_000
                    currentCoroutineContext().ensureActive()
                    timing(JmTransformTiming(bounds.width, bounds.height, decodeMs, encodeMs))
                } finally {
                    try { reconstructed?.close() } finally { decoded?.close() }
                }
            }
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(output) }
            throw error
        }
    }
    private fun admit(bounds: JmImageBounds) {
        val estimate = try {
            require(bounds.width > 0 && bounds.height > 0)
            val pixels = Math.multiplyExact(bounds.width.toLong(),bounds.height.toLong())
            // Two ARGB_8888 images, codec reserve and two row buffers/alignment.
            Math.addExact(Math.multiplyExact(8L,pixels), Math.addExact(1024L*1024,Math.multiplyExact(16L,bounds.width.toLong())))
        } catch (_: Exception) { throw InvalidPageTransferException("JM page dimensions are invalid") }
        if (estimate > minOf(64L*1024*1024,heapLimit()/4) || estimate > headroom()) {
            throw InvalidPageTransferException("JM page exceeds the image memory budget")
        }
    }
    companion object { private val transformPermit = Semaphore(1) }
}

internal class AndroidJmImageCodec : JmImageCodec {
    private class Pixels(val bitmap: Bitmap) : JmPixels { override fun close() { bitmap.recycle() } }
    override fun bounds(source: Path): JmImageBounds {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.toString(),options)
        return JmImageBounds(options.outWidth,options.outHeight)
    }
    override fun decode(source: Path): JmPixels {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false; inMutable = true }
        val bitmap = BitmapFactory.decodeFile(source.toString(),options)
            ?: throw InvalidPageTransferException("JM page cannot be decoded")
        if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.recycle()
            throw InvalidPageTransferException("JM page has an unsupported pixel format")
        }
        return Pixels(bitmap)
    }
    override fun output(bounds: JmImageBounds): JmPixels = Pixels(createBitmap(bounds.width,bounds.height,Bitmap.Config.ARGB_8888))
    override fun draw(source: JmPixels, output: JmPixels, bounds: JmImageBounds, band: JmBand) {
        val input = (source as Pixels).bitmap
        if (input.width != bounds.width || input.height != bounds.height) throw InvalidPageTransferException("JM page bounds changed")
        val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
        Canvas((output as Pixels).bitmap).drawBitmap(input,
            Rect(0,band.sourceStart,bounds.width,band.sourceStart+band.height),
            Rect(0,band.destinationStart,bounds.width,band.destinationStart+band.height),paint)
    }
    @Suppress("DEPRECATION")
    override fun encode(output: JmPixels, destination: Path) {
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        Files.newOutputStream(destination).use {
            if (!(output as Pixels).bitmap.compress(format,95,it)) throw InvalidPageTransferException("JM page could not be encoded")
        }
    }
}
