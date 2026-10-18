package com.easemic.app.data.room

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "chapter_updates",
    primaryKeys = ["providerNamespace", "providerSeriesId", "providerChapterId"],
    indices = [
        Index(
            value = ["seenAtMillis", "discoveredAtMillis"],
            name = "index_chapter_updates_seenAtMillis_discoveredAtMillis",
        ),
        Index(
            value = ["providerNamespace", "providerSeriesId"],
            name = "index_chapter_updates_providerNamespace_providerSeriesId",
        ),
    ],
)
data class ChapterUpdateEntity(
    val providerNamespace: String,
    val providerSeriesId: String,
    val providerChapterId: String,
    val seriesTitle: String,
    val chapterLabel: String,
    val groupLabel: String?,
    val sourceUpdatedAtMillis: Long?,
    val discoveredAtMillis: Long,
    val seenAtMillis: Long?,
)
