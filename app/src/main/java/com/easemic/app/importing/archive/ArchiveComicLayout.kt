package com.easemic.app.importing.archive

import com.easemic.app.importing.NaturalFilenameComparator
import com.easemic.app.importing.isSupportedImageFilename
import java.util.Locale

/**
 * A47: the shape of a zip, decided from entry names alone.
 *
 * Owner-verified reality is that a resource archive is often a bulk dump, not one comic:
 * either a single wrapper directory holding N comic directories (layout A) or N comic
 * directories at the top level (layout B). Both are the same fact once you stop looking at
 * the top level and look at where the images actually live, so the rule is deliberately
 * depth-agnostic:
 *
 *   **one comic per directory that directly contains images.**
 *
 * That single rule covers every shape without special cases. A legacy archive whose images
 * sit at the top level buckets under the root (`""`) and stays one comic, exactly as before.
 * A wrapper never has to be detected, because the wrapper directory itself holds no images.
 * Chapter directories nested two deep are separate comics, which is what folder import
 * already does for the same layout on disk.
 *
 * A directory qualifies on the same terms as a folder on disk (see
 * `CandidateRecognizer`): at least [MIN_UNRECOGNIZED_COMIC_IMAGES] images, or fewer if it
 * declares intent with a `ComicInfo.xml` or an EhViewer `.ehviewer` marker. Everything that
 * does not qualify is skipped in place -- a stray `cover.jpg`, a `readme.txt`, Apple's
 * `__MACOSX` shadow tree, a `.DS_Store`, or a thin directory -- so junk costs one directory,
 * never the whole import.
 *
 * There are no volume limits here by owner decision: how much a user imports is the user's
 * business. Path safety (zip-slip, absolute paths, `..`) is a different concern and stays in
 * [ZipSafetyPolicy], which still rejects the archive outright.
 *
 * Encoding: names are split on `/` only, so the classifier is charset-agnostic. Legacy
 * archives written by 7-Zip on Japanese Windows carry raw cp932 bytes with the UTF-8 flag
 * off; `0x2F` is not a valid cp932 trail byte, so a mojibake decode still splits into the
 * same directories and produces the same comics -- a mojibake title, never a lost comic.
 */
data class ArchiveComicLayout(
    val comics: List<Comic>,
    /** Directories that held images but not enough of them to qualify as a comic. */
    val skippedDirectoryPaths: List<String> = emptyList(),
) {
    val isSingleComic: Boolean get() = comics.size == 1

    data class Comic(
        val directoryPath: String,
        val title: String,
        val imagePaths: List<String>,
        val comicInfoPath: String?,
        val markerPath: String?,
    )

    companion object {
        fun from(entryPaths: List<String>): ArchiveComicLayout {
            val buckets = mutableMapOf<String, MutableBucket>()
            entryPaths.forEach { path ->
                if (isNoise(path)) return@forEach
                val directory = path.substringBeforeLast('/', missingDelimiterValue = "")
                val name = path.substringAfterLast('/')
                val bucket = buckets.getOrPut(directory) { MutableBucket() }
                when {
                    isSupportedImageFilename(name) -> bucket.imagePaths += path
                    name.equals(COMIC_INFO_NAME, ignoreCase = true) -> bucket.comicInfoPath = path
                    name == MARKER_NAME -> bucket.markerPath = path
                }
            }
            val sorted = buckets.toSortedMap(NaturalFilenameComparator)
            val comics = sorted
                .filterValues { it.qualifies() }
                .map { (directory, bucket) ->
                    Comic(
                        directoryPath = directory,
                        title = directory.substringAfterLast('/'),
                        imagePaths = bucket.imagePaths.sortedWith(pageOrder),
                        comicInfoPath = bucket.comicInfoPath,
                        markerPath = bucket.markerPath,
                    )
                }
            val skipped = sorted
                .filterValues { !it.qualifies() && it.imagePaths.isNotEmpty() }
                .keys
                .toList()
            return ArchiveComicLayout(comics = comics, skippedDirectoryPaths = skipped)
        }

        private fun isNoise(path: String): Boolean =
            path.split('/').any { segment ->
                segment.startsWith("._") ||
                    segment.equals(".DS_Store", ignoreCase = true) ||
                    segment.equals("Thumbs.db", ignoreCase = true) ||
                    segment.uppercase(Locale.ROOT) in APPLE_SHADOW_DIRECTORIES
            }

        private val APPLE_SHADOW_DIRECTORIES = setOf("__MACOSX", "__MACOS")

        private val pageOrder = Comparator<String> { left, right ->
            NaturalFilenameComparator.compare(left, right).takeIf { it != 0 }
                ?: left.compareTo(right)
        }

        private const val COMIC_INFO_NAME = "ComicInfo.xml"
        private const val MARKER_NAME = ".ehviewer"
    }

    private class MutableBucket(
        val imagePaths: MutableList<String> = mutableListOf(),
        var comicInfoPath: String? = null,
        var markerPath: String? = null,
    ) {
        fun qualifies(): Boolean =
            imagePaths.isNotEmpty() &&
                (
                    imagePaths.size >= MIN_UNRECOGNIZED_COMIC_IMAGES ||
                        comicInfoPath != null ||
                        markerPath != null
                    )
    }
}

/**
 * A30 §3 rule 3, shared verbatim between folder import and archive import so a "folder of
 * folders" and a "zip of folders" cannot disagree about what counts as a comic.
 */
const val MIN_UNRECOGNIZED_COMIC_IMAGES = 3
