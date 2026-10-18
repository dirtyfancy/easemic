package com.easemic.app.importing.archive

import android.os.ParcelFileDescriptor
import com.easemic.app.storage.ArchiveOpenResult
import com.easemic.app.storage.rethrowAfterCleanup
import java.io.FilterInputStream
import java.io.InputStream
import org.apache.commons.compress.archivers.zip.ZipFile

class ArchivePageReader {
    fun open(
        archive: ArchiveOpenResult.LeasedCached,
        entryPath: String,
    ): InputStream =
        openArchive(
            open = { ZipFile.builder().setFile(archive.file).get() },
            entryPath = entryPath,
            lease = archive.takeLease(),
        )

    fun open(
        archive: ArchiveOpenResult.Seekable,
        entryPath: String,
    ): InputStream {
        val descriptor = archive.archive.duplicate()
        val channel = ParcelFileDescriptor.AutoCloseInputStream(descriptor).channel
        return try {
            openArchive(
                open = {
                    ZipFile.builder()
                        .setSeekableByteChannel(channel)
                        .get()
                },
                entryPath = entryPath,
                lease = AutoCloseable {},
            )
        } catch (error: Throwable) {
            rethrowAfterCleanup(error, channel::close)
        }
    }

    private fun openArchive(
        open: () -> ZipFile,
        entryPath: String,
        lease: AutoCloseable,
    ): InputStream {
        var zipFile: ZipFile? = null
        try {
            zipFile = open()
            val entry = zipFile.getEntry(entryPath)
                ?.takeUnless { it.isDirectory }
                ?: throw IllegalArgumentException("Archive page entry is unavailable.")
            return RetainedArchiveEntryStream(
                input = zipFile.getInputStream(entry),
                zipFile = zipFile,
                lease = lease,
            )
        } catch (error: Throwable) {
            rethrowAfterCleanup(
                error,
                { zipFile?.close() },
                lease::close,
            )
        }
    }
}

private class RetainedArchiveEntryStream(
    input: InputStream,
    private val zipFile: ZipFile,
    private val lease: AutoCloseable,
) : FilterInputStream(input) {
    private var closed = false

    override fun close() {
        if (!closed) {
            closed = true
            try {
                super.close()
            } finally {
                try {
                    zipFile.close()
                } finally {
                    lease.close()
                }
            }
        }
    }
}
