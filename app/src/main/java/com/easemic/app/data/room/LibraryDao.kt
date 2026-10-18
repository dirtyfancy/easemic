package com.easemic.app.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.easemic.app.feature.library.ChapterShelfRow
import com.easemic.app.feature.library.SeriesShelfRow
import kotlinx.coroutines.flow.Flow
import com.easemic.app.feature.download.downloadOwnershipKey

/** Existing serialized ownership and its canonical source cover locator. */
data class SerializedSeriesCoverCandidateRow(
    val ownership: String,
    val coverLocator: String?,
)

@Dao
interface LibraryDao {
    @Query(
        """
        SELECT provenance_claims.claimValue AS ownership,
               metadata_snapshots.coverLocator AS coverLocator
        FROM provenance_claims
        JOIN current_metadata
          ON current_metadata.itemId = provenance_claims.itemId
        JOIN metadata_snapshots
          ON metadata_snapshots.id = current_metadata.metadataSnapshotId
        WHERE provenance_claims.namespace = 'gallery_identity_v1'
          AND provenance_claims.claimKey = 'ownership'
          AND provenance_claims.claimValue LIKE 'download:v1:%'
          AND metadata_snapshots.coverLocator IS NOT NULL
          AND TRIM(metadata_snapshots.coverLocator) <> ''
        ORDER BY provenance_claims.itemId
        """,
    )
    suspend fun serializedSeriesCoverCandidates(): List<SerializedSeriesCoverCandidateRow>

    @Transaction
    @Query("SELECT * FROM library_items ORDER BY id")
    fun observeLibraryRows(): Flow<List<LibraryItemGraph>>

    @Transaction
    @Query("SELECT * FROM library_items WHERE id = :itemId")
    fun observeItemRow(itemId: Long): Flow<LibraryItemGraph?>

    @Transaction
    @Query("SELECT * FROM library_items WHERE id = :itemId")
    suspend fun itemGraph(itemId: Long): LibraryItemGraph?

    @Query("SELECT * FROM library_items WHERE id = :itemId")
    suspend fun itemById(itemId: Long): LibraryItemEntity?

    @Query(
        """
        SELECT
          media_revisions.readingUnitId AS readingUnitId,
          authorized_sources.id AS sourceId,
          authorized_sources.grantKind AS sourceGrantKind,
          authorized_sources.grantState AS sourceGrantState,
          authorized_sources.contentUri AS sourceGrantUri,
          comic_locations.id AS locationId,
          comic_locations.locationKind AS locationKind,
          comic_locations.state AS locationState,
          comic_locations.documentUri AS locationDocumentUri,
          comic_locations.providerDocumentId AS providerDocumentId,
          media_revisions.structuralSignature AS structuralSignature,
          pages.revisionId AS revisionId,
          pages.ordinal AS ordinal,
          pages.locatorKind AS locatorKind,
          pages.documentUri AS pageDocumentUri,
          pages.documentId AS pageDocumentId,
          pages.archiveEntryPath AS archiveEntryPath,
          pages.displayName AS displayName,
          pages.mimeType AS mimeType,
          pages.size AS size,
          pages.lastModifiedMillis AS lastModifiedMillis,
          pages.width AS width,
          pages.height AS height,
          pages.corrupted AS corrupted
        FROM pages
        JOIN media_revisions
          ON media_revisions.id = pages.revisionId
        JOIN current_media_revisions
          ON current_media_revisions.readingUnitId = media_revisions.readingUnitId
         AND current_media_revisions.mediaRevisionId = media_revisions.id
        JOIN comic_locations
          ON comic_locations.id = media_revisions.locationId
        JOIN authorized_sources
          ON authorized_sources.id = comic_locations.sourceId
        WHERE authorized_sources.grantState != 'REMOVED'
          AND authorized_sources.id = :sourceId
          AND comic_locations.id = :locationId
          AND pages.revisionId = :revisionId
          AND pages.ordinal = :ordinal
          AND pages.locatorKind = :locatorKind
          AND (
            (pages.documentUri IS NULL AND :documentUri IS NULL)
            OR pages.documentUri = :documentUri
          )
          AND (
            (pages.archiveEntryPath IS NULL AND :archiveEntryPath IS NULL)
            OR pages.archiveEntryPath = :archiveEntryPath
          )
        LIMIT 1
        """,
    )
    suspend fun pageAccessTarget(
        sourceId: Long,
        locationId: Long,
        revisionId: Long,
        ordinal: Int,
        locatorKind: String,
        documentUri: String?,
        archiveEntryPath: String?,
    ): PageAccessTargetRow?

