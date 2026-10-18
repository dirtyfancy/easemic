package com.easemic.app.feature.collect

import com.easemic.app.feature.bridge.CloudSignInCopy
import com.easemic.app.sync.CloudSignIn

/** What Activity's Retry on a FAILED sync row must do — decided by the row's message, pinned on the JVM. */
enum class SyncRetryAction {
    /** The upload itself failed: flip the row back to WAITING and kick the pump (the A11 behaviour). */
    REQUEUE_UPLOAD,
    /** #C22 BLOCKING-1: the preflight never ran to a decision — run the Collect again from the bookmark. */
    RECOLLECT,
    /** #C22 HIGH-1: the local copy is unusable for upload — delete it, then Collect again (re-fetch + upload). */
    REDOWNLOAD_AND_RECOLLECT,
}

/**
 * Honest status when the Collect pre-flight (purge/list against the user's
 * bucket) fails before any upload is attempted. "cloud sync failed" would
 * imply an upload that never happened; the bookmark is the only thing that
 * landed.
 */
object CollectPreflightStatus {
    const val FALLBACK_CODE = "CLOUD_CHECK_FAILED"
    private const val ROW_PREFIX = "Cloud check failed: "

    /** #C22 HIGH-1: a terminal job whose pages cannot be resolved for upload. */
    const val LOCAL_PAGES_INCOMPLETE = "Local copy incomplete · Retry re-downloads it"

    /**
     * Re-review r2 MEDIUM-3: the local copy could not be dropped before a
     * re-download (upload in flight, refused discard, IO failure). Its own
     * terminal reason so Retry does not silently rebuild the identical row.
     */
    const val LOCAL_COPY_NOT_DROPPED = "Local copy could not be removed · tap Retry"

    fun checkFailed(message: String?): String =
        // #D16: the toast for a refused sign-in is a sentence, not the row's key.
        if (message == CloudSignIn.KEY) CloudSignInCopy.COLLECT_TOAST else "Collected (bookmark only). ${rowMessage(message)}"

    /** The Activity row's own line (the detail-screen sentence above wraps it). */
    fun rowMessage(message: String?): String {
        val detail = message?.takeIf { it.isNotBlank() } ?: FALLBACK_CODE
        return ROW_PREFIX + detail
    }

    fun isPreflightFailure(message: String?): Boolean = message?.startsWith(ROW_PREFIX) == true

    fun retryAction(message: String?): SyncRetryAction = when {
        message == LOCAL_PAGES_INCOMPLETE || message == LOCAL_COPY_NOT_DROPPED -> SyncRetryAction.REDOWNLOAD_AND_RECOLLECT
        isPreflightFailure(message) -> SyncRetryAction.RECOLLECT
        else -> SyncRetryAction.REQUEUE_UPLOAD
    }

    /** A routing key (`CLOUD_HTTP_503`, `CLOUD_INDEX_UNREADABLE`) passes through; any other message is replaced by the exception's class name. */
    fun reasonCode(error: Throwable): String =
        if (CloudSignIn.rejected(error)) {
            // #D16: a refused sign-in parks the row with the verdict key, not the raw 401 code.
            CloudSignIn.KEY
        } else {
            error.message?.takeIf { ROUTING_KEY.matches(it) } ?: error::class.simpleName ?: FALLBACK_CODE
        }

    private val ROUTING_KEY = Regex("[A-Z][A-Z0-9_]{2,}")
}
