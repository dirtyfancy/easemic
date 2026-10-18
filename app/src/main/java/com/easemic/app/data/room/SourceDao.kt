package com.easemic.app.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.easemic.app.model.SourceGrantState
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM authorized_sources WHERE grantState != 'REMOVED' AND adapterKind != 'CATALOG_DOWNLOAD' ORDER BY id")
    fun observeSources(): Flow<List<AuthorizedSourceEntity>>

    @Query("SELECT * FROM authorized_sources WHERE grantState != 'REMOVED' AND adapterKind != 'CATALOG_DOWNLOAD' ORDER BY id")
    suspend fun visibleSources(): List<AuthorizedSourceEntity>

    @Query("SELECT * FROM import_batches WHERE EXISTS (SELECT 1 FROM import_batch_sources JOIN authorized_sources ON authorized_sources.id = import_batch_sources.sourceId WHERE import_batch_sources.batchId = import_batches.id AND authorized_sources.grantState != 'REMOVED') ORDER BY id")
    fun observeBatches(): Flow<List<ImportBatchEntity>>

    @Query("SELECT * FROM authorized_sources WHERE id = :sourceId")
    suspend fun source(sourceId: Long): AuthorizedSourceEntity?

    @Query("SELECT * FROM authorized_sources WHERE grantKind = :grantKind AND providerIdentity = :providerIdentity LIMIT 1")
    suspend fun sourceByIdentity(grantKind: String, providerIdentity: String): AuthorizedSourceEntity?

    @Insert
    suspend fun insertSource(source: AuthorizedSourceEntity): Long

    @Query(
        """
        UPDATE authorized_sources
        SET contentUri = :contentUri, adapterKind = :adapterKind, displayLabel = :displayLabel,
            grantState = 'NEEDS_VALIDATION', updatedAtMillis = :updatedAtMillis
        WHERE id = :sourceId
        """,
    )
    suspend fun refreshAuthorizedSource(
        sourceId: Long,
        contentUri: String,
        adapterKind: String,
        displayLabel: String,
        updatedAtMillis: Long,
    )

    @Query(
        """
        UPDATE authorized_sources
        SET contentUri = :contentUri, providerIdentity = :providerIdentity,
            adapterKind = :adapterKind, displayLabel = :displayLabel,
            grantState = 'NEEDS_VALIDATION', updatedAtMillis = :updatedAtMillis
        WHERE id = :sourceId
        """,
    )
    suspend fun rebindAuthorizedSource(
        sourceId: Long,
        contentUri: String,
        providerIdentity: String,
        adapterKind: String,
        displayLabel: String,
        updatedAtMillis: Long,
    )

    @Query("UPDATE authorized_sources SET grantState = :grantState, updatedAtMillis = :updatedAtMillis WHERE id = :sourceId")
    suspend fun setGrantState(sourceId: Long, grantState: SourceGrantState, updatedAtMillis: Long)

    @Query(
        """
        UPDATE authorized_sources
        SET grantState = :grantState, updatedAtMillis = :updatedAtMillis
        WHERE id = :sourceId
          AND grantState != 'REMOVED'
        """,
    )
    suspend fun setGrantStateIfActive(
        sourceId: Long,
        grantState: SourceGrantState,
        updatedAtMillis: Long,
    ): Int

    @Query(
        """
        UPDATE authorized_sources
        SET grantState = 'NEEDS_REAUTHORIZATION',
            updatedAtMillis = :updatedAtMillis
        WHERE id = :sourceId
          AND grantState != 'REMOVED'
        """,
    )
    suspend fun requireReauthorizationIfActive(
        sourceId: Long,
        updatedAtMillis: Long,
    ): Int

    @Insert
    suspend fun insertBatch(batch: ImportBatchEntity): Long

    @Insert
    suspend fun insertBatchSources(sources: List<ImportBatchSourceEntity>)

    @Query("SELECT sourceId FROM import_batch_sources WHERE batchId = :batchId ORDER BY sourceId")
    suspend fun batchSourceIds(batchId: Long): List<Long>

    @Query(
        """
        SELECT import_batch_sources.sourceId FROM import_batch_sources
        JOIN authorized_sources ON authorized_sources.id = import_batch_sources.sourceId
        WHERE import_batch_sources.batchId = :batchId AND authorized_sources.grantState != 'REMOVED'
        ORDER BY import_batch_sources.sourceId
        """,
    )
    suspend fun visibleBatchSourceIds(batchId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM authorized_sources WHERE contentUri = :contentUri AND grantState != 'REMOVED'")
    suspend fun activeSourceReferences(contentUri: String): Int

    @Query("UPDATE authorized_sources SET grantState = 'REMOVED', updatedAtMillis = :updatedAtMillis WHERE id = :sourceId")
    suspend fun tombstoneSource(sourceId: Long, updatedAtMillis: Long)

    @Query(
        """
        UPDATE authorized_sources
        SET contentUri = :contentUri,
            displayLabel = :displayLabel,
            grantState = 'READY',
            updatedAtMillis = :updatedAtMillis
        WHERE id = :sourceId
        """,
    )
    suspend fun refreshLegacySource(
        sourceId: Long,
        contentUri: String,
        displayLabel: String,
        updatedAtMillis: Long,
    )

    @Query("SELECT * FROM comic_locations WHERE sourceId = :sourceId AND providerDocumentId = :providerDocumentId LIMIT 1")
    suspend fun locationByIdentity(sourceId: Long, providerDocumentId: String): ComicLocationEntity?

    @Query("SELECT * FROM comic_locations WHERE sourceId = :sourceId AND structuralSignature = :structuralSignature AND state = 'MISSING' ORDER BY id")
    suspend fun missingLocationsBySignature(sourceId: Long, structuralSignature: String): List<ComicLocationEntity>

    @Insert
    suspend fun insertLocation(location: ComicLocationEntity): Long

    @Query(
        """
        UPDATE comic_locations SET documentUri = :documentUri, locationKind = :locationKind,
          relativeDisplayPath = :relativeDisplayPath, structuralSignature = :structuralSignature,
          lastCompleteGeneration = :generation, state = :state
        WHERE id = :locationId
        """,
    )
    suspend fun updateObservedLocation(
        locationId: Long,
        documentUri: String,
        locationKind: String,
        relativeDisplayPath: String,
        structuralSignature: String,
        generation: Long,
        state: String,
    )

    @Query(
        """
        UPDATE comic_locations SET providerDocumentId = :providerDocumentId,
          documentUri = :documentUri, locationKind = :locationKind,
          relativeDisplayPath = :relativeDisplayPath, structuralSignature = :structuralSignature,
          lastCompleteGeneration = :generation, state = :state
        WHERE id = :locationId
        """,
    )
    suspend fun reconnectObservedLocation(
        locationId: Long,
        providerDocumentId: String,
        documentUri: String,
        locationKind: String,
        relativeDisplayPath: String,
        structuralSignature: String,
        generation: Long,
        state: String,
    )

    @Query(
        """
        UPDATE comic_locations
        SET documentUri = :documentUri,
            relativeDisplayPath = :relativeDisplayPath,
            state = 'PRESENT'
        WHERE id = :locationId
        """,
    )
    suspend fun restoreLegacyLocation(
        locationId: Long,
        documentUri: String,
        relativeDisplayPath: String,
    )

    /**
     * A36: [restoreLegacyLocation] with the target state spelled out, so a
     * re-commit of the SAME identity can move a row between `PRESENT` and
     * `INCOMPLETE`. A truncated download that is later re-downloaded in full
     * must stop being badged, and a full download whose re-fetch is truncated
     * must start being badged — a hard-coded 'PRESENT' could only ever do the
     * first half, and silently.
     */
    @Query(
        """
        UPDATE comic_locations
        SET documentUri = :documentUri,
            relativeDisplayPath = :relativeDisplayPath,
            state = :state
        WHERE id = :locationId
        """,
    )
    suspend fun restoreLegacyLocationWithState(
        locationId: Long,
        documentUri: String,
        relativeDisplayPath: String,
        state: String,
    )

    @Query("UPDATE comic_locations SET state = :state WHERE sourceId = :sourceId")
    suspend fun updateLocationStateForSource(sourceId: Long, state: String)

    @Query("UPDATE comic_locations SET state = 'MISSING' WHERE sourceId = :sourceId AND (lastCompleteGeneration IS NULL OR lastCompleteGeneration != :generation) AND state IN ('PRESENT', 'INCOMPLETE')")
    suspend fun markUnseenLocationsMissing(sourceId: Long, generation: Long)

    @Query(
        """
        UPDATE comic_locations
        SET lastCompleteGeneration = :generation,
            state = CASE WHEN state = 'MISSING' THEN 'PRESENT' ELSE state END
        WHERE sourceId = :sourceId
          AND EXISTS (
            SELECT 1 FROM scan_candidates
            WHERE scan_candidates.runId = :runId
              AND scan_candidates.enumerationAttemptId = :reviewAttemptId
              AND (
                scan_candidates.providerDocumentId = comic_locations.providerDocumentId
                OR (
                  comic_locations.providerDocumentId >= scan_candidates.providerDocumentId || char(0)
                  AND comic_locations.providerDocumentId < scan_candidates.providerDocumentId || char(1)
                )
              )
          )
        """,
    )
    /**
     * A47 review finding + audit fix: the second branch must match a container's composite
     * sub-comic locations ("archiveDocumentId\u0000relativePath") without matching anything
     * else. A LIKE prefix was tried first and is WRONG here for three reasons, all verified
     * against SQLite semantics:
     *
     *  1. LIKE has no default escape, so a candidate id containing `%` or `_` (SAF ids are
     *     opaque provider strings and may contain them) matches OTHER archives' sub-locations
     *     ("My_Archive" also matches "MyXArchive\0.."; "ms%1" also matches "msA1\0..").
     *  2. LIKE is case-insensitive for ASCII by default ("My_Archive" matches
     *     "my_archive\0.."), while the equality branch is BINARY — a mismatch between the
     *     two branches of the same OR.
     *  3. SQLite's LIKE compares only up to the pattern's embedded NUL for text operands
     *     (verified: 'a' LIKE 'a' || char(0) || '%' is TRUE), collapsing the prefix check.
     *
     * The range comparison is BINARY on full UTF-8 bytes, so it cannot cross candidate ids:
     * a sub-location "id\0relPath" is >= "id\0" and < "id\1" because 0x00 < every byte
     * that can start relPath (NUL cannot appear in an id or a zip entry path —
     * ZipSafetyPolicy rejects it), and no other archive's id can fall inside another id's
     * [id\0, id\1) window (the second id would have to start with id+NUL or id+SOH).
     */
    suspend fun markLocationsSeenInReviewAttempt(
        sourceId: Long,
        runId: String,
        reviewAttemptId: String,
        generation: Long,
    )

    @Query("SELECT * FROM authorized_sources WHERE grantState != 'REMOVED' AND adapterKind != 'CATALOG_DOWNLOAD' ORDER BY id")
    suspend fun validationSources(): List<AuthorizedSourceEntity>

    @Query("SELECT * FROM authorized_sources WHERE grantKind = 'TREE' AND grantState != 'REMOVED' AND adapterKind != 'CATALOG_DOWNLOAD' ORDER BY id")
    suspend fun treeValidationSources(): List<AuthorizedSourceEntity>

    /**
     * A30 §2.1: tombstoned or unreadable sources hide their comics from the shelf.
     *
     * Review LOW-2: deliberately missing the `adapterKind != 'CATALOG_DOWNLOAD'`
     * carve-out its siblings above use. CATALOG_DOWNLOAD rows are never
     * user-authorized folders, so they should never be REMOVED/tombstoned in
     * the first place, but this query is intentionally unfiltered: if one
     * ever *did* end up REMOVED, ShelfVisibility should still hide its items
     * rather than silently keep them visible via an exclusion this query
     * happens not to have.
     *
     * S4b/B3: folders whose comics must not be on the shelf right now --
     * tombstoned ones (Stop watching) plus the ones Android revoked access to. The second
     * group is deliberately a *source* state, not a location state: an access problem never
     * marks a comic MISSING, so the comics reappear untouched the moment the grant is back.
     */
    @Query(
        "SELECT id FROM authorized_sources " +
            "WHERE grantState IN ('REMOVED', 'NEEDS_REAUTHORIZATION')",
    )
    fun observeHiddenSourceIds(): Flow<List<Long>>

    /** A30 §2.2 Undo window: grants held for folders the user just stopped watching. */
    @Query("SELECT * FROM authorized_sources WHERE grantState = 'REMOVED' AND adapterKind != 'CATALOG_DOWNLOAD' ORDER BY id")
    suspend fun tombstonedSources(): List<AuthorizedSourceEntity>

    /**
     * A30 §2.2: comic counts per folder for the Folders row status line.
     * SEND-BACK #2 item D: without the join, a stopped (REMOVED) source's
     * still-present comic_locations rows and a CATALOG_DOWNLOAD source's
     * rows both leaked into the sum Settings shows next to "{n} folders",
     * so it double-counted comics that weren't behind any live folder.
     */
    @Query(
        """
        SELECT l.sourceId AS sourceId, COUNT(*) AS comicCount FROM comic_locations l
        JOIN authorized_sources s ON s.id = l.sourceId
        WHERE l.state IN ('PRESENT', 'INCOMPLETE')
          AND s.grantState != 'REMOVED'
          AND s.adapterKind != 'CATALOG_DOWNLOAD'
        GROUP BY l.sourceId
        """,
    )
    fun observeComicCountsBySource(): Flow<List<SourceComicCountRow>>
}
