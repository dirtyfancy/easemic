package com.easemic.app.feature.reader

import com.easemic.app.feature.library.ChapterShelfRow

/** The local route target needed to open one saved serialized chapter. */
data class LocalChapterTarget(
    val readingUnitId: Long,
    val chapterId: String,
    val initialOrdinal: Int,
)

/** Text and same-group targets for the two local-reader transition cards. */
data class ReaderTransitionPresentation(
    val endText: String,
    val startText: String,
    val next: LocalChapterTarget?,
    val previous: LocalChapterTarget?,
    val chapterLabel: String? = null,
)

/** Maps the persisted series order into the coordinator's pure chapter model. */
fun List<ChapterShelfRow>.toReaderChapters(): List<ReaderChapter> {
    val ordered = sortedWith(compareBy<ChapterShelfRow> { it.ordinal }.thenBy { it.readingUnitId })
    val groupOrder = ordered
        .map { it.sourceGroup }
        .distinct()
        .withIndex()
        .associate { (index, group) -> group to index }
    return ordered.map { row ->
        ReaderChapter(
            chapterId = row.sourceChapterId,
            groupOrder = groupOrder.getValue(row.sourceGroup),
            readingOrderInGroup = row.ordinal,
            canRead = row.pageCount > 0,
            progressOrdinal = row.progressOrdinal,
            completed = row.completed,
            lastReadAtMillis = row.lastReadAtMillis,
        )
    }
}

/** Builds the local reader's exact copy and targets without crossing a source group. */
fun List<ChapterShelfRow>.localChapterTransitionPresentation(
    currentChapterId: String,
): ReaderTransitionPresentation? {
    val ordered = sortedWith(compareBy<ChapterShelfRow> { it.ordinal }.thenBy { it.readingUnitId })
    val current = ordered.firstOrNull { it.sourceChapterId == currentChapterId } ?: return null
    val byChapterId = ordered.associateBy(ChapterShelfRow::sourceChapterId)
    val coordinator = ChapterReaderCoordinator(toReaderChapters())
    val nextRow = when (val boundary = coordinator.atEnd(currentChapterId)) {
        is ReaderBoundary.OpenNext -> byChapterId[boundary.chapterId]
        ReaderBoundary.NoAdjacentChapter -> null
        is ReaderBoundary.OpenPrevious -> null
    }
    val previousRow = when (val boundary = coordinator.atStart(currentChapterId)) {
        is ReaderBoundary.OpenPrevious -> byChapterId[boundary.chapterId]
        ReaderBoundary.NoAdjacentChapter -> null
        is ReaderBoundary.OpenNext -> null
    }
    return ReaderTransitionPresentation(
        endText = chapterTransitionCopy(current.label, nextRow?.label, forward = true),
        startText = chapterTransitionCopy(current.label, previousRow?.label, forward = false),
        next = nextRow?.let {
            LocalChapterTarget(
                readingUnitId = it.readingUnitId,
                chapterId = it.sourceChapterId,
                initialOrdinal = 0,
            )
        },
        previous = previousRow?.let {
            LocalChapterTarget(
                readingUnitId = it.readingUnitId,
                chapterId = it.sourceChapterId,
                initialOrdinal = clampReaderOrdinal(LAST_PAGE, it.pageCount),
            )
        },
        chapterLabel = current.label,
    )
}
