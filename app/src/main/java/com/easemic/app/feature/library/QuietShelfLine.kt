package com.easemic.app.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.easemic.app.ui.components.PclTextButton
import com.easemic.app.ui.theme.PclSpacing
import com.easemic.app.ui.theme.quietShelfPalette

/**
 * One transient line of truth at the bottom of a Quiet Shelf surface: a
 * sentence and at most one reversal. No icon, no colour, no celebration
 * (A30 §2.1). Its owner is responsible for clearing it after 6 s.
 */
data class ShelfLine(
    val text: String,
    val testTag: String,
    val actionLabel: String? = null,
    val actionTestTag: String? = null,
)

@Composable
fun QuietShelfLine(
    line: ShelfLine,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = quietShelfPalette()
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = palette.card,
        border = BorderStroke(1.dp, palette.line),
        // Review LOW-4: a screen reader should hear the outcome line the
        // moment it appears, the same way a sighted user notices new text at
        // the bottom of the shelf, without needing to move focus onto it.
        modifier = modifier
            .testTag(line.testTag)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(
                start = PclSpacing.Small,
                end = PclSpacing.ExtraSmall,
                top = PclSpacing.ExtraSmall,
                bottom = PclSpacing.ExtraSmall,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PclSpacing.ExtraSmall),
        ) {
            Text(
                line.text,
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink,
                modifier = Modifier.weight(1f),
            )
            if (line.actionLabel != null) {
                PclTextButton(
                    onClick = onAction,
                    modifier = line.actionTestTag
                        ?.let { Modifier.testTag(it) }
                        ?: Modifier,
                ) {
                    Text(line.actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
