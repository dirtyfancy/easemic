package com.easemic.app.feature.source

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.BrowseFeedRepository
import com.easemic.app.feature.detail.model.*
import com.easemic.app.feature.providers.CloudCollectionSource
import com.easemic.app.importing.ehentai.ehentaiComicDetail
import com.easemic.app.importing.nhentai.nhentaiComicDetail
import com.easemic.app.importing.opds.opdsComicDetail
import com.easemic.app.sync.*
import java.time.ZoneId

typealias GalleryDetailAdapter = (BrowseFeedItem, DetailAdapterContext) -> ComicDetail
private val builtInGalleryDetailAdapters: Map<String, GalleryDetailAdapter> = mapOf(
    "ehentai" to ::ehentaiComicDetail,
    "exhentai_catalog" to ::ehentaiComicDetail,
    "nhentai" to ::nhentaiComicDetail,
)
class SourceDetailRegistry(
    private val sources: ComicSourceRegistry,
    val profiles: SourceDetailProfiles = builtInDetailProfiles,
    private val galleryAdapters: Map<String, GalleryDetailAdapter> = builtInGalleryDetailAdapters,
) {
    private fun source(namespace: String?): ComicSource? {
        if (namespace.isNullOrBlank()) return null
        return sources.source(namespace) ?: sources.source("opds:$namespace")
    }
    private fun normalizedNamespace(raw: String?): String? = source(raw)?.descriptor?.namespace ?: raw
    fun context(
        namespace: String?,
        storedSourceName: String? = null,
        covers: DetailCover = DetailCover(),
        zone: ZoneId = ZoneId.systemDefault(),
        observation: DetailObservation = DetailObservation.COMPLETE,
    ): DetailAdapterContext = DetailAdapterContext(
        profile = profiles.profile(normalizedNamespace(namespace)),
        sourceName = source(namespace)?.descriptor?.displayName ?: storedSourceName ?: if (namespace.isNullOrBlank()) "On this phone" else "",
        covers = covers, zone = zone, observation = observation,
    )
    fun feed(item: BrowseFeedItem, observation: DetailObservation = DetailObservation.COMPLETE): ComicDetail {
        val namespace = detailNamespaceFor(item)
        val context = context(namespace, observation = observation)
        if (source(namespace)?.descriptor?.serialized == true) {
            val row = SeriesDetailMetadata(title = DetailField.Value(item.title),
                cover = item.coverUrl?.let { DetailField.Value(it) } ?: DetailField.Omitted)
            val metadata = (item.seriesMetadata ?: SeriesDetailMetadata()).over(row)
            return seriesComicDetail(null, metadata, context, SeriesCountInput())
        }
        val adapter = galleryAdapters[namespace]
        return when {
            adapter != null -> adapter(item, context)
            namespace?.startsWith("opds:") == true -> opdsComicDetail(item, context)
            else -> galleryDetail(item, context)
        }
    }

    /**
     * #E102 (owner ask: keep Library detail in lockstep with Browse): a Cloud
     * collection gallery card renders through its UPSTREAM source's adapter and
     * profile — an E-Hentai/nhentai comic collected to the cloud keeps the exact
     * detail look it has in Browse (namespaced tag rows, source profile), instead
     * of the default flat layout whose chips read as raw "namespace:value" pairs.
     * Only the rendering namespace is resolved here; the item's identity fields
     * stay cloud-shaped for routing/dedup.
     */
    private fun detailNamespaceFor(item: BrowseFeedItem): String? {
        if (item.providerId != CloudCollectionSource.SOURCE_ID) {
            return normalizedNamespace(item.providerId)
        }
        val ref = item.upstreamAcquisitionUrl?.trim().orEmpty()
        return when {
            BrowseFeedRepository.parseEhentaiAcquisition(ref) != null -> "ehentai"
            BrowseFeedRepository.parseNhentaiAcquisition(ref) != null -> "nhentai"
            else -> normalizedNamespace(item.providerId)
        }
    }
    fun series(namespace: String, snapshot: SeriesDetailSnapshot?, seed: SeriesDetailMetadata,
        counts: SeriesCountInput, covers: DetailCover = DetailCover(), storedSourceName: String? = null,
    ): ComicDetail = seriesComicDetail(snapshot, seed, context(namespace, storedSourceName, covers), counts)

    fun cloudGallery(meta: CloudComicMeta, covers: DetailCover = DetailCover(), storedSourceName: String? = null): ComicDetail =
        cloudGalleryComicDetail(meta, context(meta.providerId, storedSourceName, covers))

    fun cloudSeries(namespace: String, manifest: CloudSeriesManifest?, mirrorSnapshot: SeriesDetailSnapshot?,
        seed: SeriesDetailMetadata, savedChapterIds: Set<String>, covers: DetailCover = DetailCover(), storedSourceName: String? = null,
    ): ComicDetail {
        require(manifest == null || manifest.providerId == namespace) { "series origin mismatch" }
        return cloudSeriesComicDetail(manifest, mirrorSnapshot, seed, context(namespace, storedSourceName, covers), savedChapterIds)
    }
}
