package com.easemic.app.feature.browse

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.easemic.app.R
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.providers.ExhentaiEnableStore
import com.easemic.app.feature.providers.NhentaiEnableStore

/**
 * #D5: an official source shows its own site mark on the site's own ground (E-Hentai / ExHentai: the favicon's
 * dark-red "E-H" on its pale page colour; nhentai: its logo on its near-black header). An OPDS catalog has no mark of
 * ours to show, so it keeps the letter monogram (More-row convention). #D8: Settings uses the same tiles — nhentai's
 * white "n" disappears on a light card without its ground.
 */
internal data class SourceLogo(val drawable: Int, val ground: Color, val inset: Dp)

internal fun sourceLogoFor(sourceId: String): SourceLogo? = when (sourceId) {
    EhentaiEnableStore.SOURCE_ID, ExhentaiEnableStore.SOURCE_ID ->
        SourceLogo(R.drawable.ic_source_logo_ehentai, Color(0xFFE3E0D1), 9.dp)
    NhentaiEnableStore.SOURCE_ID ->
        SourceLogo(R.drawable.ic_source_logo_nhentai, Color(0xFF1F1F1F), 5.dp)
    else -> null
}

/** The mark on its ground, [size] square; the inset scales with the tile (40 dp is the Browse list's size). */
@Composable
internal fun SourceLogoTile(sourceId: String, size: Dp, testTag: String, modifier: Modifier = Modifier) {
    val logo = sourceLogoFor(sourceId) ?: return
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(logo.ground)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(logo.drawable),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(logo.inset * (size / 40.dp)),
        )
    }
}
