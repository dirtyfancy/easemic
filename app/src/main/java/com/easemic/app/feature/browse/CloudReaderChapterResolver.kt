package com.easemic.app.feature.browse

import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.reader.ChapterReaderCoordinator
import com.easemic.app.feature.reader.chapterTransitionCopy
import com.easemic.app.feature.reader.ReaderBoundary
import com.easemic.app.feature.reader.ReaderChapter
import com.easemic.app.feature.reader.ReaderChapterBoundary
import com.easemic.app.feature.reader.ReaderOpenTarget
import com.easemic.app.feature.reader.LAST_PAGE
import com.easemic.app.feature.reader.clampReaderOrdinal
import com.easemic.app.sync.CloudSeriesChapterDescriptor

/** The cloud facts needed to open one serialized chapter without re-deriving its prefix. */
data class CloudReaderChapter(
    val series: SeriesIdentityV2,
    val providerChapterId: String,
    val chapterKey: String,
    val label: String,
    val group: String?,
    val groupOrder: Int,
    val readingOrderInGroup: Int,
    val pageCount: Int,
    val progressOrdinal: Int = 0,
    val completed: Boolean = false,
    val lastReadAtMillis: Long = 0L,
    val readable: Boolean = pageCount > 0,
)

data class CloudReaderChapterTarget(
    val chapter: CloudReaderChapter,
    val initialOrdinal: Int,
)

/** Pure same-group chapter navigation for the format-2 cloud reader. */
class CloudReaderChapterResolver(
    private val chapters: List<CloudReaderChapter>,
) {
    private val byId = chapters.associateBy { it.providerChapterId }
    private val coordinator = ChapterReaderCoordinator(
        chapters.map {
            ReaderChapter(
                chapterId = it.providerChapterId,
                groupOrder = it.groupOrder,
                readingOrderInGroup = it.readingOrderInGroup,
                canRead = it.readable,
                progressOrdinal = it.progressOrdinal,
                completed = it.completed,
                lastReadAtMillis = it.lastReadAtMillis,
            )
        },
    )

    fun open(chapterId: String): CloudReaderChapterTarget? =
        byId[chapterId]?.takeIf { it.readable }?.let {
            CloudReaderChapterTarget(it, clampReaderOrdinal(it.progressOrdinal, it.pageCount))
        }

    fun readButtonTarget(): CloudReaderChapterTarget? {
        val target = coordinator.readButtonTarget().takeIf { it.chapterId.isNotBlank() } ?: return null
        return toTarget(target)
    }

    fun boundary(chapterId: String, boundary: ReaderChapterBoundary): CloudReaderChapterTarget? {
        val result = when (boundary) {
            ReaderChapterBoundary.END -> coordinator.atEnd(chapterId)
            ReaderChapterBoundary.START -> coordinator.atStart(chapterId)
        }
        val target = when (result) {
            is ReaderBoundary.OpenNext -> ReaderOpenTarget(result.chapterId, 0)
            is ReaderBoundary.OpenPrevious -> ReaderOpenTarget(result.chapterId, LAST_PAGE)
            ReaderBoundary.NoAdjacentChapter -> return null
        }
        return toTarget(target)
    }

    fun transition(chapterId: String): CloudReaderTransitionPresentation? {
        val current = byId[chapterId] ?: return null
        val next = boundary(chapterId, ReaderChapterBoundary.END)
        val previous = boundary(chapterId, ReaderChapterBoundary.START)
        return CloudReaderTransitionPresentation(
            startText = chapterTransitionCopy(current.label, previous?.chapter?.label, forward = false),
            endText = chapterTransitionCopy(current.label, next?.chapter?.label, forward = true),
            hasPrevious = previous != null,
            hasNext = next != null,
        )
    }

    private fun toTarget(target: ReaderOpenTarget): CloudReaderChapterTarget? =
        byId[target.chapterId]?.let {
            CloudReaderChapterTarget(it, clampReaderOrdinal(target.initialOrdinal, it.pageCount))
        }

    companion object {
        fun fromManifest(
            manifest: com.easemic.app.sync.CloudSeriesManifest,
            progress: (SeriesIdentityV2, String) -> RemoteReadingProgress?,
        ): CloudReaderChapterResolver {
            val series = SeriesIdentityV2(
                providerNamespace = manifest.providerId,
                canonicalSeriesId = manifest.providerSeriesId ?: manifest.seriesAcquisitionRef,
            )
            val groupOrders = manifest.chapters.map { it.group }.distinct().withIndex()
                .associate { (index, group) -> group to index }
            return CloudReaderChapterResolver(
                manifest.chapters.map { descriptor ->
                    val saved = progress(series, descriptor.providerChapterId)
                    CloudReaderChapter(
                        series = series,
                        providerChapterId = descriptor.providerChapterId,
                        chapterKey = descriptor.chapterKey,
                        label = descriptor.label,
                        group = descriptor.group,
                        groupOrder = groupOrders.getValue(descriptor.group),
                        readingOrderInGroup = descriptor.readingOrder,
                        pageCount = descriptor.pageCount ?: 0,
                        progressOrdinal = saved?.ordinal ?: 0,
                        completed = saved?.let { it.pageCount > 0 && it.ordinal >= it.pageCount - 1 } == true,
                        lastReadAtMillis = saved?.updatedAtMillis ?: 0L,
                        // A removed-at-source chapter remains readable from its
                        // durable cloud bytes; sourceState only controls the
                        // badge, never whether saved media can be opened.
                        readable = (descriptor.pageCount ?: 0) > 0,
                    )
                },
            )
        }

        fun fromDescriptors(
            series: SeriesIdentityV2,
            descriptors: List<CloudSeriesChapterDescriptor>,
            progress: (SeriesIdentityV2, String) -> RemoteReadingProgress?,
        ): CloudReaderChapterResolver = fromManifest(
            com.easemic.app.sync.CloudSeriesManifest(
                seriesCloudId = "resolver",
                providerId = series.providerNamespace,
                providerSeriesId = series.canonicalSeriesId,
                seriesAcquisitionRef = "resolver",
                title = "resolver",
                coverObjectKey = null,
                generation = 0,
                chapters = descriptors,
            ),
            progress,
        )
    }
}
