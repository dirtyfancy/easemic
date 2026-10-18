package com.easemic.app.feature.download

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.CloudCollectionFeedMapper

/**
 * Download identity for Cloud chrome: prefer origin Front/Index/OPDS when the
 * acquisition ref still resolves on this device; otherwise
 * `cloud_collection` + `cloudComicId`. Never mints a new cloud id.
 */
object CloudLocalDownloadIdentity {
    /**
     * #15: the deterministic cloud-library download identity. A Cloud detail
     * MUST use this, not [resolve]'s origin-preference: the origin acquisition
     * ref (meta.acquisitionRef) loads asynchronously after the detail opens, so
     * preferring it made the identity flip from `cloud_collection` to the
     * upstream namespace mid-screen. That flip (a) desynced the download switch
     * (it began observing a different, non-existent job → looked tappable while
     * the real job still ran), (b) created a DUPLICATE job on the next tap, and
     * (c) put the R2 fetch under the upstream's 3-6s/1-concurrent pacing. The
     * cloudComicId is known the instant a Cloud row opens, so this is stable.
     */
    fun stableCloud(cloudComicId: String?): BrowseGalleryIdentityResult? {
        val id = cloudComicId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return BrowseGalleryIdentityResult.Resolved(
            GalleryIdentityV1(
                providerNamespace = GalleryIdentityV1.CLOUD_COLLECTION,
                canonicalItemId = id,
            ),
        )
    }

    fun resolve(
        item: BrowseFeedItem,
        originAcquisitionUrl: String? = null,
        originProviderId: String? = null,
        cloudComicId: String? = null,
    ): BrowseGalleryIdentityResult {
        val originUrl = listOf(originAcquisitionUrl, item.upstreamAcquisitionUrl)
            .mapNotNull { it?.trim()?.takeIf { url -> url.isNotEmpty() } }
            .firstOrNull { CloudCollectionFeedMapper.parseCloudComicId(it) == null }
        val origin = BrowseGalleryIdentity.resolve(
            item = item,
            originAcquisitionUrl = originUrl,
            originProviderId = originProviderId,
        )
        if (origin is BrowseGalleryIdentityResult.Resolved) {
            return origin
        }
        val id = cloudComicId?.trim()?.takeIf { it.isNotEmpty() }
            ?: CloudCollectionFeedMapper.parseCloudComicId(item.acquisitionUrl)
        if (!id.isNullOrBlank()) {
            return BrowseGalleryIdentityResult.Resolved(
                GalleryIdentityV1(
                    providerNamespace = GalleryIdentityV1.CLOUD_COLLECTION,
                    canonicalItemId = id,
                ),
            )
        }
        return origin
    }

    /**
     * A32 §2: [tags]/[language]/[category] are the catalog facts of the row the
     * download was started from, when there is one — a cloud-routed download
     * from an enriched detail knows them, and this locator is what FINISH
     * commits from. A caller with no row (the old-bucket migration) passes
     * nothing and persists nothing.
     */
    fun locator(
        cloudComicId: String,
        title: String?,
        coverUrl: String? = null,
        tags: List<String> = emptyList(),
        language: String? = null,
        category: String? = null,
        browseSourceId: String? = null,
    ): DownloadLocator =
        DownloadLocator(
            acquisitionUrl = "cloud:$cloudComicId",
            title = title,
            cloudComicId = cloudComicId,
            coverUrl = coverUrl,
            tags = tags,
            language = language,
            category = category,
            browseSourceId = browseSourceId,
        )
}

/**
 * CLOUD Read opens the local Library Detail once the R2→device job owns pages
 * on this phone. A36 review M2: that is [downloadJobOwnsLocalPages], not
 * `state == FINISH` — a truncated seal holds real, readable pages too, and
 * sending its preview taps to the cloud (or upstream) instead would step over
 * the copy already on the device.
 */
object CloudDetailReadPreference {
    fun preferLocal(
        jobState: DownloadJobState?,
        intentLocal: Boolean = false,
        hasLibraryRow: Boolean = false,
    ): Boolean = downloadJobOwnsLocalPages(jobState, intentLocal, hasLibraryRow)
}
