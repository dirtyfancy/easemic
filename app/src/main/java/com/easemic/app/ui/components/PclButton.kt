package com.easemic.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.easemic.app.ui.theme.PclSpacing
import com.easemic.app.ui.theme.QuietShelfPalette
import com.easemic.app.ui.theme.quietShelfPalette

/**
 * PCL filled (primary) button — Material 3 [Button] with Quiet Shelf defaults.
 * API shape mirrors Now in Android design-system buttons (Apache-2.0 pattern;
 * independently reimplemented).
 *
 * Primary CTA uses Quiet Shelf ink (not MaterialTheme.primary), matching the
 * established Import FAB / brand filled-button contract in both light and dark.
 */
@Composable
fun PclButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = PclButtonDefaults.MinHeight),
        enabled = enabled,
        colors = PclButtonDefaults.buttonColors(),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun PclButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: @Composable () -> Unit,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    PclButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = pclContentPadding(leadingIcon != null),
    ) {
        PclButtonContent(text = text, leadingIcon = leadingIcon)
    }
}

/**
 * PCL tonal button — Material 3 [FilledTonalButton] with Quiet Shelf defaults.
 */
@Composable
fun PclTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = PclButtonDefaults.MinHeight),
        enabled = enabled,
        colors = PclButtonDefaults.filledTonalButtonColors(),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun PclTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: @Composable () -> Unit,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    PclTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = pclContentPadding(leadingIcon != null),
    ) {
        PclButtonContent(text = text, leadingIcon = leadingIcon)
    }
}

/**
 * PCL outlined button — Material 3 [OutlinedButton] with Quiet Shelf defaults.
 */
@Composable
fun PclOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val palette = quietShelfPalette()
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = PclButtonDefaults.MinHeight),
        enabled = enabled,
        colors = PclButtonDefaults.outlinedButtonColors(palette),
        border = PclButtonDefaults.outlinedBorder(palette, enabled),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun PclOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: @Composable () -> Unit,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    PclOutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = pclContentPadding(leadingIcon != null),
    ) {
        PclButtonContent(text = text, leadingIcon = leadingIcon)
    }
}

/**
 * PCL text button — Material 3 [TextButton] with Quiet Shelf defaults.
 */
@Composable
fun PclTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = PclButtonDefaults.MinHeight),
        enabled = enabled,
        colors = PclButtonDefaults.textButtonColors(),
        content = content,
    )
}

@Composable
fun PclTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: @Composable () -> Unit,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    PclTextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        PclButtonContent(text = text, leadingIcon = leadingIcon)
    }
}

@Composable
private fun PclButtonContent(
    text: @Composable () -> Unit,
    leadingIcon: @Composable (() -> Unit)?,
) {
    if (leadingIcon != null) {
        Box(Modifier.sizeIn(maxHeight = PclButtonDefaults.IconSize)) {
            leadingIcon()
        }
    }
    Box(
        Modifier.padding(
            start = if (leadingIcon != null) PclButtonDefaults.IconSpacing else 0.dp,
        ),
    ) {
        text()
    }
}

private fun pclContentPadding(hasLeadingIcon: Boolean): PaddingValues =
    if (hasLeadingIcon) {
        ButtonDefaults.ButtonWithIconContentPadding
    } else {
        ButtonDefaults.ContentPadding
    }

/**
 * Defaults shared by PCL button roles.
 *
 * Color roles map onto [QuietShelfPalette] (not MaterialTheme.primary), so
 * filled CTAs stay ink/surface in dark theme even though Material primary is
 * the Quiet Shelf statusOk accent.
 */
object PclButtonDefaults {
    val MinHeight = 48.dp
    val IconSize = 18.dp
    val IconSpacing = PclSpacing.ExtraSmall
    val OutlinedBorderWidth = 1.dp
    const val DisabledOutlinedBorderAlpha = 0.12f

    /** Filled primary CTA container — Quiet Shelf ink. */
    fun filledContainerColor(palette: QuietShelfPalette): Color = palette.ink

    /** Filled primary CTA label — Quiet Shelf surface. */
    fun filledContentColor(palette: QuietShelfPalette): Color = palette.surface

    /** Tonal container — Quiet Shelf card. */
    fun tonalContainerColor(palette: QuietShelfPalette): Color = palette.card

    /** Tonal / outlined / text label — Quiet Shelf ink. */
    fun onShelfColor(palette: QuietShelfPalette): Color = palette.ink

    /** Enabled outlined border — Quiet Shelf line. */
    fun outlinedBorderColor(palette: QuietShelfPalette, enabled: Boolean): Color =
        if (enabled) {
            palette.line
        } else {
            palette.ink.copy(alpha = DisabledOutlinedBorderAlpha)
        }

    @Composable
    fun buttonColors(palette: QuietShelfPalette = quietShelfPalette()): ButtonColors =
        ButtonDefaults.buttonColors(
            containerColor = filledContainerColor(palette),
            contentColor = filledContentColor(palette),
        )

    @Composable
    fun filledTonalButtonColors(
        palette: QuietShelfPalette = quietShelfPalette(),
    ): ButtonColors =
        ButtonDefaults.filledTonalButtonColors(
            containerColor = tonalContainerColor(palette),
            contentColor = onShelfColor(palette),
        )

    @Composable
    fun outlinedButtonColors(
        palette: QuietShelfPalette = quietShelfPalette(),
    ): ButtonColors =
        ButtonDefaults.outlinedButtonColors(
            contentColor = onShelfColor(palette),
        )

    @Composable
    fun textButtonColors(
        palette: QuietShelfPalette = quietShelfPalette(),
    ): ButtonColors =
        ButtonDefaults.textButtonColors(
            contentColor = onShelfColor(palette),
        )

    fun outlinedBorder(
        palette: QuietShelfPalette,
        enabled: Boolean,
    ): BorderStroke =
        BorderStroke(
            width = OutlinedBorderWidth,
            color = outlinedBorderColor(palette, enabled),
        )
}
