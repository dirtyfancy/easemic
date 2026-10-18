package com.easemic.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val QuietShelfLightScheme = lightColorScheme(
    primary = QuietShelfColors.Light.ink,
    onPrimary = QuietShelfColors.Light.surface,
    secondary = QuietShelfColors.Light.statusOk,
    onSecondary = QuietShelfColors.Light.surface,
    tertiary = QuietShelfColors.Light.statusWarn,
    background = QuietShelfColors.Light.surface,
    onBackground = QuietShelfColors.Light.ink,
    surface = QuietShelfColors.Light.surface,
    onSurface = QuietShelfColors.Light.ink,
    surfaceVariant = QuietShelfColors.Light.card,
    onSurfaceVariant = QuietShelfColors.Light.ink,
    surfaceContainerLowest = QuietShelfColors.Light.surface,
    surfaceContainerLow = QuietShelfColors.Light.surface,
    surfaceContainer = QuietShelfColors.Light.card,
    surfaceContainerHigh = QuietShelfColors.Light.card,
    surfaceContainerHighest = QuietShelfColors.Light.card,
    outline = QuietShelfColors.Light.line,
    error = QuietShelfColors.Light.statusBad,
    onError = QuietShelfColors.Light.surface,
)

private val QuietShelfDarkScheme = darkColorScheme(
    primary = QuietShelfColors.Dark.statusOk,
    onPrimary = QuietShelfColors.Dark.surface,
    secondary = QuietShelfColors.Dark.statusOk,
    onSecondary = QuietShelfColors.Dark.surface,
    tertiary = QuietShelfColors.Dark.statusWarn,
    background = QuietShelfColors.Dark.surface,
    onBackground = QuietShelfColors.Dark.ink,
    surface = QuietShelfColors.Dark.surface,
    onSurface = QuietShelfColors.Dark.ink,
    surfaceVariant = QuietShelfColors.Dark.card,
    onSurfaceVariant = QuietShelfColors.Dark.ink,
    surfaceContainerLowest = QuietShelfColors.Dark.surface,
    surfaceContainerLow = QuietShelfColors.Dark.surface,
    surfaceContainer = QuietShelfColors.Dark.card,
    surfaceContainerHigh = QuietShelfColors.Dark.card,
    surfaceContainerHighest = QuietShelfColors.Dark.card,
    outline = QuietShelfColors.Dark.line,
    error = QuietShelfColors.Dark.statusBad,
    onError = QuietShelfColors.Dark.surface,
)

@Composable
fun PrivateComicLibraryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) QuietShelfDarkScheme else QuietShelfLightScheme,
        typography = QuietShelfTypography,
        content = content,
    )
}
