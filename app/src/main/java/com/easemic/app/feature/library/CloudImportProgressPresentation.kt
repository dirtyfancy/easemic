package com.easemic.app.feature.library

import com.easemic.app.i18n.AppStrings

/**
 * Pure presentation math for the Cloud tab's "Importing · N/M" progress strip
 * (loyal-pipelines Slice B §2, live updates). No Compose/Android dependency —
 * [LibraryScreen] only renders [CloudImportProgress.label] and toggles
 * visibility from [CloudImportProgress.visible]; this object decides what (if
 * anything) to show.
 */
object CloudImportProgressPresentation {
    data class CloudImportProgress(
        val visible: Boolean,
        val label: String,
        val indeterminate: Boolean,
        /** 0f..1f fraction for a determinate progress bar; null when [indeterminate]. */
        val fraction: Float?,
    )

    private val HIDDEN = CloudImportProgress(visible = false, label = "", indeterminate = false, fraction = null)

    /**
     * #13 (owner 2026-09-09): a cloud card counts toward the strip's numerator
     * only once its cover thumbnail has landed. Tier-1 hydration inserts a card
     * row (pure text metadata) long before its cover downloads, which made the
     * fraction snap to full while thumbnails were still filling in. [coverPresent]
     * is one flag per current mirror card (coverUrl != null); the count of true
     * flags is the honest "imported so far". The hide trigger (tier-2 completion)
     * is unchanged, so the strip still vanishes exactly when it did.
     */
    fun coveredCardCount(coverPresent: List<Boolean>): Int = coverPresent.count { it }

    /**
     * [hasState] is false before hydration has ever produced a checkpoint row
     * for this bucket — nothing to report yet, so the strip stays hidden just
     * like the completed case. [visibleCards] is the Cloud tab's current item
     * count (mirror list size so far); [totalCards] is the hydration
     * checkpoint's declared manifest count — null or non-positive means
     * unknown, which renders as an indeterminate strip rather than a bogus
     * "N/0".
     */
    fun present(
        hasState: Boolean,
        hydrationComplete: Boolean,
        visibleCards: Int,
        totalCards: Int?,
    ): CloudImportProgress {
        if (!hasState || hydrationComplete) return HIDDEN
        return if (totalCards != null && totalCards > 0) {
            CloudImportProgress(
                visible = true,
                label = AppStrings.importingProgress(visibleCards, totalCards),
                indeterminate = false,
                fraction = (visibleCards.toFloat() / totalCards.toFloat()).coerceIn(0f, 1f),
            )
        } else {
            CloudImportProgress(visible = true, label = AppStrings.importing, indeterminate = true, fraction = null)
        }
    }
}
