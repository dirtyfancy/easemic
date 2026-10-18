package com.easemic.app.sync

import org.json.JSONObject

data class CloudChapterMeta(
    val seriesCloudId: String,
    val providerId: String,
    val seriesAcquisitionRef: String,
    val providerChapterId: String,
    val chapterKey: String,
    val label: String,
    val group: String?,
    val readingOrder: Int,
    val sourceState: String,
    val removedAtEpochMs: Long?,
    val sourceDiagnostic: String?,
    val pageCount: Int?,
    val coverObjectKey: String?,
    val collectedAtEpochMs: Long,
    val version: Int = CloudFormatVersions.CHAPTER_META,
    /** Canonical provider series id from the follow bookmark. */
    val providerSeriesId: String? = null,
) {
    companion object {
        fun encode(meta: CloudChapterMeta): ByteArray = JSONObject()
            .put("version", CloudFormatVersions.CHAPTER_META)
            .put("seriesCloudId", meta.seriesCloudId)
            .put("providerId", meta.providerId)
            .put("providerSeriesId", meta.providerSeriesId)
            .put("seriesAcquisitionRef", meta.seriesAcquisitionRef)
            .put("providerChapterId", meta.providerChapterId)
            .put("chapterKey", meta.chapterKey)
            .put("label", meta.label)
            .put("group", meta.group)
            .put("readingOrder", meta.readingOrder)
            .put("sourceState", meta.sourceState)
            .put("removedAtEpochMs", meta.removedAtEpochMs)
            .put("sourceDiagnostic", meta.sourceDiagnostic)
            .put("pageCount", meta.pageCount)
            .put("coverObjectKey", meta.coverObjectKey)
            .put("collectedAtEpochMs", meta.collectedAtEpochMs)
            .toString()
            .toByteArray(Charsets.UTF_8)

        fun decode(bytes: ByteArray): CloudChapterMeta {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val version = json.getInt("version")
            if (version > CloudFormatVersions.CHAPTER_META) {
                throw NewerCloudFormatException("chapter meta", version)
            }
            require(version == CloudFormatVersions.CHAPTER_META) { "unsupported chapter meta version $version" }
            return CloudChapterMeta(
                seriesCloudId = json.getString("seriesCloudId"),
                providerId = json.getString("providerId"),
                providerSeriesId = json.nullableString("providerSeriesId"),
                seriesAcquisitionRef = json.getString("seriesAcquisitionRef"),
                providerChapterId = json.getString("providerChapterId"),
                chapterKey = json.getString("chapterKey"),
                label = json.getString("label"),
                group = json.nullableString("group"),
                readingOrder = json.getInt("readingOrder"),
                sourceState = json.getString("sourceState"),
                removedAtEpochMs = json.nullableLong("removedAtEpochMs"),
                sourceDiagnostic = json.nullableString("sourceDiagnostic"),
                pageCount = json.nullableInt("pageCount"),
                coverObjectKey = json.nullableString("coverObjectKey"),
                collectedAtEpochMs = json.getLong("collectedAtEpochMs"),
                version = version,
            )
        }

        private fun JSONObject.nullableString(key: String): String? =
            if (!has(key) || isNull(key)) null else getString(key)

        private fun JSONObject.nullableLong(key: String): Long? =
            if (!has(key) || isNull(key)) null else getLong(key)

        private fun JSONObject.nullableInt(key: String): Int? =
            if (!has(key) || isNull(key)) null else getInt(key)
    }

    fun toSeriesDescriptor(): CloudSeriesChapterDescriptor = CloudSeriesChapterDescriptor(
        providerChapterId = providerChapterId,
        chapterKey = chapterKey,
        label = label,
        group = group,
        readingOrder = readingOrder,
        sourceUpdatedAtEpochMs = null,
        sourceState = sourceState,
        removedAtEpochMs = removedAtEpochMs,
        pageCount = pageCount,
        coverObjectKey = coverObjectKey,
    )
}
