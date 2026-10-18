package com.easemic.app.feature.collect

import com.easemic.app.feature.browse.BrowseFeedRepository
import com.easemic.app.sync.CloudCardLayout
import com.easemic.app.sync.CloudCollectionCard

/**
 * #C56: the device's collection is the truth; a descended comic joins it
 * under its ORIGINAL acquisition, never `cloud:<id>` — that id is
 * bucket-local and useless after a rebind. See
 * `docs/superpowers/specs/2026-09-21-c56-collection-adoption-design.md` §4.1.
 */
object CloudCardAdoption {
    /**
     * #C56 send-back R5: [detailProviderId] is a SUPPLIER, invoked only from
     * the fallback branch below (an acquisition that is neither e-hentai nor
     * nhentai). The eager version read a Room detail row for EVERY card;
     * lazy means an e-hentai/nhentai card — the overwhelming majority —
     * never triggers that read at all.
     */
    suspend fun bookmarkFor(
        card: CloudCollectionCard,
        detailProviderId: suspend () -> String?,
        fileExists: (String) -> Boolean,
        coverUri: (String) -> String,
    ): CollectedBookmark? {
        val acquisition = card.acquisitionRef?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (card.layoutKind == CloudCardLayout.SERIES) {
            // A series acquisition URL is mutable display/refetch data. The
            // provider namespace and canonical series id come from series.json
            // and are absent until summary hydration completes.
            val providerId = card.providerId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val providerSeriesId = card.providerSeriesId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return CollectedBookmark(
                id = providerSeriesId,
                title = card.title,
                acquisitionUrl = acquisition,
                providerId = providerId,
                coverUrl = card.coverPath?.takeIf { fileExists(it) }?.let(coverUri),
                feedUrl = null,
                serialized = true,
            )
        }
        val providerId = when {
            BrowseFeedRepository.parseEhentaiAcquisition(acquisition) != null -> "ehentai"
            BrowseFeedRepository.parseNhentaiAcquisition(acquisition) != null -> "nhentai"
            // An empty string is "unset", same as null — a blank detail
            // provider id must not silently become the bookmark's providerId.
            else -> detailProviderId()?.takeIf { it.isNotBlank() } ?: return null
        }
        val coverUrl = card.coverPath?.takeIf { fileExists(it) }?.let(coverUri)
        return CollectedBookmark(
            id = acquisition,
            title = card.title,
            acquisitionUrl = acquisition,
            providerId = providerId,
            coverUrl = coverUrl,
            feedUrl = null,
        )
    }
}
