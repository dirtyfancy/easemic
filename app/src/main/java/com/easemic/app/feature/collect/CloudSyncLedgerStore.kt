package com.easemic.app.feature.collect

import com.easemic.app.data.room.CloudSyncLedgerDao
import com.easemic.app.data.room.CloudSyncLedgerEntity
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.serializedSeriesLedgerKey
import com.easemic.app.feature.library.galleryDedupKey
import com.easemic.app.sync.CloudCollectionCard
import com.easemic.app.sync.CloudCardLayout
import com.easemic.app.data.room.CloudMirrorChapterEntity

const val LEGACY_CHAPTER_IDENTITY = "legacy:0"

/**
 * Durable per-comic sync ledger (S3 Task 1, owner bug C3/A3). Records that a
 * comic is uploaded to a specific bucket so cold-start reconcile logic can
 * consult a durable local truth INSTEAD of a live R2 re-list that may
 * momentarily fail. See [com.easemic.app.data.room.CloudSyncLedgerEntity].
 *
 * Gallery rows use the token-free canonical key
 * ([com.easemic.app.feature.library.galleryDedupKey]).
 * Serialized rows use [serializedSeriesLedgerKey] from provider identity; a
 * series acquisition URL is only a locator and is never a ledger key.
 */
interface CloudSyncLedgerStore {
    suspend fun markSynced(
        fp: String,
        acquisitionKey: String,
        cloudComicId: String,
        generation: Int,
        nowMs: Long,
        chapterIdentity: String = LEGACY_CHAPTER_IDENTITY,
    )

    suspend fun isSynced(fp: String, acquisitionKey: String, chapterIdentity: String = LEGACY_CHAPTER_IDENTITY): Boolean

    /** A comic uncollected/trashed/deleted from the cloud may be re-uploaded later. */
    suspend fun clear(fp: String, acquisitionKey: String, chapterIdentity: String = LEGACY_CHAPTER_IDENTITY)

    suspend fun clearSeries(fp: String, acquisitionKey: String)

    suspend fun syncedChapterIds(fp: String, acquisitionKey: String): Set<String>

    /** Wipe one bucket's ledger (e.g. that bucket is no longer ours). */
    suspend fun invalidateBucket(fp: String)

    /** Rebind hygiene: keep the bound bucket's ledger, drop every other bucket's. */
    suspend fun invalidateAllExcept(fp: String)

    /** Disconnect wipe: no bucket is bound anymore. */
    suspend fun invalidateAll()

    /**
     * A24-1: a ledger row must not outlive the [CloudCollectionCard] mirror
     * row it was derived from. A hydration re-walk that prunes a mirror card
     * no longer present upstream
     * ([com.easemic.app.feature.collect.CloudHydrationMirror.pruneStaleCards])
     * otherwise leaves that comic's ledger row behind forever, and every
     * re-upload guard that consults the ledger (the catch-up sweep, the
     * cold-start handoff repair) keeps treating it as already-synced and
     * skips it. Reconciliation compares each row with the supplied mirror
     * cards by cloud id and serialized identity; a SERIES card whose summary
     * identity is still absent is left untouched until hydration completes.
     * The `generation < keepGeneration` guard protects a row written by THIS
     * walk's own in-flight upload. Trashed cards remain in the mirror, so
     * their ledger rows are retained. A blank [fp] no-ops and returns 0.
     *
     * Returns the number of rows removed.
     */
    suspend fun reconcileWithMirror(
        fp: String,
        keepGeneration: Int,
        mirrorCards: List<CloudCollectionCard>,
    ): Int
}

