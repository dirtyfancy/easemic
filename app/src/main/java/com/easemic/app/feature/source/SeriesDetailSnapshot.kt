package com.easemic.app.feature.source

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Durable observation of a serialized series response. */
data class SeriesDetailSnapshot(
    val metadata: SeriesDetailMetadata,
    val status: SeriesPublicationStatus,
    val sourceChapterCount: Int?,
    val completeness: ChapterListCompleteness,
    val observedAtMillis: Long,
)

fun snapshotFromListMetadata(metadata: SeriesDetailMetadata, observedAtMillis: Long = 0L): SeriesDetailSnapshot =
    SeriesDetailSnapshot(
        metadata = metadata,
        status = SeriesPublicationStatus.UNKNOWN,
        sourceChapterCount = null,
        completeness = ChapterListCompleteness.UNKNOWN,
        observedAtMillis = observedAtMillis,
    )

fun preferNewerSeriesSnapshot(
    first: SeriesDetailSnapshot?,
    second: SeriesDetailSnapshot?,
): SeriesDetailSnapshot? = when {
    first == null -> second
    second == null -> first
    second.observedAtMillis > first.observedAtMillis -> second.copy(metadata = second.metadata.over(first.metadata))
    else -> first.copy(metadata = first.metadata.over(second.metadata))
}

/**
 * Content identity for write-back diffs (F3, spec §3.3): two observations of
 * the SAME series text are "unchanged" even when re-fetched at different
 * times, so an unchanged series costs no bucket request. [SeriesDetailSnapshot.observedAtMillis]
 * is deliberately not compared.
 */
fun SeriesDetailSnapshot.sameSeriesText(other: SeriesDetailSnapshot?): Boolean {
    if (other == null) return false
    return metadata == other.metadata &&
        status == other.status &&
        sourceChapterCount == other.sourceChapterCount &&
        completeness == other.completeness
}

fun mergeSeriesSnapshot(old: SeriesDetailSnapshot?, result: ChapterListResult): SeriesDetailSnapshot {
    if (old != null && result.fetchedAtMillis < old.observedAtMillis) return old
    val exact = result.completeness == ChapterListCompleteness.COMPLETE ||
        result.completeness == ChapterListCompleteness.EMPTY_CONFIRMED
    return SeriesDetailSnapshot(
        metadata = result.metadata.over(old?.metadata ?: SeriesDetailMetadata()),
        status = if (result.statusSupplied) result.seriesStatus else old?.status ?: result.seriesStatus,
        sourceChapterCount = if (exact) result.chapters.map { it.identity.canonicalChapterId }.distinct().size else null,
        completeness = result.completeness,
        observedAtMillis = result.fetchedAtMillis,
    )
}

fun encodeSeriesSnapshot(snapshot: SeriesDetailSnapshot): String = JSONObject().apply {
    put("metadata", encodeMetadata(snapshot.metadata))
    put("status", snapshot.status.name)
    put("sourceChapterCount", snapshot.sourceChapterCount)
    put("completeness", snapshot.completeness.name)
    put("observedAtMillis", snapshot.observedAtMillis)
}.toString()

fun decodeSeriesSnapshot(json: String): SeriesDetailSnapshot {
    val root = JSONObject(json)
    return SeriesDetailSnapshot(
        metadata = decodeMetadata(root.getJSONObject("metadata")),
        status = SeriesPublicationStatus.valueOf(root.getString("status")),
        sourceChapterCount = if (root.has("sourceChapterCount") && !root.isNull("sourceChapterCount")) root.getInt("sourceChapterCount") else null,
        completeness = ChapterListCompleteness.valueOf(root.getString("completeness")),
        observedAtMillis = root.getLong("observedAtMillis"),
    )
}

private fun encodeMetadata(metadata: SeriesDetailMetadata): JSONObject = JSONObject().apply {
    putField("title", metadata.title)
    putField("cover", metadata.cover)
    putField("authors", metadata.authors)
    putField("synopsis", metadata.synopsis)
    putField("genres", metadata.genres)
    putField("tags", metadata.tags)
    putField("published", metadata.published)
    putField("updated", metadata.updated)
    putField("totalPages", metadata.totalPages)
}

private fun decodeMetadata(json: JSONObject): SeriesDetailMetadata = SeriesDetailMetadata(
    title = json.fieldString("title"),
    cover = json.fieldString("cover"),
    authors = json.fieldStrings("authors"),
    synopsis = json.fieldString("synopsis"),
    genres = json.fieldStrings("genres"),
    tags = json.fieldStrings("tags"),
    published = json.fieldDate("published"),
    updated = json.fieldDate("updated"),
    totalPages = json.fieldInt("totalPages"),
)

private fun JSONObject.putField(key: String, field: DetailField<*>) {
    if (field === DetailField.Omitted) return
    val value = (field as DetailField.Value<*>).value
    put(key, when (value) {
        null -> JSONObject.NULL
        is List<*> -> JSONArray(value)
        is SeriesDate.EpochSeconds -> JSONObject().put("kind", "epochSeconds").put("value", value.value)
        is SeriesDate.CalendarDate -> JSONObject().put("kind", "calendarDate").put("value", value.value.toString())
        else -> value
    })
}

private fun JSONObject.fieldString(key: String): DetailField<String> = when {
    !has(key) -> DetailField.Omitted
    isNull(key) -> DetailField.Value(null)
    else -> DetailField.Value(getString(key))
}

private fun JSONObject.fieldInt(key: String): DetailField<Int> = when {
    !has(key) -> DetailField.Omitted
    isNull(key) -> DetailField.Value(null)
    else -> DetailField.Value(getInt(key))
}

private fun JSONObject.fieldStrings(key: String): DetailField<List<String>> = when {
    !has(key) -> DetailField.Omitted
    isNull(key) -> DetailField.Value(null)
    else -> {
        val array = getJSONArray(key)
        DetailField.Value((0 until array.length()).map { array.getString(it) })
    }
}

private fun JSONObject.fieldDate(key: String): DetailField<SeriesDate> = when {
    !has(key) -> DetailField.Omitted
    isNull(key) -> DetailField.Value(null)
    else -> {
        val value = getJSONObject(key)
        when (value.getString("kind")) {
            "epochSeconds" -> DetailField.Value(SeriesDate.EpochSeconds(value.getLong("value")))
            "calendarDate" -> DetailField.Value(SeriesDate.CalendarDate(LocalDate.parse(value.getString("value"))))
            else -> error("unknown series date kind")
        }
    }
}
