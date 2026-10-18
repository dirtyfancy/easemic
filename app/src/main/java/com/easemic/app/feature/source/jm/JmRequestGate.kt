package com.easemic.app.feature.source.jm

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** One shared authority for API, template and (in S7-2/3) image wire lifetimes. */
internal class JmRequestGate(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
    private val spacingMillis: Long = 750,
) {
    private val permits = Semaphore(2)
    private val starts = Mutex()
    private var lastStart: Long? = null
    init { require(spacingMillis >= 0) }
    suspend fun <T> wire(block: suspend () -> T): T = permits.withPermit {
        starts.withLock {
            val previous = lastStart
            if (previous != null) {
                val remaining = spacingMillis - (nowMillis() - previous)
                if (remaining > 0) waitMillis(remaining)
            }
            lastStart = nowMillis()
        }
        // The caller closes the body inside block; permits are never released at headers.
        block()
    }
}
