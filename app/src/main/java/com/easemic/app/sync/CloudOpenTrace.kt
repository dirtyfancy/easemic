package com.easemic.app.sync

import androidx.annotation.VisibleForTesting

/**
 * C34-d: permanent phase log for a cloud-comic open, under one tag so the
 * whole first screen can be read with `adb logcat -s CloudOpen`.
 *
 * Every line is `phase=<p> ms=<n>[ k=v…]`. The vocabulary is deliberately
 * narrow — a phase name, a duration, an outcome enum, counts, and a cloud
 * comic id. The cloud comic id IS logged (it is the variable part of the
 * `manifest-get` key, and the only way to tell two opens apart); bucket names,
 * tokens, URIs, file paths, titles and full object keys are not. This log
 * ships in release builds.
 */
object CloudOpenTrace {
    const val TAG = "CloudOpen"

    /** Test seam: when set, lines go here instead of [DiagLog]. */
    @Volatile
    @VisibleForTesting
    var sink: ((String) -> Unit)? = null

    fun log(phase: String, ms: Long, extras: String = "") {
        val line = "phase=$phase ms=$ms" + if (extras.isBlank()) "" else " $extras"
        val target = sink
        if (target != null) target(line) else DiagLog.i(TAG, line)
    }

    /**
     * Times [block] and logs exactly one line for it, including when it
     * throws — a phase that failed still cost the time it cost. [extras] is
     * evaluated after [block] so it can report what the block resolved.
     */
    inline fun <T> timed(
        phase: String,
        extras: () -> String = { "" },
        block: () -> T,
    ): T {
        val startedNs = System.nanoTime()
        try {
            return block()
        } finally {
            log(phase, (System.nanoTime() - startedNs) / 1_000_000L, extras())
        }
    }
}
