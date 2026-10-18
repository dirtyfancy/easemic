package com.easemic.app.feature.collect

import com.easemic.app.feature.download.serializedSeriesLedgerKey
import com.easemic.app.feature.source.SeriesDetailSnapshot
import com.easemic.app.sync.CloudArchiveUpload
import com.easemic.app.sync.CloudCollectionCard
import com.easemic.app.sync.CloudComicMeta
import com.easemic.app.sync.CloudSeriesChapterDescriptor
import com.easemic.app.sync.CloudSeriesChapterUploadRequest

/**
 * Mirror slice used when one serialized chapter finishes uploading.
 * [RoomCloudMirrorStore] implements this directly; JVM tests use a fake.
 */
interface SerializedChapterSyncMirror {
    suspend fun state(bucketFingerprint: String): CloudMirrorHydrationState?

    suspend fun upsertLiveCard(bucketFingerprint: String, card: CloudCollectionCard, meta: CloudComicMeta)

    suspend fun upsertLiveChapter(
        bucketFingerprint: String,
        card: CloudCollectionCard,
        descriptor: CloudSeriesChapterDescriptor,
        generation: Int,
    )

    /**
     * F3 (b) (spec §3.3): prefer-newer merge of a series' structured text
     * into the mirror's `seriesSnapshotJson`, so the mirror does not lag what
     * this phone's Room already holds. Never regresses newer stored text with
     * older incoming text.
     */
    suspend fun upsertLiveSeriesSnapshot(
        bucketFingerprint: String,
        cloudComicId: String,
        snapshot: SeriesDetailSnapshot,
    )
}

/**
 * Writes the mirror card, the chapter row, and the ledger row for one
 * serialized chapter that just landed. Local pages are released only after
 * the ledger row for this chapter exists.
 *
 * [seriesSnapshot] (F3 (b), spec §3.3) is the series' current Room text;
 * when supplied it is prefer-newer merged into the mirror's
 * `seriesSnapshotJson` so the mirror does not lag what this phone holds.
 */
suspend fun recordSerializedChapterSynced(
    ledger: CloudSyncLedgerStore,
    mirror: SerializedChapterSyncMirror,
    bucketFingerprint: String,
    request: CloudSeriesChapterUploadRequest,
    upload: CloudArchiveUpload,
    newGeneration: Int?,
    nowMs: Long,
    seriesSnapshot: SeriesDetailSnapshot? = null,
    releaseLocalPages: suspend () -> Unit,
) {
    val generation = newGeneration
        ?: mirror.state(bucketFingerprint)?.generation
        ?: 0
    val mirrorCard = upload.card.copy(
        providerId = request.series.providerId,
        providerSeriesId = request.series.providerSeriesId,
    )
    mirror.upsertLiveCard(
        bucketFingerprint,
        mirrorCard,
        CloudComicMeta(
            cloudComicId = upload.card.cloudComicId,
            title = request.series.title,
            pageCount = request.pageFiles.size,
            providerId = request.series.providerId,
            acquisitionRef = request.series.seriesAcquisitionRef,
            collectedAtEpochMs = request.chapter.collectedAtEpochMs,
        ),
    )
    seriesSnapshot?.let { snapshot ->
        mirror.upsertLiveSeriesSnapshot(
            bucketFingerprint = bucketFingerprint,
            cloudComicId = mirrorCard.cloudComicId,
            snapshot = snapshot,
        )
    }
    mirror.upsertLiveChapter(
        bucketFingerprint = bucketFingerprint,
        card = upload.card,
        descriptor = request.chapter.toSeriesDescriptor(),
        generation = generation,
    )
    val acquisitionKey = serializedSeriesLedgerKey(
        request.series.providerId,
        requireNotNull(request.series.providerSeriesId),
    )
    ledger.markSynced(
        fp = bucketFingerprint,
        acquisitionKey = acquisitionKey,
        cloudComicId = mirrorCard.cloudComicId,
        generation = generation,
        nowMs = nowMs,
        chapterIdentity = request.chapter.providerChapterId,
    )
    if (ledger.isSynced(bucketFingerprint, acquisitionKey, request.chapter.providerChapterId)) {
        releaseLocalPages()
    }
}
