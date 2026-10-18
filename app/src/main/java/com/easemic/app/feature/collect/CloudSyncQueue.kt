package com.easemic.app.feature.collect

import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.GalleryIdentityV2
import com.easemic.app.feature.download.TransferRateMeter
import com.easemic.app.feature.download.formatTransferRate
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CloudSyncStage {
    WAITING,
    FETCHING,
    UPLOADING,
    /** Terminal-until-retry: stay visible after ingest failure (do not silently drop). */
    FAILED,
}

data class CloudSyncQueueItem(
    val id: String,
    val title: String,
    val coverUrl: String?,
    val stage: CloudSyncStage,
    val readyPages: Int,
    val expectedPages: Int?,
    val bytesPerSecond: Long? = null,
    val errorMessage: String? = null,
    val providerId: String? = null,
    /** Canonical item id paired with [providerId] for opaque OPDS locators. */
    val canonicalItemId: String? = null,
    /**
     * The Collect tap has been accepted, but the cloud index preflight has not
     * yet decided whether this is a restore, a no-op, or a new ingest. Such a
     * row is visible in Activity and durable across a process pause, but is
     * deliberately not runnable by the cloud worker.
     */
    val preflightPending: Boolean = false,
    /**
     * #C46 D1: every page is up and the pump is writing the tail (thumbs,
     * manifest, meta, index). The row reads "Uploading \u00b7 finishing" with an
     * indeterminate bar instead of sitting at N/N. Cleared by any
     * non-finishing progress, by a failure, by an interruption, and never
     * replayed from disk (a restart has no pump driving a tail).
     */
    val finishing: Boolean = false,
    /**
     * Owner bug A3/C5 fix (T3c): count of consecutive [markFailed] calls
     * since this row last ran clean, so [nextRunnableCloudSyncRow] /
     * [shouldAutoResumeFailedCloudSync] can bound how many times a FAILED row
     * is auto-resumed instead of retrying it forever. 0 (default) also covers
     * every legacy persisted row from before this field existed — treated as
     * immediately eligible for one auto-resume, matching pre-existing
     * behavior for callers that never look at this field.
     */
    val failedAttempts: Int = 0,
    /** Wall-clock of the most recent [markFailed] call; backoff window anchor. */
    val failedAtEpochMs: Long? = null,
    /**
     * C35-c: wall-clock at which an account-wide rate-limit window
     * ([CloudRateLimitPause]) expires, or null when this row is not sitting
     * one out. C37-f already parked a 429'd row WAITING, but mutated nothing
     * else — so the row froze at its last count for the whole window with
     * nothing on screen saying it was waiting on a clock rather than stuck.
     * This is the only WAITING state with a known end, and the row copy says
     * so. Set by [markPausedUntil]; cleared by every mutation that means the
     * row moved again ([onProgress], [markFailed], [markFailedTerminal],
     * [markInterrupted]).
     */
    val pausedUntilMs: Long? = null,
    /** Stable provider chapter identity; legacy gallery rows use `legacy:0`. */
    val chapterIdentity: String = GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID,
)

/** Missing identity fields may be filled, but known fields never change accounts/items. */
internal fun CloudSyncQueueItem.acceptsIdentityBackfill(namespace: String, canonical: String): Boolean {
    val provider = providerId
    val providerMatches = provider.isNullOrBlank() ||
        provider == namespace ||
        // #C20 audit LOW-1: a bare OPDS config id and its `opds:` namespace name
        // the same account. Only a config id may be widened this way — every
        // namespace this app mints itself is excluded, or `cloud_collection`
        // would be re-accounted to a (fictional) `opds:cloud_collection`.
        // Re-review r1 LOW-1: the list used to stop at the two source ids and
        // omitted CLOUD_COLLECTION and the r2source namespaces.
        (!provider.startsWith(GalleryIdentityV1.OPDS_PREFIX) &&
            !provider.startsWith(GalleryIdentityV1.R2_SOURCE_PREFIX) &&
            provider != GalleryIdentityV1.EHENTAI && provider != GalleryIdentityV1.NHENTAI &&
            provider != GalleryIdentityV1.CLOUD_COLLECTION &&
            GalleryIdentityV1.opdsNamespace(provider) == namespace)
    return providerMatches && (canonicalItemId.isNullOrBlank() || canonicalItemId == canonical)
}

