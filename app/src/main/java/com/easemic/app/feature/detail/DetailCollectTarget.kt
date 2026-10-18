package com.easemic.app.feature.detail

import com.easemic.app.feature.activity.activityNavItemId
import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.collect.CollectedBookmark
import com.easemic.app.feature.download.DownloadLocator
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.acquisitionKey
import com.easemic.app.feature.library.galleryDedupKey
import com.easemic.app.feature.providers.CloudCollectionSource

/**
 * A32 S2 (J1/K1) — the `BrowseFeedItem` the Local Detail route hands to
 * `collectBookmark` / `uncollectBookmark`.
 *
 * The naive version synthesised the item from [GalleryIdentityV1] alone, which
 * produces a value nothing else in the app agrees with: the identity's
 * `acquisitionKey()` is the token-LESS `ehentai://<gid>`, while Browse rows
 * carry the full `ehentai://<gid>/<token>` and an id shaped `ehentai:<gid>`.
 *
 * Two records can supply the real upstream, and they are tried in this order:
 *
 *  1. **An existing bookmark**, matched on [galleryDedupKey]. This is the only
 *     record that works for a collected-then-downloaded comic (K1): its
 *     locator was written by `CloudLocalDownloadIdentity.locator`, whose
 *     acquisition URL is `cloud:<id>` — the user's own bucket, not an upstream
 *     — while the identity stayed the origin gallery. Handing back the
 *     bookmark's own id and URL is also what makes `uncollectBookmark` →
 *     `resolveCloudComicId` / `listedCloudItemFor` / `bookmarksMatchingUncollect`
 *     all match the row Browse wrote.
 *  2. **The download locator**, for a comic downloaded but never collected.
 *
 * If neither yields an upstream, there is nothing honest to collect against and
 * the caller must offer no Collect switch at all rather than a broken one.
 */
fun detailCollectFeedItem(
    identity: GalleryIdentityV1,
    locator: DownloadLocator?,
    bookmarks: List<CollectedBookmark>,
    fallbackTitle: String,
    pageCount: Int,
): BrowseFeedItem? {
    if (identity.providerNamespace == GalleryIdentityV1.CLOUD_COLLECTION) {
        // Owner 2026-09-09: a local copy downloaded FROM the cloud library is,
        // by definition, in the cloud — its switch renders the in-cloud state
        // and its only verb is Uncollect, which `uncollectBookmark` resolves
        // through the same `cloud:<id>` acquisition the Cloud shelf tile uses.
        val cloudId = identity.canonicalItemId.trim().takeIf { it.isNotEmpty() } ?: return null
        return BrowseFeedItem(
            id = "${CloudCollectionSource.SOURCE_ID}:$cloudId",
            title = locator?.title?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle,
            providerId = CloudCollectionSource.SOURCE_ID,
            acquisitionUrl = "cloud:$cloudId",
            coverUrl = locator?.coverUrl?.trim()?.takeIf { it.isNotEmpty() },
            pageCount = pageCount.takeIf { it > 0 },
        )
    }
    if (!isCollectableNamespace(identity.providerNamespace)) return null
    // A `cloud:<id>` locator points at the user's own bucket. It is a real
    // record of a re-download, but it is not an upstream to collect against.
    val locatorUpstream = locator?.acquisitionUrl
        ?.takeIf { !locator.routesToCloudCollection() }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    // The identity's own key has no token; galleryDedupKey strips the token
    // from the bookmark side too, which is exactly what it exists for. #C12:
    // an OPDS identity key (`opds:<cfg>://<id>`) never equals the bookmark's
    // http acquisition key — the locator's acquisition is the same URL the
    // bookmark holds, so match on either (the C4 "both keys" rule).
    val identityKey = galleryDedupKey(identity.acquisitionKey())
    val locatorKey = galleryDedupKey(locatorUpstream)
    val bookmark = bookmarks.firstOrNull { candidate ->
        val key = galleryDedupKey(candidate.acquisitionUrl) ?: return@firstOrNull false
        key == identityKey || key == locatorKey
    }
    val acquisition = bookmark?.acquisitionUrl?.trim()?.takeIf { it.isNotEmpty() }
        ?: locatorUpstream
        ?: return null
    return BrowseFeedItem(
        // #C19b: our OWN identity outranks the URL fallback. `activityNavItemId`
        // mirrors Browse's `<provider>:<galleryId>` for E-Hentai and nhentai,
        // but it has no such mirror for OPDS and returns the raw acquisition
        // URL. `BrowseGalleryIdentity` then keys the identity to that URL while
        // the pages already on disk are keyed to the server's stable entry id
        // (the owner's phone held `562039` one way and a 458-char URL the
        // other), so the collect minted a second job row and uploaded against
        // an empty namespace. The identity we were handed already carries the
        // right id, so state it before falling back to a URL-derived guess.
        id = bookmark?.id
            ?: identityScopedItemId(identity)
            ?: activityNavItemId(acquisition)
            ?: "${identity.providerNamespace}:${identity.canonicalItemId}",
        title = locator?.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: bookmark?.title?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackTitle,
        providerId = locator?.browseSourceId
            ?: bookmark?.providerId?.takeIf { it.isNotBlank() && it != CloudCollectionSource.SOURCE_ID }
            ?: identity.providerNamespace,
        acquisitionUrl = acquisition,
        coverUrl = locator?.coverUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: bookmark?.coverUrl?.trim()?.takeIf { it.isNotEmpty() },
        pageCount = pageCount.takeIf { it > 0 },
        // #C12: the OPDS containing feed, for the existence check.
        feedUrl = bookmark?.feedUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: locator?.feedUrl?.trim()?.takeIf { it.isNotEmpty() },
    )
}

