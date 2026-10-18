package com.easemic.app.feature.collect

import com.easemic.app.sync.CloudCollectionIngest
import com.easemic.app.sync.CloudCollectionIngestResult
import com.easemic.app.sync.R2RestObjectStore
import com.easemic.app.sync.WebDavObjectStore

/**
 * Bounded backoff for mid-job cloud ingest failures. Not an infinite tight loop:
 * at most [MAX_ATTEMPTS] ingest attempts, with growing delays between them.
 *
 * Permanent / non-retryable failures (empty plan, unsupported source, cancel,
 * and bad-request-style page HTTP codes other than 429) leave Failed immediately
 * and do not burn the auto-resume budget.
 *
 * C37 audit (B1): a failed OAuth token refresh
 * ([R2RestObjectStore.OAUTH_REFRESH_FAILED_MESSAGE]) is also non-retryable, but
 * unlike the plan-shaped failures above it IS reported once — a single ingest
 * attempt, a single transient-failure report (so the row goes FAILED and its
 * budget ticks), then no in-loop retry. A refresh that already failed will not
 * succeed on a ~15 s tight retry against the same store; the runner decides
 * afterward whether the grant is dead (terminal reconnect) or the exchange was
 * a transient outage (a normal retryable FAILED the pump re-drives).
 *
 * C37-f: a [CloudCollectionIngestResult.RateLimited] attempt is not a failure
 * at all. Cloudflare's 429 blocks the whole ACCOUNT for its `Retry-After`
 * window, so spending one of the five attempts on it would exhaust the budget
 * in seconds against a wall that only clears minutes later. Such an attempt
 * instead waits [rateLimitWaitMs] and resumes from the durable checkpoint,
 * leaving [MAX_ATTEMPTS] entirely for real failures.
 */
object CloudSyncIngestAutoRetry {
    const val MAX_ATTEMPTS = 5
    const val BASE_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 30_000L

    /** Cloudflare's account-wide block lasts five minutes; never park a slot longer than that. */
    const val MAX_RATE_LIMIT_WAIT_MS = 300_000L

    private const val PAGE_PREFIX = "CLOUD_INGEST_PAGE_"

    /**
     * Permanent failures where reporting a transient auto-resume failure would
     * itself mislead: an empty/unsupported plan or a cancel was never a real
     * upload attempt, and a bad-request page code (4xx other than 429) fails
     * identically on replay. These leave Failed on the FIRST ingest without
     * recording an attempt. Distinct from [OAUTH_REFRESH_FAILED_MESSAGE], which
     * is non-retryable yet reported once (see [isNonRetryable] / the object KDoc).
     */
    fun leavesFailedWithoutReport(message: String): Boolean {
        if (!CloudSyncFailurePolicy.canRetry(message)) return true
        when (message) {
            CloudCollectionIngest.CANCELLED,
            "CLOUD_INGEST_EMPTY",
            "CLOUD_INGEST_UNSUPPORTED_SOURCE",
            // #C81: a full drive fails identically on replay; the runner parks it terminal.
            WebDavObjectStore.QUOTA_FULL,
            // #D16: a refused sign-in fails identically on replay too; the runner parks it terminal.
            com.easemic.app.sync.CloudSignIn.KEY,
            -> return true
        }
        if (!message.startsWith(PAGE_PREFIX)) return false
        val code = message.removePrefix(PAGE_PREFIX).toIntOrNull() ?: return false
        // Permanent client / bad-request style; 429 is rate-limit and remains retryable.
        return code in 400..499 && code != 429
    }

    fun isNonRetryable(message: String): Boolean {
        if (leavesFailedWithoutReport(message)) return true
        // A refresh that already failed will not succeed on a tight in-loop
        // retry — non-retryable, but reported once (see the object KDoc).
        return message == R2RestObjectStore.OAUTH_REFRESH_FAILED_MESSAGE
    }

    /**
     * @param failureAttempt 1-based count of failures observed so far (after the
     * latest Failed result). Retry while this is still below [MAX_ATTEMPTS].
     */
    fun shouldRetry(failureAttempt: Int, message: String): Boolean {
        if (isNonRetryable(message)) return false
        if (failureAttempt <= 0) return false
        return failureAttempt < MAX_ATTEMPTS
    }

    fun backoffDelayMs(failureAttempt: Int): Long {
        val shift = (failureAttempt - 1).coerceAtLeast(0).coerceAtMost(8)
        val raw = BASE_DELAY_MS shl shift
        return raw.coerceAtMost(MAX_DELAY_MS)
    }

    /**
     * C37-f: how long to park on a 429. The server's own `Retry-After` wins,
     * floored at one second (a `Retry-After: 0` must not become a tight loop)
     * and capped at [MAX_RATE_LIMIT_WAIT_MS] so a wild header can never strand
     * a pump slot for hours.
     */
    fun rateLimitWaitMs(retryAfterSeconds: Int): Long =
        (retryAfterSeconds.coerceAtLeast(1) * 1_000L).coerceAtMost(MAX_RATE_LIMIT_WAIT_MS)
}

/**
 * Keep the ingest job alive across transient failures: briefly report Failed,
 * wait backoff, then resume (caller's ingest must honor durable page checkpoint).
 * Plan-shaped permanent failures ([CloudSyncIngestAutoRetry.leavesFailedWithoutReport])
 * return Failed immediately without consuming an attempt or reporting one. A
 * failed OAuth refresh is reported exactly once (one attempt) and then returns
 * Failed without in-loop retry (C37 audit B1). A rate-limited attempt (C37-f)
 * consumes no attempt either: it reports through [onRateLimited], waits the
 * server's window, and resumes.
 */
suspend fun runCloudIngestWithAutoResume(
    ingestOnce: suspend () -> CloudCollectionIngestResult,
    onTransientFailure: (failureAttempt: Int, message: String) -> Unit,
    onResume: (failureAttempt: Int) -> Unit,
    onRateLimited: (retryAfterSeconds: Int) -> Unit = {},
    delayMs: suspend (Long) -> Unit,
): CloudCollectionIngestResult {
    var failureAttempt = 0
    while (true) {
        when (val result = ingestOnce()) {
            is CloudCollectionIngestResult.Synced -> return result
            is CloudCollectionIngestResult.RateLimited -> {
                // Not this comic's fault and not an attempt: wait the server's
                // window out, then resume from the checkpoint.
                onRateLimited(result.retryAfterSeconds)
                delayMs(CloudSyncIngestAutoRetry.rateLimitWaitMs(result.retryAfterSeconds))
                onResume(failureAttempt)
            }
            is CloudCollectionIngestResult.Failed -> {
                if (CloudSyncIngestAutoRetry.leavesFailedWithoutReport(result.message)) {
                    return result
                }
                failureAttempt += 1
                onTransientFailure(failureAttempt, result.message)
                if (!CloudSyncIngestAutoRetry.shouldRetry(failureAttempt, result.message)) {
                    return result
                }
                delayMs(CloudSyncIngestAutoRetry.backoffDelayMs(failureAttempt))
                onResume(failureAttempt)
            }
        }
    }
}
