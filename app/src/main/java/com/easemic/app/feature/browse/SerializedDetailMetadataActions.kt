package com.easemic.app.feature.browse

/** Nullable actions shared by the serialized title and metadata chips. */
data class SerializedDetailMetadataActions(
    val onTag: ((String) -> Unit)?,
    val onAuthor: ((String) -> Unit)? = null,
)

internal fun serializedDetailMetadataActions(
    providerId: String?,
    tags: List<String>,
    authors: List<String>,
    optionsFor: () -> List<BrowseSourceOption>,
    genresFor: () -> List<ZGenre>,
    apply: (TagSearchTarget) -> Unit,
): SerializedDetailMetadataActions {
    fun target(kind: DetailSearchKind, text: String) = tagSearchTarget(
        providerId, kind, text, optionsFor(), genresFor(),
    )
    fun action(kind: DetailSearchKind, labels: List<String>): ((String) -> Unit)? {
        if (labels.none { target(kind, it) != null }) return null
        // Read live availability/taxonomy again before changing navigation state.
        return { raw -> target(kind, raw)?.let(apply) }
    }
    return SerializedDetailMetadataActions(
        onTag = action(DetailSearchKind.TAG, tags),
        onAuthor = action(DetailSearchKind.AUTHOR, authors),
    )
}
