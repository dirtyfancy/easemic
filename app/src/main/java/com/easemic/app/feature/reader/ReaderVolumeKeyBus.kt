package com.easemic.app.feature.reader

import android.view.KeyEvent
import java.util.concurrent.atomic.AtomicReference

/** Which way a volume key pages. */
enum class VolumeKeyDirection { PREVIOUS, NEXT }

/**
 * Owner bug 2026-09-10 (HONOR PGT-AN20): hardware volume-key paging did nothing.
 * The readers relied on Compose `Modifier.onKeyEvent` on a focusable Box, but
 * hardware VOLUME_UP/DOWN are dispatched to the Activity window and only reach a
 * focused Compose node when it actually holds focus — the `HorizontalPager`
 * steals focus after the first swipe, and some OEMs consume volume keys for the
 * ringer/media UI before the view tree ever sees them. So the keys must be
 * intercepted at [android.app.Activity.dispatchKeyEvent], which gets first crack
 * before the AudioManager fallback.
 *
 * This bus is the seam: the on-screen reader [register]s a handler while it is
 * composed (and only while volume-key paging is enabled); MainActivity's
 * dispatchKeyEvent calls [dispatch]. When no reader is registered, [dispatch]
 * returns false and the system handles volume normally everywhere else in the
 * app. Single-handler by design — only one reader is on screen at a time; a new
 * registration replaces the old.
 */
object ReaderVolumeKeyBus {
    private val handler = AtomicReference<((VolumeKeyDirection) -> Unit)?>(null)

    fun register(onDirection: (VolumeKeyDirection) -> Unit) {
        handler.set(onDirection)
    }

    /** Clears [onDirection] only if it is still the active handler (avoids a late unregister clobbering a newer reader). */
    fun unregister(onDirection: (VolumeKeyDirection) -> Unit) {
        handler.compareAndSet(onDirection, null)
    }

    /**
     * Consumes a volume key when a reader is registered: pages on ACTION_DOWN
     * and swallows both DOWN and UP so the system volume UI never appears.
     * Returns false (system handles it) for non-volume keys or when no reader
     * is active.
     */
    fun dispatch(event: KeyEvent): Boolean {
        val direction = directionFor(event.keyCode) ?: return false
        val active = handler.get() ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && !event.isCanceled) {
            active(direction)
        }
        return true
    }

    /** True when this key is a volume key AND a reader is registered to consume it. */
    fun shouldConsume(keyCode: Int, hasActiveReader: Boolean): Boolean =
        hasActiveReader && directionFor(keyCode) != null

    /** Maps a key code to a paging direction, or null when it is not a volume key. */
    fun directionFor(keyCode: Int): VolumeKeyDirection? = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> VolumeKeyDirection.PREVIOUS
        KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeKeyDirection.NEXT
        else -> null
    }

    /** Test seam only. */
    fun hasActiveReaderForTest(): Boolean = handler.get() != null
}
