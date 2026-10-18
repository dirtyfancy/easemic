package com.easemic.app.ui.library

import com.easemic.app.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.easemic.app.feature.download.SourceDelistedCopy
import com.easemic.app.model.ShelfDownloadState
import com.easemic.app.ui.theme.QuietShelfColors
import com.easemic.app.ui.theme.quietShelfPalette
import kotlin.math.pow

/**
 * Shelf tile presence along the collect/cloud axis. Distinct from the
 * storage-facing `ResourcePresence`: this type is a UI semantic and is mapped
 * at the call site, never reused from the network model.
 */
enum class ShelfPresence { Bookmarked, Cloud }

/** Tag prefix on the badge cluster: `library-badges:<joined label>`. */
const val BADGE_TEST_TAG_PREFIX = "library-badges:"

/** One drawn glyph. Icons have no content description, so tests count this. */
const val BADGE_GLYPH_TEST_TAG = "library-badge-glyph"

/** One glyph in the shelf's badge vocabulary. */
enum class ShelfBadge { Heart, CloudQueued, CloudStored, CloudBlocked, Downloaded, DownloadQueued, Imported }

/**
 * A42: everything the badge row is allowed to know, as plain facts. Kept as a
 * value object so the resolution below is a pure function the JVM unit tests
 * can pin, and so grid and list feed it identically.
 *
 * [delistedAtSource] is the #C9 fact (a GONE job, or a SEALED job under the
 * GONE verdict): the source no longer serves this comic. Owner ruling
 * 2026-09-16: it renders the slashed cloud whether or not pages are on the
 * device — a truncated download is both downloaded (arrow) and delisted
 * (slashed cloud). Fed from the shelf SQL projection (Local tiles) and from
 * `observeDelistedJobs` keys via `ShelfBadges` (Remote tiles).
 *
 * A63 adds [downloadState]: the live per-item download-job state from the W3
 * projection. `QUEUED` renders the hollow arrow (WAIT/DOWNLOAD job), `COMPLETE`
 * the filled one (FINISH/sealed with local pages) — superseding the legacy
 * [downloaded] boolean whenever the axis is known (see [resolveShelfBadges]).
 */
data class ShelfBadgeFacts(
    val cloudBound: Boolean,
    /** Null ⇒ this tile has no collect/cloud presence; only the rest may speak. */
    val presence: ShelfPresence?,
    val downloaded: Boolean,
    val imported: Boolean = false,
    val delistedAtSource: Boolean = false,
    val downloadState: ShelfDownloadState = ShelfDownloadState.NONE,
)

/**
 * The badge truth table, owner-decided (A42):
 *  - heart and cloud are **mutually exclusive** — at most one of them is ever
 *    in the returned list. A shelf row is collected by definition, so with no
 *    bucket bound that fact is a solid heart; once a bucket is bound the heart
 *    is *replaced* by a cloud carrying the upload state.
 *  - cloud sub-states: hollow = queued for upload, solid = in the cloud,
 *    `cloud-off` = collected but the source delisted it (pages or not; an
 *    uploaded copy still wins with the solid cloud).
 *  - download and imported are independent facts and stack after the primary.
 *
 * A63 (2026-09-09 ruling) makes the download axis dual-state: a live job
 * (WAIT/DOWNLOAD, [ShelfDownloadState.QUEUED]) renders the hollow
 * [ShelfBadge.DownloadQueued] glyph and outranks a stale legacy `downloaded`
 * boolean; a completed job ([ShelfDownloadState.COMPLETE]) renders the same
 * filled [ShelfBadge.Downloaded] glyph the legacy boolean always did — one
 * download glyph, never two.
 */
