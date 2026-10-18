package com.easemic.app.feature.download

/**
 * A36 — how much of a download the Library commit is being handed.
 *
 * [Complete] is every FINISH: each expected ordinal is READY, so the row's real
 * pages ARE the whole comic and its location is `PRESENT`.
 *
 * [Truncated] is the GONE wind-down of a job the user wanted on device (spec
 * §1.2, owner's phone: Kyouei 22 of 43). The pages that were fetched are real
 * and readable, but the comic is short and the source says the rest is gone —
 * so the location is `INCOMPLETE` (still on the shelf, badged; see
 * `ShelfVisibility` / A30 §2.1) and [expectedPageCount] carries the count
 * pinned at enqueue rather than the number of page rows. Keeping the pinned
 * count is what lets the Detail truncation note say "22 of 43" instead of
 * "22 of 22", which would be the app quietly agreeing with a loss.
 */
sealed interface DownloadCommitCompleteness {
    data object Complete : DownloadCommitCompleteness

    data class Truncated(val expectedPageCount: Int) : DownloadCommitCompleteness

    /** `INCOMPLETE` ⇒ the shelf badges it; `PRESENT` ⇒ an ordinary download. */
    val locationState: String
        get() = when (this) {
            Complete -> "PRESENT"
            is Truncated -> "INCOMPLETE"
        }

    /** What the row should claim it expects; null ⇒ "however many pages I have". */
    val pinnedPageCount: Int?
        get() = when (this) {
            Complete -> null
            is Truncated -> expectedPageCount
        }
}
