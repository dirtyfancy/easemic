package com.easemic.app.feature.browse

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easemic.app.ui.library.lucideArrowBigDown
import com.easemic.app.ui.library.lucideCloud
import com.easemic.app.ui.theme.QuietShelfColors
import com.easemic.app.ui.theme.QuietShelfPalette

internal const val SERIALIZED_CHAPTER_CELL_TAG = "serialized-chapter-cell-"

@Composable
internal fun chapterTileHeight(): Dp = with(LocalDensity.current) {
    // Reserve the taller of the word/number layouts, including font-scale growth.
    maxOf(48.dp, 17.dp + 33.8.sp.toDp())
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SerializedChapterCell(
    row: ChapterRowPresentation,
    palette: QuietShelfPalette,
    menuOpen: Boolean,
    onMenu: (String?) -> Unit,
    onRead: () -> Unit,
    onReadOnline: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDeleteSavedCopy: (String) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    onSelect: () -> Unit = {},
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    /** #E103: revives this chapter's stranded cloud upload row (chapter id). */
    onRetryCloudUpload: (String) -> Unit = {},
) {
    val presentation = chapterCellPresentation(row)
    val label = splitChapterCellLabel(row.label)
    val selection = selected?.let { chapterCellSelection(row, it) }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val dark = palette.surface == QuietShelfColors.Dark.surface
    val secondary = palette.ink.copy(alpha = if (dark) .70f else .68f)
    val shape = RoundedCornerShape(10.dp)
    val fill = palette.ink.copy(alpha = if (pressed) {
        if (dark) .15f else .11f
    } else if (dark) .09f else .06f)
    val click = {
        if (selection != null) onSelect()
        else when (presentation.tap) {
            ChapterCellTap.READ -> onRead()
            ChapterCellTap.MENU -> onMenu(row.chapterId)
            ChapterCellTap.SIGN_IN -> onSignIn()
        }
    }
    val clickLabel = if (selection != null) {
        if (selection.selected) "Deselect chapter" else "Select chapter"
    } else when (presentation.tap) {
        ChapterCellTap.READ -> "Read chapter"
        ChapterCellTap.MENU -> "Chapter options"
        ChapterCellTap.SIGN_IN -> "Sign in to read"
    }
    Box(modifier) {
        Box(
            Modifier.fillMaxWidth().height(chapterTileHeight())
                .testTag(SERIALIZED_CHAPTER_CELL_TAG + row.chapterId)
                .alpha(selection?.alpha ?: 1f)
                .chapterDecoration(palette.ink, presentation.dashed, focused, selection?.selected == true)
                .clip(shape).background(fill)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = selection?.enabled != false,
                    role = Role.Button,
                    onClickLabel = clickLabel,
                    onLongClickLabel = "Chapter options",
                    onLongClick = if (selection == null) ({ onMenu(row.chapterId) }) else null,
                    onClick = click,
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = presentation.description
                    if (selection != null) this.selected = selection.selected
                },
        ) {
            val hasGlyph = presentation.glyph != null || selection?.selected == true
            val reserve = if (hasGlyph) 15.dp else 0.dp
            if (label.word != null) {
                Text(label.word, color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    lineHeight = 16.9.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 7.dp, top = 8.dp, end = 7.dp + reserve, bottom = 7.dp)
                        .clearAndSetSemantics {})
            } else {
                Column(Modifier.fillMaxWidth().padding(start = 7.dp, top = 8.dp, end = 7.dp, bottom = 7.dp)) {
                    Text(checkNotNull(label.number), color = palette.ink,
                        style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                            lineHeight = 17.4.sp, fontFeatureSettings = "tnum"),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(end = reserve).clearAndSetSemantics {})
                    Spacer(Modifier.height(2.dp))
                    Text(presentation.removedLine ?: label.title, color = secondary,
                        fontSize = 11.sp, lineHeight = 14.3.sp,
                        minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {})
                }
            }
            val glyphModifier = Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 6.dp).size(14.dp)
            if (selection?.selected == true) {
                SelectedChapterGlyph(palette, glyphModifier.testTag("serialized-chapter-selected-${row.chapterId}"))
            } else {
                ChapterGlyph(presentation.glyph, palette, secondary,
                    glyphModifier.testTag("serialized-chapter-glyph-${row.chapterId}-${presentation.glyph}"))
            }
            presentation.progressFraction?.let { fraction ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                    .testTag("serialized-chapter-progress-${row.chapterId}")
                    .background(palette.ink.copy(alpha = .11f)).clearAndSetSemantics {}) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(palette.statusOk))
                }
            }
        }
        // Popup is outside the cell semantics subtree; options remain independently accessible.
        DropdownMenu(expanded = menuOpen && selection == null, onDismissRequest = { onMenu(null) }) {
            Text(presentation.menuHeader, color = secondary, fontSize = 11.5.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            presentation.actions.forEach { action ->
                DropdownMenuItem(text = { Text(when (action) {
                    ChapterCellAction.RETRY -> "Retry"
                    ChapterCellAction.DELETE_SAVED_COPY -> "Delete saved copy"
                    ChapterCellAction.READ_ONLINE -> "Read online"
                }) }, onClick = {
                    onMenu(null)
                    when (action) {
                        ChapterCellAction.RETRY ->
                            if (row.cloudUploadFailed) onRetryCloudUpload(row.chapterId)
                            else row.jobId?.let(onRetry)
                        ChapterCellAction.DELETE_SAVED_COPY -> onDeleteSavedCopy(row.chapterId)
                        ChapterCellAction.READ_ONLINE -> onReadOnline(row.chapterId)
                    }
                })
            }
        }
    }
}

