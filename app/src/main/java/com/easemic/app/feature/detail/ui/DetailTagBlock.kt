package com.easemic.app.feature.detail.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easemic.app.feature.detail.model.DetailTag
import com.easemic.app.feature.detail.model.DetailTagRow
import com.easemic.app.feature.detail.model.DetailTags
import com.easemic.app.feature.detail.model.detailTagCount
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.ui.theme.QuietShelfPalette
import com.easemic.app.ui.theme.QuietShelfShimmer
import java.util.Locale

private const val COLLAPSED_NAMESPACED_ROWS = 4
private const val COLLAPSED_NAMESPACED_ROW_LINES = 2
private const val COLLAPSED_FLAT_LINES = 2

/**
 * Detail tag block (skeleton spec §2.6, T1 + #E120 measured per-namespace line cap).
 *
 * Collapse (#E120):
 * - Namespaced sources show at most the first [COLLAPSED_NAMESPACED_ROWS] namespace rows, and each
 *   collapsed namespace row wraps at most [COLLAPSED_NAMESPACED_ROW_LINES] measured lines (ending
 *   with an inline `+N more` pill when that namespace has additional chips).
 * - Flat sources show the first [COLLAPSED_FLAT_LINES] measured lines.
 * - While anything is hidden — either entire trailing namespace rows OR overflow chips inside a
 *   shown namespace row — the disclosure line reads `"Show all N tags ⌄"` and expands in place to
 *   `"Show fewer ⌃"`.
 */
