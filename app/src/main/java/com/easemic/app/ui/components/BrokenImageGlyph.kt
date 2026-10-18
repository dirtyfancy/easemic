package com.easemic.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.easemic.app.ui.theme.quietShelfPalette
import com.easemic.app.i18n.AppStrings

/**
 * A62 (spec D1): the ONE broken-image glyph, shared by the local reader's corrupted-page
 * recovery block, the online/cloud readers' load failures, and every shelf cover error.
 *
 * Geometry is the owner-approved design (`docs/superpowers/specs/a60-broken-page-design.html`):
 * a partial Lucide-lineage picture frame with its mountain/sun remnant in
 * [com.easemic.app.ui.theme.QuietShelfPalette.statusWarn], and a
 * crack-lightning filled shape in statusBad across the frame. 3.5 round-cap stroke on the
 * 88×88 design viewport, the same hand-authored vector approach as the shelf's badge
 * icons (black source colour is impossible here — the two-tone palette IS the design).
 * No words: the optional status digits ("429") render as mono digits UNDER the glyph, and
 * the only text is the invisible contentDescription for screen-reader honesty.
 */
@Composable
fun BrokenImageGlyph(
    size: Dp,
    contentDescription: String,
    modifier: Modifier = Modifier,
    statusCode: String? = null,
) {
    val palette = quietShelfPalette()
    val vector = remember(palette) {
        brokenImageGlyphVector(
            statusWarn = palette.statusWarn,
            statusBad = palette.statusBad,
        )
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Image(
            imageVector = vector,
            contentDescription = contentDescription,
            modifier = Modifier.size(size),
        )
        if (statusCode != null) {
            Text(
                text = statusCode,
                fontFamily = FontFamily.Monospace,
                color = palette.statusBad,
            )
        }
    }
}

/**
 * A62 (spec D4): the same glyph at shelf-tile scale, in place of a cover that failed to
 * load. No status digits (a tile has no transport), no retry (recovery lives in Detail /
 * the reader; a stale URL self-heals on the next shelf epoch or source re-fetch).
 */
@Composable
fun BrokenCoverPlaceholder(
    modifier: Modifier = Modifier,
    glyphSize: Dp = 48.dp,
    /**
     * A62 (device-run finding): a failed cover must still identify ITS comic to
     * screen readers — the default "Cover failed to load" names no comic, so a
     * TalkBack user browsing the shelf cannot tell which row is broken.
     * Library cover call sites pass "Cover for <title>, <N pages>", matching
     * the AsyncImage description the tile would have carried.
     */
    contentDescription: String = AppStrings.coverFailedToLoad,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        BrokenImageGlyph(
            size = glyphSize,
            contentDescription = contentDescription,
        )
    }
}

private fun brokenImageGlyphVector(statusWarn: Color, statusBad: Color): ImageVector =
    ImageVector.Builder(
        name = "BrokenImageGlyph",
        defaultWidth = 88.dp,
        defaultHeight = 88.dp,
        viewportWidth = 88f,
        viewportHeight = 88f,
    ).apply {
        // Frame: top edge and right side intact.
        path(
            stroke = SolidColor(statusWarn),
            strokeAlpha = 1f,
            strokeLineWidth = 3.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(22f, 24f)
            horizontalLineToRelative(44f)
            curveToRelative(2.209f, 0f, 4f, 1.791f, 4f, 4f)
            verticalLineToRelative(28f)
        }
        // Frame: left edge, broken away from the bottom (the picture is coming apart).
        path(
            stroke = SolidColor(statusWarn),
            strokeAlpha = 0.55f,
            strokeLineWidth = 3.5f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(22f, 56f)
            verticalLineToRelative(-28f)
        }
        // Mountain remnant.
        path(
            stroke = SolidColor(statusWarn),
            strokeAlpha = 0.9f,
            strokeLineWidth = 3.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(30f, 52f)
            lineToRelative(8f, -11f)
            lineToRelative(6f, 7f)
        }
        // Sun remnant (circle as two half arcs).
        path(
            stroke = SolidColor(statusWarn),
            strokeAlpha = 0.55f,
            strokeLineWidth = 3.5f,
        ) {
            addNamedCircle(52f, 35f, 4f)
        }
        // The crack: a filled lightning shape across the picture.
        path(
            fill = SolidColor(statusBad),
            fillAlpha = 0.92f,
        ) {
            moveTo(58f, 22f)
            lineToRelative(-9f, 14f)
            horizontalLineToRelative(7f)
            lineToRelative(-8f, 13f)
            lineToRelative(17f, -15f)
            horizontalLineToRelative(-7f)
            lineToRelative(7f, -12f)
            close()
        }
    }.build()

/** A stroked circle on the PathBuilder via two half-arcs (PathBuilder has no circle op). */
private fun androidx.compose.ui.graphics.vector.PathBuilder.addNamedCircle(
    centerX: Float,
    centerY: Float,
    radius: Float,
) {
    moveTo(centerX - radius, centerY)
    arcTo(
        horizontalEllipseRadius = radius,
        verticalEllipseRadius = radius,
        theta = 0f,
        isMoreThanHalf = true,
        isPositiveArc = true,
        x1 = centerX + radius,
        y1 = centerY,
    )
    arcTo(
        horizontalEllipseRadius = radius,
        verticalEllipseRadius = radius,
        theta = 0f,
        isMoreThanHalf = true,
        isPositiveArc = true,
        x1 = centerX - radius,
        y1 = centerY,
    )
    close()
}
