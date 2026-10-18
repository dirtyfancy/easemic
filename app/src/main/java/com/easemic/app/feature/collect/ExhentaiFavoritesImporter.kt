package com.easemic.app.feature.collect

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.BrowseFeedRepository
import com.easemic.app.feature.library.galleryDedupKey
import com.easemic.app.feature.providers.ExhentaiEnableStore
import com.easemic.app.importing.ehentai.EhentaiClient
import com.easemic.app.importing.ehentai.EhentaiException
import com.easemic.app.importing.ehentai.EhentaiListEntry
import com.easemic.app.importing.ehentai.EhentaiListPage
import com.easemic.app.importing.ehentai.EhentaiManifestEmptyException
import com.easemic.app.importing.ehentai.judgeEhentaiGalleryBody
import com.easemic.app.importing.ehentai.EhentaiGalleryVerdict
import kotlinx.coroutines.CancellationException

data class ExhentaiFavoritesImportProgress(
    val currentPage: Int,
    val discoveredTotal: Int,
    val queuedCount: Int,
    val alreadyPresentCount: Int,
    val unavailableSkippedCount: Int,
) {
    fun formatStatusLine(): String = buildString {
        append("Scanning page $currentPage · $queuedCount queued")
        if (alreadyPresentCount > 0) append(", $alreadyPresentCount already in Library")
        if (unavailableSkippedCount > 0) append(", $unavailableSkippedCount unavailable skipped")
    }
}

sealed interface ExhentaiFavoritesImportResult {
    data class Completed(
        val totalFound: Int,
        val queuedCount: Int,
        val alreadyPresentCount: Int,
        val unavailableSkippedCount: Int,
    ) : ExhentaiFavoritesImportResult {
        fun formatSummaryLine(): String {
            if (totalFound == 0) return "No favorites found on your ExHentai account."
            val parts = mutableListOf<String>()
            parts += if (queuedCount == 1) "1 favorite queued" else "$queuedCount favorites queued"
            if (alreadyPresentCount > 0) parts += "$alreadyPresentCount already in Library"
            if (unavailableSkippedCount > 0) parts += "$unavailableSkippedCount unavailable skipped"
            return parts.joinToString(" · ")
        }
    }

    data object Unauthorized : ExhentaiFavoritesImportResult
    data class Failed(val message: String) : ExhentaiFavoritesImportResult
}

/**
 * #E117: Pages through the signed-in user's ExHentai favorites (`favorites.php`),
 * deduplicates against already-collected / local / cloud galleries by canonical
 * `ehentai://<gid>`, checks gallery availability via a lightweight first-grid
 * probe so removed/unavailable galleries are skipped quietly (without creating
 * noisy `GONE` / `Needs attention` rows), and queues each live gallery through
 * [enqueueGallery].
 */
