package com.easemic.app.feature.browse

/**
 * How tall the detail screen's preview box is, per the owner's rule.
 *
 * Measure everything else on the page (cover, title, actions, helper text, page
 * count, tag area). Then it is an if/else against the screen:
 *
 *  - fits: the box grows to swallow the leftover space, so the page needs no
 *    outer scroll and the box's bottom edge sits [DEFAULT_BOTTOM_GAP_DP] above
 *    the bottom tab bar;
 *  - overflows (the usual case once a subject carries many tags, unless the
 *    user is on a tablet): the box falls back to a floor of [MIN_ROWS] full
 *    preview rows, and the page scrolls to reach the rest of the header. At the
 *    end of that scroll the box's bottom edge is again the same gap above the
 *    tab bar.
 *
 * The branch has nothing to do with whether tags exist per se, only with
 * whether the sum of every element exceeds the viewport.
 *
 * Either way the box ends up with a bounded height, which is what makes its own
 * drag get consumed internally and keeps the header above it from moving.
 *
 * C3 audit HIGH-1 (2026-09-18): a viewport shorter than the floor plus the gap
 * (a split-screen pane, a landscape tablet — the fixed three-column grid makes
 * a WIDER window's floor TALLER) used to get a box taller than the screen, and
 * with the nested-scroll boundary swallowing every drag inside it the user was
 * trapped: no header left on screen to drag, no way back to Back/Read. Two
 * guards: the floor is clamped to `viewport − gap` ("始终有高度上限" — the
 * screen is the cap), and the boundary applies only while [keepsHeaderInReach].
 */
object DetailPreviewBoxHeight {
    /** Preview rows kept visible even when the header alone overflows the screen. */
    const val MIN_ROWS = 2

    /** Constant distance from the box's bottom edge to the bottom tab bar. */
    const val DEFAULT_BOTTOM_GAP_DP = 12

    /** Positive last-resort height when neither viewport nor rows are measured. */
    private const val UNMEASURED_FALLBACK_PX = 1

    /** Height of [minRows] full rows including the gaps between them. */
    fun floorPx(
        rowHeightPx: Int,
        rowGapPx: Int,
        minRows: Int = MIN_ROWS,
    ): Int {
        val rows = minRows.coerceAtLeast(1)
        val row = rowHeightPx.coerceAtLeast(0)
        val gap = rowGapPx.coerceAtLeast(0)
        return row * rows + gap * (rows - 1)
    }

    /**
     * Bounded height for the preview box.
     *
     * @param viewportHeightPx the screen's content area, tab bar already excluded.
     * @param contentAboveHeightPx everything on the page above the box, tags included.
     * @param bottomGapPx the constant gap kept below the box.
     * @return a strictly positive height; an unmeasured state falls back to the
     *   row floor, and then to [UNMEASURED_FALLBACK_PX] if rows are unmeasured too.
     */
    fun heightPx(
        viewportHeightPx: Int,
        contentAboveHeightPx: Int,
        bottomGapPx: Int,
        rowHeightPx: Int,
        rowGapPx: Int,
        minRows: Int = MIN_ROWS,
        unmeasuredFallbackPx: Int = UNMEASURED_FALLBACK_PX,
    ): Int {
        val floor = floorPx(rowHeightPx = rowHeightPx, rowGapPx = rowGapPx, minRows = minRows)
        val safeFloor = if (floor > 0) floor else unmeasuredFallbackPx.coerceAtLeast(1)
        val viewport = viewportHeightPx.coerceAtLeast(0)
        if (viewport <= 0) return safeFloor
        if (contentAboveHeightPx < 0) return safeFloor
        val above = contentAboveHeightPx
        val gap = bottomGapPx.coerceAtLeast(0)
        // The if/else: leftover room in the viewport once everything else is placed.
        val leftover = viewport - above - gap
        val chosen = if (leftover > safeFloor) leftover else safeFloor
        // Never taller than the screen minus the gap (C3 audit HIGH-1); a viewport
        // too short even for that keeps the positive fallback.
        val cap = viewport - gap
        return if (cap >= 1) minOf(chosen, cap) else chosen
    }

    /**
     * Whether a box of [boxHeightPx] leaves any header above it inside a
     * [viewportHeightPx] viewport — the condition under which the preview
     * grid may keep the leftover of its own drag (the owner's "sections above
     * never move"). False when the box fills the viewport: then there is
     * nothing above it to hold still, and swallowing the leftover would only
     * trap the user (C3 audit HIGH-1). An unmeasured viewport keeps the hold.
     */
    fun keepsHeaderInReach(
        viewportHeightPx: Int,
        boxHeightPx: Int,
        bottomGapPx: Int,
    ): Boolean {
        if (viewportHeightPx <= 0) return true
        return boxHeightPx + bottomGapPx.coerceAtLeast(0) < viewportHeightPx
    }
}
