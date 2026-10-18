package com.easemic.app.data.room

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.easemic.app.data.ScanRunRepository
import com.easemic.app.model.CandidateCommitResult
import com.easemic.app.model.CandidateConfidence
import com.easemic.app.model.CandidateId
import com.easemic.app.model.DiscoveredCandidate
import com.easemic.app.model.EnumerationAttemptId
import com.easemic.app.model.RunCheckpoint
import com.easemic.app.model.RunFailure
import com.easemic.app.model.RunPreview
import com.easemic.app.model.ScanPhase
import com.easemic.app.model.ScanRun
import com.easemic.app.model.ScanRunId
import com.easemic.app.model.ScanRunState
import com.easemic.app.model.SourceId
import com.easemic.app.model.WorkRequestId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class RoomScanRunRepository(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val afterActiveRunPreflight: suspend () -> Unit = {},
    private val onActiveSlotConflict: (SQLiteConstraintException) -> Unit = {},
) : ScanRunRepository {
    private val dao = database.scanRunDao()

    override suspend fun createOrResume(sourceId: SourceId): ScanRunId {
        dao.activeRunId(sourceId.value)?.let(::ScanRunId)?.let { return it }
        afterActiveRunPreflight()
        try {
            return database.withTransaction create@{
                val now = clock()
                val runId = ids()
                val priorGeneration = dao.latestRunForSource(sourceId.value)?.generation ?: 0
                dao.insertRun(
                    ScanRunEntity(
                        id = runId,
                        sourceId = sourceId.value,
                        generation = priorGeneration + 1,
                        phase = ScanPhase.VALIDATE.name,
                        state = ScanRunState.ENQUEUED.name,
                        enumerationComplete = false,
                        pauseRequested = false,
                        enumerationAttemptId = null,
                        reviewAttemptId = null,
                        checkpointLastCandidateId = null,
                        checkpointProcessedCount = 0,
                        discoveredCount = 0,
                        selectedCount = 0,
                        committedCount = 0,
                        highConfidenceCount = 0,
                        lowConfidenceCount = 0,
                        incompleteCount = 0,
                        rejectedCount = 0,
                        failedCount = 0,
                        problem = null,
                        recoveryAction = "NONE",
                        createdAtMillis = now,
                        updatedAtMillis = now,
                    ),
                )
                dao.setActiveRun(ActiveScanRunEntity(sourceId.value, runId))
                ScanRunId(runId)
            }
        } catch (failure: SQLiteConstraintException) {
            onActiveSlotConflict(failure)
            return database.withTransaction {
                dao.activeRunId(sourceId.value)?.let(::ScanRunId)
            } ?: throw failure
        }
    }

    override fun observeRun(runId: ScanRunId): Flow<ScanRun> =
        dao.observeRun(runId.value).map { requireNotNull(it).toPreviewRun() }
    override fun observePreview(runId: ScanRunId): Flow<RunPreview> =
        combine(
            dao.observeRun(runId.value),
            dao.observePreviewCandidates(runId.value),
        ) { run, _ ->
            val candidates = dao.previewCandidates(runId.value)
            RunPreview(
                run = requireNotNull(run).toPreviewRun(),
                candidates = candidates.map { entity ->
                    com.easemic.app.model.CandidatePreview(
                        id = CandidateId(entity.id),
                        recognitionKind = com.easemic.app.model.RecognitionKind.valueOf(entity.recognitionKind),
                        confidence = entity.confidence?.let(CandidateConfidence::valueOf),
                        completeness = entity.completeness?.let(com.easemic.app.model.CandidateCompleteness::valueOf),
                        outcome = com.easemic.app.model.CandidateOutcome.valueOf(entity.outcome),
                        selected = entity.selected,
                        title = entity.metadataJson?.let(::metadataTitle),
                        actualPageCount = if (entity.recognitionKind == "RECOVERABLE") {
                            dao.candidatePages(entity.id).size
                        } else {
                            null
                        },
                        cover = if (entity.recognitionKind == "RECOVERABLE") dao.candidatePages(entity.id).firstOrNull()?.toDescriptor() else null,
                        warnings = entity.warningsJson?.let(::warnings),
                        rejection = entity.rejectionReason?.toRejection(entity.rejectionArchiveFailure),
                        retryReason = entity.retryReason?.toFailure(),
                        documentUri = entity.documentUri,
                        providerDocumentId = entity.providerDocumentId,
                        structuralSignature = entity.structuralSignature,
                        displayName = entity.seedDisplayName,
                    )
                },
            )
        }
    /**
     * S4b/G4: the shelf's added line reads [RunSummary.newlyAdded] from here. It is one
     * extra COUNT per watched folder per emission (folders are few, and `scan_candidates`
     * writes already bump `scan_runs`, so this flow re-reads exactly when it should).
     */
    override fun observeLatestRunsBySource(): Flow<Map<SourceId, ScanRun>> =
        dao.observeRunsForSourceProjection().map { runs ->
            runs.groupBy(ScanRunEntity::sourceId)
                .mapValues { (_, sourceRuns) ->
                    val latest = sourceRuns.maxBy(ScanRunEntity::generation)
                    latest.toPreviewRun(newlyAdded = dao.newlyAddedCount(latest.id))
                }
                .mapKeys { (sourceId, _) -> SourceId(sourceId) }
        }
    override suspend fun run(runId: ScanRunId): ScanRun? = dao.run(runId.value)?.toPreviewRun()
    override suspend fun nonTerminalRuns(): List<ScanRun> =
        dao.nonTerminalRuns().map { it.toPreviewRun() }
    override suspend fun beginEnumerationAttempt(runId: ScanRunId): EnumerationAttemptId {
        val attempt = EnumerationAttemptId(ids())
        check(dao.beginEnumerationAttempt(runId.value, attempt.value, clock()) > 0) {
            "Cannot begin enumeration for a stale or terminal run."
        }
        return attempt
    }

    suspend fun persistedProviderDocumentIds(
        runId: ScanRunId,
        attemptId: EnumerationAttemptId,
    ): Set<String> = dao.providerDocumentIdsForAttempt(runId.value, attemptId.value).toSet()

    suspend fun retainAttemptCandidates(
        runId: ScanRunId,
        attemptId: EnumerationAttemptId,
        keepProviderDocumentIds: Set<String>,
    ) {
        if (keepProviderDocumentIds.isEmpty()) {
            dao.deleteAllAttemptCandidates(runId.value, attemptId.value)
        } else {
            dao.deleteAttemptCandidatesNotIn(
                runId.value,
                attemptId.value,
                keepProviderDocumentIds.toList(),
            )
        }
        dao.refreshPreviewCounts(runId.value, clock())
    }
    override suspend fun persistCandidate(
        runId: ScanRunId,
        attemptId: EnumerationAttemptId,
        candidate: DiscoveredCandidate,
    ): CandidateId = database.withTransaction {
        check(dao.isCurrentRunningAttempt(runId.value, attemptId.value) > 0) {
            "Cannot persist a candidate for a stale enumeration attempt."
        }
        val recognition = candidate.recognition
        val entity = when (recognition) {
            is com.easemic.app.model.CandidateRecognition.Recoverable -> ScanCandidateEntity(
                runId = runId.value,
                providerDocumentId = candidate.seed.providerDocumentId,
                documentUri = candidate.seed.documentUri,
                enumerationAttemptId = attemptId.value,
                seedDisplayName = candidate.seed.displayName,
                locationKind = candidate.seed.locationKind.name,
                selectedDirectly = candidate.seed.selectedDirectly,
                recognitionKind = "RECOVERABLE",
                relativeDisplayPath = recognition.candidate.relativeDisplayPath,
                confidence = recognition.candidate.confidence.name,
                completeness = recognition.candidate.completeness.name,
                outcome = if (recognition.candidate.confidence == CandidateConfidence.LOW) "LOW_CONFIDENCE" else "PENDING",
                selected = recognition.candidate.confidence == CandidateConfidence.HIGH,
                metadataJson = recognition.candidate.metadata.toJson(),
                expectedPageCount = recognition.candidate.expectedPageCount,
                structuralSignature = recognition.candidate.structuralSignature,
                warningsJson = recognition.candidate.warnings.toJson(),
                rejectionReason = null,
                rejectionArchiveFailure = null,
                retryReason = null,
            )
            is com.easemic.app.model.CandidateRecognition.Rejected -> ScanCandidateEntity(
                runId = runId.value,
                providerDocumentId = candidate.seed.providerDocumentId,
                documentUri = candidate.seed.documentUri,
                enumerationAttemptId = attemptId.value,
                seedDisplayName = candidate.seed.displayName,
                locationKind = candidate.seed.locationKind.name,
                selectedDirectly = candidate.seed.selectedDirectly,
                recognitionKind = "REJECTED",
                relativeDisplayPath = null,
                confidence = null,
                completeness = null,
                outcome = "REJECTED",
                selected = false,
                metadataJson = null,
                expectedPageCount = null,
                structuralSignature = null,
                warningsJson = null,
                rejectionReason = recognition.reason.toStoredRejection(),
                rejectionArchiveFailure = (recognition.reason as? com.easemic.app.model.CandidateRejection.UnsafeArchive)?.reason?.name,
                retryReason = null,
            )
            is com.easemic.app.model.CandidateRecognition.Retryable -> ScanCandidateEntity(
                runId = runId.value,
                providerDocumentId = candidate.seed.providerDocumentId,
                documentUri = candidate.seed.documentUri,
                enumerationAttemptId = attemptId.value,
                seedDisplayName = candidate.seed.displayName,
                locationKind = candidate.seed.locationKind.name,
                selectedDirectly = candidate.seed.selectedDirectly,
                recognitionKind = "RETRYABLE",
                relativeDisplayPath = null,
                confidence = null,
                completeness = null,
                outcome = "RETRYABLE",
                selected = false,
                metadataJson = null,
                expectedPageCount = null,
                structuralSignature = null,
                warningsJson = null,
                rejectionReason = null,
                rejectionArchiveFailure = null,
                retryReason = recognition.reason.toStoredFailure(),
            )
        }
        val existing = dao.candidateByProviderDocumentId(
            runId.value,
            attemptId.value,
            candidate.seed.providerDocumentId,
        )
        val id = if (existing == null) dao.insertCandidate(entity) else {
            dao.updateCandidate(entity.copy(id = existing.id))
            existing.id
        }
        dao.deleteCandidatePages(id)
        dao.deleteCandidateClaims(id)
        dao.deleteCandidateSubComics(id)
        if (recognition is com.easemic.app.model.CandidateRecognition.Recoverable) {
            dao.insertCandidatePages(recognition.candidate.pages.mapIndexed { ordinal, page ->
                ScanCandidatePageEntity(id, ordinal, if (page.archiveEntryPath == null) "DOCUMENT" else "ARCHIVE_ENTRY", page.documentId, page.documentUri, page.archiveEntryPath, page.displayName, page.mimeType, page.size, page.lastModifiedMillis, page.width, page.height, -1, page.corrupted)
            })
            dao.insertCandidateClaims(recognition.candidate.provenance.map { claim ->
                ScanCandidateClaimEntity(candidateId = id, namespace = claim.namespace, claimKey = claim.key, claimValue = claim.value)
            })
            // A47: a container persists its sub-comics (spec D1) -- pages and claims keyed
            // by sub-comic ordinal, `ordinal` running across ALL pages of the candidate
            // (flat first, then each sub-comic's pages in order) so the (candidateId,
            // ordinal) primary key stays unique.
            var nextPageOrdinal = recognition.candidate.pages.size
            recognition.candidate.subComics.forEachIndexed { subComicOrdinal, subComic ->
                dao.insertCandidateSubComics(
                    listOf(
                        ScanCandidateSubComicEntity(
                            candidateId = id,
                            ordinal = subComicOrdinal,
                            relativePath = subComic.relativePath,
                            metadataJson = subComic.metadata.toJson(),
                            expectedPageCount = subComic.expectedPageCount,
                            structuralSignature = subComic.structuralSignature,
                            completeness = subComic.completeness.name,
                        ),
                    ),
                )
                dao.insertCandidatePages(
                    subComic.pages.map { page ->
                        val ordinal = nextPageOrdinal
                        nextPageOrdinal += 1
                        ScanCandidatePageEntity(
                            candidateId = id,
                            ordinal = ordinal,
                            locatorKind = if (page.archiveEntryPath == null) "DOCUMENT" else "ARCHIVE_ENTRY",
                            documentId = page.documentId,
                            documentUri = page.documentUri,
                            archiveEntryPath = page.archiveEntryPath,
                            displayName = page.displayName,
                            mimeType = page.mimeType,
                            size = page.size,
                            lastModifiedMillis = page.lastModifiedMillis,
                            width = page.width,
                            height = page.height,
                            subComicOrdinal = subComicOrdinal,
                            corrupted = page.corrupted,
                        )
                    },
                )
                dao.insertCandidateClaims(
                    subComic.provenance.map { claim ->
                        ScanCandidateClaimEntity(
                            candidateId = id,
                            namespace = claim.namespace,
                            claimKey = claim.key,
                            claimValue = claim.value,
                            subComicOrdinal = subComicOrdinal,
                        )
                    },
                )
            }
        }
        val run = requireNotNull(dao.run(runId.value))
        if (run.reviewAttemptId == null || run.reviewAttemptId == attemptId.value) {
            dao.refreshPreviewCounts(runId.value, clock())
        }
        CandidateId(id)
    }
    override suspend fun candidate(candidateId: CandidateId): DiscoveredCandidate? =
        dao.candidate(candidateId.value)?.let { entity ->
            if (entity.recognitionKind == "REJECTED") {
                return@let DiscoveredCandidate(
                    com.easemic.app.model.CandidateSeed(entity.providerDocumentId, entity.documentUri, entity.seedDisplayName, com.easemic.app.model.LocationKind.valueOf(entity.locationKind), entity.selectedDirectly),
                    com.easemic.app.model.CandidateRecognition.Rejected(requireNotNull(entity.rejectionReason).toRejection(entity.rejectionArchiveFailure)),
                )
            }
            if (entity.recognitionKind == "RETRYABLE") {
                return@let DiscoveredCandidate(
                    com.easemic.app.model.CandidateSeed(entity.providerDocumentId, entity.documentUri, entity.seedDisplayName, com.easemic.app.model.LocationKind.valueOf(entity.locationKind), entity.selectedDirectly),
                    com.easemic.app.model.CandidateRecognition.Retryable(requireNotNull(entity.retryReason).toFailure()),
                )
            }
            check(entity.recognitionKind == "RECOVERABLE") { "Unknown recognition kind ${entity.recognitionKind}" }
            val allPages = dao.candidatePages(entity.id)
            val allClaims = dao.candidateClaims(entity.id)
            val subComicRows = dao.candidateSubComics(entity.id)
            val flatPages = allPages.filter { it.subComicOrdinal < 0 }.map { it.toDescriptor() }
            val flatClaims = allClaims.filter { it.subComicOrdinal < 0 }
                .map { com.easemic.app.model.ProvenanceValue(it.namespace, it.claimKey, it.claimValue) }
            // A47: rebuild the container's sub-comics (pages/claims sliced by their
            // subComicOrdinal discriminator; a sub-comic's pages keep their relative order).
            val subComics = subComicRows.map { row ->
                com.easemic.app.model.RecognizedSubComic(
                    relativePath = row.relativePath,
                    metadata = requireNotNull(row.metadataJson).toMetadata(),
                    expectedPageCount = row.expectedPageCount,
                    pages = allPages.filter { it.subComicOrdinal == row.ordinal }.map { it.toDescriptor() },
                    structuralSignature = row.structuralSignature,
                    provenance = allClaims.filter { it.subComicOrdinal == row.ordinal }
                        .map { com.easemic.app.model.ProvenanceValue(it.namespace, it.claimKey, it.claimValue) },
                    completeness = com.easemic.app.model.CandidateCompleteness.valueOf(row.completeness),
                )
            }
            DiscoveredCandidate(
                com.easemic.app.model.CandidateSeed(
                    entity.providerDocumentId,
                    entity.documentUri,
                    entity.seedDisplayName,
                    com.easemic.app.model.LocationKind.valueOf(entity.locationKind),
                    entity.selectedDirectly,
                ),
                com.easemic.app.model.CandidateRecognition.Recoverable(
                    com.easemic.app.model.RecognizedCandidate(
                        entity.providerDocumentId,
                        entity.documentUri,
                        com.easemic.app.model.LocationKind.valueOf(entity.locationKind),
                        requireNotNull(entity.relativeDisplayPath),
                        CandidateConfidence.valueOf(requireNotNull(entity.confidence)),
                        com.easemic.app.model.CandidateCompleteness.valueOf(requireNotNull(entity.completeness)),
                        requireNotNull(entity.metadataJson).toMetadata(),
                        entity.expectedPageCount,
                        flatPages,
                        requireNotNull(entity.structuralSignature),
                        flatClaims,
                        entity.warningsJson?.let(::warnings) ?: emptySet(),
                        subComics,
                    ),
                ),
            )
        }
    override suspend fun nextSelectedNonTerminalCandidate(runId: ScanRunId): CandidateId? =
        dao.nextSelectedNonTerminalCandidate(runId.value)?.let(::CandidateId)
    override suspend fun setSelection(runId: ScanRunId, selected: Set<CandidateId>) = database.withTransaction {
        check(dao.reviewCandidateCount(runId.value, selected.map(CandidateId::value)) == selected.size) {
            "Cannot select candidates outside the current review attempt."
        }
        dao.clearSelection(runId.value)
        if (selected.isNotEmpty()) dao.selectRecoverableCandidates(runId.value, selected.map(CandidateId::value))
        dao.refreshPreviewCounts(runId.value, clock())
    }
    override suspend fun requestPause(runId: ScanRunId): Boolean =
        dao.requestPauseIfActive(runId.value, clock()) > 0
    override suspend fun requestPauseIfCurrent(
        runId: ScanRunId,
        requestId: WorkRequestId,
    ): Boolean = dao.requestPauseIfCurrent(runId.value, requestId.value, clock()) > 0

    override suspend fun currentWorkRequest(runId: ScanRunId): WorkRequestId? =
        dao.currentWorkRequestId(runId.value)?.let(::WorkRequestId)

    override suspend fun associateWorkRequest(
        runId: ScanRunId,
        expectedRequestId: WorkRequestId?,
        newRequestId: WorkRequestId,
    ): Boolean = database.withTransaction {
        val current = dao.currentWorkRequestId(runId.value)
        when {
            current == newRequestId.value -> true
            current == null && expectedRequestId == null -> {
                dao.insertWorkRequest(ScanWorkRequestEntity(runId.value, newRequestId.value, clock()))
                true
            }
            current == expectedRequestId?.value ->
                dao.replaceWorkRequest(
                    runId.value,
                    requireNotNull(current),
                    newRequestId.value,
                    clock(),
                ) > 0
            else -> false
        }
    }
    override suspend fun resumeWorkRequest(
        runId: ScanRunId,
        expectedRequestId: WorkRequestId?,
        newRequestId: WorkRequestId,
    ): Boolean = database.withTransaction {
        val current = dao.currentWorkRequestId(runId.value)
        if (current != expectedRequestId?.value) return@withTransaction false
        val run = dao.run(runId.value) ?: return@withTransaction false
        if (run.state !in setOf("PAUSED", "PAUSE_REQUESTED")) return@withTransaction false
        val associated = if (current == null) {
            dao.insertWorkRequest(ScanWorkRequestEntity(runId.value, newRequestId.value, clock()))
            true
        } else {
            dao.replaceWorkRequest(
                runId.value,
                current,
                newRequestId.value,
                clock(),
            ) > 0
        }
        associated && dao.reactivateForResume(runId.value, newRequestId.value, clock()) > 0
    }
    override suspend fun checkpoint(runId: ScanRunId, checkpoint: RunCheckpoint) {
        dao.updateCheckpoint(runId.value, checkpoint.lastCandidateId?.value, checkpoint.processedCount, clock())
    }
    override suspend fun transition(
        runId: ScanRunId,
        expectedStates: Set<ScanRunState>,
        newState: ScanRunState,
        phase: ScanPhase,
        failure: RunFailure?,
    ): Boolean = dao.transitionState(
        runId.value,
        expectedStates.map(ScanRunState::name),
        newState.name,
        phase.name,
        failure?.problem?.name,
        failure?.recoveryAction?.name ?: "NONE",
        clock(),
    ) > 0
    override suspend fun markCandidateOutcome(runId: ScanRunId, candidateId: CandidateId, result: CandidateCommitResult) = database.withTransaction {
        check(dao.reviewCandidateCount(runId.value, listOf(candidateId.value)) == 1) {
            "Cannot update a candidate outside the current review attempt."
        }
        dao.markInitialRecoverableCandidateOutcome(runId.value, candidateId.value, result.outcome.name)
        dao.refreshPreviewCounts(runId.value, clock())
    }
    override suspend fun markEnumerationComplete(runId: ScanRunId, attemptId: EnumerationAttemptId): Boolean =
        database.withTransaction {
            if (dao.isCurrentRunningAttempt(runId.value, attemptId.value) == 0) return@withTransaction false
            val completed = dao.markEnumerationComplete(runId.value, attemptId.value, clock()) > 0
            if (completed) {
                dao.pruneCandidatesNotSeenInAttempt(runId.value, attemptId.value)
                dao.refreshPreviewCounts(runId.value, clock())
            }
            completed
        }
    override suspend fun finish(runId: ScanRunId) = database.withTransaction {
        val run = requireNotNull(dao.run(runId.value))
        if (dao.markCompleteIfEligible(run.id, clock()) == 0) return@withTransaction
        dao.clearActiveRun(run.sourceId, run.id)
    }
    override suspend fun recoverAbandonedRuns(activeWorkNames: Set<String>) = database.withTransaction {
        val missingRunIds = dao.nonTerminalRuns()
            .map(ScanRunEntity::id)
            .filter { runId -> "scan-run-$runId" !in activeWorkNames }
        if (missingRunIds.isNotEmpty()) dao.pauseMissingWorkRuns(missingRunIds, clock())
    }

    private fun com.easemic.app.model.MetadataSnapshot.toJson() = JSONObject().apply {
        put("title", title)
        put("series", series)
        put("alternateSeries", alternateSeries)
        put("creators", JSONArray(creators))
        put("genres", JSONArray(genres))
        put("tags", JSONArray(tags))
        put("language", language)
    }.toString()
    private fun Set<String>.toJson() = JSONArray(toList()).toString()
    private fun metadataTitle(json: String): String = JSONObject(json).getString("title")
    private fun String.toMetadata() = JSONObject(this).let { json ->
        com.easemic.app.model.MetadataSnapshot(
            title = json.getString("title"),
            series = json.optionalString("series"),
            alternateSeries = json.optionalString("alternateSeries"),
            creators = json.getJSONArray("creators").toStrings(),
            genres = json.getJSONArray("genres").toStrings(),
            tags = json.getJSONArray("tags").toStrings(),
            language = json.optionalString("language"),
        )
    }
    private fun JSONObject.optionalString(key: String) = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONArray.toStrings() = (0 until length()).map(::getString)
    private fun warnings(json: String): Set<String> = JSONArray(json).let { array -> (0 until array.length()).map(array::getString).toSet() }
    private fun ScanCandidatePageEntity.toDescriptor() = com.easemic.app.model.PageDescriptor(documentId, documentUri, archiveEntryPath, displayName, mimeType, size, lastModifiedMillis, width, height, corrupted)
    private fun com.easemic.app.model.CandidateRejection.toStoredRejection() = when (this) {
        com.easemic.app.model.CandidateRejection.NoSafePages -> "NO_SAFE_PAGES"
        com.easemic.app.model.CandidateRejection.IncidentalFiles -> "INCIDENTAL_FILES"
        is com.easemic.app.model.CandidateRejection.UnsafeArchive -> "UNSAFE_ARCHIVE"
    }
    private fun String.toRejection(archiveFailure: String?) = when (this) {
        "NO_SAFE_PAGES" -> com.easemic.app.model.CandidateRejection.NoSafePages
        "INCIDENTAL_FILES" -> com.easemic.app.model.CandidateRejection.IncidentalFiles
        "UNSAFE_ARCHIVE" -> com.easemic.app.model.CandidateRejection.UnsafeArchive(com.easemic.app.model.ArchiveFailure.valueOf(requireNotNull(archiveFailure)))
        else -> error("Unknown rejection $this")
    }
    private fun com.easemic.app.model.CandidateFailure.toStoredFailure() = when (this) {
        com.easemic.app.model.CandidateFailure.SourceChanged -> "SOURCE_CHANGED"
        com.easemic.app.model.CandidateFailure.ProviderUnavailable -> "PROVIDER_UNAVAILABLE"
        com.easemic.app.model.CandidateFailure.InsufficientSpace -> "INSUFFICIENT_SPACE"
    }
    private fun String.toFailure() = when (this) {
        "SOURCE_CHANGED" -> com.easemic.app.model.CandidateFailure.SourceChanged
        "PROVIDER_UNAVAILABLE" -> com.easemic.app.model.CandidateFailure.ProviderUnavailable
        "INSUFFICIENT_SPACE" -> com.easemic.app.model.CandidateFailure.InsufficientSpace
        else -> error("Unknown candidate failure $this")
    }

    private fun ScanRunEntity.toPreviewRun(newlyAdded: Int = 0) = ScanRun(
        id = ScanRunId(id),
        sourceId = SourceId(sourceId),
        generation = generation,
        phase = ScanPhase.valueOf(phase),
        state = ScanRunState.valueOf(state),
        enumerationComplete = enumerationComplete,
        pauseRequested = pauseRequested,
        enumerationAttemptId = enumerationAttemptId?.let(::EnumerationAttemptId),
        checkpoint = RunCheckpoint(checkpointLastCandidateId?.let(::CandidateId), checkpointProcessedCount),
        summary = com.easemic.app.model.RunSummary(
            discoveredCount, highConfidenceCount, lowConfidenceCount, incompleteCount,
            rejectedCount, selectedCount, committedCount, failedCount, newlyAdded,
        ),
        failure = problem?.let {
            RunFailure(
                com.easemic.app.model.RunProblem.valueOf(it),
                com.easemic.app.model.RecoveryAction.valueOf(recoveryAction),
            )
        },
        // A52 (finding 3): every scan_runs state/summary write already bumps
        // updatedAtMillis; carrying it here lets the Activity tracker tell a
        // run that finished before this process started (a stale receipt)
        // from one the user has not seen yet.
        updatedAtMillis = updatedAtMillis,
    )
}
