package com.easemic.app.feature.browse

import com.easemic.app.feature.download.chapterNoun
import com.easemic.app.feature.providers.CloudCollectionSource
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.feature.source.sourceDisplayNameFor
import com.easemic.app.sync.CloudCardLayout
import com.easemic.app.sync.CloudCollectionCard
import com.easemic.app.sync.CloudComicMeta

object CloudCollectionFeedMapper {
    private const val ACQUISITION_PREFIX = "cloud:"

    /**
     * #E101: name the source a card came from, not the bucket it lives in.
     * A gallery card's own [CloudCollectionCard.providerId] (mirror-only, set
     * when detail/meta hydration runs) wins; before that, the upstream
     * acquisition's scheme still identifies E-Hentai/nhentai; a card with
     * neither (imported, legacy) keeps the Cloud collection label.
     */
    private fun cardSourceName(card: CloudCollectionCard): String {
        card.providerId?.takeIf { it.isNotBlank() && it != CloudCollectionSource.SOURCE_ID }?.let { id ->
            return sourceDisplayNameFor(id).ifBlank { id }
        }
        return upstreamSourceId(card.acquisitionRef)?.let(::sourceDisplayNameFor)
            ?: CloudCollectionSource.DISPLAY_NAME
    }

    /** The gallery source an upstream acquisition names (E-Hentai/nhentai), or null. */
    fun upstreamSourceId(acquisitionRef: String?): String? {
        val ref = acquisitionRef?.trim().orEmpty()
        return when {
            BrowseFeedRepository.parseEhentaiAcquisition(ref) != null -> EhentaiEnableStore.SOURCE_ID
            BrowseFeedRepository.parseNhentaiAcquisition(ref) != null -> NhentaiEnableStore.SOURCE_ID
            else -> null
        }
    }

    fun mapCard(card: CloudCollectionCard, coverUrl: String? = null): BrowseFeedItem {
        val secondary = listOfNotNull(
            cardSourceName(card),
            if (card.layoutKind == CloudCardLayout.SERIES) {
                "${card.readableChapterCount} / ${card.chapterCount} ${chapterNoun(card.chapterCount)}"
            } else {
                card.pageCount?.let { "$it pages" }
            },
            card.language,
        ).joinToString(" · ")
        val serialized = card.layoutKind == CloudCardLayout.SERIES &&
            !card.providerId.isNullOrBlank() && !card.providerSeriesId.isNullOrBlank()
        return BrowseFeedItem(
            id = if (serialized) card.providerSeriesId.orEmpty() else "${CloudCollectionSource.SOURCE_ID}:${card.cloudComicId}",
            title = card.title,
            secondaryLine = secondary,
            providerId = if (serialized) card.providerId.orEmpty() else CloudCollectionSource.SOURCE_ID,
            subsectionUrl = null,
            acquisitionUrl = "$ACQUISITION_PREFIX${card.cloudComicId}",
            coverUrl = coverUrl,
            pageCount = card.pageCount,
            language = card.language,
            upstreamAcquisitionUrl = card.acquisitionRef,
        )
    }

    fun isSeries(item: BrowseFeedItem): Boolean =
        parseCloudComicId(item.acquisitionUrl) != null &&
            item.providerId.isNotBlank() && item.providerId != CloudCollectionSource.SOURCE_ID

    fun parseCloudComicId(acquisitionUrl: String?): String? {
        val raw = acquisitionUrl?.trim().orEmpty()
        if (!raw.startsWith(ACQUISITION_PREFIX)) return null
        return raw.removePrefix(ACQUISITION_PREFIX).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Upstream acquisition for explicit **Read online**.
     * Cloud Collection cards use `cloud:{id}`; stored meta keeps the original
     * front/index acquisition as [CloudComicMeta.acquisitionRef].
     */
    fun upstreamAcquisitionForOnlineRead(
        itemAcquisitionUrl: String?,
        metaAcquisitionRef: String?,
    ): String? {
        if (parseCloudComicId(itemAcquisitionUrl) != null) {
            val ref = metaAcquisitionRef?.trim().orEmpty()
            if (ref.isEmpty() || parseCloudComicId(ref) != null) return null
            return ref
        }
        return itemAcquisitionUrl?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun applyMeta(
        item: BrowseFeedItem,
        meta: CloudComicMeta,
        coverUrl: String? = item.coverUrl,
    ): BrowseFeedItem {
        // #E101: the stored meta names the real source; only a blank/legacy
        // providerId falls back to the Cloud collection label.
        val sourceName = meta.providerId
            .takeIf { it.isNotBlank() && it != CloudCollectionSource.SOURCE_ID }
            ?.let { sourceDisplayNameFor(it).ifBlank { it } }
            ?: CloudCollectionSource.DISPLAY_NAME
        val trimmedBadge = meta.trimmedAdPages?.takeIf { it > 0 }?.let { trimmed ->
            "$trimmed ${if (trimmed == 1) "ad" else "ads"} trimmed"
        }
        val secondary = listOfNotNull(
            sourceName,
            "${meta.pageCount} pages",
            trimmedBadge,
            meta.language,
        ).joinToString(" · ")
        return item.copy(
            title = meta.title.ifBlank { item.title },
            secondaryLine = secondary,
            pageCount = meta.pageCount,
            trimmedAdPages = meta.trimmedAdPages?.takeIf { it > 0 },
            language = meta.language,
            tags = meta.tags,
            artists = meta.artists,
            groups = meta.groups,
            category = meta.category,
            coverUrl = coverUrl,
            upstreamAcquisitionUrl = meta.acquisitionRef.takeIf { it.isNotBlank() }
                ?: item.upstreamAcquisitionUrl,
        )
    }

    /**
     * A39: patch exactly one card's cover into the shelf list when the
     * background thumb-fill finishes a download — instead of re-mapping every
     * card per cover (the pre-A39 full re-map keyed on the cover-URL map,
     * O(N²) mapCard+disk-probe work for N sequential covers). Idempotent: a
     * card not on the shelf, or one already carrying the URI, returns the
     * same list instance.
     */
    fun patchCover(
        items: List<BrowseFeedItem>,
        cloudComicId: String,
        coverUrl: String,
        providerId: String? = null,
        providerSeriesId: String? = null,
    ): List<BrowseFeedItem> {
        val feedId = "${CloudCollectionSource.SOURCE_ID}:${cloudComicId}"
        val serialized = !providerId.isNullOrBlank() && !providerSeriesId.isNullOrBlank()
        fun matches(item: BrowseFeedItem): Boolean = if (serialized) {
            item.providerId == providerId && item.id == providerSeriesId && isSeries(item)
        } else {
            item.id == feedId
        }
        if (items.none { matches(it) && it.coverUrl != coverUrl }) return items
        return items.map { item ->
            if (matches(item) && item.coverUrl != coverUrl) item.copy(coverUrl = coverUrl) else item
        }
    }
}
