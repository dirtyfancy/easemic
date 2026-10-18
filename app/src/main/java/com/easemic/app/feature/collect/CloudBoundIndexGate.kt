package com.easemic.app.feature.collect

import com.easemic.app.feature.library.galleryDedupKey
import com.easemic.app.sync.CloudCollectionCard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * #C66: the seam `AppGraph` wires as [CloudIngestRunner]'s
 * `presentInCurrentIndex` hook — extracted out of AppGraph so both the
 * hydration gate and the per-pass memo are testable without a full AppGraph
 * (Context/Room), same reasoning as [CloudCollectionAdopter]. Reuses the
 * EXACT match rule [backfillFromMirror] uses (`galleryDedupKey` compared on
 * both sides) so this gate can never disagree with what hydration's own
 * backfill would find later — no new match rule invented for this check.
 *
 * Two responsibilities, both required by the design
 * (`docs/superpowers/specs/2026-09-25-c66-no-reupload-before-ledger-backfill-design.md`):
 * - Skip the read entirely once [hydrationComplete] says the bound bucket's
 *   ledger is already authoritative (`AppGraph.syncLedgerWithMirror`'s own
 *   KDoc) — a hydration-complete bucket must not pay a live index GET here.
 * - Otherwise read (and cache) the index at most once per (fingerprint,
 *   generation) pair, so N rows revived by the same pump pass
 *   (`AppGraph.onBucketBound` reviving a whole queue at once is the
 *   motivating case) cost exactly one `listAllIndexCards()` call. A rebind
 *   or a hydration re-walk changes the fingerprint or the generation, which
 *   invalidates the cache on the very next call — never serves a stale
 *   bucket's index under a new identity.
 */
class CloudBoundIndexGate(
    private val currentBucketFingerprint: () -> String?,
    private val generationFor: suspend (fp: String) -> Int,
    private val hydrationComplete: suspend (fp: String) -> Boolean,
    private val listCards: suspend () -> List<CloudCollectionCard>,
) {
    // The upload pump runs several slots at once: one lock so concurrent rows
    // of the same pass share ONE listing instead of each reading the index.
    private val lock = Mutex()
    private var cachedFor: Pair<String, Int>? = null
    private var cachedIndex: Map<String, String> = emptyMap()

    /**
     * Returns the matching card's `cloudComicId` for [acquisition] in the
     * currently bound bucket's index, or null when there is no bound
     * bucket, hydration is already complete, or nothing matches. A blank/
     * unparseable [acquisition] (nothing to key it by) always misses
     * without reading the index.
     */
    suspend fun matchingCloudComicId(acquisition: String): String? {
        val fp = currentBucketFingerprint() ?: return null
        if (hydrationComplete(fp)) return null
        val acquisitionKey = galleryDedupKey(acquisition) ?: return null
        val cacheKey = fp to generationFor(fp)
        return lock.withLock {
            if (cachedFor != cacheKey) {
                cachedIndex = listCards().mapNotNull { card ->
                    val cardKey = galleryDedupKey(card.acquisitionRef) ?: return@mapNotNull null
                    cardKey to card.cloudComicId
                }.toMap()
                cachedFor = cacheKey
            }
            cachedIndex[acquisitionKey]
        }
    }
}
