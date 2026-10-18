package com.easemic.app.feature.browse

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.easemic.app.feature.download.SourceDelistedCopy
import com.easemic.app.i18n.tr
import com.easemic.app.ui.library.lucideArrowBigDown
import com.easemic.app.ui.theme.quietShelfPalette

/**
 * Detail reverse-proxy switches (spec §2): two state switches next to Read —
 * download and collect (heart/cloud). 46dp monochrome touch targets. Active =
 * `statusOk` border + filled icon. Cloud pending (collected, not yet uploaded) =
 * filled black cloud + a rotating progress ring; cloud uploaded = filled orange
 * cloud. Download in-flight adds the ring. No status-dot badges.
 *
 * #E73 (owner 2026-10-12): the row order is Download LEFT of Collect, the same
 * order on serialized detail — only the order moved; icons, sizing and the
 * confirm-dialog behaviour are unchanged.
 */

/**
 * C4 §2.1 — what the download switch says while this comic's upload is in
 * flight. It replaces the state description rather than joining it: the tap is
 * gone, and "Downloaded" alone would invite one.
 */
const val DETAIL_DOWNLOAD_UPLOADING_LABEL = "Uploading to cloud"

private val SwitchSize = 46.dp
private val IconSize = 22.dp
private val RingSize = 34.dp
private val SwitchShape = RoundedCornerShape(12.dp)

@Composable
fun DetailActionSwitches(
    collectIcon: CollectIcon,
    downloadIcon: DownloadIcon,
    onCollectClick: () -> Unit,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** C9: the zero-page GONE case — the download switch is inert and dimmed, and its description says why. */
    downloadDelisted: Boolean = false,
    /**
     * C4 §2.1: this comic's upload is in flight. Locked wins over the click —
     * the tap behind it is "Delete download?", which would unlink the pages the
     * running upload is still reading.
     */
    downloadLocked: Boolean = false,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DownloadSwitch(
            icon = downloadIcon,
            onClick = if (downloadDelisted || downloadLocked) null else onDownloadClick,
            delisted = downloadDelisted,
            locked = downloadLocked,
        )
        CollectSwitch(icon = collectIcon, onClick = onCollectClick)
    }
}

