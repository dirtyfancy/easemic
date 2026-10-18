package com.easemic.app.storage

import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.easemic.app.data.LibraryRepository
import com.easemic.app.importing.archive.ArchivePageReader
import com.easemic.app.importing.archive.ZipArchiveIndexer
import com.easemic.app.importing.ReaderSupportPolicy
import com.easemic.app.model.ArchiveComicsResult
import com.easemic.app.model.ArchiveFailure
import com.easemic.app.model.GrantKind
import com.easemic.app.model.ArchiveIndexResult
import com.easemic.app.model.LocationState
import com.easemic.app.model.PageAccessTarget
import com.easemic.app.model.PageLocatorKind
import com.easemic.app.model.PageRef
import com.easemic.app.model.SourceGrantState
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The checked page source; document handles avoid Coil's whole-page animated-image copy. */
sealed interface PageContent {
    class File(val file: java.io.File) : PageContent
    class ContentUri(
        val uri: String,
        // A provider can disappear between validation and Coil's own descriptor open.
        internal val onFailure: suspend (Throwable) -> Nothing = { throw it },
    ) : PageContent
    class Stream(val stream: InputStream) : PageContent
}

interface PageContentOpener {
    suspend fun open(page: PageRef): InputStream

    suspend fun openForImage(page: PageRef): PageContent = PageContent.Stream(open(page))
}

enum class PageOpenFailure {
    REAUTHORIZE,
    PROVIDER_UNAVAILABLE,

    /**
     * A30 §2.4 (S4b/B4): the provider answered and the document/archive is simply not
     * there any more -- the user deleted it outside the app since the shelf was drawn.
     * Distinct from [PROVIDER_UNAVAILABLE], which is a storage hiccup worth retrying.
     */
    CONTENT_GONE,
    SOURCE_CHANGED,
    INSUFFICIENT_SPACE,
    UNSUPPORTED_PAGE,
    STALE_PAGE,
}

class PageOpenException(
    val failure: PageOpenFailure,
) : Exception() {
    override fun toString(): String = "PageOpenException($failure)"
}

