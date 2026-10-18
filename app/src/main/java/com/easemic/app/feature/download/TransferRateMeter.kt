package com.easemic.app.feature.download

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory byte window for the active download/sync job. Not persisted.
 */
class TransferRateMeter(
    private val windowMs: Long = 3_000L,
) {
    private data class Sample(val atMs: Long, val bytes: Long)

    private val windows = ConcurrentHashMap<String, ArrayDeque<Sample>>()
    private val _ticks = MutableStateFlow(0)
    val ticks: StateFlow<Int> = _ticks.asStateFlow()

    fun record(id: String, byteCount: Long, nowMs: Long) {
        if (id.isBlank() || byteCount <= 0) return
        val queue = windows.getOrPut(id) { ArrayDeque() }
        synchronized(queue) {
            queue.addLast(Sample(nowMs, byteCount.toLong()))
            trim(queue, nowMs)
        }
        _ticks.value += 1
    }

    fun record(id: String, byteCount: Int, nowMs: Long) = record(id, byteCount.toLong(), nowMs)

    fun bytesPerSecond(id: String, nowMs: Long): Long? {
        val queue = windows[id] ?: return null
        synchronized(queue) {
            trim(queue, nowMs)
            if (queue.isEmpty()) return null
            val total = queue.sumOf { it.bytes }
            val elapsed = (nowMs - queue.first().atMs).coerceAtLeast(1_000L)
            return (total * 1_000L) / elapsed
        }
    }

    fun clear(id: String) {
        windows.remove(id)
        _ticks.value += 1
    }

    private fun trim(queue: ArrayDeque<Sample>, nowMs: Long) {
        while (queue.isNotEmpty() && nowMs - queue.first().atMs > windowMs) {
            queue.removeFirst()
        }
    }
}
