package com.easemic.app.feature.collect

/**
 * C37-f: process-wide "the account is rate-limited until" marker shared by
 * every pump slot through the one [CloudIngestRunner]. Cloudflare blocks the
 * whole account for its `Retry-After` window (five minutes after the
 * 1,200-request budget), so one slot learning of a 429 must hold the others
 * back too. Windows only extend, never shorten; capped at
 * [CloudSyncIngestAutoRetry.MAX_RATE_LIMIT_WAIT_MS].
 */
class CloudRateLimitPause {
    @Volatile private var untilMs: Long = 0L

    fun pauseFor(retryAfterSeconds: Int, nowMs: Long) {
        val until = nowMs + CloudSyncIngestAutoRetry.rateLimitWaitMs(retryAfterSeconds)
        synchronized(this) { if (until > untilMs) untilMs = until }
    }

    fun remainingMs(nowMs: Long): Long = (untilMs - nowMs).coerceAtLeast(0L)
}
