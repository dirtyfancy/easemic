package com.easemic.app.feature.bridge

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Cloudflare brand tokens, locked by the Orange Field spec §2. Nominative use:
 * the orange + a simplified custom cloud label the service the Connect button
 * talks to; no Cloudflare bitmap assets ship in the app.
 */
object CloudflareBrand {
    val Orange = Color(0xFFF6821F)
    val OrangeSoft = Color(0xFFFBAD41)

    /** CTA label ink on the orange fill — measured contrast 6.6:1 (never white). */
    val CtaInk = Color(0xFF241B0E)

    val FieldTopLight = Color(0xFFFDEEDC)
    val FieldBottomLight = Color(0xFFF7E8D2)
    val FieldTopDark = Color(0xFF2A2118)
    val FieldBottomDark = Color(0xFF201A12)
}

object BunnyBrand {
    val Orange = Color(0xFFFF6600)
    val Amber = Color(0xFFFF9933)
}

object BackblazeBrand {
    val Red = Color(0xFFD32027)
    val FlameInner = Color(0xFFFF5A36)
}

/**
 * Simplified two-tone cloud: soft under-lobe peeking out lower-left, orange
 * main body. Drawn as vector paths so it scales from 16dp status chrome to the
 * unbound hero without shipping bitmaps.
 */
fun cloudflareCloudMark(): ImageVector =
    ImageVector.Builder(
        name = "CloudflareCloudMark",
        defaultWidth = 96.dp,
        defaultHeight = 64.dp,
        viewportWidth = 96f,
        viewportHeight = 64f,
    ).apply {
        // Under-lobe (soft orange), lower-left.
        path(fill = SolidColor(CloudflareBrand.OrangeSoft)) {
            moveTo(32f, 52f)
            lineTo(11f, 52f)
            curveTo(5.5f, 52f, 1f, 47.5f, 1f, 42f)
            curveTo(1f, 37f, 4.7f, 33f, 9.5f, 32.2f)
            curveTo(10.4f, 25.3f, 16.3f, 20f, 23.5f, 20f)
            curveTo(28.4f, 20f, 32.8f, 22.5f, 35.3f, 26.4f)
            lineTo(32f, 52f)
            close()
        }
        // Main cloud body (Cloudflare orange).
        path(fill = SolidColor(CloudflareBrand.Orange)) {
            moveTo(84f, 52f)
            lineTo(27f, 52f)
            curveTo(19.8f, 52f, 14f, 46.2f, 14f, 39f)
            curveTo(14f, 32.6f, 18.6f, 27.3f, 24.7f, 26.2f)
            curveTo(26.3f, 17f, 34.3f, 10f, 44f, 10f)
            curveTo(52f, 10f, 58.9f, 14.8f, 61.9f, 21.7f)
            curveTo(63.3f, 21.2f, 64.9f, 21f, 66.5f, 21f)
            curveTo(73.7f, 21f, 79.6f, 26.5f, 80.2f, 33.5f)
            curveTo(86.1f, 34.2f, 90.5f, 39.1f, 90.5f, 45f)
            curveTo(90.5f, 48.9f, 87.6f, 52f, 84f, 52f)
            close()
        }
    }.build()

/**
 * Bunny.net geometric leaping-hare head silhouette (orange ear + amber back ear + sleek snout).
 */
fun bunnyNetMark(): ImageVector =
    ImageVector.Builder(
        name = "BunnyNetMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Back ear (warm amber)
        path(fill = SolidColor(BunnyBrand.Amber)) {
            moveTo(9.2f, 11.2f)
            lineTo(5.2f, 3.6f)
            curveTo(4.7f, 2.6f, 5.7f, 1.6f, 6.7f, 2.2f)
            lineTo(13.6f, 7.8f)
            close()
        }
        // Main swept ear + geometric bunny head + neck (Bunny orange)
        path(fill = SolidColor(BunnyBrand.Orange)) {
            moveTo(12.2f, 9.0f)
            lineTo(7.8f, 2.4f)
            curveTo(7.3f, 1.6f, 8.3f, 0.9f, 9.2f, 1.5f)
            lineTo(16.8f, 7.6f)
            curveTo(19.6f, 9.4f, 21.5f, 11.8f, 21.5f, 14.2f)
            curveTo(21.5f, 16.5f, 19.4f, 18.0f, 16.5f, 18.2f)
            lineTo(13.4f, 22.0f)
            lineTo(8.0f, 22.0f)
            lineTo(10.5f, 15.6f)
            curveTo(8.8f, 14.5f, 8.5f, 11.8f, 12.2f, 9.0f)
            close()
        }
        // Eye cutout
        path(fill = SolidColor(Color.White)) {
            moveTo(16.6f, 12.6f)
            arcToRelative(1.15f, 1.15f, 0f, true, true, -2.3f, 0f)
            arcToRelative(1.15f, 1.15f, 0f, true, true, 2.3f, 0f)
            close()
        }
    }.build()

