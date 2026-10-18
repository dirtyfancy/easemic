package com.easemic.app.feature.download

import com.easemic.app.feature.bridge.CloudSignInCopy

data class DownloadQueueRow(
    val jobId: String,
    val title: String,
    val coverUrl: String?,
    val state: DownloadJobState,
    val readyPages: Int,
    val expectedPages: Int?,
    val bytesPerSecond: Long?,
    /** Epoch ms before which a DOWNLOAD job is parked (throttle/quota window); 0 = runnable now. */
    val resumeNotBeforeMs: Long = 0L,
    /** Source provider id (== GalleryIdentityV1.providerNamespace) for Activity→Detail nav. */
    val providerId: String = "",
    /** Serialized-series identity; null for legacy gallery rows. */
    val seriesCanonicalId: String? = null,
    /** Serialized provider chapter identity; null for legacy gallery rows. */
    val chapterId: String? = null,
    /** Acquisition URL for Activity→Detail nav; null when no locator is known yet. */
    val acquisitionUrl: String? = null,
    /** Last recorded [FetchFailureClass.label] (S3 Task 8, owner bug #6); null = never recorded. */
    val failureClass: String? = null,
    /** Human detail accompanying [failureClass]; see [downloadFailureReason] for the UI-safe phrase. */
    val failureDetail: String? = null,
    /** A20: auto-revive inputs mirrored from [DownloadJob] so [downloadRowWillAutoRevive] can be honest. */
    val intentCloud: Boolean = false,
    val autoRetryAttempts: Int = 0,
    val lastAutoRetryAtMs: Long = 0L,
)

/**
 * A job that still holds the active-download slot but is waiting out a
 * throttle/quota/jitter window: state stays DOWNLOAD while [resumeNotBeforeMs]
 * is in the future. The pump correctly starts the next FIFO gallery meanwhile,
 * so the UI must show this row as paused-and-retrying, not "downloading".
 */
fun downloadRowParked(row: DownloadQueueRow, nowMs: Long): Boolean =
    row.state == DownloadJobState.DOWNLOAD && row.resumeNotBeforeMs > nowMs

/**
 * A20: will the bounded auto-revive sweep ([downloadJobsToRevive]) pick this
 * FAILED row up on its own — now, or once its resume window elapses? Shares
 * the attempt-budget arithmetic with the sweep via [autoRetryBudgetRemains]
 * (P3, polish backlog: it used to be written out twice, so a change to one
 * could silently make the UI promise an auto-resume the sweep would never
 * perform), EXCEPT that a future [DownloadQueueRow.resumeNotBeforeMs] still
 * counts as "will resume automatically" — the sweep merely waits for it.
 * False means the only way forward is the row's Retry button (local-only
 * intent, or a capped cloud job like the Kyouei baseline at
 * `autoRetryAttempts=4`), and the Activity line must say so instead of
 * promising an auto-resume.
 */
fun downloadRowWillAutoRevive(row: DownloadQueueRow, nowMs: Long): Boolean {
    if (row.state != DownloadJobState.FAILED || !row.intentCloud) return false
    return autoRetryBudgetRemains(
        autoRetryAttempts = row.autoRetryAttempts,
        lastAutoRetryAtMs = row.lastAutoRetryAtMs,
        nowMs = nowMs,
    )
}

fun downloadQueueRow(
    job: DownloadJob,
    locator: DownloadLocator?,
    readyPages: Int,
    bytesPerSecond: Long?,
): DownloadQueueRow {
    val title = locator?.title?.trim()?.takeIf { it.isNotEmpty() }
        ?: job.identity.canonicalItemId
    val speed = bytesPerSecond.takeIf { job.state == DownloadJobState.DOWNLOAD }
    return DownloadQueueRow(
        jobId = job.id,
        title = title,
        coverUrl = locator?.coverUrl?.trim()?.takeIf { it.isNotEmpty() },
        state = job.state,
        readyPages = readyPages.coerceAtLeast(0),
        expectedPages = job.expectedPageCount,
        bytesPerSecond = speed,
        resumeNotBeforeMs = job.resumeNotBeforeMs,
        providerId = job.identity.providerNamespace,
        seriesCanonicalId = job.identity.canonicalItemId.takeIf { job.isSerializedChapter },
        chapterId = job.chapterIdentity.takeIf { job.isSerializedChapter },
        acquisitionUrl = locator?.acquisitionUrl,
        failureClass = job.failureClass,
        failureDetail = job.failureDetail,
        intentCloud = job.intentCloud,
        autoRetryAttempts = job.autoRetryAttempts,
        lastAutoRetryAtMs = job.lastAutoRetryAtMs,
    )
}