/** Room-backed [CloudSyncLedgerStore] (sibling to [RoomCloudMirrorStore]). */
class RoomCloudSyncLedgerStore(
    private val dao: CloudSyncLedgerDao,
) : CloudSyncLedgerStore {
    suspend fun markSynced(fp: String, acquisitionKey: String, cloudComicId: String, generation: Int, nowMs: Long) =
        markSynced(fp, acquisitionKey, cloudComicId, generation, nowMs, LEGACY_CHAPTER_IDENTITY)

    suspend fun isSynced(fp: String, acquisitionKey: String): Boolean =
        isSynced(fp, acquisitionKey, LEGACY_CHAPTER_IDENTITY)

    suspend fun clear(fp: String, acquisitionKey: String) =
        clear(fp, acquisitionKey, LEGACY_CHAPTER_IDENTITY)

    override suspend fun markSynced(
        fp: String,
        acquisitionKey: String,
        cloudComicId: String,
        generation: Int,
        nowMs: Long,
        chapterIdentity: String,
    ) {
        if (fp.isBlank() || acquisitionKey.isBlank()) return
        dao.upsert(
            CloudSyncLedgerEntity(
                bucketFingerprint = fp,
                acquisitionKey = acquisitionKey,
                cloudComicId = cloudComicId,
                generation = generation,
                syncedAtMs = nowMs,
                chapterIdentity = chapterIdentity,
            ),
        )
    }

    override suspend fun isSynced(fp: String, acquisitionKey: String, chapterIdentity: String): Boolean {
        if (fp.isBlank() || acquisitionKey.isBlank()) return false
        return dao.find(fp, acquisitionKey, chapterIdentity) != null
    }

    override suspend fun clear(fp: String, acquisitionKey: String, chapterIdentity: String) {
        if (fp.isBlank() || acquisitionKey.isBlank()) return
        dao.deleteOne(fp, acquisitionKey, chapterIdentity)
    }

    override suspend fun clearSeries(fp: String, acquisitionKey: String) {
        if (fp.isBlank() || acquisitionKey.isBlank()) return
        dao.deleteSeries(fp, acquisitionKey)
    }

    override suspend fun syncedChapterIds(fp: String, acquisitionKey: String): Set<String> {
        if (fp.isBlank() || acquisitionKey.isBlank()) return emptySet()
        return dao.chapterIds(fp, acquisitionKey).toSet()
    }

    override suspend fun invalidateBucket(fp: String) {
        if (fp.isBlank()) return
        dao.deleteForBucket(fp)
    }

    override suspend fun invalidateAllExcept(fp: String) {
        if (fp.isBlank()) return
        dao.deleteAllExcept(fp)
    }

    override suspend fun invalidateAll() {
        dao.deleteAll()
    }

    override suspend fun reconcileWithMirror(
        fp: String,
        keepGeneration: Int,
        mirrorCards: List<CloudCollectionCard>,
    ): Int {
        if (fp.isBlank()) return 0
        val cardsById = mirrorCards.associateBy { it.cloudComicId }
        var removed = 0
        dao.rows(fp).forEach { row ->
            if (row.generation >= keepGeneration) return@forEach
            val card = cardsById[row.cloudComicId]
            if (card == null) {
                dao.deleteOne(fp, row.acquisitionKey, row.chapterIdentity)
                removed++
                return@forEach
            }
            val expectedSeriesKey = if (card.layoutKind == CloudCardLayout.SERIES) {
                val provider = card.providerId?.takeIf { it.isNotBlank() }
                val seriesId = card.providerSeriesId?.takeIf { it.isNotBlank() }
                // A tier-1 SERIES card has no identity until its summary is
                // hydrated. Keep its rows until that identity is available.
                if (provider == null || seriesId == null) return@forEach
                serializedSeriesLedgerKey(SeriesIdentityV2(provider, seriesId))
            } else {
                null
            }
            if (expectedSeriesKey != null) {
                if (row.acquisitionKey != expectedSeriesKey) {
                    dao.deleteOne(fp, row.acquisitionKey, row.chapterIdentity)
                    removed++
                }
            } else if (row.acquisitionKey.startsWith("serialized-series:")) {
                dao.deleteOne(fp, row.acquisitionKey, row.chapterIdentity)
                removed++
            }
        }
        return removed
    }
}

/**
 * One-shot, idempotent backfill (S3 Task 6, owner bug 2026-09-04): the ledger
 * (S3 Task 1) is only ever written going FORWARD, by [CloudIngestRunner]'s
 * `onSynced` hook — a comic that finished uploading to [fp] BEFORE the ledger
 * existed (or before this device ever ran the code that writes it) has no
 * row, so every ledger-consulting re-upload guard (pump-level presence guard,
 * cold-start handoff repair) still treats it as never-synced and re-drives
 * it. The local mirror's own LIVE (non-trashed — see
 * [RoomCloudMirrorStore.activeCards]) cards already prove each one reached
 * the cloud, independent of when the ledger came into existence, so they are
 * the correct source to backfill from.
 *
 * Skips a SERIES card until its provider identity is hydrated and any card
 * whose key is ALREADY ledgered (never clobbers an existing row's
 * `cloudComicId`/`generation`/`syncedAtMs`) —
 * safe to call on every cold start. Returns the number of NEW rows written.
 */
suspend fun CloudSyncLedgerStore.backfillFromMirror(
    fp: String,
    liveCards: List<CloudCollectionCard>,
    generation: Int,
    nowMs: Long,
    chaptersByCard: Map<String, List<CloudMirrorChapterEntity>> = emptyMap(),
): Int {
    var backfilled = 0
    for (card in liveCards) {
        val key = if (card.layoutKind == CloudCardLayout.SERIES) {
            val provider = card.providerId?.takeIf { it.isNotBlank() } ?: continue
            val seriesId = card.providerSeriesId?.takeIf { it.isNotBlank() } ?: continue
            serializedSeriesLedgerKey(provider, seriesId)
        } else {
            galleryDedupKey(card.acquisitionRef) ?: continue
        }
        val chapters = chaptersByCard[card.cloudComicId]
        if (card.layoutKind == CloudCardLayout.SERIES) {
            if (chapters == null) continue
            for (chapter in chapters) {
                if (isSynced(fp, key, chapter.chapterIdentity)) continue
                markSynced(fp, key, card.cloudComicId, generation, nowMs, chapter.chapterIdentity)
                backfilled++
            }
        } else if (!isSynced(fp, key)) {
            markSynced(fp, key, card.cloudComicId, generation, nowMs)
            backfilled++
        }
    }
    return backfilled
}
