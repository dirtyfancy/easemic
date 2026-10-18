package com.easemic.app.model

@JvmInline value class SourceId(val value: Long)
@JvmInline value class ImportBatchId(val value: Long)
@JvmInline value class ScanRunId(val value: String)
@JvmInline value class WorkRequestId(val value: String)
@JvmInline value class EnumerationAttemptId(val value: String)
@JvmInline value class CandidateId(val value: Long)
@JvmInline value class LocationId(val value: Long)
@JvmInline value class LibraryItemId(val value: Long)
@JvmInline value class ReadingUnitId(val value: Long)
@JvmInline value class MediaRevisionId(val value: Long)

enum class GrantKind { TREE, DOCUMENT }

/**
 * A63 (2026-09-09 owner ruling): the per-item download-job axis for the shelf
 * badge, precomputed in the W3 projection's SQL (`download_jobs` state joined
 * through the item's `gallery_identity_v1`/`ownership` provenance claim).
 * NONE is the default so every pre-A63 construction keeps compiling with the
 * legacy `downloaded` boolean as the only download fact. Lives in the model
 * layer because Room's projection row (data) and the badge facts (ui) both
 * speak it.
 */
enum class ShelfDownloadState { NONE, QUEUED, COMPLETE }
enum class AdapterKind { EHVIEWER, GENERIC_DIRECTORY, COMIC_ARCHIVE, CATALOG_DOWNLOAD }
enum class SourceGrantState { NEEDS_VALIDATION, READY, NEEDS_REAUTHORIZATION, REMOVED }
enum class SourceState { NEEDS_VALIDATION, READY, DISCOVERING, READY_FOR_REVIEW, IMPORTING, PAUSED, COMPLETE, PARTIAL, NEEDS_REAUTHORIZATION, FAILED, REMOVED }
enum class LocationKind { DIRECTORY, ARCHIVE }
enum class LocationState { PRESENT, MISSING, UNAVAILABLE, INCOMPLETE, CONFLICTED }
enum class CandidateConfidence { HIGH, LOW, REJECTED }
enum class CandidateOutcome { PENDING, LOW_CONFIDENCE, RETRYABLE, NEW, UPDATED, UNCHANGED, INCOMPLETE, CONFLICT, REJECTED, FAILED }
enum class ScanPhase { VALIDATE, ENUMERATE, RECOGNIZE, REVIEW, COMMIT, RECONCILE }
enum class ScanRunState { ENQUEUED, RUNNING, IMPORTING, WAITING_FOR_REVIEW, PAUSE_REQUESTED, PAUSED, COMPLETE, PARTIAL, FAILED }
enum class PageLocatorKind { DOCUMENT, ARCHIVE_ENTRY }
enum class RecognitionKind { RECOVERABLE, REJECTED, RETRYABLE }
enum class CandidateCompleteness { COMPLETE, INCOMPLETE }
enum class RunProblem { GRANT_LOST, PROVIDER_UNAVAILABLE, SOURCE_CHANGED, UNSAFE_ARCHIVE, INSUFFICIENT_SPACE, PLATFORM_STOPPED, UNKNOWN }
enum class RecoveryAction { NONE, REAUTHORIZE, RETRY, FREE_SPACE, CHOOSE_DIFFERENT_SOURCE, REVIEW_RESULTS }
enum class GrantReleaseOutcome { NOT_RELEASED_SHARED, RELEASED, RELEASE_FAILED }

data class PageRef(
    val sourceId: SourceId,
    val locationId: LocationId,
    val revisionId: MediaRevisionId,
    val ordinal: Int,
    val locatorKind: PageLocatorKind,
    val documentUri: String?,
    val archiveEntryPath: String?,
    val displayName: String,
    /**
     * A30 §2.4 (S4b/H1): the provider's own id for this page's document, as recorded at
     * import. It survives a re-grant of the tree, which [documentUri] does not -- after the
     * user re-picks the parent folder the stored URIs still carry the old tree id, so
     * comparing URIs would read every present page as gone. Null only for legacy rows and
     * archive entries (which live inside one document, not next to it).
     */
    val documentId: String? = null,
)

