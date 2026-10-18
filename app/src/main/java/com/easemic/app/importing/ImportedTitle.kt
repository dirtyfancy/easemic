package com.easemic.app.importing

private val ARCHIVE_EXTENSIONS = setOf("cbz", "zip")

private fun strippedFileName(fileName: String): String {
    val trimmed = fileName.trim()
    if (trimmed.isEmpty()) return ""
    val extension = trimmed.substringAfterLast('.', missingDelimiterValue = "")
    if (extension.lowercase() !in ARCHIVE_EXTENSIONS) return trimmed
    return trimmed.substringBeforeLast('.').trim()
}

/**
 * The title a comic gets from the file system alone: the `.cbz`'s own base name,
 * or the name of the directory wrapping it. Only a real archive extension is
 * stripped, so `Vol. 3` and `bocchi「…` keep every character they came with.
 */
fun importedTitleFrom(fileName: String): String =
    strippedFileName(fileName).ifBlank { fileName }

/**
 * A46: every Mihon-imported comic showed up as "Chapter", because ComicInfo.xml
 * carries a per-chapter `<Title>` and that won over the file system name. The
 * owner's rule is the whole rule -- the file system name IS the title, so
 * `<Title>`/`<Series>` never override it; `<Series>` still fills the separate
 * `series` field. ComicInfo is consulted for the title only when the name on
 * disk is nothing but whitespace or a bare extension.
 *
 * Which file system name is the right one is decided upstream, where the tree
 * shape is known ([source.GenericDirectorySourceAdapter]).
 */
fun importedTitle(fileName: String, comicInfoTitle: String?): String {
    val stripped = strippedFileName(fileName)
    if (stripped.isNotBlank()) return stripped
    return comicInfoTitle?.trim()?.ifEmpty { null } ?: fileName
}
