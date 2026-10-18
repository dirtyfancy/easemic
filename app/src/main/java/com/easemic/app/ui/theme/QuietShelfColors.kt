package com.easemic.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

object QuietShelfColors {
    object Light {
        val surface = Color(0xFFF5F2EB)
        val ink = Color(0xFF1C1915)
        val statusOk = Color(0xFF3D5C45)
        val statusWarn = Color(0xFF9A6B2F)
        val statusBad = Color(0xFF8A3B3B)
        val cloudUploaded = Color(0xFFF38020)
        val cloudGlyph = Color(0xFFB5560A)
        val card = Color(0xFFFAF8F3)
        val muted = Color(0x731C1915)
        val line = Color(0x121C1915)
    }

    object Dark {
        val surface = Color(0xFF111110)
        val ink = Color(0xFFEEF0F4)
        val statusOk = Color(0xFF7DD3B0)
        val statusWarn = Color(0xFFFBBF24)
        val statusBad = Color(0xFFFCA5A5)
        val cloudUploaded = Color(0xFFF9A03C)
        val cloudGlyph = cloudUploaded
        val card = Color(0xFF1A1917)
        val muted = Color(0x73EEF0F4)
        val line = Color(0x16FFFFFF)
    }
}

@Immutable
data class QuietShelfPalette(
    val surface: Color,
    val ink: Color,
    val statusOk: Color,
    val statusWarn: Color,
    val statusBad: Color,
    val cloudUploaded: Color,
    val cloudGlyph: Color,
    val card: Color,
    val muted: Color,
    val line: Color,
)

@Composable
fun quietShelfPalette(darkTheme: Boolean = isSystemInDarkTheme()): QuietShelfPalette =
    if (darkTheme) {
        QuietShelfPalette(
            surface = QuietShelfColors.Dark.surface,
            ink = QuietShelfColors.Dark.ink,
            statusOk = QuietShelfColors.Dark.statusOk,
            statusWarn = QuietShelfColors.Dark.statusWarn,
            statusBad = QuietShelfColors.Dark.statusBad,
            cloudUploaded = QuietShelfColors.Dark.cloudUploaded,
            cloudGlyph = QuietShelfColors.Dark.cloudGlyph,
            card = QuietShelfColors.Dark.card,
            muted = QuietShelfColors.Dark.muted,
            line = QuietShelfColors.Dark.line,
        )
    } else {
        QuietShelfPalette(
            surface = QuietShelfColors.Light.surface,
            ink = QuietShelfColors.Light.ink,
            statusOk = QuietShelfColors.Light.statusOk,
            statusWarn = QuietShelfColors.Light.statusWarn,
            statusBad = QuietShelfColors.Light.statusBad,
            cloudUploaded = QuietShelfColors.Light.cloudUploaded,
            cloudGlyph = QuietShelfColors.Light.cloudGlyph,
            card = QuietShelfColors.Light.card,
            muted = QuietShelfColors.Light.muted,
            line = QuietShelfColors.Light.line,
        )
    }
