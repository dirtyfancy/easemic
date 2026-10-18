package com.easemic.app.feature.detail.model

import com.easemic.app.feature.browse.DetailSearchKind
import com.easemic.app.feature.browse.TagSearchTarget
import com.easemic.app.feature.browse.ZGenre

enum class DetailTagField { GENRES, TAGS }
sealed interface DetailTagLayout {
    data class Namespaced(
        val order: List<String> = emptyList(),
        val labels: Map<String, String> = emptyMap(),
        val bylineNamespaces: Set<String> = setOf("artist", "group"),
        val excludedNamespaces: Set<String> = emptySet(),
        val includeUnlisted: Boolean = true,
    ) : DetailTagLayout
    data class Flat(val fields: List<DetailTagField> = listOf(DetailTagField.GENRES, DetailTagField.TAGS)) : DetailTagLayout
}
interface SourceDetailProfile {
    val contentKind: DetailContentKind
    val tagLayout: DetailTagLayout
    val publishedLabel: String
    fun searchFor(value: String, kind: DetailSearchKind, sourceId: String, genres: List<ZGenre>): TagSearchTarget?
}
object DefaultSourceDetailProfile : SourceDetailProfile {
    override val contentKind = DetailContentKind.PAGES
    override val tagLayout = DetailTagLayout.Flat()
    override val publishedLabel = "Published"
    override fun searchFor(value: String, kind: DetailSearchKind, sourceId: String, genres: List<ZGenre>): TagSearchTarget? = null
}
