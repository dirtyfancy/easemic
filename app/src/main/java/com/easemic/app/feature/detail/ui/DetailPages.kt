package com.easemic.app.feature.detail.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easemic.app.feature.browse.DetailPreviewBoxHeight
import com.easemic.app.feature.browse.PREVIEW_BOTTOM_PADDING
import com.easemic.app.feature.browse.PreviewNestedScrollBoundary
import com.easemic.app.feature.browse.PreviewPrefetchWindow
import com.easemic.app.feature.browse.DetailAsyncImage
import com.easemic.app.feature.detail.DetailImage
import com.easemic.app.i18n.AppStrings
import com.easemic.app.ui.theme.QuietShelfShimmer
import com.easemic.app.ui.theme.quietShelfPalette
import kotlin.math.roundToInt

/**
 * The gallery content section (skeleton spec §2.1.7, Part 4 Chunk E): the
 * section heading — "Pages 32", 15 sp semibold with its count in muted tabular
 * figures — over today's [DetailPreviewGrid]. The grid keeps its scroll-box
 * height rule (#17 v2); this wrapper adds only the heading, so call sites that
 * render the grid directly (today's `DetailSkin`) are pixel-identical.
 */
@Composable
internal fun DetailPages(
    count: Int,
    previewImages: List<DetailImage?>,
    slotCount: Int,
    scrollState: ScrollState,
    viewportTopPx: Int,
    viewportHeightPx: Int,
    interactive: Boolean = true,
    onPreviewPageClick: (Int) -> Unit,
    onPreviewWarmThrough: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PagesSectionHeading(count)
        DetailPreviewGrid(
            previewImages = previewImages,
            slotCount = slotCount,
            scrollState = scrollState,
            viewportTopPx = viewportTopPx,
            viewportHeightPx = viewportHeightPx,
            interactive = interactive,
            onPreviewPageClick = onPreviewPageClick,
            onPreviewWarmThrough = onPreviewWarmThrough,
        )
    }
}

/** §2.1.7 heading typography: 15 sp semibold (ChapterSectionHeading's) + a muted tabular count. */
@Composable
private fun PagesSectionHeading(count: Int) {
    val palette = quietShelfPalette()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            AppStrings.pagesHeading,
            color = palette.ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            count.toString(),
            color = palette.muted,
            style = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum"),
        )
    }
}

/** The preview grid's own gap: between cells, and between rows. */
private val PREVIEW_CELL_SPACING = 8.dp

@Composable
internal fun DetailPreviewGrid(
    previewImages: List<DetailImage?>,
    slotCount: Int,
    scrollState: ScrollState,
    viewportTopPx: Int,
    viewportHeightPx: Int,
    interactive: Boolean = true,
    onPreviewPageClick: (Int) -> Unit,
    onPreviewWarmThrough: (Int) -> Unit,
) {
    val gridState = rememberLazyGridState()
    // #17 v2: the box scrolls internally again, so warm-through is back on the
    // grid's own layoutInfo — the honest answer to "which rows can the owner
    // see", with no viewport arithmetic to keep in sync.
    val warmThrough by remember(slotCount) {
        derivedStateOf {
            val visible = gridState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) {
                PreviewPrefetchWindow.INITIAL_CELLS.coerceAtMost(slotCount)
            } else {
                val firstRow = visible.minOf { it.row }
                val lastRow = visible.maxOf { it.row }
                PreviewPrefetchWindow.endExclusive(
                    firstVisibleRow = firstRow,
                    visibleRowCount = (lastRow - firstRow + 1).coerceAtLeast(1),
                    totalCells = slotCount,
                )
            }
        }
    }
    LaunchedEffect(warmThrough) {
        onPreviewWarmThrough(warmThrough)
    }

    val density = LocalDensity.current
    // The box's top in scroll-content space. Content space is invariant under
    // scrolling (window y falls by exactly what scrollState.value rises), so
    // this does not churn while the owner scrolls — but it does follow a header
    // that grows later, e.g. a status line appearing. -1 = not placed yet.
    var gridTopPx by remember { mutableIntStateOf(-1) }

    Column(
        verticalArrangement = Arrangement.spacedBy(PREVIEW_CELL_SPACING),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("remote-detail-preview-grid")
            .semantics { contentDescription = "Preview thumbnails" },
    ) {
        Text(
            "PAGES · PREVIEW",
            style = MaterialTheme.typography.labelSmall,
            color = quietShelfPalette().muted,
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    if (viewportHeightPx > 0) {
                        val top = coords.positionInWindow().y.roundToInt() -
                            viewportTopPx + scrollState.value
                        if (top != gridTopPx) gridTopPx = top
                    }
                },
        ) {
            // A row is one cell plus the gap under it, and a cell is a 3/4 slice
            // of a third of the width — all of which BoxWithConstraints knows at
            // composition time. So the clamp floor is real on the very first
            // frame, before anything has been positioned, and the box is never
            // 0-high while the layout settles.
            val boxWidth = maxWidth
            val (rowHeightPx, rowGapPx) = with(density) {
                val spacing = PREVIEW_CELL_SPACING.toPx()
                val cellWidth =
                    (boxWidth.toPx() - spacing * (PreviewPrefetchWindow.COLUMNS - 1)) /
                        PreviewPrefetchWindow.COLUMNS
                (cellWidth * 4f / 3f).roundToInt() to spacing.roundToInt()
            }
            val bottomGapPx = with(density) { PREVIEW_BOTTOM_PADDING.roundToPx() }
            val boxHeightPx = DetailPreviewBoxHeight.heightPx(
                viewportHeightPx = viewportHeightPx,
                contentAboveHeightPx = gridTopPx,
                bottomGapPx = bottomGapPx,
                rowHeightPx = rowHeightPx,
                rowGapPx = rowGapPx,
            )
            val boxHeight = with(density) { boxHeightPx.toDp() }
            // The box keeps the leftover of its own drag only while some header
            // stays above it; a box that fills the viewport hands it to the page
            // so the header can come back (C3 audit HIGH-1).
            val holdsHeader = DetailPreviewBoxHeight.keepsHeaderInReach(
                viewportHeightPx = viewportHeightPx,
                boxHeightPx = boxHeightPx,
                bottomGapPx = bottomGapPx,
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(PreviewPrefetchWindow.COLUMNS),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(PREVIEW_CELL_SPACING),
                verticalArrangement = Arrangement.spacedBy(PREVIEW_CELL_SPACING),
                userScrollEnabled = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(boxHeight)
                    .then(if (holdsHeader) Modifier.nestedScroll(PreviewNestedScrollBoundary) else Modifier)
                    .testTag("remote-detail-preview-scroller"),
            ) {
                items(slotCount, key = { it }) { index ->
                    val image = previewImages.getOrNull(index)
                    val cellDescription = if (image is DetailImage.Missing) {
                        "Preview ${index + 1} unavailable"
                    } else {
                        "Preview ${index + 1}"
                    }
                    Box(
                        modifier = Modifier
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (image == null || !interactive) {
                                    Modifier
                                } else {
                                    Modifier.clickable { onPreviewPageClick(index) }
                                },
                            )
                            .testTag("remote-detail-preview-cell-$index")
                            .semantics { contentDescription = cellDescription },
                    ) {
                        if (image == null) {
                            QuietShelfShimmer(modifier = Modifier.fillMaxSize())
                        } else {
                            DetailAsyncImage(
                                image = image,
                                contentDescription = cellDescription,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
