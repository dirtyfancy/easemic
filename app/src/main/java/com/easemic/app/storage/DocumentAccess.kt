package com.easemic.app.storage

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.easemic.app.model.DocumentRef
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class DocumentUnavailableException(cause: Throwable? = null) : Exception(cause)

data class DocumentStat(
    val documentId: String,
    val displayName: String,
    val size: Long?,
    val lastModified: Long?,
    val flags: Int,
    val mimeType: String?,
    val isDirectory: Boolean,
)

/**
 * A30 §2.4 (S4b/I3): a directory listing plus whether the provider says it is finished.
 * A DocumentsProvider may return a partial cursor and flag it with
 * `DocumentsContract.EXTRA_LOADING` (still fetching) or `EXTRA_ERROR` (it gave up); cloud
 * providers do this routinely. A half-filled listing is not evidence that a file is missing,
 * so [complete] must be true before anything may be concluded from an absent child.
 */
data class ChildListing(
    val children: List<DocumentRef>,
    val complete: Boolean = true,
)

/**
 * #C71: a child plus the size and modified time its listing cursor already carries, so a
 * directory scan need not stat every image one by one. Null when the provider omits them.
 */
data class DocumentEntry(
    val ref: DocumentRef,
    val size: Long?,
    val lastModified: Long?,
)

interface DocumentAccess {
    suspend fun treeDisplayName(treeUri: String): String

    /** #C71: [listChildren] with each child's size / modified time from the same query. */
    suspend fun listChildEntries(treeUri: String, parentDocumentId: String): List<DocumentEntry> =
        listChildren(treeUri, parentDocumentId).map { DocumentEntry(it, size = null, lastModified = null) }

    suspend fun listChildren(treeUri: String, parentDocumentId: String): List<DocumentRef> =
        throw UnsupportedOperationException("Hierarchical listing is not available.")

    /** [listChildren] plus the provider's own verdict on whether the listing is finished. */
    suspend fun listChildListing(treeUri: String, parentDocumentId: String): ChildListing =
        ChildListing(listChildren(treeUri, parentDocumentId))

    suspend fun listDirectChildren(treeUri: String): List<DocumentRef>

    suspend fun document(documentUri: String): DocumentStat =
        throw UnsupportedOperationException("Document stat is not available.")

    suspend fun isDecodableImage(documentUri: String): Boolean

    suspend fun imageBounds(documentUri: String): ImageBounds? =
        throw UnsupportedOperationException("Image bounds are not available.")

    suspend fun readPrefix(documentUri: String, byteLimit: Int): ByteArray =
        readBytes(documentUri, byteLimit)

    suspend fun readBytes(documentUri: String, byteLimit: Int): ByteArray

    suspend fun openStream(documentUri: String): InputStream =
        throw UnsupportedOperationException("Document stream is not available.")

    suspend fun openFileDescriptor(documentUri: String): ParcelFileDescriptor? = null

    suspend fun canReadTree(treeUri: String): Boolean

    suspend fun canReadDocument(documentUri: String): Boolean =
        throw UnsupportedOperationException("Document grant validation is not available.")
}

