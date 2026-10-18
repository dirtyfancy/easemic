package com.easemic.app.feature.browse

import com.easemic.app.sync.CloudHttpException
import com.easemic.app.sync.CloudRateLimitedException
import com.easemic.app.sync.CloudSignIn
import com.easemic.app.sync.LibraryLockedException
import com.easemic.app.sync.NewerCloudFormatException
import com.easemic.app.sync.StaleCloudBindingException
import com.easemic.app.sync.UnreadableCloudLibraryException

/**
 * #C68: whether — and how long to wait before — the cloud reader should
 * retry a failed page load on its own, before showing the failure column and
 * Retry button. Pure: the prefetcher owns the timing (delay, scheduling,
 * cancellation on close), this only classifies a throwable.
 *
 * Mirrors the download pump's split (`DownloadWorkerLoop.IN_PLACE_PAGE_ATTEMPTS`):
 * a source-terminal verdict (locked library, newer-format library, stale binding, unreadable
 * bucket, or a 4xx other than 408/429 per RFC 9110 §15.5) gives up at once;
 * everything else (transport IOException, 5xx, 408, 429) is transient and
 * gets a short ladder, because a person is waiting on the screen.
 */
fun interface CloudReaderRetryPolicy {
    /** Wait before automatic attempt number [failedAttempts] + 1, or null to show the failure. */
    fun delayBeforeRetryMs(failedAttempts: Int, error: Throwable): Long?

    companion object {
        /** 3 automatic retries (4 attempts total): 1s, 3s, 9s, then give up. */
        private val LADDER_MS = longArrayOf(1_000L, 3_000L, 9_000L)
        private const val MAX_RATE_LIMIT_WAIT_MS = 30_000L

        val DEFAULT: CloudReaderRetryPolicy = CloudReaderRetryPolicy { failedAttempts, error ->
            if (isTerminal(error)) return@CloudReaderRetryPolicy null
            val ladderDelay = LADDER_MS.getOrNull(failedAttempts - 1) ?: return@CloudReaderRetryPolicy null
            val rateLimited = generateSequence(error, Throwable::cause)
                .filterIsInstance<CloudRateLimitedException>()
                .firstOrNull()
            if (rateLimited != null) {
                maxOf(ladderDelay, rateLimited.retryAfterSeconds * 1_000L).coerceAtMost(MAX_RATE_LIMIT_WAIT_MS)
            } else {
                ladderDelay
            }
        }

        /** Never retries: the failure is reported at once, every time. */
        val NEVER: CloudReaderRetryPolicy = CloudReaderRetryPolicy { _, _ -> null }

        private fun isTerminal(error: Throwable): Boolean {
            if (CloudSignIn.rejected(error)) return true
            val chain = generateSequence(error, Throwable::cause)
            if (chain.any {
                    it is LibraryLockedException ||
                        it is StaleCloudBindingException ||
                        it is NewerCloudFormatException ||
                        it is UnreadableCloudLibraryException
                }) {
                return true
            }
            val httpCode = chain
                .filterIsInstance<CloudHttpException>()
                .mapNotNull(::cloudHttpStatusCodeOf)
                .firstOrNull()
                ?: return false
            return httpCode in 400..499 && httpCode != 408 && httpCode != 429
        }
    }
}

private val CLOUD_HTTP_STATUS_PATTERN = Regex("CLOUD_HTTP_(\\d+)")

private fun cloudHttpStatusCodeOf(exception: CloudHttpException): Int? =
    CLOUD_HTTP_STATUS_PATTERN.find(exception.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