@Composable
internal fun DetailTagBlock(
    tags: DetailTags,
    palette: QuietShelfPalette,
    memoryKey: String,
    loading: Boolean = false,
    clickable: Boolean = true,
    onTagClick: (DetailTag) -> Unit = {},
) {
    if (loading) {
        DetailTagsLoading()
        return
    }
    val chips = when (tags) {
        is DetailTags.Flat -> tags.chips
        is DetailTags.Namespaced -> tags.rows.flatMap { it.chips }
    }
    if (chips.isEmpty()) return
    var expanded by rememberSaveable(memoryKey, "tags") { mutableStateOf(false) }
    val total = detailTagCount(tags)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val onLongClick: (DetailTag) -> Unit = { tag ->
        clipboard.setText(AnnotatedString(tag.label))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            android.widget.Toast.makeText(context, AppStrings.copied, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    when (tags) {
        is DetailTags.Namespaced -> {
            val rows = tags.rows
            val visibleRows = if (expanded) rows else rows.take(COLLAPSED_NAMESPACED_ROWS)
            var overflowRowsMask by remember(rows, expanded) { mutableIntStateOf(0) }
            val hiddenRows = rows.size > COLLAPSED_NAMESPACED_ROWS
            val hasRowOverflow = overflowRowsMask != 0
            Column(
                Modifier.fillMaxWidth().testTag("detail-tag-block"),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                visibleRows.forEachIndexed { index, row ->
                    NamespacedTagRow(
                        row = row,
                        expanded = expanded,
                        palette = palette,
                        clickable = clickable,
                        onTagClick = onTagClick,
                        onLongClick = onLongClick,
                        onExpand = { expanded = true },
                        onOverflowChanged = { hasOverflow ->
                            val bit = 1 shl index
                            val next = if (hasOverflow) overflowRowsMask or bit else overflowRowsMask and bit.inv()
                            if (next != overflowRowsMask) overflowRowsMask = next
                        },
                    )
                }
                if (expanded || hiddenRows || hasRowOverflow) {
                    TagsDisclosureLine(expanded, total, palette) { expanded = !expanded }
                }
            }
        }
        is DetailTags.Flat -> MeasuredFlatTagChips(
            chips = tags.chips,
            expanded = expanded,
            total = total,
            palette = palette,
            clickable = clickable,
            onTagClick = onTagClick,
            onLongClick = onLongClick,
            onToggleExpanded = { expanded = !expanded },
            modifier = Modifier.testTag("detail-tag-block"),
        )
    }
}

@Composable
private fun NamespacedTagRow(
    row: DetailTagRow,
    expanded: Boolean,
    palette: QuietShelfPalette,
    clickable: Boolean,
    onTagClick: (DetailTag) -> Unit,
    onLongClick: (DetailTag) -> Unit,
    onExpand: () -> Unit,
    onOverflowChanged: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = tagRowLabel(row.namespace, row.label.replaceFirstChar { it.titlecase(Locale.ENGLISH) }),
            color = palette.ink.copy(alpha = .68f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(72.dp)
                .padding(top = 5.dp)
                .testTag("detail-tag-row-label-${row.namespace}"),
        )
        MeasuredNamespacedRowChips(
            namespace = row.namespace,
            chips = row.chips,
            expanded = expanded,
            palette = palette,
            clickable = clickable,
            onTagClick = onTagClick,
            onLongClick = onLongClick,
            onExpand = onExpand,
            onOverflowChanged = onOverflowChanged,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MeasuredNamespacedRowChips(
    namespace: String,
    chips: List<DetailTag>,
    expanded: Boolean,
    palette: QuietShelfPalette,
    clickable: Boolean,
    onTagClick: (DetailTag) -> Unit,
    onLongClick: (DetailTag) -> Unit,
    onExpand: () -> Unit,
    onOverflowChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hiddenCount by remember(chips, expanded) { mutableIntStateOf(0) }
    Layout(
        content = {
            chips.forEach { chip -> TagChip(chip, palette, clickable, onTagClick, onLongClick) }
            NamespaceOverflowPill(
                hiddenCount = hiddenCount.coerceAtLeast(1),
                namespace = namespace,
                palette = palette,
                onClick = onExpand,
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val chipCount = chips.size
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val chipPlaceables = measurables.take(chipCount).map { it.measure(loose) }
        val pillMeasurable = measurables[chipCount]
        val gap = 5.dp.roundToPx()
        val maxWidth = constraints.maxWidth

        var visibleCount = chipCount
        if (!expanded && chipCount > 0) {
            var count = 0
            var x = 0
            var rows = 1
            for (placeable in chipPlaceables) {
                if (x != 0 && x + placeable.width > maxWidth) {
                    rows += 1
                    x = 0
                }
                if (rows > COLLAPSED_NAMESPACED_ROW_LINES) break
                x += placeable.width + gap
                count += 1
            }
            visibleCount = count.coerceAtLeast(1)
        }

        val hasOverflow = !expanded && visibleCount < chipCount
        val pillPlaceable = if (hasOverflow) pillMeasurable.measure(loose) else null

        if (hasOverflow && pillPlaceable != null) {
            // Make sure the "+N more" pill fits on the second line without spilling onto a 3rd line.
            while (visibleCount > 1) {
                var x = 0
                var rows = 1
                for (i in 0 until visibleCount) {
                    val p = chipPlaceables[i]
                    if (x != 0 && x + p.width > maxWidth) {
                        rows += 1
                        x = 0
                    }
                    x += p.width + gap
                }
                val pillFitsCurrentLine = x == 0 || x + pillPlaceable.width <= maxWidth
                val totalRowsWithPill = if (pillFitsCurrentLine) rows else rows + 1
                if (totalRowsWithPill <= COLLAPSED_NAMESPACED_ROW_LINES) break
                visibleCount -= 1
            }
        }

        val currentHidden = (chipCount - visibleCount).coerceAtLeast(0)
        if (currentHidden != hiddenCount) {
            hiddenCount = currentHidden
        }
        onOverflowChanged(currentHidden > 0)

        val positions = ArrayList<Pair<Placeable, IntOffset>>(visibleCount + 1)
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until visibleCount) {
            val placeable = chipPlaceables[i]
            if (x != 0 && x + placeable.width > maxWidth) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            positions += placeable to IntOffset(x, y)
            x += placeable.width + gap
            rowHeight = maxOf(rowHeight, placeable.height)
        }
        if (hasOverflow && pillPlaceable != null) {
            if (x != 0 && x + pillPlaceable.width > maxWidth) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            positions += pillPlaceable to IntOffset(x, y)
            rowHeight = maxOf(rowHeight, pillPlaceable.height)
        }
        val totalHeight = if (positions.isEmpty()) 0 else y + rowHeight
        layout(maxWidth, totalHeight) {
            positions.forEach { (placeable, pos) -> placeable.place(pos.x, pos.y) }
        }
    }
}

@Composable
private fun NamespaceOverflowPill(
    hiddenCount: Int,
    namespace: String,
    palette: QuietShelfPalette,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.statusOk.copy(alpha = 0.11f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("detail-tag-row-more-$namespace"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = tr("+$hiddenCount more", "+$hiddenCount 更多", "+$hiddenCount 更多", "+$hiddenCount 件"),
            color = palette.statusOk,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TagChip(
    tag: DetailTag,
    palette: QuietShelfPalette,
    clickable: Boolean,
    onTagClick: (DetailTag) -> Unit,
    onLongClick: (DetailTag) -> Unit,
) {
    DetailTagChip(
        label = tag.label,
        palette = palette,
        onClick = if (clickable) ({ onTagClick(tag) }) else null,
        onLongClick = { onLongClick(tag) },
    )
}

/**
 * Flat tags collapse at two lines. Measure-then-place: every chip is measured, the leading chips that fit
 * in [COLLAPSED_FLAT_LINES] wrap lines are placed, and the disclosure sits below.
 */
@Composable
private fun MeasuredFlatTagChips(
    chips: List<DetailTag>,
    expanded: Boolean,
    total: Int,
    palette: QuietShelfPalette,
    clickable: Boolean,
    onTagClick: (DetailTag) -> Unit,
    onLongClick: (DetailTag) -> Unit,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            chips.forEach { chip -> TagChip(chip, palette, clickable, onTagClick, onLongClick) }
            TagsDisclosureLine(expanded, total, palette, onToggleExpanded)
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val chipCount = chips.size
        val chipPlaceables = measurables.take(chipCount)
            .map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val disclosure = measurables[chipCount].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = 5.dp.roundToPx()
        val maxWidth = constraints.maxWidth
        val visibleCount = if (expanded) chipCount else {
            var count = 0
            var x = 0
            var rows = 1
            for (placeable in chipPlaceables) {
                if (x != 0 && x + placeable.width > maxWidth) {
                    rows += 1
                    x = 0
                }
                x += placeable.width + gap
                if (rows > COLLAPSED_FLAT_LINES) break
                count += 1
            }
            count
        }
        val showDisclosure = expanded || visibleCount < chipCount
        val placed = chipPlaceables.take(visibleCount)
        var x = 0
        var y = 0
        var rowHeight = 0
        val positions = ArrayList<Pair<Placeable, IntOffset>>()
        placed.forEach { placeable ->
            if (x != 0 && x + placeable.width > maxWidth) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            positions += placeable to IntOffset(x, y)
            x += placeable.width + gap
            rowHeight = maxOf(rowHeight, placeable.height)
        }
        val chipsHeight = if (positions.isEmpty()) 0 else y + rowHeight
        val disclosureY = if (chipsHeight == 0) 0 else chipsHeight + gap
        val totalHeight = if (showDisclosure) disclosureY + disclosure.height else chipsHeight
        layout(maxWidth, totalHeight) {
            positions.forEach { (placeable, position) -> placeable.place(position.x, position.y) }
            if (showDisclosure) disclosure.place(0, disclosureY)
        }
    }
}

@Composable
private fun TagsDisclosureLine(
    expanded: Boolean,
    total: Int,
    palette: QuietShelfPalette,
    onToggleExpanded: () -> Unit,
) {
    Text(
        if (expanded) AppStrings.showFewerTags else AppStrings.showAllTags(total),
        modifier = Modifier
            .testTag("detail-tags-more")
            .clickable(onClick = onToggleExpanded)
            .padding(vertical = 2.dp),
        color = palette.muted,
    )
}

/** §2.6 loading: exactly two shimmer rows in the block's place. */
@Composable
private fun DetailTagsLoading() {
    Column(
        Modifier.fillMaxWidth().testTag("detail-tags-loading"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val widths = listOf(listOf(.30f, .36f, .22f), listOf(.34f, .28f, .26f))
        repeat(2) { row ->
            Row(
                Modifier.fillMaxWidth().testTag("detail-tags-shimmer-row-$row"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                widths[row].forEach { fraction ->
                    QuietShelfShimmer(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(24.dp)
                            .clearAndSetSemantics {},
                    )
                }
            }
        }
    }
}

/** Tag group headings are app wording, translated by namespace; an unknown namespace keeps the source's own word. */
internal fun tagRowLabel(namespace: String, english: String): String = when (namespace) {
    "tag" -> tr(english, "标签", "標籤", "タグ")
    "language" -> tr(english, "语言", "語言", "言語")
    "parody" -> tr(english, "原作", "原作", "原作")
    "character" -> tr(english, "角色", "角色", "キャラクター")
    "artist" -> tr(english, "作者", "作者", "作者")
    "group" -> tr(english, "社团", "社團", "サークル")
    "cosplayer" -> tr(english, "Coser", "Coser", "コスプレイヤー")
    "female" -> tr(english, "女性", "女性", "女性")
    "male" -> tr(english, "男性", "男性", "男性")
    "mixed" -> tr(english, "混合", "混合", "混合")
    "other" -> tr(english, "其他", "其他", "その他")
    "location" -> tr(english, "地点", "地點", "場所")
    else -> english
}
