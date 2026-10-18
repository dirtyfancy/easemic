package com.easemic.app.importing.opds

import com.easemic.app.model.PageDescriptor
import java.io.File
import java.util.zip.ZipFile

/** Extracts named archive entries into destDir using the importer filename scheme. */
fun extractOpdsAcquisitionPages(
    archive: File,
    destDir: File,
    pages: List<PageDescriptor>,
) {
    destDir.mkdirs()
    ZipFile(archive).use { zip ->
        pages.forEachIndexed { index, page ->
            val entryName = page.archiveEntryPath ?: page.displayName
            val entry = zip.getEntry(entryName)
                ?: error("Missing archive entry: $entryName")
            val safeName = File(page.displayName).name.ifBlank { "page.bin" }
            val out = File(destDir, "%04d_%s".format(index, safeName))
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
