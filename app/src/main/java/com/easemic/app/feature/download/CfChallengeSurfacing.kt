package com.easemic.app.feature.download

/**
 * Marker substring distinguishing the nh Cloudflare-challenge ABUSE_STOP
 * (detail "Cloudflare challenge (nh) — WebView solve required", see
 * [FetchFailureClass.ABUSE_STOP]) from the OTHER current ABUSE_STOP source —
 * e-h's "IP address temporarily banned" detail, which never mentions
 * Cloudflare and has no WebView-solve UI. Both share the same
 * `failureClass == "ABUSE_STOP"` label, so surfacing decisions must key off
 * [DownloadJob.failureDetail] too, not the class alone.
 */
private const val CF_CHALLENGE_DETAIL_MARKER = "Cloudflare"

/**
 * The first still-runnable (WAIT/DOWNLOAD) job parked on the nh Cloudflare
 * challenge, or null when none is pending. Safe to call with either
 * [RoomDownloadJobStore.observeQueue]'s rows (already WAIT/DOWNLOAD-filtered,
 * but NOT resume-window-filtered — exactly what a parked ABUSE_STOP job needs
 * in order to still be visible) or an unfiltered snapshot such as
 * [RoomDownloadJobStore.loadAllJobs]; the state check below makes both safe.
 */
fun cfChallengeJob(jobs: List<DownloadJob>): DownloadJob? =
    jobs.firstOrNull { job ->
        job.state.isInConsumptionQueue() &&
            // Only nhentai (nhentai) has a WebView solve — a foreign OPDS
            // server's Cloudflare challenge shares the same detail string but
            // the nhentai.net WebView cannot clear it, so never offer it.
            job.identity.providerNamespace == GalleryIdentityV1.NHENTAI &&
            job.failureClass == "ABUSE_STOP" &&
            job.failureDetail?.contains(CF_CHALLENGE_DETAIL_MARKER, ignoreCase = true) == true
    }

/** True when at least one job is parked awaiting a WebView Cloudflare solve. */
fun cfChallengePending(jobs: List<DownloadJob>): Boolean = cfChallengeJob(jobs) != null

/**
 * Decides whether the pump should (re)post its "solve challenge" notification
 * this tick. Returns the job id to notify for, or null when nothing new needs
 * surfacing. [alreadyNotifiedJobId] is whichever job id the notification is
 * currently showing for (null if none showing) — this dedupes so ticking
 * against the SAME parked job does not re-post every second; a freshly
 * parked job (a different id) still surfaces immediately.
 */
fun shouldNotifyCfChallenge(jobs: List<DownloadJob>, alreadyNotifiedJobId: String?): String? =
    cfChallengeJob(jobs)?.id?.takeIf { it != alreadyNotifiedJobId }
