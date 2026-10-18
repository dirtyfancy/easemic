package com.easemic.app.importing.opds

import com.easemic.app.importing.archive.ZipArchiveIndexer
import com.easemic.app.model.ArchiveComic
import com.easemic.app.model.ArchiveComicsResult
import com.easemic.app.model.ArchiveIndexResult
import java.io.File

/**
 * Spec 2026-09-09 (A47 audit tail): the OPDS acquisition seam, wired in AppGraph from
 * [ZipArchiveIndexer.indexComics] so a container archive reaches the importer as
 * [ArchiveIndexResult.ContainerAccepted] instead of the old single-comic `index()` (which
 * could only flatten it). A single-comic archive (top-level images, or one wrapper
 * directory) is synthesized back into the exact old [ArchiveIndexResult.Accepted] shape —
 * the same mapping [com.easemic.app.importing.archive.SafArchiveCandidateIndexer]
 * uses — so single-comic OPDS acquisitions stay byte-compatible with candidates built by
 * older builds. Rejections map 1:1 as before.
 */
fun opdsIndexResultFrom(zipIndexer: ZipArchiveIndexer): suspend (File) -> ArchiveIndexResult =
    { file ->
        when (val comics = zipIndexer.indexComics(file)) {
            is ArchiveComicsResult.Accepted ->
                if (comics.comics.size == 1) {
                    comics.comics.single().toAccepted()
                } else {
                    ArchiveIndexResult.ContainerAccepted(
                        comics = comics.comics,
                        skippedPaths = comics.skippedPaths,
                    )
                }
            is ArchiveComicsResult.Rejected ->
                ArchiveIndexResult.Rejected(comics.reason)
        }
    }

private fun ArchiveComic.toAccepted(): ArchiveIndexResult.Accepted =
    ArchiveIndexResult.Accepted(
        pages = pages,
        comicInfoBytes = comicInfoBytes,
        structuralSignature = structuralSignature,
    )