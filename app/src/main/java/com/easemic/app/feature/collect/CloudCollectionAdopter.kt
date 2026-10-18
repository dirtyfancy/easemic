package com.easemic.app.feature.collect

import com.easemic.app.sync.CloudCollectionCard
import com.easemic.app.sync.DiagLog
import kotlinx.coroutines.CancellationException

/**
 * #C56: the seam `AppGraph.syncLedgerWithMirror` calls after its ledger
 * backfill pass — extracted out of AppGraph so this pass is testable without
 * a full AppGraph (Context/Room). Maps each card via [CloudCardAdoption] and
 * hands the results to [CollectBookmarkStore.adoptAll] in one batch.
 */
class CloudCollectionAdopter(
    private val bookmarks: CollectBookmarkStore,
    private val fileExists: (String) -> Boolean,
    private val coverUri: (String) -> String,
) {
    suspend fun adopt(cards: List<CloudCollectionCard>, detailProviderIdOf: suspend (String) -> String?): Int {
        // #C56 send-back 2 S3: the OPDS branch's detail read is a real Room
        // query and can throw. One bad card must not abort the whole batch
        // (and, upstream, the caller's releaseLocalPagesForCloudOnlyJobs()
        // that follows this call in AppGraph.syncLedgerWithMirror) — skip
        // just that card and keep going. CancellationException is never a
        // "bad card": it means this coroutine is being cancelled and must
        // propagate, not be swallowed as a skip.
        var skipped = 0
        val candidates = cards.mapNotNull { card ->
            try {
                CloudCardAdoption.bookmarkFor(
                    card = card,
                    // #C56 send-back R5: lazy — bookmarkFor invokes this only
                    // from its fallback branch (an acquisition it cannot
                    // classify e-hentai/nhentai), so the overwhelming majority
                    // of cards never trigger this Room read.
                    detailProviderId = { detailProviderIdOf(card.cloudComicId) },
                    fileExists = fileExists,
                    coverUri = coverUri,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                skipped += 1
                null
            }
        }
        if (skipped > 0) {
            DiagLog.w("CloudSync", "collection adoption: skipped=$skipped cards (detail read failed)")
        }
        return bookmarks.adoptAll(candidates)
    }
}