object CloudSyncQueueEmptyCopy {
    val TITLE: String get() = AppStrings.noCloudSyncQueue
    val SUBTITLE: String get() = AppStrings.cloudSyncQueueSubtitle
}

fun cloudSyncSecondaryLine(item: CloudSyncQueueItem): String {
    if (item.preflightPending) return AppStrings.checkingCloud
    if (item.stage == CloudSyncStage.FAILED) {
        val detail = item.errorMessage?.takeIf { it.isNotBlank() } ?: "sync failed"
        val pages = item.expectedPages?.let { expected ->
            " · ${item.readyPages} / ${AppStrings.pageCount(expected)}"
        }.orEmpty()
        return tr("Failed · $detail$pages", "失败 · $detail$pages", "失敗 · $detail$pages", "失敗 · $detail$pages")
    }
    val stateLabel = when (item.stage) {
        CloudSyncStage.WAITING -> AppStrings.waiting
        CloudSyncStage.FETCHING -> AppStrings.fetching
        CloudSyncStage.UPLOADING -> AppStrings.uploading
        CloudSyncStage.FAILED -> AppStrings.failed
    }
    val pages = item.expectedPages?.let { expected ->
        AppStrings.pageCount(expected).let { "${item.readyPages} / $it" }
    } ?: AppStrings.pagesUnknown
    val speed = item.bytesPerSecond?.let { " · ${formatTransferRate(it)}" }.orEmpty()
    return "$stateLabel · $pages$speed"
}

class CloudSyncQueue {
    private data class QueueKey(val id: String, val chapterIdentity: String)

    private val lock = Any()
    private val items = linkedMapOf<QueueKey, CloudSyncQueueItem>()
    private val meter = TransferRateMeter()
    private val _rows = MutableStateFlow<List<CloudSyncQueueItem>>(emptyList())
    val rows: StateFlow<List<CloudSyncQueueItem>> = _rows.asStateFlow()
    private var store: CloudSyncQueueStore? = null

    /**
     * Wires a durable prefs-backed store and merges its rows into the
     * queue: any persisted row whose (id, chapter) identity is NOT already
     * held in memory is replayed. An in-memory row always wins over its
     * persisted twin — it
     * may be fresher than what was last durably saved (e.g. a caller
     * upserted before the store was attached) — so existing in-memory rows
     * are never overwritten. Every mutation from here on writes through to
     * [store].
     */
    fun attach(store: CloudSyncQueueStore) {
        synchronized(lock) {
            this.store = store
            store.load().forEach { row ->
                val key = keyOf(row)
                if (!items.containsKey(key)) {
                    items[key] = row
                }
            }
            publishLocked()
        }
    }

    fun snapshot(): List<CloudSyncQueueItem> = synchronized(lock) { items.values.toList() }

    /** Resolves an old id-only lookup when it is unambiguous (or to legacy:0). */
    fun get(id: String): CloudSyncQueueItem? = synchronized(lock) {
        findKeyLocked(id, chapterIdentity = null)?.let(items::get)
    }

    /** Chapter-aware lookup used by serialized-series callers. */
    fun get(id: String, chapterIdentity: String): CloudSyncQueueItem? = synchronized(lock) {
        items[keyOf(id, chapterIdentity)]
    }

    fun contains(id: String): Boolean = synchronized(lock) {
        findKeyLocked(id, chapterIdentity = null) != null
    }

