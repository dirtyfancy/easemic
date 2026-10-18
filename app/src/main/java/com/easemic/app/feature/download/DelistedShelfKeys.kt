package com.easemic.app.feature.download

import com.easemic.app.feature.library.galleryDedupKey

/**
 * #C12: the acquisition dedup keys the Library shelf compares its collected /
 * cloud tiles against, for every job the source delisted.
 *
 * For E-Hentai / nhentai the identity key (`ehentai://<gid>`, `nhentai://<id>`)
 * IS the dedup key. For an OPDS job the identity key is `opds:<cfg>://<entryId>`
 * while the bookmark holds the http acquisition URL, so the two never matched
 * (the "OPDS key gap" recorded at C9). The locator — written at every enqueue
 * with that same acquisition URL — is the translation; a job with no locator
 * keeps its identity key (never matches, never a false positive).
 */
fun delistedShelfKeys(
    delisted: List<DownloadJob>,
    locatorFor: (GalleryIdentityV1) -> DownloadLocator?,
): Set<String> = delisted.mapNotNullTo(mutableSetOf()) { job ->
    val identity = job.identity
    val viaLocator = if (identity.providerNamespace.startsWith(GalleryIdentityV1.OPDS_PREFIX)) {
        locatorFor(identity)?.acquisitionUrl?.let(::galleryDedupKey)
    } else {
        null
    }
    viaLocator ?: galleryDedupKey(identity.acquisitionKey())
}
