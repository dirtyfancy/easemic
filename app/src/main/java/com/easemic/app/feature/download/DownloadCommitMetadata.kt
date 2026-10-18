package com.easemic.app.feature.download

/**
 * A32 §2: the catalog facts that travel enqueue → locator file → FINISH →
 * the Library row, so a downloaded comic can show its own tags with the radio
 * off. Empty is the honest default — a locator written before A32 has none of
 * these, and a row with none renders no tag section at all.
 */
data class DownloadCommitMetadata(
    val tags: List<String> = emptyList(),
    val language: String? = null,
    val category: String? = null,
) {
    /** Raw `namespace:label` strings, so E-Hentai grouping survives the round trip. */
    fun normalizedTags(): List<String> =
        tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * Creators are not a separate upstream field: E-Hentai and nhentai carry
     * them as `artist:` / `group:` tags. The detail Tag projection keeps those
     * namespaces visible; lifting them here re-reads the same persisted
     * strings — it invents nothing — and also restores the local row's
     * dedicated creator fields.
     *
     * The namespace is **kept** (lowercased, e.g. `artist:foo` / `group:bar`):
     * the creators column is the only record of which slot a name belongs in,
     * so dropping it would file every circle under ARTIST forever. The read
     * side (`DetailSubject.Local.toFeedItem`) splits it back out.
     */
    fun creators(): List<String> = normalizedTags().mapNotNull { tag ->
        val colon = tag.indexOf(':')
        if (colon <= 0) return@mapNotNull null
        val namespace = tag.substring(0, colon).trim().lowercase()
        if (namespace != "artist" && namespace != "group") return@mapNotNull null
        val label = tag.substring(colon + 1).trim().takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        "$namespace:$label"
    }.distinct()

    /** The catalog category is the row's one genre; `MetadataSnapshot` has no category field. */
    fun genres(): List<String> = listOfNotNull(category?.trim()?.takeIf { it.isNotEmpty() })
}

/**
 * A36 review O1 — what a commit that has no locator in hand still needs: the
 * comic's title and [DownloadCommitMetadata].
 *
 * The FINISH path reads both off the [DownloadLocator] the pump is already
 * holding. Adopting a SEALED row's pages happens at the Download TAP instead,
 * far from the pump, so the store asks for them through a seam. Everything
 * absent is absent: a blank title falls back to the canonical item id, and
 * empty metadata commits exactly as a pre-A32 locator would.
 */
data class DownloadCommitFacts(
    val title: String? = null,
    val metadata: DownloadCommitMetadata = DownloadCommitMetadata(),
)
