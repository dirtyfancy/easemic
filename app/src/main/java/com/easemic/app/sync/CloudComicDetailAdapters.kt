package com.easemic.app.sync

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.detail.model.*
import com.easemic.app.feature.source.*

fun cloudGalleryComicDetail(meta: CloudComicMeta, context: DetailAdapterContext): ComicDetail = galleryDetail(
    BrowseFeedItem(id = meta.cloudComicId, title = meta.title, providerId = meta.providerId,
        pageCount = meta.pageCount, trimmedAdPages = meta.trimmedAdPages, artists = meta.artists, groups = meta.groups,
        category = meta.category, language = meta.language, tags = meta.tags),
    context.copy(observation = DetailObservation.COMPLETE),
).copy(cover = DetailField.Value(context.covers))

fun cloudSeriesComicDetail(
    manifest: CloudSeriesManifest?,
    mirrorSnapshot: SeriesDetailSnapshot?,
    seed: SeriesDetailMetadata,
    context: DetailAdapterContext,
    savedChapterIds: Set<String>,
): ComicDetail {
    val flat = if (manifest == null) SeriesDetailMetadata() else {
        val fields = (context.profile.tagLayout as? DetailTagLayout.Flat)?.fields.orEmpty()
        SeriesDetailMetadata(
            title = DetailField.Value(manifest.title),
            authors = manifest.author?.let { DetailField.Value(listOf(it)) } ?: DetailField.Omitted,
            synopsis = manifest.description?.let { DetailField.Value(it) } ?: DetailField.Omitted,
            genres = if (DetailTagField.GENRES in fields) DetailField.Value(manifest.tags) else DetailField.Omitted,
            tags = if (DetailTagField.GENRES !in fields) DetailField.Value(manifest.tags) else DetailField.Omitted,
        )
    }
    val snapshot = preferNewerSeriesSnapshot(manifest?.detailSnapshot, mirrorSnapshot)
    return seriesComicDetail(snapshot, flat.over(seed), context,
        SeriesCountInput(savedChapterIds = savedChapterIds, sourceUnavailable = true))
}
