package com.easemic.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Quiet Shelf YouTube-style shimmer bone for loading placeholders.
 */
@Composable
fun QuietShelfShimmer(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    content: @Composable BoxScope.() -> Unit = {},
) {
    val palette = quietShelfPalette()
    val infinite = rememberInfiniteTransition(label = "quiet-shelf-shimmer")
    val shift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "quiet-shelf-shimmer-shift",
    )
    val brush = Brush.linearGradient(
        colors = listOf(
            palette.line,
            palette.line,
            palette.muted.copy(alpha = 0.22f),
            palette.line,
            palette.line,
        ),
        start = Offset(x = shift * 400f - 200f, y = 0f),
        end = Offset(x = shift * 400f + 200f, y = 200f),
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(brush),
        content = content,
    )
}
