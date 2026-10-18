package com.easemic.app.feature.detail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.easemic.app.ui.components.PclButton
import com.easemic.app.ui.theme.QuietShelfPalette

/**
 * Skeleton spec §2.1.3 (Part 4 Chunk D): Read/Continue pill → Collect switch →
 * Download switch, this order for every source. The switches arrive as the
 * caller's slot — their presence and gating stay with the caller (a serialized
 * detail without the #E73 picker passes no Download switch, never an inert one).
 *
 * [serializedCrowded] carries `SerializedDetailActionRow`'s SubcomposeLayout
 * stacking rule (`SerializedContinueLabel.kt`:91-124) verbatim: when the label's
 * fixed run + button padding + gap + switches cannot share one line, the pill
 * takes the full width and the switches stack under it, right-aligned. False is
 * the plain row `DetailSkin` renders today (`RemoteCatalogDetailScreen.kt`:786-801).
 * The pill keeps its "remote-read" test tag (plan decision 9).
 */
@Composable
internal fun DetailActionRow(
    palette: QuietShelfPalette,
    readLabel: String,
    readEnabled: Boolean,
    onRead: () -> Unit,
    switches: (@Composable () -> Unit)? = null,
    serializedCrowded: Boolean = false,
) {
    if (!serializedCrowded) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PclButton(
                onClick = onRead,
                enabled = readEnabled,
                modifier = Modifier
                    .weight(1f)
                    .testTag("remote-read"),
            ) {
                Text(readLabel)
            }
            switches?.invoke()
        }
    } else {
        val measurer = rememberTextMeasurer()
        val style = MaterialTheme.typography.labelLarge
        // The plain label is the row's one fixed run (the serialized row fixed the
        // verb/separator/suffix runs and let only its chapter shrink).
        val fixedWidth = measurer.measure(readLabel, style, softWrap = false).size.width
        SubcomposeLayout { constraints ->
            val gap = 10.dp.roundToPx()
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val switch = subcompose("switch") { switches?.invoke() }.map { it.measure(loose) }
            val switchWidth = switch.sumOf { it.width }
            val padding = ButtonDefaults.ContentPadding.calculateLeftPadding(layoutDirection).roundToPx() +
                ButtonDefaults.ContentPadding.calculateRightPadding(layoutDirection).roundToPx()
            val stacked = switchWidth > 0 && fixedWidth + padding + gap + switchWidth > constraints.maxWidth
            val buttonWidth =
                if (stacked || switchWidth == 0) constraints.maxWidth else constraints.maxWidth - switchWidth - gap
            val button = subcompose("read") {
                PclButton(
                    onClick = onRead,
                    enabled = readEnabled,
                    modifier = Modifier.testTag("remote-read"),
                ) {
                    Text(readLabel)
                }
            }.single().measure(Constraints.fixedWidth(buttonWidth))
            val switchHeight = switch.maxOfOrNull { it.height } ?: 0
            val height = if (stacked) button.height + gap + switchHeight else maxOf(button.height, switchHeight)
            layout(constraints.maxWidth, height) {
                button.placeRelative(0, if (stacked) 0 else (height - button.height) / 2)
                var x = constraints.maxWidth - switchWidth
                switch.forEach {
                    it.placeRelative(x, if (stacked) button.height + gap else (height - it.height) / 2)
                    x += it.width
                }
            }
        }
    }
}