    @Query(
        """
        UPDATE comic_locations
        SET state = 'UNAVAILABLE'
        WHERE id = :locationId
          AND EXISTS (
            SELECT 1
            FROM current_media_revisions
            JOIN media_revisions
              ON media_revisions.id = current_media_revisions.mediaRevisionId
            WHERE current_media_revisions.readingUnitId = :readingUnitId
              AND current_media_revisions.mediaRevisionId = :revisionId
              AND media_revisions.locationId = :locationId
          )
        """,
    )
    suspend fun markLocationUnavailableIfCurrent(
        readingUnitId: Long,
        revisionId: Long,
        locationId: Long,
    ): Int

    @Query(
        """
        SELECT comic_locations.sourceId
        FROM comic_locations
        JOIN authorized_sources
          ON authorized_sources.id = comic_locations.sourceId
        WHERE comic_locations.id = :locationId
          AND authorized_sources.grantState != 'REMOVED'
          AND EXISTS (
            SELECT 1
            FROM current_media_revisions
            JOIN media_revisions
              ON media_revisions.id = current_media_revisions.mediaRevisionId
            WHERE current_media_revisions.readingUnitId = :readingUnitId
              AND current_media_revisions.mediaRevisionId = :revisionId
              AND media_revisions.locationId = :locationId
          )
        LIMIT 1
        """,
    )
    suspend fun sourceIdForCurrentLocation(
        readingUnitId: Long,
        revisionId: Long,
        locationId: Long,
    ): Long?

    @Query("SELECT * FROM reading_units WHERE id = :readingUnitId")
    suspend fun readingUnitById(readingUnitId: Long): ReadingUnitEntity?

    @Query("SELECT * FROM reading_units WHERE itemId = :itemId ORDER BY ordinal LIMIT 1")
    suspend fun readingUnitForItem(itemId: Long): ReadingUnitEntity?

    @Query(
        "SELECT * FROM reading_units " +
            "WHERE itemId = :itemId AND sourceChapterId = :sourceChapterId " +
            "LIMIT 1",
    )
    suspend fun readingUnitByChapter(
        itemId: Long,
        sourceChapterId: String,
    ): ReadingUnitEntity?

    @Query(
        "SELECT * FROM reading_units WHERE itemId = :itemId ORDER BY ordinal, id",
    )
    suspend fun readingUnitsForItem(itemId: Long): List<ReadingUnitEntity>

    @Query(
        "SELECT * FROM reading_units " +
            "WHERE itemId = :itemId AND sourceState = 'ACTIVE' " +
            "ORDER BY ordinal, id",
    )
    suspend fun activeReadingUnitsForItem(itemId: Long): List<ReadingUnitEntity>

    @Query("UPDATE reading_units SET ordinal = -id - 1 WHERE itemId = :itemId")
    suspend fun stageChapterOrdinals(itemId: Long)

    @Query(
        "UPDATE reading_units SET label = :label, ordinal = :ordinal, sourceGroup = :sourceGroup, " +
            "sourceState = :sourceState, sourceUpdatedAtMillis = :sourceUpdatedAtMillis " +
            "WHERE id = :readingUnitId",
    )
    suspend fun updateChapter(
        readingUnitId: Long,
        label: String,
        ordinal: Int,
        sourceGroup: String?,
        sourceState: String,
        sourceUpdatedAtMillis: Long?,
    )

