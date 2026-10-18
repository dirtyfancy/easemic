package com.easemic.app.feature.reader

/**
 * Pure helpers for the immersive bottom page-seek overlay.
 */
object ReaderSeekOverlayMath {
    fun clampOrdinal0Based(ordinal: Int, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        return ordinal.coerceIn(0, pageCount - 1)
    }

    fun displayCurrent(ordinal0Based: Int, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        return clampOrdinal0Based(ordinal0Based, pageCount) + 1
    }

    fun displayTotal(pageCount: Int): Int = pageCount.coerceAtLeast(0)

    fun sliderValueRange(pageCount: Int): ClosedFloatingPointRange<Float> {
        if (pageCount <= 1) return 0f..0f
        return 0f..(pageCount - 1).toFloat()
    }

    /** Discrete stops between endpoints (Material3 Slider semantics). */
    fun sliderSteps(pageCount: Int): Int = (pageCount - 2).coerceAtLeast(0)

    fun ordinalFromSlider(value: Float, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        return clampOrdinal0Based(kotlin.math.round(value).toInt(), pageCount)
    }
}