fun resolveShelfBadges(facts: ShelfBadgeFacts): List<ShelfBadge> = buildList {
    val primary = when {
        // A32 deviation D10: without the null arm a downloaded-only tile on a
        // bound bucket would claim a cloud state it never had.
        facts.presence == null -> null
        // Unbound world: no cloud vocabulary on tiles at all, ever.
        !facts.cloudBound -> ShelfBadge.Heart
        facts.presence == ShelfPresence.Cloud -> ShelfBadge.CloudStored
        facts.delistedAtSource -> ShelfBadge.CloudBlocked
        else -> ShelfBadge.CloudQueued
    }
    if (primary != null) add(primary)
    when (facts.downloadState) {
        ShelfDownloadState.QUEUED -> add(ShelfBadge.DownloadQueued)
        ShelfDownloadState.COMPLETE -> add(ShelfBadge.Downloaded)
        ShelfDownloadState.NONE -> if (facts.downloaded) add(ShelfBadge.Downloaded)
    }
    if (facts.imported) add(ShelfBadge.Imported)
}

/** Accessibility/test label for one badge. There are no text badges in the row. */
internal fun ShelfBadge.label(): String = when (this) {
    ShelfBadge.Heart -> tr("Collected", "已收藏", "已收藏", "コレクション済み")
    ShelfBadge.CloudQueued -> tr("Collected · not yet in cloud", "已收藏 · 尚未上传到云端", "已收藏 · 尚未上傳到雲端", "コレクション済み · まだクラウドにありません")
    ShelfBadge.CloudStored -> tr("In cloud", "在云端", "在雲端", "クラウド内")
    ShelfBadge.CloudBlocked -> SourceDelistedCopy.COLLECTED_LABEL
    ShelfBadge.Downloaded -> tr("Downloaded", "已下载", "已下載", "ダウンロード済み")
    ShelfBadge.DownloadQueued -> tr("Queued for download", "等待下载", "等待下載", "ダウンロード待ち")
    ShelfBadge.Imported -> tr("Imported", "本地导入", "本機匯入", "インポート済み")
}

/**
 * A63: the per-axis color language, as a pure function so the JVM tint tests
 * can pin it without Compose. Three axes, all existing palette tokens (no new
 * token): orange (`cloudUploaded`) = the storage axis — cloud glyphs AND
 * folder; green (`statusOk`) = the download axis, filled and hollow alike;
 * red (`statusBad` family) = the heart (collect axis, unbound world only);
 * `cloud-off` keeps `statusWarn`. `palette.ink` is gone from every badge
 * (black-tint retirement, A63 §2).
 */
internal enum class ShelfBadgeTintAxis { CLOUD_UPLOADED, STATUS_OK, STATUS_BAD, STATUS_WARN }

internal fun shelfBadgeTintAxis(badge: ShelfBadge): ShelfBadgeTintAxis = when (badge) {
    ShelfBadge.Heart -> ShelfBadgeTintAxis.STATUS_BAD
    ShelfBadge.CloudQueued -> ShelfBadgeTintAxis.CLOUD_UPLOADED
    ShelfBadge.CloudStored -> ShelfBadgeTintAxis.CLOUD_UPLOADED
    ShelfBadge.CloudBlocked -> ShelfBadgeTintAxis.STATUS_WARN
    ShelfBadge.Downloaded -> ShelfBadgeTintAxis.STATUS_OK
    ShelfBadge.DownloadQueued -> ShelfBadgeTintAxis.STATUS_OK
    ShelfBadge.Imported -> ShelfBadgeTintAxis.CLOUD_UPLOADED
}

/**
 * #D1: glyphs on the cover scrim. The scrim is always dark, and the light-theme
 * status colours miss 3:1 there, so the scrim uses the dark-theme token of the
 * same axis. List rows keep [quietShelfPalette].
 */
internal fun scrimBadgeTint(axis: ShelfBadgeTintAxis): Color = when (axis) {
    ShelfBadgeTintAxis.CLOUD_UPLOADED -> QuietShelfColors.Dark.cloudUploaded
    ShelfBadgeTintAxis.STATUS_OK -> QuietShelfColors.Dark.statusOk
    ShelfBadgeTintAxis.STATUS_BAD -> QuietShelfColors.Dark.statusBad
    ShelfBadgeTintAxis.STATUS_WARN -> QuietShelfColors.Dark.statusWarn
}

