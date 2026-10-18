package com.easemic.app.importing.opds

enum class OpdsLinkRel {
    SUBSECTION,
    IMAGE,
    IMAGE_THUMBNAIL,
    ACQUISITION,
    ACQUISITION_OPEN_ACCESS,
    PSE_STREAM,
    NEXT,
    SELF,
    SEARCH,
    OTHER,
}

data class OpdsLink(
    val rel: OpdsLinkRel,
    val href: String,
    val type: String? = null,
    val count: Int? = null,
)

data class OpdsEntry(
    val id: String?,
    val title: String,
    val summary: String? = null,
    val links: List<OpdsLink> = emptyList(),
    /** Entry author (atom <author><name>), when present. */
    val author: String? = null,
    /** Entry publisher (dcterms:publisher or atom publisher), when present. */
    val publisher: String? = null,
) {
    fun primarySubsection(): OpdsLink? =
        links.firstOrNull { it.rel == OpdsLinkRel.SUBSECTION }

    fun primaryAcquisition(): OpdsLink? =
        links.firstOrNull { it.rel == OpdsLinkRel.ACQUISITION_OPEN_ACCESS }
            ?: links.firstOrNull { it.rel == OpdsLinkRel.ACQUISITION }

    fun coverImage(): OpdsLink? =
        links.firstOrNull { it.rel == OpdsLinkRel.IMAGE }
            ?: links.firstOrNull { it.rel == OpdsLinkRel.IMAGE_THUMBNAIL }

    fun primaryStream(): OpdsLink? =
        links.firstOrNull { it.rel == OpdsLinkRel.PSE_STREAM }
}

data class OpdsFeed(
    val id: String?,
    val title: String,
    val entries: List<OpdsEntry>,
    /**
     * Feed-level links. Paginated shelves advertise the rest of the collection
     * here as `rel="next"` and nowhere else, so dropping these truncates a
     * library to its first page.
     */
    val links: List<OpdsLink> = emptyList(),
    /** OpenSearch feed-level total, when the catalog advertises it. */
    val totalResults: Int? = null,
) {
    fun nextPage(): OpdsLink? = links.firstOrNull { it.rel == OpdsLinkRel.NEXT }

    /**
     * The OpenSearch description document link a catalog advertises at feed
     * level (`rel="search"`, type `application/opensearchdescription+xml`).
     * Kavita/Komga expose their real search here; without it Browse can only
     * client-filter one page.
     */
    fun searchLink(): OpdsLink? = links.firstOrNull { it.rel == OpdsLinkRel.SEARCH }
}