class ExhentaiFavoritesImporter(
    private val fetchFavoritesPage: suspend (nextSeekGid: Long?, pageIndex: Int?) -> EhentaiListPage,
    private val probeGalleryAvailable: suspend (galleryId: Long, token: String) -> Boolean,
    private val existingDedupKeys: () -> Set<String>,
    private val enqueueGallery: suspend (BrowseFeedItem) -> Unit,
    private val maxPages: Int = DEFAULT_MAX_PAGES,
) {
    suspend fun run(
        onProgress: (ExhentaiFavoritesImportProgress) -> Unit = {},
    ): ExhentaiFavoritesImportResult {
        val seenGids = HashSet<Long>()
        val knownKeys = existingDedupKeys().toMutableSet()
        var currentPage = 0
        var nextSeekGid: Long? = null
        var nextPageIndex: Int? = 0
        var discoveredTotal = 0
        var queuedCount = 0
        var alreadyPresentCount = 0
        var unavailableSkippedCount = 0

        while (currentPage < maxPages) {
            currentPage += 1
            val page = try {
                fetchFavoritesPage(nextSeekGid, nextPageIndex)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: EhentaiException.ExhentaiUnauthorized) {
                return ExhentaiFavoritesImportResult.Unauthorized
            } catch (error: Exception) {
                return if (currentPage == 1) {
                    ExhentaiFavoritesImportResult.Failed(
                        error.message?.takeIf { it.isNotBlank() } ?: "Could not load ExHentai favorites.",
                    )
                } else {
                    // Partial pagination after at least one page succeeded finishes gracefully.
                    ExhentaiFavoritesImportResult.Completed(
                        totalFound = discoveredTotal,
                        queuedCount = queuedCount,
                        alreadyPresentCount = alreadyPresentCount,
                        unavailableSkippedCount = unavailableSkippedCount,
                    )
                }
            }

            val freshEntries = page.entries.filter { entry -> seenGids.add(entry.galleryId) }
            if (freshEntries.isEmpty()) break

            for (entry in freshEntries) {
                discoveredTotal += 1
                val acquisitionUrl = BrowseFeedRepository.ehentaiAcquisitionUrl(entry.galleryId, entry.token)
                val dedupKey = galleryDedupKey(acquisitionUrl) ?: "ehentai://${entry.galleryId}"
                if (dedupKey in knownKeys) {
                    alreadyPresentCount += 1
                    onProgress(
                        ExhentaiFavoritesImportProgress(
                            currentPage = currentPage,
                            discoveredTotal = discoveredTotal,
                            queuedCount = queuedCount,
                            alreadyPresentCount = alreadyPresentCount,
                            unavailableSkippedCount = unavailableSkippedCount,
                        ),
                    )
                    continue
                }

                val available = try {
                    probeGalleryAvailable(entry.galleryId, entry.token)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }

                if (!available) {
                    unavailableSkippedCount += 1
                } else {
                    knownKeys += dedupKey
                    enqueueGallery(entry.toBrowseFeedItem(acquisitionUrl))
                    queuedCount += 1
                }

                onProgress(
                    ExhentaiFavoritesImportProgress(
                        currentPage = currentPage,
                        discoveredTotal = discoveredTotal,
                        queuedCount = queuedCount,
                        alreadyPresentCount = alreadyPresentCount,
                        unavailableSkippedCount = unavailableSkippedCount,
                    ),
                )
            }

            when {
                page.nextSeekGid != null && page.nextSeekGid != nextSeekGid -> {
                    nextSeekGid = page.nextSeekGid
                    nextPageIndex = null
                }
                page.nextPageIndex != null && page.nextPageIndex != nextPageIndex -> {
                    nextSeekGid = null
                    nextPageIndex = page.nextPageIndex
                }
                else -> break
            }
        }

        return ExhentaiFavoritesImportResult.Completed(
            totalFound = discoveredTotal,
            queuedCount = queuedCount,
            alreadyPresentCount = alreadyPresentCount,
            unavailableSkippedCount = unavailableSkippedCount,
        )
    }

    companion object {
        const val DEFAULT_MAX_PAGES = 100

        /**
         * Probes whether a gallery on E-Hentai / ExHentai is available for download.
         * Returns `false` quietly when the gallery has been removed, taken down,
         * or returns an empty/unavailable manifest, and `true` when alive (or on
         * transient network/rate-limit errors so the durable download queue's own
         * backoff can handle temporary hiccups).
         */
        fun probeWithClient(client: EhentaiClient, galleryId: Long, token: String): Boolean =
            try {
                val plan = client.fetchGalleryGridPage(galleryId = galleryId, token = token, gridIndex = 0)
                plan.pageViewPaths.isNotEmpty()
            } catch (interstitial: EhentaiException.Interstitial) {
                val html = interstitial.response.body.toString(Charsets.UTF_8)
                when (judgeEhentaiGalleryBody(html)) {
                    EhentaiGalleryVerdict.Removed,
                    EhentaiGalleryVerdict.NotFound,
                    is EhentaiGalleryVerdict.UnavailableUnconfirmed,
                    -> false
                    else -> false
                }
            } catch (_: EhentaiManifestEmptyException) {
                false
            } catch (http: EhentaiException.Http) {
                http.code != 404 && http.code != 410
            } catch (_: EhentaiException.RateLimited) {
                true
            } catch (_: Exception) {
                false
            }

        private fun EhentaiListEntry.toBrowseFeedItem(acquisitionUrl: String): BrowseFeedItem =
            BrowseFeedItem(
                id = "${ExhentaiEnableStore.SOURCE_ID}:$galleryId",
                title = title,
                secondaryLine = listOfNotNull(category, uploader).joinToString(" · "),
                providerId = ExhentaiEnableStore.SOURCE_ID,
                acquisitionUrl = acquisitionUrl,
                coverUrl = thumbUrl,
                pageCount = pageCount,
                language = language,
                category = category,
                rating = rating,
                author = uploader,
            )
    }
}
