package com.easemic.app.model

data class AuthorizedSourceDraft(
    val grantKind: GrantKind,
    val adapterKind: AdapterKind,
    val contentUri: String,
    val providerIdentity: String,
    val displayLabel: String,
)

data class AuthorizedSource(
    val id: SourceId,
    val grantKind: GrantKind,
    val adapterKind: AdapterKind,
    val contentUri: String,
    val providerIdentity: String,
    val displayLabel: String,
    val grantState: SourceGrantState,
    val state: SourceState,
    val activeGeneration: Long,
    val completedGeneration: Long?,
    val lastSummary: RunSummary?,
    val lastFailure: RunFailure?,
)

data class ImportBatch(val id: ImportBatchId, val sourceIds: List<SourceId>, val createdAtMillis: Long)
data class RunCheckpoint(val lastCandidateId: CandidateId?, val processedCount: Int)
/**
 * [newlyAdded] (S4b/G4) is a read-side count, not a stored counter (no schema change): the
 * run's candidates whose commit outcome was NEW. [committed] includes UPDATED/UNCHANGED
 * re-finds, so only [newlyAdded] answers "did anything actually arrive on the shelf?".
 * Filled in where it is needed (the per-source latest-run projection); 0 elsewhere.
 */
data class RunSummary(val discovered: Int, val highConfidence: Int, val lowConfidence: Int, val incomplete: Int, val rejected: Int, val selected: Int, val committed: Int, val failed: Int, val newlyAdded: Int = 0)
data class RunFailure(val problem: RunProblem, val recoveryAction: RecoveryAction)
/**
 * A52 (owner bug backlog, ledger 2026-09-04): read-side mirror of scan_runs.updatedAtMillis
 * — the column exists, only the mapping dropped it. Default 0 keeps every existing test
 * constructor compiling and reads as "no staleness opinion" (the tracker's processStartMs
 * gate is disabled at 0).
 */
data class ScanRun(
    val id: ScanRunId, val sourceId: SourceId, val generation: Long, val phase: ScanPhase,
    val state: ScanRunState, val enumerationComplete: Boolean, val pauseRequested: Boolean,
    val enumerationAttemptId: EnumerationAttemptId?, val checkpoint: RunCheckpoint,
    val summary: RunSummary, val failure: RunFailure?, val updatedAtMillis: Long = 0L,
)

data class PageDescriptor(
    val documentId: String?, val documentUri: String?, val archiveEntryPath: String?,
    val displayName: String, val mimeType: String?, val size: Long?, val lastModifiedMillis: Long?,
    val width: Int, val height: Int,
    /**
     * A62 (spec D2): this entry exists but its bytes could not be decoded at import time.
     * The page KEEPS its slot (nothing is silently dropped any more); the reader renders
     * the broken-image glyph page and re-attempts the decode only when the user retries.
     */
    val corrupted: Boolean = false,
)
data class ProvenanceValue(val namespace: String, val key: String, val value: String)
data class CandidateSeed(val providerDocumentId: String, val documentUri: String, val displayName: String, val locationKind: LocationKind, val selectedDirectly: Boolean)
data class RecognizedCandidate(
    val providerDocumentId: String, val documentUri: String, val locationKind: LocationKind,
    val relativeDisplayPath: String, val confidence: CandidateConfidence,
    val completeness: CandidateCompleteness, val metadata: MetadataSnapshot,
    val expectedPageCount: Int?, val pages: List<PageDescriptor>, val structuralSignature: String,
    val provenance: List<ProvenanceValue>, val warnings: Set<String>,
    /**
     * A47 (spec D1): non-empty only for a container archive (top level = N image
     * directories). When it is non-empty the flat fields above describe nothing at commit
     * time -- a container is not one comic -- and the committer reads [subComics] instead.
     */
    val subComics: List<RecognizedSubComic> = emptyList(),
)

/**
 * A47: one comic inside a container archive. [relativePath] is the sub-directory inside
 * the archive (empty never applies here: a single-comic archive stays in the flat fields).
 * [metadata]/[expectedPageCount]/[pages]/[structuralSignature]/[provenance] follow the same
 * ImportedTitle/dedup rules as a top-level comic; [completeness] is per sub-comic so D3's
 * INCOMPLETE precedence can be evaluated per comic, not for the archive as a whole.
 */
