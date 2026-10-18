package com.easemic.app.feature.download

import com.easemic.app.sync.CloudCollectionStore

/**
 * Fetches pages for slice-C2 old-bucket migration jobs directly from the
 * RETIRED bucket — never the comic's original upstream source, and never the
 * CURRENT bucket. This is what makes migration takedown-proof: it keeps
 * working even once the original source has gone offline, because it never
 * touches it.
 *
 * Routes on the `r2source:<oldFingerprint>` namespace prefix
 * ([GalleryIdentityV1.R2_SOURCE_PREFIX]), never on the locator's
 * `cloudComicId` alone — an r2source locator's `cloudComicId` is the OLD
 * bucket's id, which the CURRENT-bucket `cloud` adapter must never be handed.
 * `RegistryDownloadPageAdapter` checks this adapter first for exactly that
 * reason.
 *
 * #C61: the Migrate flow that created r2source jobs was deleted (owner ruling
 * 2026-09-21), so production wires [store] to `null` — an r2source row (device
 * probe fixture or leftover) fails its fetch honestly instead of reaching the
 * current bucket under a foreign id. A null result fails the individual fetch
 * rather than crashing adapter construction or the whole download worker loop.
 */
class R2SourceDownloadAdapter(
    private val store: () -> CloudCollectionStore?,
) : DownloadPageBytesAdapter {
    override fun supports(identity: GalleryIdentityV1): Boolean =
        identity.providerNamespace.startsWith(GalleryIdentityV1.R2_SOURCE_PREFIX)

    override fun fetchManifest(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): DownloadPageManifest {
        val collection = requireStore()
        val id = requireOldCloudComicId(identity, locator)
        val manifest = collection.downloadPagesManifest(id)
        val title = locator.title?.takeIf { it.isNotBlank() }
            ?: runCatching { collection.downloadMeta(id).title }.getOrNull()
        return DownloadPageManifest(
            expectedPageCount = manifest.pageCount,
            title = title,
        )
    }

    override fun fetchPageBytes(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
    ): ByteArray {
        val collection = requireStore()
        val id = requireOldCloudComicId(identity, locator)
        return collection.downloadPage(id, ordinal)
    }

    private fun requireStore(): CloudCollectionStore =
        store() ?: error("Old bucket store unavailable for r2source fetch")

    private fun requireOldCloudComicId(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): String {
        locator.cloudComicId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val id = identity.canonicalItemId.trim()
        require(id.isNotEmpty()) { "r2source identity missing old cloudComicId" }
        return id
    }
}
