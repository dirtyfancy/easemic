package com.easemic.app.importing

import com.easemic.app.importing.metadata.CompatibleEhViewerMarkerParser
import com.easemic.app.importing.metadata.EhViewerMarkerParser
import com.easemic.app.importing.metadata.MarkerParseResult
import com.easemic.app.model.ImportCandidate
import com.easemic.app.model.MetadataSnapshot
import com.easemic.app.model.PageDescriptor
import com.easemic.app.model.ParsedComicInfo
import com.easemic.app.model.ScanRejection
import com.easemic.app.model.ScanResult
import com.easemic.app.storage.DocumentAccess
import com.easemic.app.storage.DocumentEntry
import com.easemic.app.storage.DocumentUnavailableException
import kotlinx.coroutines.CancellationException

sealed interface DirectoryObservation {
    data class Readable(
        val displayName: String,
        val pages: List<PageDescriptor>,
        val comicInfo: ParsedComicInfo?,
        val marker: MarkerParseResult?,
    ) : DirectoryObservation

    data class Rejected(val reason: ScanRejection) : DirectoryObservation
}

class ImageDirectoryScanner(
    private val documents: DocumentAccess,
    private val comicInfoParser: ComicInfoParser,
    private val markerParser: EhViewerMarkerParser = CompatibleEhViewerMarkerParser(),
) {
    suspend fun scan(treeUri: String): ScanResult =
        when (val observation = observe(treeUri)) {
            is DirectoryObservation.Rejected -> ScanResult.Rejected(observation.reason)
            is DirectoryObservation.Readable -> ScanResult.Accepted(
                candidate = ImportCandidate(
                    sourceTreeUri = treeUri,
                    sourceDisplayName = observation.displayName,
                    metadata = observation.comicInfo?.metadata
                        ?.copy(
                            title = importedTitle(
                                observation.displayName,
                                observation.comicInfo.metadata.title,
                            ),
                        )
                        ?: MetadataSnapshot.fallback(importedTitleFrom(observation.displayName)),
                    pages = observation.pages.map { page ->
                        com.easemic.app.model.DocumentRef(
                            documentId = checkNotNull(page.documentId),
                            uri = checkNotNull(page.documentUri),
                            displayName = page.displayName,
                            mimeType = page.mimeType,
                        )
                    },
                ),
                metadataWarning = observation.comicInfo?.warning == true,
            )
        }

    suspend fun observe(
        directoryUri: String,
        providerDocumentId: String? = null,
        knownDisplayName: String? = null,
    ): DirectoryObservation {
        if (providerDocumentId == null && !documents.canReadTree(directoryUri)) {
            return DirectoryObservation.Rejected(ScanRejection.SOURCE_UNAVAILABLE)
        }
        return try {
            val displayName = knownDisplayName
                ?: documents.treeDisplayName(directoryUri)
            // #C71: a sub-folder's listing also carries each child's size and modified
            // time, so the pages below need no per-image stat when the provider reports them.
            val entries = if (providerDocumentId == null) {
                documents.listDirectChildren(directoryUri).map { DocumentEntry(it, null, null) }
            } else {
                documents.listChildEntries(directoryUri, providerDocumentId)
            }
            val children = entries.map { it.ref }
            val listed = entries.associateBy { it.ref.documentId }
            val namedImages = children.filter { isSupportedImageFilename(it.displayName) }
            if (namedImages.isEmpty()) {
                return DirectoryObservation.Rejected(ScanRejection.NO_SUPPORTED_IMAGES)
            }
            val decodable = namedImages
                .mapNotNull { image ->
                    documents.imageBounds(image.uri)?.let { bounds -> image to bounds }
                }
                .sortedWith { left, right ->
                    documentNaturalComparator.compare(left.first, right.first)
                }
            if (decodable.isEmpty()) {
                return DirectoryObservation.Rejected(ScanRejection.NO_DECODABLE_IMAGES)
            }
            val pages = decodable.map { (image, bounds) ->
                val entry = listed[image.documentId]
                val fromListing = entry?.size != null && entry.lastModified != null
                val stat = if (fromListing) null else runCatching { documents.document(image.uri) }.getOrNull()
                PageDescriptor(
                    documentId = image.documentId,
                    documentUri = image.uri,
                    archiveEntryPath = null,
                    displayName = image.displayName,
                    mimeType = stat?.mimeType ?: image.mimeType,
                    size = if (fromListing) entry?.size else stat?.size,
                    lastModifiedMillis = if (fromListing) entry?.lastModified else stat?.lastModified,
                    width = bounds.width,
                    height = bounds.height,
                )
            }
            val (marker, comicInfo) = readMetadata(children, displayName)
            DirectoryObservation.Readable(displayName, pages, comicInfo, marker)
        } catch (_: DocumentUnavailableException) {
            DirectoryObservation.Rejected(ScanRejection.SOURCE_UNAVAILABLE)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DirectoryObservation.Rejected(ScanRejection.PROVIDER_ERROR)
        }
    }

    /**
     * #C71: the stability re-check. When a fresh listing shows exactly the first pass's
     * images with the same id, name, mime, size and modified time, the first pass's pages
     * (bounds included) are reused and only the metadata files are re-read. Anything else
     * — a changed listing, or one without size/modified time (which then cannot equal the
     * page's stat-sourced values) — falls back to a full [observe].
     */
    suspend fun reobserve(
        previous: DirectoryObservation.Readable,
        directoryUri: String,
        providerDocumentId: String,
        knownDisplayName: String?,
    ): DirectoryObservation {
        val cheap = previous.pages.all { it.size != null && it.lastModifiedMillis != null && it.documentId != null }
        if (!cheap) return observe(directoryUri, providerDocumentId, knownDisplayName)
        return try {
            val entries = documents.listChildEntries(directoryUri, providerDocumentId)
            val byId = entries.associateBy { it.ref.documentId }
            val imageIds = entries.filter { isSupportedImageFilename(it.ref.displayName) }.map { it.ref.documentId }.toSet()
            val unchanged = imageIds == previous.pages.map { it.documentId }.toSet() &&
                previous.pages.all { page ->
                    val entry = byId[page.documentId]
                    entry != null && entry.ref.displayName == page.displayName &&
                        entry.ref.mimeType == page.mimeType &&
                        entry.size == page.size && entry.lastModified == page.lastModifiedMillis
                }
            if (!unchanged) return observe(directoryUri, providerDocumentId, knownDisplayName)
            val (marker, comicInfo) = readMetadata(entries.map { it.ref }, previous.displayName)
            DirectoryObservation.Readable(previous.displayName, previous.pages, comicInfo, marker)
        } catch (_: DocumentUnavailableException) {
            DirectoryObservation.Rejected(ScanRejection.SOURCE_UNAVAILABLE)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DirectoryObservation.Rejected(ScanRejection.PROVIDER_ERROR)
        }
    }

    private suspend fun readMetadata(
        children: List<com.easemic.app.model.DocumentRef>,
        displayName: String,
    ): Pair<MarkerParseResult?, ParsedComicInfo?> {
        val marker = children.firstOrNull { it.displayName == ".ehviewer" }
            ?.let { markerParser.parse(documents.readBytes(it.uri, MARKER_LIMIT)) }
        val comicInfo = children.firstOrNull {
            it.displayName.equals("ComicInfo.xml", ignoreCase = true)
        }?.let {
            comicInfoParser.parse(documents.readBytes(it.uri, COMIC_INFO_LIMIT), displayName)
        }
        return marker to comicInfo
    }

    private companion object {
        const val MARKER_LIMIT = 1_048_576
        const val COMIC_INFO_LIMIT = 1_048_576
    }
}
