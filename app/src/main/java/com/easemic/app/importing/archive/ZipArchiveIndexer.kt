package com.easemic.app.importing.archive

import com.easemic.app.importing.NaturalFilenameComparator
import com.easemic.app.importing.isSupportedImageFilename
import com.easemic.app.model.ArchiveComic
import com.easemic.app.model.ArchiveComicsResult
import com.easemic.app.model.ArchiveFailure
import com.easemic.app.model.ArchiveIndexResult
import com.easemic.app.model.PageDescriptor
import com.easemic.app.storage.ImageProbe
import com.easemic.app.storage.ImageProbeResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import java.util.Enumeration
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class ZipArchiveIndexer(
    private val imageProbe: ImageProbe = ImageProbe(),
    private val policy: ZipSafetyPolicy = ZipSafetyPolicy(),
    private val entryStreamDecorator: (String, Long, InputStream) -> InputStream =
        { _, _, input -> input },
    private val entryEnumerator: (ZipFile) -> Enumeration<ZipArchiveEntry> =
        { archive -> archive.entries },
) {
    suspend fun index(file: File): ArchiveIndexResult =
        index { ZipFile.builder().setFile(file).get() }

    suspend fun index(channel: SeekableByteChannel): ArchiveIndexResult =
        index { ZipFile.builder().setSeekableByteChannel(channel).get() }

    /**
     * A47: index a container archive as N comics, one per directory of images.
     *
     * A zip that is just images (or one wrapper of images) yields exactly one comic whose
     * pages are identical to what [index] returns, so this is a superset of the old model
     * rather than a replacement. Path safety still rejects the whole archive -- a zip-slip
     * entry means the file is hostile, and no part of it is trustworthy.
     */
    suspend fun indexComics(file: File): ArchiveComicsResult =
        indexComics { ZipFile.builder().setFile(file).get() }

    suspend fun indexComics(channel: SeekableByteChannel): ArchiveComicsResult =
        indexComics { ZipFile.builder().setSeekableByteChannel(channel).get() }

    private suspend fun indexComics(openArchive: () -> ZipFile): ArchiveComicsResult {
        return try {
            openArchive().use { archive ->
                val entries = mutableListOf<ZipArchiveEntry>()
                val enumeration = entryEnumerator(archive)
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement()
                    if (entry.isDirectory) continue
                    entries += entry
                }
                val entryFacts = entries.map { entry ->
                    ZipEntryFacts(
                        path = entry.name,
                        compressedBytes = entry.compressedSize,
                        uncompressedBytes = entry.size,
                        isImage = isSupportedImageFilename(entry.name),
                        isEncryptedOrUnsupported =
                        entry.generalPurposeBit.usesEncryption() ||
                            entry.generalPurposeBit.usesStrongEncryption() ||
                            !archive.canReadEntryData(entry),
                    )
                }
                policy.validate(entryFacts)?.let { failure ->
                    return ArchiveComicsResult.Rejected(failure)
                }
                streamEntries(archive, entries)?.let { failure ->
                    return ArchiveComicsResult.Rejected(failure)
                }
                val layout = ArchiveComicLayout.from(entries.map { it.name })
                if (layout.comics.isEmpty()) {
                    return ArchiveComicsResult.Rejected(ArchiveFailure.IMAGE_UNSAFE)
                }
                val entriesByPath = entries.associateBy { it.name }
                val skipped = mutableListOf<String>()
                val comics = mutableListOf<ArchiveComic>()
                layout.comics.forEach { candidate ->
                    currentCoroutineContext().ensureActive()
                    val pages = candidate.imagePaths.mapNotNull { path ->
                        val entry = entriesByPath[path] ?: return@mapNotNull null
                        val probe = imageProbe.probe { archive.getInputStream(entry) }
                        when (probe) {
                            is ImageProbeResult.Accepted -> PageDescriptor(
                                documentId = null,
                                documentUri = null,
                                archiveEntryPath = path,
                                displayName = path.substringAfterLast('/'),
                                mimeType = probe.bounds.mimeType,
                                size = entry.size.takeIf { it >= 0 },
                                lastModifiedMillis = null,
                                width = probe.bounds.width,
                                height = probe.bounds.height,
                            )
                            // A62 (spec D2): an undecodable image KEEPS its slot instead of
                            // being silently dropped. Zeroed geometry + octet-stream mark
                            // it corrupted; the signature tuple stays stable across
                            // rescans because every field comes from the entry itself.
                            is ImageProbeResult.Unsupported -> corruptedPageDescriptor(
                                archiveEntryPath = path,
                                displayName = path.substringAfterLast('/'),
                                size = entry.size.takeIf { it >= 0 },
                            )
                        }
                    }
                    // A62 (spec D2): a directory whose images are ALL corrupted holds no
                    // readable page, so it is skipped exactly like a directory whose
                    // images were all dropped before A62 -- and, symmetrically, like the
                    // flat index()'s zero-decodable-pages rejection. A MIXED directory
                    // keeps every slot, corrupted ones included.
                    if (pages.none { !it.corrupted }) {
                        skipped += candidate.directoryPath
                        return@forEach
                    }
                    comics += ArchiveComic(
                        relativePath = candidate.directoryPath,
                        pages = pages,
                        comicInfoBytes = candidate.comicInfoPath
                            ?.let { entriesByPath[it] }
                            ?.let { readComicInfo(archive, it) },
                        structuralSignature = structuralSignature(pages),
                    )
                }
                skipped += layout.skippedDirectoryPaths
                if (comics.isEmpty()) {
                    return ArchiveComicsResult.Rejected(ArchiveFailure.IMAGE_UNSAFE)
                }
                ArchiveComicsResult.Accepted(comics = comics, skippedPaths = skipped.sorted())
            }
        } catch (_: java.util.zip.ZipException) {
            ArchiveComicsResult.Rejected(ArchiveFailure.CORRUPT)
        } catch (_: java.io.IOException) {
            ArchiveComicsResult.Rejected(ArchiveFailure.TRUNCATED)
        }
    }

    private fun readComicInfo(archive: ZipFile, entry: ZipArchiveEntry): ByteArray? {
        if (entry.size > COMIC_INFO_LIMIT) return null
        return archive.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (output.size() <= COMIC_INFO_LIMIT) {
                val read = input.read(buffer, 0, minOf(buffer.size, COMIC_INFO_LIMIT + 1 - output.size()))
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.toByteArray().takeIf { it.size <= COMIC_INFO_LIMIT }
        }
    }

    private suspend fun index(openArchive: () -> ZipFile): ArchiveIndexResult {
        return try {
            openArchive().use { archive ->
            val entries = mutableListOf<ZipArchiveEntry>()
            val enumeration = entryEnumerator(archive)
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entry.isDirectory) continue
                entries += entry
            }
            val entryFacts = entries.map { entry ->
                ZipEntryFacts(
                    path = entry.name,
                    compressedBytes = entry.compressedSize,
                    uncompressedBytes = entry.size,
                    isImage = isSupportedImageFilename(entry.name),
                    isEncryptedOrUnsupported =
                    entry.generalPurposeBit.usesEncryption() ||
                        entry.generalPurposeBit.usesStrongEncryption() ||
                        !archive.canReadEntryData(entry),
                )
            }
            policy.validate(entryFacts)?.let { failure ->
                return ArchiveIndexResult.Rejected(failure)
            }
            streamEntries(archive, entries)?.let { failure ->
                return ArchiveIndexResult.Rejected(failure)
            }
            val imageEntries = entries.filter { isSupportedImageFilename(it.name) }
            if (imageEntries.isEmpty()) {
                return ArchiveIndexResult.Rejected(ArchiveFailure.IMAGE_UNSAFE)
            }
            val pagesByPath = mutableMapOf<String, PageDescriptor>()
            imageEntries.forEach { entry ->
                val probe = imageProbe.probe { archive.getInputStream(entry) }
                // A62 (spec D2): a corrupted slot is kept here too -- the reader re-verifies
                // plain archives through this path, so the page list and signature it
                // recomputes must include the same corrupted slot the commit persisted
                // (otherwise every page of a comic with one bad image would read as
                // SOURCE_CHANGED). A flat archive with ONLY bad images still rejects, as
                // before: no page survives to read.
                pagesByPath[entry.name] = when (probe) {
                    is ImageProbeResult.Accepted -> PageDescriptor(
                        documentId = null,
                        documentUri = null,
                        archiveEntryPath = entry.name,
                        displayName = entry.name.substringAfterLast('/'),
                        mimeType = probe.bounds.mimeType,
                        size = entry.size.takeIf { it >= 0 },
                        lastModifiedMillis = null,
                        width = probe.bounds.width,
                        height = probe.bounds.height,
                    )
                    is ImageProbeResult.Unsupported -> corruptedPageDescriptor(
                        archiveEntryPath = entry.name,
                        displayName = entry.name.substringAfterLast('/'),
                        size = entry.size.takeIf { it >= 0 },
                    )
                }
            }
            if (pagesByPath.values.none { !it.corrupted }) {
                return ArchiveIndexResult.Rejected(ArchiveFailure.IMAGE_UNSAFE)
            }
            val comicInfoBytes = entries.singleOrNull { entry ->
                !entry.name.contains('/') && entry.name.equals("ComicInfo.xml", ignoreCase = true)
            }?.let { entry ->
                if (entry.size > COMIC_INFO_LIMIT) {
                    null
                } else {
                    archive.getInputStream(entry).use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        while (output.size() <= COMIC_INFO_LIMIT) {
                            val read = input.read(buffer, 0, minOf(buffer.size, COMIC_INFO_LIMIT + 1 - output.size()))
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray().takeIf { it.size <= COMIC_INFO_LIMIT }
                    }
                }
            }
            val pages = entries.asSequence()
                .filterNot { it.isDirectory }
                .filter { isSupportedImageFilename(it.name) }
                .sortedWith { left, right ->
                    NaturalFilenameComparator.compare(left.name, right.name)
                        .takeIf { it != 0 }
                        ?: left.name.compareTo(right.name)
                }
                .map { entry ->
                    val descriptor = checkNotNull(pagesByPath[entry.name])
                    descriptor
                }
                .toList()
                ArchiveIndexResult.Accepted(
                pages = pages,
                comicInfoBytes = comicInfoBytes,
                structuralSignature = structuralSignature(pages),
            )
            }
        } catch (_: java.util.zip.ZipException) {
            ArchiveIndexResult.Rejected(ArchiveFailure.CORRUPT)
        } catch (_: java.io.IOException) {
            ArchiveIndexResult.Rejected(ArchiveFailure.TRUNCATED)
        }
    }

    private suspend fun streamEntries(
        archive: ZipFile,
        entries: List<org.apache.commons.compress.archivers.zip.ZipArchiveEntry>,
    ): ArchiveFailure? {
        val coroutineContext = currentCoroutineContext()
        val buffer = ByteArray(8 * 1024)
        var totalObservedBytes = 0L
        var totalCompressedBytes = 0L
        entries.forEach { entry ->
            coroutineContext.ensureActive()
            var observedBytes = 0L
            if (
                entry.compressedSize < 0 ||
                totalCompressedBytes > Long.MAX_VALUE - entry.compressedSize
            ) {
                return ArchiveFailure.EXCESSIVE_RATIO
            }
            totalCompressedBytes += entry.compressedSize
            archive.getInputStream(entry).use { rawInput ->
                entryStreamDecorator(entry.name, entry.size, rawInput).use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (observedBytes > Long.MAX_VALUE - read) {
                            throw IOException("archive entry exceeds declared size")
                        }
                        observedBytes += read
                        if (observedBytes > policy.maxEntryUncompressedBytes) {
                            return ArchiveFailure.ENTRY_TOO_LARGE
                        }
                        if (totalObservedBytes > Long.MAX_VALUE - read) {
                            throw IOException("archive exceeds addressable size")
                        }
                        totalObservedBytes += read
                        policy.validateExpansion(entry.compressedSize, observedBytes)?.let { failure ->
                            return failure
                        }
                        policy.validateExpansion(totalCompressedBytes, totalObservedBytes)
                            ?.let { failure -> return failure }
                        if (entry.size >= 0 && observedBytes > entry.size) {
                            throw IOException("archive entry exceeds declared size")
                        }
                        coroutineContext.ensureActive()
                    }
                }
            }
        }
        return null
    }

    /**
     * A62 (spec D2): the descriptor for an image the probe could not decode. Kept in the
     * page list at its original position; zeroed geometry + octet-stream mime so it can
     * never masquerade as a decodable page downstream (the reader fast-fails on it).
     */
    private fun corruptedPageDescriptor(
        archiveEntryPath: String,
        displayName: String,
        size: Long?,
    ): PageDescriptor =
        PageDescriptor(
            documentId = null,
            documentUri = null,
            archiveEntryPath = archiveEntryPath,
            displayName = displayName,
            mimeType = "application/octet-stream",
            size = size,
            lastModifiedMillis = null,
            width = 0,
            height = 0,
            corrupted = true,
        )

    private fun structuralSignature(pages: List<PageDescriptor>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        pages.forEach { page ->
            // A62 (spec D2/test 3): the corrupted marker deliberately does NOT enter the
            // tuple. A corrupted slot's (path, size, 0, 0, "application/octet-stream")
            // tuple is already unreachable by an accepted probe (width/height > 0 always),
            // so no disambiguation is needed — and keeping the algorithm byte-identical
            // to pre-A62 means every CLEAN archive already in a library rescans to the
            // same signature and stays UNCHANGED. A corrupted slot still contributes a
            // deterministic tuple of its own stable fields, so rescans stay equal.
            listOf(
                page.archiveEntryPath.orEmpty(),
                page.size?.toString().orEmpty(),
                page.width.toString(),
                page.height.toString(),
                page.mimeType.orEmpty(),
            ).forEach { value ->
                digest.update(value.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val COMIC_INFO_LIMIT = 1024 * 1024
    }
}
