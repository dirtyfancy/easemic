package com.easemic.app.work

import com.easemic.app.model.ScanRunState

/**
 * Which durable run states startup recovery must leave alone.
 *
 * A30: `WAITING_FOR_REVIEW` used to mean "waiting for the user to pick items".
 * The review gate is gone, so the state now only ever means "enumerated, commit
 * not finished" -- including rows written by a pre-A30 build -- and recovery
 * re-enqueues it so the engine's auto-commit finishes the job. Only an
 * explicitly paused run rests.
 */
object RunRecoveryPolicy {
    private val TERMINAL = setOf(
        ScanRunState.COMPLETE,
        ScanRunState.PARTIAL,
        ScanRunState.FAILED,
    )

    private val INTENTIONAL_IDLE = setOf(
        ScanRunState.PAUSED,
        ScanRunState.PAUSE_REQUESTED,
    )

    fun isTerminal(state: ScanRunState): Boolean = state in TERMINAL

    fun isIntentionallyIdle(state: ScanRunState): Boolean = state in INTENTIONAL_IDLE
}