private fun Modifier.chapterDecoration(ink: Color, removed: Boolean, focused: Boolean, selected: Boolean) =
    drawWithContent {
        drawContent()
        if (removed) {
            val inset = .75.dp.toPx()
            drawRoundRect(ink.copy(alpha = .68f), Offset(inset, inset),
                Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(10.dp.toPx()),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
        }
        if (selected) {
            val inset = 1.dp.toPx()
            drawRoundRect(ink, Offset(inset, inset),
                Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(9.dp.toPx()),
                style = Stroke(2.dp.toPx()))
        }
        if (focused) {
            val outside = 3.dp.toPx() // 2dp gap plus half the 2dp stroke.
            drawRoundRect(ink, Offset(-outside, -outside),
                Size(size.width + 2 * outside, size.height + 2 * outside), CornerRadius(13.dp.toPx()),
                style = Stroke(2.dp.toPx()))
        }
    }

@Composable
private fun SelectedChapterGlyph(palette: QuietShelfPalette, modifier: Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        drawCircle(palette.ink)
        val stroke = 1.6.dp.toPx()
        drawLine(palette.surface, Offset(size.width * .25f, size.height * .50f),
            Offset(size.width * .44f, size.height * .68f), stroke)
        drawLine(palette.surface, Offset(size.width * .44f, size.height * .68f),
            Offset(size.width * .76f, size.height * .31f), stroke)
    }
}

@Composable
private fun ChapterGlyph(glyph: ChapterCellGlyph?, palette: QuietShelfPalette, secondary: Color, modifier: Modifier) {
    if (glyph == null) return
    val tint = when (glyph) {
        ChapterCellGlyph.FAILED -> palette.statusBad
        ChapterCellGlyph.DOWNLOADING, ChapterCellGlyph.LOCAL -> palette.statusOk
        ChapterCellGlyph.CLOUD -> palette.cloudGlyph
        ChapterCellGlyph.LOCK -> secondary
        ChapterCellGlyph.SIGN_IN -> palette.ink
    }
    when (glyph) {
        ChapterCellGlyph.DOWNLOADING -> Icon(lucideArrowBigDown(false), null, modifier, tint)
        ChapterCellGlyph.LOCAL -> Icon(lucideArrowBigDown(true), null, modifier, tint)
        ChapterCellGlyph.CLOUD -> Icon(lucideCloud(true), null, modifier, tint)
        ChapterCellGlyph.FAILED, ChapterCellGlyph.LOCK, ChapterCellGlyph.SIGN_IN -> Canvas(modifier) {
            val stroke = 1.5.dp.toPx()
            when (glyph) {
                ChapterCellGlyph.FAILED -> {
                    drawCircle(tint, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
                    drawLine(tint, Offset(size.width / 2, size.height * .25f),
                        Offset(size.width / 2, size.height * .58f), stroke)
                    drawCircle(tint, stroke / 2, Offset(size.width / 2, size.height * .76f))
                }
                ChapterCellGlyph.LOCK -> {
                    drawRoundRect(tint, Offset(size.width * .18f, size.height * .42f),
                        Size(size.width * .64f, size.height * .5f), CornerRadius(stroke), style = Stroke(stroke))
                    drawArc(tint, 180f, 180f, false, Offset(size.width * .3f, size.height * .08f),
                        Size(size.width * .4f, size.height * .6f), style = Stroke(stroke))
                }
                ChapterCellGlyph.SIGN_IN -> {
                    drawCircle(tint, radius = size.minDimension * .18f,
                        center = Offset(size.width / 2, size.height * .3f), style = Stroke(stroke))
                    drawArc(tint, 180f, 180f, false, Offset(size.width * .18f, size.height * .56f),
                        Size(size.width * .64f, size.height * .72f), style = Stroke(stroke))
                }
                else -> Unit
            }
        }
    }
}
