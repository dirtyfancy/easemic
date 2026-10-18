package com.easemic.app.feature.detail

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.model.LibraryItem
import com.easemic.app.model.MetadataSnapshot

/**
 * What the one Detail skin is being asked to render (A32 §1). A catalog row
 * (Browse / bookmark / cloud tile) is [Remote]; an imported folder and a
 * finished catalog download are both [Local] and differ only by whether an
 * upstream acquisition exists.
 */
sealed interface DetailSubject {
    data class Remote(val item: BrowseFeedItem) : DetailSubject

    data class Local(
        val item: LibraryItem,
        val metadata: MetadataSnapshot,
        /** Token-free `<ns>://<id>` for a catalog download; null ⇒ SAF import. */
        val acquisitionKey: String? = null,
        val collected: Boolean = false,
        val inCloud: Boolean = false,
        val downloadState: DownloadJobState? = null,
        /**
         * P-a: set only when this download's job SEALED short of its pinned page
         * count under a GONE verdict (`detailTruncatedSeal`). Null for every
         * complete download and for every import.
         */
        val truncatedSeal: DetailTruncatedSeal? = null,
        /**
         * C9: the source delisted this comic (`DownloadJob.isDelistedAtSource()`
         * on the row's job). Lives on the subject, not on [item], because the
         * Detail's item comes from `observeItemDetail`, which never carries the
         * shelf projection's flag — reading `item.delistedAtSource` here was a
         * dead input.
         */
        val delistedAtSource: Boolean = false,
    ) : DetailSubject {
        /** An imported folder has no upstream, so it can never be collected. */
        val isCatalogDownload: Boolean get() = acquisitionKey != null

        /**
         * Provider namespace of [acquisitionKey] (`ehentai`, `opds:<cfg>`, ...);
         * null for an import. J2: which namespace it is decides whether Collect
         * may be offered at all -- see [isCollectableNamespace].
         */
        val acquisitionNamespace: String?
            get() = acquisitionKey
                ?.takeIf { it.contains("://") }
                ?.substringBefore("://")
                ?.takeIf { it.isNotEmpty() }

        /**
         * A30 §2.2 (S4b/I2): [LibraryItem.isAvailable] is false for two very different
         * reasons. Only a lost grant may be told as "Access lost … choose it again";
         * files that left the phone get the statement with no action. Read straight off
         * the row so the skin and the shelf can never disagree about which it is.
         */
        val sourceAccessLost: Boolean get() = item.sourceAccessLost
    }
}

/**
 * Local row → the `BrowseFeedItem`-shaped value
 * [com.easemic.app.feature.browse.RemoteDetailMetadataLayout.from]
 * consumes, so both kinds of subject render through one metadata layout.
 *
 * Nothing is invented: every field is either a real count or a persisted
 * string, and each is dropped when absent — a row with nothing persisted
 * therefore produces no meta line and no tag groups at all (spec §2, §7 bans).
 * `secondaryLine` is deliberately blank: the catalog path's `"Catalog"`
 * fallback would be a lie about a folder on this phone, and the helper line
 * already says where the comic lives.
 */
fun DetailSubject.Local.toFeedItem(): BrowseFeedItem = BrowseFeedItem(
    id = item.id.value.toString(),
    title = metadata.title,
    secondaryLine = "",
    acquisitionUrl = acquisitionKey,
    pageCount = item.pageCount.takeIf { it > 0 },
    language = metadata.language?.trim()?.takeIf { it.isNotEmpty() },
    // D2 (amended): a download persists creators namespaced (`artist:foo` /
    // `group:bar`) because the column is the only record of which slot a name
    // belongs in; splitting them here is what makes a circle render under
    // GROUP. An import's bare ComicInfo name stays an artist, verbatim.
    artists = metadata.creators.creatorLabels(groupSlot = false),
    groups = metadata.creators.creatorLabels(groupSlot = true),
    // D1: all genres, joined — picking one would silently drop persisted facts.
    category = metadata.genres.map { it.trim() }.filter { it.isNotEmpty() }
        .takeIf { it.isNotEmpty() }?.joinToString(" / "),
    tags = metadata.tags.map { it.trim() }.filter { it.isNotEmpty() },
)

/**
 * Splits persisted creators into the artist and group slots
 * [com.easemic.app.feature.browse.RemoteDetailMetadataLayout]
 * renders from. Only the two namespaces the download commit writes
 * ([com.easemic.app.feature.download.DownloadCommitMetadata.creators])
 * are stripped; anything else — a ComicInfo name, a name that merely contains a
 * colon — is kept verbatim as an artist rather than being cut in half.
 */
private fun List<String>.creatorLabels(groupSlot: Boolean): List<String> = mapNotNull { raw ->
    val creator = raw.trim()
    if (creator.isEmpty()) return@mapNotNull null
    val colon = creator.indexOf(':')
    val namespace = if (colon > 0) creator.substring(0, colon).trim().lowercase() else ""
    val label = if (colon > 0) creator.substring(colon + 1).trim() else creator
    when (namespace) {
        "group" -> label.takeIf { groupSlot && it.isNotEmpty() }
        "artist" -> label.takeIf { !groupSlot && it.isNotEmpty() }
        else -> creator.takeIf { !groupSlot }
    }
}.distinct()
