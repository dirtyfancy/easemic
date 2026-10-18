package com.easemic.app.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.easemic.app.i18n.AppStrings

@Composable
fun rememberReaderImageProgress(url: String): ReaderImageProgressSnapshot {
    val tracker = ReaderImageProgressTracker.shared
    val key = remember(url) { ReaderImageProgressTracker.canonicalKey(url) }
    val flow = remember(key) { tracker.watch(key) }
    DisposableEffect(key) {
        onDispose { tracker.unwatch(key) }
    }
    val snapshot by flow.collectAsState()
    return snapshot
}

@Composable
fun ReaderPageLoadOverlay(
    presentation: ReaderPageLoadPresentation,
    modifier: Modifier = Modifier,
) {
    if (!presentation.visible) return
    Column(
        modifier = modifier
            .testTag(ReaderPageLoadProgress.TEST_TAG)
            .semantics { contentDescription = AppStrings.loadingPage },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val indicatorModifier = Modifier.size(56.dp)
        if (presentation.determinate) {
            CircularProgressIndicator(
                progress = { presentation.fraction },
                modifier = indicatorModifier,
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.24f),
                strokeWidth = 3.dp,
            )
        } else {
            CircularProgressIndicator(
                modifier = indicatorModifier,
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.24f),
                strokeWidth = 3.dp,
            )
        }
        val label = presentation.label
        if (label != null) {
            Text(
                text = label,
                color = Color.White,
                modifier = Modifier.testTag(ReaderPageLoadProgress.PERCENT_TAG),
            )
        }
    }
}
