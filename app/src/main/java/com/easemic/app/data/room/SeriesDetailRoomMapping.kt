package com.easemic.app.data.room

import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.source.DetailField
import com.easemic.app.feature.source.SeriesDetailMetadata
import com.easemic.app.feature.source.SeriesDetailSnapshot
import com.easemic.app.feature.source.SeriesPublicationStatus
import com.easemic.app.feature.source.decodeSeriesSnapshot
import com.easemic.app.feature.source.encodeSeriesSnapshot
import com.easemic.app.feature.source.valueOrNull
import org.json.JSONArray
import org.json.JSONObject

fun MetadataSnapshotEntity.toSeriesDetailSnapshot(): SeriesDetailSnapshot? {
    val decoded = seriesFactsJson?.let { runCatching { decodeSeriesSnapshot(it) }.getOrNull() }
    val metadata = (decoded?.metadata ?: SeriesDetailMetadata()).let { known ->
        known.copy(
            title = known.title.takeIf { it !== DetailField.Omitted }
                ?: DetailField.Value(title),
            cover = known.cover.takeIf { it !== DetailField.Omitted }
                ?: coverLocator?.let { DetailField.Value(it) }
                ?: DetailField.Omitted,
            authors = known.authors.takeIf { it !== DetailField.Omitted }
                ?: DetailField.Value(parseJsonStringList(creators)),
            genres = known.genres.takeIf { it !== DetailField.Omitted }
                ?: DetailField.Value(parseJsonStringList(genres)),
            tags = known.tags.takeIf { it !== DetailField.Omitted }
                ?: DetailField.Value(parseJsonStringList(tags)),
            synopsis = when {
                known.synopsis !== DetailField.Omitted -> known.synopsis
                synopsis != null -> DetailField.Value(synopsis)
                else -> DetailField.Omitted
            },
        )
    }
    return decoded?.copy(metadata = metadata) ?: SeriesDetailSnapshot(
        metadata = metadata,
        status = SeriesPublicationStatus.UNKNOWN,
        sourceChapterCount = null,
        completeness = com.easemic.app.feature.source.ChapterListCompleteness.UNKNOWN,
        observedAtMillis = createdAtMillis,
    )
}

fun SeriesDetailSnapshot.toMetadataEntity(
    itemId: Long,
    old: MetadataSnapshotEntity?,
    fallbackTitle: String,
    fallbackCover: String?,
    now: Long,
): MetadataSnapshotEntity {
    val metadata = metadata
    val title = metadata.title.valueOrNull()?.takeIf { it.isNotBlank() }
        ?: old?.title?.takeIf { it.isNotBlank() }
        ?: fallbackTitle
    fun retained(field: DetailField<List<String>>, previous: String?): List<String> = when (field) {
        DetailField.Omitted -> parseJsonStringList(previous)
        is DetailField.Value -> field.value.orEmpty()
    }
    val authors = retained(metadata.authors, old?.creators)
    val genres = retained(metadata.genres, old?.genres)
    val tags = retained(metadata.tags, old?.tags)
    val synopsisValue = when (val field = metadata.synopsis) {
        DetailField.Omitted -> old?.synopsis
        is DetailField.Value -> field.value
    }
    val effectiveMetadata = metadata.copy(
        title = if (metadata.title === DetailField.Omitted) DetailField.Value(title) else metadata.title,
        cover = if (metadata.cover === DetailField.Omitted) {
            (old?.coverLocator ?: fallbackCover)?.let { DetailField.Value(it) } ?: DetailField.Omitted
        } else metadata.cover,
        authors = if (metadata.authors === DetailField.Omitted) DetailField.Value(authors) else metadata.authors,
        genres = if (metadata.genres === DetailField.Omitted) DetailField.Value(genres) else metadata.genres,
        tags = if (metadata.tags === DetailField.Omitted) DetailField.Value(tags) else metadata.tags,
        synopsis = if (metadata.synopsis === DetailField.Omitted) {
            synopsisValue?.let { DetailField.Value(it) } ?: DetailField.Omitted
        } else metadata.synopsis,
    )
    val effectiveSnapshot = copy(metadata = effectiveMetadata)
    return MetadataSnapshotEntity(
        itemId = itemId,
        title = title,
        series = old?.series ?: title,
        alternateSeries = old?.alternateSeries,
        creators = JSONArray(authors).toString(),
        genres = JSONArray(genres).toString(),
        tags = JSONArray(tags).toString(),
        language = old?.language,
        expectedPageCount = metadata.totalPages.valueOrNull() ?: old?.expectedPageCount,
        createdAtMillis = now,
        coverLocator = when (val field = metadata.cover) {
            DetailField.Omitted -> old?.coverLocator ?: fallbackCover
            is DetailField.Value -> field.value
        },
        synopsis = synopsisValue,
        seriesFactsJson = encodeSeriesSnapshot(effectiveSnapshot),
    )
}

data class PendingSeriesDetailUpload(
    val itemId: Long,
    val series: SeriesIdentityV2,
    val snapshot: SeriesDetailSnapshot,
)

fun encodeRoomSeriesFacts(snapshot: SeriesDetailSnapshot): String = encodeSeriesSnapshot(snapshot)

fun decodeRoomSeriesFacts(raw: String?): SeriesDetailSnapshot? = raw?.let { runCatching { decodeSeriesSnapshot(it) }.getOrNull() }
