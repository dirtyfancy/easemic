package com.easemic.app.data

import com.easemic.app.data.room.ReadingUnitEntity
import com.easemic.app.feature.download.DownloadCommitCompleteness
import com.easemic.app.feature.download.DownloadCommitMetadata
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.library.ChapterReconciliationPlan
import com.easemic.app.feature.library.ChapterShelfRow
import com.easemic.app.feature.library.SeriesShelfRow
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.SeriesDetailSnapshot
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.model.DocumentRef
import com.easemic.app.model.LibraryItem
import com.easemic.app.model.LibraryItemId
import com.easemic.app.model.ImportCandidate
import com.easemic.app.model.LocationId
import com.easemic.app.model.MediaRevisionId
import com.easemic.app.model.MetadataSnapshot
import com.easemic.app.model.PageAccessTarget
import com.easemic.app.model.PageRef
import com.easemic.app.model.ReadingUnitId
import com.easemic.app.model.SourceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface LibraryRepository {
    fun observeLibrary(): Flow<List<LibraryItem>>

    /**
     * Unfiltered read of every Library row, including ones ShelfVisibility
     * would hide (UNAVAILABLE/REMOVED-source items). Backup/restore snapshots
     * must never lose a row just because it is not on-shelf right now
     * (review MEDIUM-1). Default matches [observeLibrary] because most
     * implementations (fakes, and the download/collect paths) never apply a
     * ShelfVisibility-style filter to begin with; only [RoomLibraryRepository]
     * needs a distinct override.
     */
    fun observeAllItems(): Flow<List<LibraryItem>> = observeLibrary()

    fun observeItem(itemId: LibraryItemId): Flow<LibraryItem?>

    /** Reads exactly [readingUnitId]; serialized callers must never fall back to a representative unit. */
    fun observeItemAtUnit(
        itemId: LibraryItemId,
        readingUnitId: ReadingUnitId,
    ): Flow<LibraryItem?> = kotlinx.coroutines.flow.flowOf(null)

    /** #E2 4b serialized-series projection (Room); gallery and test repositories keep the empty defaults. */
    suspend fun seriesShelfOnce(itemId: Long): SeriesShelfRow? = null

    suspend fun chapterShelfRowsForItem(itemId: Long): List<ChapterShelfRow> = emptyList()

    fun observeChapterShelfRowsForItem(itemId: Long): Flow<List<ChapterShelfRow>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun activeReadingUnitsForItem(itemId: Long): List<ReadingUnitEntity> = emptyList()

    /**
     * A32 §1: [observeItem] plus the row's persisted metadata snapshot, which
     * `RoomLibraryRepository.toLibraryItem()` discards. Additive on purpose —
     * Reader / backup / shelf keep reading [observeItem] unchanged.
     *
     * The default fabricates nothing: [MetadataSnapshot.fallback] is title-only
     * with empty creators/genres/tags, so a fake (or any repository that does
     * not persist metadata) renders no tag section rather than inventing one.
     */
    fun observeItemDetail(itemId: LibraryItemId): Flow<LocalDetailRow?> =
        observeItem(itemId).map { item ->
            item?.let { LocalDetailRow(item = it, metadata = MetadataSnapshot.fallback(it.title)) }
        }

    suspend fun resolvePageAccessTarget(page: PageRef): PageAccessTarget?
    suspend fun updateProgressIfCurrent(
        readingUnitId: ReadingUnitId,
        revisionId: MediaRevisionId,
        locationId: LocationId,
        ordinal: Int,
    ): Boolean

    /**
     * Serialized chapter progress with the same current-revision/location fence as gallery progress. [completed]
     * only ever sets the flag: re-reading a finished chapter does not mark it unread again.
     */
    suspend fun updateChapterProgressIfCurrent(
        readingUnitId: ReadingUnitId,
        revisionId: MediaRevisionId,
        locationId: LocationId,
        ordinal: Int,
        completed: Boolean,
        lastReadAtMillis: Long,
    ): Boolean = false

    /** Best-effort restore path for encrypted backup (no revision fencing). */
    suspend fun setReadingProgress(readingUnitId: ReadingUnitId, ordinal: Int) {}

    /**
     * Best-effort archive restore for a serialized chapter. The chapter's
     * revision has just been committed by the restore transaction, so the
     * restore path does not have a reader revision/location fence to provide.
     * Completion is monotonic: restoring an older archive cannot mark a
     * finished chapter unread.
     */
    suspend fun setChapterProgress(
        readingUnitId: ReadingUnitId,
        ordinal: Int,
        completed: Boolean,
        lastReadAtMillis: Long,
    ) {}

    /** Restore the source-side chapter state carried by a serialized archive. */
    suspend fun setChapterSourceState(readingUnitId: ReadingUnitId, sourceState: String) {}

    suspend fun markLocationUnavailable(locationId: LocationId)
    suspend fun markLocationUnavailableIfCurrent(
        readingUnitId: ReadingUnitId,
        revisionId: MediaRevisionId,
        locationId: LocationId,
        requireSourceReauthorization: Boolean,
    ): Boolean

    /**
     * A30 §2.2 (S4b/G2): the reader could not open a page because the folder's grant is
     * gone. ACCESS problems change SOURCE state only -- the folder flips to
     * `NEEDS_REAUTHORIZATION` and every comic_locations row is left untouched, so nothing
     * is ever recorded as missing on the strength of a permission failure. Fenced on the
     * reading unit's current revision; returns false when the pointer moved underneath.
     */
    suspend fun markSourceAccessLostIfCurrent(
        readingUnitId: ReadingUnitId,
        revisionId: MediaRevisionId,
        locationId: LocationId,
    ): Boolean = false

    /**
     * A30 §2.4 (S4b/B4): records that this comic's files are no longer on the phone, using
     * the same MISSING state a rescan's reconciliation writes -- so the shelf hides it
     * silently, the records and reading progress stay, and the structural signature
     * re-links it when the files come back. Fenced on the reading unit's current revision,
     * exactly like [markLocationUnavailableIfCurrent]; returns false when the pointer moved
     * underneath (nothing is written then).
     */
    suspend fun markLocationMissingIfCurrent(
        readingUnitId: ReadingUnitId,
        revisionId: MediaRevisionId,
        locationId: LocationId,
    ): Boolean = false

    /**
     * Batch export of every downloaded catalog item's token-free acquisition key
     * (itemId → `<providerNamespace>://<canonicalItemId>`), for the Library shelf
     * to dedup local downloads against collected/cloud cards. Best-effort: empty
     * when unavailable so the shelf still renders with less dedup.
     */
    suspend fun downloadAcquisitionKeys(): Map<Long, String> = emptyMap()

    /**
     * W3 (A38): [downloadAcquisitionKeys] as a reactive Flow — a provenance
     * claim write re-emits the shelf instead of the old one-shot-per-load
     * poll. Default stays the polled shape for fakes; the Room override is
     * live.
     */
    fun observeDownloadAcquisitionKeys(): Flow<Map<Long, String>> = kotlinx.coroutines.flow.flowOf(emptyMap())

    /**
     * Reactive local serialized-series identities keyed by library item ID.
     * These come from the persisted ownership claim, rather than from an
     * acquisition locator that may change between source refreshes.
     */
    fun observeSerializedSeriesLedgerKeys(): Flow<Map<Long, String>> =
        kotlinx.coroutines.flow.flowOf(emptyMap())

    /**
     * W3 (A38): the shelf's projection read — one row per visible comic with
     * pageCount/cover/download facts resolved in SQL, no page rows loaded.
     * Defaults to [observeLibrary] so fakes and non-Room repositories keep
     * their behavior; only the Room override projects.
     */
    fun observeShelf(): Flow<List<LibraryItem>> = observeLibrary()
}

