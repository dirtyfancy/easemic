package com.easemic.app.feature.download

/**
 * EhViewer-informed download job states.
 *
 * UPDATE / GOTO_NEW are omitted in v1. Consumption-queue membership is exactly
 * [WAIT] + [DOWNLOAD]. [FINISH] and [FAILED] leave the queue.
 */
enum class DownloadJobState {
    NONE,
    WAIT,
    DOWNLOAD,
    FINISH,
    FAILED,

    /**
     * Fetch complete (all pages, or truncation wind-down declared complete).
     * Ready for CLOUD-intent upload handoff. Terminal; not fetch-pump runnable.
     */
    SEALED,

    /**
     * Source confirmed gone with zero pages fetched. Terminal; not fetch-pump
     * runnable. #C9 (owner ruling 2026-09-16, supersedes loyal-pipelines §1.3's
     * auto-uncollect): the collected tile STAYS on the shelf with the slashed
     * cloud until the user uncollects; Activity keeps a Dismiss-only trace
     * (`dismissedAtMillis`, never a state change); a fresh Download/Collect on
     * the identity is a no-op until uncollect. A truncated seal (SEALED with
     * `failureClass = GONE`) is the sibling shape — see `isDelistedAtSource`.
     */
    GONE,
}

/** Consumption-queue membership: exactly [WAIT] + [DOWNLOAD] (spec §4.3). */
fun DownloadJobState.isInConsumptionQueue(): Boolean =
    this == DownloadJobState.WAIT || this == DownloadJobState.DOWNLOAD
