package com.easemic.app.importing.source

import com.easemic.app.importing.CandidateRecognitionService
import com.easemic.app.model.AdapterEnumeration
import com.easemic.app.model.AdapterKind
import com.easemic.app.model.AuthorizedSource
import com.easemic.app.model.CandidateRecognition
import com.easemic.app.model.CandidateSeed
import com.easemic.app.model.DocumentRef
import com.easemic.app.model.LocationKind
import com.easemic.app.model.SourceFailure
import com.easemic.app.storage.DocumentAccess
import com.easemic.app.storage.DocumentUnavailableException
import kotlinx.coroutines.CancellationException

class GenericDirectorySourceAdapter(
    private val documents: DocumentAccess,
    private val recognizer: CandidateRecognitionService,
) : SourceAdapter {
    override val kind = AdapterKind.GENERIC_DIRECTORY

    override suspend fun enumerate(source: AuthorizedSource): AdapterEnumeration {
        val root = CandidateSeed(
            providerDocumentId = source.providerIdentity.substringAfter(":tree:"),
            documentUri = source.contentUri,
            displayName = source.displayLabel,
            locationKind = LocationKind.DIRECTORY,
            selectedDirectly = true,
        )
        return try {
            when (recognizer.recognize(source, root)) {
                is CandidateRecognition.Recoverable -> AdapterEnumeration.Complete(listOf(root))
                is CandidateRecognition.Retryable ->
                    AdapterEnumeration.Failed(SourceFailure.Unavailable)
                is CandidateRecognition.Rejected -> AdapterEnumeration.Complete(
                    seedsForRootChildren(
                        source,
                        documents.listDirectChildren(source.contentUri),
                    ),
                )
            }
        } catch (_: DocumentUnavailableException) {
            AdapterEnumeration.Failed(SourceFailure.Unavailable)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AdapterEnumeration.Failed(SourceFailure.ProviderError)
        }
    }

    // The granted root, once its own probe has rejected it as a comic. Unlike a nested
    // container the root enumerates BOTH its subdirectories and its archives, but the
    // archive titles now follow A30 §3 rule 2 exactly, as they already did one level down
    // (B2, emulator E2E step 2):
    //   * the root holds exactly one archive, no images and no subdirectories -> that
    //     archive IS this folder, so it takes the folder's own display name
    //     (`A30-LoneArchive`, not `Chapter_49631d.cbz`);
    //   * otherwise every archive is one comic titled by its base name, never with the
    //     `.cbz`/`.zip` extension the file system happens to carry (`volume-1`).
    private suspend fun seedsForRootChildren(
        source: AuthorizedSource,
        children: List<DocumentRef>,
    ): List<CandidateSeed> {
        val archiveKids = children.filter { it.displayName.isArchiveName() }
        val loneArchiveIsTheFolder = archiveKids.size == 1 &&
            children.none { it.isImageMime() } &&
            children.none { it.mimeType == DIRECTORY_MIME }
        return children.flatMap { child ->
            when {
                child.mimeType == DIRECTORY_MIME -> seedsForSeriesDirectory(source, child)
                child.displayName.isArchiveName() -> listOf(
                    CandidateSeed(
                        child.documentId,
                        child.uri,
                        if (loneArchiveIsTheFolder) {
                            source.displayLabel
                        } else {
                            child.displayName.archiveBaseName()
                        },
                        LocationKind.ARCHIVE,
                        false,
                    ),
                )
                else -> emptyList()
            }
        }
    }

    // Bounded lookahead into a direct child ("Series") directory: Mihon's real layout is
    // `local/<Series>/<chapter>.cbz` (or `<Series>/<Chapter>/` folders of images), and a Series
    // directory has no direct images of its own so it would otherwise be rejected and dropped
    // entirely (owner bug A9). A Series directory is also sometimes actually a *source root*
    // (e.g. picking Mihon's `downloads` folder directly, whose children are `<Manga>` folders
    // that themselves hold either archives or a lone, meaninglessly-named chapter folder) --
    // so exactly one extra probe level is allowed here via `allowDeeperProbe`, and that deeper
    // probe (`seedsForGrandchildDirectory`) never recurses again. This guarantees at most two
    // chained `listChildren` calls beyond the initial `listDirectChildren`, i.e. never a 4th
    // level (`root/a/b/c/`) is listed.
    private suspend fun seedsForSeriesDirectory(
        source: AuthorizedSource,
        seriesDoc: DocumentRef,
    ): List<CandidateSeed> {
        val children = documents.listChildren(source.contentUri, seriesDoc.documentId)
        return seedsForContainerChildren(source, seriesDoc, children, allowDeeperProbe = true)
    }

    // Classifies the already-listed children of `containerDoc` (a Series directory, or --
    // one level deeper -- a Manga directory found inside it) into seeds.
    private suspend fun seedsForContainerChildren(
        source: AuthorizedSource,
        containerDoc: DocumentRef,
        children: List<DocumentRef>,
        allowDeeperProbe: Boolean,
    ): List<CandidateSeed> {
        val archiveKids = children.filter { it.displayName.isArchiveName() }
        if (archiveKids.isNotEmpty()) {
            // Archive seeds win over everything else in this directory -- including a stray
            // loose image that would otherwise make CandidateRecognizer treat the whole
            // directory as one incidental comic and ignore the real archives inside it.
            if (archiveKids.size == 1 && children.none { it.isImageMime() }) {
                // Exactly one archive and no stray images: this folder *is* one comic and the
                // archive's own file name is meaningless (Mihon's `Chapter_49631d.cbz`), so the
                // title comes from the folder name alone, not a composed "<folder> — <archive>".
                val archiveDoc = archiveKids.single()
                return listOf(
                    CandidateSeed(
                        archiveDoc.documentId,
                        archiveDoc.uri,
                        containerDoc.displayName,
                        LocationKind.ARCHIVE,
                        false,
                    ),
                )
            }
            return archiveKids.map { archiveDoc ->
                CandidateSeed(
                    archiveDoc.documentId,
                    archiveDoc.uri,
                    "${containerDoc.displayName} — ${archiveDoc.displayName.archiveBaseName()}",
                    LocationKind.ARCHIVE,
                    false,
                )
            }
        }
        val subDirs = children.filter { it.mimeType == DIRECTORY_MIME }
        if (subDirs.isNotEmpty()) {
            if (allowDeeperProbe) {
                return subDirs.flatMap { grandchildDoc ->
                    seedsForGrandchildDirectory(source, containerDoc, grandchildDoc)
                }
            }
            return subDirs.map { chapterDoc ->
                CandidateSeed(
                    chapterDoc.documentId,
                    chapterDoc.uri,
                    "${containerDoc.displayName} — ${chapterDoc.displayName}",
                    LocationKind.DIRECTORY,
                    false,
                )
            }
        }
        // Nothing nested: fall back to the old, unchanged behavior of treating the container
        // directory itself as one flat comic (e.g. a directory of images with nothing else).
        return listOf(
            CandidateSeed(
                containerDoc.documentId,
                containerDoc.uri,
                containerDoc.displayName,
                LocationKind.DIRECTORY,
                false,
            ),
        )
    }

    // The one extra, non-recursing probe allowed below a Series directory. If the grandchild
    // itself contains archives, classify it exactly like a Series directory (with
    // `allowDeeperProbe = false`, so this can never cascade to a 3rd probe / 4th listed level)
    // and use the grandchild's own name as the title root -- the intermediate ancestor (e.g. a
    // Mihon `downloads` root or `Akuma (ALL)` source folder) is not a real series and must not
    // be prefixed into the title. If the grandchild has no archives, this is not the Mihon
    // one-archive-per-folder shape, so fall back to the old terminal chapter-folder title
    // composed from the immediate parent -- and, critically, no further `listChildren` call is
    // made in that fallback, which is what enforces the hard depth cap.
    private suspend fun seedsForGrandchildDirectory(
        source: AuthorizedSource,
        parentDoc: DocumentRef,
        grandchildDoc: DocumentRef,
    ): List<CandidateSeed> {
        val grandchildren = documents.listChildren(source.contentUri, grandchildDoc.documentId)
        val hasArchive = grandchildren.any { it.displayName.isArchiveName() }
        if (hasArchive) {
            return seedsForContainerChildren(source, grandchildDoc, grandchildren, allowDeeperProbe = false)
        }
        return listOf(
            CandidateSeed(
                grandchildDoc.documentId,
                grandchildDoc.uri,
                "${parentDoc.displayName} — ${grandchildDoc.displayName}",
                LocationKind.DIRECTORY,
                false,
            ),
        )
    }

    private fun String.isArchiveName() =
        endsWith(".cbz", true) || endsWith(".zip", true)

    private fun DocumentRef.isImageMime() = mimeType?.startsWith("image/", ignoreCase = true) == true

    private fun String.archiveBaseName() = substringBeforeLast('.', this)

    private companion object {
        const val DIRECTORY_MIME = "vnd.android.document/directory"
    }
}
