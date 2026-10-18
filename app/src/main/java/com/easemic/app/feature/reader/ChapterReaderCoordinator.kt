package com.easemic.app.feature.reader

/** One serialized chapter's facts needed by the reader route coordinator. */
data class ReaderChapter(
    val chapterId: String,
    val groupOrder: Int,
    val readingOrderInGroup: Int,
    val canRead: Boolean,
    val progressOrdinal: Int,
    val completed: Boolean,
    val lastReadAtMillis: Long,
)

data class ReaderOpenTarget(val chapterId: String, val initialOrdinal: Int)

/** Request value used when a chapter transition should open on its final page. */
const val LAST_PAGE: Int = Int.MAX_VALUE

/** Clamps an opening ordinal before any reader adds transition-card slots. */
internal fun clampReaderOrdinal(initialOrdinal: Int, pageCount: Int): Int =
    initialOrdinal.coerceIn(0, (pageCount - 1).coerceAtLeast(0))

/** Maps a real page ordinal into the pager slot that includes optional cards. */
internal fun readerInitialPagerPage(
    initialOrdinal: Int,
    pageCount: Int,
    leadingSlotCount: Int,
): Int {
    val safePageCount = pageCount.coerceAtLeast(1)
    val pageSlotCount = safePageCount + if (leadingSlotCount > 0) 2 else 0
    return (clampReaderOrdinal(initialOrdinal, safePageCount) + leadingSlotCount)
        .coerceIn(0, pageSlotCount - 1)
}

sealed interface ReaderBoundary {
    data class OpenNext(val chapterId: String) : ReaderBoundary
    data class OpenPrevious(val chapterId: String) : ReaderBoundary
    data object NoAdjacentChapter : ReaderBoundary
}

/** Pure selection and same-group boundary logic for serialized chapter reads. */
class ChapterReaderCoordinator(private val chapters: List<ReaderChapter>) {
    fun readButtonTarget(): ReaderOpenTarget {
        val readable = chapters.filter { it.canRead }
        if (readable.isEmpty()) return ReaderOpenTarget(chapterId = "", initialOrdinal = 0)

        val mostRecentlyRead = readable
            .filter {
                it.lastReadAtMillis != 0L || it.progressOrdinal != 0 || it.completed
            }
            .maxWithOrNull(compareBy<ReaderChapter> { it.lastReadAtMillis }
                .thenBy { it.groupOrder }
                .thenBy { it.readingOrderInGroup })

        if (mostRecentlyRead == null) {
            return readable
                .sortedWith(compareBy<ReaderChapter> { it.groupOrder }
                    .thenBy { it.readingOrderInGroup })
                .first()
                .toOpenTarget(initialOrdinal = 0)
        }

        if (!mostRecentlyRead.completed) {
            return mostRecentlyRead.toOpenTarget(
                initialOrdinal = mostRecentlyRead.progressOrdinal,
            )
        }

        val next = readable
            .asSequence()
            .filter {
                it.groupOrder == mostRecentlyRead.groupOrder &&
                    !it.completed &&
                    it.readingOrderInGroup > mostRecentlyRead.readingOrderInGroup
            }
            .minWithOrNull(compareBy<ReaderChapter> { it.readingOrderInGroup })
        // A completed chapter at the end of its group remains the read-button
        // target at its saved terminal page. The reader can then show the
        // latest-chapter boundary without crossing into another group.
        return (next ?: mostRecentlyRead)
            .toOpenTarget(initialOrdinal = if (next == null) {
                mostRecentlyRead.progressOrdinal
            } else {
                0
            })
    }

    fun atEnd(chapterId: String): ReaderBoundary {
        val current = chapters.firstOrNull { it.chapterId == chapterId && it.canRead }
            ?: return ReaderBoundary.NoAdjacentChapter
        val next = chapters
            .asSequence()
            .filter {
                it.canRead &&
                    it.groupOrder == current.groupOrder &&
                    it.readingOrderInGroup > current.readingOrderInGroup
            }
            .minWithOrNull(compareBy<ReaderChapter> { it.readingOrderInGroup })
        return next?.let { ReaderBoundary.OpenNext(it.chapterId) }
            ?: ReaderBoundary.NoAdjacentChapter
    }

    fun atStart(chapterId: String): ReaderBoundary {
        val current = chapters.firstOrNull { it.chapterId == chapterId && it.canRead }
            ?: return ReaderBoundary.NoAdjacentChapter
        val previous = chapters
            .asSequence()
            .filter {
                it.canRead &&
                    it.groupOrder == current.groupOrder &&
                    it.readingOrderInGroup < current.readingOrderInGroup
            }
            .maxWithOrNull(compareBy<ReaderChapter> { it.readingOrderInGroup })
        return previous?.let { ReaderBoundary.OpenPrevious(it.chapterId) }
            ?: ReaderBoundary.NoAdjacentChapter
    }

    private fun ReaderChapter.toOpenTarget(initialOrdinal: Int): ReaderOpenTarget =
        ReaderOpenTarget(
            chapterId = chapterId,
            initialOrdinal = initialOrdinal,
        )
}
