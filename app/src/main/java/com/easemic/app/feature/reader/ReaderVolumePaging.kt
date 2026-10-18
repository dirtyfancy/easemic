package com.easemic.app.feature.reader

object ReaderVolumePaging {
    const val VOLUME_UP_DELTA = -1
    const val VOLUME_DOWN_DELTA = 1

    fun pageDeltaForVolumeUp(): Int = VOLUME_UP_DELTA

    fun pageDeltaForVolumeDown(): Int = VOLUME_DOWN_DELTA

    /** When [rtl] is true, tap/volume directions are mirrored for manga-style reading. */
    fun directedDelta(baseDelta: Int, rtl: Boolean): Int =
        if (rtl) -baseDelta else baseDelta

    fun nextPageIndex(current: Int, delta: Int, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        return (current + delta).coerceIn(0, pageCount - 1)
    }
}
