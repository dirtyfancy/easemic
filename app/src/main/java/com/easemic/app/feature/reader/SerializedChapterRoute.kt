package com.easemic.app.feature.reader

import com.easemic.app.feature.browse.OnlineReaderSession
import com.easemic.app.feature.download.ChapterIdentityV2
import com.easemic.app.feature.download.DownloadLocator
import com.easemic.app.feature.download.DownloadPageBytesAdapter
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.PageTransferResult
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.serializedChapterAdapterIdentity
import com.easemic.app.feature.download.serializedChapterLocatorKey
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Direction requested when a serialized reader reaches a chapter edge. */
enum class ReaderChapterBoundary { START, END }

/** Context is optional so gallery sessions keep their existing behavior. */
data class ReaderChapterContext(
    val chapterId: String,
    val chapterLabel: String? = null,
)

/**
 * The ephemeral route for one readable serialized chapter.
 *
 * The route deliberately carries no Room/download identity. Its V1 identity
 * is scoped by the series and chapter only for the page adapter request; the
 * caller owns the short-lived cache used to turn a fetched page into a Coil URI.
 */
data class SerializedChapterRoute(
    val series: SeriesIdentityV2,
    val chapter: ChapterIdentityV2,
    val title: String,
    val pageUrls: List<String>,
    val initialOrdinal: Int,
) {
    init {
        require(chapter.series == series) { "chapter must belong to series" }
    }

    /** The V1-shaped key accepted by [DownloadPageBytesAdapter]. */
    val galleryIdentity: GalleryIdentityV1
        get() = serializedChapterAdapterIdentity(series, chapter.canonicalChapterId)

    /**
     * A chapter locator is intentionally built from the chapter page list.
     * Serialized sources expose no gallery acquisition URL, while the first
     * page URL is the stable request seed used by the test source and adapter.
     */
    val locator: DownloadLocator
        get() = DownloadLocator(
            acquisitionUrl = pageUrls.firstOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: chapter.canonicalChapterId,
            title = title,
        )

    companion object {
        /** Prepare metadata before opening; page transfers remain lazy. */
        fun prepare(
            pageAdapter: DownloadPageBytesAdapter,
            chapter: ChapterIdentityV2,
            title: String,
            initialOrdinal: Int,
            legacyPageLocator: (Int) -> String,
        ): SerializedChapterRoute {
            val identity = serializedChapterAdapterIdentity(chapter.series, chapter.canonicalChapterId)
            val locator = DownloadLocator(
                serializedChapterLocatorKey(chapter.series, chapter.canonicalChapterId), title,
            )
            val fileOnline = pageAdapter.prefersFileBackedOnlineRead(identity, locator)
            val manifest = pageAdapter.fetchManifest(
                identity,
                if (fileOnline) locator else DownloadLocator(chapter.canonicalChapterId, title),
            )
            require(manifest.expectedPageCount > 0) { "serialized chapter has no readable pages" }
            return SerializedChapterRoute(
                chapter.series, chapter, title,
                List(manifest.expectedPageCount) { ordinal ->
                    if (fileOnline) locator.acquisitionUrl else legacyPageLocator(ordinal)
                },
                clampReaderOrdinal(initialOrdinal, manifest.expectedPageCount),
            )
        }
    }

    private fun locatorFor(ordinal: Int): DownloadLocator =
        DownloadLocator(
            acquisitionUrl = pageUrls.getOrNull(ordinal)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: locator.acquisitionUrl,
            title = title,
        )

    /**
     * Builds the existing online reader session. [cachePage] is ephemeral
     * caller-owned storage (usually a file in cacheDir); it must return a URI
     * readable by Coil. File-capable adapters require [cachePageFile], which owns
     * the unpublished destination and only returns a URI after successful transfer.
     * Pages are fetched lazily, so this route performs no network work while opening.
     */
    fun session(
        pageAdapter: DownloadPageBytesAdapter,
        cachePageFile: (suspend (ordinal: Int, transfer: suspend (Path) -> PageTransferResult) -> String)? = null,
        cachePage: (ordinal: Int, bytes: ByteArray) -> String,
    ): OnlineReaderSession {
        val fileOnline = pageAdapter.prefersFileBackedOnlineRead(galleryIdentity, locator)
        require(!fileOnline || cachePageFile != null) { "file-backed online reading requires cachePageFile" }
        val start = clampReaderOrdinal(initialOrdinal, pageUrls.size)
        return OnlineReaderSession(
            title = title,
            // A serialized page URL is a source locator, never a display URL, so
            // every slot is blank and resolved through the page adapter — the
            // same shape the gallery/OPDS online reader uses (readOnline seeds
            // only readable file:// pages and blanks the rest).
            imageUrls = List(pageUrls.size) { "" },
            initialOrdinal = start,
            chapterContext = ReaderChapterContext(chapter.canonicalChapterId, title),
            resolveBlankPageUrl = { ordinal ->
                withContext(Dispatchers.IO) {
                    if (fileOnline) {
                        checkNotNull(cachePageFile).invoke(ordinal) { destination ->
                            pageAdapter.fetchPageToFile(galleryIdentity, locatorFor(ordinal), ordinal, destination, 0)
                        }
                    } else {
                        val bytes = pageAdapter.fetchPageBytes(galleryIdentity, locatorFor(ordinal), ordinal)
                        cachePage(ordinal, bytes)
                    }
                }
            },
        )
    }
}
