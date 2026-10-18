package com.easemic.app.feature.collect

import com.easemic.app.sync.CloudCollectionCard
import com.easemic.app.sync.CloudCollectionKeys
import com.easemic.app.sync.CloudCollectionManifest
import com.easemic.app.sync.CloudComicMeta
import com.easemic.app.sync.CloudSeriesManifest
import com.easemic.app.sync.DamagedLibraryLockException
import com.easemic.app.sync.LibraryLockedException
import com.easemic.app.sync.NewerCloudFormatException
import com.easemic.app.sync.UnreadableCloudLibraryException
import com.easemic.app.sync.looksLikeMissingObject
import kotlinx.coroutines.CancellationException

/**
 * The slice of [RoomCloudMirrorStore] the hydration engine needs.
 * [RoomCloudMirrorStore] implements this directly in production (its method
 * names already match); unit tests drive the engine against a fake so it
 * never touches Room/network (loyal-pipelines design §2).
 */
interface CloudHydrationMirror {
    suspend fun state(bucketFingerprint: String): CloudMirrorHydrationState?

    suspend fun upsertChunkCards(
        bucketFingerprint: String,
        chunkIndex: Int,
        cards: List<CloudCollectionCard>,
        generation: Int = 0,
    )

    suspend fun upsertDetail(bucketFingerprint: String, meta: CloudComicMeta)

    suspend fun putState(
        bucketFingerprint: String,
        totalCards: Int,
        lastCompletedChunk: Int,
        hydrationComplete: Boolean,
        generation: Int,
    )

    suspend fun activeCards(bucketFingerprint: String): List<CloudCollectionCard>

    suspend fun card(bucketFingerprint: String, cloudComicId: String): CloudCollectionCard?

    /** Tier-2 cursor: next card still awaiting `meta.json` detail, or null once none remain. */
    suspend fun nextUnhydratedCard(bucketFingerprint: String): CloudCollectionCard?

    /** Skip path: marks a card hydrated without a detail row (confirmed-missing `meta.json`). */
    suspend fun markDetailHydrated(bucketFingerprint: String, cloudComicId: String)

    /** True when a SERIES card needs its summary fetched for this mirror row. */
    suspend fun seriesNeedsHydration(bucketFingerprint: String, card: CloudCollectionCard): Boolean = false

    /** Atomically replaces a series card's summary chapter rows after a successful decode. */
    suspend fun replaceSeriesChapters(
        bucketFingerprint: String,
        card: CloudCollectionCard,
        manifest: CloudSeriesManifest,
    ) = Unit

    /** Confirmed missing series summary: clear rows but leave the generation retryable. */
    suspend fun markSeriesHydrationMissing(bucketFingerprint: String, card: CloudCollectionCard) = Unit

    /**
     * Ghost-card prune: deletes cards (and their orphaned detail rows) not
     * stamped with [keepGeneration]. Called once a generation-bump re-walk
     * finishes its last chunk — every surviving card has been re-stamped with
     * the new generation by then, so anything still on an older generation is
     * confirmed gone upstream (e.g. discarded on another device) rather than
     * merely not-yet-visited.
     */
    suspend fun pruneStaleCards(bucketFingerprint: String, keepGeneration: Int)
}

/** Outcome of one [CloudHydrationEngine.hydrateStep] call. */
sealed interface HydrationOutcome {
    /** [hydratedChunks]/[totalChunks] describe tier-1 progress; [done] is true once both tiers finish. */
    data class Progress(val hydratedChunks: Int, val totalChunks: Int, val done: Boolean) : HydrationOutcome

    /** No bound bucket — nothing to hydrate. */
    data object Idle : HydrationOutcome

    /** Locked/unreadable/newer-format library — honest, non-corrupting stop; retry after unlock or update. */
    data class Blocked(val message: String) : HydrationOutcome

    /** Transient error (network, etc.) — caller retries; existing mirror state is untouched. */
    data class Failed(val message: String) : HydrationOutcome
}

