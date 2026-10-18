package com.easemic.app.feature.browse

import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.providers.CloudCollectionSource
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.providers.ExhentaiEnableStore
import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.feature.source.ComicSourceRegistry
import com.easemic.app.feature.source.OpdsComicSource
import java.net.URI

sealed interface BrowseFeedNotice {
    data object ALBUM_MATCH : BrowseFeedNotice
    data object ZAIMANHUA_SIGNED_OUT : BrowseFeedNotice
    data class JmScopedFallback(val scope: JmSearchScope, val query: String) : BrowseFeedNotice
}

sealed class BrowseFeedResult {
    data class Ok(
        val items: List<BrowseFeedItem>,
        val nextContinuation: String? = null,
        val totalItems: Int? = null,
        val notice: BrowseFeedNotice? = null,
    ) : BrowseFeedResult()
    data class Error(val message: String) : BrowseFeedResult()
}

/**
 * Browse grids key on BrowseFeedItem.id; a feed that repeats an id (observed
 * live from Kavita's OPDS "Recently Added") would crash LazyVerticalGrid.
 * Dedup by id (first wins) so the key is always unique.
 */
internal fun browseOk(
    items: List<BrowseFeedItem>,
    nextContinuation: String? = null,
    totalItems: Int? = null,
    notice: BrowseFeedNotice? = null,
): BrowseFeedResult.Ok =
    BrowseFeedResult.Ok(items.distinctBy { remoteItemKey(it.providerId, it.id) }, nextContinuation, totalItems, notice)

/** Browse persists bare OPDS provider ids; every other Browse source id is its registry namespace. */
internal fun browseSourceNamespace(sourceId: String): String =
    if (sourceId in BUILT_IN_BROWSE_SOURCE_IDS) sourceId else GalleryIdentityV1.opdsNamespace(sourceId)

private val BUILT_IN_BROWSE_SOURCE_IDS = setOf(
    EhentaiEnableStore.SOURCE_ID,
    ExhentaiEnableStore.SOURCE_ID,
    NhentaiEnableStore.SOURCE_ID,
    CloudCollectionSource.SOURCE_ID,
    GalleryIdentityV1.ZAIMANHUA,
    GalleryIdentityV1.JM,
)

/**
 * Browse's one entry point. [BrowsePrefs.sourceId] names one concrete source (there is no aggregate "all"), which the
 * registry resolves and browses. Cloud comics are not a Browse source: the Library shelf lists them from the hydration
 * mirror.
 */
class BrowseFeedRepository(
    private val sourceRegistry: ComicSourceRegistry,
) {
    suspend fun load(
        prefs: BrowsePrefs,
        query: String,
        explicitFeedUrl: String? = null,
        explicitProviderId: String? = null,
        continuation: String? = null,
    ): BrowseFeedResult {
        // A continuation wins over the drilled-in feed URL: the UI's load-more keeps passing both, and the token
        // already names the next page of that feed.
        if (continuation == null && explicitFeedUrl != null) {
            val opds = sourceRegistry.all().filterIsInstance<OpdsComicSource>()
            val source = opds.firstOrNull { it.provider.id == explicitProviderId } ?: opds.firstOrNull()
                ?: return browseOk(emptyList())
            return source.browseFeed(prefs, query, explicitFeedUrl, explicitProviderId)
        }
        return sourceRegistry.source(browseSourceNamespace(prefs.sourceId))
            ?.browse(prefs, query, continuation)
            ?: browseOk(emptyList())
    }

    companion object {
        const val EHENTAI_SCHEME = "ehentai"
        const val NHENTAI_SCHEME = "nhentai"

        fun ehentaiAcquisitionUrl(galleryId: Long, token: String): String =
            "$EHENTAI_SCHEME://$galleryId/$token"

        fun parseEhentaiAcquisition(url: String): Pair<Long, String>? {
            if (!url.startsWith("$EHENTAI_SCHEME://")) return null
            val rest = url.removePrefix("$EHENTAI_SCHEME://")
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            val gid = rest.substring(0, slash).toLongOrNull() ?: return null
            val token = rest.substring(slash + 1)
            if (token.isBlank()) return null
            return gid to token
        }

        fun nhentaiAcquisitionUrl(galleryId: Long): String =
            "$NHENTAI_SCHEME://$galleryId"

        fun parseNhentaiAcquisition(url: String): Long? {
            if (!url.startsWith("$NHENTAI_SCHEME://")) return null
            val rest = url.removePrefix("$NHENTAI_SCHEME://")
            return rest.substringBefore('/').toLongOrNull()
        }

        fun resolveHref(baseUrl: String, href: String): String {
            if (href.startsWith("http://", ignoreCase = true) ||
                href.startsWith("https://", ignoreCase = true)
            ) {
                return href
            }
            val encoded = href.replace("{", "%7B").replace("}", "%7D")
            return URI(baseUrl).resolve(encoded).toString()
                .replace("%7B", "{")
                .replace("%7D", "}")
        }
    }
}
