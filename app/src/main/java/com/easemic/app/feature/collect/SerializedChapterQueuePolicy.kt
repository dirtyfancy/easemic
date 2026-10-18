package com.easemic.app.feature.collect

import com.easemic.app.feature.download.SeriesIdentityV2

/**
 * Decides whether a followed serialized chapter still needs source work.
 *
 * The three checks deliberately stay separate: a local job, a durable synced
 * ledger row, and a hydrated cloud mirror chapter each prove a different
 * state. A chapter is queued only when none of those facts exists. This keeps
 * a cloud-only device from fetching a chapter the bucket already owns while
 * allowing a sibling chapter to proceed independently.
 */
class SerializedChapterQueuePolicy(
    private val localJobExists: suspend (SeriesIdentityV2, String) -> Boolean,
    private val ledgerHasChapter: suspend (SeriesIdentityV2, String) -> Boolean,
    private val mirrorHasChapter: suspend (SeriesIdentityV2, String) -> Boolean,
    /** #E110: a local job in NONE state — its pages are deleted and a refresh must not resurrect it. */
    private val localJobDeleted: suspend (SeriesIdentityV2, String) -> Boolean = { _, _ -> false },
) {
    suspend fun shouldQueue(series: SeriesIdentityV2, chapterIdentity: String): Boolean {
        if (localJobExists(series, chapterIdentity)) return false
        if (ledgerHasChapter(series, chapterIdentity)) return false
        if (mirrorHasChapter(series, chapterIdentity)) return false
        return true
    }

    /**
     * #E110: whether a chapter still needs CLOUD work — the question a Collect
     * asks. Unlike [shouldQueue] (source work: a chapter that already has a
     * local job needs no new fetch), an existing local job is exactly the
     * adoption case: `collectFrom`'s enqueue OR-merges the cloud intent into
     * it, and the follow's catch-up sweep uploads the finished pages. A
     * chapter the durable ledger or the hydrated cloud mirror already knows
     * is done everywhere, and a NONE job (pages deleted) must not be
     * resurrected by a refresh — `followRefreshDoesNotReviveDeletedNone`
     * pins that rule.
     */
    suspend fun shouldAdoptForCloud(series: SeriesIdentityV2, chapterIdentity: String): Boolean {
        if (localJobDeleted(series, chapterIdentity)) return false
        if (ledgerHasChapter(series, chapterIdentity)) return false
        if (mirrorHasChapter(series, chapterIdentity)) return false
        return true
    }
}