    @Query(
        """
        SELECT ru.*
        FROM reading_units AS ru
        JOIN provenance_claims AS pc
          ON pc.itemId = ru.itemId
        JOIN download_jobs AS jobs
          ON pc.claimValue = 'download:v' || jobs.identityVersion
              || ':' || jobs.providerNamespace || ':' || jobs.canonicalItemId
         AND jobs.chapterIdentity = ru.sourceChapterId
        WHERE ru.itemId = :itemId
          AND pc.namespace = 'gallery_identity_v1'
          AND pc.claimKey = 'ownership'
          AND jobs.identityVersion = :identityVersion
        ORDER BY jobs.updatedAtMillis DESC, jobs.id DESC
        LIMIT 1
        """,
    )
    suspend fun readingUnitForDownloadJob(
        itemId: Long,
        identityVersion: Int,
    ): ReadingUnitEntity?

    @Query(
        """
        SELECT metadata_snapshots.* FROM metadata_snapshots
        JOIN current_metadata
          ON current_metadata.metadataSnapshotId = metadata_snapshots.id
        WHERE current_metadata.itemId = :itemId
        """,
    )
    suspend fun currentMetadataForItem(itemId: Long): MetadataSnapshotEntity?

    @Query(
        """
        SELECT metadata_snapshots.*
        FROM metadata_snapshots
        JOIN current_metadata
          ON current_metadata.metadataSnapshotId = metadata_snapshots.id
        JOIN provenance_claims
          ON provenance_claims.itemId = current_metadata.itemId
        WHERE provenance_claims.namespace = 'gallery_identity_v1'
          AND provenance_claims.claimKey = 'ownership'
          AND provenance_claims.claimValue = :ownership
        LIMIT 1
        """,
    )
    suspend fun currentMetadataForOwnership(ownership: String): MetadataSnapshotEntity?

    @Query(
        """
        SELECT metadata_snapshots.*
        FROM metadata_snapshots
        JOIN current_metadata
          ON current_metadata.metadataSnapshotId = metadata_snapshots.id
        JOIN provenance_claims
          ON provenance_claims.itemId = current_metadata.itemId
        WHERE provenance_claims.namespace = 'gallery_identity_v1'
          AND provenance_claims.claimKey = 'ownership'
          AND provenance_claims.claimValue = :ownership
        LIMIT 1
        """,
    )
    fun observeCurrentMetadataForOwnership(ownership: String): Flow<MetadataSnapshotEntity?>

    @Query(
        """
        SELECT library_items.id FROM library_items
        JOIN reading_units ON reading_units.itemId = library_items.id
        JOIN media_revisions ON media_revisions.readingUnitId = reading_units.id
        WHERE media_revisions.locationId = :locationId
        GROUP BY library_items.id
        """,
    )
    suspend fun itemIdsByLocation(locationId: Long): List<Long>

    @Query("SELECT mediaRevisionId FROM current_media_revisions WHERE readingUnitId = :readingUnitId")
    suspend fun currentRevisionId(readingUnitId: Long): Long?

    @Query(
        """
        SELECT media_revisions.structuralSignature AS structuralSignature,
            comic_locations.state AS locationState
        FROM current_media_revisions
        JOIN media_revisions ON media_revisions.id = current_media_revisions.mediaRevisionId
        JOIN comic_locations ON comic_locations.id = media_revisions.locationId
        WHERE current_media_revisions.readingUnitId = :readingUnitId
        """,
    )
    suspend fun currentRevisionLocation(readingUnitId: Long): CurrentRevisionLocationRow?

    @Query("SELECT * FROM provenance_claims WHERE itemId = :itemId ORDER BY namespace, claimKey, claimValue")
    suspend fun claimsForItem(itemId: Long): List<ProvenanceClaimEntity>

    @Query(
        """
        SELECT itemId FROM provenance_claims
        WHERE namespace = :namespace
          AND claimKey = :claimKey
          AND claimValue = :claimValue
        LIMIT 1
        """,
    )
    suspend fun itemIdByClaim(
        namespace: String,
        claimKey: String,
        claimValue: String,
    ): Long?

    @Query(
        """
        SELECT itemId, claimValue FROM provenance_claims
        WHERE namespace = :namespace
          AND claimKey = :claimKey
        """,
    )
    suspend fun ownershipClaims(namespace: String, claimKey: String): List<ItemOwnershipRow>

