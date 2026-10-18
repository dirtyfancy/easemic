package com.easemic.app.ui.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * A42: the shelf's badge vocabulary as real Lucide [ImageVector]s, replacing the
 * Canvas placeholder glyphs the row used to draw. Authored in the same style as
 * `DetailActionSwitches.kt` (24×24 viewport, 2 px stroke, round cap/join, black
 * source colour that the call site tints) so the shelf row and the Detail page
 * speak one language instead of two.
 *
 * [lucideArrowBigDown] is deliberately shared with the Detail page — owner ruling:
 * the thin arrow-plus-tray did not match the heart/cloud silhouette weight, and
 * both surfaces must carry the same glyph.
 */
private fun badgeVector(
    name: String,
    block: ImageVector.Builder.() -> Unit,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

/** Lucide `heart`. Solid = filled silhouette, hollow = outline stroke. */
internal fun lucideHeart(filled: Boolean): ImageVector =
    badgeVector(if (filled) "LucideHeartSolid" else "LucideHeartHollow") {
        path(
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = if (filled) null else SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(19f, 14f)
            curveTo(20.49f, 12.54f, 22f, 10.79f, 22f, 8.5f)
            curveTo(22f, 7.04f, 21.53f, 5.59f, 20.62f, 4.44f)
            curveTo(19.7f, 3.29f, 18.42f, 2.53f, 17f, 2.27f)
            curveTo(15.58f, 2.01f, 14.11f, 2.27f, 12.86f, 3.01f)
            curveTo(12.61f, 3.16f, 12.37f, 3.33f, 12.14f, 3.52f)
            curveTo(12.09f, 3.56f, 12.05f, 3.6f, 12f, 3.64f)
            curveTo(11.95f, 3.6f, 11.91f, 3.56f, 11.86f, 3.52f)
            curveTo(11.63f, 3.33f, 11.39f, 3.16f, 11.14f, 3.01f)
            curveTo(9.89f, 2.27f, 8.42f, 2.01f, 7f, 2.27f)
            curveTo(5.58f, 2.53f, 4.3f, 3.29f, 3.38f, 4.44f)
            curveTo(2.47f, 5.59f, 2f, 7.04f, 2f, 8.5f)
            curveTo(2f, 10.79f, 3.51f, 12.54f, 5f, 14f)
            lineTo(12f, 21f)
            close()
        }
    }

/** Lucide `cloud`. Solid = in the cloud, hollow = queued for upload. */
internal fun lucideCloud(filled: Boolean): ImageVector =
    badgeVector(if (filled) "LucideCloudSolid" else "LucideCloudHollow") {
        path(
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = if (filled) null else SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            cloudBody()
        }
    }

/**
 * Lucide `cloud-off`: the cloud silhouette with a slash through it. Owner chose
 * the slash over a knocked-out ✕ — the ✕ destroys the cloud shape at badge size.
 * Always drawn hollow: this state means "nothing can ever be uploaded", so a
 * filled (in-the-cloud) body would contradict the slash.
 */
internal fun lucideCloudOff(): ImageVector =
    badgeVector("LucideCloudOff") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            cloudBody()
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(3f, 3f)
            lineTo(21f, 21f)
        }
    }

/** Shared cloud outline, so `cloud` and `cloud-off` cannot drift apart. */
private fun androidx.compose.ui.graphics.vector.PathBuilder.cloudBody() {
    moveTo(17.5f, 19f)
    horizontalLineTo(6.5f)
    curveTo(4.01f, 19f, 2f, 16.99f, 2f, 14.5f)
    curveTo(2f, 12.4f, 3.46f, 10.62f, 5.4f, 10.16f)
    curveTo(5.14f, 9.45f, 5f, 8.69f, 5f, 7.9f)
    curveTo(5f, 5.02f, 7.24f, 2.7f, 10.09f, 2.5f)
    curveTo(12.95f, 2.3f, 15.5f, 4.24f, 16.14f, 7.02f)
    curveTo(16.59f, 6.88f, 17.06f, 6.81f, 17.55f, 6.81f)
    curveTo(20.07f, 6.81f, 22.14f, 8.87f, 22.14f, 11.4f)
    curveTo(22.14f, 13.93f, 20.07f, 15.99f, 17.55f, 15.99f)
    lineTo(17.5f, 19f)
    close()
}

/**
 * Lucide `arrow-big-down`: the fat arrow. Shared by the shelf row's download
 * badge and the Detail page's download switch.
 */
internal fun lucideArrowBigDown(filled: Boolean): ImageVector =
    badgeVector(if (filled) "LucideArrowBigDownSolid" else "LucideArrowBigDownHollow") {
        path(
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = if (filled) null else SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(9f, 4f)
            horizontalLineTo(15f)
            verticalLineTo(12f)
            horizontalLineTo(19f)
            lineTo(12f, 20f)
            lineTo(5f, 12f)
            horizontalLineTo(9f)
            close()
        }
    }

/**
 * A63 (owner ruling 2026-09-09): Lucide `folder` is the imported badge,
 * replacing A42's `book`. An import is only ever "present" — no dual state —
 * so production always draws it filled; the hollow variant exists only to
 * keep the family's (filled, hollow) vector convention uniform.
 *
 * Owner correction 2026-09-09: the upstream Lucide double-stroke folder read
 * too busy at badge size. This is a SIMPLIFIED single-path silhouette — one
 * closed tab-shoulder shape with a soft-seamed body, no inner stroke line —
 * matching the visual weight of heart/cloud/arrow at 17 dp.
 */
