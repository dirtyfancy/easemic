package com.easemic.app.feature.detail

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.CloudCollectionFeedMapper
import com.easemic.app.feature.browse.seriesIdentityFor
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.sync.CloudCardLayout
import com.easemic.app.sync.CloudCollectionCard

/**
 * What the content of a detail entry is: a serialized series (chapters) or a
 * one-shot gallery (pages). Deliberately a different axis from
 * [com.easemic.app.feature.detail.model.DetailContentKind],
 * which is the detail table's rendering axis (PAGES vs CHAPTERS).
 */
enum class DetailContentKind { SERIES, GALLERY }

/**
 * The one identity every detail load step and every action keys off (spec
 * §3.1 item 4): the original source namespace and canonical series-or-item id,
 * the upstream acquisition, the cloud comic id, the local item id and the
 * content kind. Facets an entry does not carry stay null — a cloud card that
 * never hydrated its source identity still has a [cloudComicId], and an
 * imported local row has only a [localItemId].
 *
 * Every derivation here replaces an inline one at a load step or action, so
 * nothing re-derives identity from raw fields at tap time and nothing
 * dispatches on a provider display string. Namespaces are stored ids
 * ([SeriesIdentityV2.providerNamespace] and friends), never display names.
 */
data class DetailIdentity(
    /** Original source namespace — a stored id, never a display name. */
    val sourceNamespace: String?,
    /** Canonical series-or-item id within [sourceNamespace]. */
    val canonicalId: String?,
    /** Upstream acquisition locator, when the entry carries one. */
    val upstreamAcquisition: String?,
    /** Cloud Collection comic id, when the entry is (or is bound to) a cloud card. */
    val cloudComicId: String?,
    /** Library row id, for a local entry. */
    val localItemId: Long?,
    val contentKind: DetailContentKind,
) {
    /**
     * The stored series key later steps use (Room, mirrors, progress, refresh).
     * Null only when the entry carries no source identity at all.
     */
    val series: SeriesIdentityV2?
        get() = sourceNamespace?.let { namespace ->
            canonicalId?.let { id -> SeriesIdentityV2(namespace, id) }
        }

    companion object {
        /**
         * A Browse / History / collected entry. The canonical id is the same
         * one today's load path keys Room, mirrors and progress on
         * ([seriesIdentityFor]), so this identity never forks from the stored
         * rows. The content kind is the caller's dispatch (the source registry
         * decides it; a rebuilt bookmark or history row alone cannot).
         */
        fun fromBrowseFeedItem(
            item: BrowseFeedItem,
            contentKind: DetailContentKind,
        ): DetailIdentity {
            val series = seriesIdentityFor(item)
            return DetailIdentity(
                sourceNamespace = series.providerNamespace,
                canonicalId = series.canonicalSeriesId,
                upstreamAcquisition = item.upstreamAcquisitionUrl,
                cloudComicId = CloudCollectionFeedMapper.parseCloudComicId(item.acquisitionUrl),
                localItemId = null,
                contentKind = contentKind,
            )
        }

        /**
         * A cloud-collection card. The source facet comes from the card's own
         * hydrated fields — never the feed row, which for a cloud card can
         * carry the cloud collection's own provider id — and the content kind
         * from the card's layout.
         */
        fun fromCloudCard(card: CloudCollectionCard): DetailIdentity =
            DetailIdentity(
                sourceNamespace = card.providerId?.trim()?.takeIf { it.isNotEmpty() },
                canonicalId = card.providerSeriesId?.trim()?.takeIf { it.isNotEmpty() },
                upstreamAcquisition = card.acquisitionRef,
                cloudComicId = card.cloudComicId,
                localItemId = null,
                contentKind = if (card.layoutKind == CloudCardLayout.SERIES) {
                    DetailContentKind.SERIES
                } else {
                    DetailContentKind.GALLERY
                },
            )

        /**
         * A local library entry (import or catalog download). Today's local
         * Detail path renders one page comic — the Library's serialized series
         * cards open through the remote path instead — so the kind is GALLERY;
         * the id facets come from the row and its token-free acquisition key.
         */
        fun fromLocalItem(subject: DetailSubject.Local): DetailIdentity {
            val acquisitionKey = subject.acquisitionKey
            return DetailIdentity(
                sourceNamespace = subject.acquisitionNamespace,
                canonicalId = acquisitionKey
                    ?.takeIf { it.contains("://") }
                    ?.substringAfter("://")
                    ?.takeIf { it.isNotEmpty() },
                upstreamAcquisition = acquisitionKey,
                cloudComicId = CloudCollectionFeedMapper.parseCloudComicId(acquisitionKey),
                localItemId = subject.item.id.value,
                contentKind = DetailContentKind.GALLERY,
            )
        }
    }
}
