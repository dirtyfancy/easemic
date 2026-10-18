package com.easemic.app.feature.reader

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView
import com.easemic.app.i18n.AppStrings

/**
 * Reader page counter chrome shown while a Ready comic is on screen.
 */
object ReaderPageChrome {
    val COUNTER_CONTENT_DESCRIPTION: String get() = AppStrings.readerPageCounter

    /**
     * Formats a page counter as `"current/total"` with a 1-based current page.
     *
     * [ordinal0Based] is the zero-based pager index; it is clamped into
     * `[0, total - 1]` before display. A non-positive [total] has no valid
     * page to show, so the safe fallback `"0/0"` is returned instead of a
     * misleading or out-of-range counter.
     */
    fun formatCounter(ordinal0Based: Int, total: Int): String {
        if (total <= 0) return "0/0"
        val current = (ordinal0Based + 1).coerceIn(1, total)
        return "$current/$total"
    }
}

/**
 * Keeps the screen awake for as long as the composable using this modifier
 * remains in the composition, releasing the request when it leaves.
 */
fun Modifier.keepScreenOn(enabled: Boolean = true): Modifier = composed {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
    this
}
