package com.easemic.app.importing.opds

import java.io.File
import java.security.MessageDigest
import com.easemic.app.storage.CacheBudget

/** Writes one OPDS-PSE page into a cache directory and returns a `file:` URI. */
object OpdsOnlinePageCache {
    private val HEX_KEY = Regex("^[0-9a-fA-F]{64}$")

    fun pageKey(pageUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(pageUrl.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun write(
        cacheRoot: File,
        ordinal: Int,
        bytes: ByteArray,
        pageKey: String,
        budget: CacheBudget? = null,
    ): String {
        require(bytes.isNotEmpty()) { "OPDS page empty" }
        require(HEX_KEY.matches(pageKey)) { "OPDS page key" }
        val dest = File(cacheRoot, "${pageKey.lowercase()}-$ordinal")
        return budget?.withRootLock(cacheRoot) {
            cacheRoot.mkdirs()
            if (dest.isFile && dest.length() > 0) {
                dest.setLastModified(System.currentTimeMillis())
                // Already on disk: the mtime touch adds no bytes.
                budget.recordWrite(cacheRoot, 0L)
                return@withRootLock dest.toURI().toString()
            }
            // Whatever is already under this key is not new on disk; only
            // the delta is. (Reached only when `dest` is absent or empty, so
            // this is normally 0 — it stays honest if that changes.)
            val previous = if (dest.isFile) dest.length() else 0L
            val tmp = File.createTempFile("opds-page-", ".part", cacheRoot)
            var published = true
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(dest)) {
                    if (!(dest.isFile && dest.length() > 0)) {
                        tmp.copyTo(dest, overwrite = true)
                    } else {
                        // Another writer published this page between our
                        // presence check and the rename. Its bytes are not
                        // ours to report.
                        published = false
                    }
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            budget.recordWrite(cacheRoot, if (published) bytes.size.toLong() - previous else 0L)
            dest.toURI().toString()
        } ?: run {
            cacheRoot.mkdirs()
            if (dest.isFile && dest.length() > 0) {
                dest.setLastModified(System.currentTimeMillis())
                return@run dest.toURI().toString()
            }
            val tmp = File.createTempFile("opds-page-", ".part", cacheRoot)
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(dest) && !(dest.isFile && dest.length() > 0)) {
                    tmp.copyTo(dest, overwrite = true)
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            dest.toURI().toString()
        }
    }
}