class LocalPageContentOpener(
    private val repository: LibraryRepository,
    private val documentAccess: DocumentAccess,
    private val grantManager: PersistedReadGrantManager,
    private val archiveAccess: ArchiveAccess? = null,
    private val archiveResultOpener:
        (suspend (String, String, ArchiveRevisionKey) -> ArchiveOpenResult)? = null,
    private val archiveIndex: suspend (File) -> ArchiveIndexResult = ZipArchiveIndexer()::index,
    private val seekableArchiveIndex: suspend (SeekableByteChannel) -> ArchiveIndexResult =
        ZipArchiveIndexer()::index,
    private val archivePageReader: ArchivePageReader = ArchivePageReader(),
    /**
     * A47: the container-aware classifier used ONLY when the page's location identity is a
     * sub-comic composite (archive document id + NUL + relativePath, spec D2). Plain
     * archives verify exactly as before through [archiveIndex]/[seekableArchiveIndex],
     * so existing fakes and behavior are untouched.
     */
    private val containerArchiveIndex: suspend (File) -> ArchiveComicsResult =
        ZipArchiveIndexer()::indexComics,
    private val seekableContainerArchiveIndex: suspend (SeekableByteChannel) -> ArchiveComicsResult =
        ZipArchiveIndexer()::indexComics,
) : PageContentOpener {
    private val verificationMutex = Mutex()
    private val verificationMemoLock = Any()
    private val verificationMemo = object :
        LinkedHashMap<ArchiveVerificationIdentity, Unit>(
            MAX_VERIFIED_ARCHIVES + 1,
            0.75f,
            true,
        ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ArchiveVerificationIdentity, Unit>,
        ): Boolean = size > MAX_VERIFIED_ARCHIVES
    }

    override suspend fun openForImage(page: PageRef): PageContent {
        val target = checkedTarget(page)
        if (target.page.locatorKind == PageLocatorKind.ARCHIVE_ENTRY) {
            return PageContent.Stream(openArchive(target))
        }
        val documentUri = documentUri(target)
        val stream = openDocument(target)
        return when {
            documentUri.startsWith("file:", ignoreCase = true) -> {
                // Keep the same provider-open/close checks and SafeDocumentInputStream mapping.
                // Neither a File nor a ContentUri retains this stream on cancellation;
                // Coil owns any resources it subsequently opens for the returned handle.
                stream.use { currentCoroutineContext().ensureActive() }
                PageContent.File(contentCall(target) { File(java.net.URI(documentUri)) })
            }
            documentUri.startsWith("content:", ignoreCase = true) -> {
                stream.use { currentCoroutineContext().ensureActive() }
                PageContent.ContentUri(documentUri) { error ->
                    contentCall<Nothing>(target) { throw error }
                }
            }
            else -> PageContent.Stream(stream)
        }
    }

    override suspend fun open(page: PageRef): InputStream {
        val target = checkedTarget(page)
        return when (target.page.locatorKind) {
            PageLocatorKind.DOCUMENT -> openDocument(target)
            PageLocatorKind.ARCHIVE_ENTRY -> openArchive(target)
        }
    }

    private suspend fun checkedTarget(page: PageRef): PageAccessTarget {
        val target = repository.resolvePageAccessTarget(page)
            ?: throw PageOpenException(PageOpenFailure.STALE_PAGE)
        if (target.locationState !in setOf(LocationState.PRESENT, LocationState.INCOMPLETE)) {
            throw PageOpenException(PageOpenFailure.SOURCE_CHANGED)
        }
        // A62 (spec D2): a corrupted page's bytes were already proven undecodable at import.
        // Fail fast -- before the grant check, the provider, or any archive stream -- and
        // let the reader render the broken-image glyph; Retry re-attempts the decode, which
        // may honestly succeed after an app/codec update.
        if (target.corrupted) {
            throw PageOpenException(PageOpenFailure.UNSUPPORTED_PAGE)
        }
        if (!ReaderSupportPolicy.supportsPersistedPage(target.width, target.height)) {
            throw PageOpenException(PageOpenFailure.UNSUPPORTED_PAGE)
        }
        val hasGrant = providerCall {
            grantManager.hasReadGrant(target.sourceGrantUri.toUri())
        }
        if (target.sourceGrantState != SourceGrantState.READY || !hasGrant) {
            throw PageOpenException(PageOpenFailure.REAUTHORIZE)
        }
        return target
    }

    private fun documentUri(target: PageAccessTarget): String {
        val storedUri = target.page.documentUri ?: throw PageOpenException(PageOpenFailure.STALE_PAGE)
        return currentTreeDocumentUri(target, target.page.documentId) ?: storedUri
    }

    private suspend fun openDocument(target: PageAccessTarget): InputStream {
        val documentUri = documentUri(target)
        val stream = contentCall(target) { documentAccess.openStream(documentUri) }
        return try {
            currentCoroutineContext().ensureActive()
            SafeDocumentInputStream(stream)
        } catch (cancelled: CancellationException) {
            rethrowAfterCleanup(cancelled, stream::close)
        }
    }

    private suspend fun openArchive(target: PageAccessTarget): InputStream {
        if (archiveAccess == null && archiveResultOpener == null) {
            throw PageOpenException(PageOpenFailure.PROVIDER_UNAVAILABLE)
        }
        val entryPath = target.page.archiveEntryPath
            ?: throw PageOpenException(PageOpenFailure.STALE_PAGE)
        if (target.page.documentUri != null) {
            throw PageOpenException(PageOpenFailure.STALE_PAGE)
        }
        // A47: a container sub-comic's location identity is
        // "<archiveDocumentId>\u0000<relativePath>" (spec D2), but the document on disk IS
        // the archive -- resolve and stat-check against the archive's own document id.
        val archiveDocumentId = archiveDocumentIdOf(target.providerDocumentId)
        val archiveUri = currentTreeDocumentUri(target, archiveDocumentId)
            ?: target.locationDocumentUri
        val stat = contentCall(target) {
            documentAccess.document(archiveUri)
        }
        if (stat.documentId != archiveDocumentId) {
            throw PageOpenException(PageOpenFailure.SOURCE_CHANGED)
        }
        val key = ArchiveRevisionKey(
            sourceId = target.page.sourceId,
            documentId = stat.documentId,
            size = stat.size,
            lastModifiedMillis = stat.lastModified,
        )
        val opened = providerCall {
            archiveResultOpener?.invoke(
                archiveUri,
                stat.displayName,
                key,
            ) ?: checkNotNull(archiveAccess).open(
                    documentUri = archiveUri,
                    displayName = stat.displayName,
                    key = key,
                )
        }
        return when (opened) {
            is ArchiveOpenResult.LeasedCached ->
                openCachedArchive(target, entryPath, key, opened)
            is ArchiveOpenResult.Seekable ->
                openSeekableArchive(target, entryPath, key, opened)
            is ArchiveOpenResult.RecoverableFailure ->
                throw PageOpenException(opened.reason.toPageOpenFailure())
            is ArchiveOpenResult.Cached ->
                throw PageOpenException(PageOpenFailure.PROVIDER_UNAVAILABLE)
        }
    }

    private suspend fun openCachedArchive(
        target: PageAccessTarget,
        entryPath: String,
        key: ArchiveRevisionKey,
        opened: ArchiveOpenResult.LeasedCached,
    ): InputStream {
        val identity = ArchiveVerificationIdentity(
            sourceId = target.page.sourceId.value,
            locationId = target.page.locationId.value,
            revisionId = target.page.revisionId.value,
            observedKey = key,
            structuralSignature = target.structuralSignature,
            cacheFile = opened.file,
        )
        try {
            if (isSubComicIdentity(target.providerDocumentId)) {
                verifySubComic(target, entryPath, key) { containerArchiveIndex(opened.file) }
            } else {
                verifyArchive(identity, target, entryPath) {
                    archiveIndex(opened.file)
                }
            }
            val stream = archivePageReader.open(opened, entryPath)
            return try {
                currentCoroutineContext().ensureActive()
                InvalidatingArchiveInputStream(stream) {
                    forget(identity)
                    opened.evict()
                }
            } catch (cancelled: CancellationException) {
                rethrowAfterCleanup(cancelled, stream::close)
            }
        } catch (cancelled: CancellationException) {
            forget(identity)
            rethrowAfterCleanup(cancelled, opened::evict)
        } catch (failure: PageOpenException) {
            forget(identity)
            rethrowAfterCleanup(failure, opened::evict)
        } catch (_: Exception) {
            forget(identity)
            rethrowAfterCleanup(
                PageOpenException(PageOpenFailure.SOURCE_CHANGED),
                opened::evict,
            )
        } finally {
            cleanupIgnoringFailure(opened::close)
        }
    }

    private suspend fun openSeekableArchive(
        target: PageAccessTarget,
        entryPath: String,
        key: ArchiveRevisionKey,
        opened: ArchiveOpenResult.Seekable,
    ): InputStream {
        val identity = ArchiveVerificationIdentity(
            sourceId = target.page.sourceId.value,
            locationId = target.page.locationId.value,
            revisionId = target.page.revisionId.value,
            observedKey = key,
            structuralSignature = target.structuralSignature,
            cacheFile = null,
        )
        try {
            if (isSubComicIdentity(target.providerDocumentId)) {
                verifySubComic(target, entryPath, key) {
                    val descriptor = opened.archive.duplicate()
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).channel.use { channel ->
                        seekableContainerArchiveIndex(channel)
                    }
                }
            } else {
                verifyArchive(identity, target, entryPath) {
                    val descriptor = opened.archive.duplicate()
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).channel.use { channel ->
                        seekableArchiveIndex(channel)
                    }
                }
            }
            val stream = archivePageReader.open(opened, entryPath)
            return try {
                currentCoroutineContext().ensureActive()
                InvalidatingArchiveInputStream(stream) {
                    forget(identity)
                }
            } catch (cancelled: CancellationException) {
                rethrowAfterCleanup(cancelled, stream::close)
            }
        } catch (cancelled: CancellationException) {
            forget(identity)
            throw cancelled
        } catch (failure: PageOpenException) {
            forget(identity)
            throw failure
        } catch (_: Exception) {
            forget(identity)
            throw PageOpenException(PageOpenFailure.SOURCE_CHANGED)
        } finally {
            cleanupIgnoringFailure(opened.archive::close)
        }
    }

    private suspend fun verifyArchive(
        identity: ArchiveVerificationIdentity,
        target: PageAccessTarget,
        entryPath: String,
        index: suspend () -> ArchiveIndexResult,
    ) {
        if (remembered(identity)) return
        verificationMutex.withLock {
            if (remembered(identity)) return
            val result = index()
            currentCoroutineContext().ensureActive()
            if (
                result !is ArchiveIndexResult.Accepted ||
                result.structuralSignature != target.structuralSignature ||
                result.pages.none { it.archiveEntryPath == entryPath }
            ) {
                throw PageOpenException(PageOpenFailure.SOURCE_CHANGED)
            }
            remember(identity)
        }
    }

    /**
     * A47 (spec D2): a sub-comic's location signature and pages describe ONE comic inside
     * the container, not the whole archive, so verification goes through the container
     * classifier and accepts when the entry's own comic matches the stored signature.
     * Shares the single-comic verification memo/mutex: one archive read verifies once
     * regardless of how many of its sub-comics the shelf opens.
     */
    private suspend fun verifySubComic(
        target: PageAccessTarget,
        entryPath: String,
        key: ArchiveRevisionKey,
        index: suspend () -> ArchiveComicsResult,
    ) {
        val identity = ArchiveVerificationIdentity(
            sourceId = target.page.sourceId.value,
            locationId = target.page.locationId.value,
            revisionId = target.page.revisionId.value,
            observedKey = key,
            structuralSignature = target.structuralSignature,
            cacheFile = null,
        )
        if (remembered(identity)) return
        verificationMutex.withLock {
            if (remembered(identity)) return
            val result = index()
            currentCoroutineContext().ensureActive()
            val accepted = result as? ArchiveComicsResult.Accepted
            val comic = accepted?.comics?.singleOrNull { it.structuralSignature == target.structuralSignature }
            if (
                comic == null ||
                comic.pages.none { it.archiveEntryPath == entryPath }
            ) {
                throw PageOpenException(PageOpenFailure.SOURCE_CHANGED)
            }
            remember(identity)
        }
    }

    /** A47 (spec D2): "<archiveDocumentId>\u0000<relativePath>" marks a container sub-comic. */
    private fun isSubComicIdentity(providerDocumentId: String): Boolean =
        providerDocumentId.contains(SUB_COMIC_IDENTITY_SEPARATOR)

    /** The archive's own document id: everything before the A47 NUL separator, or the id. */
    private fun archiveDocumentIdOf(providerDocumentId: String): String =
        providerDocumentId.substringBefore(SUB_COMIC_IDENTITY_SEPARATOR)

    private fun remembered(identity: ArchiveVerificationIdentity): Boolean =
        synchronized(verificationMemoLock) {
            verificationMemo[identity] != null
        }

    private fun remember(identity: ArchiveVerificationIdentity) {
        synchronized(verificationMemoLock) {
            verificationMemo[identity] = Unit
        }
    }

    private fun forget(identity: ArchiveVerificationIdentity) {
        synchronized(verificationMemoLock) {
            verificationMemo.remove(identity)
        }
    }

    /**
     * A30 §2.4 (S4b/B4 + G1): like [providerCall], but tells "this file is gone" apart from
     * "storage is not responding" -- and only ever on a positive proof from
     * [PageContentAbsence], never on the shape of the exception (the stock
     * ExternalStorageProvider reports a deleted tree child as an IllegalArgumentException
     * from `enforceTree`, and a bare FileNotFoundException also comes out of a dead
     * provider). Only the calls that touch the comic's own bytes use it.
     */
    private suspend fun <T> contentCall(
        target: PageAccessTarget,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            throw PageOpenException(PageOpenFailure.REAUTHORIZE)
        } catch (failure: PageOpenException) {
            throw failure
        } catch (error: Exception) {
            // S4b/H1: a permission failure never enters the absence proof -- it is an ACCESS
            // problem, and the reader turns it into "Access lost" without touching a single
            // location row. Only after that is ruled out may absence be proven.
            if (error.isPermissionDenial() || !hasReadGrant(target)) {
                throw PageOpenException(PageOpenFailure.REAUTHORIZE)
            }
            throw PageOpenException(proveAbsenceOrTransient(target))
        }

    private fun Throwable.isPermissionDenial(): Boolean =
        generateSequence(this, Throwable::cause).any { it is SecurityException }

    private suspend fun hasReadGrant(target: PageAccessTarget): Boolean = try {
        grantManager.hasReadGrant(target.sourceGrantUri.toUri())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // If even the permission list will not answer, assume the grant is fine and let the
        // absence proof's own gates decide; they fail closed (transient).
        true
    }

    /**
     * DOCUMENT pages live inside the comic's own directory ([PageAccessTarget.providerDocumentId]
     * is that location); an archive's location IS the file, so its siblings are listed from the
     * derived parent directory. Anything that is not a TREE grant (legacy per-archive DOCUMENT
     * grants) has no listable parent, so it stays transient.
     */
    /**
     * A30 §2.2 (S4b/I1): resolve the document against the source's CURRENT tree.
     *
     * "Choose folder again" lets the user pick a different (e.g. parent) folder, and
     * `reauthorizeTreeSource` rebinds the source to that new tree. A rescan then rewrites
     * `comic_locations.documentUri`, but the `pages` rows keep the URIs built from the OLD
     * tree (the committer only inserts pages for a revision that has none). Opening those
     * verbatim throws SecurityException against the released grant, which would now flip the
     * folder the user has just re-authorized straight back to "Access lost". The provider's
     * document id is stable across the rebind, so rebuild the URI from it; rows imported
     * before ids were recorded fall back to the stored URI.
     */
    private fun currentTreeDocumentUri(
        target: PageAccessTarget,
        documentId: String?,
    ): String? {
        if (target.sourceGrantKind != GrantKind.TREE || documentId == null) return null
        return try {
            DocumentsContract
                .buildDocumentUriUsingTree(target.sourceGrantUri.toUri(), documentId)
                .toString()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun proveAbsenceOrTransient(target: PageAccessTarget): PageOpenFailure {
        if (target.sourceGrantKind != GrantKind.TREE) return PageOpenFailure.PROVIDER_UNAVAILABLE
        val archiveEntry = target.page.locatorKind == PageLocatorKind.ARCHIVE_ENTRY
        val treeRootDocumentId = try {
            DocumentsContract.getTreeDocumentId(target.sourceGrantUri.toUri())
        } catch (_: Exception) {
            null
        }
        return PageContentAbsence.classify(
            providerAnswers = { documentAccess.canReadTree(target.sourceGrantUri) },
            listChildren = { parentId ->
                documentAccess.listChildListing(target.sourceGrantUri, parentId)
            },
            treeRootDocumentId = treeRootDocumentId,
            // The document the catalog points at: the archive file itself for an archive
            // entry, otherwise the page's own document (H1: matched by its recorded id, which
            // survives a re-grant of the tree; the URI is only secondary evidence). A47: for
            // a container sub-comic the document IS the archive (the NUL suffix is internal).
            targetDocumentId = if (archiveEntry) {
                archiveDocumentIdOf(target.providerDocumentId)
            } else {
                target.page.documentId
            },
            targetDocumentUri = if (archiveEntry) {
                target.locationDocumentUri
            } else {
                target.page.documentUri
            },
        )
    }

    private suspend fun <T> providerCall(block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            throw PageOpenException(PageOpenFailure.REAUTHORIZE)
        } catch (failure: PageOpenException) {
            throw failure
        } catch (_: Exception) {
            throw PageOpenException(PageOpenFailure.PROVIDER_UNAVAILABLE)
        }

    private fun ArchiveFailure.toPageOpenFailure(): PageOpenFailure =
        when (this) {
            ArchiveFailure.INSUFFICIENT_SPACE -> PageOpenFailure.INSUFFICIENT_SPACE
            ArchiveFailure.SOURCE_UNAVAILABLE -> PageOpenFailure.PROVIDER_UNAVAILABLE
            else -> PageOpenFailure.SOURCE_CHANGED
        }

    private data class ArchiveVerificationIdentity(
        val sourceId: Long,
        val locationId: Long,
        val revisionId: Long,
        val observedKey: ArchiveRevisionKey,
        val structuralSignature: String,
        val cacheFile: File?,
    ) {
        override fun toString(): String =
            "ArchiveVerificationIdentity($sourceId,$locationId,$revisionId)"
    }

    internal class SafeDocumentInputStream(
        input: InputStream,
    ) : FilterInputStream(input) {
        override fun read(): Int = safely { super.read() }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            safely { super.read(buffer, offset, length) }

        override fun skip(count: Long): Long = safely { super.skip(count) }

        override fun close() {
            try {
                super.close()
            } catch (_: SecurityException) {
                throw PageOpenException(PageOpenFailure.REAUTHORIZE)
            } catch (_: Exception) {
                throw PageOpenException(PageOpenFailure.PROVIDER_UNAVAILABLE)
            }
        }

        private inline fun <T> safely(block: () -> T): T =
            try {
                block()
            } catch (cancelled: CancellationException) {
                closeQuietly()
                throw cancelled
            } catch (_: SecurityException) {
                closeQuietly()
                throw PageOpenException(PageOpenFailure.REAUTHORIZE)
            } catch (failure: PageOpenException) {
                closeQuietly()
                throw failure
            } catch (_: Exception) {
                closeQuietly()
                throw PageOpenException(PageOpenFailure.PROVIDER_UNAVAILABLE)
            }

        private fun closeQuietly() {
            runCatching { super.close() }
        }
    }

    private companion object {
        const val MAX_VERIFIED_ARCHIVES = 8

        /** A47 (spec D2): must match [CatalogCandidateCommitter]'s separator. */
        const val SUB_COMIC_IDENTITY_SEPARATOR = "\u0000"
    }
}

internal class InvalidatingArchiveInputStream(
    input: InputStream,
    private val onFailure: () -> Unit,
) : FilterInputStream(input) {
    private var failed = false

    override fun read(): Int = safely { super.read() }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        safely { super.read(buffer, offset, length) }

    override fun skip(count: Long): Long = safely { super.skip(count) }

    override fun close() {
        if (failed) {
            closeQuietly()
            return
        }
        safely { super.close() }
    }

    private inline fun <T> safely(block: () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            rethrowAfterCleanup(
                cancelled,
                ::invalidateOnce,
                ::closeQuietly,
            )
        } catch (_: Exception) {
            rethrowAfterCleanup(
                PageOpenException(PageOpenFailure.SOURCE_CHANGED),
                ::invalidateOnce,
                ::closeQuietly,
            )
        }

    private fun invalidateOnce() {
        if (!failed) {
            failed = true
            onFailure()
        }
    }

    private fun closeQuietly() {
        runCatching { super.close() }
    }
}

internal fun cleanupIgnoringFailure(vararg cleanup: () -> Unit) {
    cleanup.forEach { action ->
        try {
            action()
        } catch (_: Throwable) {
            // A cleanup failure must not replace a primary failure or cancellation.
        }
    }
}

internal fun rethrowAfterCleanup(
    primary: Throwable,
    vararg cleanup: () -> Unit,
): Nothing {
    cleanupIgnoringFailure(*cleanup)
    throw primary
}
