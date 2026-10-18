package com.easemic.app.feature.source

import java.time.LocalDate

/** Presence-aware value used when a source response distinguishes omission from clearing. */
sealed interface DetailField<out T> {
    data object Omitted : DetailField<Nothing>
    data class Value<T>(val value: T?) : DetailField<T>
}

sealed interface SeriesDate {
    data class EpochSeconds(val value: Long) : SeriesDate
    data class CalendarDate(val value: LocalDate) : SeriesDate
}

data class SeriesDetailMetadata(
    val title: DetailField<String> = DetailField.Omitted,
    val cover: DetailField<String> = DetailField.Omitted,
    val authors: DetailField<List<String>> = DetailField.Omitted,
    val synopsis: DetailField<String> = DetailField.Omitted,
    val genres: DetailField<List<String>> = DetailField.Omitted,
    val tags: DetailField<List<String>> = DetailField.Omitted,
    val published: DetailField<SeriesDate> = DetailField.Omitted,
    val updated: DetailField<SeriesDate> = DetailField.Omitted,
    val totalPages: DetailField<Int> = DetailField.Omitted,
)

@Suppress("UNCHECKED_CAST")
fun <T> DetailField<T>.valueOrNull(): T? = (this as? DetailField.Value<T>)?.value

fun <T> DetailField<T>.over(old: DetailField<T>): DetailField<T> =
    if (this === DetailField.Omitted) old else this

fun SeriesDetailMetadata.over(old: SeriesDetailMetadata): SeriesDetailMetadata = SeriesDetailMetadata(
    title = title.over(old.title),
    cover = cover.over(old.cover),
    authors = authors.over(old.authors),
    synopsis = synopsis.over(old.synopsis),
    genres = genres.over(old.genres),
    tags = tags.over(old.tags),
    published = published.over(old.published),
    updated = updated.over(old.updated),
    totalPages = totalPages.over(old.totalPages),
)
