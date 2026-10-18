package com.easemic.app.feature.collect

import com.easemic.app.sync.CloudCollectAction
import com.easemic.app.sync.CloudCollectLifecycle
import com.easemic.app.sync.CloudCollectionCard
import com.easemic.app.sync.CloudCollectionStore

/**
 * C35-d: what a Collect tap means, decided from two bucket reads and nothing
 * else.
 *
 * The decision used to be inlined in the Compose collect handler, which ran
 * `CloudCollectionStore.purgeExpiredTrash(now)` first — a trash listing, a
 * `discardComic` per expired card (2N+2 deletes each), then another trash
 * listing to rewrite the index — followed by the active listing and a third
 * trash listing, all under `INDEX_MUTATION_LOCK`, all before the tap could
 * move a byte. None of that expiry work is needed to answer the only question
 * the tap asks. Expiry now runs as a background sweep
 * (`AppGraph.purgeExpiredCloudTrash`).
 *
 * The [Cards] seam is the whole reason this type is safe: it exposes the two
 * listings and nothing else, so the preflight is structurally incapable of
 * mutating anyone's trash, and the decision is unit-testable without a bucket.
 */
class CloudCollectPreflightDecision(private val cards: Cards) {
    /** The read-only slice of [CloudCollectionStore] a preflight may touch. */
    interface Cards {
        fun listAllIndexCards(): List<CloudCollectionCard>
        fun listAllTrashCards(): List<CloudCollectionCard>
    }

    sealed interface Decision {
        /** Already cloud-side (or already in the durable ledger): upload nothing. */
        data object SkipIngest : Decision

        /**
         * In the trash. The caller un-trashes [cloudComicId] and updates the
         * live mirror — those side effects stay at the call site, so this type
         * stays a pure function of the two listings.
         */
        data class Restore(val cloudComicId: String) : Decision

        /** Not cloud-side at all: hand it to the ingest pipeline. */
        data object Ingest : Decision
    }

    constructor(store: CloudCollectionStore) : this(
        object : Cards {
            override fun listAllIndexCards(): List<CloudCollectionCard> = store.listAllIndexCards()
            override fun listAllTrashCards(): List<CloudCollectionCard> = store.listAllTrashCards()
        },
    )

    /**
     * Exactly one active-index listing and at most one trash listing per call.
     *
     * [knownSynced] is the durable per-comic sync ledger's answer for the
     * CURRENT bucket (S3 Task 1, owner bug C3/A3): a comic recorded as synced
     * must never be re-uploaded even if a transiently incomplete
     * [Cards.listAllIndexCards] read would miss it — so it short-circuits
     * ahead of both reads and costs no network at all.
     *
     * Listing order is deliberate and matches the pre-extraction behaviour:
     * active first, then trash, with [CloudCollectLifecycle.collectAction]'s
     * precedence (a card in BOTH indexes is a restore, not a skip) unchanged.
     */
    fun decide(matches: (CloudCollectionCard) -> Boolean, knownSynced: Boolean): Decision {
        if (knownSynced) return Decision.SkipIngest
        val hasActive = cards.listAllIndexCards().any(matches)
        val trashCard = cards.listAllTrashCards().firstOrNull(matches)
        return when (CloudCollectLifecycle.collectAction(hasActive, trashCard != null)) {
            // RESTORE is returned only when hasTrash, so trashCard is non-null;
            // the elvis keeps that an expression rather than a `!!`.
            CloudCollectAction.RESTORE -> trashCard?.let { Decision.Restore(it.cloudComicId) } ?: Decision.Ingest
            CloudCollectAction.SKIP_INGEST -> Decision.SkipIngest
            CloudCollectAction.INGEST -> Decision.Ingest
        }
    }
}
