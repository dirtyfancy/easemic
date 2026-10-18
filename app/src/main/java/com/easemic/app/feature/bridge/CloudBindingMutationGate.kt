package com.easemic.app.feature.bridge

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes cloud binding replacement with destructive local-copy release.
 *
 * A release must validate the bucket fingerprint while holding this gate and
 * keep the gate until its local delete finishes. Bind/disconnect paths hold the
 * same gate while changing durable binding state, so a release can never use a
 * ledger proof from the binding that just got replaced.
 */
class CloudBindingMutationGate {
    private val lock = Mutex()

    /** Suspend-friendly mutation boundary for bind/save/OAuth code. */
    suspend fun <T> withMutation(block: suspend () -> T): T = lock.withLock { block() }

    /** Synchronous adapter for the legacy disconnect callback. */
    fun <T> withMutationBlocking(block: () -> T): T = runBlocking {
        lock.withLock { block() }
    }

    suspend fun <T> withCurrentBinding(
        currentFingerprint: () -> String?,
        expectedFingerprint: String? = null,
        block: suspend (String) -> T,
    ): T? {
        return lock.withLock {
            val current = currentFingerprint()
            if (current == null || (expectedFingerprint != null && current != expectedFingerprint)) {
                return null
            }
            return block(current)
        }
    }
}
