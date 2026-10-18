package com.easemic.app.feature.settings.backup

import androidx.room.withTransaction
import com.easemic.app.data.room.RoomLibraryRepository
import com.easemic.app.data.room.toSeriesDetailSnapshot
import com.easemic.app.feature.download.DownloadCommitCompleteness
import com.easemic.app.feature.download.DownloadLocator
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.RoomDownloadJobStore
import com.easemic.app.feature.download.asSeriesIdentityV2
import com.easemic.app.feature.download.putKeepingCatalogFacts
import com.easemic.app.feature.download.serializedChapterLocatorKey
import com.easemic.app.feature.download.serializedChapterStorageIdentity
import com.easemic.app.feature.download.deleteCatalogDownloadRevision
import com.easemic.app.feature.source.ChapterListCompleteness
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.ChapterSummary
import com.easemic.app.model.LibraryItemId
import com.easemic.app.model.ReadingUnitId
import com.easemic.app.sync.archive.ArchiveChapter
import com.easemic.app.sync.archive.ArchiveSeries
import com.easemic.app.sync.archive.ArchiveWriter
import java.io.File
import java.net.URI
import java.util.UUID

/** Builds and restores the serialized-series half of a device archive. */
class SerializedSeriesArchive(
    private val database: com.easemic.app.data.room.AppDatabase,
    private val repository: RoomLibraryRepository,
    private val downloadJobStore: RoomDownloadJobStore,
    private val locatorStore: com.easemic.app.feature.download.DownloadLocatorStore,
    private val filesDir: File,
    /** Test seam for proving a chapter-two failure rolls the outer transaction back. */
    private val beforeChapterCommit: suspend (ArchiveChapter) -> Unit = {},
) {
    /** Reads one Room relation graph per series, then selects each saved chapter from it. */
    suspend fun build(itemId: LibraryItemId): ArchiveWriter.SeriesSource? {
        val identity = repository.findSerializedSeriesIdentity(itemId) ?: return null
        val graph = repository.archiveItemGraphOnce(itemId) ?: return null
        val metadataId = graph.currentMetadata?.metadataSnapshotId ?: return null
        val metadata = graph.metadataSnapshots.singleOrNull { it.id == metadataId } ?: return null
        val chapters = graph.readingUnits.sortedWith(compareBy({ it.readingUnit.ordinal }, { it.readingUnit.id })).map { unit ->
            val revision = unit.currentRevision?.mediaRevisionId?.let { revisionId ->
                unit.revisions.singleOrNull { it.revision.id == revisionId }
            }
            val present = revision?.pages
                ?.sortedBy { it.ordinal }
                ?.mapNotNull { page ->
                    val uri = page.documentUri ?: return@mapNotNull null
                    if (!uri.startsWith("file:", ignoreCase = true)) return@mapNotNull null
                    runCatching { File(URI(uri)) }.getOrNull()?.takeIf { it.isFile && it.length() > 0L }
                }
                .orEmpty()
            val names = present.mapIndexed { ordinal, file -> "$ordinal.${extensionOf(file)}" }
            val chapter = ArchiveChapter(
                providerChapterId = unit.readingUnit.sourceChapterId,
                label = unit.readingUnit.label,
                group = unit.readingUnit.sourceGroup,
                readingOrder = unit.readingUnit.ordinal,
                sourceState = unit.readingUnit.sourceState,
                pageFiles = names,
                groupOrder = 0,
                sourceUpdatedAtMillis = unit.readingUnit.sourceUpdatedAtMillis,
                expectedPageCount = revision?.pages?.size?.takeIf { it > present.size },
                progressOrdinal = unit.readingUnit.progressOrdinal.coerceIn(0, present.lastIndex.coerceAtLeast(0)),
                completed = unit.readingUnit.completed,
                lastReadAtMillis = unit.readingUnit.lastReadAtMillis,
            )
            ArchiveWriter.ChapterSource(chapter, present)
        }
        val coverLocator = metadata.coverLocator
        val detailSnapshot = metadata.toSeriesDetailSnapshot()
        val coverFile = coverLocator
            ?.takeIf { it.startsWith("file:", ignoreCase = true) }
            ?.let { runCatching { File(URI(it)) }.getOrNull() }
            ?.takeIf { it.isFile && it.length() > 0L }
        val series = ArchiveSeries(
            providerId = identity.providerNamespace,
            providerSeriesId = identity.canonicalItemId,
            title = metadata.title,
            coverFileName = coverFile?.let { "cover.${extensionOf(it)}" },
            coverLocator = coverLocator,
            chapters = chapters.map { it.chapter },
            detailSnapshot = detailSnapshot,
        )
        return ArchiveWriter.SeriesSource(series, coverFile, chapters)
    }

    /**
     * Materializes every chapter before entering one Room transaction. A failed
     * transaction removes all materialized chapter trees and the copied cover.
     */
    suspend fun restore(
        series: ArchiveSeries,
        coverFile: File?,
        chapters: List<ArchiveRestorer.SeriesChapter>,
        followed: Boolean,
    ): Boolean {
        require(chapters.isNotEmpty()) { "series archive has no chapters" }
        val identity = GalleryIdentityV1(series.providerId, series.providerSeriesId)
        val sourceIdentity = identity.asSeriesIdentityV2()
        val coverTarget = copyCover(coverFile)
        val coverLocator = coverTarget?.toURI()?.toString()
            ?: series.coverLocator?.takeUnless { it.startsWith("file:", ignoreCase = true) }
        val materialized = mutableListOf<MaterializedChapter>()
        try {
            chapters.forEach { entry ->
                if (entry.pageFiles.isEmpty()) return@forEach
                val storageIdentity = serializedChapterStorageIdentity(sourceIdentity, entry.chapter.providerChapterId)
                val (location, documents) = com.easemic.app.feature.download.materializeCatalogDownloadPages(
                    filesDir = filesDir,
                    identity = storageIdentity,
                    downloadRevision = ArchiveRestorer.RESTORED_REVISION,
                    readyPagePaths = entry.pageFiles.map { it.toPath() },
                )
                materialized += MaterializedChapter(entry, storageIdentity, location, documents)
            }

            database.withTransaction {
                val summaries = chapters.map { entry ->
                    val state = knownSourceState(entry.chapter.sourceState) ?: "ACTIVE"
                    ChapterSummary(
                        identity = com.easemic.app.feature.download.ChapterIdentityV2(
                            sourceIdentity,
                            entry.chapter.providerChapterId,
                        ),
                        label = entry.chapter.label,
                        group = entry.chapter.group,
                        groupOrder = entry.chapter.groupOrder,
                        readingOrderInGroup = entry.chapter.readingOrder,
                        sourceUpdatedAtMillis = entry.chapter.sourceUpdatedAtMillis,
                        canRead = state != "SOURCE_PERMISSION",
                        isFee = false,
                    )
                }
                val itemId = repository.reconcileSeries(
                    series = identity,
                    title = series.title,
                    coverLocator = coverLocator,
                    chapters = ChapterListResult(
                        chapters = summaries,
                        // Structural reconciliation is not a new source observation.
                        // Do not let import time supersede archived or newer saved facts.
                        fetchedAtMillis = 0L,
                        // An archive is a saved subset; omitted chapters remain untouched.
                        completeness = ChapterListCompleteness.PARTIAL,
                    ),
                ).itemId
                val unitsByChapter = repository.chapterShelfRowsForItem(itemId)
                    .associateBy { it.sourceChapterId }
                series.detailSnapshot?.let { snapshot ->
                    repository.restoreSeriesDetailSnapshot(
                        series = sourceIdentity,
                        snapshot = snapshot,
                        fallbackTitle = series.title,
                        fallbackCover = coverLocator,
                    )
                }
                materialized.forEach { material ->
                    val chapter = material.entry.chapter
                    beforeChapterCommit(chapter)
                    val unit = unitsByChapter[chapter.providerChapterId]
                        ?: error("Restored series chapter has no Room reading unit")
                    repository.commitChapterDownloadLocalAvailability(
                        series = identity,
                        chapterId = chapter.providerChapterId,
                        downloadLocationUri = material.location,
                        downloadRevision = ArchiveRestorer.RESTORED_REVISION,
                        title = chapter.label.ifBlank { series.title },
                        pages = material.documents,
                        completeness = chapter.expectedPageCount?.let { DownloadCommitCompleteness.Truncated(it) }
                            ?: DownloadCommitCompleteness.Complete,
                    )
                    repository.setChapterProgress(
                        readingUnitId = ReadingUnitId(unit.readingUnitId),
                        ordinal = chapter.progressOrdinal,
                        completed = chapter.completed,
                        lastReadAtMillis = chapter.lastReadAtMillis,
                    )
                    knownSourceState(chapter.sourceState)?.let { state ->
                        repository.setChapterSourceState(ReadingUnitId(unit.readingUnitId), state)
                    }
                    downloadJobStore.insertRestoredFinish(
                        identity = identity,
                        chapterIdentity = chapter.providerChapterId,
                        expectedPageCount = material.documents.size,
                        downloadRevision = ArchiveRestorer.RESTORED_REVISION,
                        intentCloud = followed,
                    )
                }
                chapters.filter { it.pageFiles.isEmpty() }.forEach { entry ->
                    val chapter = entry.chapter
                    val unit = unitsByChapter[chapter.providerChapterId]
                        ?: error("Restored series chapter has no Room reading unit")
                    repository.setChapterProgress(
                        readingUnitId = ReadingUnitId(unit.readingUnitId),
                        ordinal = chapter.progressOrdinal,
                        completed = chapter.completed,
                        lastReadAtMillis = chapter.lastReadAtMillis,
                    )
                    knownSourceState(chapter.sourceState)?.let { state ->
                        repository.setChapterSourceState(ReadingUnitId(unit.readingUnitId), state)
                    }
                }
            }
        } catch (error: Throwable) {
            materialized.forEach { it.locationDirectory.deleteRecursively() }
            // A copy can fail before its MaterializedChapter is appended; clean
            // every deterministic target as well as the completed entries.
            chapters.filter { it.pageFiles.isNotEmpty() }.forEach { entry ->
                deleteCatalogDownloadRevision(
                    filesDir = filesDir,
                    identity = serializedChapterStorageIdentity(sourceIdentity, entry.chapter.providerChapterId),
                    downloadRevision = ArchiveRestorer.RESTORED_REVISION,
                )
            }
            coverTarget?.delete()
            throw error
        }
        // Locators are written only after Room commits. A locator failure must
        // not delete the now-durable library rows and page trees.
        materialized.forEach { material ->
            val chapter = material.entry.chapter
            locatorStore.putKeepingCatalogFacts(
                material.storageIdentity,
                DownloadLocator(
                    acquisitionUrl = serializedChapterLocatorKey(sourceIdentity, chapter.providerChapterId),
                    title = chapter.label,
                    coverUrl = coverLocator,
                ),
            )
        }
        return true
    }

    private fun copyCover(source: File?): File? {
        if (source?.isFile != true || source.length() <= 0L) return null
        val directory = File(filesDir, "archive-series-covers").also { it.mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}-${source.name}")
        return try {
            source.copyTo(target, overwrite = true)
            target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private data class MaterializedChapter(
        val entry: ArchiveRestorer.SeriesChapter,
        val storageIdentity: GalleryIdentityV1,
        val location: String,
        val documents: List<com.easemic.app.model.DocumentRef>,
    ) {
        val locationDirectory: File get() = runCatching { File(URI(location)) }.getOrElse { File(location) }
    }

    private fun knownSourceState(value: String): String? =
        value.takeIf { it == "ACTIVE" || it == "SOURCE_PERMISSION" || it == "SOURCE_REMOVED" }

    private fun extensionOf(file: File): String =
        file.extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "img"
}