    fun contains(id: String, chapterIdentity: String): Boolean = synchronized(lock) {
        items.containsKey(keyOf(id, chapterIdentity))
    }

    fun upsert(item: CloudSyncQueueItem) {
        synchronized(lock) {
            items[keyOf(item)] = item
            publishLocked()
        }
    }

    /** Atomically accept a new tap without resetting an existing worker's row. */
    fun beginPreflight(item: CloudSyncQueueItem): CloudSyncQueueItem? = synchronized(lock) {
        val key = keyOf(item)
        val existing = items[key]
        // #C20 audit MEDIUM-3: a parked FAILED row is nobody's work — the pump
        // has given up on it (or it is B1's preflight failure). A new tap is a
        // new attempt and replaces it; anything live keeps its row.
        if (existing != null && existing.stage != CloudSyncStage.FAILED) return@synchronized null
        // Re-review r2 MEDIUM-4: the replaced FAILED row's reason rides along on
        // the placeholder so a bail that changes nothing (the ingest-registry
        // BUSY exit) can keep it via failPreflightKeepingPriorReason. It is
        // invisible while pending and cleared by the first worker write
        // (onProgress sets errorMessage = null) as before.
        val pending = item.copy(stage = CloudSyncStage.WAITING, preflightPending = true, errorMessage = existing?.errorMessage)
        items[key] = pending
        if (existing != null) meter.clear(meterKey(key))
        publishLocked()
        pending
    }

    /**
     * Migration may race a worker's progress, failure, or completion. Apply
     * only identity fields to current incomplete rows under the queue lock;
     * never replay a stale full-row snapshot or resurrect a removed row.
     */
    fun backfillIdentities(repairedRows: List<CloudSyncQueueItem>) {
        synchronized(lock) {
            var changed = false
            repairedRows.forEach { repaired ->
                if (repaired.providerId.isNullOrBlank() || repaired.canonicalItemId.isNullOrBlank()) {
                    return@forEach
                }
                val currentKey = keyOf(repaired)
                val current = items[currentKey] ?: return@forEach
                // Preflight owns its exact placeholder until handoff. Replacing
                // it here would detach the owning coroutine's cleanup token.
                if (current.preflightPending) return@forEach
                if (!current.providerId.isNullOrBlank() && !current.canonicalItemId.isNullOrBlank()) {
                    return@forEach
                }
                if (!current.acceptsIdentityBackfill(repaired.providerId, repaired.canonicalItemId)) {
                    return@forEach
                }
                items[currentKey] = current.copy(
                    providerId = repaired.providerId,
                    canonicalItemId = repaired.canonicalItemId,
                )
                changed = true
            }
            if (changed) publishLocked()
        }
    }

