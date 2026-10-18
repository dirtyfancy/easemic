package com.easemic.app.feature.reader

import com.easemic.app.i18n.AppStrings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * #E114: Floating bottom-right auto-scroll status & speed control pill for vertical strip mode.
 * When [speed] is active (`!= OFF`), stays subtly visible at the bottom-right so the user can
 * see the current speed (`1.0×` or `1.0× · Hold` while their finger is touching the screen),
 * tap to cycle `0.5× → 1.0× → 1.5× → 2.0× → 3.0×`, or tap `✕` to stop auto-scroll.
 */
@Composable
fun VerticalStripAutoScrollFloatingPill(
    speed: AutoScrollSpeed,
    touchPaused: Boolean,
    onCycleSpeed: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (speed == AutoScrollSpeed.OFF) return
    val statusText = AppStrings.autoScrollSpeed(speed.label, touchPaused)
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color(0xFF1A1918).copy(alpha = 0.86f),
        contentColor = Color.White,
        modifier = modifier
            .testTag("reader-floating-auto-scroll-pill")
            .semantics { contentDescription = AppStrings.autoScrollActive(statusText) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = if (touchPaused) Color.White.copy(alpha = 0.72f) else Color(0xFF6CD9A6),
                modifier = Modifier
                    .testTag("reader-floating-auto-scroll-cycle")
                    .clickable(onClick = onCycleSpeed),
            )
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier
                    .testTag("reader-floating-auto-scroll-stop")
                    .clickable(onClick = onStop)
                    .semantics { contentDescription = AppStrings.stopAutoScroll },
            )
        }
    }
}

/**
 * #E114: Compact chapter boundary banner rendered at the top/bottom of a vertical strip chapter.
 */
@Composable
fun VerticalStripChapterBanner(
    text: String,
    actionLabel: String?,
    testTag: String,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF141312))
            .clickable(enabled = actionLabel != null, onClick = onActivate)
            .padding(horizontal = 20.dp, vertical = 28.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
            )
            if (actionLabel != null) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