@Composable
fun CollectSwitch(icon: CollectIcon, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // A54: CLOUD_PENDING keeps the plain "Collected" description — the filled
    // black cloud + rotating ring carry the pending-upload state; the old
    // "Collected · syncing to cloud" subtitle narrated a success that has
    // not happened yet.
    val description = when (icon) {
        CollectIcon.HEART_HOLLOW, CollectIcon.CLOUD_HOLLOW -> tr("Not collected", "未收藏", "未收藏", "未コレクション")
        CollectIcon.HEART_SOLID, CollectIcon.CLOUD_SOLID, CollectIcon.CLOUD_PENDING -> tr("Collected", "已收藏", "已收藏", "コレクション済み")
        CollectIcon.CLOUD_BLOCKED -> tr(SourceDelistedCopy.COLLECTED_LABEL, "已收藏 · 图源不再提供此漫画", "已收藏 · 圖源不再提供此漫畫", "コレクション済み · ソースではこのマンガを提供していません")
    }
    val isHeart = icon == CollectIcon.HEART_HOLLOW || icon == CollectIcon.HEART_SOLID
    // Pending (collected, upload not yet done) and uploaded both render FILLED.
    // Pending is inked black; uploaded is Cloudflare orange.
    val filled = icon == CollectIcon.HEART_SOLID ||
        icon == CollectIcon.CLOUD_SOLID ||
        icon == CollectIcon.CLOUD_PENDING
    val inFlight = icon == CollectIcon.CLOUD_PENDING
    val palette = quietShelfPalette()
    val tint = when (icon) {
        CollectIcon.CLOUD_SOLID -> palette.cloudUploaded
        CollectIcon.CLOUD_BLOCKED -> palette.statusWarn
        else -> palette.ink
    }
    SwitchBox(
        tag = "detail-collect",
        contentDescription = description,
        active = filled || icon == CollectIcon.CLOUD_BLOCKED,
        inFlight = inFlight,
        activeColor = if (icon == CollectIcon.CLOUD_BLOCKED) palette.statusWarn else palette.statusOk,
        onClick = onClick,
        modifier = modifier,
    ) {
        Icon(
            imageVector = when {
                isHeart -> detailHeartIcon(filled)
                icon == CollectIcon.CLOUD_BLOCKED -> detailCloudOffIcon()
                else -> detailCloudIcon(filled)
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(IconSize)
                .testTag(if (isHeart) "detail-collect-heart" else "detail-collect-cloud"),
        )
    }
}

@Composable
fun DownloadSwitch(
    icon: DownloadIcon,
    /**
     * A32 §3: null makes this a pure state indicator with no click action at
     * all. A local subject's file is on the phone by definition and its single
     * deleting verb lives behind the ··· two-tap, so an inert glyph is the
     * honest rendering — a no-op lambda would still advertise a tap.
     */
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** C9: the zero-page GONE case — dims the box and swaps the a11y description for the statement clause. */
    delisted: Boolean = false,
    /**
     * C4 §2.1: this comic's upload is in flight, so NO click action is
     * installed at all — a disabled-looking box that still fires is how the
     * owner lost both copies. Dimmed like the delisted case, and the
     * description says what the app is doing instead of what the box would do.
     */
    locked: Boolean = false,
    /**
     * #E73: the serialized detail's picker switch is a different control (it
     * opens the chapter sheet; it never cancels or deletes), so it carries its
     * own tag and the gallery switch's `detail-download-*` tags stay with the
     * gallery flow.
     */
    tag: String = "detail-download",
) {
    val description = when {
        locked -> tr(DETAIL_DOWNLOAD_UPLOADING_LABEL, "正在上传到云端", "正在上傳至雲端", "クラウドにアップロード中")
        icon == DownloadIcon.HOLLOW ->
            if (delisted) tr(SourceDelistedCopy.NOT_DOWNLOADED_LABEL, "未下载 · 图源不再提供此漫画", "未下載 · 圖源不再提供此漫畫", "未ダウンロード · ソースではこのマンガを提供していません")
            else tr("Not downloaded", "未下载", "未下載", "未ダウンロード")
        icon == DownloadIcon.IN_FLIGHT -> tr("Downloading", "正在下载", "正在下載", "ダウンロード中")
        else -> tr("Downloaded", "已下载", "已下載", "ダウンロード済み")
    }
    SwitchBox(
        tag = tag,
        contentDescription = description,
        active = icon != DownloadIcon.HOLLOW,
        inFlight = icon == DownloadIcon.IN_FLIGHT,
        onClick = if (locked) null else onClick,
        dimmed = delisted || locked,
        modifier = modifier,
    ) {
        Icon(
            imageVector = detailArrowDownIcon(icon == DownloadIcon.SOLID),
            contentDescription = null,
            tint = quietShelfPalette().ink,
            modifier = Modifier.size(IconSize),
        )
    }
}

/**
 * 46dp touch target. Active states draw a `statusOk` border. In-flight wraps the
 * icon in an indeterminate progress ring; the icon itself stays still. No dot badge.
 */
@Composable
private fun SwitchBox(
    tag: String,
    contentDescription: String,
    active: Boolean,
    inFlight: Boolean,
    /** null ⇒ no click action is installed at all; the box is a label. */
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    activeColor: Color? = null,
    /** C9: dims the whole box (icon + border) to the shelf's `UnreachableAlpha` (0.55f). */
    dimmed: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = quietShelfPalette()
    val resolvedActiveColor = activeColor ?: palette.statusOk
    Box(
        modifier = modifier
            .size(SwitchSize)
            .alpha(if (dimmed) 0.55f else 1f)
            .border(
                width = if (active) 1.5.dp else 1.dp,
                color = if (active) resolvedActiveColor else palette.line,
                shape = SwitchShape,
            )
            .let { if (active) it.testTag("$tag-active-border") else it },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(SwitchSize)
                .clip(SwitchShape)
                .testTag("$tag-switch")
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (inFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(RingSize)
                            .testTag("$tag-ring"),
                        color = resolvedActiveColor,
                        strokeWidth = 2.dp,
                    )
                }
                content()
            }
        }
    }
}

// ── Lucide-style monochrome icons (24x24 viewport) ───────────────────────

/** Lucide `heart`; solid = filled silhouette, hollow = outline stroke. */
private fun detailHeartIcon(filled: Boolean): ImageVector =
    ImageVector.Builder(
        name = if (filled) "DetailHeartSolid" else "DetailHeartHollow",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = if (filled) null else SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
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
    }.build()

/** Lucide `cloud`; solid = filled silhouette, hollow = outline stroke. */
private fun detailCloudIcon(filled: Boolean): ImageVector =
    ImageVector.Builder(
        name = if (filled) "DetailCloudSolid" else "DetailCloudHollow",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = if (filled) null else SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        ) {
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
    }.build()

/** Lucide `cloud-off`; the source has withdrawn the comic, so upload is blocked. */
private fun detailCloudOffIcon(): ImageVector =
    ImageVector.Builder(
        name = "DetailCloudOff",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        ) {
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
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
        ) {
            moveTo(3f, 3f)
            lineTo(21f, 21f)
        }
    }.build()

/**
 * A42: Lucide `arrow-big-down`, the same vector the shelf row's download badge
 * uses. The old thin arrow-plus-tray did not carry the heart/cloud silhouette
 * weight, and Detail and the shelf must not speak two dialects of one icon.
 */
private fun detailArrowDownIcon(filled: Boolean): ImageVector = lucideArrowBigDown(filled)
