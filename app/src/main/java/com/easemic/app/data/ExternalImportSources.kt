package com.easemic.app.data

import com.easemic.app.model.AdapterKind

/**
 * Sources screen / import management lists only externally authorized imports
 * (SAF folder tree, comic archives, and OPDS-style import commits that use
 * directory/archive adapters). Provider download-queue rows use
 * [AdapterKind.CATALOG_DOWNLOAD] and belong on Library / Downloads, not Sources.
 */
fun AdapterKind.isExternalImportSource(): Boolean = when (this) {
    AdapterKind.EHVIEWER,
    AdapterKind.GENERIC_DIRECTORY,
    AdapterKind.COMIC_ARCHIVE,
    -> true
    AdapterKind.CATALOG_DOWNLOAD -> false
}

fun Iterable<AdapterKind>.filterExternalImportSources(): List<AdapterKind> =
    filter { it.isExternalImportSource() }
