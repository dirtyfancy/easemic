package com.easemic.app.sync

import java.io.File

/** The file-backed supplier used by AppGraph for serialized chapter uploads. */
internal fun fileThumbnailSupplier(
    pageFiles: List<File>,
    thumbnailer: (File) -> ByteArray?,
): CloudCollectionStore.ThumbSupplier = object : CloudCollectionStore.ThumbSupplier {
    override val size: Int = pageFiles.size
    override fun get(ordinal: Int): ByteArray =
        thumbnailer(pageFiles[ordinal]) ?: rawThumbnailFallback(pageFiles[ordinal])
}

/** Shared gallery/chapter fallback: a failed decode must not put a large page in a thumb pack. */
internal fun rawThumbnailFallback(
    page: File,
    maxBytes: Long = CloudCollectionIngest.RAW_THUMB_FALLBACK_MAX_BYTES,
): ByteArray = if (page.length() > maxBytes) {
    CloudCollectionIngest.RAW_THUMB_FALLBACK_PLACEHOLDER
} else {
    page.readBytes()
}
