package com.easemic.app.feature.detail.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easemic.app.feature.browse.ChapterGroupPresentation
import com.easemic.app.feature.browse.ChapterRowPresentation
import com.easemic.app.feature.browse.SERIALIZED_SIGN_IN_HINT
import com.easemic.app.feature.browse.SerializedChapterCell
import com.easemic.app.feature.browse.SerializedChapterOrderStore
import com.easemic.app.feature.browse.chapterDisclosureLabel
import com.easemic.app.feature.browse.chapterSortDescription
import com.easemic.app.feature.browse.chapterTileHeight
import com.easemic.app.feature.browse.collapsedChapterRows
import com.easemic.app.feature.browse.displayedChapterRows
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.ui.components.PclTextButton
import com.easemic.app.ui.theme.QuietShelfColors
import com.easemic.app.ui.theme.QuietShelfShimmer
import com.easemic.app.ui.theme.quietShelfPalette

/**
 * The series content section (skeleton spec §2.1.7, Part 4 Chunk E): the
 * section heading — "Chapters 344" when [chapterCount] is known, plain
 * "Chapters" when it is not — over the Part 3 chapter grid. Thin wrapper
 * (plan decision 8); the grid below is moved code, cells/groups/toggles
 * untouched.
 */
@Composable
internal fun DetailChapters(
    series: SeriesIdentityV2,
    groups: List<ChapterGroupPresentation>,
    orders: SerializedChapterOrderStore,
    onRead: (ChapterRowPresentation) -> Unit,
    onReadOnline: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDeleteSavedCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** A chapter cell that needs an account was tapped (the caller confirms before signing in). */
    onSignIn: (String) -> Unit = {},
    /** The group hint's "Sign in": the user asked to sign in, so no chapter confirmation. */
    onSignInHint: () -> Unit = {},
    /** §2.1.7: the total chapter count when the caller knows it (e45 §5 count rules). */
    chapterCount: Int? = null,
    /** #E103: revives this chapter's stranded cloud upload row (chapter id). */
    onRetryCloudUpload: (String) -> Unit = {},
) {
    SerializedChapterGrid(
        series = series,
        groups = groups,
        orders = orders,
        onRead = onRead,
        onReadOnline = onReadOnline,
        onRetry = onRetry,
        onDeleteSavedCopy = onDeleteSavedCopy,
        modifier = modifier,
        onSignIn = onSignIn,
        onSignInHint = onSignInHint,
        chapterCount = chapterCount,
        onRetryCloudUpload = onRetryCloudUpload,
    )
}

