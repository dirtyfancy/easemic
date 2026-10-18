package com.easemic.app.feature.browse

enum class ResourcePresence { REMOTE, BOOKMARKED, CLOUD, TOMBSTONED }

data class CloudPresenceRef(
    val providerId: String,
    val acquisitionUrl: String,
)

enum class DetailSourceFamily { FRONT_INDEX, OPDS }

data class DetailActionFacts(
    val presence: ResourcePresence,
    val sourceFamily: DetailSourceFamily = DetailSourceFamily.FRONT_INDEX,
)

data class DetailActionPresentation(
    val helper: String,
)

object DetailActionMatrix {
    fun present(facts: DetailActionFacts): DetailActionPresentation {
        // Owner 2026-09-09: OPDS is a first-class source — same verbs as
        // E-Hentai/nhentai (collect to the user's cloud, download to phone),
        // only the helper copy differs.
        val base = presentFrontIndex(facts)
        if (facts.sourceFamily == DetailSourceFamily.OPDS) {
            val helper = when (facts.presence) {
                ResourcePresence.REMOTE -> RemoteDetailHelperCopy.OPDS_LIBRARY
                else -> base.helper
            }
            return base.copy(helper = helper)
        }
        return base
    }

    private fun presentFrontIndex(facts: DetailActionFacts): DetailActionPresentation {
        return when (facts.presence) {
            ResourcePresence.REMOTE -> DetailActionPresentation(
                helper = RemoteDetailHelperCopy.BROWSE_COLLECT,
            )
            ResourcePresence.BOOKMARKED -> DetailActionPresentation(
                helper = RemoteDetailHelperCopy.BOOKMARKED,
            )
            ResourcePresence.CLOUD -> DetailActionPresentation(
                helper = RemoteDetailHelperCopy.CLOUD_COLLECTION,
            )
            ResourcePresence.TOMBSTONED -> DetailActionPresentation(
                helper = RemoteDetailHelperCopy.TOMBSTONED,
            )
        }
    }
}

object ResourcePresenceResolver {
    fun resolve(
        acquisitionUrl: String?,
        bookmarked: Boolean,
        listedCloudIds: Set<String>,
        listedAcquisitionRefs: Set<String>,
        peekedCloudId: String?,
        trashCloudIds: Set<String> = emptySet(),
        trashAcquisitionRefs: Set<String> = emptySet(),
        providerId: String = "",
        serialized: Boolean = false,
        listedProviderAcquisitionRefs: Set<CloudPresenceRef> = emptySet(),
        trashProviderAcquisitionRefs: Set<CloudPresenceRef> = emptySet(),
    ): ResourcePresence {
        val url = acquisitionUrl?.trim().orEmpty()
        val scopedRef = CloudPresenceRef(providerId, url)
        val cloudId = CloudCollectionFeedMapper.parseCloudComicId(url)
        if (cloudId != null) {
            if (cloudId in listedCloudIds) {
                return ResourcePresence.CLOUD
            }
            if (cloudId in trashCloudIds) {
                return if (bookmarked) ResourcePresence.BOOKMARKED else ResourcePresence.TOMBSTONED
            }
            return ResourcePresence.CLOUD
        }
        if (url.isNotEmpty() &&
            (scopedRef in listedProviderAcquisitionRefs || (!serialized && url in listedAcquisitionRefs))
        ) {
            return ResourcePresence.CLOUD
        }
        val peeked = peekedCloudId?.trim().orEmpty()
        if (peeked.isNotEmpty() && peeked in listedCloudIds) {
            return ResourcePresence.CLOUD
        }
        if (bookmarked) return ResourcePresence.BOOKMARKED
        if (url.isNotEmpty() &&
            (scopedRef in trashProviderAcquisitionRefs || (!serialized && url in trashAcquisitionRefs))
        ) {
            return ResourcePresence.TOMBSTONED
        }
        if (peeked.isNotEmpty() && peeked in trashCloudIds) {
            return ResourcePresence.TOMBSTONED
        }
        return ResourcePresence.REMOTE
    }
}

fun ResourcePresence.usesCloudCollection(): Boolean =
    this == ResourcePresence.CLOUD || this == ResourcePresence.TOMBSTONED

object CloudPresenceSets {
    fun fromItems(items: List<BrowseFeedItem>): Pair<Set<String>, Set<String>> {
        val ids = items.mapNotNull { CloudCollectionFeedMapper.parseCloudComicId(it.acquisitionUrl) }.toSet()
        val refs = items.mapNotNull { item ->
            item.upstreamAcquisitionUrl?.trim()?.takeIf { it.isNotEmpty() }
        }.toSet()
        return ids to refs
    }

    fun fromItemsScoped(items: List<BrowseFeedItem>): Pair<Set<String>, Set<CloudPresenceRef>> {
        val ids = items.mapNotNull { CloudCollectionFeedMapper.parseCloudComicId(it.acquisitionUrl) }.toSet()
        val refs = items.mapNotNull { item ->
            item.upstreamAcquisitionUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { url ->
                CloudPresenceRef(item.providerId, url)
            }
        }.toSet()
        return ids to refs
    }
}