    fun onProgress(
        id: String,
        stage: CloudSyncStage,
        readyPages: Int,
        expectedPages: Int?,
        deltaBytes: Int,
        nowMs: Long,
        /** #C46 D1: the page phase is done and the tail is running. */
        finishing: Boolean = false,
        chapterIdentity: String? = null,
    ) {
        synchronized(lock) {
            val key = findKeyLocked(id, chapterIdentity) ?: return
            val current = items[key] ?: return
            val meterId = meterKey(key)
            if (deltaBytes > 0) {
                meter.record(meterId, deltaBytes, nowMs)
            }
            val speed = when (stage) {
                CloudSyncStage.WAITING, CloudSyncStage.FAILED -> null
                else -> meter.bytesPerSecond(meterId, nowMs)
            }
            // #C46 audit LOW-1: completion-order Page events come from ~6 PUT
            // lanes and can land out of order (done=7 before done=6) because
            // the lane's increment and this call are not one atomic step.
            // Within one upload phase the counter never moves backwards — the
            // row tells the truth, and 7 then 6 is a lie the user can see. A
            // stage change (FETCHING/WAITING -> UPLOADING, a resumed pickup)
            // still takes the caller's count, so a checkpoint restart resets.
            // Decided here under the lock, not by the caller: a get-then-set
            // outside it would race the same way the lanes do.
            val monotoneReady =
                if (stage == CloudSyncStage.UPLOADING && current.stage == CloudSyncStage.UPLOADING) {
                    maxOf(current.readyPages, readyPages)
                } else {
                    readyPages
                }
            items[key] = current.copy(
                stage = stage,
                readyPages = monotoneReady,
                expectedPages = expectedPages ?: current.expectedPages,
                bytesPerSecond = speed,
                errorMessage = null,
                finishing = finishing,
                // Re-review r1 HIGH-1: a worker write proves this is not a
                // placeholder. `beginPreflight` may replace a FAILED row the
                // pump has ALREADY claimed (a FAILED row inside its auto-resume
                // window is claimable), and carrying the bit forward left a
                // hybrid nothing could drain: "Checking cloud" forever, filtered
                // out of every pump selector, Retry a no-op, and deleted by the
                // next cold start's clearPreflightPendingRows.
                preflightPending = false,
                // C35-c: the row moved, so whatever window it was sitting out
                // is over (or was overtaken by a resume). A stale stamp would
                // keep a countdown on screen over live progress.
                pausedUntilMs = null,
            )
            publishLocked()
        }
    }

    /**
     * Keep the row visible after a non-success finish so catch-up / UI do not
     * silently lose mid-job failures. Also bumps [CloudSyncQueueItem.failedAttempts]
     * and stamps [CloudSyncQueueItem.failedAtEpochMs] (T3c) so
     * [nextRunnableCloudSyncRow] can bound auto-resume instead of retrying a
     * persistently-broken row forever. [onProgress] deliberately does NOT
     * reset [CloudSyncQueueItem.failedAttempts] on the FAILED→WAITING pickup
     * transition — the count must survive across attempts for the cap to
     * ever trigger; it only ever resets by the row being freshly [upsert]ed
     * (a brand new collect) or [remove]d (synced).
     */
    fun markFailed(
        id: String,
        message: String,
        readyPages: Int? = null,
        expectedPages: Int? = null,
        nowMs: Long = System.currentTimeMillis(),
        chapterIdentity: String? = null,
    ) {
        synchronized(lock) {
            val key = findKeyLocked(id, chapterIdentity) ?: return
            val current = items[key] ?: return
            items[key] = current.copy(
                stage = CloudSyncStage.FAILED,
                readyPages = readyPages ?: current.readyPages,
                expectedPages = expectedPages ?: current.expectedPages,
                bytesPerSecond = null,
                errorMessage = message,
                finishing = false,
                // Re-review r1 HIGH-1: a worker write is not a placeholder.
                preflightPending = false,
                failedAttempts = current.failedAttempts + 1,
                failedAtEpochMs = nowMs,
                pausedUntilMs = null,
            )
            meter.clear(meterKey(key))
            publishLocked()
        }
    }

    /**
     * C37-c review (F2): a failure nothing can retry — today only a revoked
     * OAuth grant ([com.easemic.app.feature.bridge.ByoCloudIoCopy.RECONNECT]).
     * Parks the row exactly where a fully-spent retry budget would leave it
     * (so [nextRunnableCloudSyncRow] never picks it up again and Activity
     * renders it under "Needs attention"), but in ONE [publishLocked] — the
     * call site used to `repeat(MAX_ATTEMPTS) { markFailed(...) }`, and every
     * one of those five calls durably re-serialised the whole queue because
     * the publish fingerprint includes `failedAttempts`.
     *
     * [maxOf] rather than a plain assignment: a row that already burned MORE
     * than the cap through ordinary retries must not have its count lowered.
     * Only [reviveFailedWithMessage] (the reconnect hook) ever clears this.
     */
    fun markFailedTerminal(
        id: String,
        message: String,
        nowMs: Long = System.currentTimeMillis(),
        chapterIdentity: String? = null,
    ) {
        synchronized(lock) {
            val key = findKeyLocked(id, chapterIdentity) ?: return
            val current = items[key] ?: return
            items[key] = current.copy(
                stage = CloudSyncStage.FAILED,
                bytesPerSecond = null,
                errorMessage = message,
                finishing = false,
                // Re-review r1 HIGH-1: a worker write is not a placeholder.
                preflightPending = false,
                failedAttempts = maxOf(current.failedAttempts, CloudSyncIngestAutoRetry.MAX_ATTEMPTS),
                failedAtEpochMs = nowMs,
                pausedUntilMs = null,
            )
            meter.clear(meterKey(key))
            publishLocked()
        }
    }