class ContentResolverDocumentAccess(
    private val contentResolver: ContentResolver,
    private val hasPersistedReadGrant: (Uri) -> Boolean = { uri ->
        contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    },
    private val streamOpener: (suspend (String) -> InputStream)? = null,
    private val descriptorOpener: ((Uri, CancellationSignal) -> ParcelFileDescriptor?)? = null,
) : DocumentAccess {
    override suspend fun treeDisplayName(treeUri: String): String =
        rootCursor(treeUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)).use { cursor ->
            if (!cursor.moveToFirst()) throw DocumentUnavailableException()
            cursor.getString(0) ?: throw DocumentUnavailableException()
        }

    override suspend fun listChildren(
        treeUri: String,
        parentDocumentId: String,
    ): List<DocumentRef> = listChildListing(treeUri, parentDocumentId).children

    override suspend fun listChildEntries(
        treeUri: String,
        parentDocumentId: String,
    ): List<DocumentEntry> {
        val tree = treeUri.toUri()
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return query(childUri, projection).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0) ?: continue
                    val displayName = cursor.getString(1) ?: continue
                    add(
                        DocumentEntry(
                            ref = DocumentRef(
                                documentId = documentId,
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString(),
                                displayName = displayName,
                                mimeType = cursor.getString(2),
                            ),
                            size = cursor.getNullableLong(3),
                            lastModified = cursor.getNullableLong(4),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun listChildListing(
        treeUri: String,
        parentDocumentId: String,
    ): ChildListing {
        val tree = treeUri.toUri()
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        return query(childUri, projection).use { cursor ->
            val children = buildList {
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0) ?: continue
                    val displayName = cursor.getString(1) ?: continue
                    add(
                        DocumentRef(
                            documentId = documentId,
                            uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString(),
                            displayName = displayName,
                            mimeType = cursor.getString(2),
                        ),
                    )
                }
            }
            val extras = cursor.extras
            val stillLoading = extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)
            val errored = extras.getString(DocumentsContract.EXTRA_ERROR) != null
            ChildListing(children = children, complete = !stillLoading && !errored)
        }
    }

    override suspend fun listDirectChildren(treeUri: String): List<DocumentRef> =
        listChildren(
            treeUri = treeUri,
            parentDocumentId = DocumentsContract.getTreeDocumentId(treeUri.toUri()),
        )

    override suspend fun document(documentUri: String): DocumentStat {
        val uri = documentUri.toUri()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return query(uri, projection).use { cursor ->
            if (!cursor.moveToFirst()) throw DocumentUnavailableException()
            val mimeType = cursor.getString(5)
            DocumentStat(
                documentId = cursor.getString(0) ?: throw DocumentUnavailableException(),
                displayName = cursor.getString(1) ?: throw DocumentUnavailableException(),
                size = cursor.getNullableLong(2),
                lastModified = cursor.getNullableLong(3),
                flags = cursor.getInt(4),
                mimeType = mimeType,
                isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
            )
        }
    }

    override suspend fun isDecodableImage(documentUri: String): Boolean =
        imageBounds(documentUri) != null

    override suspend fun imageBounds(documentUri: String): ImageBounds? {
        val stream = openStream(documentUri)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                runCatching { stream.close() }
            }
            try {
                val bounds = stream.use {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(it, null, options)
                    if (options.outWidth <= 0 || options.outHeight <= 0) {
                        null
                    } else {
                        ImageBounds(
                            width = options.outWidth,
                            height = options.outHeight,
                            mimeType = options.outMimeType ?: "application/octet-stream",
                        )
                    }
                }
                if (continuation.isActive) {
                    continuation.resume(bounds)
                }
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(unavailableFailure(error)))
                }
            }
        }
    }

    override suspend fun readPrefix(documentUri: String, byteLimit: Int): ByteArray {
        require(byteLimit > 0) { "Prefix limit must be positive." }
        return openStream(documentUri).use { stream ->
            ByteArrayOutputStream(minOf(byteLimit, 8 * 1024)).use { output ->
                val buffer = ByteArray(8 * 1024)
                var remaining = byteLimit
                while (remaining > 0) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val bytesRead = stream.readCancellable(
                        buffer = buffer,
                        offset = 0,
                        length = minOf(buffer.size, remaining),
                    )
                    if (bytesRead < 0) break
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    output.write(buffer, 0, bytesRead)
                    remaining -= bytesRead
                }
                output.toByteArray()
            }
        }
    }

    override suspend fun readBytes(documentUri: String, byteLimit: Int): ByteArray =
        readPrefix(documentUri, byteLimit)

    override suspend fun openStream(documentUri: String): InputStream {
        val customOpener = streamOpener
        if (customOpener != null) return customOpener(documentUri)

        val context = kotlinx.coroutines.currentCoroutineContext()
        val descriptor = openFileDescriptor(documentUri)
            ?: throw DocumentUnavailableException()
        return try {
            DescriptorInputStream(descriptor, context)
        } catch (error: Throwable) {
            descriptor.close()
            throw error
        }
    }

    override suspend fun openFileDescriptor(documentUri: String): ParcelFileDescriptor? {
        val context = kotlinx.coroutines.currentCoroutineContext()
        context.ensureActive()
        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                val descriptor = unavailableAsException {
                    descriptorOpener?.invoke(documentUri.toUri(), signal)
                        ?: contentResolver.openFileDescriptor(
                            documentUri.toUri(),
                            "r",
                            signal,
                        )
                        ?: throw DocumentUnavailableException()
                }
                if (!continuation.isActive) {
                    descriptor.close()
                    return@suspendCancellableCoroutine
                }
                continuation.resume(descriptor) { _, cancelledDescriptor, _ ->
                    cancelledDescriptor.close()
                }
            } catch (error: android.os.OperationCanceledException) {
                continuation.cancel(CancellationException("Document open cancelled.", error))
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
        }
    }

    private suspend fun InputStream.readCancellable(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                runCatching { close() }
            }
            try {
                val bytesRead = read(buffer, offset, length)
                if (continuation.isActive) {
                    continuation.resume(bytesRead)
                }
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }

    override suspend fun canReadTree(treeUri: String): Boolean {
        val tree = treeUri.toUri()
        if (!hasPersistedReadGrant(tree)) return false
        if (tree.scheme.equals("file", ignoreCase = true)) {
            return isReadableLocalFileTree(treeUri)
        }
        return try {
            rootCursor(treeUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)).use { it.moveToFirst() }
        } catch (_: DocumentUnavailableException) {
            false
        }
    }

    override suspend fun canReadDocument(documentUri: String): Boolean {
        val document = documentUri.toUri()
        if (!hasPersistedReadGrant(document)) return false
        return try {
            query(document, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)).use { it.moveToFirst() }
        } catch (_: DocumentUnavailableException) {
            false
        }
    }

    private suspend fun rootCursor(treeUri: String, projection: Array<String>) =
        query(
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri.toUri(),
                DocumentsContract.getTreeDocumentId(treeUri.toUri()),
            ),
            projection,
        )

    private suspend fun query(
        uri: Uri,
        projection: Array<String>,
    ) = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            val cursor = unavailableAsException {
                contentResolver.query(uri, projection, null, null, null, signal)
                    ?: throw DocumentUnavailableException()
            }
            if (!continuation.isActive) {
                cursor.close()
                return@suspendCancellableCoroutine
            }
            continuation.resume(cursor) { _, cancelledCursor, _ ->
                cancelledCursor.close()
            }
        } catch (error: android.os.OperationCanceledException) {
            continuation.cancel(CancellationException("Document query cancelled.", error))
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }
    }

    private fun android.database.Cursor.getNullableLong(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun <T> unavailableAsException(block: () -> T): T =
        try {
            block()
        } catch (error: DocumentUnavailableException) {
            throw error
        } catch (error: SecurityException) {
            throw DocumentUnavailableException(error)
        } catch (error: FileNotFoundException) {
            throw DocumentUnavailableException(error)
        } catch (error: IllegalArgumentException) {
            // S4b/G1: how the stock ExternalStorageProvider reports a deleted tree child --
            // `DocumentsProvider.enforceTree` → `FileSystemProvider.isChildDocument` throws
            // IllegalArgumentException("Failed to determine if … is child of …") wrapping a
            // FileNotFoundException. It is a provider answer about one document, not a bug in
            // the caller, so it belongs on the unavailable path with everything else; whether
            // the document is really gone is settled by PageContentAbsence, not by this type.
            throw DocumentUnavailableException(error)
        }

    private fun unavailableFailure(error: Throwable): Throwable =
        when (error) {
            is DocumentUnavailableException,
            is CancellationException,
            -> error
            is SecurityException,
            is FileNotFoundException,
            -> DocumentUnavailableException(error)
            else -> error
        }

    private class DescriptorInputStream(
        descriptor: ParcelFileDescriptor,
        private val coroutineContext: kotlin.coroutines.CoroutineContext,
    ) : InputStream() {
        private val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)

        override fun read(): Int {
            coroutineContext.ensureActive()
            return input.read().also { coroutineContext.ensureActive() }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            coroutineContext.ensureActive()
            return input.read(buffer, offset, length).also { coroutineContext.ensureActive() }
        }

        override fun close() {
            input.close()
        }
    }
}
