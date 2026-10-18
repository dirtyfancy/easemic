package com.easemic.app.importing.archive

import android.os.ParcelFileDescriptor
import com.easemic.app.importing.ArchiveCandidateIndexer
import com.easemic.app.model.ArchiveComic
import com.easemic.app.model.ArchiveFailure
import com.easemic.app.model.ArchiveIndexResult
import com.easemic.app.model.AuthorizedSource
import com.easemic.app.model.CandidateSeed
import com.easemic.app.storage.ArchiveAccess
import com.easemic.app.storage.ArchiveOpenResult
import com.easemic.app.storage.ArchiveRevisionKey
import com.easemic.app.storage.DocumentAccess

/**
 * A47 (spec D5): the SAF indexer classifies through [ZipArchiveIndexer.indexComics], so a
 * container archive (top level = N image directories) reaches the recognizer as
 * [ArchiveIndexResult.ContainerAccepted] instead of being silently flattened into one comic.
 * A single-comic archive (top-level images, or one wrapper directory) is synthesized back
 * into the exact old [ArchiveIndexResult.Accepted] shape, so single-comic candidates --
 * including their dedup identity -- are byte-compatible with candidates persisted by older
 * builds. Rejections map 1:1 as before.
 */
class SafArchiveCandidateIndexer(
    private val documents: DocumentAccess,
    private val archiveAccess: ArchiveAccess,
    private val zipIndexer: ZipArchiveIndexer = ZipArchiveIndexer(),
) : ArchiveCandidateIndexer {
    override suspend fun index(
        source: AuthorizedSource,
        seed: CandidateSeed,
    ): ArchiveIndexResult =
        index(source, seed, freshObservation = false)

    override suspend fun indexFresh(
        source: AuthorizedSource,
        seed: CandidateSeed,
    ): ArchiveIndexResult =
        index(source, seed, freshObservation = true)

    private suspend fun index(
        source: AuthorizedSource,
        seed: CandidateSeed,
        freshObservation: Boolean,
    ): ArchiveIndexResult {
        val stat = documents.document(seed.documentUri)
        if (stat.documentId != seed.providerDocumentId) {
            return ArchiveIndexResult.Rejected(ArchiveFailure.SOURCE_CHANGED)
        }
        val key = ArchiveRevisionKey(
            sourceId = source.id,
            documentId = seed.providerDocumentId,
            size = stat.size,
            lastModifiedMillis = stat.lastModified,
        )
        val opened = if (freshObservation) {
            archiveAccess.openFresh(
                documentUri = seed.documentUri,
                displayName = seed.displayName,
                key = key,
            )
        } else {
            archiveAccess.open(
                documentUri = seed.documentUri,
                displayName = seed.displayName,
                key = key,
            )
        }
        return when (opened) {
            is ArchiveOpenResult.RecoverableFailure ->
                ArchiveIndexResult.Rejected(opened.reason)
            is ArchiveOpenResult.LeasedCached -> {
                var keepCached = false
                try {
                    val result = zipIndexer.indexComics(opened.file).toIndexResult()
                    keepCached =
                        !freshObservation && result !is ArchiveIndexResult.Rejected
                    result
                } finally {
                    if (!keepCached) {
                        opened.evict()
                    }
                    opened.close()
                }
            }
            is ArchiveOpenResult.Cached ->
                error("ArchiveAccess returned an unleased cache entry.")
            is ArchiveOpenResult.Seekable ->
                opened.archive.use { archive ->
                    val descriptor = archive.duplicate()
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).channel.use {
                        zipIndexer.indexComics(it).toIndexResult()
                    }
                }
        }
    }

    private fun com.easemic.app.model.ArchiveComicsResult.toIndexResult(): ArchiveIndexResult =
        when (this) {
            is com.easemic.app.model.ArchiveComicsResult.Accepted -> {
                val comics = comics
                if (comics.size == 1) {
                    comics.single().toAccepted()
                } else {
                    ArchiveIndexResult.ContainerAccepted(comics, skippedPaths)
                }
            }
            is com.easemic.app.model.ArchiveComicsResult.Rejected ->
                ArchiveIndexResult.Rejected(reason)
        }

    private fun ArchiveComic.toAccepted(): ArchiveIndexResult.Accepted =
        ArchiveIndexResult.Accepted(
            pages = pages,
            comicInfoBytes = comicInfoBytes,
            structuralSignature = structuralSignature,
        )
}