    /**
     * A27 (owner bug backlog, ledger 2026-09-05): brings every row that was
     * terminal-[markFailed] with exactly [message] back to WAITING, clearing
     * [CloudSyncQueueItem.failedAttempts], [CloudSyncQueueItem.failedAtEpochMs]
     * and [CloudSyncQueueItem.errorMessage] -- a fresh start, not a resumed
     * backoff. Used by AppGraph.onBucketBound to revive rows that were
     * parked/failed with ByoCloudIoCopy.BIND before the user connected cloud
     * storage. Rows in any other stage, or FAILED with a different
     * [CloudSyncQueueItem.errorMessage], are left untouched. Returns the
     * number of rows revived.
     */
    fun reviveFailedWithMessage(message: String, nowMs: Long): Int {
        synchronized(lock) {
            var revived = 0
            for ((id, current) in items) {
                if (current.stage == CloudSyncStage.FAILED && current.errorMessage == message) {
                    items[id] = current.copy(
                        stage = CloudSyncStage.WAITING,
                        failedAttempts = 0,
                        failedAtEpochMs = null,
                        errorMessage = null,
                    )
                    revived += 1
                }
            }
            if (revived > 0) {
                publishLocked()
            }
            return revived
        }
    }

    /**
     * C35-a: this row's ingest was INTERRUPTED — the worker was stopped by the
     * system, or replaced by a user kick that preempted a backed-off pump
     * ([com.easemic.app.work.CloudIngestWorker.kickPolicy]'s
     * `REPLACE`). Park it back at WAITING so the next pump resumes it, keeping
     * [CloudSyncQueueItem.readyPages]/[CloudSyncQueueItem.expectedPages] (the
     * durable checkpoint is what makes a resume cheap — dropping to 0 would
     * re-upload the whole comic) and keeping
     * [CloudSyncQueueItem.failedAttempts] (an interruption must not be a way
     * to refresh a spent retry budget). An interruption is not a failure: no
     * attempt is charged and [CloudSyncQueueItem.errorMessage] is cleared.
     *
     * A missing id is a deliberate no-op. The user's own Cancel does not come
     * through here — it calls [remove] — and a row the user removed must stay
     * removed even when the interrupted worker reports back afterwards.
     */
    fun markInterrupted(id: String, chapterIdentity: String? = null) {
        synchronized(lock) {
            val key = findKeyLocked(id, chapterIdentity) ?: return
            val current = items[key] ?: return
            items[key] = current.copy(
                stage = CloudSyncStage.WAITING,
                bytesPerSecond = null,
                errorMessage = null,
                finishing = false,
                pausedUntilMs = null,
            )
            meter.clear(meterKey(key))
            publishLocked()
        }
    }

