package com.easemic.app.feature.detail.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.easemic.app.i18n.AppStrings
import com.easemic.app.ui.theme.QuietShelfPalette

/**
 * Skeleton spec §2.1.1 (Part 4 Chunk D, decision 10): the detail top bar —
 * Back on the left, More (⋯) on the right only when it holds an action. The
 * More button replaces today's "Delete saved chapters" text button under the
 * SAME gate the overflow slot uses (`RemoteCatalogDetailScreen.kt`:469/:566-577:
 * saved serialized chapters + a delete callback); the gate itself stays with the
 * caller, which passes `moreActions = null` when it does not hold. No other
 * actions, and no glyph at all when the slot is null — same as today's null
 * overflow.
 *
 * The Back pill is [DetailSkin]'s exactly (`RemoteCatalogDetailScreen.kt`:739-751).
 * The ⋯ glyph is three small circles drawn on Canvas — the app has no
 * Material-icons dependency and does not gain one (LibraryBadgeIcons idiom).
 */
@Composable
internal fun DetailTopBar(
    palette: QuietShelfPalette,
    onBack: () -> Unit,
    moreActions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val actions = moreActions
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = onBack,
            shape = RoundedCornerShape(999.dp),
            color = palette.card,
            border = BorderStroke(1.dp, palette.line),
            modifier = Modifier.semantics { contentDescription = "Back" },
        ) {
            Text(
                AppStrings.back,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (actions != null) {
            Box {
                Surface(
                    onClick = { menuOpen = true },
                    shape = RoundedCornerShape(999.dp),
                    color = palette.card,
                    border = BorderStroke(1.dp, palette.line),
                    modifier = Modifier
                        .testTag("detail-more")
                        .semantics { contentDescription = "More options" },
                ) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        MoreGlyph(palette = palette)
                    }
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    content = actions,
                )
            }
        }
    }
}

/** A horizontal ⋯ — three small ink circles, drawn (no icon dependency). */
@Composable
private fun MoreGlyph(palette: QuietShelfPalette) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val radius = 1.6.dp.toPx()
        val cy = size.height / 2f
        listOf(0.22f, 0.5f, 0.78f).forEach { fx ->
            drawCircle(
                color = palette.ink,
                radius = radius,
                center = Offset(size.width * fx, cy),
            )
        }
    }
}
