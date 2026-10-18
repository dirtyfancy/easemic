package com.easemic.app.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr

object ReaderSeekOverlay {
    const val TEST_TAG = "reader-seek-overlay"
    val SLIDER_CONTENT_DESCRIPTION: String get() = AppStrings.readerPageSeek
    val CURRENT_CONTENT_DESCRIPTION: String get() = AppStrings.readerSeekCurrentPage
    val TOTAL_CONTENT_DESCRIPTION: String get() = AppStrings.readerSeekTotalPages
    const val CHAPTER_LABEL_TEST_TAG = "reader-seek-chapter-label"
    const val LAYOUT_MODE_PILL_TEST_TAG = "reader-layout-mode-pill"
    const val AUTO_SCROLL_PILL_TEST_TAG = "reader-auto-scroll-pill"
    const val DIRECTION_PILL_TEST_TAG = "reader-direction-pill"
    const val SPREAD_PILL_TEST_TAG = "reader-spread-pill"
}

/**
 * Absolute bottom overlay: current page · seek · total. Does not participate in
 * page layout; host must place it inside a [Box] over full-bleed pager content.
 */
@Composable
fun BoxScope.ReaderSeekOverlay(
    currentPage0Based: Int,
    pageCount: Int,
    onSeekToPage0Based: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** #C83: right-to-left reading mirrors the whole bar: page 1 at the right end, the total on the left. */
    rtl: Boolean = false,
    /** #E125: optional callback to toggle reading direction (RTL vs LTR) directly inside the reader. */
    onToggleRtl: (() -> Unit)? = null,
    /** Serialized readers identify the current chapter above the page seek controls. */
    chapterLabel: String? = null,
    /** #E114: active layout mode pill ("Auto" / "Strip" / "Paged") + whether strip mode is active. */
    layoutMode: ReaderLayoutMode? = null,
    verticalStripActive: Boolean = false,
    onToggleLayoutMode: (() -> Unit)? = null,
    /** #E114: 5-speed hands-free vertical auto-scroll pill, shown when [verticalStripActive] is true. */
    autoScrollSpeed: AutoScrollSpeed = AutoScrollSpeed.OFF,
    autoScrollTouchPaused: Boolean = false,
    onCycleAutoScrollSpeed: (() -> Unit)? = null,
    /** #E125: when the active page is a wide double-page spread, expose its sub-slot / rotation control. */
    currentPageIsSpread: Boolean = false,
    spreadSubSlot: SpreadPageSubSlot = SpreadPageSubSlot.FULL,
    onCycleSpreadSubSlot: (() -> Unit)? = null,
) {
    val current = ReaderSeekOverlayMath.displayCurrent(currentPage0Based, pageCount)
    val total = ReaderSeekOverlayMath.displayTotal(pageCount)
    val range = ReaderSeekOverlayMath.sliderValueRange(pageCount)
    val sliderValue = ReaderSeekOverlayMath.clampOrdinal0Based(currentPage0Based, pageCount)
        .toFloat()
        .coerceIn(range.start, range.endInclusive)

    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LocalLayoutDirection.current,
    ) {
        Column(
            modifier = modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(ReaderSeekOverlay.TEST_TAG),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val showSpreadPill = (currentPageIsSpread || spreadSubSlot != SpreadPageSubSlot.FULL) &&
                onCycleSpreadSubSlot != null
            val showDirectionPill = !verticalStripActive && onToggleRtl != null
            val hasTopRow = !chapterLabel.isNullOrBlank() || layoutMode != null ||
                (verticalStripActive && onCycleAutoScrollSpeed != null) ||
                showSpreadPill || showDirectionPill
            if (hasTopRow) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!chapterLabel.isNullOrBlank()) {
                        Text(
                            text = chapterLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(ReaderSeekOverlay.CHAPTER_LABEL_TEST_TAG),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (showSpreadPill && onCycleSpreadSubSlot != null) {
                        val spreadLabel = when (spreadSubSlot) {
                            SpreadPageSubSlot.FULL -> AppStrings.spreadFull
                            SpreadPageSubSlot.RIGHT_HALF -> if (rtl) tr("Spread · 1/2 (R)", "双页 · 1/2 (R)", "跨頁 · 1/2 (R)", "見開き · 1/2 (R)") else tr("Spread · 2/2 (R)", "双页 · 2/2 (R)", "跨頁 · 2/2 (R)", "見開き · 2/2 (R)")
                            SpreadPageSubSlot.LEFT_HALF -> if (rtl) tr("Spread · 2/2 (L)", "双页 · 2/2 (L)", "跨頁 · 2/2 (L)", "見開き · 2/2 (L)") else tr("Spread · 1/2 (L)", "双页 · 1/2 (L)", "跨頁 · 1/2 (L)", "見開き · 1/2 (L)")
                            SpreadPageSubSlot.ROTATED_90 -> tr("Spread · 90°", "双页 · 90°", "跨頁 · 90°", "見開き · 90°")
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (spreadSubSlot != SpreadPageSubSlot.FULL) {
                                Color(0xFF3B5998).copy(alpha = 0.88f)
                            } else {
                                Color.White.copy(alpha = 0.20f)
                            },
                            contentColor = Color.White,
                            modifier = Modifier
                                .testTag(ReaderSeekOverlay.SPREAD_PILL_TEST_TAG)
                                .clickable(onClick = onCycleSpreadSubSlot)
                                .semantics {
                                    contentDescription = AppStrings.cycleSpreadView(spreadLabel)
                                },
                        ) {
                            Text(
                                text = spreadLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (showDirectionPill && onToggleRtl != null) {
                        val dirLabel = if (rtl) "RTL" else "LTR"
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (rtl) {
                                Color.White.copy(alpha = 0.24f)
                            } else {
                                Color.White.copy(alpha = 0.14f)
                            },
                            contentColor = Color.White,
                            modifier = Modifier
                                .testTag(ReaderSeekOverlay.DIRECTION_PILL_TEST_TAG)
                                .clickable(onClick = onToggleRtl)
                                .semantics {
                                    contentDescription = AppStrings.toggleReadingDirection(dirLabel)
                                },
                        ) {
                            Text(
                                text = dirLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (verticalStripActive && onCycleAutoScrollSpeed != null) {
                        val speedActive = autoScrollSpeed != AutoScrollSpeed.OFF
                        val pillText = when {
                            !speedActive -> AppStrings.autoScrollOff
                            else -> AppStrings.autoScrollSpeed(autoScrollSpeed.label, autoScrollTouchPaused)
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (speedActive) {
                                Color(0xFF2E6B4E).copy(alpha = 0.88f)
                            } else {
                                Color.White.copy(alpha = 0.16f)
                            },
                            contentColor = Color.White,
                            modifier = Modifier
                                .testTag(ReaderSeekOverlay.AUTO_SCROLL_PILL_TEST_TAG)
                                .clickable(onClick = onCycleAutoScrollSpeed)
                                .semantics {
                                    contentDescription = AppStrings.cycleAutoScrollSpeed(pillText)
                                },
                        ) {
                            Text(
                                text = pillText,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (layoutMode != null && onToggleLayoutMode != null) {
                        val modeLabel = when (layoutMode) {
                            ReaderLayoutMode.AUTO -> AppStrings.readerMode(auto = true, strip = verticalStripActive)
                            ReaderLayoutMode.VERTICAL_STRIP -> AppStrings.readerMode(auto = false, strip = true)
                            ReaderLayoutMode.PAGED -> AppStrings.readerMode(auto = false, strip = false)
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (verticalStripActive) {
                                Color.White.copy(alpha = 0.24f)
                            } else {
                                Color.White.copy(alpha = 0.14f)
                            },
                            contentColor = Color.White,
                            modifier = Modifier
                                .testTag(ReaderSeekOverlay.LAYOUT_MODE_PILL_TEST_TAG)
                                .clickable(onClick = onToggleLayoutMode)
                                .semantics {
                                    contentDescription = AppStrings.switchReadingMode(modeLabel)
                                },
                        ) {
                            Text(
                                text = modeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            Text(
                text = current.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.semantics {
                    contentDescription = ReaderSeekOverlay.CURRENT_CONTENT_DESCRIPTION
                },
            )
            Slider(
                value = sliderValue,
                onValueChange = { value ->
                    onSeekToPage0Based(ReaderSeekOverlayMath.ordinalFromSlider(value, pageCount))
                },
                valueRange = range,
                steps = ReaderSeekOverlayMath.sliderSteps(pageCount),
                enabled = pageCount > 1,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = ReaderSeekOverlay.SLIDER_CONTENT_DESCRIPTION },
            )
            Text(
                text = total.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.semantics {
                    contentDescription = ReaderSeekOverlay.TOTAL_CONTENT_DESCRIPTION
                },
            )
            }
        }
    }
}
