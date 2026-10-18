package com.easemic.app.feature.source

import com.easemic.app.feature.detail.model.*

data class SeriesCountInput(
    val loadedChapterIds: Set<String> = emptySet(),
    val savedChapterIds: Set<String> = emptySet(),
    val sourceUnavailable: Boolean = false,
)
private fun <T, R> DetailField<T>.mapped(transform: (T) -> R): DetailField<R> = when (this) {
    DetailField.Omitted -> DetailField.Omitted
    is DetailField.Value -> DetailField.Value(value?.let(transform))
}
fun seriesComicDetail(
    snapshot: SeriesDetailSnapshot?,
    seed: SeriesDetailMetadata,
    context: DetailAdapterContext,
    counts: SeriesCountInput,
): ComicDetail {
    val metadata = (snapshot?.metadata ?: SeriesDetailMetadata()).over(seed)
    val mode = when {
        counts.sourceUnavailable -> DetailCountMode.SAVED
        snapshot?.completeness in setOf(ChapterListCompleteness.COMPLETE, ChapterListCompleteness.EMPTY_CONFIRMED) && snapshot?.sourceChapterCount != null -> DetailCountMode.EXACT
        snapshot?.completeness == ChapterListCompleteness.PARTIAL -> DetailCountMode.LOADED
        else -> DetailCountMode.SAVED
    }
    val total = when (mode) {
        DetailCountMode.EXACT -> checkNotNull(snapshot?.sourceChapterCount)
        DetailCountMode.LOADED -> counts.loadedChapterIds.size
        DetailCountMode.SAVED -> counts.savedChapterIds.size
    }
    val layout = context.profile.tagLayout
    val selectedFields = when (layout) {
        is DetailTagLayout.Flat -> layout.fields.map { field ->
            when (field) { DetailTagField.GENRES -> metadata.genres; DetailTagField.TAGS -> metadata.tags }
        }
        is DetailTagLayout.Namespaced -> listOf(metadata.tags)
    }
    val tags: DetailField<DetailTags> = if (selectedFields.all { it === DetailField.Omitted }) DetailField.Omitted else
        DetailField.Value(projectDetailTags(layout, selectedFields.flatMap { it.valueOrNull().orEmpty() }))
    val updated = metadata.updated.valueOrNull()
    val published = metadata.published.valueOrNull()
    val date: DetailField<DetailDate> = when {
        updated != null -> DetailField.Value(DetailDate(DetailDateKind.UPDATED, DetailFactsFormatter.localDate(updated, context.zone)))
        published != null -> DetailField.Value(DetailDate(DetailDateKind.PUBLISHED, DetailFactsFormatter.localDate(published, context.zone)))
        metadata.updated === DetailField.Omitted && metadata.published === DetailField.Omitted -> DetailField.Omitted
        else -> DetailField.Value(null)
    }
    val kind: DetailField<String> = if (snapshot == null) DetailField.Omitted else DetailField.Value(when (snapshot.status) {
        SeriesPublicationStatus.ONGOING -> "Ongoing"
        SeriesPublicationStatus.COMPLETED -> "Completed"
        SeriesPublicationStatus.UNKNOWN -> null
    })
    return ComicDetail(
        title = metadata.title,
        cover = DetailField.Value(context.covers.over(DetailCover(source = metadata.cover.mapped { DetailCoverRef.Uri(it) }))),
        people = metadata.authors.mapped { names -> names.map(String::trim).filter(String::isNotEmpty).distinct().map { DetailPerson(it, "author", it) } },
        sourceName = DetailField.Value(context.sourceName), kind = kind,
        count = if (snapshot == null && !counts.sourceUnavailable && counts.savedChapterIds.isEmpty()) DetailField.Omitted else
            DetailField.Value(DetailCount.Chapters(total, mode, metadata.totalPages.valueOrNull())),
        date = date, synopsis = metadata.synopsis, tags = tags,
        contentKind = DetailField.Value(DetailContentKind.CHAPTERS),
    )
}
