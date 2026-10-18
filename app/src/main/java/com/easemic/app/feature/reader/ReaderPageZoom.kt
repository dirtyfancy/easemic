package com.easemic.app.feature.reader

import androidx.compose.ui.semantics.SemanticsPropertyKey

/**
 * #E125: Cross-page spread (wide landscape page, `width / height >= 1.20f`) adaptation mode
 * inspired by Bilibili Manga (`flutter.wc_wide_ratio`, `[横屏]观看大图`) and Zaimanhua
 * (`ComicDualPageSplit`, `ComicDualPageInvert`).
 */
enum class SpreadPageSubSlot {
    FULL,
    LEFT_HALF,
    RIGHT_HALF,
    ROTATED_90,
}

object ReaderPageZoom {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 3f
    const val FIT_SCALE = MIN_SCALE
    const val ZOOMED_SCALE = 2f

    /**
     * #E125 (Bilibili `flutter.wc_wide_ratio` & Zaimanhua `ComicDualPageSplit`):
     * A page whose width is at least 1.20× its height (`height / width <= 0.8333f`)
     * AND at least 320px wide (so tiny synthetic test icons like 3x2 px are ignored)
     * is a double-page spread.
     */
    const val SPREAD_WIDTH_OVER_HEIGHT_THRESHOLD = 1.20f
    const val SPREAD_MAX_HEIGHT_OVER_WIDTH = 1f / SPREAD_WIDTH_OVER_HEIGHT_THRESHOLD
    const val SPREAD_MIN_WIDTH_PX = 320

    val SCALE_SEMANTICS_KEY = SemanticsPropertyKey<Float>("ReaderPageZoomScale")

    fun isSpreadAspect(heightOverWidth: Float?): Boolean =
        heightOverWidth != null &&
            heightOverWidth > 0f &&
            heightOverWidth <= SPREAD_MAX_HEIGHT_OVER_WIDTH

    fun isSpreadDimensions(widthPx: Int, heightPx: Int): Boolean =
        widthPx >= SPREAD_MIN_WIDTH_PX &&
            heightPx > 0 &&
            (widthPx.toFloat() / heightPx.toFloat()) >= SPREAD_WIDTH_OVER_HEIGHT_THRESHOLD

    /**
     * Standard single-parameter toggle preserved for existing callers (`1f <-> 2f`).
     */
    fun nextScale(current: Float): Float =
        if (current == ZOOMED_SCALE) FIT_SCALE else ZOOMED_SCALE

    /**
     * #E125 (Tencent `PhotoView.s0` / `v0` & Bilibili spread zoom):
     * When double-tapping a wide spread (`isSpreadAspect == true`) in a portrait viewport,
     * compute the scale needed to fill ~88% of the viewport height (clamped to [ZOOMED_SCALE]..[MAX_SCALE])
     * so small dialogue text becomes immediately readable without black-bar waste.
     * For standard portrait pages, preserves the exact `nextScale(current)` (`1f <-> 2f`) contract.
     */
    fun nextScaleForPage(
        current: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        contentAspectHeightOverWidth: Float?,
    ): Float {
        if (
            viewportWidth > 0f &&
            viewportHeight > 0f &&
            isSpreadAspect(contentAspectHeightOverWidth)
        ) {
            if (current > FIT_SCALE) return FIT_SCALE
            val aspect = contentAspectHeightOverWidth ?: return ZOOMED_SCALE
            val renderedHeightAtFit = viewportWidth * aspect
            if (renderedHeightAtFit > 0f && renderedHeightAtFit < viewportHeight) {
                val fillHeightScale = (viewportHeight * 0.88f) / renderedHeightAtFit
                return fillHeightScale.coerceIn(ZOOMED_SCALE, MAX_SCALE)
            }
        }
        return nextScale(current)
    }

    /**
     * #E125 (Zaimanhua `ComicDualPageSplit` + `ComicDualPageInvert` & Bilibili `DIRECTION_H_BACKWORD`):
     * When splitting a double-page spread into two half-page steps in portrait mode:
     * - Japanese manga (`rtl = true`): read the right page first, then the left page (`[RIGHT_HALF, LEFT_HALF]`).
     * - Chinese/Korean/Western comics (`rtl = false`): read the left page first, then the right page (`[LEFT_HALF, RIGHT_HALF]`).
     */
    fun spreadSplitOrder(rtl: Boolean): List<SpreadPageSubSlot> =
        if (rtl) {
            listOf(SpreadPageSubSlot.RIGHT_HALF, SpreadPageSubSlot.LEFT_HALF)
        } else {
            listOf(SpreadPageSubSlot.LEFT_HALF, SpreadPageSubSlot.RIGHT_HALF)
        }

    /**
     * #E125: Cycles the active spread sub-slot through `[FULL -> firstHalf -> secondHalf -> ROTATED_90 -> FULL]`
     * where `firstHalf` and `secondHalf` follow [spreadSplitOrder] for [rtl].
     */
    fun nextSpreadSubSlot(current: SpreadPageSubSlot, rtl: Boolean): SpreadPageSubSlot {
        val (firstHalf, secondHalf) = spreadSplitOrder(rtl)
        return when (current) {
            SpreadPageSubSlot.FULL -> firstHalf
            firstHalf -> secondHalf
            secondHalf -> SpreadPageSubSlot.ROTATED_90
            SpreadPageSubSlot.ROTATED_90 -> SpreadPageSubSlot.FULL
            else -> SpreadPageSubSlot.FULL
        }
    }

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    fun pagerUserScrollEnabled(scale: Float): Boolean = scale == FIT_SCALE
}
