package com.easemic.app.feature.source

import com.easemic.app.feature.download.ChapterIdentityV2

data class ChapterSummary(
    val identity: ChapterIdentityV2,
    val label: String,
    val group: String?,
    val groupOrder: Int,
    val readingOrderInGroup: Int,
    val sourceUpdatedAtMillis: Long?,
    val canRead: Boolean,
    val isFee: Boolean,
    val needsSignIn: Boolean = false,
)

enum class SeriesPublicationStatus { ONGOING, COMPLETED, UNKNOWN }

data class ChapterListResult(
    val chapters: List<ChapterSummary>,
    val fetchedAtMillis: Long,
    val completeness: ChapterListCompleteness,
    val seriesStatus: SeriesPublicationStatus = SeriesPublicationStatus.UNKNOWN,
    val metadata: SeriesDetailMetadata = SeriesDetailMetadata(),
    val statusSupplied: Boolean = false,
)

enum class ChapterListCompleteness {
    COMPLETE,
    EMPTY_CONFIRMED,
    PARTIAL,
    UNKNOWN,
}