/** WCAG 2 relative luminance contrast. Channels are gamma-encoded sRGB in 0..1. */
internal fun contrastRatio(a: Color, b: Color): Double {
    val lighter = maxOf(relativeLuminance(a), relativeLuminance(b))
    val darker = minOf(relativeLuminance(a), relativeLuminance(b))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(color: Color): Double {
    fun channel(encoded: Float): Double {
        val c = encoded.toDouble()
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) +
        0.7152 * channel(color.green) +
        0.0722 * channel(color.blue)
}

/** Pure label joiner, exposed for unit tests and reused by the composable. */
internal fun badgesLabel(facts: ShelfBadgeFacts): String =
    resolveShelfBadges(facts).joinToString(" · ") { it.label() }

/** Badge glyph size; matches the row's footer-line metrics. */
private val BadgeGlyphSize = 17.dp

/** Dim the cloud badge when upstream is unreachable; tile stays tappable. */
private const val UnreachableAlpha = 0.55f

@Composable
fun LibraryTileBadges(
    facts: ShelfBadgeFacts,
    modifier: Modifier = Modifier,
    unreachable: Boolean = false,
    onScrim: Boolean = false,
    maxGlyphs: Int = Int.MAX_VALUE,
) {
    val badges = remember(facts) { resolveShelfBadges(facts) }
    if (badges.isEmpty()) return
    // The label names every resolved badge. maxGlyphs only caps what is drawn.
    val label = badges.joinToString(" · ") { it.label() }
    val palette = quietShelfPalette()
    // contentDescription carries the joined label for a11y; the testTag mirrors
    // it so instrumented tests can locate a tile's badge cluster even through
    // ancestors that merge (but do not clear) descendants.
    Row(
        modifier = modifier
            .testTag("$BADGE_TEST_TAG_PREFIX$label")
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        badges.take(maxGlyphs.coerceAtLeast(0)).forEach { badge ->
            val axis = shelfBadgeTintAxis(badge)
            BadgeIcon(
                badge = badge,
                // A63: the one axis mapping both the JVM tint tests and this
                // renderer read — orange storage axis (cloud+folder), green
                // download axis, red heart, warn cloud-off. Black ink is gone.
                // On the scrim the same axis uses the dark-theme token.
                tint = if (onScrim) {
                    scrimBadgeTint(axis)
                } else {
                    when (axis) {
                        ShelfBadgeTintAxis.CLOUD_UPLOADED -> palette.cloudUploaded
                        ShelfBadgeTintAxis.STATUS_OK -> palette.statusOk
                        ShelfBadgeTintAxis.STATUS_BAD -> palette.statusBad
                        ShelfBadgeTintAxis.STATUS_WARN -> palette.statusWarn
                    }
                },
                dimmed = unreachable && badge.isCloud(),
            )
        }
    }
}

private fun ShelfBadge.isCloud(): Boolean =
    this == ShelfBadge.CloudQueued ||
        this == ShelfBadge.CloudStored ||
        this == ShelfBadge.CloudBlocked

@Composable
private fun BadgeIcon(badge: ShelfBadge, tint: Color, dimmed: Boolean) {
    val vector = remember(badge) {
        when (badge) {
            ShelfBadge.Heart -> lucideHeart(filled = true)
            ShelfBadge.CloudQueued -> lucideCloud(filled = false)
            ShelfBadge.CloudStored -> lucideCloud(filled = true)
            ShelfBadge.CloudBlocked -> lucideCloudOff()
            ShelfBadge.Downloaded -> lucideArrowBigDown(filled = true)
            // A63 (owner re-confirmed): download dual-state IS hollow/filled,
            // same shape language as the cloud axis — hollow = queued/being
            // fetched, filled = on this device. Both statusesOk green.
            ShelfBadge.DownloadQueued -> lucideArrowBigDown(filled = false)
            // A63: folder replaces A42's book glyph (owner ruling — an import
            // is "present here", never dual-state, so always filled).
            ShelfBadge.Imported -> lucideFolder(filled = true)
        }
    }
    Icon(
        imageVector = vector,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .testTag(BADGE_GLYPH_TEST_TAG)
            .size(BadgeGlyphSize)
            .alpha(if (dimmed) UnreachableAlpha else 1f),
    )
}
