package com.easemic.app.importing.opds

import com.easemic.app.importing.ComicInfoParser
import com.easemic.app.importing.metadata.MetadataResolver
import com.easemic.app.model.ArchiveComic
import com.easemic.app.model.ArchiveIndexResult
import com.easemic.app.model.DocumentRef
import com.easemic.app.model.ImportCandidate
import com.easemic.app.model.MetadataSnapshot
import com.easemic.app.model.PageDescriptor
import java.io.File
import java.util.UUID

sealed class OpdsImportResult {
    data class Ready(
        val candidate: ImportCandidate,
        val extractedDir: File,
        val archiveFile: File,
    ) : OpdsImportResult()

    /**
     * Spec 2026-09-09 (A47 audit tail): a container acquisition (top level = N image
     * directories) yields one candidate per sub-comic. [candidates]/[extractedDirs] are
     * index-aligned — candidate i's pages live in extractedDirs[i], and the caller commits
     * each candidate in sequence through the same path a single [Ready] uses (per-candidate
     * fault isolation is the caller's job; this import already isolated extraction faults
     * per sub-comic so a fault never silently removes a candidate's siblings).
     */
    data class ContainerReady(
        val candidates: List<ImportCandidate>,
        val extractedDirs: List<File>,
        val archiveFile: File,
    ) : OpdsImportResult()

    data class Failed(val message: String) : OpdsImportResult()
}

/**
 * Downloads an OPDS acquisition archive into app-private storage, indexes it,
 * extracts readable page images, and builds a legacy [ImportCandidate] that
 * the private library can commit for offline reading.
 *
 * A single-comic acquisition ([ArchiveIndexResult.Accepted]) is byte-identical to
 * the pre-container flow: one `pages/` dir, one candidate. A container archive
 * ([ArchiveIndexResult.ContainerAccepted], produced by wiring `indexComics` — see
 * [opdsIndexResultFrom]) extracts each sub-comic's pages into its own directory
 * (`pages/<sanitized-relativePath>/`) and returns one candidate per sub-comic.
 */
