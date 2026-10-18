package com.easemic.app.feature.source.jm

import com.easemic.app.feature.browse.*
import com.easemic.app.feature.detail.model.*

object JmDetailProfile : SourceDetailProfile {
    override val contentKind = DetailContentKind.CHAPTERS
    override val tagLayout = DetailTagLayout.Flat(listOf(DetailTagField.TAGS))
    override val publishedLabel = "Published"
    override fun searchFor(value: String, kind: DetailSearchKind, sourceId: String, genres: List<ZGenre>): TagSearchTarget =
        TagSearchTarget.JmScoped(value, if (kind == DetailSearchKind.AUTHOR) JmSearchScope.AUTHOR else JmSearchScope.TAG)
}