fun downloadQueueSecondaryLine(row: DownloadQueueRow): String {
    val stateLabel = when (row.state) {
        DownloadJobState.WAIT -> "Waiting"
        DownloadJobState.DOWNLOAD -> "Downloading"
        else -> row.state.name
    }
    val pages = row.expectedPages?.let { expected ->
        "${row.readyPages} / $expected pages"
    } ?: "Pages unknown"
    val speed = row.bytesPerSecond?.let { " · ${formatTransferRate(it)}" }.orEmpty()
    return "$stateLabel · $pages$speed"
}

fun formatTransferRate(bytesPerSecond: Long): String {
    val abs = bytesPerSecond.coerceAtLeast(0L)
    return when {
        abs < 1_024L -> "$abs B/s"
        abs < 1_024L * 1_024L -> "${abs / 1_024L} KB/s"
        else -> {
            val tenths = (abs * 10) / (1_024L * 1_024L)
            "${tenths / 10}.${tenths % 10} MB/s"
        }
    }
}

/** Same substring [CfChallengeSurfacing.kt] keys off to tell the nh CF challenge ABUSE_STOP apart from an e-h ban. */
private const val CLOUDFLARE_DETAIL_MARKER = "Cloudflare"

/**
 * A short, UI-safe phrase explaining why [row] is FAILED (S3 Task 8, owner
 * bug #6: previously a permanently-capped job gave no clue why it kept
 * dying). Deliberately does NOT echo [DownloadQueueRow.failureDetail]
 * verbatim for every class — MANIFEST_ERROR's detail is a raw exception
 * message/HTML snippet, unfit for display — so this maps the closed set of
 * [FetchFailureClass.label] values to a canonical phrase instead. An unknown
 * (future) class or a null [DownloadQueueRow.failureClass] (never recorded
 * yet) returns null, so the caller falls back to the bare
 * "retrying · resumes automatically" line rather than showing a raw label.
 */
fun downloadFailureReason(row: DownloadQueueRow): String? {
    // #D16: the same predicate that picks the job for revival, so what reads "refused" is what comes back.
    if (signInRefusedCloudFailure(row.providerId, row.failureClass, row.failureDetail)) {
        return CloudSignInCopy.DOWNLOAD_REASON
    }
    return when (row.failureClass) {
        // A41: a cloud->device download on a device with nothing bound throws
        // CLOUD_DOWNLOAD_NOT_CONNECTED out of the adapter's manifest fetch. That is
        // not a transport failure, so the pump hard-fails it as MANIFEST_ERROR —
        // and reading that back as "couldn't read gallery page" sends the owner
        // hunting for a broken gallery when the real fix is to connect a bucket.
        "MANIFEST_ERROR" -> when {
            row.failureDetail?.contains(CLOUD_DOWNLOAD_NOT_CONNECTED, ignoreCase = true) == true ->
                "cloud storage is not connected"
            // A41/W8: for the cloud-collection namespace this same non-transport
            // arm carries the bucket's OWN read failures (CloudHttpException on
            // the R2 GET of the pages manifest, UnreadableCloudLibraryException
            // on an unparseable one). There is no gallery and no page on that
            // path — the honest reason names the cloud library read. The
            // gallery phrase stays exact for every upstream source (an
            // EhentaiManifestEmptyException IS a gallery page that could not be
            // read).
            row.providerId == GalleryIdentityV1.CLOUD_COLLECTION -> "cloud library could not be read"
            else -> "couldn't read gallery page"
        }
        "JITTER" -> "temporary network issue"
        "PACED" -> "rate limited by source"
        "QUOTA" -> "image quota exhausted"
        "ABUSE_STOP" -> if (row.failureDetail?.contains(CLOUDFLARE_DETAIL_MARKER, ignoreCase = true) == true) {
            "Cloudflare challenge"
        } else {
            "source temporarily blocked"
        }
        "GONE" -> "source says this is gone"
        // SEND_BACK item 5 (owner bug backlog): these four classes were added
        // by S3 Task 9 (PAGE_COUNT_MISMATCH[_PENDING] from the upstream-count
        // reconciliation, JITTER_EXHAUSTED from the retry-state escalation, and
        // UNSPECIFIED from the markFailed backstop) but never got a UI phrase,
        // so a job failing for any of them silently fell through to `else ->
        // null` and rendered as a bare "retrying" row with no explanation.
        "PAGE_COUNT_MISMATCH" -> "page count doesn't match source"
        "PAGE_COUNT_MISMATCH_PENDING" -> "confirming page count with source"
        "JITTER_EXHAUSTED" -> "repeated network failures"
        "UNSPECIFIED" -> "unknown failure"
        // A20 (owner bug backlog, Kyouei/A18 investigation): three more hard-fail
        // classes that could already reach a FAILED row but had no UI phrase.
        "NO_LOCATOR" -> "missing download link"
        "UNSUPPORTED_IDENTITY" -> "source not supported"
        "EMPTY_MANIFEST" -> "gallery has no pages"
        // #C73: a pass that threw (an app bug path) — parked with backoff, FAILED after repeated throws.
        DownloadWorkerLoop.PASS_THREW -> "unexpected error while downloading"
        else -> null
    }
}