    /**
     * #C46 D2: a row is UPLOADING/FETCHING only while a pump is actually
     * driving it. Called once at process start, before any pump kick: every
     * in-flight row becomes WAITING, keeping its counts (that many pages
     * really are up) and dropping [CloudSyncQueueItem.finishing]. The pump's
     * own progress events flip the row back the moment it is being worked on
     * again. Returns how many rows were parked.
     */
    fun markAllIdle(): Int {
        synchronized(lock) {
            var parked = 0
            items.replaceAll { _, item ->
                when (item.stage) {
                    CloudSyncStage.FETCHING, CloudSyncStage.UPLOADING -> {
                        parked++
                        item.copy(
                            stage = CloudSyncStage.WAITING,
                            bytesPerSecond = null,
                            finishing = false,
                            pausedUntilMs = null,
                        )
                    }
                    CloudSyncStage.WAITING, CloudSyncStage.FAILED -> item
                }
            }
            if (parked > 0) publishLocked()
            return parked
        }
    }

    /**
     * C35-c: this row is sitting out the account-wide 429 window until
     * [untilMs] (see [CloudSyncQueueItem.pausedUntilMs]). Stage is deliberately
     * untouched — [CloudIngestRunner] has already parked the row WAITING
     * through the ordinary progress path, and the stamp is purely what lets
     * the row say "resumes in Ns" instead of freezing at its last count.
     *
     * A missing id is a no-op, same as every other mutator: the user may have
     * cancelled the row while the slot was on its way into the sleep.
     */
    fun markPausedUntil(id: String, untilMs: Long, chapterIdentity: String? = null) {
        synchronized(lock) {
            val key = findKeyLocked(id, chapterIdentity) ?: return
            val current = items[key] ?: return
            items[key] = current.copy(pausedUntilMs = untilMs)
            publishLocked()
        }
    }

    fun remove(id: String, chapterIdentity: String? = null) {
        synchronized(lock) {
            val key = findKeyLocked(id, chapterIdentity) ?: return
            items.remove(key)
            meter.clear(meterKey(key))
            publishLocked()
        }
    }

    /** Removes undecided Collect placeholders during cold-start recovery. */
    fun clearPreflightPendingRows(): Int {
        synchronized(lock) {
            val keys = items.filterValues { it.preflightPending }.keys.toList()
            if (keys.isEmpty()) return 0
            keys.forEach {
                items.remove(it)
                meter.clear(meterKey(it))
            }
            publishLocked()
            return keys.size
        }
    }

    /** An old coroutine cannot remove a newer tap or a worker's replacement row. */
    fun removePreflightPending(expected: CloudSyncQueueItem): Boolean {
        synchronized(lock) {
            val key = keyOf(expected)
            if (!expected.preflightPending || items[key] !== expected) return false
            items.remove(key)
            meter.clear(meterKey(key))
            publishLocked()
            return true
        }
    }

    /**
     * #C22 audit BLOCKING-1: the preflight failed before any upload was
     * attempted. Instead of retiring the placeholder (which left NO row until
     * the next cold start re-collected the bookmark), park it as a terminal
     * attention row: FAILED, no auto-resume (attempts at the cap), Retry and
     * Dismiss available. Only a still-pending placeholder is touched — a
     * worker's replacement row or a newer tap's placeholder is never overwritten.
     */
    fun failPreflight(id: String, message: String, nowMs: Long = System.currentTimeMillis(), chapterIdentity: String? = null): Boolean {
        synchronized(lock) {
            val key = findKeyLocked(id, chapterIdentity) ?: return false
            val current = items[key] ?: return false
            if (!current.preflightPending) return false
            items[key] = current.copy(
                preflightPending = false,
                stage = CloudSyncStage.FAILED,
                bytesPerSecond = null,
                errorMessage = message,
                finishing = false,
                failedAttempts = CloudSyncIngestAutoRetry.MAX_ATTEMPTS,
                failedAtEpochMs = nowMs,
                pausedUntilMs = null,
            )
            meter.clear(meterKey(key))
            publishLocked()
            return true
        }
    }

