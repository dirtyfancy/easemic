package com.easemic.app.feature.reader

/**
 * Target bitmap size for reader pages. Comic files are often several thousand
 * pixels on a side; decoding at native size during a pager fling stalls the
 * GPU upload. Screen-sized (inexact) decode is the usual manga-reader pattern.
 */
object ReaderPageDecodeTarget {
    const val MAX_EDGE_PX: Int = 4096

    fun pixels(widthPx: Int, heightPx: Int): Pair<Int, Int>? {
        if (widthPx <= 0 || heightPx <= 0) return null
        return widthPx.coerceAtMost(MAX_EDGE_PX) to heightPx.coerceAtMost(MAX_EDGE_PX)
    }
}
