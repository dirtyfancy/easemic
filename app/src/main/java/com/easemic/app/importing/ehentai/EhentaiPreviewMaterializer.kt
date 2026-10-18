package com.easemic.app.importing.ehentai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import com.easemic.app.storage.CacheBudget

/**
 * Turns EH gallery gdt sprite cells into discrete preview image URIs for Detail.
 */
object EhentaiPreviewMaterializer {
    const val LIMIT = 24

    suspend fun materialize(
        thumbs: List<EhentaiPreviewThumb>,
        fetchSheet: (String) -> ByteArray,
        cropAndStore: (index: Int, sheetBytes: ByteArray, thumb: EhentaiPreviewThumb) -> String?,
        limit: Int = LIMIT,
        seed: List<String> = emptyList(),
        onPartial: suspend (List<String>) -> Unit = {},
        isUsableUri: (String) -> Boolean = { true },
    ): List<String> {
        val selected = thumbs.take(limit.coerceAtLeast(0))
        val sheetCache = mutableMapOf<String, ByteArray>()
        val validSeed = seed.takeWhile(isUsableUri)
        val urls = validSeed.toMutableList()
        selected.forEachIndexed { index, thumb ->
            if (index < validSeed.size) return@forEachIndexed
            val sheet = sheetCache.getOrPut(thumb.sheetUrl) { fetchSheet(thumb.sheetUrl) }
            val uri = cropAndStore(index, sheet, thumb) ?: return@forEachIndexed
            urls += uri
            onPartial(urls.toList())
        }
        return urls.toList()
    }

    fun cropSpriteToJpeg(sheetBytes: ByteArray, thumb: EhentaiPreviewThumb): ByteArray? {
        val sheet = BitmapFactory.decodeByteArray(sheetBytes, 0, sheetBytes.size) ?: return null
        return try {
            if (thumb.offsetX + thumb.width > sheet.width || thumb.height > sheet.height) {
                return null
            }
            val cropped = Bitmap.createBitmap(
                sheet,
                thumb.offsetX,
                0,
                thumb.width,
                thumb.height,
            )
            try {
                ByteArrayOutputStream().use { out ->
                    if (!cropped.compress(Bitmap.CompressFormat.JPEG, 85, out)) return null
                    out.toByteArray()
                }
            } finally {
                if (cropped !== sheet) cropped.recycle()
            }
        } finally {
            sheet.recycle()
        }
    }

    fun writeJpegToCache(
        cacheDir: File,
        galleryId: Long,
        index: Int,
        jpeg: ByteArray,
        budget: CacheBudget? = null,
    ): String {
        val file = File(cacheDir, "eh-preview-$galleryId-$index.jpg")
        return budget?.withRootLock(cacheDir) {
            cacheDir.mkdirs()
            // Re-materializing a preview overwrites the previous one: only the
            // delta is new on disk, so only the delta is reported.
            val previous = if (file.isFile) file.length() else 0L
            val tmp = File.createTempFile("eh-preview-", ".part", cacheDir)
            try {
                tmp.writeBytes(jpeg)
                if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
            } finally {
                tmp.delete()
            }
            budget.recordWrite(cacheDir, jpeg.size.toLong() - previous)
            file.toURI().toString()
        } ?: run {
            cacheDir.mkdirs()
            val tmp = File.createTempFile("eh-preview-", ".part", cacheDir)
            try {
                tmp.writeBytes(jpeg)
                if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
            } finally {
                tmp.delete()
            }
            file.toURI().toString()
        }
    }
}