data class RecognizedSubComic(
    val relativePath: String,
    val metadata: MetadataSnapshot,
    val expectedPageCount: Int,
    val pages: List<PageDescriptor>,
    val structuralSignature: String,
    val provenance: List<ProvenanceValue>,
    val completeness: CandidateCompleteness,
)
sealed interface SourceFailure {
    data object Unavailable : SourceFailure
    data object ProviderError : SourceFailure
}
sealed interface AdapterEnumeration {
    data class Complete(val seeds: List<CandidateSeed>) : AdapterEnumeration
    data class Failed(val reason: SourceFailure) : AdapterEnumeration
}
sealed interface CandidateRejection { data object NoSafePages : CandidateRejection; data object IncidentalFiles : CandidateRejection; data class UnsafeArchive(val reason: ArchiveFailure) : CandidateRejection }
sealed interface CandidateFailure {
    data object SourceChanged : CandidateFailure
    data object ProviderUnavailable : CandidateFailure
    data object InsufficientSpace : CandidateFailure
}
/**
 * TOO_MANY_ENTRIES / TOO_MANY_IMAGES / TOTAL_TOO_LARGE are no longer produced: A47 removed the
 * volume caps (how much a user imports is the user's business). They stay declared because
 * rejections are persisted by name and read back with `ArchiveFailure.valueOf`
 * (RoomScanRunRepository), so deleting them would throw on a pre-existing row.
 */
enum class ArchiveFailure { CORRUPT, TRUNCATED, UNSAFE_PATH, DUPLICATE_PATH, TOO_MANY_ENTRIES, TOO_MANY_IMAGES, ENTRY_TOO_LARGE, TOTAL_TOO_LARGE, EXCESSIVE_RATIO, NESTED_ARCHIVE, ENCRYPTED_OR_UNSUPPORTED, IMAGE_UNSAFE, INSUFFICIENT_SPACE, SOURCE_CHANGED, SOURCE_UNAVAILABLE }
sealed interface ArchiveIndexResult {
    data class Accepted(
        val pages: List<PageDescriptor>,
        val comicInfoBytes: ByteArray?,
        val structuralSignature: String,
    ) : ArchiveIndexResult

    /**
     * A47 (spec D5): a container archive (top level = N image directories) recognized as
     * N comics. Only [SafArchiveCandidateIndexer] produces this after classifying via
     * [ArchiveComicsResult]; rejections map 1:1 to [Rejected] as before.
     */
    data class ContainerAccepted(
        val comics: List<ArchiveComic>,
        val skippedPaths: List<String>,
    ) : ArchiveIndexResult

    data class Rejected(val reason: ArchiveFailure) : ArchiveIndexResult
}

/**
 * A47: one archive can hold N comics. [relativePath] is the directory inside the archive that
 * the comic came from, empty for a plain single-comic archive whose images sit at the top level.
 */
data class ArchiveComic(
    val relativePath: String,
    val pages: List<PageDescriptor>,
    val comicInfoBytes: ByteArray?,
    val structuralSignature: String,
)

sealed interface ArchiveComicsResult {
    /**
     * [skippedPaths] are directories that looked like comics but held too few images, plus
     * incidental junk. They are reported rather than failing the import: one bad directory in a
     * 600-comic dump must not cost the user the other 599.
     */
    data class Accepted(
        val comics: List<ArchiveComic>,
        val skippedPaths: List<String>,
    ) : ArchiveComicsResult

    data class Rejected(val reason: ArchiveFailure) : ArchiveComicsResult
}
sealed interface CandidateRecognition { data class Recoverable(val candidate: RecognizedCandidate) : CandidateRecognition; data class Rejected(val reason: CandidateRejection) : CandidateRecognition; data class Retryable(val reason: CandidateFailure) : CandidateRecognition }
data class DiscoveredCandidate(val seed: CandidateSeed, val recognition: CandidateRecognition)
data class CandidateCommitResult(val itemId: LibraryItemId?, val outcome: CandidateOutcome)
data class CandidatePreview(
    val id: CandidateId,
    val recognitionKind: RecognitionKind,
    val confidence: CandidateConfidence?,
    val completeness: CandidateCompleteness?,
    val outcome: CandidateOutcome,
    val selected: Boolean,
    val title: String?,
    val actualPageCount: Int?,
    val cover: PageDescriptor?,
    val warnings: Set<String>?,
    val rejection: CandidateRejection?,
    val retryReason: CandidateFailure?,
    val documentUri: String? = null,
    val providerDocumentId: String? = null,
    val structuralSignature: String? = null,
    val displayName: String? = null,
)
data class RunPreview(val run: ScanRun, val candidates: List<CandidatePreview>)
data class SourceRelease(val sourceId: SourceId, val fencedRunId: ScanRunId?, val grantReleaseOutcome: GrantReleaseOutcome)
