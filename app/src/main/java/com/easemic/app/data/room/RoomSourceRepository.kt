package com.easemic.app.data.room

import androidx.room.withTransaction
import androidx.core.net.toUri
import com.easemic.app.data.SourceRepository
import com.easemic.app.data.isExternalImportSource
import com.easemic.app.model.AdapterKind
import com.easemic.app.model.AuthorizedSource
import com.easemic.app.model.AuthorizedSourceDraft
import com.easemic.app.model.GrantKind
import com.easemic.app.model.GrantReleaseOutcome
import com.easemic.app.model.ImportBatch
import com.easemic.app.model.ImportBatchId
import com.easemic.app.model.RunFailure
import com.easemic.app.model.RunSummary
import com.easemic.app.model.ScanRunId
import com.easemic.app.model.SourceGrantState
import com.easemic.app.model.SourceId
import com.easemic.app.model.SourceRelease
import com.easemic.app.model.SourceState
import com.easemic.app.storage.GrantPersistenceResult
import com.easemic.app.storage.GrantedUri
import com.easemic.app.storage.PersistedReadGrantManager
import com.easemic.app.storage.SafProviderIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomSourceRepository(
    private val database: AppDatabase,
    private val grants: PersistedReadGrantManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val beforeTreeSourceUpsert: suspend () -> Unit = {},
    private val beforeDocumentBatchCommit: suspend () -> Unit = {},
    private val canReadTree: suspend (String) -> Boolean = { true },
    private val canReadDocument: suspend (String) -> Boolean = { true },
) : SourceRepository {
    private val sourceDao = database.sourceDao()
    private val scanRunDao = database.scanRunDao()

    override fun observeSources(): Flow<List<AuthorizedSource>> =
        sourceDao.observeSources()
            .combine(scanRunDao.observeRunsForSourceProjection()) { _, _ ->
                database.withTransaction {
                    sourceDao.visibleSources()
                        .filter { AdapterKind.valueOf(it.adapterKind).isExternalImportSource() }
                        .map { source -> project(source) }
                }
            }

    override fun observeBatches(): Flow<List<ImportBatch>> =
        sourceDao.observeBatches().map { batches -> database.withTransaction { batches.map { batch ->
            ImportBatch(ImportBatchId(batch.id), sourceDao.visibleBatchSourceIds(batch.id).map(::SourceId), batch.createdAtMillis)
        } } }

    override suspend fun source(sourceId: SourceId): AuthorizedSource? =
        database.withTransaction {
            sourceDao.source(sourceId.value)?.let { entity -> project(entity) }
        }

    override suspend fun upsertAuthorizedSource(draft: AuthorizedSourceDraft): SourceId = database.withTransaction {
        val existing = sourceDao.sourceByIdentity(draft.grantKind.name, draft.providerIdentity)
        if (existing != null) {
            refreshAuthorizedSourceInTransaction(existing, draft)
        } else SourceId(sourceDao.insertSource(draft.toEntity(clock())))
    }

    override suspend fun createBatch(sourceIds: List<SourceId>): ImportBatchId = database.withTransaction {
        val batchId = sourceDao.insertBatch(ImportBatchEntity(createdAtMillis = clock()))
        sourceDao.insertBatchSources(sourceIds.distinct().map { ImportBatchSourceEntity(batchId, it.value) })
        ImportBatchId(batchId)
    }

    override suspend fun setGrantState(sourceId: SourceId, state: SourceGrantState) {
        sourceDao.setGrantState(sourceId.value, state, clock())
    }

    suspend fun authorizeTreeSource(
        grant: GrantedUri,
        adapterKind: AdapterKind,
        displayLabel: String,
    ): AuthorizedSourceOutcome? {
        val identity = SafProviderIdentity.tree(grant.uri.toString()) ?: return null
        if (grants.persist(grant) !is GrantPersistenceResult.Persisted) return null
        return try {
            if (!canReadTree(grant.uri.toString())) {
                releaseUnreferencedGrants(listOf(grant.uri))
                return null
            }
            beforeTreeSourceUpsert()
            database.withTransaction {
                val outcome = upsertAuthorizedSourceOutcomeInTransaction(
                    AuthorizedSourceDraft(
                        grantKind = GrantKind.TREE,
                        adapterKind = adapterKind,
                        contentUri = grant.uri.toString(),
                        providerIdentity = identity,
                        displayLabel = displayLabel,
                    ),
                )
                sourceDao.setGrantState(outcome.sourceId.value, SourceGrantState.READY, clock())
                outcome
            }
        } catch (error: Throwable) {
            releaseUnreferencedGrants(listOf(grant.uri))
            throw error
        }
    }

    suspend fun reauthorizeTreeSource(
        sourceId: SourceId,
        grant: GrantedUri,
        adapterKind: AdapterKind,
        displayLabel: String,
    ): SourceId? {
        val identity = SafProviderIdentity.tree(grant.uri.toString()) ?: return null
        val target = sourceDao.source(sourceId.value)
            ?.takeIf {
                it.grantState != SourceGrantState.REMOVED.name &&
                    it.grantKind == GrantKind.TREE.name
            }
            ?: return null
        val identityMatches = target.providerIdentity == identity
        val oldUnreadable = !canReadTree(target.contentUri)
        val needsReauth = target.grantState == SourceGrantState.NEEDS_REAUTHORIZATION.name
        val allowIdentityChange = !identityMatches && (oldUnreadable || needsReauth)
        if (!identityMatches && !allowIdentityChange) return null
        if (allowIdentityChange) {
            val collision = sourceDao.sourceByIdentity(GrantKind.TREE.name, identity)
            if (collision != null && collision.id != target.id &&
                collision.grantState != SourceGrantState.REMOVED.name
            ) {
                return null
            }
        }
        if (grants.persist(grant) !is GrantPersistenceResult.Persisted) return null
        val previousUri = target.contentUri
        return try {
            if (!canReadTree(grant.uri.toString())) {
                releaseUnreferencedGrants(listOf(grant.uri))
                return null
            }
            database.withTransaction {
                val current = sourceDao.source(sourceId.value)
                if (
                    current == null ||
                    current.grantState == SourceGrantState.REMOVED.name ||
                    current.grantKind != GrantKind.TREE.name ||
                    (!identityMatches && current.providerIdentity != target.providerIdentity)
                ) {
                    return@withTransaction null
                }
                if (identityMatches) {
                    refreshAuthorizedSourceInTransaction(
                        current,
                        AuthorizedSourceDraft(
                            grantKind = GrantKind.TREE,
                            adapterKind = adapterKind,
                            contentUri = grant.uri.toString(),
                            providerIdentity = identity,
                            displayLabel = displayLabel,
                        ),
                    )
                } else {
                    rebindAuthorizedSourceInTransaction(
                        current,
                        AuthorizedSourceDraft(
                            grantKind = GrantKind.TREE,
                            adapterKind = adapterKind,
                            contentUri = grant.uri.toString(),
                            providerIdentity = identity,
                            displayLabel = displayLabel,
                        ),
                    )
                }
                sourceDao.setGrantState(sourceId.value, SourceGrantState.READY, clock())
                sourceId
            }.also { refreshed ->
                if (refreshed == null) {
                    releaseUnreferencedGrants(listOf(grant.uri))
                } else if (!identityMatches && previousUri != grant.uri.toString()) {
                    releaseUnreferencedGrants(listOf(previousUri.toUri()))
                }
            }
        } catch (error: Throwable) {
            releaseUnreferencedGrants(listOf(grant.uri))
            throw error
        }
    }

    suspend fun authorizeDocumentSources(selections: List<DocumentSourceSelection>): ImportBatchId =
        requireNotNull(authorizeDocumentBatch(selections)) {
            "At least one document grant must be persisted"
        }.batchId

    suspend fun authorizeDocumentBatch(
        selections: List<DocumentSourceSelection>,
    ): AuthorizedDocumentBatch? {
        val persistedUris = mutableListOf<android.net.Uri>()
        val accepted = mutableListOf<AcceptedDocument>()
        try {
            for (selection in selections) {
                val draft = selection.toDraftOrNull() ?: continue
                when (grants.persist(selection.grant)) {
                    is GrantPersistenceResult.Persisted -> {
                        persistedUris += selection.grant.uri
                        if (canReadDocument(selection.grant.uri.toString())) {
                            accepted += AcceptedDocument(draft, selection.grant.uri)
                        } else {
                            releaseUnreferencedGrants(listOf(selection.grant.uri))
                            persistedUris.remove(selection.grant.uri)
                        }
                    }
                    GrantPersistenceResult.MissingReadGrant,
                    is GrantPersistenceResult.Failed,
                    -> Unit
                }
            }
        } catch (error: Throwable) {
            releaseUnreferencedGrants(persistedUris)
            throw error
        }
        if (accepted.isEmpty()) return null
        return try {
            database.withTransaction {
                val outcomes = accepted.map { acceptedDocument ->
                    upsertAuthorizedSourceOutcomeInTransaction(acceptedDocument.draft)
                }
                val sourceIds = outcomes.map(AuthorizedSourceOutcome::sourceId)
                sourceIds.forEach { sourceId ->
                    sourceDao.setGrantState(sourceId.value, SourceGrantState.READY, clock())
                }
                beforeDocumentBatchCommit()
                val batchId = sourceDao.insertBatch(ImportBatchEntity(createdAtMillis = clock()))
                sourceDao.insertBatchSources(sourceIds.distinct().map { ImportBatchSourceEntity(batchId, it.value) })
                AuthorizedDocumentBatch(
                    batchId = ImportBatchId(batchId),
                    sourceIds = sourceIds.distinct(),
                    rejectedCount = selections.size - accepted.size,
                    freshlyWatchedSourceIds = outcomes
                        .filter(AuthorizedSourceOutcome::freshlyWatched)
                        .map(AuthorizedSourceOutcome::sourceId)
                        .distinct(),
                )
            }
        } catch (error: Throwable) {
            releaseUnreferencedGrants(persistedUris)
            throw error
        }
    }

    suspend fun reauthorizeDocumentSource(
        sourceId: SourceId,
        selection: DocumentSourceSelection,
    ): SourceId? {
        val draft = selection.toDraftOrNull() ?: return null
        val target = sourceDao.source(sourceId.value)
            ?.takeIf {
                it.grantState != SourceGrantState.REMOVED.name &&
                    it.grantKind == GrantKind.DOCUMENT.name
            }
            ?: return null
        val identityMatches = target.providerIdentity == draft.providerIdentity
        val oldUnreadable = !canReadDocument(target.contentUri)
        val needsReauth = target.grantState == SourceGrantState.NEEDS_REAUTHORIZATION.name
        val allowIdentityChange = !identityMatches && (oldUnreadable || needsReauth)
        if (!identityMatches && !allowIdentityChange) return null
        if (allowIdentityChange) {
            val collision = sourceDao.sourceByIdentity(GrantKind.DOCUMENT.name, draft.providerIdentity)
            if (collision != null && collision.id != target.id &&
                collision.grantState != SourceGrantState.REMOVED.name
            ) {
                return null
            }
        }
        if (grants.persist(selection.grant) !is GrantPersistenceResult.Persisted) return null
        val previousUri = target.contentUri
        return try {
            if (!canReadDocument(selection.grant.uri.toString())) {
                releaseUnreferencedGrants(listOf(selection.grant.uri))
                return null
            }
            database.withTransaction {
                val current = sourceDao.source(sourceId.value)
                if (
                    current == null ||
                    current.grantState == SourceGrantState.REMOVED.name ||
                    current.grantKind != GrantKind.DOCUMENT.name ||
                    (!identityMatches && current.providerIdentity != target.providerIdentity)
                ) {
                    return@withTransaction null
                }
                if (identityMatches) {
                    refreshAuthorizedSourceInTransaction(current, draft)
                } else {
                    rebindAuthorizedSourceInTransaction(current, draft)
                }
                sourceDao.setGrantState(sourceId.value, SourceGrantState.READY, clock())
                sourceId
            }.also { refreshed ->
                if (refreshed == null) {
                    releaseUnreferencedGrants(listOf(selection.grant.uri))
                } else if (!identityMatches && previousUri != selection.grant.uri.toString()) {
                    releaseUnreferencedGrants(listOf(previousUri.toUri()))
                }
            }
        } catch (error: Throwable) {
            releaseUnreferencedGrants(listOf(selection.grant.uri))
            throw error
        }
    }

    override suspend fun releaseSource(sourceId: SourceId): SourceRelease {
        val releaseUri = database.withTransaction {
            val source = requireNotNull(sourceDao.source(sourceId.value))
            sourceDao.tombstoneSource(source.id, clock())
            sourceDao.updateLocationStateForSource(source.id, "UNAVAILABLE")
            val activeRunId = scanRunDao.activeRunId(source.id)
            val fencedRunId = activeRunId?.takeIf {
                scanRunDao.requestPauseIfActive(it, clock()) > 0
            }
            ReleaseFence(source.contentUri, fencedRunId, sourceDao.activeSourceReferences(source.contentUri) == 0)
        }
        val outcome = if (!releaseUri.isLastReference) GrantReleaseOutcome.NOT_RELEASED_SHARED
        else if (grants.release(releaseUri.contentUri.toUri())) GrantReleaseOutcome.RELEASED
        else GrantReleaseOutcome.RELEASE_FAILED
        return SourceRelease(sourceId, releaseUri.runId?.let(::ScanRunId), outcome)
    }

    override suspend fun stopWatchingSource(sourceId: SourceId): SourceRelease {
        val fencedRunId = database.withTransaction {
            val source = requireNotNull(sourceDao.source(sourceId.value))
            sourceDao.tombstoneSource(source.id, clock())
            val activeRunId = scanRunDao.activeRunId(source.id)
            activeRunId?.takeIf { scanRunDao.requestPauseIfActive(it, clock()) > 0 }
        }
        return SourceRelease(sourceId, fencedRunId?.let(::ScanRunId), GrantReleaseOutcome.NOT_RELEASED_SHARED)
    }

    override suspend fun restoreStoppedSource(sourceId: SourceId): Boolean {
        val source = sourceDao.source(sourceId.value)
            ?.takeIf { it.grantState == SourceGrantState.REMOVED.name }
            ?: return false
        // SEND-BACK #2 item A (corrects the earlier MEDIUM-4 fix): writing
        // NEEDS_VALIDATION here was wrong -- nothing in-process ever
        // promotes it (validatePendingSources only runs from
        // AppGraph.start), so the restored folder was stuck reading
        // "Waiting for storage" and never rescanned until the app
        // relaunched. Validate synchronously instead, using
        // the same hasGrant + canRead check as validatePendingSources, but
        // scoped to this one source -- still no extra picker trip.
        val contentUri = source.contentUri.toUri()
        val readable = grants.hasReadGrant(contentUri) && try {
            when (GrantKind.valueOf(source.grantKind)) {
                GrantKind.TREE -> canReadTree(source.contentUri)
                GrantKind.DOCUMENT -> canReadDocument(source.contentUri)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        val nextState = if (readable) SourceGrantState.READY else SourceGrantState.NEEDS_REAUTHORIZATION
        return database.withTransaction {
            val current = sourceDao.source(sourceId.value)
            if (current == null || current.grantState != SourceGrantState.REMOVED.name) {
                false
            } else {
                sourceDao.setGrantState(current.id, nextState, clock())
                true
            }
        }
    }

    override suspend fun releasePendingStoppedGrants() {
        val uris = sourceDao.tombstonedSources().map { it.contentUri.toUri() }.distinct()
        releaseUnreferencedGrants(uris)
    }

    override suspend fun revalidateReadyGrants(): List<SourceId> {
        val lost = sourceDao.validationSources()
            .filter { it.grantState == SourceGrantState.READY.name }
            .filter { !grants.hasReadGrant(it.contentUri.toUri()) }
        return lost.mapNotNull { entity ->
            SourceId(entity.id).takeIf {
                sourceDao.setGrantStateIfActive(
                    entity.id,
                    SourceGrantState.NEEDS_REAUTHORIZATION,
                    clock(),
                ) == 1
            }
        }
    }

    override fun observeComicCountsBySource(): Flow<Map<SourceId, Int>> =
        sourceDao.observeComicCountsBySource().map { rows -> rows.associate { SourceId(it.sourceId) to it.comicCount } }

    override fun observeLastCompletedScanBySource(): Flow<Map<SourceId, Long>> =
        scanRunDao.observeLastCompletedScans().map { rows -> rows.associate { SourceId(it.sourceId) to it.completedAtMillis } }

    suspend fun validatePendingSources(
        hasGrant: (String) -> Boolean,
        canRead: suspend (AuthorizedSource) -> Boolean,
    ) {
        val sources = sourceDao.validationSources()
        for (source in sources) {
            val next = if (!hasGrant(source.contentUri)) SourceGrantState.NEEDS_REAUTHORIZATION
            else try {
                if (canRead(project(source))) SourceGrantState.READY else SourceGrantState.NEEDS_REAUTHORIZATION
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) { SourceGrantState.NEEDS_REAUTHORIZATION }
            sourceDao.setGrantStateIfActive(source.id, next, clock())
        }
    }

    private suspend fun project(entity: AuthorizedSourceEntity): AuthorizedSource {
        val grantState = SourceGrantState.valueOf(entity.grantState)
        val run = scanRunDao.latestRunForSource(entity.id)
        val runSummary = run?.toSummary()
        val runFailure = run?.toFailure()
        val state = when (grantState) {
            SourceGrantState.REMOVED -> SourceState.REMOVED
            SourceGrantState.NEEDS_REAUTHORIZATION -> SourceState.NEEDS_REAUTHORIZATION
            SourceGrantState.NEEDS_VALIDATION -> SourceState.NEEDS_VALIDATION
            SourceGrantState.READY -> run?.toSourceState() ?: SourceState.READY
        }
        return AuthorizedSource(
            SourceId(entity.id), GrantKind.valueOf(entity.grantKind), AdapterKind.valueOf(entity.adapterKind),
            entity.contentUri, entity.providerIdentity, entity.displayLabel, grantState, state,
            entity.activeGeneration, entity.completedGeneration, runSummary, runFailure,
        )
    }

    private fun AuthorizedSourceDraft.toEntity(now: Long) = AuthorizedSourceEntity(
        grantKind = grantKind.name, adapterKind = adapterKind.name, contentUri = contentUri,
        providerIdentity = providerIdentity, displayLabel = displayLabel, grantState = SourceGrantState.NEEDS_VALIDATION.name,
        activeGeneration = 0, completedGeneration = null, createdAtMillis = now, updatedAtMillis = now,
    )

    private suspend fun upsertAuthorizedSourceInTransaction(draft: AuthorizedSourceDraft): SourceId =
        upsertAuthorizedSourceOutcomeInTransaction(draft).sourceId

    /**
     * A30 §2.1 (S4b/H3): also reports whether this folder started being watched *now* -- a
     * brand new row, or a tombstoned one the user brought back through the picker. Re-picking
     * a folder that is already watched merely refreshes it, and must not be announced as an
     * import (its "Undo" would stop watching a folder the user never asked to add).
     */
    private suspend fun upsertAuthorizedSourceOutcomeInTransaction(
        draft: AuthorizedSourceDraft,
    ): AuthorizedSourceOutcome {
        val existing = sourceDao.sourceByIdentity(draft.grantKind.name, draft.providerIdentity)
        if (existing == null) {
            return AuthorizedSourceOutcome(
                SourceId(sourceDao.insertSource(draft.toEntity(clock()))),
                freshlyWatched = true,
            )
        }
        val revived = existing.grantState == SourceGrantState.REMOVED.name
        return AuthorizedSourceOutcome(
            refreshAuthorizedSourceInTransaction(existing, draft),
            freshlyWatched = revived,
        )
    }

    private suspend fun refreshAuthorizedSourceInTransaction(
        existing: AuthorizedSourceEntity,
        draft: AuthorizedSourceDraft,
    ): SourceId {
        if (existing.adapterKind != draft.adapterKind.name) {
            scanRunDao.activeRunId(existing.id)?.let { activeRunId ->
                check(
                    scanRunDao.failActiveRunForAdapterChange(
                        sourceId = existing.id,
                        runId = activeRunId,
                        updatedAtMillis = clock(),
                    ) == 1,
                ) {
                    "The active scan generation could not be fenced before changing its adapter."
                }
                scanRunDao.clearAnyWorkRequest(activeRunId)
                scanRunDao.clearActiveRun(existing.id, activeRunId)
            }
        }
        sourceDao.refreshAuthorizedSource(
            existing.id,
            draft.contentUri,
            draft.adapterKind.name,
            draft.displayLabel,
            clock(),
        )
        return SourceId(existing.id)
    }

    private suspend fun rebindAuthorizedSourceInTransaction(
        existing: AuthorizedSourceEntity,
        draft: AuthorizedSourceDraft,
    ): SourceId {
        if (existing.adapterKind != draft.adapterKind.name) {
            scanRunDao.activeRunId(existing.id)?.let { activeRunId ->
                check(
                    scanRunDao.failActiveRunForAdapterChange(
                        sourceId = existing.id,
                        runId = activeRunId,
                        updatedAtMillis = clock(),
                    ) == 1,
                ) {
                    "The active scan generation could not be fenced before changing its adapter."
                }
                scanRunDao.clearAnyWorkRequest(activeRunId)
                scanRunDao.clearActiveRun(existing.id, activeRunId)
            }
        }
        sourceDao.rebindAuthorizedSource(
            existing.id,
            draft.contentUri,
            draft.providerIdentity,
            draft.adapterKind.name,
            draft.displayLabel,
            clock(),
        )
        return SourceId(existing.id)
    }

    private suspend fun releaseUnreferencedGrants(uris: List<android.net.Uri>) = withContext(NonCancellable) {
        uris.distinct().forEach { uri ->
            if (sourceDao.activeSourceReferences(uri.toString()) == 0) grants.release(uri)
        }
    }

    private fun ScanRunEntity.toSummary() = RunSummary(discoveredCount, highConfidenceCount, lowConfidenceCount, incompleteCount, rejectedCount, selectedCount, committedCount, failedCount)
    private fun ScanRunEntity.toFailure(): RunFailure? = problem?.let { RunFailure(com.easemic.app.model.RunProblem.valueOf(it), com.easemic.app.model.RecoveryAction.valueOf(recoveryAction)) }
    private fun ScanRunEntity.toSourceState() = when (state) {
        "ENQUEUED", "RUNNING" -> SourceState.DISCOVERING
        "IMPORTING" -> SourceState.IMPORTING
        "WAITING_FOR_REVIEW" -> SourceState.READY_FOR_REVIEW
        "PAUSE_REQUESTED", "PAUSED" -> SourceState.PAUSED
        "COMPLETE" -> SourceState.COMPLETE
        "PARTIAL" -> SourceState.PARTIAL
        "FAILED" -> SourceState.FAILED
        else -> SourceState.FAILED
    }

    private data class ReleaseFence(val contentUri: String, val runId: String?, val isLastReference: Boolean)
    private data class AcceptedDocument(val draft: AuthorizedSourceDraft, val uri: android.net.Uri)
}

data class DocumentSourceSelection(
    val grant: GrantedUri,
    val adapterKind: AdapterKind,
    val displayLabel: String,
) {
    fun toDraftOrNull(): AuthorizedSourceDraft? = SafProviderIdentity.document(grant.uri.toString())?.let { identity ->
        AuthorizedSourceDraft(
            grantKind = GrantKind.DOCUMENT,
            adapterKind = adapterKind,
            contentUri = grant.uri.toString(),
            providerIdentity = identity,
            displayLabel = displayLabel,
        )
    }
}

data class AuthorizedDocumentBatch(
    val batchId: ImportBatchId,
    val sourceIds: List<SourceId>,
    val rejectedCount: Int,
    /** S4b/H3: the subset that started being watched now (new rows and revived tombstones). */
    val freshlyWatchedSourceIds: List<SourceId> = emptyList(),
)

/** A30 §2.1 (S4b/H3): an authorized folder, and whether the picker just started watching it. */
data class AuthorizedSourceOutcome(
    val sourceId: SourceId,
    val freshlyWatched: Boolean,
)
