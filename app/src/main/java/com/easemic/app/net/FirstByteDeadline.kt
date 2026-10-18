package com.easemic.app.net

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Socket read timeout for a watchdog transfer. OkHttp closes the socket when this fires, so it
 * has to cover the floor window; the first-byte limit is a separate deadline
 * ([FirstByteDeadline]).
 */
internal fun stallReadTimeoutMs(firstByteMs: Long, floorWindowMs: Long): Long =
    maxOf(firstByteMs, floorWindowMs).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong())

/**
 * Cancels the call if no body byte arrives within [waitMs], and names that failure with
 * [stallPrefix] (`EHENTAI_STALL`, `NHENTAI_STALL`, `OPDS_STALL`). The socket read timeout is at
 * least as long as the floor window, so it cannot be the thing that reports a missing first byte.
 */
internal class FirstByteDeadline(
    private val stallPrefix: String,
    private val waitMs: Long,
    private val sawBodyByte: AtomicBoolean,
    private val closeOnce: () -> Unit,
) {
    private val waitNs = waitMs.coerceAtLeast(1L) * 1_000_000L
    private val startedAt = System.nanoTime()
    private val expired = AtomicBoolean(false)
    private val worker = thread(isDaemon = true, name = "source-first-byte") {
        try {
            Thread.sleep(waitMs.coerceAtLeast(1L))
        } catch (_: InterruptedException) {
            return@thread
        }
        if (!sawBodyByte.get()) {
            expired.set(true)
            closeOnce()
        }
    }

    fun stallIfDue(error: IOException): IOException? {
        if (sawBodyByte.get()) return null
        if (!expired.get() && System.nanoTime() - startedAt < waitNs) return null
        return IOException("$stallPrefix: no first byte within ${waitMs}ms", error)
    }

    fun close() {
        worker.interrupt()
        worker.join(1_000)
    }
}
