package com.easemic.app.data.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "authorized_sources", indices = [Index(value = ["grantKind", "providerIdentity"], unique = true)])
data class AuthorizedSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val grantKind: String, val adapterKind: String, val contentUri: String,
    val providerIdentity: String, val displayLabel: String, val grantState: String,
    val activeGeneration: Long, val completedGeneration: Long?,
    val createdAtMillis: Long, val updatedAtMillis: Long,
)

@Entity(tableName = "import_batches")
data class ImportBatchEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val createdAtMillis: Long)

@Entity(
    tableName = "import_batch_sources",
    primaryKeys = ["batchId", "sourceId"],
    foreignKeys = [
        ForeignKey(entity = ImportBatchEntity::class, parentColumns = ["id"], childColumns = ["batchId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AuthorizedSourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("sourceId")],
)
data class ImportBatchSourceEntity(val batchId: Long, val sourceId: Long)

@Entity(
    tableName = "comic_locations",
    foreignKeys = [ForeignKey(entity = AuthorizedSourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sourceId", "providerDocumentId"], unique = true)],
)
data class ComicLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val sourceId: Long,
    val providerDocumentId: String, val documentUri: String, val locationKind: String,
    val relativeDisplayPath: String, val structuralSignature: String?,
    val lastCompleteGeneration: Long?, val state: String,
)

@Entity(tableName = "library_items")
data class LibraryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val historicalAvailable: Boolean, val createdAtMillis: Long, val updatedAtMillis: Long,
)

@Entity(
    tableName = "metadata_snapshots",
    foreignKeys = [ForeignKey(entity = LibraryItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("itemId")],
)
data class MetadataSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val itemId: Long,
    val title: String, val series: String?, val alternateSeries: String?, val creators: String,
    val genres: String, val tags: String, val language: String?, val expectedPageCount: Int?,
    val createdAtMillis: Long, val coverLocator: String? = null,
    val synopsis: String? = null,
    val seriesFactsJson: String? = null,
)

@Entity(
    tableName = "current_metadata",
    primaryKeys = ["itemId"],
    foreignKeys = [
        ForeignKey(entity = LibraryItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MetadataSnapshotEntity::class, parentColumns = ["id"], childColumns = ["metadataSnapshotId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["metadataSnapshotId"], unique = true)],
)
data class CurrentMetadataEntity(val itemId: Long, val metadataSnapshotId: Long)

@Entity(
    tableName = "reading_units",
    foreignKeys = [ForeignKey(entity = LibraryItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["itemId", "ordinal"], unique = true),
        Index(value = ["itemId", "sourceChapterId"], unique = true),
    ],
)
data class ReadingUnitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val label: String,
    val ordinal: Int,
    val progressOrdinal: Int,
    val state: String,
    @ColumnInfo(defaultValue = "legacy:0") val sourceChapterId: String = "legacy:0",
    val sourceGroup: String? = null,
    @ColumnInfo(defaultValue = "ACTIVE") val sourceState: String = "ACTIVE",
    val sourceUpdatedAtMillis: Long? = null,
    @ColumnInfo(defaultValue = "0") val completed: Boolean = false,
    @ColumnInfo(defaultValue = "0") val lastReadAtMillis: Long = 0L,
)

@Entity(
    tableName = "media_revisions",
    foreignKeys = [
        ForeignKey(entity = ReadingUnitEntity::class, parentColumns = ["id"], childColumns = ["readingUnitId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ComicLocationEntity::class, parentColumns = ["id"], childColumns = ["locationId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("locationId"), Index(value = ["readingUnitId", "structuralSignature"], unique = true)],
)
data class MediaRevisionEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val readingUnitId: Long, val locationId: Long, val structuralSignature: String, val createdAtMillis: Long)

@Entity(
    tableName = "current_media_revisions",
    primaryKeys = ["readingUnitId"],
    foreignKeys = [
        ForeignKey(entity = ReadingUnitEntity::class, parentColumns = ["id"], childColumns = ["readingUnitId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaRevisionEntity::class, parentColumns = ["id"], childColumns = ["mediaRevisionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["mediaRevisionId"], unique = true)],
)
data class CurrentMediaRevisionEntity(val readingUnitId: Long, val mediaRevisionId: Long)

@Entity(
    tableName = "pages",
    primaryKeys = ["revisionId", "ordinal"],
    foreignKeys = [ForeignKey(entity = MediaRevisionEntity::class, parentColumns = ["id"], childColumns = ["revisionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("revisionId")],
)
data class PageEntity(
    val revisionId: Long, val ordinal: Int, val locatorKind: String, val documentId: String?,
    val documentUri: String?, val archiveEntryPath: String?, val displayName: String,
    val mimeType: String?, val size: Long?, val lastModifiedMillis: Long?, val width: Int, val height: Int,
    /** A62 (spec D2): the page's bytes were undecodable at import; the reader fast-fails on it. */
    val corrupted: Boolean = false,
)

@Entity(
    tableName = "provenance_claims",
    foreignKeys = [ForeignKey(entity = LibraryItemEntity::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE)],
    indices = [
        Index(value = ["itemId", "namespace", "claimKey", "claimValue"], unique = true),
        Index(value = ["namespace", "claimKey", "claimValue"]),
    ],
)
data class ProvenanceClaimEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val itemId: Long, val namespace: String, val claimKey: String, val claimValue: String)

@Entity(
    tableName = "scan_runs",
    foreignKeys = [ForeignKey(entity = AuthorizedSourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sourceId", "generation"], unique = true)],
)
data class ScanRunEntity(
    @PrimaryKey val id: String, val sourceId: Long, val generation: Long, val phase: String, val state: String,
    val enumerationComplete: Boolean, val pauseRequested: Boolean, val enumerationAttemptId: String?,
    val reviewAttemptId: String?,
    val checkpointLastCandidateId: Long?, val checkpointProcessedCount: Int, val discoveredCount: Int,
    val selectedCount: Int, val committedCount: Int, val highConfidenceCount: Int, val lowConfidenceCount: Int,
    val incompleteCount: Int, val rejectedCount: Int, val failedCount: Int, val problem: String?,
    val recoveryAction: String, val createdAtMillis: Long, val updatedAtMillis: Long,
)

@Entity(
    tableName = "active_scan_runs",
    primaryKeys = ["sourceId"],
    foreignKeys = [
        ForeignKey(entity = AuthorizedSourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ScanRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["runId"], unique = true)],
)
data class ActiveScanRunEntity(val sourceId: Long, val runId: String)

@Entity(
    tableName = "scan_work_requests",
    foreignKeys = [
        ForeignKey(
            entity = ScanRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["requestId"], unique = true)],
)
data class ScanWorkRequestEntity(
    @PrimaryKey val runId: String,
    val requestId: String,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "scan_candidates",
    foreignKeys = [ForeignKey(entity = ScanRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["runId", "enumerationAttemptId", "providerDocumentId"], unique = true), Index(value = ["runId", "enumerationAttemptId"])],
)
data class ScanCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val runId: String, val providerDocumentId: String,
    val documentUri: String, val enumerationAttemptId: String, val seedDisplayName: String,
    val locationKind: String, val selectedDirectly: Boolean, val recognitionKind: String,
    val relativeDisplayPath: String?, val confidence: String?, val completeness: String?, val outcome: String,
    val selected: Boolean, val metadataJson: String?, val expectedPageCount: Int?, val structuralSignature: String?,
    val warningsJson: String?, val rejectionReason: String?, val rejectionArchiveFailure: String?, val retryReason: String?,
)

@Entity(
    tableName = "scan_candidate_pages",
    primaryKeys = ["candidateId", "ordinal"],
    foreignKeys = [ForeignKey(entity = ScanCandidateEntity::class, parentColumns = ["id"], childColumns = ["candidateId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("candidateId")],
)
data class ScanCandidatePageEntity(
    val candidateId: Long, val ordinal: Int, val locatorKind: String, val documentId: String?,
    val documentUri: String?, val archiveEntryPath: String?, val displayName: String, val mimeType: String?,
    val size: Long?, val lastModifiedMillis: Long?, val width: Int, val height: Int,
    /**
     * A47: -1 = a flat page of the candidate itself (the single-comic shape, and every
     * row persisted before v13); >= 0 = the page belongs to the sub-comic with this
     * ordinal in `scan_candidate_sub_comics`. `ordinal` stays unique per candidate: for a
     * container it runs across sub-comics, and reconstitution slices per sub-comic.
     */
    val subComicOrdinal: Int = -1,
    /** A62 (spec D2): undecodable at scan time; persisted so a crash before commit loses nothing. */
    val corrupted: Boolean = false,
)

@Entity(
    tableName = "scan_candidate_claims",
    foreignKeys = [ForeignKey(entity = ScanCandidateEntity::class, parentColumns = ["id"], childColumns = ["candidateId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("candidateId")],
)
data class ScanCandidateClaimEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val candidateId: Long, val namespace: String, val claimKey: String, val claimValue: String,
    /** A47: same discriminator contract as [ScanCandidatePageEntity.subComicOrdinal]. */
    val subComicOrdinal: Int = -1,
)

/**
 * A47 (spec D1): one row per sub-comic of a container candidate. Persisted durably at
 * recognition time alongside the candidate's flat row, so a crash before commit loses
 * nothing and the committer can fan the container out into N library items.
 */
@Entity(
    tableName = "scan_candidate_sub_comics",
    foreignKeys = [ForeignKey(entity = ScanCandidateEntity::class, parentColumns = ["id"], childColumns = ["candidateId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["candidateId", "ordinal"], unique = true)],
)
data class ScanCandidateSubComicEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val candidateId: Long,
    val ordinal: Int,
    val relativePath: String,
    val metadataJson: String,
    val expectedPageCount: Int,
    val structuralSignature: String,
    val completeness: String,
)

data class LibraryItemGraph(
    @Embedded val item: LibraryItemEntity,
    @Relation(parentColumn = "id", entityColumn = "itemId")
    val metadataSnapshots: List<MetadataSnapshotEntity>,
    @Relation(parentColumn = "id", entityColumn = "itemId")
    val currentMetadata: CurrentMetadataEntity?,
    @Relation(entity = ReadingUnitEntity::class, parentColumn = "id", entityColumn = "itemId")
    val readingUnits: List<ReadingUnitGraph>,
)

data class ReadingUnitGraph(
    @Embedded val readingUnit: ReadingUnitEntity,
    @Relation(parentColumn = "id", entityColumn = "readingUnitId")
    val currentRevision: CurrentMediaRevisionEntity?,
    @Relation(entity = MediaRevisionEntity::class, parentColumn = "id", entityColumn = "readingUnitId")
    val revisions: List<MediaRevisionGraph>,
)

data class MediaRevisionGraph(
    @Embedded val revision: MediaRevisionEntity,
    @Relation(parentColumn = "locationId", entityColumn = "id")
    val location: ComicLocationEntity?,
    @Relation(parentColumn = "id", entityColumn = "revisionId")
    val pages: List<PageEntity>,
)

data class PageAccessTargetRow(
    val readingUnitId: Long,
    val sourceId: Long,
    val sourceGrantKind: String,
    val sourceGrantState: String,
    val sourceGrantUri: String,
    val locationId: Long,
    val locationKind: String,
    val locationState: String,
    val locationDocumentUri: String,
    val providerDocumentId: String,
    val structuralSignature: String,
    val revisionId: Long,
    val ordinal: Int,
    val locatorKind: String,
    val pageDocumentUri: String?,
    /** S4b/H1: the provider id the absence proof matches on (URIs do not survive a re-grant). */
    val pageDocumentId: String?,
    val archiveEntryPath: String?,
    val displayName: String,
    val mimeType: String?,
    val size: Long?,
    val lastModifiedMillis: Long?,
    val width: Int,
    val height: Int,
    /** A62 (spec D2): the persisted corruption marker from `pages.corrupted`. */
    val corrupted: Boolean,
)

/** Projection for the batch download-ownership claim export (S2 shelf dedup). */
data class ItemOwnershipRow(
    val itemId: Long,
    val claimValue: String,
)

/**
 * W3 (A38): one flat row per shelf comic from LibraryDao.observeShelfRows —
 * the projection that stops the shelf from loading every `pages` row. The
 * cover fields carry only the current revision's lowest-ordinal page; a
 * shelf `LibraryItem` is built with just that cover page plus the SQL-side
 * page count, so the badge/download facts must come precomputed
 * ([downloadedPages]) rather than derived from a full page list.
 *
 * A63 adds [downloadState]: the per-item download-job state joined through
 * the item's `gallery_identity_v1`/`ownership` claim, so the badge's
 * dual-state download axis (hollow QUEUED vs filled COMPLETE) is a SQL fact.
 * Null when there is no live or terminal-success job (NONE/FAILED/no row at
 * all).
 *
 * #C9 adds the fourth value `'DELISTED'` for a GONE job — the source no longer
 * serves this comic. It rides the SAME subquery (one job row, chosen by the
 * same recency order) rather than a second one, so the download axis and the
 * delisted flag can never be read off two different jobs. Decode both with
 * [decodeShelfDownloadProjection].
 */
data class ShelfRowEntity(
    val itemId: Long,
    val metadataSnapshotId: Long,
    val title: String,
    val readingUnitId: Long,
    val progressOrdinal: Int,
    val unitOrdinal: Int,
    val locationId: Long,
    val sourceId: Long,
    val locationState: String,
    val revisionId: Long,
    val pageCount: Int,
    val coverLocatorKind: String?,
    val coverDocumentUri: String?,
    val coverArchiveEntryPath: String?,
    val coverDisplayName: String?,
    val sourceCoverLocator: String?,
    val serializedOwnership: String?,
    val downloadedPages: Int,
    val downloadState: String?,
)

/** A30 §2.2/§5: per-folder comic count for the Folders row status line. */
data class SourceComicCountRow(val sourceId: Long, val comicCount: Int)

/** A30 §2.2/§5: last completed scan per folder, for the Folders row status line. */
data class SourceScanCompletionRow(val sourceId: Long, val completedAtMillis: Long)

/** #E2 Slice 4a: one reading unit's current revision signature and the state of its location. */
data class CurrentRevisionLocationRow(val structuralSignature: String, val locationState: String)
