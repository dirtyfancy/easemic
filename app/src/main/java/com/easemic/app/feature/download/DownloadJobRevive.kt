package com.easemic.app.feature.download

import com.easemic.app.sync.CloudSignIn

/**
 * #D16: FAILED cloud-library downloads the drive refused for the sign-in. Pull-backs are not cloud-intent jobs, so
 * [downloadJobsToRevive] never picks them up; a cloud check the drive accepts (after a new password, a reconnect or a
 * passing hiccup) puts these back.
 */
fun signInRefusedCloudDownloads(jobs: List<DownloadJob>): List<DownloadJob> = jobs.filter { job ->
    job.state == DownloadJobState.FAILED &&
        signInRefusedCloudFailure(job.identity.providerNamespace, job.failureClass, job.failureDetail)
}

/**
 * #D16: a cloud-library download the drive refused for the sign-in, whether the manifest read failed
 * (MANIFEST_ERROR) or its pages were retried out (JITTER_EXHAUSTED). The Downloads row's reason reads the same test.
 */
internal fun signInRefusedCloudFailure(providerNamespace: String?, failureClass: String?, failureDetail: String?): Boolean =
    providerNamespace == GalleryIdentityV1.CLOUD_COLLECTION &&
        (failureClass == "MANIFEST_ERROR" || failureClass == "JITTER_EXHAUSTED") &&
        CloudSignIn.rejected(failureDetail)

/**
 * Bounded auto-revive sweep (S3 Task 4, owner bug A6): a cloud-intent
 * download job that dies at FAILED never re-enters the consumption queue on
 * its own — only a manual UI retry ([RoomDownloadJobStore.retry]) moves
 * FAILED → WAIT — yet S1 already shows the collected item's cloud icon as
 * "pending" the moment it's collected. Without this sweep such a job stays
 * stranded forever.
 *
 * Returns the subset of [jobs] to revive: FAILED, cloud-intent
 * ([DownloadJob.intentCloud]), past its QUOTA/ban idle window
 * ([DownloadJob.resumeNotBeforeMs] `<= nowMs`), and under the lifetime
 * [maxAttempts] cap. GONE (source confirmed gone) is never a candidate — it
 * isn't FAILED, so the state filter alone excludes it. Callers apply
 * [RoomDownloadJobStore.autoRetry] to each candidate, which increments the
 * persisted counter and stamps [DownloadJob.lastAutoRetryAtMs] for diagnostics.
 */
fun downloadJobsToRevive(
    jobs: List<DownloadJob>,
    nowMs: Long,
    maxAttempts: Int = AUTO_RETRY_MAX_ATTEMPTS,
): List<DownloadJob> = jobs.filter { job ->
    job.state == DownloadJobState.FAILED &&
        job.intentCloud &&
        autoRetryBudgetRemains(
            autoRetryAttempts = job.autoRetryAttempts,
            lastAutoRetryAtMs = job.lastAutoRetryAtMs,
            nowMs = nowMs,
            maxAttempts = maxAttempts,
        ) &&
        job.resumeNotBeforeMs <= nowMs
}

/**
 * The attempt-budget half of the auto-revive rule, extracted so the sweep
 * ([downloadJobsToRevive]) and the Activity-row promise
 * ([downloadRowWillAutoRevive]) cannot drift apart (P3, polish backlog: the
 * cooldown/attempt arithmetic was written out twice, and a change to one
 * would have let the UI promise an auto-resume the sweep never performs).
 *
 * True while the persisted lifetime attempt count is still under
 * [maxAttempts]. The state/intent/resume-window parts of the rule differ
 * between the two call sites on purpose (the sweep must wait out
 * [DownloadJob.resumeNotBeforeMs]; the UI counts a future window as "will
 * resume automatically") and stay at their call sites.
 */
fun autoRetryBudgetRemains(
    autoRetryAttempts: Int,
    lastAutoRetryAtMs: Long,
    nowMs: Long,
    maxAttempts: Int = AUTO_RETRY_MAX_ATTEMPTS,
): Boolean {
    return autoRetryAttempts < maxAttempts
}

/**
 * Legacy compatibility constant. Automatic retry no longer resets at this
 * interval; a human Retry is the explicit reset.
 */
const val AUTO_RETRY_COOLDOWN_MS = 6L * 60 * 60 * 1000L

/**
 * Default lifetime cap of unattended [downloadJobsToRevive] attempts. Shared
 * with the Activity-row predicate
 * `downloadRowWillAutoRevive` so the UI never promises an auto-resume the
 * sweep will not perform.
 */
const val AUTO_RETRY_MAX_ATTEMPTS = 3
