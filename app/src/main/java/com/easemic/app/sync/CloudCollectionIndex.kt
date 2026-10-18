package com.easemic.app.sync

import org.json.JSONArray
import org.json.JSONObject

enum class CloudCardLayout {
    SINGLE_CHAPTER,
    SERIES,
}

data class CloudCollectionCard(
    val cloudComicId: String,
    val title: String,
    val coverThumbObjectKey: String,
    val collectedAtEpochMs: Long,
    val pageCount: Int? = null,
    val language: String? = null,
    val acquisitionRef: String? = null,
    val trashedAtEpochMs: Long? = null,
    /**
     * S4 Task 1 (owner bug C4): absolute path to a durable on-device cover
     * JPEG, once ingest or hydration has written one. Deliberately NOT part
     * of [CloudCollectionIndex.encodeChunk]/[CloudCollectionIndex.decodeChunk]
     * — it names a path on THIS device, so it would be meaningless (or
     * actively wrong) on any other device reading the same remote index.
     * Local-only; lives solely in the Room mirror (see
     * `com.easemic.app.feature.collect.RoomCloudMirrorStore`).
     */
    val coverPath: String? = null,
    val layoutKind: CloudCardLayout = CloudCardLayout.SINGLE_CHAPTER,
    val chapterCount: Int = 1,
    val readableChapterCount: Int = 1,
    val seriesMetaObjectKey: String? = null,
    val seriesGeneration: Int = 0,
    /** Local mirror identity hydrated from series.json; intentionally absent from index bytes. */
    val providerId: String? = null,
    val providerSeriesId: String? = null,
)

data class CloudCollectionIndexChunk(
    val chunkIndex: Int,
    val cards: List<CloudCollectionCard>,
)

data class CloudCollectionManifest(
    val format: Int = 1,
    val generation: Int,
    val comicCount: Int,
    val chunkSize: Int = 100,
) {
    /**
     * A24: how many index chunks this manifest declares, derived from its own
     * [comicCount]/[chunkSize] rather than from the reader's configured chunk
     * size. This is the authoritative extent of a chunk scan — a chunk that is
     * missing or empty inside `0 until declaredChunkCount()` is a hole to skip,
     * not the end of the index.
     */
    fun declaredChunkCount(): Int = when {
        comicCount <= 0 || chunkSize <= 0 -> 0
        else -> (comicCount + chunkSize - 1) / chunkSize
    }
}

/**
 * A22 §4: which manifest generation an index rewrite moved the bucket
 * from/to. [previousGeneration] is null when the bucket had no manifest yet
 * (first Collect). Surfaced by `CloudCollectionStore.uploadComicArchiveWithIndex`
 * so the uploading device can stamp its local mirror instead of re-walking.
 */
data class CloudIndexGenerations(
    val previousGeneration: Int?,
    val newGeneration: Int,
    val comicCount: Int,
)

object CloudCollectionIndex {
    const val DEFAULT_CHUNK_SIZE: Int = 100

    fun encodeChunk(chunk: CloudCollectionIndexChunk): ByteArray {
        require(chunk.chunkIndex >= 0) { "chunkIndex must be >= 0" }
        val cards = JSONArray()
        chunk.cards.forEach { card ->
            val obj = JSONObject()
                .put("cloudComicId", card.cloudComicId)
                .put("title", card.title)
                .put("coverThumbObjectKey", card.coverThumbObjectKey)
                .put("collectedAtEpochMs", card.collectedAtEpochMs)
                .put("pageCount", card.pageCount)
                .put("language", card.language)
            if (!card.acquisitionRef.isNullOrBlank()) {
                obj.put("acquisitionRef", card.acquisitionRef)
            }
            if (card.trashedAtEpochMs != null) {
                obj.put("trashedAtEpochMs", card.trashedAtEpochMs)
            }
            if (card.layoutKind == CloudCardLayout.SERIES) {
                obj.put("layout", "series")
                    .put("chapterCount", card.chapterCount)
                    .put("readableChapterCount", card.readableChapterCount)
                    .put("seriesMeta", card.seriesMetaObjectKey ?: JSONObject.NULL)
                    .put("seriesGeneration", card.seriesGeneration)
            }
            cards.put(obj)
        }
        val json = JSONObject()
            .put("chunkIndex", chunk.chunkIndex)
            .put("cards", cards)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeChunk(bytes: ByteArray): CloudCollectionIndexChunk {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val arr = json.getJSONArray("cards")
        val cards = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val layout = if (!o.has("layout")) {
                    CloudCardLayout.SINGLE_CHAPTER
                } else {
                    when (val value = o.optString("layout")) {
                        "series" -> CloudCardLayout.SERIES
                        else -> throw IllegalArgumentException("unknown cloud card layout: $value")
                    }
                }
                add(
                    CloudCollectionCard(
                        cloudComicId = o.getString("cloudComicId"),
                        title = o.getString("title"),
                        coverThumbObjectKey = o.getString("coverThumbObjectKey"),
                        collectedAtEpochMs = o.getLong("collectedAtEpochMs"),
                        pageCount = if (o.has("pageCount") && !o.isNull("pageCount")) {
                            o.getInt("pageCount")
                        } else {
                            null
                        },
                        language = o.optString("language").takeIf { it.isNotBlank() },
                        acquisitionRef = o.optString("acquisitionRef").takeIf { it.isNotBlank() },
                        trashedAtEpochMs = if (o.has("trashedAtEpochMs") && !o.isNull("trashedAtEpochMs")) {
                            o.getLong("trashedAtEpochMs")
                        } else {
                            null
                        },
                        layoutKind = layout,
                        chapterCount = if (layout == CloudCardLayout.SERIES) o.optInt("chapterCount", 1) else 1,
                        readableChapterCount = if (layout == CloudCardLayout.SERIES) {
                            o.optInt("readableChapterCount", 1)
                        } else {
                            1
                        },
                        seriesMetaObjectKey = if (layout == CloudCardLayout.SERIES && o.has("seriesMeta") && !o.isNull("seriesMeta")) {
                            o.getString("seriesMeta")
                        } else {
                            null
                        },
                        seriesGeneration = if (layout == CloudCardLayout.SERIES) {
                            o.optInt("seriesGeneration", 0)
                        } else {
                            0
                        },
                    ),
                )
            }
        }
        return CloudCollectionIndexChunk(
            chunkIndex = json.getInt("chunkIndex"),
            cards = cards,
        )
    }

    fun encodeManifest(manifest: CloudCollectionManifest): ByteArray {
        require(manifest.comicCount >= 0) { "comicCount must be >= 0" }
        require(manifest.chunkSize > 0) { "chunkSize must be > 0" }
        val json = JSONObject()
            .put("format", manifest.format)
            .put("generation", manifest.generation)
            .put("comicCount", manifest.comicCount)
            .put("chunkSize", manifest.chunkSize)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeManifest(bytes: ByteArray): CloudCollectionManifest {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val format = json.optInt("format", 1)
        if (format > CloudFormatVersions.INDEX_MANIFEST) {
            throw NewerCloudFormatException("collection index manifest", format)
        }
        return CloudCollectionManifest(
            format = format,
            generation = json.getInt("generation"),
            comicCount = json.getInt("comicCount"),
            chunkSize = json.optInt("chunkSize", DEFAULT_CHUNK_SIZE),
        )
    }
}
