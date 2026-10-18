package com.easemic.app.ui.fastscroll

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.easemic.app.ui.theme.quietShelfPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Small, additive fast-scroll affordance for long lazy lists and grids.
 *
 * The overlay owns no list state. It reads the state supplied by the caller
 * and only issues scrollToItem calls while the user drags the thumb.
 *
 * #C8 re-verification (2026-09-18):
 *  - the THUMB is the only touch target (a 44 dp-wide grab box around it, at
 *    least 44 dp tall); the strip beside it has no pointer input, so a tap or
 *    a flick there reaches the list — while the thumb is shown and after;
 *  - one gesture coroutine for the life of the grab box (`pointerInput(Unit)`,
 *    the latest snapshot/callbacks read through [rememberUpdatedState]) — a
 *    drag survives every layout change it causes and every page a feed loads;
 *  - the thumb moves by the finger's delta from where it was grabbed
 *    ([FastScrollerMath.dragFractionByDelta]) — no jump on the grab;
 *  - a held finger keeps the thumb (and the bubble) — the idle timer only
 *    runs when neither the list nor a drag is in progress;
 *  - the bubble needs a KNOWN total ([FastScrollerMath.showsBubble]) — the
 *    owner's rule for growing lists applies to any feed without one;
 *  - the overlay is in the semantics tree only while shown ("Fast scroller" +
 *    "N / total").
 */
@Composable
fun FastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
    knownTotalItems: Int? = null,
    showPositionBubble: Boolean = false,
    onRequestMore: () -> Unit = {},
) {
    val snapshot = rememberFastScrollerSnapshot(state)
    FastScrollerOverlay(
        snapshot = snapshot,
        knownTotalItems = knownTotalItems,
        showPositionBubble = showPositionBubble,
        modifier = modifier,
        onScrollToIndex = { index -> state.scrollToItem(index) },
        isScrollInProgress = state.isScrollInProgress,
        onRequestMore = onRequestMore,
    )
}

@Composable
fun FastScroller(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    knownTotalItems: Int? = null,
    showPositionBubble: Boolean = false,
    onRequestMore: () -> Unit = {},
) {
    val snapshot = rememberFastScrollerSnapshot(state)
    FastScrollerOverlay(
        snapshot = snapshot,
        knownTotalItems = knownTotalItems,
        showPositionBubble = showPositionBubble,
        modifier = modifier,
        onScrollToIndex = { index -> state.scrollToItem(index) },
        isScrollInProgress = state.isScrollInProgress,
        onRequestMore = onRequestMore,
    )
}

private data class FastScrollerSnapshot(
    val totalItems: Int = 0,
    val visibleItems: Int = 0,
    val firstVisibleIndex: Int = 0,
)

@Composable
private fun rememberFastScrollerSnapshot(state: LazyListState): FastScrollerSnapshot {
    var snapshot by remember { mutableStateOf(FastScrollerSnapshot()) }
    LaunchedEffect(state) {
        snapshotFlow {
            val layout = state.layoutInfo
            FastScrollerSnapshot(
                totalItems = layout.totalItemsCount,
                visibleItems = layout.visibleItemsInfo.size,
                firstVisibleIndex = state.firstVisibleItemIndex,
            )
        }.distinctUntilChanged().collect { snapshot = it }
    }
    return snapshot
}

@Composable
private fun rememberFastScrollerSnapshot(state: LazyGridState): FastScrollerSnapshot {
    var snapshot by remember { mutableStateOf(FastScrollerSnapshot()) }
    LaunchedEffect(state) {
        snapshotFlow {
            val layout = state.layoutInfo
            FastScrollerSnapshot(
                totalItems = layout.totalItemsCount,
                visibleItems = layout.visibleItemsInfo.size,
                firstVisibleIndex = state.firstVisibleItemIndex,
            )
        }.distinctUntilChanged().collect { snapshot = it }
    }
    return snapshot
}

/** At most one scrollToItem in flight: the newest target wins. */
private class ScrollJobHolder {
    private var job: Job? = null

    fun launch(scope: CoroutineScope, block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }
}

private val STRIP_WIDTH = 44.dp
private val GRAB_MIN_HEIGHT = 44.dp
private val THUMB_WIDTH = 6.dp

