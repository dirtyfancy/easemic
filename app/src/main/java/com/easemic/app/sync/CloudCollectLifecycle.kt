package com.easemic.app.sync

enum class CloudCollectAction { INGEST, RESTORE, SKIP_INGEST }

object CloudCollectLifecycle {
    const val PURGE_AFTER_MS: Long = 30L * 24L * 60L * 60L * 1000L

    fun collectAction(hasActive: Boolean, hasTrash: Boolean): CloudCollectAction {
        if (hasTrash) return CloudCollectAction.RESTORE
        if (hasActive) return CloudCollectAction.SKIP_INGEST
        return CloudCollectAction.INGEST
    }

    fun catchUpAction(
        bookmarked: Boolean,
        hasActive: Boolean,
        hasTrash: Boolean,
    ): CloudCollectAction {
        if (!bookmarked) return CloudCollectAction.SKIP_INGEST
        return collectAction(hasActive, hasTrash)
    }

    fun shouldPurge(trashedAtEpochMs: Long, nowEpochMs: Long): Boolean =
        nowEpochMs - trashedAtEpochMs >= PURGE_AFTER_MS

    fun remainingPurgeDays(trashedAtEpochMs: Long, nowEpochMs: Long): Int {
        val remainingMs = PURGE_AFTER_MS - (nowEpochMs - trashedAtEpochMs)
        if (remainingMs <= 0L) return 0
        val dayMs = 24L * 60L * 60L * 1000L
        return ((remainingMs + dayMs - 1L) / dayMs).toInt()
    }

    fun cardMatches(
        card: CloudCollectionCard,
        acquisitionUrl: String?,
        upstreamAcquisitionUrl: String? = null,
        peekedCloudId: String? = null,
        cloudIdFromUrl: String? = null,
    ): Boolean {
        if (!cloudIdFromUrl.isNullOrBlank() && card.cloudComicId == cloudIdFromUrl.trim()) {
            return true
        }
        val peeked = peekedCloudId?.trim().orEmpty()
        if (peeked.isNotEmpty() && card.cloudComicId == peeked) {
            return true
        }
        val ref = card.acquisitionRef?.trim().orEmpty()
        if (ref.isEmpty()) return false
        if (ref == acquisitionUrl?.trim()) return true
        if (ref == upstreamAcquisitionUrl?.trim()) return true
        return false
    }
}