interface LegacyCatalogCompatibility {
    suspend fun upsertLegacyDirectory(candidate: ImportCandidate): LibraryItemId
    suspend fun sourceValidationTargets(): List<LegacySourceValidationTarget>
    suspend fun setSourceLocationsAvailable(sourceId: SourceId, available: Boolean)
}

/** Durable Room boundary for collecting a serialized source's chapter list. */
interface SerializedSeriesLibrary {
    suspend fun reconcileSeries(
        series: GalleryIdentityV1,
        title: String,
        coverLocator: String?,
        chapters: ChapterListResult,
    ): SeriesReconcileOutcome

    /** Persists supplied detail facts without creating or reconciling chapters. */
    suspend fun updateSeriesDetailSnapshot(
        series: SeriesIdentityV2,
        result: ChapterListResult,
    ) {}

    suspend fun seriesDetailSnapshot(series: SeriesIdentityV2): SeriesDetailSnapshot? = null

    suspend fun restoreSeriesDetailSnapshot(
        series: SeriesIdentityV2,
        snapshot: SeriesDetailSnapshot,
        fallbackTitle: String,
        fallbackCover: String?,
    ) {}
}

data class SeriesReconcileOutcome(
    val itemId: Long,
    val plan: ChapterReconciliationPlan,
)

