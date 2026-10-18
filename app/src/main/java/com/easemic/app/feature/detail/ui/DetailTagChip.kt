package com.easemic.app.feature.detail.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.easemic.app.ui.theme.QuietShelfColors
import com.easemic.app.ui.theme.QuietShelfPalette

/**
 * Detail tag chip (#E92, skeleton spec §2.5): tonal ink fill over the palette
 * surface, no border, 8 dp radius, 13 sp ink label.
 *
 * A plain chip (no [onClick]) is inert text: no press state, no focus ring, no
 * 36 dp tap-target floor, no click semantics (spec §2.4). [onLongClick] —
 * long-press copy, wired by the tag block — works on both kinds; a plain chip
 * takes it as a raw long-press gesture so nothing else becomes tappable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DetailTagChip(
    label: String,
    palette: QuietShelfPalette,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val clickable = onClick != null
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val dark = palette.surface == QuietShelfColors.Dark.surface
    val fill = palette.ink.copy(alpha = when {
        clickable && pressed -> if (dark) .15f else .11f
        dark -> .09f
        else -> .06f
    })
    Box(
        modifier
            .detailTagChipRing(palette.ink, drawRing = clickable && focused)
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .then(
                when {
                    onClick != null -> Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                    onLongClick != null -> Modifier.pointerInput(onLongClick) {
                        detectTapGestures(onLongPress = { onLongClick() })
                    }
                    else -> Modifier
                }
            )
            .padding(horizontal = 9.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = palette.ink, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 2 dp ink ring with a 2 dp offset — same idiom as the chapter cell's focus ring. */
private fun Modifier.detailTagChipRing(ink: Color, drawRing: Boolean) =
    drawWithContent {
        drawContent()
        if (drawRing) {
            val outside = 3.dp.toPx() // 2 dp gap plus half the 2 dp stroke.
            drawRoundRect(
                ink,
                Offset(-outside, -outside),
                Size(size.width + 2 * outside, size.height + 2 * outside),
                CornerRadius(11.dp.toPx()),
                style = Stroke(2.dp.toPx()),
            )
        }
    }
