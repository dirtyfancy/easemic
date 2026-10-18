package com.easemic.app.feature.library

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.RemoteProgressPolicy
import com.easemic.app.feature.browse.RemoteReadingProgress
import com.easemic.app.feature.browse.seriesIdentityFor
import com.easemic.app.feature.browse.CloudCollectionFeedMapper
import com.easemic.app.feature.collect.CollectedBookmark
import com.easemic.app.feature.collect.isSerializedBookmark
import com.easemic.app.feature.download.serializedSeriesLedgerKey
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.model.LibraryItem

/** The shelf key shared by serialized marks and their collected cards. */
internal fun collectedBookmarkDedupKey(bookmark: CollectedBookmark): String? {
    if (!isSerializedBookmark(bookmark)) return galleryDedupKey(bookmark.acquisitionUrl)
    val series = seriesIdentityFor(
        BrowseFeedItem(
            id = bookmark.id,
            title = bookmark.title,
            providerId = bookmark.providerId,
            acquisitionUrl = bookmark.acquisitionUrl,
            coverUrl = bookmark.coverUrl,
            feedUrl = bookmark.feedUrl,
        ),
    )
    return serializedSeriesLedgerKey(series)
}

/**
 * Pure merge of the local, collected-bookmark, and cloud-mirror feeds into
 * one deduped, badged shelf list. Side-effect-free: callers own I/O.
 */
object LibraryShelfComposer {
    fun compose(
        localItems: List<LibraryItem>,
        localAcquisitionByItemId: Map<Long, String?>,
        bookmarks: List<CollectedBookmark>,
        cloudCards: List<BrowseFeedItem>,
        cloudActiveKeys: Set<String>,
        localSerializedSeriesLedgerKeyByItemId: Map<Long, String> = emptyMap(),
        /**
         * A42 remainder: stored remote-read positions (the whole
         * RemoteReadingProgressStore map, policy-keyed). A remote tile whose
         * comic has a record carries it so the shelf can render the same
         * quiet progress bar local rows have; default empty keeps callers
         * without progress wiring unchanged.
         */
        remoteProgress: Map<String, RemoteReadingProgress> = emptyMap(),
        /**
         * C9: acquisition dedup keys ([galleryDedupKey]) of every job the
         * source delisted (`RoomDownloadJobStore.observeDelistedJobs`). A
         * Remote entry whose key is in the set carries `delistedAtSource`;
         * Local entries read the same fact from the shelf SQL projection
         * (`LibraryItem.delistedAtSource`) and ignore this set.
         */
        delistedKeys: Set<String> = emptySet(),
        /** Quiet serialized-series marks keyed by [serializedSeriesLedgerKey]. */
        newChapterMarks: Map<String, Int> = emptyMap(),
        /**
         * Same-device managed source covers, keyed by serialized identity. Null
         * means the map has not resolved yet (it is read off the main thread);
         * empty means resolved with no managed file, so cards keep their own
         * cover (hydrated cloud URI, or the bookmark URL for a collected card).
         */
        serializedSourceCoverUris: Map<SeriesIdentityV2, String>? = emptyMap(),
    ): List<ShelfEntry> {
        // C4: every cloud card contributes BOTH of its identities, so a comic
        // pulled back from the cloud library (downloaded as `cloud:<id>` while
        // its card also names the upstream source) is one badged tile and not
        // two. The caller's own key set is unioned in, never replaced.
        val cloudKeys = cloudCards.flatMap { cloudCardKeys(it) }.toSet()
        val normalizedCloudActiveKeys =
            cloudActiveKeys.mapNotNull { galleryDedupKey(it) }.toSet() + cloudKeys
        val bookmarkKeys = bookmarks.mapNotNull(::bookmarkDedupKey).toSet()

        fun progressFor(item: BrowseFeedItem): ShelfRemoteProgress? {
            val stored = RemoteProgressPolicy.keyFor(item)?.let { remoteProgress[it] } ?: return null
            return ShelfRemoteProgress(ordinal = stored.ordinal, pageCount = stored.pageCount)
        }

        val localEntries = localItems.map { item ->
            val key = localSerializedSeriesLedgerKeyByItemId[item.id.value]
                ?: galleryDedupKey(localAcquisitionByItemId[item.id.value])
            ShelfEntry.Local(
                item = item,
                badges = ShelfBadges(
                    onDevice = true,
                    collected = key != null && key in bookmarkKeys,
                    inCloud = key != null && key in normalizedCloudActiveKeys,
                ),
                // Marks are only written for serialized follows, so a gallery or import key never matches.
                newChapterCount = key?.let { newChapterMarks[it] } ?: 0,
            )
        }
        val localKeys = localItems
            .mapNotNull { item ->
                localSerializedSeriesLedgerKeyByItemId[item.id.value]
                    ?: galleryDedupKey(localAcquisitionByItemId[item.id.value])
            }
            .toSet()

        val cloudEntries = cloudCards.mapNotNull { card ->
            if (cloudCardKeys(card).any { it in localKeys }) return@mapNotNull null
            val key = cloudCardKey(card)
            ShelfEntry.Remote(
                feedItem = card.withSerializedSourceCover(serializedSourceCoverUris),
                origin = RemoteOrigin.CLOUD,
                badges = ShelfBadges(
                    onDevice = false,
                    collected = key != null && key in bookmarkKeys,
                    inCloud = true,
                    delistedAtSource = key != null && key in delistedKeys,
                ),
                progress = progressFor(card),
                // Marks are only written for serialized follows, so a gallery card's key never matches.
                newChapterCount = key?.let { newChapterMarks[it] } ?: 0,
            )
        }

        val collectedEntries = bookmarks.mapNotNull { bookmark ->
            val key = bookmarkDedupKey(bookmark)
            if (key != null && (key in localKeys || key in cloudKeys)) return@mapNotNull null
            val feedItem = BrowseFeedItem(
                id = bookmark.id,
                title = bookmark.title,
                providerId = bookmark.providerId,
                acquisitionUrl = bookmark.acquisitionUrl,
                coverUrl = bookmark.coverUrl,
                feedUrl = bookmark.feedUrl,
            )
            ShelfEntry.Remote(
                feedItem = if (isSerializedBookmark(bookmark)) {
                    feedItem.withSerializedSourceCover(
                        serializedSourceCoverUris,
                        SeriesIdentityV2(bookmark.providerId, bookmark.id),
                    )
                } else {
                    feedItem
                },
                origin = RemoteOrigin.COLLECTED,
                badges = ShelfBadges(
                    onDevice = false,
                    collected = true,
                    inCloud = false,
                    delistedAtSource = key != null && key in delistedKeys,
                ),
                progress = progressFor(feedItem),
                newChapterCount = if (isSerializedBookmark(bookmark)) {
                    key?.let { newChapterMarks[it] } ?: 0
                } else {
                    0
                },
            )
        }

        return localEntries + cloudEntries + collectedEntries
    }