/**
 * Local download → Library availability (spec §4.6). Commits on FINISH and —
 * since A36 — on the GONE truncation wind-down of a job the user wanted on
 * device ([DownloadCommitCompleteness.Truncated]); never Collect/R2; never
 * merges Import/SAF ownership into a download identity.
 */
interface DownloadLibraryAvailability {
    suspend fun commitDownloadLocalAvailability(
        identity: GalleryIdentityV1,
        downloadLocationUri: String,
        downloadRevision: Int,
        title: String,
        pages: List<DocumentRef>,
        /** A32 §2: catalog facts from the locator; empty ⇒ commit exactly as before. */
        metadata: DownloadCommitMetadata = DownloadCommitMetadata(),
        /** A36: PRESENT + `pages.size` by default, INCOMPLETE + the pinned count for a truncated seal. */
        completeness: DownloadCommitCompleteness = DownloadCommitCompleteness.Complete,
    ): LibraryItemId

    /**
     * #E2 Slice 4a: commits one serialized chapter's pages into the reading unit the collector created for it
     * (matched by sourceChapterId) under the series item that owns [series]'s download ownership claim. Never creates
     * the item or the unit and never touches series metadata or unit progress. Throws when either is missing, so the
     * caller's FINISH transaction rolls back.
     */
    suspend fun commitChapterDownloadLocalAvailability(
        series: GalleryIdentityV1,
        chapterId: String,
        downloadLocationUri: String,
        downloadRevision: Int,
        title: String,
        pages: List<DocumentRef>,
        completeness: DownloadCommitCompleteness = DownloadCommitCompleteness.Complete,
    ): LibraryItemId = error("chapter commits need the Room library")

    /** True when [chapterId]'s unit under [series] currently shows this download revision's pages. */
    suspend fun chapterDownloadCommitted(series: GalleryIdentityV1, chapterId: String, downloadRevision: Int): Boolean = false

    /** Removes download-owned local availability only (bookmarks/cloud/history untouched). */
    suspend fun removeDownloadLocalAvailability(identity: GalleryIdentityV1): Boolean

    /**
     * Removes download-owned availability for selected serialized chapters only.
     * The series row, follow bookmark, and other chapters remain untouched.
     */
    suspend fun removeChapterLocalAvailability(
        series: GalleryIdentityV1,
        chapterIds: Set<String>,
    ): Boolean = false

    suspend fun downloadOnDeviceAssociationCount(identity: GalleryIdentityV1): Int

    /** Library item for a finished download identity, if one was committed. */
    suspend fun findDownloadItemId(identity: GalleryIdentityV1): LibraryItemId? = null

    /** Reverse of [findDownloadItemId] for Library → Downloads delete. */
    suspend fun findDownloadIdentity(itemId: LibraryItemId): GalleryIdentityV1? = null

    /** #E2 4b Task A3: the serialized series a Library item downloads into; null for galleries and imports. */
    suspend fun findSerializedSeriesIdentity(itemId: LibraryItemId): GalleryIdentityV1? = null

    /**
     * Re-review r2 BLOCKING-1: the directories the Library's own page rows
     * point into for catalog downloads (`*-downloads` trees). The ONE durable
     * record of which on-disk tree a shelf entry depends on — the legacy-tree
     * sweep treats these as referenced. Default empty for fakes.
     */
    suspend fun catalogDownloadPageDirectories(): Set<java.io.File> = emptySet()
}

data class LegacySourceValidationTarget(
    val sourceId: SourceId,
    val contentUri: String,
)

/**
 * One local row's Detail facts (A32 §1): the shelf/reader [LibraryItem] plus
 * the persisted [MetadataSnapshot] behind it.
 */
data class LocalDetailRow(
    val item: LibraryItem,
    val metadata: MetadataSnapshot,
)
