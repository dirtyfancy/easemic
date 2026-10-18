package com.easemic.app.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsPropertyKey
import kotlin.math.abs

object ReaderPagePan {
    val TRANSLATION_SEMANTICS_KEY = SemanticsPropertyKey<Offset>("ReaderPagePanTranslation")

    fun clampTranslation(
        translation: Offset,
        viewportWidth: Float,
        viewportHeight: Float,
        scale: Float,
        fitWidth: Boolean = false,
        contentAspectHeightOverWidth: Float? = null,
    ): Offset {
        val maxTranslation = maxTranslation(
            viewportWidth,
            viewportHeight,
            scale,
            fitWidth,
            contentAspectHeightOverWidth,
        )
        if (maxTranslation == Offset.Zero) {
            return Offset.Zero
        }
        return Offset(
            x = translation.x.coerceIn(-maxTranslation.x, maxTranslation.x),
            y = translation.y.coerceIn(-maxTranslation.y, maxTranslation.y),
        )
    }

    fun maxTranslation(
        viewportWidth: Float,
        viewportHeight: Float,
        scale: Float,
        fitWidth: Boolean = false,
        contentAspectHeightOverWidth: Float? = null,
    ): Offset {
        if (viewportWidth <= 0f || viewportHeight <= 0f || scale <= 0f) return Offset.Zero
        if (fitWidth) {
            val aspect = contentAspectHeightOverWidth ?: return Offset.Zero
            if (aspect <= 0f) return Offset.Zero
            val displayedW = viewportWidth * scale
            val displayedH = viewportWidth * aspect * scale
            return Offset(
                x = ((displayedW - viewportWidth) / 2f).coerceAtLeast(0f),
                y = ((displayedH - viewportHeight) / 2f).coerceAtLeast(0f),
            )
        }
        if (scale <= ReaderPageZoom.FIT_SCALE) return Offset.Zero
        return Offset(
            x = viewportWidth * (scale - ReaderPageZoom.FIT_SCALE) / 2f,
            y = viewportHeight * (scale - ReaderPageZoom.FIT_SCALE) / 2f,
        )
    }

    fun panGestureEnabled(scale: Float, maxTranslation: Offset): Boolean =
        maxTranslation != Offset.Zero || scale > ReaderPageZoom.FIT_SCALE

    fun isVerticalDominantPan(delta: Offset): Boolean =
        abs(delta.y) >= abs(delta.x)

    /**
     * #E125: Computes the initial pan translation when double-tapping to zoom in (`targetScale > 1f`).
     * - When the page is a wide double-page spread (`ReaderPageZoom.isSpreadAspect(contentAspectHeightOverWidth)`),
     *   aligns horizontally to the reading-start edge (`-maxTranslation.x` for RTL right-edge start,
     *   `+maxTranslation.x` for LTR left-edge start) unless the user explicitly double-tapped near the
     *   opposite third of the screen.
     * - For normal portrait pages, shifts toward the tapped focal point (`tapOffset` in `[0..viewportWidth, 0..viewportHeight]`)
     *   and clamps within [maxTranslation].
     */
    fun doubleTapTargetTranslation(
        tapOffset: Offset?,
        viewportWidth: Float,
        viewportHeight: Float,
        targetScale: Float,
        rtl: Boolean = false,
        fitWidth: Boolean = false,
        contentAspectHeightOverWidth: Float? = null,
    ): Offset {
        if (targetScale <= ReaderPageZoom.FIT_SCALE || viewportWidth <= 0f || viewportHeight <= 0f) {
            return Offset.Zero
        }
        val max = maxTranslation(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            scale = targetScale,
            fitWidth = fitWidth,
            contentAspectHeightOverWidth = contentAspectHeightOverWidth,
        )
        if (max == Offset.Zero) return Offset.Zero
        if (ReaderPageZoom.isSpreadAspect(contentAspectHeightOverWidth)) {
            val tapX = tapOffset?.x
            val targetX = when {
                tapX != null && tapX < viewportWidth * 0.35f -> max.x
                tapX != null && tapX > viewportWidth * 0.65f -> -max.x
                rtl -> -max.x
                else -> max.x
            }
            return Offset(x = targetX.coerceIn(-max.x, max.x), y = 0f)
        }
        if (tapOffset == null) return Offset.Zero
        val centerX = viewportWidth / 2f
        val centerY = viewportHeight / 2f
        val desiredX = (centerX - tapOffset.x) * (targetScale - 1f)
        val desiredY = (centerY - tapOffset.y) * (targetScale - 1f)
        return Offset(
            x = desiredX.coerceIn(-max.x, max.x),
            y = desiredY.coerceIn(-max.y, max.y),
        )
    }

    /**
     * #E125: Scale and horizontal translation to display a single half (`LEFT_HALF` or `RIGHT_HALF`)
     * of a double-page spread filling the width of the viewport.
     */
    fun spreadSubSlotTransform(
        subSlot: SpreadPageSubSlot,
        viewportWidth: Float,
    ): Pair<Float, Offset> = when (subSlot) {
        SpreadPageSubSlot.FULL -> ReaderPageZoom.FIT_SCALE to Offset.Zero
        SpreadPageSubSlot.LEFT_HALF -> 2f to Offset(x = viewportWidth / 2f, y = 0f)
        SpreadPageSubSlot.RIGHT_HALF -> 2f to Offset(x = -viewportWidth / 2f, y = 0f)
        SpreadPageSubSlot.ROTATED_90 -> ReaderPageZoom.FIT_SCALE to Offset.Zero
    }

    fun resetTranslation(): Offset = Offset.Zero
}
