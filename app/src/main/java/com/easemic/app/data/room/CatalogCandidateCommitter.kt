package com.easemic.app.data.room

import androidx.room.withTransaction
import com.easemic.app.data.CatalogCandidateCommitter as CatalogCommitterContract
import com.easemic.app.model.CandidateCommitResult
import com.easemic.app.model.CandidateId
import com.easemic.app.model.CandidateOutcome
import com.easemic.app.model.CandidateRecognition
import com.easemic.app.model.LibraryItemId
import com.easemic.app.model.PageLocatorKind
import com.easemic.app.model.SourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.json.JSONArray

class CatalogCandidateCommitter(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val beforeTransaction: suspend () -> Unit = {},
    private val beforePointerSwap: suspend () -> Unit = {},
    private val afterCommit: suspend () -> Unit = {},
) : CatalogCommitterContract {
    private val library = database.libraryDao()
    private val sources = database.sourceDao()
    private val scans = database.scanRunDao()

    override suspend fun commit(
        sourceId: SourceId,
        generation: Long,
        candidateId: CandidateId,
    ): CandidateCommitResult = requireNotNull(
        commitIfCurrent(sourceId, generation, candidateId) { true },
    )

    internal suspend fun commitIfCurrent(
        sourceId: SourceId,
        generation: Long,
        candidateId: CandidateId,
        requestIsCurrent: suspend () -> Boolean,
    ): CandidateCommitResult? {
        coroutineContext.ensureActive()
        when (
            val preflight = database.withTransaction {
                if (!requestIsCurrent()) {
                    CommitPreflight.Stale
                } else {
                    scans.candidate(candidateId.value)
                        ?.takeIf { it.outcome !in setOf("PENDING", "LOW_CONFIDENCE") }
                        ?.let {
                            CommitPreflight.Terminal(
                                CandidateCommitResult(
                                    it.itemIdOrNull(),
                                    CandidateOutcome.valueOf(it.outcome),
                                ),
                            )
                        }
                        ?: CommitPreflight.Pending
                }
            }
        ) {
            CommitPreflight.Stale -> return null
            is CommitPreflight.Terminal -> return preflight.result
            CommitPreflight.Pending -> Unit
        }
        try {
            beforeTransaction()
            coroutineContext.ensureActive()
            val result = commitTransaction(
                sourceId,
                generation,
                candidateId,
                requestIsCurrent,
            ) ?: return null
            afterCommit()
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (removed: SourceRemovedException) {
            throw removed
        } catch (failure: Exception) {
            database.withTransaction {
                if (requestIsCurrent() && scans.markPendingCandidateFailed(candidateId.value) > 0) {
                    scans.candidate(candidateId.value)
                        ?.let { scans.refreshPreviewCounts(it.runId, clock()) }
                }
            }
            throw failure
        }
    }

    private suspend fun commitTransaction(
        sourceId: SourceId,
        generation: Long,
        candidateId: CandidateId,
        requestIsCurrent: suspend () -> Boolean,
    ): CandidateCommitResult? = database.withTransaction {
        if (!requestIsCurrent()) return@withTransaction null
        val source = requireNotNull(sources.source(sourceId.value))
        if (source.grantState == "REMOVED") throw SourceRemovedException()
        val row = requireNotNull(scans.candidate(candidateId.value))
        if (row.outcome !in setOf("PENDING", "LOW_CONFIDENCE")) {
            return@withTransaction CandidateCommitResult(
                row.itemIdOrNull(),
                CandidateOutcome.valueOf(row.outcome),
            )
        }
        val run = requireNotNull(scans.run(row.runId))
        check(run.sourceId == sourceId.value)
        check(run.generation == generation)
        check(run.enumerationComplete && run.reviewAttemptId == row.enumerationAttemptId)
        val candidate = requireNotNull(RoomScanRunRepository(database).candidate(candidateId))
        val recognized = (candidate.recognition as CandidateRecognition.Recoverable).candidate
        val now = clock()
        // A47 (spec D3): one location->item->unit->revision->pages->claims->outcome pass
        // per comic -- a single-comic archive runs it once on the flat fields (the exact
        // pre-A47 path), a container runs it once per sub-comic with the D2 identity
        // (archive document id + NUL + relativePath).
        val comics = if (recognized.subComics.isNotEmpty()) {
            recognized.subComics.map { subComic ->
                ComicCommitInputs(
                    providerDocumentId = recognized.providerDocumentId + SUB_COMIC_IDENTITY_SEPARATOR + subComic.relativePath,
                    documentUri = recognized.documentUri,
                    locationKind = recognized.locationKind,
                    relativeDisplayPath = recognized.relativeDisplayPath + "/" + subComic.relativePath,
                    metadata = subComic.metadata,
                    expectedPageCount = subComic.expectedPageCount,
                    pages = subComic.pages,
                    structuralSignature = subComic.structuralSignature,
                    provenance = subComic.provenance,
                    completeness = subComic.completeness,
                )
            }
        } else {
            listOf(
                ComicCommitInputs(
                    providerDocumentId = recognized.providerDocumentId,
                    documentUri = recognized.documentUri,
                    locationKind = recognized.locationKind,
                    relativeDisplayPath = recognized.relativeDisplayPath,
                    metadata = recognized.metadata,
                    expectedPageCount = recognized.expectedPageCount,
                    pages = recognized.pages,
                    structuralSignature = recognized.structuralSignature,
                    provenance = recognized.provenance,
                    completeness = recognized.completeness,
                ),
            )
        }
        val outcomes = comics.map { comic -> commitOneComic(sourceId, generation, comic, now) }
        val outcomesSet = outcomes.map { it.second }.toSet()
        val outcome = when {
            CandidateOutcome.CONFLICT in outcomesSet -> CandidateOutcome.CONFLICT
            CandidateOutcome.INCOMPLETE in outcomesSet -> CandidateOutcome.INCOMPLETE
            CandidateOutcome.NEW in outcomesSet -> CandidateOutcome.NEW
            CandidateOutcome.UPDATED in outcomesSet -> CandidateOutcome.UPDATED
            else -> CandidateOutcome.UNCHANGED
        }
        scans.markInitialRecoverableCandidateOutcome(row.runId, row.id, outcome.name)
        scans.refreshPreviewCounts(row.runId, now)
        // A container is many items; the single-comic shape keeps returning its one item id.
        val itemId = outcomes.singleOrNull()?.first
        CandidateCommitResult(itemId?.let(::LibraryItemId), outcome)
    }

    /**
     * The existing single-comic commit body, factored so containers loop the ONE code
     * path instead of duplicating the transaction logic. Runs INSIDE the caller's Room
     * transaction (spec D1: all sub-comics commit atomically or not at all). Returns the
     * committed library item id and the per-comic outcome.
     */
    private suspend fun commitOneComic(
        sourceId: SourceId,
        generation: Long,
        comic: ComicCommitInputs,
        now: Long,
    ): Pair<Long, CandidateOutcome> {
        val existingLocation = sources.locationByIdentity(sourceId.value, comic.providerDocumentId)
        val reconnectMatches = if (existingLocation == null && comic.provenance.isNotEmpty()) {
            val observedClaims = comic.provenance
                .map { Triple(it.namespace, it.key, it.value) }
                .toSet()
            sources.missingLocationsBySignature(sourceId.value, comic.structuralSignature)
                .mapNotNull { location ->
                    val itemId = library.uniqueItemIdByLocation(location.id) ?: return@mapNotNull null
                    val persistedClaims = library.claimsForItem(itemId)
                        .map { Triple(it.namespace, it.claimKey, it.claimValue) }
                        .toSet()
                    if (persistedClaims == observedClaims) location to itemId else null
                }
        } else {
            emptyList()
        }
        val reconnected = reconnectMatches.singleOrNull()
        val conflicted = reconnectMatches.size > 1
        val locationState = when {
            conflicted -> "CONFLICTED"
            comic.completeness.name == "INCOMPLETE" -> "INCOMPLETE"
            else -> "PRESENT"
        }
        val locationId = existingLocation?.id ?: reconnected?.first?.id ?: sources.insertLocation(
            ComicLocationEntity(
                sourceId = sourceId.value,
                providerDocumentId = comic.providerDocumentId,
                documentUri = comic.documentUri,
                locationKind = comic.locationKind.name,
                relativeDisplayPath = comic.relativeDisplayPath,
                structuralSignature = comic.structuralSignature,
                lastCompleteGeneration = generation,
                state = locationState,
            ),
        )
        if (reconnected != null) {
            sources.reconnectObservedLocation(
                locationId,
                comic.providerDocumentId,
                comic.documentUri,
                comic.locationKind.name,
                comic.relativeDisplayPath,
                comic.structuralSignature,
                generation,
                locationState,
            )
        } else if (existingLocation != null) {
            sources.updateObservedLocation(
                locationId,
                comic.documentUri,
                comic.locationKind.name,
                comic.relativeDisplayPath,
                comic.structuralSignature,
                generation,
                locationState,
            )
        }
        val existingItemId = reconnected?.second ?: library.uniqueItemIdByLocation(locationId)
        val itemId = existingItemId ?: library.insertItem(
            LibraryItemEntity(historicalAvailable = true, createdAtMillis = now, updatedAtMillis = now),
        )
        library.refreshLegacyItem(itemId, now)
        val metadata = comic.metadata
        val observedMetadata = MetadataSnapshotEntity(
            itemId = itemId,
            title = metadata.title,
            series = metadata.series,
            alternateSeries = metadata.alternateSeries,
            creators = JSONArray(metadata.creators).toString(),
            genres = JSONArray(metadata.genres).toString(),
            tags = JSONArray(metadata.tags).toString(),
            language = metadata.language,
            expectedPageCount = comic.expectedPageCount,
            createdAtMillis = now,
        )
        val currentMetadata = library.currentMetadataForItem(itemId)
        val metadataChanged = currentMetadata?.sameContentAs(observedMetadata) != true
        if (metadataChanged) {
            val metadataId = library.insertMetadata(observedMetadata)
            library.setCurrentMetadata(CurrentMetadataEntity(itemId, metadataId))
        }
        val existingUnit = library.readingUnitForItem(itemId)
        val unitId = existingUnit?.id ?: library.insertReadingUnit(
            ReadingUnitEntity(itemId = itemId, label = comic.relativeDisplayPath, ordinal = 0, progressOrdinal = 0, state = "READY"),
        )
        val priorRevisionId = library.currentRevisionId(unitId)
        val revisionId = library.revisionIdBySignature(unitId, comic.structuralSignature)
            ?: library.insertRevision(MediaRevisionEntity(readingUnitId = unitId, locationId = locationId, structuralSignature = comic.structuralSignature, createdAtMillis = now))
        if (library.pageCountForRevision(revisionId) == 0) {
            library.insertPages(
                comic.pages.mapIndexed { ordinal, page ->
                    PageEntity(
                        revisionId = revisionId,
                        ordinal = ordinal,
                        locatorKind = if (page.archiveEntryPath == null) PageLocatorKind.DOCUMENT.name else PageLocatorKind.ARCHIVE_ENTRY.name,
                        documentId = page.documentId,
                        documentUri = page.documentUri,
                        archiveEntryPath = page.archiveEntryPath,
                        displayName = page.displayName,
                        mimeType = page.mimeType,
                        size = page.size,
                        lastModifiedMillis = page.lastModifiedMillis,
                        width = page.width,
                        height = page.height,
                        corrupted = page.corrupted,
                    )
                },
            )
        }
        beforePointerSwap()
        coroutineContext.ensureActive()
        val freshSource = requireNotNull(sources.source(sourceId.value))
        if (freshSource.grantState == "REMOVED") throw SourceRemovedException()
        library.setCurrentRevision(CurrentMediaRevisionEntity(unitId, revisionId))
        existingUnit?.let {
            library.updateProgress(unitId, it.progressOrdinal.coerceIn(0, comic.pages.lastIndex))
        }
        library.insertClaims(
            comic.provenance.map {
                ProvenanceClaimEntity(itemId = itemId, namespace = it.namespace, claimKey = it.key, claimValue = it.value)
            },
        )
        val outcome = when {
            conflicted -> CandidateOutcome.CONFLICT
            comic.completeness.name == "INCOMPLETE" -> CandidateOutcome.INCOMPLETE
            existingItemId == null -> CandidateOutcome.NEW
            priorRevisionId != revisionId || metadataChanged -> CandidateOutcome.UPDATED
            else -> CandidateOutcome.UNCHANGED
        }
        return itemId to outcome
    }

    private data class ComicCommitInputs(
        val providerDocumentId: String,
        val documentUri: String,
        val locationKind: com.easemic.app.model.LocationKind,
        val relativeDisplayPath: String,
        val metadata: com.easemic.app.model.MetadataSnapshot,
        val expectedPageCount: Int?,
        val pages: List<com.easemic.app.model.PageDescriptor>,
        val structuralSignature: String,
        val provenance: List<com.easemic.app.model.ProvenanceValue>,
        val completeness: com.easemic.app.model.CandidateCompleteness,
    )

    override suspend fun reconcileMissing(sourceId: SourceId, completedGeneration: Long) {
        check(reconcileMissingIfCurrent(sourceId, completedGeneration) { true })
    }

    internal suspend fun reconcileMissingIfCurrent(
        sourceId: SourceId,
        completedGeneration: Long,
        requestIsCurrent: suspend () -> Boolean,
    ): Boolean = database.withTransaction {
            if (!requestIsCurrent()) return@withTransaction false
            val source = requireNotNull(sources.source(sourceId.value))
            check(source.grantState == "READY")
            val run = requireNotNull(scans.runForGeneration(sourceId.value, completedGeneration))
            check(run.enumerationComplete && run.reviewAttemptId != null)
            check(run.state !in setOf("FAILED", "PAUSED", "PAUSE_REQUESTED"))
            sources.markLocationsSeenInReviewAttempt(
                sourceId = sourceId.value,
                runId = run.id,
                reviewAttemptId = run.reviewAttemptId,
                generation = completedGeneration,
            )
            sources.markUnseenLocationsMissing(sourceId.value, completedGeneration)
            true
        }

    private suspend fun ScanCandidateEntity.itemIdOrNull(): LibraryItemId? {
        val run = scans.run(runId) ?: return null
        val location = sources.locationByIdentity(run.sourceId, providerDocumentId) ?: return null
        return library.uniqueItemIdByLocation(location.id)?.let(::LibraryItemId)
    }

    private fun MetadataSnapshotEntity.sameContentAs(other: MetadataSnapshotEntity): Boolean =
        title == other.title &&
            series == other.series &&
            alternateSeries == other.alternateSeries &&
            creators == other.creators &&
            genres == other.genres &&
            tags == other.tags &&
            language == other.language &&
            expectedPageCount == other.expectedPageCount

    private suspend fun LibraryDao.uniqueItemIdByLocation(locationId: Long): Long? =
        itemIdsByLocation(locationId).singleOrNull()

    private sealed interface CommitPreflight {
        data object Stale : CommitPreflight
        data object Pending : CommitPreflight
        data class Terminal(val result: CandidateCommitResult) : CommitPreflight
    }

    private class SourceRemovedException : IllegalStateException("Source was removed before catalog commit")

    private companion object {
        /**
         * A47 (spec D2): sub-comic location identity separator. NUL cannot appear in a
         * SAF document id or an archive entry path, so the composite stays unambiguous
         * without touching the location schema.
         */
        const val SUB_COMIC_IDENTITY_SEPARATOR = "\u0000"
    }
}
