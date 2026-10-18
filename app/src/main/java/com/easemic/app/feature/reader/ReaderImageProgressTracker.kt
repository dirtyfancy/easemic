package com.easemic.app.feature.reader

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ReaderImageProgressSnapshot(
    val receivedBytes: Long = 0L,
    val contentLength: Long = -1L,
)

class ReaderImageProgressTracker {
    private class Slot {
        val flow = MutableStateFlow(ReaderImageProgressSnapshot())
        var refs: Int = 0
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    fun watch(url: String): StateFlow<ReaderImageProgressSnapshot> {
        val slot = slots.compute(url) { _, existing ->
            val current = existing ?: Slot()
            current.refs += 1
            current
        }!!
        return slot.flow
    }

    fun unwatch(url: String) {
        slots.compute(url) { _, existing ->
            if (existing == null) {
                return@compute null
            }
            existing.refs -= 1
            if (existing.refs <= 0) null else existing
        }
    }

    fun isWatched(url: String): Boolean = (slots[url]?.refs ?: 0) > 0

    fun report(url: String, receivedBytes: Long, contentLength: Long) {
        slots[url]?.flow?.value = ReaderImageProgressSnapshot(
            receivedBytes = receivedBytes,
            contentLength = contentLength,
        )
    }

    companion object {
        val shared = ReaderImageProgressTracker()

        fun canonicalKey(url: String): String {
            val stripped = url.substringBefore('#')
            return stripped.toHttpUrlOrNull()?.toString() ?: stripped
        }
    }
}