/**
 * #C19b: the `BrowseFeedItem.id` that round-trips back to [identity].
 *
 * Scoped to OPDS on purpose. E-Hentai and nhentai already agree across every
 * entry point, because [activityNavItemId] reconstructs the same
 * `"<provider>:<galleryId>"` string `BrowseFeedRepository` emits, and their
 * `canonicalItemId` is that gallery id — so widening this would change nothing
 * for them while risking the one path that is currently correct.
 *
 * Returns null for a URL-derived identity: `fallbackUrlDerived` means we never
 * had a stable entry id to begin with, so there is nothing better to offer and
 * the existing fallbacks should run.
 */
private fun identityScopedItemId(identity: GalleryIdentityV1): String? {
    if (!identity.providerNamespace.startsWith(GalleryIdentityV1.OPDS_PREFIX)) return null
    if (identity.fallbackUrlDerived) return null
    val id = identity.canonicalItemId.trim().takeIf { it.isNotEmpty() } ?: return null
    // Guard against an identity that itself holds a URL: older rows were written
    // before this fix and their `canonicalItemId` IS the acquisition URL, with
    // `fallbackUrlDerived` unset. Handing that back as an entry id would let
    // `BrowseGalleryIdentity` treat a URL as stable and re-fork the identity.
    if (id.startsWith("http://") || id.startsWith("https://")) return null
    return "${identity.providerNamespace}:$id"
}

/**
 * A32 S2 (J1) — the item `uncollectBookmark` should receive.
 *
 * `uncollectBookmark` removes by `id` first and only then sweeps by
 * acquisition, and A31's uncollect routing (`applyUncollectJobOutcome`) keys
 * off the same value. A bookmark written from Browse carries Browse's id and
 * Browse's exact acquisition string, which need not be byte-identical to the
 * locator's (E-Hentai tokens rotate). So resolve the REAL bookmark by gallery
 * dedup key and hand back its own id and URL; only when no bookmark matches do
 * we fall back to [collectItem], which is then a no-op removal rather than a
 * removal of the wrong row.
 */
fun detailUncollectFeedItem(
    collectItem: BrowseFeedItem,
    bookmarks: List<CollectedBookmark>,
): BrowseFeedItem {
    val key = galleryDedupKey(collectItem.acquisitionUrl) ?: return collectItem
    val match = bookmarks.firstOrNull { galleryDedupKey(it.acquisitionUrl) == key }
        ?: return collectItem
    return collectItem.copy(id = match.id, acquisitionUrl = match.acquisitionUrl)
}

/**
 * A32 S2 (J2) — only E-Hentai and nhentai downloads may offer Collect.
 *
 * An OPDS acquisition is `opds:<cfg>://<id>`, which is not an http(s) URL, so
 * `OpdsCloudIngestGuard` refuses it downstream -- but only after a bookmark has
 * already been written. Offering the switch at all would be an affordance the
 * subject cannot perform. Cloud-collection and old-bucket migration namespaces
 * are not upstreams either.
 */
fun isCollectableNamespace(providerNamespace: String?): Boolean =
    providerNamespace == GalleryIdentityV1.EHENTAI ||
        providerNamespace == GalleryIdentityV1.NHENTAI ||
        // Owner 2026-09-09: OPDS is a first-class source; a downloaded OPDS
        // comic may be sent to the user's cloud library.
        providerNamespace?.startsWith("opds:") == true ||
        // A local copy of a cloud-library comic keeps its cloud switch (in-cloud
        // state; Uncollect) — see detailCollectFeedItem's cloud branch.
        providerNamespace == GalleryIdentityV1.CLOUD_COLLECTION

/**
 * A32 S2 (K2) — whether a collect/uncollect outcome belongs to the comic whose
 * Local Detail is open.
 *
 * `collectBookmark` announces asynchronously: the pre-flight read, the ingest
 * handoff and the failure paths all write their line long after the tap that
 * started them. Mirroring that line onto whatever Detail is open at the time
 * would attribute one comic's "Collected." -- or one comic's blocked-bucket
 * explanation -- to a different comic entirely.
 *
 * Matched on [galleryDedupKey] rather than raw strings, because the owner is
 * the collect item's URL and the announcement carries the acquisition the
 * bookmark store was keyed with; E-Hentai tokens rotate between the two.
 */
fun detailStatusForOwner(
    owner: String?,
    statusAcquisition: String?,
    status: String?,
): String? {
    if (status == null) return null
    val ownerKey = galleryDedupKey(owner) ?: return null
    return status.takeIf { galleryDedupKey(statusAcquisition) == ownerKey }
}
