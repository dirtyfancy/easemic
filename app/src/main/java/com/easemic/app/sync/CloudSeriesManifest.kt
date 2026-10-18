package com.easemic.app.sync

import org.json.JSONArray
import org.json.JSONObject
import com.easemic.app.feature.source.DetailField
import com.easemic.app.feature.source.SeriesDetailSnapshot
import com.easemic.app.feature.source.decodeSeriesSnapshot
import com.easemic.app.feature.source.encodeSeriesSnapshot
import com.easemic.app.feature.source.valueOrNull

data class CloudSeriesChapterDescriptor(
    val providerChapterId: String,
    val chapterKey: String,
    val label: String,
    val group: String?,
    val readingOrder: Int,
    val sourceUpdatedAtEpochMs: Long?,
    val sourceState: String,
    val removedAtEpochMs: Long?,
    val pageCount: Int?,
    val coverObjectKey: String?,
)

typealias CloudSeriesChapter = CloudSeriesChapterDescriptor

data class CloudSeriesManifest(
    val seriesCloudId: String,
    val providerId: String,
    val seriesAcquisitionRef: String,
    val title: String,
    val coverObjectKey: String?,
    val author: String? = null,
    val tags: List<String> = emptyList(),
    val description: String? = null,
    val generation: Int,
    val chapters: List<CloudSeriesChapterDescriptor>,
    val version: Int = CloudFormatVersions.SERIES_MANIFEST,
    /** Canonical provider series id from the follow bookmark (never inferred from acquisitionRef). */
    val providerSeriesId: String? = null,
    /** Optional structured text/facts carried beside the chapter summary. */
    val detailSnapshot: SeriesDetailSnapshot? = null,
) {
    companion object {
        fun encode(manifest: CloudSeriesManifest): ByteArray {
            val chapters = JSONArray()
            manifest.chapters.forEach { chapter ->
                chapters.put(
                    JSONObject()
                        .put("providerChapterId", chapter.providerChapterId)
                        .put("chapterKey", chapter.chapterKey)
                        .put("label", chapter.label)
                        .put("group", chapter.group)
                        .put("readingOrder", chapter.readingOrder)
                        .put("sourceUpdatedAtEpochMs", chapter.sourceUpdatedAtEpochMs)
                        .put("sourceState", chapter.sourceState)
                        .put("removedAtEpochMs", chapter.removedAtEpochMs)
                        .put("pageCount", chapter.pageCount)
                        .put("coverObjectKey", chapter.coverObjectKey),
                )
            }
            val snapshot = manifest.detailSnapshot
            val author = when (val field = snapshot?.metadata?.authors) {
                null, DetailField.Omitted -> manifest.author
                is DetailField.Value -> field.value?.joinToString(", ")
            }
            val tags = when (val field = snapshot?.let {
                if (manifest.providerId == "zaimanhua") it.metadata.genres else it.metadata.tags
            }) {
                null, DetailField.Omitted -> manifest.tags
                is DetailField.Value -> field.value.orEmpty()
            }
            val description = when (val field = snapshot?.metadata?.synopsis) {
                null, DetailField.Omitted -> manifest.description
                is DetailField.Value -> field.value
            }
            return JSONObject()
                .put("version", CloudFormatVersions.SERIES_MANIFEST)
                .put("seriesCloudId", manifest.seriesCloudId)
                .put("providerId", manifest.providerId)
                .put("providerSeriesId", manifest.providerSeriesId)
                .put("seriesAcquisitionRef", manifest.seriesAcquisitionRef)
                .put("title", manifest.title)
                .put("coverObjectKey", manifest.coverObjectKey)
                .put("author", author)
                .put("tags", JSONArray(tags))
                .put("description", description)
                .put("detailSnapshot", snapshot?.let { JSONObject(encodeSeriesSnapshot(it)) } ?: JSONObject.NULL)
                .put("generation", manifest.generation)
                .put("chapters", chapters)
                .toString()
                .toByteArray(Charsets.UTF_8)
        }

        fun decode(bytes: ByteArray): CloudSeriesManifest {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val version = json.getInt("version")
            if (version > CloudFormatVersions.SERIES_MANIFEST) {
                throw NewerCloudFormatException("series manifest", version)
            }
            require(version == CloudFormatVersions.SERIES_MANIFEST) { "unsupported series manifest version $version" }
            val chapterArray = json.getJSONArray("chapters")
            val chapters = buildList {
                for (i in 0 until chapterArray.length()) {
                    val chapter = chapterArray.getJSONObject(i)
                    add(
                        CloudSeriesChapterDescriptor(
                            providerChapterId = chapter.getString("providerChapterId"),
                            chapterKey = chapter.getString("chapterKey"),
                            label = chapter.getString("label"),
                            group = chapter.nullableString("group"),
                            readingOrder = chapter.getInt("readingOrder"),
                            sourceUpdatedAtEpochMs = chapter.nullableLong("sourceUpdatedAtEpochMs"),
                            sourceState = chapter.getString("sourceState"),
                            removedAtEpochMs = chapter.nullableLong("removedAtEpochMs"),
                            pageCount = chapter.nullableInt("pageCount"),
                            coverObjectKey = chapter.nullableString("coverObjectKey"),
                        ),
                    )
                }
            }
            return CloudSeriesManifest(
                seriesCloudId = json.getString("seriesCloudId"),
                providerId = json.getString("providerId"),
                providerSeriesId = json.nullableString("providerSeriesId"),
                seriesAcquisitionRef = json.getString("seriesAcquisitionRef"),
                title = json.getString("title"),
                coverObjectKey = json.nullableString("coverObjectKey"),
                author = json.nullableString("author"),
                tags = json.optJSONArray("tags").toStringList(),
                description = json.nullableString("description"),
                generation = json.getInt("generation"),
                detailSnapshot = json.opt("detailSnapshot")?.takeUnless { it == JSONObject.NULL }?.let {
                    decodeSeriesSnapshot(it.toString())
                },
                chapters = chapters,
                version = version,
            )
        }

        private fun JSONObject.nullableString(key: String): String? =
            if (!has(key) || isNull(key)) null else getString(key)

        private fun JSONObject.nullableLong(key: String): Long? =
            if (!has(key) || isNull(key)) null else getLong(key)

        private fun JSONObject.nullableInt(key: String): Int? =
            if (!has(key) || isNull(key)) null else getInt(key)

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return buildList {
                for (i in 0 until length()) add(getString(i))
            }
        }
    }
}
