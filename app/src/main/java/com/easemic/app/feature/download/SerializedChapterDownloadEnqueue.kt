package com.easemic.app.feature.download

import com.easemic.app.feature.source.ChapterListResult

/**
 * #E73 spec §4 — the download picker's enqueue: one local-only fetch job per
 * selected chapter, a resume locator per chapter, and NO bookmark write (the
 * series is not followed and nothing is uploaded).
 *
 * This handler mirrors [com.easemic.app.feature.collect.SerializedSeriesCollector.collectFrom]'s
 * locator + `upsertEnqueue` shape with `intentCloud = false`, and has no
 * collect/bookmark or cloud-queue seam at all — the only effects it can fire
 * are the three injected below. `upsertEnqueue` OR-merges intents in the DAO,
 * so a chapter that is already queued/collected keeps its `intentCloud`.
 */
data class SerializedDownloadChapterRequest(
    val chapterId: String,
    val label: String,
)

/**
 * Enqueues [chapters] as local-only downloads for [series]. Returns the
 * chapter ids that ended up queued (WAIT/DOWNLOAD) and kicks the pump once
 * when anything is queued. Effect seams: [putLocator] writes the resume
 * locator for the chapter's storage identity, [upsertEnqueue] is the store's
 * transactional upsert (identity, expectedPageCount, intentLocal, intentCloud,
 * chapterIdentity).
 */
suspend fun enqueueLocalOnlyChapterDownloads(
    series: GalleryIdentityV1,
    title: String,
    coverLocator: String?,
    /** The fetched chapter list — the same one collect reconciles before queueing. */
    chapterList: ChapterListResult,
    chapters: List<SerializedDownloadChapterRequest>,
    /**
     * The collect path's series reconcile (`SerializedSeriesLibrary.reconcileSeries`).
     * Without it a downloaded chapter cannot commit: `tryFinish` needs the
     * series Library item and its reading units (`RoomLibraryRepository.commitChapterDownloadLocalAvailability`),
     * and spec §6's "shelf rows + files" acceptance depends on exactly that.
     * It writes Library facts only — never the follow bookmark (spec §4).
     */
    reconcileSeries: suspend (GalleryIdentityV1, String, String?, ChapterListResult) -> Unit,
    putLocator: suspend (storageIdentity: GalleryIdentityV1, locator: DownloadLocator) -> Unit,
    upsertEnqueue: suspend (
        identity: GalleryIdentityV1,
        expectedPageCount: Int?,
        intentLocal: Boolean,
        intentCloud: Boolean,
        chapterIdentity: String,
    ) -> DownloadJob,
    kickDownloadPump: () -> Unit,
): List<String> {
    reconcileSeries(series, title, coverLocator, chapterList)
    val sourceSeries = series.asSeriesIdentityV2()
    val queued = mutableListOf<String>()
    for (chapter in chapters) {
        val storageIdentity = serializedChapterStorageIdentity(sourceSeries, chapter.chapterId)
        putLocator(
            storageIdentity,
            DownloadLocator(
                acquisitionUrl = serializedChapterLocatorKey(sourceSeries, chapter.chapterId),
                title = chapter.label,
                coverUrl = coverLocator,
            ),
        )
        // (identity, expectedPageCount=null, intentLocal=true, intentCloud=false,
        //  chapterIdentity) — function-type calls take positional arguments.
        val job = upsertEnqueue(series, null, true, false, chapter.chapterId)
        if (job.state == DownloadJobState.WAIT || job.state == DownloadJobState.DOWNLOAD) {
            queued += chapter.chapterId
        }
    }
    if (queued.isNotEmpty()) kickDownloadPump()
    return queued
}
