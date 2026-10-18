package com.easemic.app.feature.download

/**
 * C9 (2026-09-16): the ONE reading of "the source no longer serves this
 * comic". The fact is already durable in `download_jobs`: a zero-page GONE
 * seal (`state = GONE`) or a truncated seal that stopped under the GONE
 * verdict (`state = SEALED, failureClass = GONE`). Every Kotlin surface reads
 * this predicate; the two SQL encodings (the shelf CASE in `LibraryDao` and
 * `DownloadDao.observeDelistedJobs`) are pinned to it by
 * `SourceDelistedSqlGuardTest`. A FAILED row is a failure, never a takedown —
 * the owner named both failure directions and marking a live source dead
 * forever is the worse one.
 */
fun DownloadJob.isDelistedAtSource(): Boolean =
    state == DownloadJobState.GONE ||
        (state == DownloadJobState.SEALED && failureClass == FetchFailureClass.GONE.label())

/**
 * C9: one clause, three shapes, one place. Rules honoured: A30 bans
 * "unavailable"; a statement, not an alarm (no "!", no "Failed"); unreachable
 * is a state, not an error — no Retry. The truncation clause
 * `The source says the rest is gone.` (A36, `ReaderIncompleteCopy`) is about
 * the *rest* and stays where it is.
 */
object SourceDelistedCopy {
    const val CLAUSE = "source no longer serves this comic"

    /** Shelf badge label and the detail collect switch's a11y text. */
    const val COLLECTED_LABEL = "Collected · $CLAUSE"

    /** The inert, dimmed download switch on a zero-page GONE detail. */
    const val NOT_DOWNLOADED_LABEL = "Not downloaded · $CLAUSE"

    /** The statement line under the detail buttons. */
    const val SENTENCE = "The source no longer serves this comic."
}
