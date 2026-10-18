package com.easemic.app.storage

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.InputStream

class ArchiveAccess(
    private val archiveCache: ArchiveCache,
    private val descriptorOpener: suspend (String) -> ParcelFileDescriptor?,
    private val streamOpener: suspend (String) -> InputStream,
    private val seekabilityProbe: (ParcelFileDescriptor) -> Boolean = { descriptor ->
        descriptor.isSeekable()
    },
) {
    suspend fun open(
        documentUri: String,
        displayName: String,
        key: ArchiveRevisionKey,
    ): ArchiveOpenResult =
        open(documentUri, displayName, key, freshObservation = false)

    suspend fun openFresh(
        documentUri: String,
        displayName: String,
        key: ArchiveRevisionKey,
    ): ArchiveOpenResult =
        open(documentUri, displayName, key, freshObservation = true)

    private suspend fun open(
        documentUri: String,
        displayName: String,
        key: ArchiveRevisionKey,
        freshObservation: Boolean,
    ): ArchiveOpenResult {
        val descriptor = descriptorOpener(documentUri)
        if (descriptor != null) {
            var ownershipTransferred = false
            try {
                if (seekabilityProbe(descriptor)) {
                    val archive = ParcelSeekableDocument(descriptor)
                    ownershipTransferred = true
                    return ArchiveOpenResult.Seekable(archive)
                }
            } finally {
                if (!ownershipTransferred) {
                    descriptor.close()
                }
            }
        }
        return if (freshObservation) {
            archiveCache.refreshRetained(key, displayName) { streamOpener(documentUri) }
        } else {
            archiveCache.cacheRetained(key, displayName) { streamOpener(documentUri) }
        }
    }
}

private fun ParcelFileDescriptor.isSeekable(): Boolean =
    try {
        Os.lseek(fileDescriptor, 0L, OsConstants.SEEK_CUR)
        true
    } catch (_: android.system.ErrnoException) {
        false
    }

private class ParcelSeekableDocument(
    private val descriptor: ParcelFileDescriptor,
) : SeekableDocument {
    private var closed = false

    override fun duplicate(): ParcelFileDescriptor {
        check(!closed) { "Seekable archive is closed." }
        return ParcelFileDescriptor.dup(descriptor.fileDescriptor)
    }

    override fun close() {
        if (!closed) {
            closed = true
            descriptor.close()
        }
    }
}
