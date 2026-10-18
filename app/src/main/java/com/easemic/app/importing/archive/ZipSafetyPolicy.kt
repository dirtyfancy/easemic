package com.easemic.app.importing.archive

import com.easemic.app.model.ArchiveFailure
import java.util.Locale

/**
 * A47: how much a user imports is the user's business.
 *
 * The entry-count, image-count and total-uncompressed-bytes caps are gone by owner decision:
 * a real resource archive is a bulk dump of hundreds of comics, and a volume cap only ever
 * rejected a legitimate import. What stays is everything that guards against *corruption*
 * rather than *size*: zip-slip and absolute/traversal paths, duplicate normalized paths,
 * encryption, nested archives, the per-entry byte ceiling, and the compression-ratio check
 * that catches a zip bomb. Those protect the filesystem and the device, which is a different
 * concern from how many comics are inside.
 */
data class ZipSafetyPolicy(
    val maxEntryUncompressedBytes: Long = 512L * 1024 * 1024,
    val maxCompressionRatio: Long = 200,
    val ratioThresholdBytes: Long = 1024 * 1024,
    val maxArchiveCacheBytes: Long = 20L * 1024 * 1024 * 1024,
) {
    fun validate(entries: List<ZipEntryFacts>): ArchiveFailure? {
        if (entries.any { it.uncompressedBytes > maxEntryUncompressedBytes }) {
            return ArchiveFailure.ENTRY_TOO_LARGE
        }
        if (entries.any { it.isEncryptedOrUnsupported }) {
            return ArchiveFailure.ENCRYPTED_OR_UNSUPPORTED
        }
        if (entries.any { entry ->
                entry.path.substringAfterLast('.').lowercase(Locale.ROOT) in NESTED_ARCHIVE_EXTENSIONS
            }
        ) {
            return ArchiveFailure.NESTED_ARCHIVE
        }
        if (entries.any { it.uncompressedBytes < 0 }) return ArchiveFailure.CORRUPT
        entries.forEach { entry ->
            if (exceedsRatio(entry.uncompressedBytes, entry.compressedBytes)) {
                return ArchiveFailure.EXCESSIVE_RATIO
            }
        }
        var totalUncompressedBytes = 0L
        var totalCompressedBytes = 0L
        entries.forEach { entry ->
            if (entry.compressedBytes < 0 || totalCompressedBytes > Long.MAX_VALUE - entry.compressedBytes) {
                return ArchiveFailure.EXCESSIVE_RATIO
            }
            // Volume is unlimited by owner decision, but the running totals still feed the
            // ratio check, so they saturate instead of wrapping negative -- an overflowed
            // (negative) total would silently disable zip-bomb detection.
            totalUncompressedBytes = saturatingAdd(totalUncompressedBytes, entry.uncompressedBytes)
            totalCompressedBytes = saturatingAdd(totalCompressedBytes, entry.compressedBytes)
            if (exceedsRatio(totalUncompressedBytes, totalCompressedBytes)) {
                return ArchiveFailure.EXCESSIVE_RATIO
            }
        }
        entries.firstNotNullOfOrNull { validateEntryPath(it.path) }?.let { return it }
        validateDuplicatePaths(entries.map { it.path })?.let { return it }
        return null
    }

    internal fun validateEntryPath(path: String): ArchiveFailure? =
        if (normalize(path) == null) ArchiveFailure.UNSAFE_PATH else null

    internal fun validateDuplicatePaths(paths: List<String>): ArchiveFailure? {
        val normalizedPaths = mutableSetOf<String>()
        paths.forEach { path ->
            val normalized = normalize(path) ?: return ArchiveFailure.UNSAFE_PATH
            if (!normalizedPaths.add(normalized.lowercase(Locale.ROOT))) {
                return ArchiveFailure.DUPLICATE_PATH
            }
        }
        return null
    }

    internal fun validateNestedArchive(path: String): ArchiveFailure? =
        if (path.substringAfterLast('.').lowercase(Locale.ROOT) in NESTED_ARCHIVE_EXTENSIONS) {
            ArchiveFailure.NESTED_ARCHIVE
        } else {
            null
        }

    internal fun validateExpansion(
        compressedBytes: Long,
        uncompressedBytes: Long,
    ): ArchiveFailure? =
        if (exceedsRatio(uncompressedBytes, compressedBytes)) ArchiveFailure.EXCESSIVE_RATIO else null

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun exceedsRatio(uncompressedBytes: Long, compressedBytes: Long): Boolean =
        uncompressedBytes > ratioThresholdBytes &&
            (
                compressedBytes <= 0 ||
                    uncompressedBytes / compressedBytes > maxCompressionRatio ||
                    (
                        uncompressedBytes / compressedBytes == maxCompressionRatio &&
                            uncompressedBytes % compressedBytes > 0
                        )
                )

    private fun normalize(path: String): String? {
        if (
            path.isEmpty() ||
            path.startsWith("/") ||
            path.contains('\\') ||
            path.contains('\u0000') ||
            DRIVE_PATH.containsMatchIn(path)
        ) {
            return null
        }
        val segments = path.split('/')
        if (segments.any { it == ".." }) return null
        val normalized = segments.filter { it.isNotEmpty() && it != "." }
        return normalized.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    private companion object {
        val DRIVE_PATH = Regex("^[A-Za-z]:")
        val NESTED_ARCHIVE_EXTENSIONS = setOf("zip", "cbz")
    }
}

data class ZipEntryFacts(
    val path: String,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val isImage: Boolean,
    val isEncryptedOrUnsupported: Boolean = false,
)
