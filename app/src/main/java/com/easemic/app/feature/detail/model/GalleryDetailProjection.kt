package com.easemic.app.feature.detail.model

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.source.DetailField
import java.time.Instant
import java.time.ZoneId

enum class DetailObservation { SEED, COMPLETE }
data class DetailAdapterContext(
    val profile: SourceDetailProfile,
    val sourceName: String,
    val covers: DetailCover = DetailCover(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val observation: DetailObservation = DetailObservation.COMPLETE,
)
fun <T> observedDetailField(value: T?, observation: DetailObservation): DetailField<T> =
    if (observation == DetailObservation.SEED && (value == null || value is Collection<*> && value.isEmpty() || value is String && value.isBlank())) DetailField.Omitted
    else DetailField.Value(value)

fun galleryDetail(item: BrowseFeedItem, context: DetailAdapterContext, feedAuthor: Boolean = false): ComicDetail {
    val observation = context.observation
    val sourceCover = observedDetailField<DetailCoverRef>(item.coverUrl?.takeIf(String::isNotBlank)?.let { DetailCoverRef.Uri(it) }, observation)
    val people = if (feedAuthor) item.author?.trim()?.takeIf(String::isNotEmpty)?.let {
        listOf(DetailPerson(it, "author", it))
    }.orEmpty() else galleryDetailPeople(item.artists, item.groups, item.tags)
    val projectedTags = projectDetailTags(context.profile.tagLayout, item.tags)
    val tagField: DetailField<DetailTags> = if (observation == DetailObservation.SEED && detailTagCount(projectedTags) == 0) DetailField.Omitted else DetailField.Value(projectedTags)
    return ComicDetail(
        title = observedDetailField(item.title, observation),
        cover = DetailField.Value(context.covers.over(DetailCover(source = sourceCover))),
        people = observedDetailField(people, observation), sourceName = DetailField.Value(context.sourceName),
        kind = observedDetailField(item.category?.takeIf(String::isNotBlank), observation),
        count = observedDetailField<DetailCount>(
            item.pageCount?.takeIf { it >= 0 }?.let {
                DetailCount.Pages(total = it, trimmedAdPages = item.trimmedAdPages?.takeIf { trimmed -> trimmed > 0 })
            },
            observation,
        ),
        language = observedDetailField(item.language?.takeIf(String::isNotBlank), observation),
        tags = tagField, contentKind = DetailField.Value(DetailContentKind.PAGES),
    )
}
fun galleryDate(item: BrowseFeedItem, context: DetailAdapterContext): DetailField<DetailDate> = observedDetailField(
    item.uploadDateEpochSeconds?.let { DetailDate(DetailDateKind.PUBLISHED, Instant.ofEpochSecond(it).atZone(context.zone).toLocalDate()) },
    context.observation,
)