    /** The card's canonical key: the upstream source when known, else its own `cloud:<id>`. */
    private fun cloudCardKey(card: BrowseFeedItem): String? =
        if (CloudCollectionFeedMapper.isSeries(card)) {
            serializedSeriesLedgerKey(card.providerId, card.id)
        } else {
            galleryDedupKey(card.upstreamAcquisitionUrl) ?: galleryDedupKey(card.acquisitionUrl)
        }

    /** Serialized downloads are owned by the series identity, not its source locator. */
    private fun bookmarkDedupKey(bookmark: CollectedBookmark): String? =
        collectedBookmarkDedupKey(bookmark)

    private fun BrowseFeedItem.withSerializedSourceCover(
        covers: Map<SeriesIdentityV2, String>?,
        explicitIdentity: SeriesIdentityV2? = null,
    ): BrowseFeedItem {
        val identity = explicitIdentity ?: if (CloudCollectionFeedMapper.isSeries(this)) {
            SeriesIdentityV2(providerId, id)
        } else {
            return this
        }
        if (covers == null) {
            // #E75: the managed-cover map is resolved off the main thread, so the
            // first frames see no entry even when the file already exists. Handing
            // Coil the remote source URL then hits the source for a cover this
            // device already holds; hold it back until the map has resolved once.
            val remote = coverUrl?.let { it.startsWith("http://") || it.startsWith("https://") } == true
            return if (remote) copy(coverUrl = null) else this
        }
        return covers[identity]?.let { copy(coverUrl = it) } ?: this
    }

    /**
     * Both identities a cloud card carries (spec 2.3, mirrors
     * `feature/detail/LocalCloudPresence`). A `cloud:<id>` download matches
     * the card's own acquisition; a source download matches its upstream.
     * Choosing one with `?:` drops whichever side the local row was keyed by.
     */
    private fun cloudCardKeys(card: BrowseFeedItem): Set<String> = buildSet {
        cloudCardKey(card)?.let(::add)
        if (!CloudCollectionFeedMapper.isSeries(card)) {
            galleryDedupKey(card.upstreamAcquisitionUrl)?.let(::add)
            galleryDedupKey(card.acquisitionUrl)?.let(::add)
        }
    }
}
