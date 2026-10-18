package com.easemic.app.feature.library

/** One serialized chapter as projected for detail, Activity, and the shelf. */
data class ChapterShelfRow(
    val itemId: Long,
    val readingUnitId: Long,
    val sourceChapterId: String,
    val label: String,
    val sourceGroup: String?,
    val ordinal: Int,
    val progressOrdinal: Int,
    val completed: Boolean,
    val lastReadAtMillis: Long,
    val pageCount: Int,
    val sourceState: String,
    val sourceUpdatedAtMillis: Long?,
)

/** One serialized series as projected for a shelf card and its read action. */
data class SeriesShelfRow(
    val itemId: Long,
    val title: String,
    val seriesCoverLocator: String?,
    val chapterCount: Int,
    val readableChapterCount: Int,
    val completedChapterCount: Int,
    val selectedReadingUnitId: Long,
    val selectedChapterOrdinal: Int,
    val selectedProgressOrdinal: Int,
    val selectedPageCount: Int,
    val sourceRemovalCount: Int,
)
