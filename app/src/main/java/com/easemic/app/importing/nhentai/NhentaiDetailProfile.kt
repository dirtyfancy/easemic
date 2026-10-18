package com.easemic.app.importing.nhentai

import com.easemic.app.feature.browse.*
import com.easemic.app.feature.detail.model.*

object NhentaiDetailProfile : SourceDetailProfile {
    override val contentKind = DetailContentKind.PAGES
    override val tagLayout = DetailTagLayout.Namespaced(
        order = listOf("parody", "character", "tag", "language"), labels = mapOf("tag" to "Tags"),
        excludedNamespaces = setOf("category"), includeUnlisted = false,
    )
    override val publishedLabel = "Uploaded"
    override fun searchFor(value: String, kind: DetailSearchKind, sourceId: String, genres: List<ZGenre>): TagSearchTarget =
        TagSearchTarget.Text(sourceId, NhentaiQueryMapper.tagQuery(value))
}
