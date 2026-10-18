package com.easemic.app.storage

import com.easemic.app.feature.browse.OnlineReaderSession
import com.easemic.app.feature.download.DownloadLocator
import com.easemic.app.feature.download.DownloadPageBytesAdapter
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.PageTransferResult
import com.easemic.app.feature.reader.SerializedChapterRoute
import com.easemic.app.feature.reader.SerializedPageFileCache
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** Backend session seam: file sources stream; byte sources keep fetching one page. */
fun serializedOnlinePageSession(
    route: SerializedChapterRoute,
    adapter: DownloadPageBytesAdapter,
    cache: SerializedPageFileCache,
    generation: Long,
): OnlineReaderSession {
    val fileOnline = adapter.prefersFileBackedOnlineRead(route.galleryIdentity, route.locator)
    // A fetched-at chapter-list timestamp changes on detail reopen. Byte pages use their stable chapter identity;
    // the cache budget/clear owns their lifetime. File sources retain their existing unresolved-plan generation.
    val reader = cache.reader(route.chapter, if (fileOnline) generation else 0L)
    val onlineAdapter = if (fileOnline) adapter else object : DownloadPageBytesAdapter by adapter {
        override fun prefersFileBackedOnlineRead(identity: GalleryIdentityV1, locator: DownloadLocator) = true
        override suspend fun fetchPageToFile(identity: GalleryIdentityV1, locator: DownloadLocator, ordinal: Int,
            destination: Path, offset: Long): PageTransferResult = withContext(Dispatchers.IO) {
            // Never call the source's file seam: for 再漫画 it fetches an entire chapter ZIP.
            val bytes = runInterruptible { adapter.fetchPageBytes(identity, locator, ordinal) }
            Files.write(destination, bytes)
            PageTransferResult(bytes.size.toLong(), bytes.size.toLong())
        }
    }
    try {
        return route.session(
            pageAdapter = onlineAdapter,
            cachePageFile = reader::cachePageFile,
            cachePage = { _, _ -> error("serialized online cache must own publication") },
        ).copy(onClose = { reader.close() })
    } catch (failure: Throwable) {
        reader.close()
        throw failure
    }
}