    @Query(
        """
        SELECT COUNT(*) FROM pages
        WHERE revisionId = (
            SELECT mediaRevisionId FROM current_media_revisions
            WHERE readingUnitId = :readingUnitId
        )
        """,
    )
    suspend fun pageCountForReadingUnit(readingUnitId: Long): Int

    @Query("UPDATE reading_units SET progressOrdinal = :ordinal WHERE id = :readingUnitId")
    suspend fun updateProgress(readingUnitId: Long, ordinal: Int)

    @Query(
        """
        UPDATE reading_units
        SET progressOrdinal = :ordinal,
            completed = CASE WHEN :completed THEN 1 ELSE completed END,
            lastReadAtMillis = :lastReadAtMillis
        WHERE id = :readingUnitId
        """,
    )
    suspend fun updateChapterProgress(
        readingUnitId: Long,
        ordinal: Int,
        completed: Boolean,
        lastReadAtMillis: Long,
    )

    @Query("UPDATE reading_units SET sourceState = :sourceState WHERE id = :readingUnitId")
    suspend fun updateChapterSourceState(readingUnitId: Long, sourceState: String)

    @Query(
        """
        UPDATE reading_units
        SET progressOrdinal = :ordinal
        WHERE id = :readingUnitId
          AND EXISTS (
            SELECT 1
            FROM current_media_revisions
            JOIN media_revisions
              ON media_revisions.id = current_media_revisions.mediaRevisionId
            WHERE current_media_revisions.readingUnitId = :readingUnitId
              AND current_media_revisions.mediaRevisionId = :revisionId
              AND media_revisions.locationId = :locationId
          )
        """,
    )
    suspend fun updateProgressIfCurrent(
        readingUnitId: Long,
        revisionId: Long,
        locationId: Long,
        ordinal: Int,
    ): Int

    @Query(
        """
        UPDATE reading_units
        SET progressOrdinal = :ordinal,
            completed = CASE WHEN :completed THEN 1 ELSE completed END,
            lastReadAtMillis = :lastReadAtMillis
        WHERE id = :readingUnitId
          AND EXISTS (
            SELECT 1
            FROM current_media_revisions
            JOIN media_revisions
              ON media_revisions.id = current_media_revisions.mediaRevisionId
            WHERE current_media_revisions.readingUnitId = :readingUnitId
              AND current_media_revisions.mediaRevisionId = :revisionId
              AND media_revisions.locationId = :locationId
          )
        """,
    )
    suspend fun updateChapterProgressIfCurrent(
        readingUnitId: Long,
        revisionId: Long,
        locationId: Long,
        ordinal: Int,
        completed: Boolean,
        lastReadAtMillis: Long,
    ): Int

    @Query("UPDATE comic_locations SET state = 'UNAVAILABLE' WHERE id = :locationId")
    suspend fun markLocationUnavailable(locationId: Long)

