package com.easemic.app.feature.download

/**
 * Pure Detail local-Download button matrix (spec §4.2). English labels only.
 */
enum class DetailDownloadPrimaryAction {
    ENQUEUE,
    NONE,
    CONFIRM_DELETE,
    RETRY,
    UNAVAILABLE,
}

data class DetailDownloadPresentation(
    val primaryLabel: String,
    val primaryEnabled: Boolean,
    val primaryAction: DetailDownloadPrimaryAction,
    val showCancel: Boolean,
    val statusHint: String? = null,
)

object DetailDownloadControl {
    /**
     * @param ownsLocalPages A36 review M2: whether this job's pages are on the
     *   phone ([downloadJobOwnsLocalPages]). Only that may offer the delete —
     *   a glyph reading "Not downloaded" must never open "Delete download?".
     *   Defaults to what the state alone proves (FINISH), so pre-existing
     *   callers are unchanged.
     */
    fun present(
        available: Boolean,
        state: DownloadJobState?,
        unavailableReason: String? = null,
        ownsLocalPages: Boolean = downloadJobOwnsLocalPages(state),
    ): DetailDownloadPresentation {
        if (!available) {
            return DetailDownloadPresentation(
                primaryLabel = "Download unavailable",
                primaryEnabled = false,
                primaryAction = DetailDownloadPrimaryAction.UNAVAILABLE,
                showCancel = false,
                statusHint = unavailableReason,
            )
        }
        return when (state ?: DownloadJobState.NONE) {
            DownloadJobState.NONE -> DetailDownloadPresentation(
                primaryLabel = "Download",
                primaryEnabled = true,
                primaryAction = DetailDownloadPrimaryAction.ENQUEUE,
                showCancel = false,
            )
            DownloadJobState.WAIT, DownloadJobState.DOWNLOAD -> DetailDownloadPresentation(
                primaryLabel = "Downloading…",
                primaryEnabled = false,
                primaryAction = DetailDownloadPrimaryAction.NONE,
                showCancel = true,
            )
            DownloadJobState.FINISH -> DetailDownloadPresentation(
                primaryLabel = "Downloaded",
                primaryEnabled = true,
                primaryAction = DetailDownloadPrimaryAction.CONFIRM_DELETE,
                showCancel = false,
            )
            DownloadJobState.FAILED -> DetailDownloadPresentation(
                primaryLabel = "Retry",
                primaryEnabled = true,
                primaryAction = DetailDownloadPrimaryAction.RETRY,
                showCancel = false,
            )
            // A36 review M2: a SEALED job is two different things. With pages
            // on the phone (a truncated intentLocal wind-down that committed
            // its Library row) it is a download the user can delete. Without
            // them it is a cloud-only fetch that was never put on this phone,
            // so offering "Delete download?" would delete something the screen
            // never said was here — it reads as not-downloaded, and the honest
            // verb for that is Download.
            DownloadJobState.SEALED -> if (ownsLocalPages) {
                DetailDownloadPresentation(
                    primaryLabel = "Downloaded",
                    primaryEnabled = true,
                    primaryAction = DetailDownloadPrimaryAction.CONFIRM_DELETE,
                    showCancel = false,
                )
            } else {
                DetailDownloadPresentation(
                    primaryLabel = "Download",
                    primaryEnabled = true,
                    primaryAction = DetailDownloadPrimaryAction.ENQUEUE,
                    showCancel = false,
                )
            }
            // C9: the source delisted it. An inert key plus one statement — no
            // "unavailable" (A30), no Retry (unreachable is a state, not an error).
            DownloadJobState.GONE -> DetailDownloadPresentation(
                primaryLabel = "Download",
                primaryEnabled = false,
                primaryAction = DetailDownloadPrimaryAction.UNAVAILABLE,
                showCancel = false,
                statusHint = SourceDelistedCopy.SENTENCE,
            )
        }
    }
}
