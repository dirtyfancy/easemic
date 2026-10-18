package com.easemic.app.feature.reader

import com.easemic.app.i18n.AppStrings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import kotlin.math.abs

/** Builds the exact copy shared by local and cloud serialized readers. */
internal fun chapterTransitionCopy(
    currentLabel: String,
    adjacentLabel: String?,
    forward: Boolean,
): String = when {
    adjacentLabel != null && forward -> "$currentLabel 完 · 下一话：$adjacentLabel"
    adjacentLabel != null -> "$currentLabel · 上一话：$adjacentLabel"
    forward -> "已是最新一话"
    else -> "已是第一话"
}

/** The shared transition card used by local and cloud chapter readers. */
@Composable
internal fun ChapterTransitionCard(
    text: String,
    testTag: String,
    forward: Boolean,
    rtlPagingEnabled: Boolean,
    onPass: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val latestOnPass by rememberUpdatedState(onPass)
    val latestOnPrevious by rememberUpdatedState(onPrevious)
    val latestOnNext by rememberUpdatedState(onNext)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(testTag)
            .pointerInput(forward, rtlPagingEnabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    var lastX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: break
                        lastX = change.position.x
                        if (!change.pressed) break
                    }
                    val dragDistance = lastX - down.position.x
                    if (abs(dragDistance) > viewConfiguration.touchSlop) {
                        val forwardSwipe = if (rtlPagingEnabled) {
                            dragDistance > 0f
                        } else {
                            dragDistance < 0f
                        }
                        if (forwardSwipe == forward) latestOnPass()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag("reader-zone-previous")
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = AppStrings.previousChapter,
                        onClick = { latestOnPrevious() },
                    ),
            )
            Box(modifier = Modifier.weight(1f).fillMaxHeight())
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag("reader-zone-next")
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = AppStrings.nextChapter,
                        onClick = { latestOnNext() },
                    ),
            )
        }
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
