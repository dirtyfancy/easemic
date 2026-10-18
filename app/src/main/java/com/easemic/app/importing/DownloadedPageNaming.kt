package com.easemic.app.importing

import com.easemic.app.sync.ImageContentType

/**
 * A8-P5a: downloaded pages were named `NNNN.jpg` with an `image/jpeg` mime
 * whatever the bytes said, so a PNG/GIF/WebP page landed under a lying name.
 * Sniffing reuses [ImageContentType]; bytes matching nothing known keep the old
 * jpeg naming, because a wrong extension still imports while an unrecognized
 * one is dropped by [isSupportedImageFilename].
 */
fun downloadedPageType(bytes: ByteArray): ImageContentType =
    ImageContentType.sniff(bytes).takeIf { it != ImageContentType.UNKNOWN }
        ?: ImageContentType.JPEG

fun downloadedPageName(pageNumber: Int, type: ImageContentType): String =
    "%04d.%s".format(pageNumber, type.extension)
