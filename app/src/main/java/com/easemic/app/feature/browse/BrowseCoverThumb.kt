package com.easemic.app.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import androidx.compose.ui.platform.LocalContext
import com.easemic.app.ui.components.BrokenCoverPlaceholder
import com.easemic.app.ui.theme.quietShelfPalette

const val QUIET_SHELF_COVER_WIDTH_DP = 72
const val QUIET_SHELF_COVER_HEIGHT_DP = 96

@Composable
fun BrowseCoverThumb(
    coverUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    placeholderTestTag: String = "browse-cover-placeholder",
    imageTestTag: String = "browse-cover-image",
    /**
     * A62 (spec test 6): injectable so a device test can drive the error placeholder
     * with a loader whose fetcher throws. Null = the singleton, as everywhere else.
     */
    imageLoader: ImageLoader? = null,
) {
    val palette = quietShelfPalette()
    val shape = RoundedCornerShape(9.dp)
    val sized = if (modifier === Modifier) {
        Modifier.size(
            width = QUIET_SHELF_COVER_WIDTH_DP.dp,
            height = QUIET_SHELF_COVER_HEIGHT_DP.dp,
        )
    } else {
        modifier
    }
    val placeholder = sized
        .clip(shape)
        .background(palette.line)
        .border(1.dp, palette.ink.copy(alpha = 0.08f), shape)
    if (coverUrl.isNullOrBlank()) {
        Box(
            modifier = placeholder.testTag(placeholderTestTag),
        )
    } else {
        // A62 (spec D4): a remote cover that fails to load renders the shared broken-image
        // glyph inside the tile — no retry (the tile re-fetches on the next shelf epoch
        // or when its source re-resolves the URL).
        var coverFailed by remember(coverUrl) { mutableStateOf(false) }
        Box(modifier = placeholder.testTag(imageTestTag)) {
            if (coverFailed) {
                BrokenCoverPlaceholder(modifier = Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    imageLoader = imageLoader
                        ?: SingletonImageLoader.get(LocalContext.current),
                    onError = { coverFailed = true },
                )
            }
        }
    }
}
