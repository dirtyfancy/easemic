package com.easemic.app.feature.browse

import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.ComicSource
import com.easemic.app.feature.source.SerializedDetailSeedSource
import com.easemic.app.sync.OpdsCloudIngestGuard

fun detailSearchProviderId(
    item: BrowseFeedItem?,
    originalProviderId: String? = null,
    localProviderId: String? = null,
): String? = sequenceOf(originalProviderId, item?.providerId, localProviderId)
    .mapNotNull { it?.trim()?.takeIf { id -> id.isNotEmpty() && id != com.easemic.app.feature.providers.CloudCollectionSource.SOURCE_ID } }
    .firstOrNull()

object DetailSourceFamilyResolver {
    fun of(item: BrowseFeedItem): DetailSourceFamily =
        if (
            OpdsCloudIngestGuard.isHttpAcquisition(item.acquisitionUrl) ||
                OpdsCloudIngestGuard.isHttpAcquisition(item.pageStreamUrl) ||
                isHttpOrHttps(item.acquisitionUrl) ||
                isHttpOrHttps(item.pageStreamUrl)
        ) {
            DetailSourceFamily.OPDS
        } else {
            DetailSourceFamily.FRONT_INDEX
        }

    private fun isHttpOrHttps(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        return raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
    }
}

internal fun seriesIdentityFor(item: BrowseFeedItem): SeriesIdentityV2 {
    BrowseFeedRepository.parseEhentaiAcquisition(item.acquisitionUrl.orEmpty())?.let { (gid, _) ->
        return SeriesIdentityV2(GalleryIdentityV1.EHENTAI, gid.toString())
    }
    BrowseFeedRepository.parseNhentaiAcquisition(item.acquisitionUrl.orEmpty())?.let { gid ->
        return SeriesIdentityV2(GalleryIdentityV1.NHENTAI, gid.toString())
    }
    return SeriesIdentityV2(
        providerNamespace = item.providerId,
        canonicalSeriesId = item.id,
    )
}

internal suspend fun loadSerializedDetailChapters(
    source: ComicSource?,
    item: BrowseFeedItem,
): ChapterListResult? {
    val serializedSource = source ?: return null
    if (!serializedSource.descriptor.serialized) return null
    val series = seriesIdentityFor(item)
    return (serializedSource as? SerializedDetailSeedSource)?.takeDetailSeed(series)
        ?: serializedSource.chapters(series)
}