class OpdsAcquisitionImporter(
    private val httpGet: OpdsHttpGet,
    private val downloadsRoot: File,
    private val indexArchive: suspend (File) -> ArchiveIndexResult,
    private val extractPages: suspend (archive: File, destDir: File, pages: List<PageDescriptor>) -> Unit,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun import(
        acquisitionUrl: String,
        title: String,
        providerDisplayName: String,
        username: String? = null,
        password: String? = null,
    ): OpdsImportResult {
        val workDir = File(downloadsRoot, idFactory()).also { it.mkdirs() }
        val archiveFile = File(workDir, "acquisition.cbz")
        return try {
            val response = httpGet.get(
                OpdsHttpRequest(
                    url = acquisitionUrl,
                    authorizationHeader = OpdsCatalogClient.basicAuthorizationHeader(username, password),
                ),
            )
            if (response.code !in 200..299) {
                return OpdsImportResult.Failed("OPDS_HTTP_${response.code}")
            }
            archiveFile.writeBytes(response.body)
            when (val indexed = indexArchive(archiveFile)) {
                is ArchiveIndexResult.Rejected ->
                    OpdsImportResult.Failed("OPDS_ARCHIVE_${indexed.reason}")
                is ArchiveIndexResult.Accepted -> {
                    if (indexed.pages.isEmpty()) {
                        return OpdsImportResult.Failed("OPDS_ARCHIVE_EMPTY")
                    }
                    val pagesDir = File(workDir, "pages").also { it.mkdirs() }
                    extractPages(archiveFile, pagesDir, indexed.pages)
                    val docs = indexed.pages.mapIndexed { index, page ->
                        val safeName = File(page.displayName).name.ifBlank { "page.bin" }
                        val file = File(pagesDir, "%04d_%s".format(index, safeName))
                        require(file.isFile) { "Extracted page missing: ${file.name}" }
                        DocumentRef(
                            documentId = file.name,
                            uri = file.toURI().toString(),
                            displayName = page.displayName,
                            mimeType = page.mimeType,
                        )
                    }
                    OpdsImportResult.Ready(
                        candidate = ImportCandidate(
                            sourceTreeUri = pagesDir.toURI().toString(),
                            sourceDisplayName = providerDisplayName,
                            metadata = MetadataSnapshot(
                                title = title,
                                series = null,
                                alternateSeries = null,
                                creators = emptyList(),
                                genres = emptyList(),
                                tags = emptyList(),
                                language = null,
                            ),
                            pages = docs,
                        ),
                        extractedDir = pagesDir,
                        archiveFile = archiveFile,
                    )
                }
                // Spec 2026-09-09: one candidate per sub-comic. A fault while
                // extracting ONE sub-comic must not abort its siblings (same
                // principle as the local A47 skippedPaths behavior), so each
                // sub-comic is extracted inside its own try/catch; a sub-comic
                // that fails is dropped, and only an import with NO surviving
                // candidate fails.
                is ArchiveIndexResult.ContainerAccepted -> {
                    val pagesDir = File(workDir, "pages").also { it.mkdirs() }
                    val candidates = mutableListOf<ImportCandidate>()
                    val extractedDirs = mutableListOf<File>()
                    val usedDirNames = mutableSetOf<String>()
                    indexed.comics.forEach { comic ->
                        val subDir = File(pagesDir, sanitizedSubComicDirName(comic, usedDirNames))
                        val extracted = try {
                            extractPages(archiveFile, subDir, comic.pages)
                            buildSubComicDocuments(comic, subDir)
                        } catch (_: Exception) {
                            null
                        }
                        if (extracted != null) {
                            candidates += subComicCandidate(
                                comic = comic,
                                providerDisplayName = providerDisplayName,
                                extractedDir = subDir,
                                docs = extracted,
                            )
                            extractedDirs += subDir
                        }
                    }
                    if (candidates.isEmpty()) {
                        return OpdsImportResult.Failed("OPDS_ARCHIVE_EMPTY")
                    }
                    OpdsImportResult.ContainerReady(
                        candidates = candidates,
                        extractedDirs = extractedDirs,
                        archiveFile = archiveFile,
                    )
                }
            }
        } catch (_: Exception) {
            OpdsImportResult.Failed("OPDS_IMPORT_ERROR")
        }
    }

    /**
     * The extraction destDir for one sub-comic. The relativePath comes from the archive's
     * own entry names, so it is untrusted: [File] name normalization keeps only the last
     * path segment (a zip-slip-ish `../x` or `a/../../b` collapses to a plain directory
     * name under `pages/`), and `.`/`..`/blank fall back to a neutral name so the destDir
     * can never alias its parent — matching the A47 path rules that reject the hostile
     * archive at the indexer while keeping this layer independently unable to escape.
     *
     * Two sub-comics from different parents can share a leaf name ("Part 1/Vol 1" and
     * "Part 2/Vol 1"); flattening both to `pages/Vol 1` would mix their extracted pages
     * and give both candidates the same sourceTreeUri — one TREE identity, silently one
     * shelf comic instead of two. [usedDirNames] disambiguates by suffixing `__2`, `__3`,
     * … so every sub-comic owns a distinct directory.
     */
    private fun sanitizedSubComicDirName(comic: ArchiveComic, usedDirNames: MutableSet<String>): String {
        val lastSegment = File(comic.relativePath).name
        val base = lastSegment.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "comic"
        var name = base
        var counter = 2
        while (!usedDirNames.add(name)) {
            name = "${base}__${counter++}"
        }
        return name
    }

    private suspend fun buildSubComicDocuments(
        comic: ArchiveComic,
        subDir: File,
    ): List<DocumentRef> {
        subDir.mkdirs()
        return comic.pages.mapIndexed { index, page ->
            val safeName = File(page.displayName).name.ifBlank { "page.bin" }
            val file = File(subDir, "%04d_%s".format(index, safeName))
            require(file.isFile) { "Extracted page missing: ${file.name}" }
            DocumentRef(
                documentId = file.name,
                uri = file.toURI().toString(),
                displayName = page.displayName,
                mimeType = page.mimeType,
            )
        }
    }

    /**
     * A container sub-comic's metadata: the sub-directory name wins as title (the A46/A47
     * rule — an inner ComicInfo `<Title>` never overrides the file system name), while
     * ComicInfo still fills series/creators/genres/tags/language. Same resolver +
     * display-name convention as the local container path
     * (CandidateRecognizer.recognizeContainerArchive).
     */
    private fun subComicMetadata(comic: ArchiveComic): MetadataSnapshot {
        val displayName = comic.relativePath.substringAfterLast('/')
        val metadata = MetadataResolver().resolve(
            displayName = displayName,
            comicInfo = comic.comicInfoBytes?.let { ComicInfoParser().parse(it, displayName) },
            marker = null,
            readablePageCount = comic.pages.size,
        )
        return metadata.metadata
    }

    private fun subComicCandidate(
        comic: ArchiveComic,
        providerDisplayName: String,
        extractedDir: File,
        docs: List<DocumentRef>,
    ): ImportCandidate =
        ImportCandidate(
            sourceTreeUri = extractedDir.toURI().toString(),
            sourceDisplayName = providerDisplayName,
            metadata = subComicMetadata(comic),
            pages = docs,
        )
}