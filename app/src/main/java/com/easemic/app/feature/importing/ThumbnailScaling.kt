package com.easemic.app.feature.importing

/**
 * S4 Task 1 (owner bug C4): the bounded longest-edge cap used by
 * `com.easemic.app.AppGraph`'s cloud-cover thumbnailer
 * (production `decodeCoverThumbnail`) — it downscales to at most this many
 * pixels on the longest edge before re-encoding.
 */
internal const val THUMBNAIL_MAX_EDGE = 320

/**
 * S4 Task 1 (owner bug C4): the bounded `inSampleSize` computation used by
 * `AppGraph`'s cloud-cover thumbnailer. Returns the smallest power-of-two `inSampleSize` for which
 * both [width] and [height], once downsampled, are at most [maxEdge].
 */
internal fun boundedThumbnailSampleSize(
    width: Int,
    height: Int,
    maxEdge: Int = THUMBNAIL_MAX_EDGE,
): Int {
    var sample = 1
    while (width / sample > maxEdge || height / sample > maxEdge) {
        sample *= 2
    }
    return sample
}

/**
 * C5 audit MEDIUM-3: [boundedThumbnailSampleSize] alone lands a 1288 px page at
 * `inSampleSize = 8` → a 161 px "thumb" for a ~360 px grid cell. The derivative
 * is decoded at the LARGEST power of two that keeps the longest edge at or
 * ABOVE [maxEdge] (never below it), then scaled to exactly the cap by
 * [thumbnailTargetSize]. A page already within the cap decodes at 1.
 */
internal fun thumbnailDecodeSampleSize(
    width: Int,
    height: Int,
    maxEdge: Int = THUMBNAIL_MAX_EDGE,
): Int {
    val longest = maxOf(width, height)
    if (longest <= maxEdge) return 1
    var sample = 1
    while (longest / (sample * 2) >= maxEdge) {
        sample *= 2
    }
    return sample
}

/**
 * The exact output size of a derivative: the longest edge becomes [maxEdge]
 * and the other edge keeps the aspect ratio (rounded, never below 1); an
 * image already within the cap keeps its size — a thumb is never upscaled.
 */
internal fun thumbnailTargetSize(
    width: Int,
    height: Int,
    maxEdge: Int = THUMBNAIL_MAX_EDGE,
): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= maxEdge || longest <= 0) return width to height
    val scale = maxEdge.toDouble() / longest
    val w = if (width >= height) maxEdge else maxOf(1, Math.round(width * scale).toInt())
    val h = if (height > width) maxEdge else maxOf(1, Math.round(height * scale).toInt())
    return w to h
}