/**
 * Streaming, resumable cloud→local mirror import (loyal-pipelines design §2).
 * Pure engine over injected suspend lambdas + [CloudHydrationMirror] — no
 * Room/network types leak in, so it is fully unit-testable. A pump worker
 * (a later task) calls [hydrateStep] repeatedly, the same way the download
 * loop is driven by its own `tick()`
 * ([com.easemic.app.feature.download.DownloadWorkerLoop.tick]):
 * each call does one bounded, interruptible unit of work and checkpoints
 * before returning, so process death between calls loses at most that one
 * unit.
 *
 * Tier 1 walks the chunked collection index one chunk per step — upserting a
 * chunk's cards into the mirror before advancing the checkpoint is what makes
 * the shelf fill in progressively rather than appearing all-or-nothing. Tier
 * 2 trickles one gallery comic's `meta.json` detail per step once tier 1 is
 * done. SERIES cards hydrate their complete chapter summary during tier 1 and
 * never fetch per-chapter detail here. A manifest generation bump (cards
 * added/removed upstream) re-walks tier 1 from chunk 0 rather than attempting
 * a diff — simplest correct behavior per spec.
 */
class CloudHydrationEngine(
    private val fingerprint: () -> String?,
    private val downloadManifest: suspend () -> CloudCollectionManifest,
    private val listChunk: suspend (chunkIndex: Int) -> List<CloudCollectionCard>,
    private val downloadMeta: suspend (cloudComicId: String) -> CloudComicMeta,
    private val downloadSeriesManifest: suspend (seriesMetaObjectKey: String) -> CloudSeriesManifest = {
        error("series manifest downloader is not configured")
    },
    private val mirror: CloudHydrationMirror,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    /**
     * A22 §3: called with [clockMs] after every manifest GET that actually
     * returns a manifest (generation-equal included) — never on a failed
     * fetch or an unbound (`fingerprint() == null`) step. Lets
     * `AppGraph`/`HydrationRecheckStore` stamp the foreground-recheck clock
     * without the engine knowing about prefs.
     */
    private val onManifestChecked: (Long) -> Unit = {},
    /**
     * A24-1: invoked immediately AFTER a [CloudHydrationMirror.pruneStaleCards]
     * call actually runs (the last-chunk prune in [tier1Step], or the
     * zero-chunk "emptied upstream" prune in [hydrateStep]) — never on a
     * generation-equal step (prune does not run at all then) or a failed
     * manifest/chunk fetch. Lets `AppGraph` reconcile the durable sync ledger
     * ([com.easemic.app.feature.collect.CloudSyncLedgerStore.reconcileWithMirror])
     * against the SAME [fp]/[keepGeneration] the mirror was just pruned to,
     * so a ledger row whose card just vanished does not survive to skip that
     * comic's re-upload forever (owner bug sample: job 4169293).
     */
    private val onPruned: suspend (fp: String, keepGeneration: Int) -> Unit = { _, _ -> },
    /**
     * A34: invoked immediately AFTER [tier2Step] persists the state row that
     * transitions a walk to `hydrationComplete = true` — never on a later
     * generation-equal step that short-circuits in [hydrateStep] (returning
     * `Progress(done = true)` for a state that was ALREADY complete before
     * this step ran; that path never reaches [tier2Step] at all). Lets
     * `AppGraph` backfill/reconcile the durable sync ledger
     * ([com.easemic.app.feature.collect.CloudSyncLedgerStore])
     * against the mirror right when a full re-hydration finishes — e.g. after
     * a disconnect wiped the ledger and a rebind to the same bucket re-walks
     * the mirror from scratch — instead of leaving it empty until the next
     * cold start.
     */
    private val onHydrationComplete: suspend (fp: String, generation: Int) -> Unit = { _, _ -> },
) {
    suspend fun hydrateStep(): HydrationOutcome {
        val fp = fingerprint() ?: return HydrationOutcome.Idle

        val manifest = try {
            downloadManifest()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LibraryLockedException) {
            return HydrationOutcome.Blocked(LibraryLockedException.MESSAGE)
        } catch (_: NewerCloudFormatException) {
            return HydrationOutcome.Blocked(NewerCloudFormatException.MESSAGE)
        } catch (_: UnreadableCloudLibraryException) {
            return HydrationOutcome.Blocked(UnreadableCloudLibraryException.MESSAGE)
        } catch (_: DamagedLibraryLockException) {
            return HydrationOutcome.Blocked(DamagedLibraryLockException.MESSAGE)
        } catch (error: Exception) {
            return HydrationOutcome.Failed(error.message ?: "CLOUD_HYDRATION_MANIFEST_FAILED")
        }
        try {
            onManifestChecked(clockMs())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The recheck-clock stamp is best-effort bookkeeping; a prefs
            // write failure here must not fail an otherwise-successful
            // hydration step.
        }

        val existingState = mirror.state(fp)
        val totalChunks = totalChunksFor(manifest)
        val state = if (existingState == null || existingState.generation != manifest.generation) {
            // Fresh bucket, or the collection changed upstream since the last
            // hydration pass: re-walk tier 1 from chunk 0 rather than diffing.
            val fresh = CloudMirrorHydrationState(
                totalCards = manifest.comicCount,
                lastCompletedChunk = -1,
                hydrationComplete = false,
                generation = manifest.generation,
            )
            // Publish the new generation to the mirror's state row right now,
            // at walk start — not just held in this local var — so a live
            // upsert (RoomCloudMirrorStore.upsertLiveCard) landing mid-walk
            // (e.g. a collect finishing while chunk 0 is still in flight)
            // reads *this* generation instead of the stale pre-walk one, and
            // so survives this same walk's end-of-chunk prune rather than
            // flickering out until the next walk self-heals it.
            mirror.putState(
                fp,
                totalCards = fresh.totalCards,
                lastCompletedChunk = fresh.lastCompletedChunk,
                hydrationComplete = fresh.hydrationComplete,
                generation = fresh.generation,
            )
            fresh
        } else {
            existingState
        }

        if (state.hydrationComplete) {
            return HydrationOutcome.Progress(totalChunks, totalChunks, done = true)
        }

        val nextChunkIndex = state.lastCompletedChunk + 1
        if (nextChunkIndex < totalChunks) {
            return tier1Step(fp, manifest, nextChunkIndex, totalChunks)
        }

        if (totalChunks == 0 && existingState?.generation != manifest.generation) {
            // No chunks means tier1Step (whose last-chunk branch normally
            // prunes) never runs at all — including the "upstream collection
            // was emptied entirely" case (comicCount now 0). Without this,
            // a bucket emptied on another device keeps every ghost card
            // forever, since tier2Step below only marks hydration complete.
            mirror.pruneStaleCards(fp, keepGeneration = manifest.generation)
            try {
                onPruned(fp, manifest.generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // best-effort: a ledger reconcile / handoff-repair failure
                // must not fail an otherwise-successful hydration step or
                // skip its checkpoint
            }
        }

        return tier2Step(fp, manifest, state, totalChunks)
    }

    /** One index chunk fetched, upserted into the mirror, and checkpointed. */
    private suspend fun tier1Step(
        fp: String,
        manifest: CloudCollectionManifest,
        chunkIndex: Int,
        totalChunks: Int,
    ): HydrationOutcome {
        val cards = try {
            listChunk(chunkIndex)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LibraryLockedException) {
            return HydrationOutcome.Blocked(LibraryLockedException.MESSAGE)
        } catch (_: NewerCloudFormatException) {
            return HydrationOutcome.Blocked(NewerCloudFormatException.MESSAGE)
        } catch (_: UnreadableCloudLibraryException) {
            return HydrationOutcome.Blocked(UnreadableCloudLibraryException.MESSAGE)
        } catch (_: DamagedLibraryLockException) {
            return HydrationOutcome.Blocked(DamagedLibraryLockException.MESSAGE)
        } catch (error: Exception) {
            if (looksLikeMissingObject(error)) {
                // A24: the manifest's chunk count is authoritative, so a chunk
                // object confirmed gone is a HOLE to skip — not the end of the
                // index and not a failure. `listIndexChunk` downloads without
                // the `downloadOrNull` tolerance `listAllIndexCards` uses, so
                // without this the two readers of the same index disagreed: a
                // single 404 mid-walk failed the step forever, so the walk
                // never advanced past the hole and the mirror shelf silently
                // showed fewer comics than the manifest counted.
                emptyList()
            } else {
                return HydrationOutcome.Failed(error.message ?: "CLOUD_HYDRATION_CHUNK_FAILED")
            }
        }

        val seriesNeeds = cards
            .filter { it.layoutKind == com.easemic.app.sync.CloudCardLayout.SERIES }
            .associate { it.cloudComicId to mirror.seriesNeedsHydration(fp, it) }
        mirror.upsertChunkCards(fp, chunkIndex, cards, manifest.generation)
        for (card in cards) {
            if (card.layoutKind != com.easemic.app.sync.CloudCardLayout.SERIES ||
                seriesNeeds[card.cloudComicId] != true
            ) continue
            val key = card.seriesMetaObjectKey
            if (key != CloudCollectionKeys.comicPrefix(card.cloudComicId) + "series.json") {
                mirror.markSeriesHydrationMissing(fp, card)
                continue
            }
            try {
                // Decode completes before the mirror transaction, so a newer
                // summary cannot leave partially-written chapter rows.
                val series = downloadSeriesManifest(key)
                mirror.replaceSeriesChapters(fp, card, series)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: LibraryLockedException) {
                return HydrationOutcome.Blocked(LibraryLockedException.MESSAGE)
            } catch (_: NewerCloudFormatException) {
                return HydrationOutcome.Blocked(NewerCloudFormatException.MESSAGE)
            } catch (_: UnreadableCloudLibraryException) {
                return HydrationOutcome.Blocked(UnreadableCloudLibraryException.MESSAGE)
            } catch (_: DamagedLibraryLockException) {
                return HydrationOutcome.Blocked(DamagedLibraryLockException.MESSAGE)
            } catch (error: Exception) {
                if (looksLikeMissingObject(error)) {
                    mirror.markSeriesHydrationMissing(fp, card)
                } else {
                    return HydrationOutcome.Failed(error.message ?: "CLOUD_HYDRATION_SERIES_FAILED")
                }
            }
        }
        if (chunkIndex == totalChunks - 1) {
            // Last chunk of this walk: every surviving comic has now been re-upserted
            // under manifest.generation. Any card still stamped with an older
            // generation was not seen during this walk — it no longer exists
            // upstream, so evict it (and its orphaned detail row) now rather than
            // leaving a ghost card on the shelf.
            mirror.pruneStaleCards(fp, keepGeneration = manifest.generation)
            try {
                onPruned(fp, manifest.generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // best-effort: a ledger reconcile / handoff-repair failure
                // must not fail an otherwise-successful hydration step or
                // skip its checkpoint
            }
        }
        mirror.putState(
            fp,
            totalCards = manifest.comicCount,
            lastCompletedChunk = chunkIndex,
            hydrationComplete = false,
            generation = manifest.generation,
        )
        return HydrationOutcome.Progress(chunkIndex + 1, totalChunks, done = false)
    }

    /** One comic's detail fetched (or skipped) and checkpointed; completes hydration once none remain. */
    private suspend fun tier2Step(
        fp: String,
        manifest: CloudCollectionManifest,
        state: CloudMirrorHydrationState,
        totalChunks: Int,
    ): HydrationOutcome {
        val nextCard = mirror.nextUnhydratedCard(fp)
        if (nextCard == null) {
            mirror.putState(
                fp,
                totalCards = manifest.comicCount,
                lastCompletedChunk = state.lastCompletedChunk,
                hydrationComplete = true,
                generation = manifest.generation,
            )
            try {
                onHydrationComplete(fp, manifest.generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // best-effort: a ledger backfill/reconcile failure must not
                // fail an otherwise-successful hydration step or skip its
                // checkpoint
            }
            return HydrationOutcome.Progress(totalChunks, totalChunks, done = true)
        }

        try {
            val meta = downloadMeta(nextCard.cloudComicId)
            mirror.upsertDetail(fp, meta)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LibraryLockedException) {
            return HydrationOutcome.Blocked(LibraryLockedException.MESSAGE)
        } catch (_: NewerCloudFormatException) {
            return HydrationOutcome.Blocked(NewerCloudFormatException.MESSAGE)
        } catch (_: UnreadableCloudLibraryException) {
            return HydrationOutcome.Blocked(UnreadableCloudLibraryException.MESSAGE)
        } catch (_: DamagedLibraryLockException) {
            return HydrationOutcome.Blocked(DamagedLibraryLockException.MESSAGE)
        } catch (error: Exception) {
            if (looksLikeMissingObject(error)) {
                // meta.json confirmed gone: skip this comic's detail rather than
                // retrying it on every future step and stalling the trickle.
                mirror.markDetailHydrated(fp, nextCard.cloudComicId)
            } else {
                return HydrationOutcome.Failed(error.message ?: "CLOUD_HYDRATION_DETAIL_FAILED")
            }
        }
        return HydrationOutcome.Progress(totalChunks, totalChunks, done = false)
    }

    private fun totalChunksFor(manifest: CloudCollectionManifest): Int {
        if (manifest.comicCount <= 0) return 0
        return (manifest.comicCount + manifest.chunkSize - 1) / manifest.chunkSize
    }
}