@Composable
fun SerializedChapterGrid(
    series: SeriesIdentityV2,
    groups: List<ChapterGroupPresentation>,
    orders: SerializedChapterOrderStore,
    onRead: (ChapterRowPresentation) -> Unit,
    onReadOnline: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDeleteSavedCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** A chapter cell that needs an account was tapped (the caller confirms before signing in). */
    onSignIn: (String) -> Unit = {},
    /** The group hint's "Sign in": the user asked to sign in, so no chapter confirmation. */
    onSignInHint: () -> Unit = {},
    /** §2.1.7: the total chapter count when the caller knows it (e45 §5 count rules). */
    chapterCount: Int? = null,
    /** #E103: revives this chapter's stranded cloud upload row (chapter id). */
    onRetryCloudUpload: (String) -> Unit = {},
) {
    val palette = quietShelfPalette()
    val secondary = palette.ink.copy(alpha = if (palette.surface == QuietShelfColors.Dark.surface) .70f else .68f)
    var menuChapter by rememberSaveable(series.providerNamespace, series.canonicalSeriesId) {
        mutableStateOf<String?>(null)
    }
    LaunchedEffect(groups) {
        if (groups.none { group -> group.rows.any { it.chapterId == menuChapter } }) menuChapter = null
    }
    Column(modifier.fillMaxWidth().testTag("serialized-chapter-list"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChapterSectionHeading(chapterCount)
        groups.forEach { group ->
            key(series.providerNamespace, series.canonicalSeriesId, group.label) {
                val groupKey = group.label
                var newestFirst by rememberSaveable(series.providerNamespace, series.canonicalSeriesId, groupKey) {
                    mutableStateOf(orders.newestFirst(series, groupKey))
                }
                var expanded by rememberSaveable(series.providerNamespace, series.canonicalSeriesId, groupKey, "expanded") {
                    mutableStateOf(false)
                }
                val displayRows = displayedChapterRows(group, newestFirst)
                val rows = collapsedChapterRows(displayRows, expanded)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (!group.label.isNullOrBlank()) {
                                Text(group.label, color = palette.ink, fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false))
                            }
                            Text(group.rows.size.toString(), color = secondary,
                                style = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum"))
                        }
                        Row(
                            Modifier.testTag("serialized-chapter-order-${groupKey.orEmpty()}")
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) {
                                    newestFirst = !newestFirst
                                    orders.setNewestFirst(series, groupKey, newestFirst)
                                }
                                .semantics(mergeDescendants = true) { contentDescription = chapterSortDescription(newestFirst) }
                                .padding(start = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            ChapterSortGlyph(palette.ink)
                            Text(
                                if (newestFirst) tr("Newest", "最新在前", "最新在前", "新しい順") else tr("Oldest", "最早在前", "最早在前", "古い順"),
                                color = palette.ink,
                                fontSize = 12.sp,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                    }
                    if (group.rows.any { it.needsSignIn }) {
                        Row(
                            Modifier.fillMaxWidth().testTag("serialized-chapter-sign-in-hint"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(SERIALIZED_SIGN_IN_HINT, color = palette.muted,
                                modifier = Modifier.weight(1f))
                            PclTextButton(onClick = onSignInHint) {
                                Text(AppStrings.signIn)
                            }
                        }
                    }
                    rows.chunked(4).forEach { cells ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            cells.forEach { cell ->
                                key(cell.chapterId) {
                                    SerializedChapterCell(
                                        row = cell,
                                        palette = palette,
                                        menuOpen = menuChapter == cell.chapterId,
                                        onMenu = { menuChapter = it },
                                        onRead = { onRead(cell) },
                                        onReadOnline = onReadOnline,
                                        onRetry = onRetry,
                                        onDeleteSavedCopy = onDeleteSavedCopy,
                                        onSignIn = { onSignIn(cell.chapterId) },
                                        modifier = Modifier.weight(1f),
                                        onRetryCloudUpload = onRetryCloudUpload,
                                    )
                                }
                            }
                            repeat(4 - cells.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (displayRows.size > 16) {
                        Column(Modifier.padding(top = 2.dp)) {
                            HorizontalDivider(thickness = .5.dp, color = palette.line)
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag(if (expanded) "serialized-chapter-collapse" else "serialized-chapter-disclosure")
                                    .clickable(role = Role.Button) { expanded = !expanded },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                            ) {
                                Text(chapterDisclosureLabel(displayRows.size, expanded), color = palette.ink, fontSize = 12.5.sp)
                                ChapterDisclosureGlyph(expanded, palette.ink)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * §2.1.7 / §2.8's one heading level: "Chapters" at 15 sp semibold with its
 * count in muted tabular figures when the caller knows the total.
 */
@Composable
private fun ChapterSectionHeading(chapterCount: Int? = null) {
    val palette = quietShelfPalette()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(AppStrings.chaptersHeading, color = palette.ink, fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        if (chapterCount != null) {
            Text(chapterCount.toString(), color = palette.muted,
                style = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum"))
        }
    }
}

@Composable
internal fun SerializedChapterGridLoading(modifier: Modifier = Modifier, chapterCount: Int? = null) {
    Column(modifier.fillMaxWidth().testTag("serialized-chapter-loading"),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChapterSectionHeading(chapterCount)
        repeat(2) { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(4) { column ->
                    QuietShelfShimmer(
                        modifier = Modifier.weight(1f).height(chapterTileHeight())
                            .testTag("serialized-chapter-loading-${line * 4 + column}").clearAndSetSemantics {},
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterSortGlyph(color: Color) {
    Canvas(Modifier.size(14.dp).clearAndSetSemantics {}) {
        val stroke = 1.4.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color,
            Offset(size.width * x1, size.height * y1), Offset(size.width * x2, size.height * y2),
            strokeWidth = stroke, cap = StrokeCap.Round)
        line(.3f, .15f, .3f, .85f)
        line(.12f, .65f, .3f, .85f)
        line(.3f, .85f, .48f, .65f)
        line(.7f, .85f, .7f, .15f)
        line(.52f, .35f, .7f, .15f)
        line(.7f, .15f, .88f, .35f)
    }
}

@Composable
private fun ChapterDisclosureGlyph(expanded: Boolean, color: Color) {
    Canvas(Modifier.size(14.dp).clearAndSetSemantics {}) {
        val edge = size.height * if (expanded) .65f else .35f
        val middle = size.height * if (expanded) .35f else .65f
        val stroke = 1.4.dp.toPx()
        drawLine(color, Offset(size.width * .25f, edge), Offset(size.width * .5f, middle), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * .5f, middle), Offset(size.width * .75f, edge), stroke, StrokeCap.Round)
    }
}
