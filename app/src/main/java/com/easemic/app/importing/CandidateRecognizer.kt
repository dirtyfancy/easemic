package com.easemic.app.importing

import com.easemic.app.importing.archive.MIN_UNRECOGNIZED_COMIC_IMAGES
import com.easemic.app.importing.metadata.MarkerParseResult
import com.easemic.app.importing.metadata.MetadataResolver
import com.easemic.app.model.ArchiveFailure
import com.easemic.app.model.ArchiveIndexResult
import com.easemic.app.model.AuthorizedSource
import com.easemic.app.model.CandidateCompleteness
import com.easemic.app.model.CandidateConfidence
import com.easemic.app.model.CandidateFailure
import com.easemic.app.model.CandidateRecognition
import com.easemic.app.model.CandidateRejection
import com.easemic.app.model.CandidateSeed
import com.easemic.app.model.LocationKind
import com.easemic.app.model.MetadataSnapshot
import com.easemic.app.model.PageDescriptor
import com.easemic.app.model.ParsedComicInfo
import com.easemic.app.model.ProvenanceValue
import com.easemic.app.model.RecognizedCandidate
import com.easemic.app.model.RecognizedSubComic
import com.easemic.app.model.ScanRejection
import kotlinx.coroutines.CancellationException

fun interface CandidateRecognitionService {
    suspend fun recognize(
        source: AuthorizedSource,
        seed: CandidateSeed,
    ): CandidateRecognition
}

fun interface ArchiveCandidateIndexer {
    suspend fun index(
        source: AuthorizedSource,
        seed: CandidateSeed,
    ): ArchiveIndexResult

    suspend fun indexFresh(
        source: AuthorizedSource,
        seed: CandidateSeed,
    ): ArchiveIndexResult = index(source, seed)
}

