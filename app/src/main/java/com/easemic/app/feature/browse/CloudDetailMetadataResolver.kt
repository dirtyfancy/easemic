package com.easemic.app.feature.browse

import com.easemic.app.sync.CloudComicMeta

/**
 * Chooses the already-hydrated local detail mirror before touching the
 * endpoint. A missing local row is the only case that falls back to the
 * remote `meta.json` loader.
 */
suspend fun resolveCloudDetailMetadata(
    bucketFingerprint: String?,
    cloudComicId: String?,
    local: suspend (bucketFingerprint: String, cloudComicId: String) -> CloudComicMeta?,
    remote: suspend (cloudComicId: String) -> CloudComicMeta?,
): CloudComicMeta? {
    val id = cloudComicId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val fingerprint = bucketFingerprint?.trim()?.takeIf { it.isNotEmpty() }
    if (fingerprint != null) {
        local(fingerprint, id)?.let { return it }
    }
    return remote(id)
}

/**
 * Metadata can come from the local mirror while page previews still need a
 * separately resolved cloud resource. Reuse an already-created resource when
 * the metadata path touched the endpoint; otherwise resolve it once for the
 * preview path.
 */
suspend fun <T> resolveCloudDetailPreviewResource(
    existing: T?,
    fallback: suspend () -> T?,
): T? = existing ?: fallback()
