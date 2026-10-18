package com.easemic.app.feature.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.easemic.app.i18n.AppStrings
import com.easemic.app.ui.components.PclTextButton
import com.easemic.app.ui.theme.PclSpacing
import com.easemic.app.ui.theme.quietShelfPalette

/**
 * Task 6 (quiet-shelf): the Library filter bottom sheet.
 *
 * Four rows — an "All comics" reset row plus three independent multi-select
 * toggle rows (Collected / Downloaded / Imported) — each carrying a live count
 * computed from the current shelf, and a bottom "Clear filters" action. The
 * sheet stays open between toggles so filters stack; selection state belongs
 * to the caller via [filter]/[onFilterChange] (production binds
 * [LibraryViewModel]'s shelf state).
 *
 * Counts and item filtering both come from [ShelfFacts] so the numbers always
 * agree with what the filter would show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryFilterSheet(
    filter: LibraryStateFilter,
    counts: ShelfCounts,
    onFilterChange: (LibraryStateFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val palette = quietShelfPalette()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PclSpacing.Medium)
                .padding(bottom = PclSpacing.Large),
        ) {
            Text(
                AppStrings.filterTitle,
                style = MaterialTheme.typography.titleMedium,
                color = palette.ink,
            )
            Spacer(modifier = Modifier.height(PclSpacing.Small))
            LibraryFilterRow(
                label = AppStrings.filterAllComics,
                count = counts.total,
                selected = !filter.any,
                onClick = { onFilterChange(LibraryStateFilter()) },
                testTag = "library-filter-row-all",
                isToggle = false,
            )
            LibraryFilterRow(
                label = AppStrings.filterCollected,
                count = counts.collected,
                selected = filter.collected,
                onClick = { onFilterChange(filter.copy(collected = !filter.collected)) },
                testTag = "library-filter-row-collected",
            )
            LibraryFilterRow(
                label = AppStrings.filterDownloaded,
                count = counts.downloaded,
                selected = filter.downloaded,
                onClick = { onFilterChange(filter.copy(downloaded = !filter.downloaded)) },
                testTag = "library-filter-row-downloaded",
            )
            LibraryFilterRow(
                label = AppStrings.filterImported,
                count = counts.imported,
                selected = filter.imported,
                onClick = { onFilterChange(filter.copy(imported = !filter.imported)) },
                testTag = "library-filter-row-imported",
            )
            Spacer(modifier = Modifier.height(PclSpacing.Small))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                PclTextButton(
                    onClick = { onFilterChange(LibraryStateFilter()) },
                ) {
                    Text(AppStrings.clearFilters, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(modifier = Modifier.width(PclSpacing.Small))
                PclTextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("library-filter-dismiss"),
                ) {
                    Text(AppStrings.done, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Live per-shelf counts behind the filter sheet rows. */
data class ShelfCounts(
    val total: Int,
    val collected: Int,
    val downloaded: Int,
    val imported: Int,
)

/**
 * Counts over the merged shelf (local + collected + cloud), using the same
 * per-entry [ShelfEntry.shelfFacts] the filter itself matches against, so the
 * sheet's numbers always agree with what toggling a row actually shows.
 */
fun computeShelfCounts(entries: List<ShelfEntry>): ShelfCounts {
    var collected = 0
    var downloaded = 0
    var imported = 0
    entries.forEach { entry ->
        val facts = entry.shelfFacts()
        if (facts.collected) collected++
        if (facts.downloaded) downloaded++
        if (facts.imported) imported++
    }
    return ShelfCounts(
        total = entries.size,
        collected = collected,
        downloaded = downloaded,
        imported = imported,
    )
}

@Composable
private fun LibraryFilterRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    isToggle: Boolean = true,
) {
    val palette = quietShelfPalette()
    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .testTag(testTag)
    Surface(
        onClick = onClick,
        modifier = rowModifier.semantics(mergeDescendants = true) {
            // Merged so the row reads "All comics — 12" / "Collected — 3"
            // (toggle rows additionally expose their on/off state via
            // toggleableState semantics).
            this.contentDescription = "$label — $count"
            if (isToggle) {
                toggleableState = ToggleableState(selected)
            }
        },
        shape = RoundedCornerShape(10.dp),
        color = if (selected) palette.ink.copy(alpha = 0.06f) else palette.surface,
        contentColor = palette.ink,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelLarge,
                color = palette.muted,
            )
            Spacer(modifier = Modifier.width(4.dp))
            LibraryFilterCheckBox(selected = selected)
        }
    }
}

/** Quiet-shelf check square on the row's right edge. */
@Composable
private fun LibraryFilterCheckBox(selected: Boolean) {
    val palette = quietShelfPalette()
    Surface(
        color = if (selected) palette.statusOk else palette.surface,
        shape = RoundedCornerShape(5.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (selected) palette.statusOk else palette.muted.copy(alpha = 0.6f),
        ),
        modifier = Modifier
            .width(20.dp)
            .height(20.dp),
    ) {
        if (selected) {
            Canvas(modifier = Modifier.width(20.dp).height(20.dp)) {
                val stroke = 1.75.dp.toPx()
                // checkmark stroke
                drawLine(
                    color = palette.surface,
                    start = androidx.compose.ui.geometry.Offset(5.dp.toPx(), 10.5.dp.toPx()),
                    end = androidx.compose.ui.geometry.Offset(9.dp.toPx(), 14.5.dp.toPx()),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawLine(
                    color = palette.surface,
                    start = androidx.compose.ui.geometry.Offset(9.dp.toPx(), 14.5.dp.toPx()),
                    end = androidx.compose.ui.geometry.Offset(15.dp.toPx(), 6.dp.toPx()),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
    }
}