internal fun lucideFolder(filled: Boolean): ImageVector =
    badgeVector(if (filled) "LucideFolderSolid" else "LucideFolderHollow") {
        path(
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = if (filled) null else SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // Simplified folder silhouette, single closed path: flat tab top
            // folding into a rounded body. No tab divider, no double stroke.
            moveTo(3f, 6.4f)
            curveTo(3f, 5.1f, 4.1f, 4f, 5.4f, 4f)
            horizontalLineTo(9f)
            // tab shoulder dips once, then runs flat to the right edge
            curveTo(9.6f, 4f, 10.2f, 4.3f, 10.6f, 4.8f)
            lineTo(12.1f, 6.7f)
            horizontalLineTo(18.6f)
            curveTo(19.9f, 6.7f, 21f, 7.8f, 21f, 9.1f)
            verticalLineTo(16.6f)
            curveTo(21f, 17.9f, 19.9f, 19f, 18.6f, 19f)
            horizontalLineTo(5.4f)
            curveTo(4.1f, 19f, 3f, 17.9f, 3f, 16.6f)
            close()
        }
    }

/**
 * Lucide `book`: the imported badge. Chosen over `folder` (collides with
 * Settings → Folders) and `import-tray` (muddies into the download arrow at
 * badge size). Says "what this is", the way heart and cloud do.
 *
 * A63: superseded by [lucideFolder] (owner ruling 2026-09-09); kept for any
 * future reader-facing use of the book shape.
 */
internal fun lucideBook(filled: Boolean): ImageVector =
    badgeVector(if (filled) "LucideBookSolid" else "LucideBookHollow") {
        path(
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = if (filled) null else SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(20f, 22f)
            horizontalLineTo(6.5f)
            curveTo(5.12f, 22f, 4f, 20.88f, 4f, 19.5f)
            verticalLineTo(4.5f)
            curveTo(4f, 3.12f, 5.12f, 2f, 6.5f, 2f)
            horizontalLineTo(20f)
            verticalLineTo(22f)
            close()
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4f, 19.5f)
            curveTo(4f, 18.12f, 5.12f, 17f, 6.5f, 17f)
            horizontalLineTo(20f)
        }
    }

/** Lucide `compass` for the Browse bottom-bar destination (#E119). */
internal fun lucideCompass(): ImageVector =
    badgeVector("LucideCompass") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.95f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // Outer circle (cx=12, cy=12, r=10)
            moveTo(22f, 12f)
            curveTo(22f, 17.523f, 17.523f, 22f, 12f, 22f)
            curveTo(6.477f, 22f, 2f, 17.523f, 2f, 12f)
            curveTo(2f, 6.477f, 6.477f, 2f, 12f, 2f)
            curveTo(17.523f, 2f, 22f, 6.477f, 22f, 12f)
            close()
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.95f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(16.24f, 7.76f)
            lineTo(14.12f, 14.12f)
            lineTo(7.76f, 16.24f)
            lineTo(9.88f, 9.88f)
            close()
        }
    }

/** Lucide `download-tray` (`download`) for the Activity bottom-bar destination (#E119). */
internal fun lucideActivityTray(): ImageVector =
    badgeVector("LucideActivityTray") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.95f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // Tray base
            moveTo(21f, 15f)
            verticalLineTo(19f)
            curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
            horizontalLineTo(5f)
            curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
            verticalLineTo(15f)
            // Arrow head
            moveTo(7f, 10f)
            lineTo(12f, 15f)
            lineTo(17f, 10f)
            // Arrow shaft
            moveTo(12f, 15f)
            verticalLineTo(3f)
        }
    }

/** Lucide `clock` for the History bottom-bar destination (#E119). */
internal fun lucideClock(): ImageVector =
    badgeVector("LucideClock") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.95f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(22f, 12f)
            curveTo(22f, 17.523f, 17.523f, 22f, 12f, 22f)
            curveTo(6.477f, 22f, 2f, 17.523f, 2f, 12f)
            curveTo(2f, 6.477f, 6.477f, 2f, 12f, 2f)
            curveTo(17.523f, 2f, 22f, 6.477f, 22f, 12f)
            close()
            moveTo(12f, 6f)
            verticalLineTo(12f)
            lineTo(16f, 14f)
        }
    }

/** Lucide `sliders-vertical` for the More / Settings bottom-bar destination (#E119). */
internal fun lucideSliders(): ImageVector =
    badgeVector("LucideSliders") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.95f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(4f, 21f); verticalLineTo(14f)
            moveTo(4f, 10f); verticalLineTo(3f)
            moveTo(12f, 21f); verticalLineTo(12f)
            moveTo(12f, 8f); verticalLineTo(3f)
            moveTo(20f, 21f); verticalLineTo(16f)
            moveTo(20f, 12f); verticalLineTo(3f)
            moveTo(2f, 14f); horizontalLineTo(6f)
            moveTo(10f, 8f); horizontalLineTo(14f)
            moveTo(18f, 16f); horizontalLineTo(22f)
        }
    }

