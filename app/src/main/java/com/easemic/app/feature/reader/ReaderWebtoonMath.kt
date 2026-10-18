package com.easemic.app.feature.reader

import com.easemic.app.ui.PreferenceMap
import kotlin.math.abs

/**
 * #E114: Reader layout mode.
 * - [AUTO]: standard manga/doujinshi uses paged mode; tall webtoon strips (`height / width >= 2.15f`)
 *   automatically switch to seamless vertical strip mode with `ContentScale.FillWidth`.
 * - [PAGED]: always horizontal pager.
 * - [VERTICAL_STRIP]: always seamless vertical strip mode with width-fill.
 */
enum class ReaderLayoutMode(val label: String, val shortLabel: String) {
    AUTO(label = "Auto (webtoon aware)", shortLabel = "Auto"),
    PAGED(label = "Paged", shortLabel = "Paged"),
    VERTICAL_STRIP(label = "Vertical strip", shortLabel = "Strip"),
    ;

    fun next(): ReaderLayoutMode = when (this) {
        AUTO -> VERTICAL_STRIP
        VERTICAL_STRIP -> PAGED
        PAGED -> AUTO
    }
}

/**
 * #E114: 5-speed hands-free vertical auto-scroll inspired by Tencent Comic's `AutoScrollManager`.
 * Pauses immediately on `ACTION_DOWN` and resumes on `ACTION_UP`.
 */
enum class AutoScrollSpeed(val label: String, val dpPerSecond: Float) {
    OFF(label = "Auto", dpPerSecond = 0f),
    SPEED_0_5X(label = "0.5×", dpPerSecond = 48f),
    SPEED_1_0X(label = "1.0×", dpPerSecond = 96f),
    SPEED_1_5X(label = "1.5×", dpPerSecond = 144f),
    SPEED_2_0X(label = "2.0×", dpPerSecond = 192f),
    SPEED_3_0X(label = "3.0×", dpPerSecond = 288f),
    ;

    fun next(): AutoScrollSpeed = when (this) {
        OFF -> SPEED_0_5X
        SPEED_0_5X -> SPEED_1_0X
        SPEED_1_0X -> SPEED_1_5X
        SPEED_1_5X -> SPEED_2_0X
        SPEED_2_0X -> SPEED_3_0X
        SPEED_3_0X -> OFF
    }
}

data class StripVisibleItem(
    val pageOrdinal: Int,
    val offsetPx: Int,
    val sizePx: Int,
)

object ReaderWebtoonMath {
    /**
     * A page whose height is at least 2.15× its width is a vertical webtoon strip
     * (standard manga pages are ~1.40–1.55; Korean/Chinese webtoons are 2.5–12.0+).
     */
    const val TALL_STRIP_ASPECT_THRESHOLD = 2.15f

    /** Default aspect ratio placeholder before a strip page's intrinsic size is known. */
    const val DEFAULT_STRIP_PLACEHOLDER_ASPECT = 1.45f

    fun isTallStripAspect(heightOverWidth: Float?): Boolean =
        heightOverWidth != null && heightOverWidth >= TALL_STRIP_ASPECT_THRESHOLD

    fun isTallStripDimensions(widthPx: Int, heightPx: Int): Boolean =
        widthPx > 0 && heightPx > 0 &&
            (heightPx.toFloat() / widthPx.toFloat()) >= TALL_STRIP_ASPECT_THRESHOLD

    fun shouldUseVerticalStrip(
        mode: ReaderLayoutMode,
        autoDetectedTallStrip: Boolean,
    ): Boolean = when (mode) {
        ReaderLayoutMode.PAGED -> false
        ReaderLayoutMode.VERTICAL_STRIP -> true
        ReaderLayoutMode.AUTO -> autoDetectedTallStrip
    }

    /**
     * #E114 (Tencent `ComicReaderScrollListener.f`): in vertical strip mode, tall webtoon
     * slices have wildly varying heights (from 400px banners to 8000px strips). Taking
     * `firstVisibleItemIndex` causes the page indicator to jump or lag behind what the user
     * is looking at. Instead, pick the visible page item that spans the vertical center of
     * the viewport (`viewportHeightPx / 2`), or the item whose center is closest to the
     * viewport center.
     */
    fun centerLinePageOrdinal(
        visibleItems: List<StripVisibleItem>,
        viewportHeightPx: Int,
        fallbackOrdinal: Int,
    ): Int {
        if (visibleItems.isEmpty() || viewportHeightPx <= 0) return fallbackOrdinal
        val centerY = viewportHeightPx / 2
        val spanning = visibleItems.firstOrNull { item ->
            val top = item.offsetPx
            val bottom = item.offsetPx + item.sizePx
            centerY in top..bottom
        }
        if (spanning != null) return spanning.pageOrdinal
        return visibleItems.minByOrNull { item ->
            val itemCenter = item.offsetPx + item.sizePx / 2
            abs(itemCenter - centerY)
        }?.pageOrdinal ?: fallbackOrdinal
    }

    /**
     * Computes the per-frame vertical scroll delta in pixels for [AutoScrollSpeed],
     * returning `0f` immediately while [touchPaused] is true.
     */
    fun autoScrollDeltaPx(
        speed: AutoScrollSpeed,
        touchPaused: Boolean,
        density: Float,
        frameMillis: Long,
    ): Float {
        if (touchPaused || speed == AutoScrollSpeed.OFF || density <= 0f || frameMillis <= 0L) {
            return 0f
        }
        val clampedMillis = frameMillis.coerceAtMost(64L)
        return speed.dpPerSecond * density * (clampedMillis.toFloat() / 1000f)
    }

    /**
     * #E114 (Tencent `ClassifyHeaderView` / `ClassifyFragment.D5` pattern):
     * When the user scrolls down the Browse feed (`firstVisibleItemIndex > 0` or
     * `firstVisibleItemScrollOffset >= 120`), multi-row active filter chips collapse into a
     * compact single-line summary bar to maximize gallery viewport height.
     */
    fun shouldCollapseBrowseFilters(
        hasActiveFilters: Boolean,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ): Boolean {
        if (!hasActiveFilters) return false
        return firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset >= 120
    }
}

class ReaderLayoutModeStore(
    private val prefs: PreferenceMap,
) {
    fun read(): ReaderLayoutMode {
        val raw = prefs.getString(KEY, null) ?: return ReaderLayoutMode.AUTO
        return ReaderLayoutMode.entries.firstOrNull { it.name == raw } ?: ReaderLayoutMode.AUTO
    }

    fun write(mode: ReaderLayoutMode) {
        prefs.putString(KEY, mode.name)
    }

    companion object {
        const val KEY = "reader.layout_mode.v1"
    }
}
