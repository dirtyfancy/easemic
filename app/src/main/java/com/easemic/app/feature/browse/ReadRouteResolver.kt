package com.easemic.app.feature.browse

/**
 * Reverse-proxy Read routing (spec 2026-09-03 §5, plan Task 4): Detail shows one verb —
 * Read. Given the derived [DetailSwitchState.readRoute], this resolver picks the best
 * available path and invokes the matching handler:
 *
 * 1. `LOCAL_DISK` — finished local download (only when [localAvailable] and the local
 *    open succeeds);
 * 2. `CLOUD` — uploaded cloud copy (only when the item carries a `cloud:` acquisition and
 *    the cloud open succeeds);
 * 3. `ONLINE` — upstream source stream (always the terminal fallback).
 *
 * Degradation: a failed local open falls back to cloud, then online; a failed cloud open
 * falls back to online. Handlers report success/failure via a nullable return: non-null
 * means the reader was opened; null means it was not and the next layer takes over.
 * The resolver is a suspending sequential dispatch — each handler is fully awaited, so
 * its return value is the real outcome (a handler may suspend for disk/IO work before
 * answering). Callers wrap the whole dispatch in a coroutine launch; a failed handler
 * must not navigate on its own after returning null.
 */
class ReadRouteResolver(
    val openLocal: suspend (BrowseFeedItem) -> Unit?,
    val openCloud: suspend (BrowseFeedItem) -> Unit?,
    val openOnline: suspend (BrowseFeedItem) -> Unit?,
) {
    suspend fun resolve(
        item: BrowseFeedItem,
        state: DetailSwitchState,
        localAvailable: Boolean,
    ): ReadRoute {
        val requested = state.readRoute
        if (requested == ReadRoute.LOCAL_DISK && localAvailable && openLocal(item) != null) {
            return ReadRoute.LOCAL_DISK
        }
        val canOpenCloud = CloudCollectionFeedMapper.parseCloudComicId(item.acquisitionUrl) != null
        if ((requested == ReadRoute.LOCAL_DISK || requested == ReadRoute.CLOUD) && canOpenCloud) {
            if (openCloud(item) != null) return ReadRoute.CLOUD
        }
        openOnline(item)
        return ReadRoute.ONLINE
    }
}

