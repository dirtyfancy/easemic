package com.easemic.app.importing.ehentai

import com.easemic.app.feature.browse.*
import com.easemic.app.feature.detail.model.*

object EhentaiDetailProfile : SourceDetailProfile {
    override val contentKind = DetailContentKind.PAGES
    override val tagLayout = DetailTagLayout.Namespaced()
    override val publishedLabel = "Posted"
    override fun searchFor(value: String, kind: DetailSearchKind, sourceId: String, genres: List<ZGenre>): TagSearchTarget =
        TagSearchTarget.Text(sourceId, EhentaiQueryMapper.exactTagQuery(value))
}
