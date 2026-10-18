package com.easemic.app.sync

/**
 * HTTP 429 from the bucket transport. [retryAfterSeconds] is the server's
 * `Retry-After` when present, else [DEFAULT_RETRY_AFTER_SECONDS].
 * Message is the same `CLOUD_HTTP_429` shape every classifier already parses.
 */
class CloudRateLimitedException(
    val retryAfterSeconds: Int = DEFAULT_RETRY_AFTER_SECONDS,
) : CloudHttpException("CLOUD_HTTP_429") {
    companion object {
        /**
         * C35-c: an un-headered 429 pauses ONE MINUTE. Cloudflare's own block
         * after the 1,200-request window is five minutes, and this used to be
         * 300 to match it — but the response that actually carries that window
         * also carries `Retry-After`, so 300 was only ever guessed at the
         * responses that say nothing. Guessing the worst case cost the user a
         * five-minute frozen row (the whole of C35's symptom) every time a 429
         * arrived bare. A real `Retry-After` is still honoured verbatim, up to
         * [com.easemic.app.feature.collect.CloudSyncIngestAutoRetry.MAX_RATE_LIMIT_WAIT_MS],
         * so a server that really means five minutes still gets five minutes;
         * a silent one costs a minute and is retried.
         */
        const val DEFAULT_RETRY_AFTER_SECONDS: Int = 60

        fun fromHeaders(headers: Map<String, String>): CloudRateLimitedException {
            val raw = headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }?.value
            val seconds = raw?.trim()?.toIntOrNull()?.takeIf { it >= 0 } ?: DEFAULT_RETRY_AFTER_SECONDS
            return CloudRateLimitedException(seconds)
        }
    }
}
