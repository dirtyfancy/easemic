package com.easemic.app.data.room

import androidx.room.withTransaction
import com.easemic.app.data.DownloadLibraryAvailability
import com.easemic.app.data.LegacyCatalogCompatibility
import com.easemic.app.data.LegacySourceValidationTarget
import com.easemic.app.data.LibraryRepository
import com.easemic.app.data.LocalDetailRow
import com.easemic.app.data.SerializedSeriesLibrary
import com.easemic.app.data.SeriesReconcileOutcome
import com.easemic.app.feature.download.DownloadCommitCompleteness
import com.easemic.app.feature.download.DownloadCommitMetadata
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.downloadOwnershipKey
import com.easemic.app.feature.download.parseDownloadOwnershipKey
import com.easemic.app.feature.download.parseSerializedOwnershipKey
import com.easemic.app.feature.library.ChapterShelfRow
import com.easemic.app.feature.library.ExistingChapterUnit
import com.easemic.app.feature.library.planChapterReconciliation
import com.easemic.app.feature.library.SeriesShelfRow
import com.easemic.app.feature.library.ShelfVisibility
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.SeriesDetailSnapshot
import com.easemic.app.feature.source.SeriesPublicationStatus
import com.easemic.app.feature.source.over
import com.easemic.app.feature.source.mergeSeriesSnapshot
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.asSeriesIdentityV2
import com.easemic.app.feature.importing.SerializedSourceCoverFiles
import com.easemic.app.model.DocumentRef
import com.easemic.app.model.ImportCandidate
import com.easemic.app.model.LibraryItem
import com.easemic.app.model.LibraryItemId
import com.easemic.app.model.LocationKind
import com.easemic.app.model.LocationId
import com.easemic.app.model.MediaRevisionId
import com.easemic.app.model.PageAccessTarget
import com.easemic.app.model.PageLocatorKind
import com.easemic.app.model.PageRef
import com.easemic.app.model.ReadingUnitId
import com.easemic.app.model.ShelfDownloadState
import com.easemic.app.model.SourceGrantState
import com.easemic.app.model.SourceId
import com.easemic.app.model.GrantKind
import com.easemic.app.model.LocationState
import com.easemic.app.storage.SafProviderIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray

data class SerializedSeriesCoverCandidate(
    val identity: SeriesIdentityV2,
    val coverLocator: String,
)