/**
 * Backblaze B2 twin-crest flame mark (crimson outer flame + bright inner core).
 */
fun backblazeB2Mark(): ImageVector =
    ImageVector.Builder(
        name = "BackblazeB2Mark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // Outer crimson flame
        path(fill = SolidColor(BackblazeBrand.Red)) {
            moveTo(12.4f, 1.8f)
            curveTo(12.4f, 1.8f, 19.2f, 6.8f, 19.2f, 13.5f)
            curveTo(19.2f, 18.2f, 15.8f, 22.0f, 11.6f, 22.0f)
            curveTo(7.4f, 22.0f, 4.4f, 18.7f, 4.4f, 14.5f)
            curveTo(4.4f, 10.9f, 6.9f, 7.8f, 9.1f, 6.2f)
            curveTo(8.5f, 8.6f, 9.6f, 10.5f, 11.2f, 11.2f)
            curveTo(10.1f, 7.5f, 11.1f, 4.1f, 12.4f, 1.8f)
            close()
        }
        // Inner bright flame core
        path(fill = SolidColor(BackblazeBrand.FlameInner)) {
            moveTo(12.2f, 9.6f)
            curveTo(12.2f, 9.6f, 15.8f, 12.4f, 15.8f, 16.0f)
            curveTo(15.8f, 18.5f, 13.9f, 20.4f, 11.6f, 20.4f)
            curveTo(9.3f, 20.4f, 7.8f, 18.6f, 7.8f, 16.3f)
            curveTo(7.8f, 14.0f, 9.8f, 11.6f, 12.2f, 9.6f)
            close()
        }
    }.build()

/**
 * Isometric 3D object-storage cube mark for custom S3 endpoints.
 */
fun s3CubeMark(ink: Color): ImageVector =
    ImageVector.Builder(
        name = "S3CubeMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        val stroke = SolidColor(ink)
        path(
            stroke = stroke,
            strokeLineWidth = 1.75f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // Outer hexagon
            moveTo(12f, 2.5f)
            lineTo(20.5f, 7.25f)
            lineTo(20.5f, 16.75f)
            lineTo(12f, 21.5f)
            lineTo(3.5f, 16.75f)
            lineTo(3.5f, 7.25f)
            close()
            // Inner Y edges
            moveTo(3.5f, 7.25f)
            lineTo(12f, 12f)
            lineTo(20.5f, 7.25f)
            moveTo(12f, 12f)
            lineTo(12f, 21.5f)
        }
    }.build()

/**
 * Subtle help / info circle icon for the "Which storage should I choose?" FAQ trigger.
 */
fun storageHelpCircleMark(tint: Color): ImageVector =
    ImageVector.Builder(
        name = "StorageHelpCircleMark",
        defaultWidth = 18.dp,
        defaultHeight = 18.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        val stroke = SolidColor(tint)
        path(
            stroke = stroke,
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(21f, 12f)
            arcToRelative(9f, 9f, 0f, true, true, -18f, 0f)
            arcToRelative(9f, 9f, 0f, true, true, 18f, 0f)
            close()
            moveTo(9.4f, 9.2f)
            curveTo(9.8f, 7.7f, 11.4f, 7.0f, 12.8f, 7.4f)
            curveTo(14.1f, 7.8f, 14.9f, 9.1f, 14.4f, 10.4f)
            curveTo(14.0f, 11.4f, 12.6f, 11.9f, 12.1f, 12.8f)
            curveTo(12.0f, 13.1f, 12.0f, 13.5f, 12.0f, 13.8f)
        }
        path(fill = stroke) {
            moveTo(12f, 17.1f)
            arcToRelative(1.05f, 1.05f, 0f, true, true, 0f, -2.1f)
            arcToRelative(1.05f, 1.05f, 0f, true, true, 0f, 2.1f)
            close()
        }
    }.build()
