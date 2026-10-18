package com.easemic.app.feature.download

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.BrowseFeedRepository
import com.easemic.app.feature.browse.CloudCollectionFeedMapper
import com.easemic.app.feature.providers.CloudCollectionSource
import java.net.URI

/**
 * Resolve [GalleryIdentityV1] from a Browse Detail card (spec §4.1).
 *
 * Cloud Collection cards resolve **upstream** Download via original Front/Index/OPDS
 * identity. That path must not mint a `cloudComicId` identity.
 * Cloud chrome Download-from-R2 uses [CloudLocalDownloadIdentity] instead.
 */
sealed class BrowseGalleryIdentityResult {
    data class Resolved(val identity: GalleryIdentityV1) : BrowseGalleryIdentityResult()
    data class Unavailable(val reason: String) : BrowseGalleryIdentityResult()
}

object BrowseGalleryIdentity {
    /** Primary button label when Download is unavailable (spec §4.1 / §4.2). */
    const val CLOUD_UNAVAILABLE_LABEL: String = "Download unavailable"

    const val CLOUD_UNAVAILABLE_HINT: String =
        "Original source identity could not be resolved on this device."

    fun resolve(
        item: BrowseFeedItem,
        originAcquisitionUrl: String? = null,
        originProviderId: String? = null,
    ): BrowseGalleryIdentityResult {
        val cloudId = CloudCollectionFeedMapper.parseCloudComicId(item.acquisitionUrl)
        if (cloudId != null || item.providerId == CloudCollectionSource.SOURCE_ID) {
            val origin = originAcquisitionUrl?.trim().orEmpty()
            if (origin.isEmpty() || CloudCollectionFeedMapper.parseCloudComicId(origin) != null) {
                return BrowseGalleryIdentityResult.Unavailable(CLOUD_UNAVAILABLE_LABEL)
            }
            return resolveAcquisition(
                acquisitionUrl = origin,
                providerId = originProviderId?.takeIf { it.isNotBlank() }
                    ?: item.providerId.takeIf { it != CloudCollectionSource.SOURCE_ID }.orEmpty(),
                entryId = null,
            )
        }
        return resolveAcquisition(
            acquisitionUrl = item.acquisitionUrl,
            providerId = item.providerId,
            entryId = item.id.takeIf { it.isNotBlank() },
        )
    }

    private fun resolveAcquisition(
        acquisitionUrl: String?,
        providerId: String,
        entryId: String?,
    ): BrowseGalleryIdentityResult {
        val acquisition = acquisitionUrl?.trim().orEmpty()
        if (acquisition.isNotEmpty()) {
            BrowseFeedRepository.parseEhentaiAcquisition(acquisition)?.let { (gid, _) ->
                return BrowseGalleryIdentityResult.Resolved(
                    GalleryIdentityV1(
                        providerNamespace = GalleryIdentityV1.EHENTAI,
                        canonicalItemId = gid.toString(),
                    ),
                )
            }
            BrowseFeedRepository.parseNhentaiAcquisition(acquisition)?.let { id ->
                return BrowseGalleryIdentityResult.Resolved(
                    GalleryIdentityV1(
                        providerNamespace = GalleryIdentityV1.NHENTAI,
                        canonicalItemId = id.toString(),
                    ),
                )
            }
        }

        val opdsConfigId = providerId.trim()
        if (opdsConfigId.isEmpty() ||
            opdsConfigId == GalleryIdentityV1.EHENTAI ||
            opdsConfigId == GalleryIdentityV1.NHENTAI ||
            opdsConfigId == CloudCollectionSource.SOURCE_ID
        ) {
            return BrowseGalleryIdentityResult.Unavailable(CLOUD_UNAVAILABLE_LABEL)
        }

        val namespace = GalleryIdentityV1.opdsNamespace(opdsConfigId)
        val stableEntry = unwrapOwnIdPrefix(entryId, namespace)?.takeIf { id ->
            id.isNotBlank() && !looksLikeTitleFallbackId(id, opdsConfigId)
        }
        // #C19 audit F10, narrowed by re-review item 4: an "entry id" that is
        // itself the acquisition URL (the Detail collect's activityNavItemId
        // fallback for a feed with no ids) is URL-derived, exactly like the
        // acquisition branch below. A URL-shaped id that is NOT the acquisition
        // (COPS/Calibre-Web `…/book?id=123`) is a stable id and stays verbatim.
        val entryIsTheAcquisition = stableEntry != null && looksLikeUrl(stableEntry) &&
            acquisition.isNotEmpty() && safeNormalize(stableEntry) == safeNormalize(acquisition)
        if (stableEntry != null && !entryIsTheAcquisition) {
            return BrowseGalleryIdentityResult.Resolved(
                GalleryIdentityV1(
                    providerNamespace = namespace,
                    canonicalItemId = stableEntry,
                    fallbackUrlDerived = false,
                ),
            )
        }
        if (acquisition.isEmpty()) return BrowseGalleryIdentityResult.Unavailable(CLOUD_UNAVAILABLE_LABEL)
        return BrowseGalleryIdentityResult.Resolved(
            GalleryIdentityV1(
                providerNamespace = namespace,
                canonicalItemId = safeNormalize(acquisition),
                fallbackUrlDerived = true,
            ),
        )
    }

