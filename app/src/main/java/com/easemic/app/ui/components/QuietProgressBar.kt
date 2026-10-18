package com.easemic.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * #E116: Clean rounded capsule progress bar WITHOUT Material3's trailing
 * stop-indicator dot. Preserves `progressSemantics(value)` so existing Compose
 * UI tests (`ProgressBarRangeInfo`) continue to inspect the exact fraction.
 */
@Composable
fun QuietProgressBar(
    progress: () -> Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val clamped = progress().coerceIn(0f, 1f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .progressSemantics(value = clamped),
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = radius,
        )
        if (clamped > 0f) {
            val fillWidth = (size.width * clamped).coerceAtLeast(size.height.coerceAtMost(size.width))
            drawRoundRect(
                color = color,
                topLeft = Offset.Zero,
                size = Size(fillWidth, size.height),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * #E116: Clean indeterminate capsule progress bar for live scans / unknown-page
 * activity rows, preserving `progressSemantics()` (`ProgressBarRangeInfo.Indeterminate`).
 */
@Composable
fun QuietIndeterminateProgressBar(
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val transition = rememberInfiniteTransition(label = "quiet-indeterminate-bar")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "quiet-indeterminate-phase",
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .progressSemantics(),
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(
            color = trackColor,
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = radius,
        )
        val segmentWidth = size.width * 0.36f
        val startX = (size.width + segmentWidth) * phase - segmentWidth
        val clampedStart = startX.coerceIn(0f, size.width)
        val clampedEnd = (startX + segmentWidth).coerceIn(0f, size.width)
        val drawWidth = (clampedEnd - clampedStart).coerceAtLeast(0f)
        if (drawWidth > 0f) {
            drawRoundRect(
                color = color,
                topLeft = Offset(clampedStart, 0f),
                size = Size(drawWidth, size.height),
                cornerRadius = radius,
            )
        }
    }
}
