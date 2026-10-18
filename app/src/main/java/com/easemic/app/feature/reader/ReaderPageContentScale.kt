package com.easemic.app.feature.reader

import androidx.compose.ui.layout.ContentScale

/** Maps the fit-width preference to Compose [ContentScale]. */
object ReaderPageContentScale {
    fun forFitWidth(fitWidth: Boolean): ContentScale =
        if (fitWidth) ContentScale.FillWidth else ContentScale.Fit
}