    /**
     * A30 §2.4 (S4b/B4): the reader proved the file is gone. Same current-revision fence as
     * [markLocationUnavailableIfCurrent], but writes MISSING -- the state rescan
     * reconciliation and signature re-linking already understand.
     */
    @Query(
        """
        UPDATE comic_locations
        SET state = 'MISSING'
        WHERE id = :locationId
          AND EXISTS (
            SELECT 1
            FROM current_media_revisions
            JOIN media_revisions
              ON media_revisions.id = current_media_revisions.mediaRevisionId
            WHERE current_media_revisions.readingUnitId = :readingUnitId
              AND current_media_revisions.mediaRevisionId = :revisionId
              AND media_revisions.locationId = :locationId
          )
        """,
    )
    suspend fun markLocationMissingIfCurrent(
        readingUnitId: Long,
        revisionId: Long,
        locationId: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM library_items")
    suspend fun itemCount(): Int

    @Query("SELECT COUNT(*) FROM pages")
    suspend fun pageCount(): Int

    /**
     * r3 BLOCKING-1 / MEDIUM-1: the directories the Library's LIVE catalog
     * pages point into — the CURRENT media revision of a location that has
     * not been removed. A deleted/released comic keeps its page rows (nothing
     * in this app ever deletes one) but its location is UNAVAILABLE; an old
     * revision is not current. Scoped by the same joins [pageAccessTarget]
     * uses, so the scan is one row per page of live comics only.
     *
     * A MISSING location stays IN the veto deliberately: a reader that could
     * not open one page marks the location MISSING while the job stays FINISH,
     * so the pages are still the shelf's only copy — leaking a tree is the
     * safe direction there, deleting one is not.
     */
    @Query(
        """
        SELECT DISTINCT pages.documentUri
        FROM pages
        JOIN media_revisions ON media_revisions.id = pages.revisionId
        JOIN current_media_revisions
          ON current_media_revisions.readingUnitId = media_revisions.readingUnitId
         AND current_media_revisions.mediaRevisionId = media_revisions.id
        JOIN comic_locations ON comic_locations.id = media_revisions.locationId
        WHERE pages.documentUri LIKE '%-downloads%'
          AND comic_locations.state != 'UNAVAILABLE'
        """,
    )
    suspend fun catalogDownloadPageUris(): List<String>

    @Query(
        """
        SELECT
            ru.itemId AS itemId,
            ru.id AS readingUnitId,
            ru.sourceChapterId AS sourceChapterId,
            ru.label AS label,
            ru.sourceGroup AS sourceGroup,
            ru.ordinal AS ordinal,
            ru.progressOrdinal AS progressOrdinal,
            ru.completed AS completed,
            ru.lastReadAtMillis AS lastReadAtMillis,
            COUNT(p.ordinal) AS pageCount,
            ru.sourceState AS sourceState,
            ru.sourceUpdatedAtMillis AS sourceUpdatedAtMillis
        FROM reading_units AS ru
        LEFT JOIN current_media_revisions AS cmr
          ON cmr.readingUnitId = ru.id
        LEFT JOIN media_revisions AS shelf_revision
          ON shelf_revision.id = cmr.mediaRevisionId
        LEFT JOIN comic_locations AS shelf_location
          ON shelf_location.id = shelf_revision.locationId
        LEFT JOIN pages AS p
          ON p.revisionId = cmr.mediaRevisionId
         AND shelf_location.state != 'UNAVAILABLE'
        WHERE ru.itemId = :itemId
        GROUP BY ru.itemId, ru.id, ru.sourceChapterId, ru.label, ru.sourceGroup,
            ru.ordinal, ru.progressOrdinal, ru.completed, ru.lastReadAtMillis,
            ru.sourceState, ru.sourceUpdatedAtMillis
        ORDER BY ru.ordinal, ru.id
        """,
    )
    suspend fun chapterShelfRowsForItem(itemId: Long): List<ChapterShelfRow>

    @Query(
        """
        SELECT
            ru.itemId AS itemId,
            ru.id AS readingUnitId,
            ru.sourceChapterId AS sourceChapterId,
            ru.label AS label,
            ru.sourceGroup AS sourceGroup,
            ru.ordinal AS ordinal,
            ru.progressOrdinal AS progressOrdinal,
            ru.completed AS completed,
            ru.lastReadAtMillis AS lastReadAtMillis,
            COUNT(p.ordinal) AS pageCount,
            ru.sourceState AS sourceState,
            ru.sourceUpdatedAtMillis AS sourceUpdatedAtMillis
        FROM reading_units AS ru
        LEFT JOIN current_media_revisions AS cmr
          ON cmr.readingUnitId = ru.id
        LEFT JOIN media_revisions AS shelf_revision
          ON shelf_revision.id = cmr.mediaRevisionId
        LEFT JOIN comic_locations AS shelf_location
          ON shelf_location.id = shelf_revision.locationId
        LEFT JOIN pages AS p
          ON p.revisionId = cmr.mediaRevisionId
         AND shelf_location.state != 'UNAVAILABLE'
        WHERE ru.itemId = :itemId
        GROUP BY ru.itemId, ru.id, ru.sourceChapterId, ru.label, ru.sourceGroup,
            ru.ordinal, ru.progressOrdinal, ru.completed, ru.lastReadAtMillis,
            ru.sourceState, ru.sourceUpdatedAtMillis
        ORDER BY ru.ordinal, ru.id
        """,
    )
    fun observeChapterShelfRowsForItem(itemId: Long): Flow<List<ChapterShelfRow>>

    @Query(
        """
        SELECT
            library_items.id AS itemId,
            metadata_snapshots.title AS title,
            metadata_snapshots.coverLocator AS seriesCoverLocator,
            COUNT(ru.id) AS chapterCount,
            SUM(CASE WHEN EXISTS (
                SELECT 1
                FROM current_media_revisions AS readable_current
                JOIN media_revisions AS readable_revision
                  ON readable_revision.id = readable_current.mediaRevisionId
                JOIN comic_locations AS readable_location
                  ON readable_location.id = readable_revision.locationId
                JOIN pages AS readable_page
                  ON readable_page.revisionId = readable_current.mediaRevisionId
                WHERE readable_current.readingUnitId = ru.id
                  AND readable_location.state != 'UNAVAILABLE'
            ) THEN 1 ELSE 0 END) AS readableChapterCount,
            SUM(CASE WHEN ru.completed = 1 AND EXISTS (
                SELECT 1
                FROM current_media_revisions AS completed_current
                JOIN media_revisions AS completed_revision
                  ON completed_revision.id = completed_current.mediaRevisionId
                JOIN comic_locations AS completed_location
                  ON completed_location.id = completed_revision.locationId
                JOIN pages AS completed_page
                  ON completed_page.revisionId = completed_current.mediaRevisionId
                WHERE completed_current.readingUnitId = ru.id
                  AND completed_location.state != 'UNAVAILABLE'
            ) THEN 1 ELSE 0 END) AS completedChapterCount,
            selected.id AS selectedReadingUnitId,
            selected.ordinal AS selectedChapterOrdinal,
            selected.progressOrdinal AS selectedProgressOrdinal,
            (SELECT COUNT(*) FROM pages
             JOIN media_revisions AS selected_page_revision
               ON selected_page_revision.id = pages.revisionId
             JOIN comic_locations AS selected_page_location
               ON selected_page_location.id = selected_page_revision.locationId
             WHERE pages.revisionId = selected_current.mediaRevisionId
               AND selected_page_location.state != 'UNAVAILABLE') AS selectedPageCount,
            SUM(CASE WHEN ru.sourceState = 'SOURCE_REMOVED' THEN 1 ELSE 0 END) AS sourceRemovalCount
        FROM library_items
        JOIN current_metadata
          ON current_metadata.itemId = library_items.id
        JOIN metadata_snapshots
          ON metadata_snapshots.id = current_metadata.metadataSnapshotId
        JOIN reading_units AS ru
          ON ru.itemId = library_items.id
        JOIN reading_units AS selected
          ON selected.id = (
            SELECT candidate.id
            FROM reading_units AS candidate
            JOIN current_media_revisions AS candidate_current
              ON candidate_current.readingUnitId = candidate.id
            WHERE candidate.itemId = library_items.id
              AND EXISTS (
                SELECT 1 FROM pages AS candidate_page
                JOIN media_revisions AS candidate_revision
                  ON candidate_revision.id = candidate_page.revisionId
                JOIN comic_locations AS candidate_location
                  ON candidate_location.id = candidate_revision.locationId
                WHERE candidate_page.revisionId = candidate_current.mediaRevisionId
                  AND candidate_location.state != 'UNAVAILABLE'
              )
            ORDER BY
              CASE
                WHEN candidate.lastReadAtMillis > 0 THEN 0
                WHEN candidate.completed = 0 THEN 1
                ELSE 2
              END,
              CASE WHEN candidate.lastReadAtMillis > 0 THEN candidate.lastReadAtMillis ELSE 0 END DESC,
              candidate.ordinal,
              candidate.id
            LIMIT 1
          )
        JOIN current_media_revisions AS selected_current
          ON selected_current.readingUnitId = selected.id
        WHERE library_items.id = :itemId
        GROUP BY library_items.id, metadata_snapshots.title, metadata_snapshots.coverLocator,
            selected.id, selected.ordinal, selected.progressOrdinal, selected_current.mediaRevisionId
        """,
    )
    suspend fun seriesShelfOnce(itemId: Long): SeriesShelfRow?

    @Insert
    suspend fun insertItem(item: LibraryItemEntity): Long

    @Query(
        """
        UPDATE library_items
        SET historicalAvailable = 1, updatedAtMillis = :updatedAtMillis
        WHERE id = :itemId
        """,
    )
    suspend fun refreshLegacyItem(itemId: Long, updatedAtMillis: Long)

    @Insert
    suspend fun insertMetadata(metadata: MetadataSnapshotEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCurrentMetadata(current: CurrentMetadataEntity)

    @Insert
    suspend fun insertReadingUnit(readingUnit: ReadingUnitEntity): Long

    @Query(
        """
        UPDATE reading_units
        SET label = :label, progressOrdinal = :progressOrdinal, state = 'READY'
        WHERE id = :readingUnitId
        """,
    )
    suspend fun refreshLegacyReadingUnit(
        readingUnitId: Long,
        label: String,
        progressOrdinal: Int,
    )

    @Insert
    suspend fun insertRevision(revision: MediaRevisionEntity): Long

    @Query(
        """
        SELECT id FROM media_revisions
        WHERE readingUnitId = :readingUnitId
          AND structuralSignature = :structuralSignature
        """,
    )
    suspend fun revisionIdBySignature(
        readingUnitId: Long,
        structuralSignature: String,
    ): Long?

    @Query("SELECT COUNT(*) FROM pages WHERE revisionId = :revisionId")
    suspend fun pageCountForRevision(revisionId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCurrentRevision(current: CurrentMediaRevisionEntity)

    @Insert
    suspend fun insertPages(pages: List<PageEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClaims(claims: List<ProvenanceClaimEntity>)

    @Query("DELETE FROM current_metadata WHERE itemId = :itemId")
    suspend fun deleteCurrentMetadata(itemId: Long)

    @Query("DELETE FROM current_media_revisions WHERE readingUnitId = :readingUnitId")
    suspend fun deleteCurrentRevision(readingUnitId: Long)

    /**
     * W3 (A38): the flat shelf projection. Everything the shelf needs — one
     * row per visible comic, NO page rows loaded (pageCount via COUNT, cover
     * locator via the current revision's lowest-ordinal page, download-badge
     * fact via a COUNT over the catalog-download URI patterns). The 8-table
     * relation graph stays for Detail/Reader reads.
     *
     * A63: `downloadState` joins the live download job through the item's
     * `gallery_identity_v1`/`ownership` provenance claim — the same identity
     * link [RoomLibraryRepository.commitDownloadLocalAvailability] writes and
     * the download queue's identity queries read — collapsing the job state
     * onto the badge axis: WAIT/DOWNLOAD ⇒ 'QUEUED'; FINISH/SEALED ⇒
     * 'COMPLETE' (the Kotlin mapper additionally requires local pages, the
     * ruling's "FINISH/sealed with local pages" condition); no job (or
     * NONE/FAILED) ⇒ NULL ⇒ NONE. #C9: 'GONE' ⇒ 'DELISTED' (silent on the
     * axis, delisted), 'SEALED'+failureClass 'GONE' ⇒ 'COMPLETE_DELISTED' (both facts) —
     * decoded by [decodeShelfDownloadProjection]. The 'GONE' arm is defensive
     * — production never joins a GONE job to a library row (markGone writes
     * no row); the zero-page case reaches the shelf through
     * observeDelistedJobs → the composer.
     */
    @Query(
        """
        SELECT
            library_items.id AS itemId,
            current_metadata.metadataSnapshotId AS metadataSnapshotId,
            metadata_snapshots.title AS title,
            reading_units.id AS readingUnitId,
            reading_units.progressOrdinal AS progressOrdinal,
            reading_units.ordinal AS unitOrdinal,
            comic_locations.id AS locationId,
            comic_locations.sourceId AS sourceId,
            comic_locations.state AS locationState,
            current_media_revisions.mediaRevisionId AS revisionId,
            (SELECT COUNT(*) FROM pages
              WHERE pages.revisionId = current_media_revisions.mediaRevisionId) AS pageCount,
            cover.locatorKind AS coverLocatorKind,
            cover.documentUri AS coverDocumentUri,
            cover.archiveEntryPath AS coverArchiveEntryPath,
            cover.displayName AS coverDisplayName,
            metadata_snapshots.coverLocator AS sourceCoverLocator,
            (SELECT pc.claimValue FROM provenance_claims AS pc
              WHERE pc.itemId = library_items.id
                AND pc.namespace = 'gallery_identity_v1'
                AND pc.claimKey = 'ownership'
              LIMIT 1) AS serializedOwnership,
            (SELECT COUNT(*) FROM pages
              WHERE pages.revisionId = current_media_revisions.mediaRevisionId
                AND (pages.documentUri LIKE '%ehentai-downloads%'
                  OR pages.documentUri LIKE '%nhentai-downloads%'
                  OR pages.documentUri LIKE '%front-catalog-downloads%'
                  OR pages.documentUri LIKE '%index-catalog-downloads%'
                  OR pages.documentUri LIKE '%opds-downloads%'
                  OR pages.documentUri LIKE '%cloud-collection-downloads%'
                  OR pages.documentUri LIKE '%serialized-downloads%')) AS downloadedPages,
            (SELECT CASE
                WHEN jobs.state IN ('WAIT', 'DOWNLOAD') THEN 'QUEUED'
                WHEN jobs.state = 'SEALED' AND jobs.failureClass = 'GONE' THEN 'COMPLETE_DELISTED'
                WHEN jobs.state IN ('FINISH', 'SEALED') THEN 'COMPLETE'
                WHEN jobs.state = 'GONE' THEN 'DELISTED'
                ELSE NULL
              END
              FROM download_jobs AS jobs
              WHERE EXISTS (
                  SELECT 1 FROM provenance_claims AS pc
                  WHERE pc.itemId = library_items.id
                    AND pc.namespace = 'gallery_identity_v1'
                    AND pc.claimKey = 'ownership'
                    AND pc.claimValue = 'download:v' || jobs.identityVersion
                        || ':' || jobs.providerNamespace || ':' || jobs.canonicalItemId
              )
              ORDER BY jobs.updatedAtMillis DESC, jobs.id DESC
              LIMIT 1) AS downloadState
        FROM library_items
        JOIN current_metadata ON current_metadata.itemId = library_items.id
        JOIN metadata_snapshots ON metadata_snapshots.id = current_metadata.metadataSnapshotId
        JOIN reading_units ON reading_units.id = (
            SELECT candidate.id
            FROM reading_units AS candidate
            JOIN current_media_revisions AS candidate_current
              ON candidate_current.readingUnitId = candidate.id
            WHERE candidate.itemId = library_items.id
            ORDER BY candidate.ordinal, candidate.id
            LIMIT 1
        )
        JOIN current_media_revisions ON current_media_revisions.readingUnitId = reading_units.id
        JOIN media_revisions ON media_revisions.id = current_media_revisions.mediaRevisionId
        JOIN comic_locations ON comic_locations.id = media_revisions.locationId
        LEFT JOIN pages AS cover
          ON cover.revisionId = current_media_revisions.mediaRevisionId
         AND cover.ordinal = (
              SELECT MIN(ordinal) FROM pages
               WHERE pages.revisionId = current_media_revisions.mediaRevisionId
          )
        ORDER BY library_items.id
        """,
    )
    fun observeShelfRows(): Flow<List<ShelfRowEntity>>

    /**
     * W3 (A38): acquisition keys as a Flow so a provenance write re-emits the
     * shelf instead of being polled once per load (the old one-shot suspend).
     */
    @Query(
        """
        SELECT itemId, claimValue FROM provenance_claims
        WHERE namespace = :namespace
          AND claimKey = :claimKey
        """,
    )
    fun observeOwnershipClaims(namespace: String, claimKey: String): Flow<List<ItemOwnershipRow>>
}