    /**
     * Re-review r2 MEDIUM-4: the registry bail — the tap did NOTHING, so the
     * row must keep saying what it already said. Parks like [failPreflight]
     * but with `current.errorMessage ?: fallback`.
     */
    fun failPreflightKeepingPriorReason(id: String, fallback: String, nowMs: Long = System.currentTimeMillis(), chapterIdentity: String? = null): Boolean {
        val prior = synchronized(lock) { findKeyLocked(id, chapterIdentity)?.let(items::get)?.takeIf { it.preflightPending }?.errorMessage }
        return failPreflight(id, prior ?: fallback, nowMs, chapterIdentity)
    }

    /** Durable-fingerprint dirty-check bookkeeping (see [publishLocked]). */
    private data class RowFingerprint(
        val id: String,
        val chapterIdentity: String,
        val stage: CloudSyncStage,
        val errorMessage: String?,
        val title: String,
        val coverUrl: String?,
        val providerId: String?,
        val canonicalItemId: String?,
        val preflightPending: Boolean,
        val expectedPages: Int?,
        // #C46 D1: a page-phase -> tail transition must durably re-save, so a
        // relaunch inside the tail is at least parked honestly (the field
        // itself replays as false; what this guarantees is that the row's
        // stage/expected count on disk are current when the tail starts).
        val finishing: Boolean,
        // T3c: unlike readyPages/bytesPerSecond, these matter for the
        // bounded auto-resume decision surviving process death — a repeat
        // markFailed() with an unchanged message must still trigger a
        // durable re-save so the persisted attempt count/backoff anchor
        // stay current.
        val failedAttempts: Int,
        val failedAtEpochMs: Long?,
        // C35-c: a 429 window outlives the process that met it (the ACCOUNT is
        // blocked), so a relaunch inside the window must still render the
        // countdown rather than a frozen count.
        val pausedUntilMs: Long?,
    )
    private var lastSavedFingerprint: List<RowFingerprint>? = null

    private fun publishLocked() {
        val snapshot = items.values.toList()
        _rows.value = snapshot
        val activeStore = store ?: return
        // Per-page onProgress() ticks change readyPages/bytesPerSecond
        // constantly; persisting on every tick is an ANR-at-pause exposure
        // (QueuedWork flushes pending SharedPreferences writes on process
        // pause). Only durably re-save when something that actually matters
        // for resume/coherence changes — readyPages/bytesPerSecond are
        // deliberately excluded, so persisted readyPages can go stale right
        // up to process death; that is acceptable because load() demotes
        // in-flight stages back to WAITING on replay, and the pump
        // recomputes its own checkpoint from there.
        val fingerprint = snapshot.map {
            RowFingerprint(
                id = it.id,
                chapterIdentity = it.chapterIdentity,
                stage = it.stage,
                errorMessage = it.errorMessage,
                title = it.title,
                coverUrl = it.coverUrl,
                providerId = it.providerId,
                canonicalItemId = it.canonicalItemId,
                preflightPending = it.preflightPending,
                expectedPages = it.expectedPages,
                finishing = it.finishing,
                failedAttempts = it.failedAttempts,
                failedAtEpochMs = it.failedAtEpochMs,
                pausedUntilMs = it.pausedUntilMs,
            )
        }
        if (fingerprint == lastSavedFingerprint) return
        activeStore.save(snapshot)
        lastSavedFingerprint = fingerprint
    }

    private fun keyOf(item: CloudSyncQueueItem): QueueKey = keyOf(item.id, item.chapterIdentity)

    private fun keyOf(id: String, chapterIdentity: String): QueueKey =
        QueueKey(id, chapterIdentity.ifBlank { GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID })

    private fun findKeyLocked(id: String, chapterIdentity: String?): QueueKey? {
        if (chapterIdentity != null) return keyOf(id, chapterIdentity).takeIf(items::containsKey)
        val matches = items.keys.filter { it.id == id }
        if (matches.size == 1) return matches.single()
        return matches.firstOrNull { it.chapterIdentity == GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID }
    }

    private fun meterKey(key: QueueKey): String = "${key.id}\u0000${key.chapterIdentity}"
}
