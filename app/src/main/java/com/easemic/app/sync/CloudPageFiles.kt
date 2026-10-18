package com.easemic.app.sync

import java.io.File

object CloudPageFiles {
    /** Sorted image files under an importer pages directory. */
    fun listSorted(pagesDir: File): List<File> {
        if (!pagesDir.isDirectory) return emptyList()
        return pagesDir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith('.') }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }
}
