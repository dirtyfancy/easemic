package com.easemic.app.data.room

import com.easemic.app.model.MetadataSnapshot
import org.json.JSONArray

/**
 * Reader mirroring [RoomLibraryRepository]'s `List<String>.toJson()` writer.
 * A corrupt or legacy column degrades to an empty list — a Detail row with
 * nothing persisted must render no tag section, never a placeholder (spec §7).
 */
fun parseJsonStringList(raw: String?): List<String> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    return runCatching {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).trim().takeIf { it.isNotEmpty() }
        }
    }.getOrDefault(emptyList())
}

/** Room row → the domain snapshot the unified Detail renders from (A32 §1). */
fun MetadataSnapshotEntity.toMetadataSnapshot(): MetadataSnapshot = MetadataSnapshot(
    title = title,
    series = series,
    alternateSeries = alternateSeries,
    creators = parseJsonStringList(creators),
    genres = parseJsonStringList(genres),
    tags = parseJsonStringList(tags),
    language = language?.trim()?.takeIf { it.isNotEmpty() },
)