class CandidateRecognizer(
    private val directoryScanner: ImageDirectoryScanner,
    private val metadataResolver: MetadataResolver = MetadataResolver(),
    private val archiveIndexer: ArchiveCandidateIndexer? = null,
    private val readerSupports: (PageDescriptor) -> Boolean = ReaderSupportPolicy::supports,
) : CandidateRecognitionService {
    override suspend fun recognize(
        source: AuthorizedSource,
        seed: CandidateSeed,
    ): CandidateRecognition =
        try {
            when (seed.locationKind) {
                LocationKind.DIRECTORY -> recognizeDirectory(seed)
                LocationKind.ARCHIVE -> recognizeArchive(source, seed)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            CandidateRecognition.Retryable(CandidateFailure.ProviderUnavailable)
        }

    private suspend fun recognizeDirectory(seed: CandidateSeed): CandidateRecognition {
        val before = directoryScanner.observe(
            directoryUri = seed.documentUri,
            providerDocumentId = seed.providerDocumentId,
            knownDisplayName = seed.displayName,
        )
        if (before is DirectoryObservation.Rejected) return before.toRecognition()
        val readable = before as DirectoryObservation.Readable
        if (readable.pages.isEmpty()) {
            return CandidateRecognition.Rejected(CandidateRejection.NoSafePages)
        }

        val metadata = metadataResolver.resolve(
            displayName = readable.displayName,
            comicInfo = readable.comicInfo,
            marker = readable.marker,
            readablePageCount = readable.pages.size,
        )
        val recognizedMetadata =
            readable.comicInfo?.warning == false ||
                readable.marker is MarkerParseResult.Supported
        val malformedMetadata =
            readable.comicInfo?.warning == true ||
                readable.marker is MarkerParseResult.Malformed ||
                readable.marker is MarkerParseResult.Unsupported
        // A30 §3 rule 3: a directory with fewer than three images, no archives and no
        // recognized metadata is incidental and is ignored silently -- ROOT INCLUDED.
        // B1 (emulator E2E step 2): `seed.selectedDirectly` used to skip this rejection, so
        // a granted folder holding two loose PNGs (`A30-Incidental`) was imported as one
        // comic. The root is not privileged: what makes a directory a comic is its content.
        // Metadata still wins over the count -- an EhViewer gallery folder with a valid
        // `.ehviewer` marker (or a ComicInfo.xml, or a malformed one that still declares
        // intent) stays a comic at any page count.
        if (
            !recognizedMetadata &&
            !malformedMetadata &&
            readable.pages.size < MIN_UNRECOGNIZED_PAGES
        ) {
            return CandidateRecognition.Rejected(CandidateRejection.IncidentalFiles)
        }

        val beforeSignature = StructuralSignature.from(readable.pages)
        // #C71: the stability re-check compares the cheap listing and reuses the decoded
        // pages when nothing moved, instead of decoding every image a second time.
        val after = directoryScanner.reobserve(
            previous = readable,
            directoryUri = seed.documentUri,
            providerDocumentId = seed.providerDocumentId,
            knownDisplayName = seed.displayName,
        )
        if (after !is DirectoryObservation.Readable) {
            return CandidateRecognition.Retryable(CandidateFailure.SourceChanged)
        }
        if (readable != after) {
            return CandidateRecognition.Retryable(CandidateFailure.SourceChanged)
        }

        val readerUnsupported = readable.pages.any { !readerSupports(it) }
        val incomplete =
            readerUnsupported ||
                metadata.expectedPageCount?.let { it > metadata.actualPageCount } == true
        val confidence = when {
            seed.selectedDirectly -> CandidateConfidence.HIGH
            malformedMetadata -> CandidateConfidence.LOW
            recognizedMetadata -> CandidateConfidence.HIGH
            else -> CandidateConfidence.LOW
        }
        val warnings = linkedSetOf<String>().apply {
            addAll(metadata.warnings)
            if (readerUnsupported) add("READER_UNSUPPORTED_PAGE")
        }
        return CandidateRecognition.Recoverable(
            RecognizedCandidate(
                providerDocumentId = seed.providerDocumentId,
                documentUri = seed.documentUri,
                locationKind = seed.locationKind,
                relativeDisplayPath = seed.displayName,
                confidence = confidence,
                completeness = if (incomplete) {
                    CandidateCompleteness.INCOMPLETE
                } else {
                    CandidateCompleteness.COMPLETE
                },
                metadata = metadata.metadata,
                expectedPageCount = metadata.expectedPageCount,
                pages = readable.pages,
                structuralSignature = beforeSignature,
                provenance = metadata.provenance.map { (qualifiedKey, value) ->
                    ProvenanceValue(
                        namespace = qualifiedKey.substringBefore('.'),
                        key = qualifiedKey.substringAfter('.'),
                        value = value,
                    )
                },
                warnings = warnings,
            ),
        )
    }

    private suspend fun recognizeArchive(
        source: AuthorizedSource,
        seed: CandidateSeed,
    ): CandidateRecognition {
        val indexer = archiveIndexer
            ?: return CandidateRecognition.Retryable(CandidateFailure.ProviderUnavailable)
        val before = indexer.index(source, seed)
        if (before is ArchiveIndexResult.Rejected) {
            return before.toRecognition()
        }
        val after = indexer.indexFresh(source, seed)
        if (after is ArchiveIndexResult.Rejected) {
            return after.toRecognition()
        }
        when (before) {
            is ArchiveIndexResult.Accepted -> {
                val accepted = before
                val acceptedAfter = after as? ArchiveIndexResult.Accepted
                    ?: return CandidateRecognition.Retryable(CandidateFailure.SourceChanged)
                if (
                    accepted.pages != acceptedAfter.pages ||
                    !nullableContentEquals(accepted.comicInfoBytes, acceptedAfter.comicInfoBytes) ||
                    accepted.structuralSignature != acceptedAfter.structuralSignature
                ) {
                    return CandidateRecognition.Retryable(CandidateFailure.SourceChanged)
                }
            }
            is ArchiveIndexResult.ContainerAccepted -> {
                val containerAfter = after as? ArchiveIndexResult.ContainerAccepted
                    ?: return CandidateRecognition.Retryable(CandidateFailure.SourceChanged)
                if (containerFacts(before) != containerFacts(containerAfter)) {
                    return CandidateRecognition.Retryable(CandidateFailure.SourceChanged)
                }
            }
            else -> return CandidateRecognition.Retryable(CandidateFailure.SourceChanged)
        }
        return when (before) {
            is ArchiveIndexResult.Accepted -> recognizeSingleComicArchive(seed, before)
            is ArchiveIndexResult.ContainerAccepted -> recognizeContainerArchive(seed, before)
            else -> error("unreachable: rejections handled above")
        }
    }

    /**
     * A47 (spec D5): the freshness double-read compares per-comic facts -- relativePath,
     * pages, ComicInfo bytes, structural signature -- plus the skipped-path list, so a
     * container that changed mid-read is Retryable(SourceChanged) exactly like a plain
     * archive that changed mid-read.
     */
    private fun containerFacts(result: ArchiveIndexResult.ContainerAccepted) =
        result.comics.map { comic ->
            ComicObservation(
                relativePath = comic.relativePath,
                pages = comic.pages,
                comicInfoBytes = comic.comicInfoBytes,
                structuralSignature = comic.structuralSignature,
            )
        } to result.skippedPaths

    private data class ComicObservation(
        val relativePath: String,
        val pages: List<PageDescriptor>,
        val comicInfoBytes: ByteArray?,
        val structuralSignature: String,
    ) {
        override fun equals(other: Any?): Boolean =
            other is ComicObservation &&
                relativePath == other.relativePath &&
                pages == other.pages &&
                structuralSignature == other.structuralSignature &&
                java.util.Arrays.equals(comicInfoBytes, other.comicInfoBytes)

        override fun hashCode(): Int =
            relativePath.hashCode() * 31 + structuralSignature.hashCode()
    }

    private suspend fun recognizeSingleComicArchive(
        seed: CandidateSeed,
        accepted: ArchiveIndexResult.Accepted,
    ): CandidateRecognition {
        val parsedComicInfo = accepted.comicInfoBytes?.let {
            ComicInfoParser().parse(it, seed.displayName)
        }
        val metadata = metadataResolver.resolve(
            displayName = seed.displayName,
            comicInfo = parsedComicInfo,
            marker = null,
            readablePageCount = accepted.pages.size,
        )
        val readerUnsupported = accepted.pages.any { !readerSupports(it) }
        return CandidateRecognition.Recoverable(
            RecognizedCandidate(
                providerDocumentId = seed.providerDocumentId,
                documentUri = seed.documentUri,
                locationKind = seed.locationKind,
                relativeDisplayPath = seed.displayName,
                confidence = CandidateConfidence.HIGH,
                completeness = if (readerUnsupported) {
                    CandidateCompleteness.INCOMPLETE
                } else {
                    CandidateCompleteness.COMPLETE
                },
                metadata = metadata.metadata,
                expectedPageCount = metadata.expectedPageCount,
                pages = accepted.pages,
                structuralSignature = accepted.structuralSignature,
                // A46: the single-archive path used to hard-code an empty list because
                // the resolver could only produce marker claims (markers never exist
                // inside an archive). ComicInfo `<Web>` provenance (A46 :795) made that
                // stale — an archive's claims now flow exactly like a directory's.
                provenance = metadata.provenance.map { (qualifiedKey, value) ->
                    ProvenanceValue(
                        namespace = qualifiedKey.substringBefore('.'),
                        key = qualifiedKey.substringAfter('.'),
                        value = value,
                    )
                },
                warnings = metadata.warnings +
                    if (readerUnsupported) setOf("READER_UNSUPPORTED_PAGE") else emptySet(),
            ),
        )
    }

    /**
     * A47 (spec D1/D4): one candidate for the whole container, carrying one sub-comic per
     * image directory. The flat fields describe nothing -- flat pages stay empty and the
     * flat signature is the empty string -- and each sub-comic resolves its own
     * ImportedTitle/metadata/warnings from its own ComicInfo.xml with the sub-directory
     * name as displayName (marker = null, same rule as today's archive path).
     */
    private fun recognizeContainerArchive(
        seed: CandidateSeed,
        container: ArchiveIndexResult.ContainerAccepted,
    ): CandidateRecognition {
        val warnings = linkedSetOf<String>()
        val subComics = container.comics.map { comic ->
            val displayName = comic.relativePath.substringAfterLast('/')
            val parsedComicInfo = comic.comicInfoBytes?.let {
                ComicInfoParser().parse(it, displayName)
            }
            val metadata = metadataResolver.resolve(
                displayName = displayName,
                comicInfo = parsedComicInfo,
                marker = null,
                readablePageCount = comic.pages.size,
            )
            warnings.addAll(metadata.warnings)
            val readerUnsupported = comic.pages.any { !readerSupports(it) }
            if (readerUnsupported) warnings += "READER_UNSUPPORTED_PAGE"
            RecognizedSubComic(
                relativePath = comic.relativePath,
                metadata = metadata.metadata,
                expectedPageCount = comic.pages.size,
                pages = comic.pages,
                structuralSignature = comic.structuralSignature,
                provenance = metadata.provenance.map { (qualifiedKey, value) ->
                    ProvenanceValue(
                        namespace = qualifiedKey.substringBefore('.'),
                        key = qualifiedKey.substringAfter('.'),
                        value = value,
                    )
                },
                completeness = if (readerUnsupported) {
                    CandidateCompleteness.INCOMPLETE
                } else {
                    CandidateCompleteness.COMPLETE
                },
            )
        }
        return CandidateRecognition.Recoverable(
            RecognizedCandidate(
                providerDocumentId = seed.providerDocumentId,
                documentUri = seed.documentUri,
                locationKind = seed.locationKind,
                relativeDisplayPath = seed.displayName,
                confidence = CandidateConfidence.HIGH,
                completeness = if (subComics.any { it.completeness == CandidateCompleteness.INCOMPLETE }) {
                    CandidateCompleteness.INCOMPLETE
                } else {
                    CandidateCompleteness.COMPLETE
                },
                metadata = MetadataSnapshot.fallback(seed.displayName),
                expectedPageCount = null,
                pages = emptyList(),
                structuralSignature = "",
                provenance = emptyList(),
                warnings = warnings,
                subComics = subComics,
            ),
        )
    }

    private fun DirectoryObservation.Rejected.toRecognition(): CandidateRecognition =
        when (reason) {
            ScanRejection.NO_SUPPORTED_IMAGES,
            ScanRejection.NO_DECODABLE_IMAGES,
            -> CandidateRecognition.Rejected(CandidateRejection.NoSafePages)
            ScanRejection.SOURCE_UNAVAILABLE,
            ScanRejection.PROVIDER_ERROR,
            -> CandidateRecognition.Retryable(CandidateFailure.ProviderUnavailable)
        }

    private fun ArchiveIndexResult.Rejected.toRecognition(): CandidateRecognition =
        when (reason) {
            ArchiveFailure.SOURCE_CHANGED ->
                CandidateRecognition.Retryable(CandidateFailure.SourceChanged)
            ArchiveFailure.SOURCE_UNAVAILABLE ->
                CandidateRecognition.Retryable(CandidateFailure.ProviderUnavailable)
            ArchiveFailure.INSUFFICIENT_SPACE ->
                CandidateRecognition.Retryable(CandidateFailure.InsufficientSpace)
            else ->
                CandidateRecognition.Rejected(CandidateRejection.UnsafeArchive(reason))
        }

    private fun nullableContentEquals(
        left: ByteArray?,
        right: ByteArray?,
    ): Boolean =
        when {
            left == null -> right == null
            right == null -> false
            else -> java.util.Arrays.equals(left, right)
        }

    private companion object {
        /**
         * A47: one number, shared with archive import, so a "folder of folders" on disk and
         * the same tree inside a zip cannot disagree about what counts as a comic.
         */
        const val MIN_UNRECOGNIZED_PAGES = MIN_UNRECOGNIZED_COMIC_IMAGES
    }
}
