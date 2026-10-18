package com.easemic.app.feature.collect

/**
 * Terminal outcomes for a cloud Collect ingest job.
 *
 * Contract (regression for vanished Failed rows): ONLY [Synced] removes the
 * queue row. [Failed] must call [CloudSyncQueue.markFailed] and leave the row
 * visible — never a finally-style unconditional [CloudSyncQueue.remove].
 *
 * C35-a: [Cancelled] used to remove the row too, and that was wrong. It does
 * not mean "the user abandoned this comic" — it fires when the ingest was
 * INTERRUPTED: a system stop (Doze, lost constraint, OEM app management), or
 * a user kick that replaced the worker to preempt a backoff. Deleting the row
 * there lost a half-uploaded comic with no trace. It now parks the row
 * resumable via [CloudSyncQueue.markInterrupted]. The user's own Cancel never
 * comes through here at all — it calls [CloudSyncQueue.remove] directly, and
 * `markInterrupted` no-ops on a missing id so a removed row stays removed.
 */
sealed class CloudSyncIngestFinish {
    data object Synced : CloudSyncIngestFinish()

    /** The ingest was interrupted, not abandoned — park it, never delete it. */
    data object Cancelled : CloudSyncIngestFinish()
    data class Failed(val message: String) : CloudSyncIngestFinish()
}

fun CloudSyncQueue.applyIngestFinish(
    id: String,
    finish: CloudSyncIngestFinish,
    chapterIdentity: String? = null,
) {
    when (finish) {
        CloudSyncIngestFinish.Synced -> remove(id, chapterIdentity)
        CloudSyncIngestFinish.Cancelled -> markInterrupted(id, chapterIdentity)
        is CloudSyncIngestFinish.Failed -> markFailed(id, finish.message, chapterIdentity = chapterIdentity)
    }
}

/**
 * Owner bug A3/C5 fix (T3c): whether a FAILED row is eligible to be
 * automatically re-driven right now — bounded by [CloudSyncIngestAutoRetry.MAX_ATTEMPTS]
 * and gated by a [CloudSyncIngestAutoRetry.backoffDelayMs] window since its
 * last failure, reusing the same budget/backoff shape as the WITHIN-a-single-
 * `run()`-call auto-resume in [runCloudIngestWithAutoResume] — this is the
 * ACROSS-calls counterpart that lets the pump ([nextRunnableCloudSyncRow])
 * pick a FAILED row back up on a later pass instead of leaving it to sit
 * forever showing "retrying automatically" with nothing actually retrying it.
 *
 * [failedAttempts]/[failedAtEpochMs] default to values that preserve the
 * historical single-arg `shouldAutoResumeFailedCloudSync(stage)` behavior
 * (any FAILED row eligible) for every existing caller/test — including
 * legacy persisted rows from before [CloudSyncQueueItem.failedAttempts]
 * existed, which load with `failedAttempts = 0` and are treated the same
 * way: eligible for (one) auto-resume rather than stranded.
 */
fun shouldAutoResumeFailedCloudSync(
    stage: CloudSyncStage,
    failedAttempts: Int = 0,
    failedAtEpochMs: Long? = null,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (stage != CloudSyncStage.FAILED) return false
    if (failedAttempts <= 0) return true
    if (failedAttempts >= CloudSyncIngestAutoRetry.MAX_ATTEMPTS) return false
    val failedAt = failedAtEpochMs ?: return true
    val elapsed = nowMs - failedAt
    return elapsed >= CloudSyncIngestAutoRetry.backoffDelayMs(failedAttempts)
}

/**
 * Catch-up must not re-launch while a row is already WAITING/FETCHING/UPLOADING
 * or while an ingest job is registered active.
 *
 * [CloudSyncStage.FAILED] with an idle job is eligible for auto-resume relaunch
 * (durable page checkpoint + bounded retry). Passing null [queueStage] keeps the
 * historical "any queued row skips" behavior for callers that do not know stage.
 */
fun shouldSkipCloudSyncCatchUpLaunch(
    queueContainsAcquisition: Boolean,
    ingestJobActive: Boolean,
    queueStage: CloudSyncStage? = null,
): Boolean {
    if (ingestJobActive) return true
    if (!queueContainsAcquisition) return false
    return queueStage != CloudSyncStage.FAILED
}

/** Stage-first overload: null stage means the acquisition is not queued. */
fun shouldSkipCloudSyncCatchUpLaunch(
    queueStage: CloudSyncStage?,
    ingestJobActive: Boolean,
): Boolean = shouldSkipCloudSyncCatchUpLaunch(
    queueContainsAcquisition = queueStage != null,
    ingestJobActive = ingestJobActive,
    queueStage = queueStage,
)
