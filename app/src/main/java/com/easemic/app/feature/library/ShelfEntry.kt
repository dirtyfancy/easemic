package com.easemic.app.feature.library

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.BrowseFeedRepository
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.browse.RemoteReadingProgress
import com.easemic.app.model.LibraryItem

enum class RemoteOrigin { COLLECTED, CLOUD }
data class ShelfBadges(
    val onDevice: Boolean,
    val collected: Boolean,
    val inCloud: Boolean,
    /** C9: the source delisted this comic (Remote entries: from the delisted-job key set). */
    val delistedAtSource: Boolean = false,
)

/**
 * A42 remainder: the persisted remote-read position for this tile's comic, when
 * the store has one ([RemoteReadingProgress]; device-local, spec D1). The tile
 * bar renders the SAME quiet fraction treatment local rows use.
 */
data class ShelfRemoteProgress(
    val ordinal: Int,
    val pageCount: Int,
)

sealed interface ShelfEntry {
    val badges: ShelfBadges
    data class Local(
        val item: LibraryItem,
        override val badges: ShelfBadges,
        /** Quiet mark for chapters a follow refresh found; the card a series with saved chapters shows as. */
        val newChapterCount: Int = 0,
    ) : ShelfEntry
    data class Remote(
        val feedItem: BrowseFeedItem,
        val origin: RemoteOrigin,
        override val badges: ShelfBadges,
        /** A42 remainder: stored remote-read progress, when present. */
        val progress: ShelfRemoteProgress? = null,
        /** Quiet mark for chapters found by a serialized-series follow refresh. */
        val newChapterCount: Int = 0,
    ) : ShelfEntry
}

/** Title shown on the tile, regardless of which shelf it came from. */
fun ShelfEntry.displayTitle(): String = when (this) {
    is ShelfEntry.Local -> item.title
    is ShelfEntry.Remote -> feedItem.title
}

/**
 * Maps a merged shelf entry onto the same [ShelfFacts] the filter sheet's
 * counts and [LibraryStateFilter] agree on. A local item is "collected" if
 * it's bookmarked or already mirrored in the cloud; its downloaded/imported
 * split still comes from [isCatalogDownloadUri] on its own pages. A remote
 * entry (collected-only or cloud-only) never lives on this device, so it is
 * always collected and never downloaded/imported.
 */
fun ShelfEntry.shelfFacts(): ShelfFacts = when (this) {
    is ShelfEntry.Local -> {
        val downloaded = item.pages.any { isCatalogDownloadUri(it.documentUri) }
        ShelfFacts(
            collected = badges.collected || badges.inCloud,
            downloaded = downloaded,
            imported = !downloaded,
        )
    }
    is ShelfEntry.Remote -> ShelfFacts(collected = true, downloaded = false, imported = false)
}

fun ShelfEntry.matchesShelfFilter(filter: LibraryStateFilter): Boolean = filter.matches(shelfFacts())

fun normalizeAcquisitionKey(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

/**
 * Canonical dedup key for a gallery acquisition URL. E-Hentai URLs carry a
 * per-view token (`ehentai://<gid>/<token>`) that a downloaded item's own
 * identity key never has (`ehentai://<gid>`), so a plain [normalizeAcquisitionKey]
 * would never dedup a downloaded gallery against its own bookmark/cloud card.
 * Stripping the token via the gallery id keeps both sides comparable.
 */
fun galleryDedupKey(raw: String?): String? {
    val url = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    BrowseFeedRepository.parseEhentaiAcquisition(url)?.let { (gid, _) -> return "ehentai://$gid" }
    BrowseFeedRepository.parseNhentaiAcquisition(url)?.let { gid -> return "nhentai://$gid" }
    // C4: a comic downloaded FROM the cloud library owns a `cloud_collection/<id>`
    // identity, whose token-free key is `cloud_collection://<id>`; its cloud
    // card names the same comic as `cloud:<id>`. One key for both, or the shelf
    // shows the download and the card as two tiles.
    cloudCollectionIdOrNull(url)?.let { id -> return normalizeAcquisitionKey("cloud:$id") }
    return normalizeAcquisitionKey(url)
}

private const val CLOUD_COLLECTION_KEY_PREFIX = "${GalleryIdentityV1.CLOUD_COLLECTION}://"

private fun cloudCollectionIdOrNull(url: String): String? =
    url.takeIf { it.startsWith(CLOUD_COLLECTION_KEY_PREFIX, ignoreCase = true) }
        ?.substring(CLOUD_COLLECTION_KEY_PREFIX.length)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