class RoomLibraryRepository(
    private val database: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sourceCoverFiles: SerializedSourceCoverFiles? = null,
) : LibraryRepository, LegacyCatalogCompatibility, DownloadLibraryAvailability, SerializedSeriesLibrary {
    private val dao = database.libraryDao()
    private val sourceDao = database.sourceDao()

    companion object {
        const val DOWNLOAD_PROVENANCE_NAMESPACE = "gallery_identity_v1"
        const val DOWNLOAD_OWNERSHIP_CLAIM = "ownership"
        const val DOWNLOAD_REVISION_CLAIM = "download_revision"
        private val SERIALIZED_SOURCE_COVER_PROVIDERS = setOf("zaimanhua", "jm")
        /** Distinct from SAF TREE imports — never merges by contentUri alone. */
        const val DOWNLOAD_GRANT_KIND = "TREE"
        const val DOWNLOAD_ADAPTER_KIND = "CATALOG_DOWNLOAD"
        private val VALID_CHAPTER_SOURCE_STATES = setOf("ACTIVE", "SOURCE_PERMISSION", "SOURCE_REMOVED")
    }

    override fun observeLibrary(): Flow<List<LibraryItem>> =
        combine(
            dao.observeLibraryRows(),
            sourceDao.observeHiddenSourceIds(),
            sourceCoverFiles?.epoch ?: flowOf(0L),
        ) { rows, hiddenSourceIds, _ ->
            val hidden = hiddenSourceIds.mapTo(mutableSetOf(), ::SourceId)
            rows.mapNotNull { row ->
                row.toLibraryItem(hidden)?.takeIf { item -> ShelfVisibility.isVisible(item, hidden) }
            }
        }

    /**
     * W3 (A38): the shelf's projection read — one flat [ShelfRowEntity] per
     * comic (page count in SQL, only the cover page's locator, download-badge
     * fact precomputed), mapped off the main thread, deduped, and re-emitted
     * reactively when pages, claims, metadata, or hidden sources change.
     * Detail and the Reader keep reading the full graph through
     * [observeItem]/[observeItemDetail].
     */
    override fun observeShelf(): Flow<List<LibraryItem>> =
        combine(
            dao.observeShelfRows(),
            sourceDao.observeHiddenSourceIds(),
            sourceCoverFiles?.epoch ?: flowOf(0L),
        ) { rows, hiddenSourceIds, _ ->
            val hidden = hiddenSourceIds.mapTo(mutableSetOf(), ::SourceId)
            rows.mapNotNull { row -> row.toShelfItem(hidden) }
                .filter { item -> ShelfVisibility.isVisible(item, hidden) }
        }.flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    /** Unfiltered: backup/restore must see UNAVAILABLE/REMOVED-source rows too (review MEDIUM-1). */
    override fun observeAllItems(): Flow<List<LibraryItem>> =
        dao.observeLibraryRows().map { rows -> rows.mapNotNull { it.toLibraryItem() } }

    /**
     * A30 §2.2 (S4b/G2): Detail and the Reader read the item through here, so the folder's
     * access state has to travel with it -- the deleted startup availability sweep used to
     * (destructively) provide that by rewriting location rows. Joining the hidden-source
     * stream gives the same gating with no write at all.
     */
    override fun observeItem(itemId: LibraryItemId): Flow<LibraryItem?> =
        combine(
            dao.observeItemRow(itemId.value),
            sourceDao.observeHiddenSourceIds(),
        ) { row, hiddenSourceIds ->
            row?.toLibraryItem(hiddenSourceIds.mapTo(mutableSetOf(), ::SourceId))
        }

    override fun observeItemAtUnit(
        itemId: LibraryItemId,
        readingUnitId: ReadingUnitId,
    ): Flow<LibraryItem?> = combine(
        dao.observeItemRow(itemId.value),
        sourceDao.observeHiddenSourceIds(),
    ) { row, hiddenSourceIds ->
        row?.toLibraryItem(
            hiddenSourceIds = hiddenSourceIds.mapTo(mutableSetOf(), ::SourceId),
            selectedReadingUnitId = readingUnitId.value,
        )
    }

    override suspend fun seriesShelfOnce(itemId: Long): SeriesShelfRow? =
        dao.seriesShelfOnce(itemId)

    override suspend fun chapterShelfRowsForItem(itemId: Long): List<ChapterShelfRow> =
        dao.chapterShelfRowsForItem(itemId)

    override fun observeChapterShelfRowsForItem(itemId: Long): Flow<List<ChapterShelfRow>> =
        dao.observeChapterShelfRowsForItem(itemId)

    override suspend fun activeReadingUnitsForItem(itemId: Long): List<ReadingUnitEntity> =
        dao.activeReadingUnitsForItem(itemId)

    /** One relation query for archive export; callers select each unit from this snapshot. */
    suspend fun archiveItemGraphOnce(itemId: LibraryItemId): LibraryItemGraph? =
        dao.itemGraph(itemId.value)

    /**
     * A32 §1: same row, same DAO query and the same hidden-source join as [observeItem]
     * (so access loss still travels with the row), plus the current metadata snapshot.
     * Returns null whenever [toLibraryItem] would (no current metadata, no current
     * revision, no location) so Detail's "no longer in your library" branch stays exactly
     * as truthful as before.
     */
    override fun observeItemDetail(itemId: LibraryItemId): Flow<LocalDetailRow?> =
        combine(
            dao.observeItemRow(itemId.value),
            sourceDao.observeHiddenSourceIds(),
        ) { row, hiddenSourceIds ->
            row?.toLocalDetailRow(hiddenSourceIds.mapTo(mutableSetOf(), ::SourceId))
        }

    override suspend fun resolvePageAccessTarget(page: PageRef): PageAccessTarget? =
        dao.pageAccessTarget(
            sourceId = page.sourceId.value,
            locationId = page.locationId.value,
            revisionId = page.revisionId.value,
            ordinal = page.ordinal,
            locatorKind = page.locatorKind.name,
            documentUri = page.documentUri,
            archiveEntryPath = page.archiveEntryPath,
        )?.toPageAccessTarget()

    override suspend fun updateProgressIfCurrent(
        readingUnitId: ReadingUnitId,
        revisionId: MediaRevisionId,
        locationId: LocationId,
        ordinal: Int,
    ): Boolean = database.withTransaction {
        val pageCount = dao.pageCountForRevision(revisionId.value)
        pageCount > 0 &&
            dao.updateProgressIfCurrent(
                readingUnitId = readingUnitId.value,
                revisionId = revisionId.value,
                locationId = locationId.value,
                ordinal = ordinal.coerceIn(0, pageCount - 1),
            ) == 1
    }

    override suspend fun updateChapterProgressIfCurrent(
        readingUnitId: ReadingUnitId,
        revisionId: MediaRevisionId,
        locationId: LocationId,
        ordinal: Int,
        completed: Boolean,
        lastReadAtMillis: Long,
    ): Boolean = database.withTransaction {
        val pageCount = dao.pageCountForRevision(revisionId.value)
        pageCount > 0 &&
            dao.updateChapterProgressIfCurrent(
                readingUnitId = readingUnitId.value,
                revisionId = revisionId.value,
                locationId = locationId.value,
                ordinal = ordinal.coerceIn(0, pageCount - 1),
                completed = completed,
                lastReadAtMillis = lastReadAtMillis,
            ) == 1
    }

    override suspend fun setReadingProgress(readingUnitId: ReadingUnitId, ordinal: Int) {
        if (dao.readingUnitById(readingUnitId.value) == null) return
        val pageCount = dao.pageCountForReadingUnit(readingUnitId.value).coerceAtLeast(1)
        dao.updateProgress(
            readingUnitId = readingUnitId.value,
            ordinal = ordinal.coerceIn(0, pageCount - 1),
        )
    }

    override suspend fun setChapterProgress(
        readingUnitId: ReadingUnitId,
        ordinal: Int,
        completed: Boolean,
        lastReadAtMillis: Long,
    ) {
        if (dao.readingUnitById(readingUnitId.value) == null) return
        val pageCount = dao.pageCountForReadingUnit(readingUnitId.value)
        dao.updateChapterProgress(
            readingUnitId = readingUnitId.value,
            // A collection-only archive can carry a followed chapter's cursor without its page bytes. Keep that raw
            // cursor until the chapter is downloaded; a zero-page unit has no upper bound to clamp against.
            ordinal = if (pageCount > 0) ordinal.coerceIn(0, pageCount - 1) else ordinal.coerceAtLeast(0),
            completed = completed,
            lastReadAtMillis = lastReadAtMillis.coerceAtLeast(0L),
        )
    }

    override suspend fun setChapterSourceState(readingUnitId: ReadingUnitId, sourceState: String) {
        if (dao.readingUnitById(readingUnitId.value) == null) return
        if (sourceState !in VALID_CHAPTER_SOURCE_STATES) return
        dao.updateChapterSourceState(readingUnitId.value, sourceState)
    }

    override suspend fun markLocationUnavailable(locationId: LocationId) =
        database.withTransaction {
            dao.markLocationUnavailable(locationId.value)
        }

    override suspend fun markSourceAccessLostIfCurrent(
        readingUnitId: ReadingUnitId,
        revisionId: MediaRevisionId,
        locationId: LocationId,
    ): Boolean = database.withTransaction {
        val sourceId = dao.sourceIdForCurrentLocation(
            readingUnitId = readingUnitId.value,
            revisionId = revisionId.value,
            locationId = locationId.value,
        ) ?: return@withTransaction false
        sourceDao.requireReauthorizationIfActive(
            sourceId = sourceId,
            updatedAtMillis = clock(),
        ) == 1
    }

    override suspend fun markLocationMissingIfCurrent(
        readingUnitId: ReadingUnitId,
        revisionId: MediaRevisionId,
        locationId: LocationId,
    ): Boolean = database.withTransaction {
        dao.markLocationMissingIfCurrent(
            readingUnitId = readingUnitId.value,
            revisionId = revisionId.value,
            locationId = locationId.value,
        ) == 1
    }

    override suspend fun markLocationUnavailableIfCurrent(
        readingUnitId: ReadingUnitId,
        revisionId: MediaRevisionId,
        locationId: LocationId,
        requireSourceReauthorization: Boolean,
    ): Boolean = database.withTransaction {
        val sourceId = if (requireSourceReauthorization) {
            dao.sourceIdForCurrentLocation(
                readingUnitId = readingUnitId.value,
                revisionId = revisionId.value,
                locationId = locationId.value,
            ) ?: return@withTransaction false
        } else {
            null
        }
        val updated = dao.markLocationUnavailableIfCurrent(
            readingUnitId = readingUnitId.value,
            revisionId = revisionId.value,
            locationId = locationId.value,
        )
        if (updated != 1) {
            return@withTransaction false
        }
        if (sourceId != null) {
            check(
                database.sourceDao().requireReauthorizationIfActive(
                sourceId = sourceId,
                updatedAtMillis = clock(),
                ) == 1,
            ) {
                "Current active source disappeared inside the reader failure transaction."
            }
        }
        true
    }

    override suspend fun downloadAcquisitionKeys(): Map<Long, String> {
        val rows = dao.ownershipClaims(
            namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
            claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
        )
        return downloadAcquisitionKeysFrom(rows.associate { it.itemId to it.claimValue })
    }

    /** Existing serialized follows whose source cover has no valid managed file. */
    suspend fun serializedSeriesCoverCandidates(): List<SerializedSeriesCoverCandidate> =
        dao.serializedSeriesCoverCandidates().mapNotNull { row ->
            val identity = parseSerializedOwnershipKey(row.ownership)?.asSeriesIdentityV2()
                ?: return@mapNotNull null
            if (identity.providerNamespace !in SERIALIZED_SOURCE_COVER_PROVIDERS) return@mapNotNull null
            if (sourceCoverFiles?.uriIfValid(identity) != null) return@mapNotNull null
            val locator = row.coverLocator?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SerializedSeriesCoverCandidate(identity, locator)
        }

    fun serializedSourceCoverUri(identity: SeriesIdentityV2): String? =
        sourceCoverFiles?.uriIfValid(identity)

    /**
     * W3 (A38): the same key derivation, driven by the provenance-claims
     * Flow — a collect/upload/dedup claim write re-emits the shelf badges
     * without any polling.
     */
    override fun observeDownloadAcquisitionKeys(): Flow<Map<Long, String>> =
        dao.observeOwnershipClaims(
            namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
            claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
        ).map { rows -> downloadAcquisitionKeysFrom(rows.associate { it.itemId to it.claimValue }) }
            .distinctUntilChanged()

    override fun observeSerializedSeriesLedgerKeys(): Flow<Map<Long, String>> =
        dao.observeOwnershipClaims(
            namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
            claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
        ).map { rows ->
            serializedSeriesLedgerKeysFrom(rows.associate { it.itemId to it.claimValue })
        }.distinctUntilChanged()

    override suspend fun reconcileSeries(
        series: GalleryIdentityV1,
        title: String,
        coverLocator: String?,
        chapters: ChapterListResult,
    ): SeriesReconcileOutcome = database.withTransaction {
        require(chapters.chapters.all { it.identity.series == series.asSeriesIdentityV2() }) {
            "Serialized chapter identity does not match its series."
        }
        val ownership = downloadOwnershipKey(series)
        val now = clock()
        val existingItemId = dao.itemIdByClaim(
            namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
            claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
            claimValue = ownership,
        )
        val itemId = existingItemId ?: dao.insertItem(
            LibraryItemEntity(
                historicalAvailable = true,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        if (existingItemId == null) {
            dao.insertClaims(
                listOf(
                    ProvenanceClaimEntity(
                        itemId = itemId,
                        namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
                        claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
                        claimValue = ownership,
                    ),
                ),
            )
        }
        val oldMetadata = dao.currentMetadataForItem(itemId)
        val mergedSnapshot = mergeSeriesSnapshot(oldMetadata?.toSeriesDetailSnapshot(), chapters)
        val metadataId = dao.insertMetadata(
            mergedSnapshot.toMetadataEntity(
                itemId = itemId,
                old = oldMetadata,
                fallbackTitle = title,
                fallbackCover = coverLocator,
                now = now,
            ),
        )
        dao.setCurrentMetadata(CurrentMetadataEntity(itemId, metadataId))

        val existing = dao.readingUnitsForItem(itemId)
        val existingUnits = existing.map { unit ->
            ExistingChapterUnit(
                readingUnitId = unit.id,
                sourceChapterId = unit.sourceChapterId,
                ordinal = unit.ordinal,
                label = unit.label,
                sourceGroup = unit.sourceGroup,
                sourceState = unit.sourceState,
                sourceUpdatedAtMillis = unit.sourceUpdatedAtMillis,
            )
        }
        val plan = planChapterReconciliation(existingUnits, chapters)

        // Ordinals are unique per item. Stage every existing row first so a
        // reorder can never transiently violate the (itemId, ordinal) index.
        dao.stageChapterOrdinals(itemId)
        plan.units.forEach { unit ->
            if (unit.readingUnitId != null) {
                dao.updateChapter(
                    readingUnitId = unit.readingUnitId,
                    label = unit.label,
                    ordinal = unit.ordinal,
                    sourceGroup = unit.sourceGroup,
                    sourceState = unit.sourceState,
                    sourceUpdatedAtMillis = unit.sourceUpdatedAtMillis,
                )
            } else {
                dao.insertReadingUnit(
                    ReadingUnitEntity(
                        itemId = itemId,
                        label = unit.label,
                        ordinal = unit.ordinal,
                        progressOrdinal = 0,
                        state = "READY",
                        sourceChapterId = unit.sourceChapterId,
                        sourceGroup = unit.sourceGroup,
                        sourceState = unit.sourceState,
                        sourceUpdatedAtMillis = unit.sourceUpdatedAtMillis,
                    ),
                )
            }
        }
        dao.refreshLegacyItem(itemId, now)
        SeriesReconcileOutcome(itemId = itemId, plan = plan)
    }

    override suspend fun updateSeriesDetailSnapshot(
        series: SeriesIdentityV2,
        result: ChapterListResult,
    ) {
        require(result.chapters.all { it.identity.series == series }) {
            "Serialized chapter identity does not match its series."
        }
        database.withTransaction {
            val ownership = downloadOwnershipKey(
                GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId),
            )
            val itemId = dao.itemIdByClaim(
                namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
                claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
                claimValue = ownership,
            ) ?: return@withTransaction
            val oldMetadata = dao.currentMetadataForItem(itemId)
            val merged = mergeSeriesSnapshot(oldMetadata?.toSeriesDetailSnapshot(), result)
            val metadataId = dao.insertMetadata(
                merged.toMetadataEntity(
                    itemId = itemId,
                    old = oldMetadata,
                    fallbackTitle = oldMetadata?.title.orEmpty(),
                    fallbackCover = oldMetadata?.coverLocator,
                    now = clock(),
                ),
            )
            dao.setCurrentMetadata(CurrentMetadataEntity(itemId, metadataId))
            dao.refreshLegacyItem(itemId, clock())
        }
    }

    override suspend fun seriesDetailSnapshot(series: SeriesIdentityV2): SeriesDetailSnapshot? {
        val ownership = downloadOwnershipKey(
            GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId),
        )
        return dao.currentMetadataForOwnership(ownership)?.toSeriesDetailSnapshot()
    }

    override suspend fun restoreSeriesDetailSnapshot(
        series: SeriesIdentityV2,
        snapshot: SeriesDetailSnapshot,
        fallbackTitle: String,
        fallbackCover: String?,
    ) {
        database.withTransaction {
            val ownership = downloadOwnershipKey(
                GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId),
            )
            val itemId = dao.itemIdByClaim(
                namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
                claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
                claimValue = ownership,
            ) ?: return@withTransaction
            val old = dao.currentMetadataForItem(itemId)
            val existing = old?.toSeriesDetailSnapshot()
            if (existing != null && snapshot.observedAtMillis < existing.observedAtMillis) return@withTransaction
            val merged = if (existing == null) snapshot else snapshot.copy(
                metadata = snapshot.metadata.over(existing.metadata),
                status = snapshot.status.takeIf { snapshot.status != SeriesPublicationStatus.UNKNOWN } ?: existing.status,
            )
            val id = dao.insertMetadata(merged.toMetadataEntity(itemId, old, fallbackTitle, fallbackCover, clock()))
            dao.setCurrentMetadata(CurrentMetadataEntity(itemId, id))
        }
    }

    fun observeSeriesDetailSnapshot(series: SeriesIdentityV2): Flow<SeriesDetailSnapshot?> {
        val ownership = downloadOwnershipKey(
            GalleryIdentityV1(series.providerNamespace, series.canonicalSeriesId),
        )
        return dao.observeCurrentMetadataForOwnership(ownership).map { it?.toSeriesDetailSnapshot() }
    }

    override suspend fun upsertLegacyDirectory(candidate: ImportCandidate): LibraryItemId =
        database.withTransaction {
            require(candidate.pages.isNotEmpty()) { "A catalog item needs at least one page." }
            val identity = SafProviderIdentity.treeIdentity(candidate.sourceTreeUri)
            val providerIdentity = identity?.providerIdentity
                ?: SafProviderIdentity.legacyTree(candidate.sourceTreeUri)
            val providerDocumentId = identity?.treeDocumentId ?: providerIdentity
            val now = System.currentTimeMillis()
            val existingSource = sourceDao.sourceByIdentity("TREE", providerIdentity)
            val sourceId = existingSource?.id ?: sourceDao.insertSource(
                AuthorizedSourceEntity(
                    grantKind = "TREE",
                    adapterKind = "GENERIC_DIRECTORY",
                    contentUri = candidate.sourceTreeUri,
                    providerIdentity = providerIdentity,
                    displayLabel = candidate.sourceDisplayName,
                    grantState = "READY",
                    activeGeneration = 0,
                    completedGeneration = null,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                ),
            )
            existingSource?.let {
                sourceDao.refreshLegacySource(
                    sourceId = it.id,
                    contentUri = candidate.sourceTreeUri,
                    displayLabel = candidate.sourceDisplayName,
                    updatedAtMillis = now,
                )
            }

            val existingLocation = sourceDao.locationByIdentity(sourceId, providerDocumentId)
            val locationId = existingLocation?.id ?: sourceDao.insertLocation(
                ComicLocationEntity(
                    sourceId = sourceId,
                    providerDocumentId = providerDocumentId,
                    documentUri = candidate.sourceTreeUri,
                    locationKind = "DIRECTORY",
                    relativeDisplayPath = candidate.sourceDisplayName,
                    structuralSignature = null,
                    lastCompleteGeneration = null,
                    state = "PRESENT",
                ),
            )
            existingLocation?.let {
                sourceDao.restoreLegacyLocation(
                    locationId = it.id,
                    documentUri = candidate.sourceTreeUri,
                    relativeDisplayPath = candidate.sourceDisplayName,
                )
            }

            val existingItemId = dao.itemIdsByLocation(locationId).singleOrNull()
            val itemId = existingItemId ?: dao.insertItem(
                LibraryItemEntity(
                    historicalAvailable = true,
                    createdAtMillis = now,
                    updatedAtMillis = now,
                ),
            )
            val readingUnit = existingItemId?.let { dao.readingUnitForItem(it) }
            val progress = (readingUnit?.progressOrdinal ?: 0).coerceIn(candidate.pages.indices)
            dao.refreshLegacyItem(itemId, now)

            dao.deleteCurrentMetadata(itemId)
            val metadataId = dao.insertMetadata(
                MetadataSnapshotEntity(
                    itemId = itemId,
                    title = candidate.metadata.title,
                    series = candidate.metadata.series,
                    alternateSeries = candidate.metadata.alternateSeries,
                    creators = candidate.metadata.creators.toJson(),
                    genres = candidate.metadata.genres.toJson(),
                    tags = candidate.metadata.tags.toJson(),
                    language = candidate.metadata.language,
                    expectedPageCount = candidate.pages.size,
                    createdAtMillis = now,
                ),
            )
            dao.setCurrentMetadata(CurrentMetadataEntity(itemId, metadataId))

            val readingUnitId = readingUnit?.id ?: dao.insertReadingUnit(
                ReadingUnitEntity(
                    itemId = itemId,
                    label = candidate.sourceDisplayName,
                    ordinal = 0,
                    progressOrdinal = progress,
                    state = "READY",
                ),
            )
            readingUnit?.let {
                dao.refreshLegacyReadingUnit(
                    readingUnitId = it.id,
                    label = candidate.sourceDisplayName,
                    progressOrdinal = progress,
                )
                dao.deleteCurrentRevision(it.id)
            }
            val structuralSignature = candidate.pages.joinToString(
                prefix = "legacy-directory:",
                separator = "\u001f",
            ) { it.documentId }
            val revisionId = dao.revisionIdBySignature(readingUnitId, structuralSignature)
                ?: dao.insertRevision(
                    MediaRevisionEntity(
                        readingUnitId = readingUnitId,
                        locationId = locationId,
                        structuralSignature = structuralSignature,
                        createdAtMillis = now,
                    ),
                )
            dao.setCurrentRevision(CurrentMediaRevisionEntity(readingUnitId, revisionId))
            if (dao.pageCountForRevision(revisionId) == 0) {
                dao.insertPages(
                    candidate.pages.mapIndexed { ordinal, page ->
                        PageEntity(
                            revisionId = revisionId,
                            ordinal = ordinal,
                            locatorKind = "DOCUMENT",
                            documentId = page.documentId,
                            documentUri = page.uri,
                            archiveEntryPath = null,
                            displayName = page.displayName,
                            mimeType = page.mimeType,
                            size = null,
                            lastModifiedMillis = null,
                            width = 0,
                            height = 0,
                        )
                    },
                )
            }
            LibraryItemId(itemId)
        }

    override suspend fun commitDownloadLocalAvailability(
        identity: GalleryIdentityV1,
        downloadLocationUri: String,
        downloadRevision: Int,
        title: String,
        pages: List<DocumentRef>,
        metadata: DownloadCommitMetadata,
        completeness: DownloadCommitCompleteness,
    ): LibraryItemId = database.withTransaction {
        require(pages.isNotEmpty()) { "Download commit requires at least one READY page." }
        val ownership = downloadOwnershipKey(identity)
        val providerDocumentId = ownership
        val now = clock()
        val existingSource = sourceDao.sourceByIdentity(DOWNLOAD_GRANT_KIND, ownership)
        val sourceId = existingSource?.id ?: sourceDao.insertSource(
            AuthorizedSourceEntity(
                grantKind = DOWNLOAD_GRANT_KIND,
                adapterKind = DOWNLOAD_ADAPTER_KIND,
                contentUri = downloadLocationUri,
                providerIdentity = ownership,
                displayLabel = title,
                grantState = "READY",
                activeGeneration = 0,
                completedGeneration = null,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        existingSource?.let {
            sourceDao.refreshLegacySource(
                sourceId = it.id,
                contentUri = downloadLocationUri,
                displayLabel = title,
                updatedAtMillis = now,
            )
        }

        val existingLocation = sourceDao.locationByIdentity(sourceId, providerDocumentId)
        val locationId = existingLocation?.id ?: sourceDao.insertLocation(
            ComicLocationEntity(
                sourceId = sourceId,
                providerDocumentId = providerDocumentId,
                documentUri = downloadLocationUri,
                locationKind = "DIRECTORY",
                relativeDisplayPath = title,
                structuralSignature = null,
                lastCompleteGeneration = null,
                // A36: INCOMPLETE for a truncated seal. Still on the shelf
                // (ShelfVisibility / LibraryItem.isAvailable admit it) and
                // still readable — just badged, and honest about being short.
                state = completeness.locationState,
            ),
        )
        existingLocation?.let {
            sourceDao.restoreLegacyLocationWithState(
                locationId = it.id,
                documentUri = downloadLocationUri,
                relativeDisplayPath = title,
                state = completeness.locationState,
            )
        }

        val existingItemId = dao.itemIdByClaim(
            namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
            claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
            claimValue = ownership,
        ) ?: dao.itemIdsByLocation(locationId).singleOrNull()

        val itemId = existingItemId ?: dao.insertItem(
            LibraryItemEntity(
                historicalAvailable = true,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        val readingUnit = existingItemId?.let { dao.readingUnitForItem(it) }
        val progress = (readingUnit?.progressOrdinal ?: 0).coerceIn(pages.indices)
        dao.refreshLegacyItem(itemId, now)

        // A32 §2: what this FINISH knows, falling back to what the row already
        // has when it knows nothing (a re-download of a released cloud row
        // commits from a locator with no catalog facts).
        val columns = metadata.commitColumns(
            existing = existingItemId?.let { dao.currentMetadataForItem(it) },
        )
        dao.deleteCurrentMetadata(itemId)
        val metadataId = dao.insertMetadata(
            MetadataSnapshotEntity(
                itemId = itemId,
                title = title,
                series = null,
                alternateSeries = null,
                // A32 §2: no longer hard-coded empty — these are the upstream
                // strings the enqueue site already had, carried through the
                // locator. Nothing is derived that was not persisted.
                creators = columns.creators,
                genres = columns.genres,
                tags = columns.tags,
                language = columns.language,
                // A36: a truncated seal keeps the count pinned at enqueue, so
                // the row says "22 of 43" rather than agreeing that 22 was all
                // there ever was.
                expectedPageCount = completeness.pinnedPageCount ?: pages.size,
                createdAtMillis = now,
            ),
        )
        dao.setCurrentMetadata(CurrentMetadataEntity(itemId, metadataId))

        val readingUnitId = readingUnit?.id ?: dao.insertReadingUnit(
            ReadingUnitEntity(
                itemId = itemId,
                label = title,
                ordinal = 0,
                progressOrdinal = progress,
                state = "READY",
            ),
        )
        readingUnit?.let {
            dao.refreshLegacyReadingUnit(
                readingUnitId = it.id,
                label = title,
                progressOrdinal = progress,
            )
            dao.deleteCurrentRevision(it.id)
        }

        val structuralSignature = "download:" + pages.joinToString("\u001f") { it.documentId } +
            ":r$downloadRevision"
        val revisionId = dao.revisionIdBySignature(readingUnitId, structuralSignature)
            ?: dao.insertRevision(
                MediaRevisionEntity(
                    readingUnitId = readingUnitId,
                    locationId = locationId,
                    structuralSignature = structuralSignature,
                    createdAtMillis = now,
                ),
            )
        dao.setCurrentRevision(CurrentMediaRevisionEntity(readingUnitId, revisionId))
        if (dao.pageCountForRevision(revisionId) == 0) {
            dao.insertPages(
                pages.mapIndexed { ordinal, page ->
                    PageEntity(
                        revisionId = revisionId,
                        ordinal = ordinal,
                        locatorKind = "DOCUMENT",
                        documentId = page.documentId,
                        documentUri = page.uri,
                        archiveEntryPath = null,
                        displayName = page.displayName,
                        mimeType = page.mimeType,
                        size = null,
                        lastModifiedMillis = null,
                        width = 0,
                        height = 0,
                    )
                },
            )
        }

        dao.insertClaims(
            listOf(
                ProvenanceClaimEntity(
                    itemId = itemId,
                    namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
                    claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
                    claimValue = ownership,
                ),
                ProvenanceClaimEntity(
                    itemId = itemId,
                    namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
                    claimKey = DOWNLOAD_REVISION_CLAIM,
                    claimValue = downloadRevision.toString(),
                ),
            ),
        )
        LibraryItemId(itemId)
    }

    override suspend fun commitChapterDownloadLocalAvailability(
        series: GalleryIdentityV1,
        chapterId: String,
        downloadLocationUri: String,
        downloadRevision: Int,
        title: String,
        pages: List<DocumentRef>,
        completeness: DownloadCommitCompleteness,
    ): LibraryItemId = database.withTransaction {
        require(pages.isNotEmpty()) { "Download commit requires at least one READY page." }
        val ownership = downloadOwnershipKey(series)
        val itemId = dao.itemIdByClaim(
            namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
            claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
            claimValue = ownership,
        ) ?: error("Serialized chapter commit requires the series library item.")
        val readingUnit = dao.readingUnitByChapter(itemId, chapterId)
            ?: error("Serialized chapter commit requires the reading unit.")
        val now = clock()
        val existingSource = sourceDao.sourceByIdentity(DOWNLOAD_GRANT_KIND, ownership)
        val sourceId = existingSource?.id ?: sourceDao.insertSource(
            AuthorizedSourceEntity(
                grantKind = DOWNLOAD_GRANT_KIND,
                adapterKind = DOWNLOAD_ADAPTER_KIND,
                contentUri = downloadLocationUri,
                providerIdentity = ownership,
                displayLabel = title,
                grantState = "READY",
                activeGeneration = 0,
                completedGeneration = null,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )

        val providerDocumentId = "$ownership#chapter:$chapterId"
        val existingLocation = sourceDao.locationByIdentity(sourceId, providerDocumentId)
        val locationId = existingLocation?.id ?: sourceDao.insertLocation(
            ComicLocationEntity(
                sourceId = sourceId,
                providerDocumentId = providerDocumentId,
                documentUri = downloadLocationUri,
                locationKind = "DIRECTORY",
                relativeDisplayPath = title,
                structuralSignature = null,
                lastCompleteGeneration = null,
                state = completeness.locationState,
            ),
        )
        existingLocation?.let {
            sourceDao.restoreLegacyLocationWithState(
                locationId = it.id,
                documentUri = downloadLocationUri,
                relativeDisplayPath = title,
                state = completeness.locationState,
            )
        }

        dao.deleteCurrentRevision(readingUnit.id)
        val structuralSignature = "download:" + pages.joinToString("\u001f") { it.documentId } +
            ":r$downloadRevision"
        val revisionId = dao.revisionIdBySignature(readingUnit.id, structuralSignature)
            ?: dao.insertRevision(
                MediaRevisionEntity(
                    readingUnitId = readingUnit.id,
                    locationId = locationId,
                    structuralSignature = structuralSignature,
                    createdAtMillis = now,
                ),
            )
        dao.setCurrentRevision(CurrentMediaRevisionEntity(readingUnit.id, revisionId))
        if (dao.pageCountForRevision(revisionId) == 0) {
            dao.insertPages(
                pages.mapIndexed { ordinal, page ->
                    PageEntity(
                        revisionId = revisionId,
                        ordinal = ordinal,
                        locatorKind = "DOCUMENT",
                        documentId = page.documentId,
                        documentUri = page.uri,
                        archiveEntryPath = null,
                        displayName = page.displayName,
                        mimeType = page.mimeType,
                        size = null,
                        lastModifiedMillis = null,
                        width = 0,
                        height = 0,
                    )
                },
            )
        }
        dao.refreshLegacyItem(itemId, now)
        LibraryItemId(itemId)
    }

    override suspend fun chapterDownloadCommitted(
        series: GalleryIdentityV1,
        chapterId: String,
        downloadRevision: Int,
    ): Boolean {
        val ownership = downloadOwnershipKey(series)
        val itemId = dao.itemIdByClaim(
            namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
            claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
            claimValue = ownership,
        ) ?: return false
        val unit = dao.readingUnitByChapter(itemId, chapterId) ?: return false
        // One unit's row, not itemGraph(): the C2 reclaim sweep asks this for
        // every finished chapter at start, and a series graph carries every
        // chapter's revisions and pages.
        val current = dao.currentRevisionLocation(unit.id) ?: return false
        return current.structuralSignature.endsWith(":r$downloadRevision") &&
            current.locationState != "UNAVAILABLE"
    }

    override suspend fun removeDownloadLocalAvailability(identity: GalleryIdentityV1): Boolean =
        database.withTransaction {
            val ownership = downloadOwnershipKey(identity)
            val itemId = dao.itemIdByClaim(
                namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
                claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
                claimValue = ownership,
            ) ?: return@withTransaction false
            val graph = dao.itemGraph(itemId) ?: return@withTransaction false
            val locationIds = graph.readingUnits
                .flatMap { unit -> unit.revisions.mapNotNull { it.location?.id } }
                .toSet()
            locationIds.forEach { dao.markLocationUnavailable(it) }
            true
        }

    override suspend fun removeChapterLocalAvailability(
        series: GalleryIdentityV1,
        chapterIds: Set<String>,
    ): Boolean = database.withTransaction {
        if (chapterIds.isEmpty()) return@withTransaction false
        val ownership = downloadOwnershipKey(series)
        val itemId = dao.itemIdByClaim(
            namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
            claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
            claimValue = ownership,
        ) ?: return@withTransaction false
        // Drop the chapters' current-revision pointers: the units go back to exactly the state of a chapter that was
        // never downloaded (no pages, not readable, never the Library card's unit), and keep their progress. Marking
        // the locations UNAVAILABLE instead left a deleted low chapter as the card's unit, hiding a series whose
        // later chapters are still saved.
        dao.itemGraph(itemId)
            ?.readingUnits
            .orEmpty()
            .filter { it.readingUnit.sourceChapterId in chapterIds }
            .forEach { dao.deleteCurrentRevision(it.readingUnit.id) }
        true
    }

    override suspend fun downloadOnDeviceAssociationCount(identity: GalleryIdentityV1): Int {
        val ownership = downloadOwnershipKey(identity)
        val itemId = dao.itemIdByClaim(
            namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
            claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
            claimValue = ownership,
        ) ?: return 0
        val graph = dao.itemGraph(itemId) ?: return 0
        val available = graph.toLibraryItem()?.isAvailable == true
        return if (available) 1 else 0
    }

    override suspend fun findDownloadItemId(identity: GalleryIdentityV1): LibraryItemId? {
        val ownership = downloadOwnershipKey(identity)
        val itemId = dao.itemIdByClaim(
            namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
            claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
            claimValue = ownership,
        ) ?: return null
        return LibraryItemId(itemId)
    }

    override suspend fun catalogDownloadPageDirectories(): Set<java.io.File> =
        dao.catalogDownloadPageUris()
            .mapNotNull { uri -> runCatching { java.io.File(java.net.URI(uri)).absoluteFile.parentFile }.getOrNull() }
            .toSet()

    override suspend fun findDownloadIdentity(itemId: LibraryItemId): GalleryIdentityV1? {
        val claim = dao.claimsForItem(itemId.value).firstOrNull {
            it.namespace == DOWNLOAD_PROVENANCE_NAMESPACE &&
                it.claimKey == DOWNLOAD_OWNERSHIP_CLAIM
        } ?: return null
        return parseDownloadOwnershipKey(claim.claimValue)
    }

    override suspend fun findSerializedSeriesIdentity(itemId: LibraryItemId): GalleryIdentityV1? {
        val claim = dao.claimsForItem(itemId.value).firstOrNull {
            it.namespace == DOWNLOAD_PROVENANCE_NAMESPACE &&
                it.claimKey == DOWNLOAD_OWNERSHIP_CLAIM
        } ?: return null
        return parseSerializedOwnershipKey(claim.claimValue)
    }

    suspend fun findSerializedSeriesItemId(identity: GalleryIdentityV1): LibraryItemId? =
        dao.itemIdByClaim(
            namespace = DOWNLOAD_PROVENANCE_NAMESPACE,
            claimKey = DOWNLOAD_OWNERSHIP_CLAIM,
            claimValue = downloadOwnershipKey(identity),
        )?.let(::LibraryItemId)

    override suspend fun sourceValidationTargets(): List<LegacySourceValidationTarget> =
        sourceDao.treeValidationSources().map {
            LegacySourceValidationTarget(SourceId(it.id), it.contentUri)
        }

    override suspend fun setSourceLocationsAvailable(
        sourceId: SourceId,
        available: Boolean,
    ) = database.withTransaction {
        sourceDao.updateLocationStateForSource(
            sourceId.value,
            if (available) "PRESENT" else "UNAVAILABLE",
        )
    }

    /**
     * W3 (A38): builds the shelf [LibraryItem] from a flat
     * [ShelfRowEntity] — pages carries ONLY the cover page (the shelf's
     * sole page consumer) and [LibraryItem.downloadedPages] carries the
     * SQL-precomputed download count so the badge never needs the full page
     * list. Returns null for a row the projection could not join coherently
     * (missing cover locator kinds are tolerated — an archive-only comic has
     * no documentUri but a real cover entry).
     */
    private fun ShelfRowEntity.toShelfItem(hiddenSourceIds: Set<SourceId>): LibraryItem? {
        val accessLost = SourceId(sourceId) in hiddenSourceIds
        val coverPage = coverLocatorKind?.let { kind ->
            PageRef(
                sourceId = SourceId(sourceId),
                locationId = LocationId(locationId),
                revisionId = MediaRevisionId(revisionId),
                ordinal = 0,
                locatorKind = PageLocatorKind.valueOf(kind),
                documentUri = coverDocumentUri,
                archiveEntryPath = coverArchiveEntryPath,
                displayName = coverDisplayName.orEmpty(),
                documentId = null,
            )
        }
        val isAvailable = !accessLost &&
            locationState in setOf("PRESENT", "INCOMPLETE") &&
            pageCount > 0
        val seriesCoverUri = if (
            sourceCoverLocator?.isNotBlank() == true &&
                serializedOwnership != null
        ) {
            parseSerializedOwnershipKey(serializedOwnership)
                ?.asSeriesIdentityV2()
                ?.takeIf { it.providerNamespace in SERIALIZED_SOURCE_COVER_PROVIDERS }
                ?.let { sourceCoverFiles?.uriIfValid(it) }
        } else {
            null
        }
        // A63: the badge's dual-state download axis. The SQL joins the live
        // job's state ('QUEUED' for WAIT/DOWNLOAD, 'COMPLETE' for
        // FINISH/SEALED); the ruling's "COMPLETE = FINISH/sealed WITH LOCAL
        // PAGES" gate is applied here, where the projected pageCount is in
        // hand — a finished job whose pages are gone no longer claims
        // completeness.
        // #C9 folds in the delisted fact, decoded from the same projected
        // value by [decodeShelfDownloadProjection] so both facts come from one
        // job row and cannot disagree.
        val download = decodeShelfDownloadProjection(downloadState, pageCount)
        return LibraryItem(
            id = LibraryItemId(itemId),
            title = title,
            pageCount = pageCount,
            isAvailable = isAvailable,
            isIncomplete = locationState == "INCOMPLETE",
            readingUnitId = ReadingUnitId(readingUnitId),
            progressOrdinal = progressOrdinal,
            locationId = LocationId(locationId),
            sourceId = SourceId(sourceId),
            sourceGrantKind = if (coverLocatorKind == PageLocatorKind.ARCHIVE_ENTRY.name) {
                GrantKind.DOCUMENT
            } else {
                GrantKind.TREE
            },
            pages = listOfNotNull(coverPage),
            sourceAccessLost = accessLost,
            downloadedPages = downloadedPages,
            downloadState = download.downloadState,
            delistedAtSource = download.delistedAtSource,
            seriesCoverUri = seriesCoverUri,
        )
    }

    private fun LibraryItemGraph.toLibraryItem(
        hiddenSourceIds: Set<SourceId> = emptySet(),
        selectedReadingUnitId: Long? = null,
    ): LibraryItem? {
        val metadataId = currentMetadata?.metadataSnapshotId ?: return null
        val metadata = metadataSnapshots.singleOrNull { it.id == metadataId } ?: return null
        // A serialized item may have an unreadable/undownloaded first chapter.
        // Match the shelf projection and choose the lowest-ordinal unit that
        // actually has a current revision, so the series remains visible.
        val unit = if (selectedReadingUnitId != null) {
            readingUnits.singleOrNull { it.readingUnit.id == selectedReadingUnitId }
                ?.takeIf { it.currentRevision != null }
        } else {
            readingUnits
                .asSequence()
                .filter { it.currentRevision != null }
                .minWithOrNull(compareBy<ReadingUnitGraph> { it.readingUnit.ordinal }.thenBy { it.readingUnit.id })
        } ?: return null
        val revisionId = unit.currentRevision?.mediaRevisionId ?: return null
        val revision = unit.revisions.singleOrNull { it.revision.id == revisionId } ?: return null
        val location = revision.location ?: return null
        val pages = revision.pages.sortedBy(PageEntity::ordinal)
        val accessLost = SourceId(location.sourceId) in hiddenSourceIds
        return LibraryItem(
            id = LibraryItemId(item.id),
            title = metadata.title,
            pageCount = pages.size,
            isAvailable = !accessLost &&
                location.state in setOf("PRESENT", "INCOMPLETE") &&
                pages.isNotEmpty(),
            sourceAccessLost = accessLost,
            isIncomplete = location.state == "INCOMPLETE",
            readingUnitId = ReadingUnitId(unit.readingUnit.id),
            progressOrdinal = unit.readingUnit.progressOrdinal,
            locationId = LocationId(location.id),
            sourceId = SourceId(location.sourceId),
            sourceGrantKind = if (pages.any { it.locatorKind == PageLocatorKind.ARCHIVE_ENTRY.name }) {
                GrantKind.DOCUMENT
            } else {
                GrantKind.TREE
            },
            pages = pages.map { page ->
                PageRef(
                    sourceId = SourceId(location.sourceId),
                    locationId = LocationId(location.id),
                    revisionId = MediaRevisionId(revision.revision.id),
                    ordinal = page.ordinal,
                    locatorKind = PageLocatorKind.valueOf(page.locatorKind),
                    documentUri = page.documentUri,
                    archiveEntryPath = page.archiveEntryPath,
                    displayName = page.displayName,
                    documentId = page.documentId,
                )
            },
        )
    }

    private fun LibraryItemGraph.toLocalDetailRow(
        hiddenSourceIds: Set<SourceId> = emptySet(),
    ): LocalDetailRow? {
        val item = toLibraryItem(hiddenSourceIds) ?: return null
        val metadataId = currentMetadata?.metadataSnapshotId ?: return null
        val entity = metadataSnapshots.singleOrNull { it.id == metadataId } ?: return null
        return LocalDetailRow(item = item, metadata = entity.toMetadataSnapshot())
    }

    private fun PageAccessTargetRow.toPageAccessTarget() = PageAccessTarget(
        readingUnitId = ReadingUnitId(readingUnitId),
        page = PageRef(
            sourceId = SourceId(sourceId),
            locationId = LocationId(locationId),
            revisionId = MediaRevisionId(revisionId),
            ordinal = ordinal,
            locatorKind = PageLocatorKind.valueOf(locatorKind),
            documentUri = pageDocumentUri,
            archiveEntryPath = archiveEntryPath,
            displayName = displayName,
            documentId = pageDocumentId,
        ),
        sourceGrantKind = GrantKind.valueOf(sourceGrantKind),
        sourceGrantState = SourceGrantState.valueOf(sourceGrantState),
        sourceGrantUri = sourceGrantUri,
        locationKind = LocationKind.valueOf(locationKind),
        locationState = LocationState.valueOf(locationState),
        locationDocumentUri = locationDocumentUri,
        providerDocumentId = providerDocumentId,
        structuralSignature = structuralSignature,
        mimeType = mimeType,
        size = size,
        lastModifiedMillis = lastModifiedMillis,
        width = width,
        height = height,
        corrupted = corrupted,
    )

    private fun List<String>.toJson(): String = JSONArray(this).toString()
}