@Composable
private fun FastScrollerOverlay(
    snapshot: FastScrollerSnapshot,
    knownTotalItems: Int?,
    showPositionBubble: Boolean,
    modifier: Modifier,
    onScrollToIndex: suspend (Int) -> Unit,
    isScrollInProgress: Boolean,
    onRequestMore: () -> Unit,
) {
    if (!FastScrollerMath.shouldShow(snapshot.totalItems, snapshot.visibleItems)) return

    val scrollScope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(isScrollInProgress, dragging) {
        if (isScrollInProgress || dragging) {
            visible = true
        } else {
            delay(FastScrollerMath.AUTO_HIDE_DELAY_MS)
            // A grab on the very frame this timer expires must not tear the
            // gesture node away under the finger (audit M2).
            if (!dragging) visible = false
        }
    }
    // The gesture coroutine lives for the whole visible stretch; it must see
    // the CURRENT list geometry and callbacks, never the ones it was composed
    // with (a pointerInput keyed on them restarted the gesture on the first
    // scrollToItem and dropped the drag — audit BLOCKING).
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestKnownTotal by rememberUpdatedState(knownTotalItems)
    val latestScrollToIndex by rememberUpdatedState(onScrollToIndex)
    val latestRequestMore by rememberUpdatedState(onRequestMore)
    val scrollJob = remember { ScrollJobHolder() }

    val position = FastScrollerMath.positionFraction(
        firstVisibleIndex = snapshot.firstVisibleIndex,
        totalItems = snapshot.totalItems,
        visibleItems = snapshot.visibleItems,
    )
    val thumbSize = FastScrollerMath.thumbSizeFraction(
        totalItems = snapshot.totalItems,
        visibleItems = snapshot.visibleItems,
    )
    val bubble = FastScrollerMath.showsBubble(showPositionBubble, knownTotalItems)
    val label = if (bubble) {
        FastScrollerMath.positionLabel(
            firstVisibleIndex = snapshot.firstVisibleIndex,
            totalItems = knownTotalItems ?: 0,
        )
    } else {
        null
    }
    val palette = quietShelfPalette()
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(STRIP_WIDTH)
            .then(
                if (visible) {
                    Modifier
                        .semantics {
                            contentDescription = "Fast scroller"
                            if (label != null) stateDescription = label
                        }
                        .testTag("fast-scroller")
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.TopEnd,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .alpha(if (visible) 1f else 0f)
                .testTag("fast-scroller-track"),
        ) {
            if (!constraints.hasBoundedHeight) return@BoxWithConstraints
            val density = LocalDensity.current
            val trackHeightPx = constraints.maxHeight.toFloat()
            val thumbPx = trackHeightPx * thumbSize
            val thumbOffsetPx = (trackHeightPx - thumbPx).coerceAtLeast(0f) * position
            val (grabPx, grabOffsetPx) = FastScrollerMath.grabArea(
                thumbOffsetPx = thumbOffsetPx,
                thumbPx = thumbPx,
                trackHeightPx = trackHeightPx,
                minGrabPx = with(density) { GRAB_MIN_HEIGHT.toPx() },
            )
            val latestTrackHeightPx by rememberUpdatedState(trackHeightPx)
            val latestGrabOffsetPx by rememberUpdatedState(grabOffsetPx)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(THUMB_WIDTH)
                    .height(with(density) { thumbPx.toDp() })
                    .offset(y = with(density) { thumbOffsetPx.toDp() })
                    .background(palette.ink.copy(alpha = 0.42f), RoundedCornerShape(999.dp))
                    .testTag("fast-scroller-thumb"),
            )
            if (visible) {
                // The grab box wraps the thumb and moves with it; pointer positions
                // arrive in ITS frame, so they are lifted into the track's frame
                // through the offset it was placed with.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .fillMaxWidth()
                        .height(with(density) { grabPx.toDp() })
                        .offset(y = with(density) { grabOffsetPx.toDp() })
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                dragging = true
                                val start = latestSnapshot
                                val startFraction = FastScrollerMath.positionFraction(
                                    firstVisibleIndex = start.firstVisibleIndex,
                                    totalItems = start.totalItems,
                                    visibleItems = start.visibleItems,
                                )
                                val downTrackY = down.position.y + latestGrabOffsetPx
                                var lastTarget = -1
                                try {
                                    drag(down.id) { change ->
                                        change.consume()
                                        val current = latestSnapshot
                                        val trackY = change.position.y + latestGrabOffsetPx
                                        val fraction = FastScrollerMath.dragFractionByDelta(
                                            startFraction = startFraction,
                                            deltaPx = trackY - downTrackY,
                                            trackHeightPx = latestTrackHeightPx,
                                            thumbFraction = FastScrollerMath.thumbSizeFraction(
                                                totalItems = current.totalItems,
                                                visibleItems = current.visibleItems,
                                            ),
                                        )
                                        // A lazy feed can grow while this gesture is in
                                        // flight. The target is always clamped to items
                                        // that are currently addressable by the list state.
                                        val target = FastScrollerMath.scrollIndex(
                                            dragFraction = fraction,
                                            totalItems = current.totalItems,
                                            visibleItems = current.visibleItems,
                                        )
                                        if (target != lastTarget) {
                                            lastTarget = target
                                            scrollJob.launch(scrollScope) { latestScrollToIndex(target) }
                                            if (
                                                FastScrollerMath.shouldRequestMore(
                                                    targetIndex = target,
                                                    loadedItems = current.totalItems,
                                                    visibleItems = current.visibleItems,
                                                    knownTotalItems = latestKnownTotal,
                                                )
                                            ) {
                                                latestRequestMore()
                                            }
                                        }
                                    }
                                } finally {
                                    dragging = false
                                }
                            }
                        }
                        .testTag("fast-scroller-grab"),
                )
            }
            if (label != null && visible) {
                // Re-audit H1: "N / total" never fits the 44 dp strip — measured
                // unbounded, the pill keeps its right edge in the strip and hangs
                // out to the LEFT over the list, on one line.
                Surface(
                    color = palette.ink,
                    contentColor = palette.card,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 10.dp)
                        .wrapContentWidth(align = Alignment.End, unbounded = true)
                        .testTag("fast-scroller-position-bubble"),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

object FastScrollerMath {
    const val MIN_THUMB_FRACTION = 0.08f
    const val AUTO_HIDE_DELAY_MS = 1_500L
    const val DEFAULT_LONG_LIST_THRESHOLD = 60

    fun shouldShow(
        totalItems: Int,
        visibleItems: Int,
        threshold: Int = DEFAULT_LONG_LIST_THRESHOLD,
    ): Boolean =
        totalItems >= threshold &&
            totalItems > 0 &&
            visibleItems > 0 &&
            visibleItems < totalItems

    fun thumbSizeFraction(totalItems: Int, visibleItems: Int): Float {
        if (totalItems <= 0 || visibleItems <= 0 || visibleItems >= totalItems) return 1f
        return (visibleItems.toFloat() / totalItems.toFloat())
            .coerceIn(MIN_THUMB_FRACTION, 1f)
    }

    fun positionFraction(
        firstVisibleIndex: Int,
        totalItems: Int,
        visibleItems: Int,
    ): Float {
        val maxFirstIndex = (totalItems - visibleItems).coerceAtLeast(0)
        if (maxFirstIndex == 0) return 0f
        return (firstVisibleIndex.toFloat() / maxFirstIndex.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * The thumb moves by the finger's delta from where it was grabbed: the
     * thumb spans `thumbFraction` of the track and travels the rest, so a
     * delta of one travel is the whole list. No jump on the grab.
     */
    fun dragFractionByDelta(
        startFraction: Float,
        deltaPx: Float,
        trackHeightPx: Float,
        thumbFraction: Float,
    ): Float {
        val travel = trackHeightPx * (1f - thumbFraction.coerceIn(0f, 1f))
        if (travel <= 0f) return startFraction.coerceIn(0f, 1f)
        return (startFraction + deltaPx / travel).coerceIn(0f, 1f)
    }

    /**
     * The touch target around the thumb: the thumb itself, grown to at least
     * [minGrabPx] around its centre, kept inside the track. Returns
     * `(heightPx, offsetPx)`.
     */
    fun grabArea(
        thumbOffsetPx: Float,
        thumbPx: Float,
        trackHeightPx: Float,
        minGrabPx: Float,
    ): Pair<Float, Float> {
        val grabPx = maxOf(thumbPx, minGrabPx).coerceAtMost(trackHeightPx.coerceAtLeast(0f))
        val offset = (thumbOffsetPx - (grabPx - thumbPx) / 2f)
            .coerceIn(0f, (trackHeightPx - grabPx).coerceAtLeast(0f))
        return grabPx to offset
    }

    fun targetIndex(
        dragFraction: Float,
        totalItems: Int,
        visibleItems: Int,
    ): Int {
        val maxFirstIndex = (totalItems - visibleItems).coerceAtLeast(0)
        return (dragFraction.coerceIn(0f, 1f) * maxFirstIndex)
            .roundToInt()
            .coerceIn(0, maxFirstIndex)
    }

    /**
     * The index a drag scrolls to: [targetIndex] everywhere except at the very
     * end of the rail, which asks for the LAST item. With uneven rows
     * `total − visible` (measured where the finger started) can stop a few rows
     * short of the end; `scrollToItem(last)` clamps at the end on its own.
     */
    fun scrollIndex(
        dragFraction: Float,
        totalItems: Int,
        visibleItems: Int,
    ): Int {
        if (totalItems <= 0) return 0
        if (dragFraction >= 1f) return totalItems - 1
        return targetIndex(dragFraction, totalItems, visibleItems)
    }

    /**
     * Owner ruling (2026-09-10): a bubble only where the total is constant.
     * The caller's source rule says WHERE a bubble is wanted; this says whether
     * one can be honest — an OPDS feed without `opensearch:totalResults` grows
     * like E-Hentai does, and a loaded count is not a total.
     */
    fun showsBubble(showPositionBubble: Boolean, knownTotalItems: Int?): Boolean =
        showPositionBubble && knownTotalItems != null

    /** A drag that lands on the loaded edge asks for more while more can exist. */
    fun shouldRequestMore(
        targetIndex: Int,
        loadedItems: Int,
        visibleItems: Int,
        knownTotalItems: Int?,
    ): Boolean {
        val edge = (loadedItems - visibleItems).coerceAtLeast(0)
        if (targetIndex < edge) return false
        return knownTotalItems == null || knownTotalItems > loadedItems
    }

    fun positionLabel(
        firstVisibleIndex: Int,
        totalItems: Int,
    ): String {
        val denominator = totalItems.coerceAtLeast(0)
        val numerator = if (denominator == 0) 0 else {
            (firstVisibleIndex + 1).coerceIn(1, denominator)
        }
        return "${formatCount(numerator)} / ${formatCount(denominator)}"
    }

    private fun formatCount(value: Int): String = "%,d".format(value)
}
