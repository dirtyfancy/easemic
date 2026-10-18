package com.easemic.app.feature.detail.model

import com.easemic.app.feature.source.DetailField
import com.easemic.app.feature.source.over
import com.easemic.app.feature.source.valueOrNull
import com.easemic.app.model.PageRef
import java.time.LocalDate

enum class DetailContentKind { PAGES, CHAPTERS }
enum class DetailDateKind { UPDATED, PUBLISHED }
enum class DetailNumberKind { RATING, FAVOURITES }
enum class DetailCountMode { EXACT, LOADED, SAVED }
data class DetailPerson(val name: String, val role: String?, val rawSearchValue: String)
data class DetailTag(val label: String, val rawSourceValue: String)
data class DetailTagRow(val namespace: String, val label: String, val chips: List<DetailTag>)
data class DetailDate(val kind: DetailDateKind, val value: LocalDate)
data class DetailNumber(val kind: DetailNumberKind, val decimal: String)
sealed interface DetailCount {
    data class Pages(val total: Int, val trimmedAdPages: Int? = null) : DetailCount
    data class Chapters(val total: Int, val mode: DetailCountMode, val totalPages: Int? = null) : DetailCount
}
sealed interface DetailTags {
    data class Namespaced(val rows: List<DetailTagRow>) : DetailTags
    data class Flat(val chips: List<DetailTag>) : DetailTags
}
sealed interface DetailCoverRef {
    data class Uri(val value: String) : DetailCoverRef
    data class Page(val value: PageRef) : DetailCoverRef
}
data class DetailCover(
    val phone: DetailField<DetailCoverRef> = DetailField.Omitted,
    val source: DetailField<DetailCoverRef> = DetailField.Omitted,
    val cloud: DetailField<DetailCoverRef> = DetailField.Omitted,
) {
    fun selected(): DetailCoverRef? = phone.valueOrNull() ?: source.valueOrNull() ?: cloud.valueOrNull()
    fun over(old: DetailCover): DetailCover = DetailCover(phone.over(old.phone), source.over(old.source), cloud.over(old.cloud))
}
data class ComicDetail(
    val title: DetailField<String> = DetailField.Omitted,
    val cover: DetailField<DetailCover> = DetailField.Omitted,
    val people: DetailField<List<DetailPerson>> = DetailField.Omitted,
    val sourceName: DetailField<String> = DetailField.Omitted,
    val kind: DetailField<String> = DetailField.Omitted,
    val count: DetailField<DetailCount> = DetailField.Omitted,
    val language: DetailField<String> = DetailField.Omitted,
    val date: DetailField<DetailDate> = DetailField.Omitted,
    val numbers: DetailField<List<DetailNumber>> = DetailField.Omitted,
    val synopsis: DetailField<String> = DetailField.Omitted,
    val tags: DetailField<DetailTags> = DetailField.Omitted,
    val contentKind: DetailField<DetailContentKind> = DetailField.Omitted,
) {
    fun over(old: ComicDetail): ComicDetail = ComicDetail(
        title.over(old.title), mergeCover(cover, old.cover), people.over(old.people),
        sourceName.over(old.sourceName), kind.over(old.kind), count.over(old.count),
        language.over(old.language), date.over(old.date), numbers.over(old.numbers),
        synopsis.over(old.synopsis), tags.over(old.tags), contentKind.over(old.contentKind),
    )
}
private fun mergeCover(next: DetailField<DetailCover>, old: DetailField<DetailCover>): DetailField<DetailCover> {
    if (next === DetailField.Omitted) return old
    val supplied = next.valueOrNull() ?: return next
    return DetailField.Value(old.valueOrNull()?.let(supplied::over) ?: supplied)
}