    // #C19 re-review item 5: a malformed URL is still a deterministic identity;
    // never a throw out of a bare launch.
    private fun safeNormalize(url: String): String =
        runCatching { normalizeAcquisitionUrl(url) }.getOrElse { url.trim() }

    /**
     * #C19b: recover the entry id from an id our OWN identity layer minted.
     *
     * `detailCollectFeedItem` has no bookmark for a never-collected download, so
     * its `id` falls through to `"<providerNamespace>:<canonicalItemId>"`. That
     * value then reached [looksLikeTitleFallbackId] with `providerId` holding the
     * full namespace, the prefix matched, and a perfectly good Kavita entry id
     * was thrown away as a synthetic title — sending resolution down the URL
     * branch and forking the identity away from the pages already on disk.
     *
     * Stripping the namespace is safe precisely because the namespace is ours: a
     * server-supplied entry id never carries it, so an id that does was written
     * by us and the remainder is the id we put there. A genuine `providerId:title`
     * synthetic uses the BARE config id, so it survives this and is still
     * rejected below.
     */
    private fun unwrapOwnIdPrefix(id: String?, namespace: String): String? {
        val raw = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val ownPrefix = "$namespace:"
        return if (raw.startsWith(ownPrefix)) {
            raw.removePrefix(ownPrefix).trim().takeIf { it.isNotEmpty() }
        } else {
            raw
        }
    }

    /** `providerId:title` synthetic ids from [BrowseFeedRepository] when entry.id is missing. */
    private fun looksLikeTitleFallbackId(id: String, providerId: String): Boolean =
        id.startsWith("$providerId:")

    /** #C19 audit F10: a locator in an id field — never a stable entry id. */
    private fun looksLikeUrl(id: String): Boolean =
        id.startsWith("http://", ignoreCase = true) || id.startsWith("https://", ignoreCase = true)

    /**
     * Normalize scheme/host/path; drop query and fragment so mutable locators
     * do not fork identity (spec §4.1 OPDS fallback).
     */
    fun normalizeAcquisitionUrl(url: String): String {
        val uri = URI(url.trim())
        val scheme = (uri.scheme ?: "https").lowercase()
        val host = (uri.host ?: "").lowercase()
        val port = when {
            uri.port < 0 -> ""
            scheme == "https" && uri.port == 443 -> ""
            scheme == "http" && uri.port == 80 -> ""
            else -> ":${uri.port}"
        }
        val path = uri.path?.takeIf { it.isNotEmpty() } ?: "/"
        return "$scheme://$host$port$path"
    }
}
