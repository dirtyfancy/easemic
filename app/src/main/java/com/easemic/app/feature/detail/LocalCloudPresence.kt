package com.easemic.app.feature.detail

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.acquisitionKey
import com.easemic.app.feature.library.galleryDedupKey

/**
 * Cloud presence for a Local Detail row.
 *
 * A cloud card carries two independent identities: its durable `cloud:<id>`
 * acquisition and, when known, the original upstream acquisition. Keep both
 * when matching a local download; choosing one with `?:` loses the cloud id
 * as soon as metadata has an upstream reference.
 */
object LocalCloudPresence {
    fun isInCloud(
        acquisitionKey: String?,
        identity: GalleryIdentityV1?,
        listedCloudItems: List<BrowseFeedItem>,
    ): Boolean {
        val localKeys = buildSet {
            galleryDedupKey(acquisitionKey)?.let(::add)
            if (identity?.providerNamespace == GalleryIdentityV1.CLOUD_COLLECTION) {
                identity.canonicalItemId.trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { add("cloud:$it") }
            }
            identity?.acquisitionKey()?.let { galleryDedupKey(it) }?.let(::add)
        }
        if (localKeys.isEmpty()) return false

        return listedCloudItems.any { card ->
            listOfNotNull(
                card.acquisitionUrl,
                card.upstreamAcquisitionUrl,
            ).mapNotNull(::galleryDedupKey).any(localKeys::contains)
        }
    }
}
