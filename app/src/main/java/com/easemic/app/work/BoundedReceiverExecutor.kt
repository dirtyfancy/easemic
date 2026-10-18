package com.easemic.app.work

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class BoundedReceiverExecutor(
    private val applicationScope: CoroutineScope,
    private val timeoutMillis: Long,
) {
    fun execute(
        block: suspend () -> Unit,
        finish: () -> Unit,
    ) {
        val finished = AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) finish()
        }

        try {
            applicationScope.launch {
                try {
                    withTimeout(timeoutMillis) {
                        block()
                    }
                } catch (_: Exception) {
                    // The durable Room state remains authoritative. The
                    // broadcast lifetime must still end on failure/timeout.
                }
            }.invokeOnCompletion {
                finishOnce()
            }
        } catch (_: Throwable) {
            finishOnce()
        }
    }
}
