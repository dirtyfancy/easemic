package com.easemic.app.feature.browse

import com.easemic.app.feature.reader.ChapterReaderCoordinator
import com.easemic.app.feature.reader.ReaderBoundary
import com.easemic.app.feature.reader.ReaderChapter
import com.easemic.app.feature.reader.chapterTransitionCopy
import com.easemic.app.feature.source.ChapterListResult

/** Uses source reading order and the shared coordinator; display order and progress cannot change adjacency. */
fun ChapterListResult.onlineChapterTransitionPresentation(chapterId: String): OnlineReaderTransitionPresentation? {
    val byId = chapters.associateBy { it.identity.canonicalChapterId }
    val current = byId[chapterId] ?: return null
    val coordinator = ChapterReaderCoordinator(chapters.map { chapter ->
        ReaderChapter(
            chapterId = chapter.identity.canonicalChapterId,
            groupOrder = chapter.groupOrder,
            readingOrderInGroup = chapter.readingOrderInGroup,
            canRead = chapter.canRead,
            progressOrdinal = 0,
            completed = false,
            lastReadAtMillis = 0L,
        )
    })
    val previous = (coordinator.atStart(chapterId) as? ReaderBoundary.OpenPrevious)?.chapterId?.let(byId::get)
    val next = (coordinator.atEnd(chapterId) as? ReaderBoundary.OpenNext)?.chapterId?.let(byId::get)
    return OnlineReaderTransitionPresentation(
        startText = chapterTransitionCopy(current.label, previous?.label, forward = false),
        endText = chapterTransitionCopy(current.label, next?.label, forward = true),
        hasPrevious = previous != null,
        hasNext = next != null,
    )
}
