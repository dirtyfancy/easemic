package com.easemic.app.feature.source.zaimanhua

import com.easemic.app.feature.browse.*
import com.easemic.app.feature.detail.model.*

object ZaimanhuaDetailProfile : SourceDetailProfile {
    override val contentKind = DetailContentKind.CHAPTERS
    override val tagLayout = DetailTagLayout.Flat(listOf(DetailTagField.GENRES))
    override val publishedLabel = "Published"
    override fun searchFor(value: String, kind: DetailSearchKind, sourceId: String, genres: List<ZGenre>): TagSearchTarget {
        val genre = if (kind == DetailSearchKind.TAG) genres.firstOrNull { it.id != 0L && it.label.trim() == value } else null
        return genre?.let { TagSearchTarget.Genre(it.copy(label = it.label.trim())) } ?: TagSearchTarget.Text(sourceId, value)
    }
}