data class PageAccessTarget(
    val readingUnitId: ReadingUnitId,
    val page: PageRef,
    val sourceGrantKind: GrantKind,
    val sourceGrantState: SourceGrantState,
    val sourceGrantUri: String,
    val locationKind: LocationKind,
    val locationState: LocationState,
    val locationDocumentUri: String,
    val providerDocumentId: String,
    val structuralSignature: String,
    val mimeType: String?,
    val size: Long?,
    val lastModifiedMillis: Long?,
    val width: Int,
    val height: Int,
    /** A62 (spec D2): persisted page-corruption marker; [open][com.easemic.app.storage.PageContentOpener] fast-fails on it. */
    val corrupted: Boolean = false,
)

data class LibraryItem(
    val id: LibraryItemId,
    val title: String,
    val pageCount: Int,
    val isAvailable: Boolean,
    val isIncomplete: Boolean,
    val readingUnitId: ReadingUnitId,
    val progressOrdinal: Int,
    val locationId: LocationId,
    val sourceId: SourceId,
    val sourceGrantKind: GrantKind,
    val pages: List<PageRef>,
    /**
     * A30 §2.2 (S4b/G2): the comic's folder is tombstoned or Android took its grant away.
     * The files are presumed fine -- nothing about the location changed -- so this is kept
     * apart from [isAvailable]'s other reason (the files really are gone), which is what
     * lets the reader show "Access lost" instead of the files-gone exit. [isAvailable] is
     * false whenever this is true, so every existing gate (shelf, Detail, Reader) closes.
     */
    val sourceAccessLost: Boolean = false,
    /**
     * W3 (A38): the shelf projection precomputes the catalog-download page
     * count in SQL (a shelf LibraryItem carries only the cover page, so
     * `pages.any { isCatalogDownloadUri }` would misjudge mixed revisions).
     * -1 = not projected — derive from [pages] exactly as before (Detail,
     * Reader, tests, fakes).
     */
    val downloadedPages: Int = -1,
    /**
     * A63 (2026-09-09 ruling): the shelf projection's per-item download-job
     * state (see [ShelfDownloadState]) joined through the item's ownership
     * claim — the badge's dual-state download axis. NONE = not projected /
     * no live job (Detail, Reader, tests, fakes keep the legacy
     * [downloadedPages] derivation).
     */
    val downloadState: ShelfDownloadState = ShelfDownloadState.NONE,
    /**
     * #C9: the source no longer serves this comic (a GONE download job).
     * Defaults false so Detail/Reader/test constructions are unaffected; only
     * the shelf projection populates it.
     */
    val delistedAtSource: Boolean = false,
    /** Managed source cover for a visible serialized row on this device. */
    val seriesCoverUri: String? = null,
)

data class DocumentRef(
    val documentId: String,
    val uri: String,
    val displayName: String,
    val mimeType: String?,
)

data class MetadataSnapshot(
    val title: String,
    val series: String?,
    val alternateSeries: String?,
    val creators: List<String>,
    val genres: List<String>,
    val tags: List<String>,
    val language: String?,
) {
    companion object {
        fun fallback(folderName: String) =
            MetadataSnapshot(
                title = folderName,
                series = null,
                alternateSeries = null,
                creators = emptyList(),
                genres = emptyList(),
                tags = emptyList(),
                language = null,
            )
    }
}

data class ImportCandidate(
    val sourceTreeUri: String,
    val sourceDisplayName: String,
    val metadata: MetadataSnapshot,
    val pages: List<DocumentRef>,
)

data class ParsedComicInfo(
    val metadata: MetadataSnapshot,
    val warning: Boolean,
    val webUrl: String? = null,
)

sealed interface ScanResult {
    data class Accepted(
        val candidate: ImportCandidate,
        val metadataWarning: Boolean,
    ) : ScanResult

    data class Rejected(val reason: ScanRejection) : ScanResult
}

enum class ScanRejection {
    NO_SUPPORTED_IMAGES,
    NO_DECODABLE_IMAGES,
    SOURCE_UNAVAILABLE,
    PROVIDER_ERROR,
}
