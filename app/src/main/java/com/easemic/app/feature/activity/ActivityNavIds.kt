package com.easemic.app.feature.activity

import com.easemic.app.feature.browse.BrowseFeedRepository
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.providers.NhentaiEnableStore

/**
 * A12: canonical `BrowseFeedItem.id` for an Activity row's Detail hand-off
 * (Activity → Detail → [com.easemic.app.feature.history.BrowseHistoryStore]).
 *
 * Before this, `onOpenDownload`/`onOpenSync` used the raw job id or the
 * full acquisition URL (E-Hentai's carries a per-view token) as the
 * `BrowseFeedItem.id`, so a gallery opened from Activity recorded a
 * *different* history row than the same gallery opened from Browse (whose
 * id is always `"<providerId>:<galleryId>"`, see [BrowseFeedRepository]'s
 * `mapFrontEntry`/`mapIndexEntry`) — two rows for one gallery.
 *
 * Mirrors those ids exactly: `"ehentai:<galleryId>"` / `"nhentai:<galleryId>"`.
 * Falls back to the raw acquisition string when it isn't a recognized
 * E-Hentai/nhentai acquisition (OPDS, cloud, or anything unparseable), and
 * to null when there is no acquisition string at all — callers should fall
 * back further to whatever row-local id they already have (job id, etc.).
 */
fun activityNavItemId(acquisitionUrl: String?): String? {
    val url = acquisitionUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    BrowseFeedRepository.parseEhentaiAcquisition(url)?.let { (galleryId, _) ->
        return "${EhentaiEnableStore.SOURCE_ID}:$galleryId"
    }
    BrowseFeedRepository.parseNhentaiAcquisition(url)?.let { galleryId ->
        return "${NhentaiEnableStore.SOURCE_ID}:$galleryId"
    }
    return url
}
