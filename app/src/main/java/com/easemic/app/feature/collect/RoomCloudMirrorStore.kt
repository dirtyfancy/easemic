package com.easemic.app.feature.collect

import com.easemic.app.data.room.CloudMirrorCardEntity
import com.easemic.app.data.room.CloudMirrorChapterEntity
import com.easemic.app.data.room.CloudMirrorDao
import com.easemic.app.data.room.CloudMirrorDetailEntity
import com.easemic.app.data.room.CloudMirrorStateEntity
import com.easemic.app.sync.CloudCollectionCard
import com.easemic.app.sync.DiagLog
import com.easemic.app.sync.CloudComicMeta
import com.easemic.app.sync.CloudCardLayout
import com.easemic.app.sync.CloudIndexGenerations
import com.easemic.app.sync.CloudSeriesManifest
import com.easemic.app.feature.source.SeriesDetailSnapshot
import com.easemic.app.feature.source.decodeSeriesSnapshot
import com.easemic.app.feature.source.encodeSeriesSnapshot
import com.easemic.app.feature.source.preferNewerSeriesSnapshot
import com.easemic.app.feature.download.SeriesIdentityV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private const val TAG = "CloudMirrorStore"

/** Match a SERIES mirror card by its persisted provider identity. */
fun CloudCollectionCard.matchesSeriesIdentity(identity: SeriesIdentityV2): Boolean =
    layoutKind == CloudCardLayout.SERIES &&
        providerId == identity.providerNamespace &&
        providerSeriesId == identity.canonicalSeriesId &&
        trashedAtEpochMs == null

/** Hydration checkpoint for one bucket (see [CloudMirrorStateEntity]). */
data class CloudMirrorHydrationState(
    val totalCards: Int,
    val lastCompletedChunk: Int,
    val hydrationComplete: Boolean,
    val generation: Int,
)

/** S4 Task 1 (owner bug C4): where a card's cover image should be read from. */
sealed class CoverSource {
    data class Local(val path: String) : CoverSource()
    object Remote : CoverSource()
}

/**
 * Pure decision function (S4 Task 1, owner bug C4): a durable on-device
 * cover is used only when [coverPath] is set AND the file it names still
 * exists (`fileExists` is injected so this stays a pure, directly unit-
 * testable function with zero File I/O of its own) — a path can go stale if
 * the app's private storage was cleared out from under an otherwise-valid
 * Room row. Falls back to [CoverSource.Remote] (an R2 `downloadThumb` read)
 * in every other case.
 *
 * S4 Task 1 review (cheap extra): the injected [fileExists] predicate MUST
 * also reject a zero-length file, not just check existence — a caller that
 * only checks `File(path).exists()` would wrongly accept a truncated/empty
 * cover left behind by a partial write, matching the `isFile && length() >
 * 0` predicate the M1 review fix already requires at every real call site
 * (see `coverUrlFromCacheOrNull` in PrivateComicLibraryApp.kt).
 */
fun resolveCoverSource(coverPath: String?, fileExists: (String) -> Boolean): CoverSource {
    if (coverPath != null && fileExists(coverPath)) {
        return CoverSource.Local(coverPath)
    }
    return CoverSource.Remote
}

/**
 * Thin suspend API over [CloudMirrorDao], converting between Room entities
 * and the cloud domain types ([CloudCollectionCard] / [CloudComicMeta]) so
 * callers never see the mirror's storage shape.
 *
 * Slice B data layer only (loyal-pipelines design §2) — this is the local
 * Room mirror of the cloud collection that lets the Cloud shelf read
 * instantly/offline and fill progressively during a streaming import. The
 * hydration engine ([CloudHydrationEngine]) drives these writes, the pump
 * worker ([com.easemic.app.work.HydrationWorker])
 * schedules it, and the shelf UI reads [observeActiveCards] — all wired up
 * as of loyal-pipelines Slice B.
 *
 * Implements [CloudHydrationMirror] directly (method names already match)
 * so [CloudHydrationEngine] can be constructed against the real store in
 * production while unit tests drive it against a fake.
 */
class RoomCloudMirrorStore(
    private val dao: CloudMirrorDao,
    /**
     * S4 Task 1 (owner bug C4): downloads and persists a durable on-device
     * cover for one comic during tier-2 hydration ([upsertDetail]), returning
     * the new path on success. Null on any failure — including "no thumb
     * object", an IO error, or simply not being wired at all (the default,
     * and every existing test double), in which case the card's `coverPath`
     * stays null and the shelf keeps falling back to a live `downloadThumb`
     * read for that card until something else backfills it. Injected rather
     * than implemented here because durable cover writes need an
     * `ObjectStore` read plus a `filesDir` write — neither of which this
     * class otherwise touches; production wiring lives in
     * [com.easemic.app.AppGraph].
     *
     * S4 Task 1 review (M3): takes the card's already-computed
     * `coverThumbObjectKey` alongside the comic id — the caller reads the
     * truthful upload-time key ([CloudMirrorCardEntity.coverThumbObjectKey])
     * instead of guessing one, so a non-default thumb extension (e.g. a PNG
     * cover) still resolves.
     *
     * S4 Task 1 review (M4): [upsertDetail] now rethrows
     * [CancellationException] out of this callback instead of swallowing it
     * — a caller relying on structured concurrency (e.g. a cancelled
     * hydration walk) must see cancellation propagate, not a null cover.
     */
    private val coverSync: (suspend (cloudComicId: String, coverThumbObjectKey: String) -> String?)? = null,
) : CloudHydrationMirror, SerializedChapterSyncMirror {
    /**
     * Tier-1 write for one index chunk. [detailHydrated] is preserved for any
     * card already present (a chunk rewrite must not silently discard a
     * card's tier-2 hydration flag).
     */
    override suspend fun upsertChunkCards(
        bucketFingerprint: String,
        chunkIndex: Int,
        cards: List<CloudCollectionCard>,
        generation: Int,
    ) {
        if (cards.isEmpty()) return
        val entities = cards.map { card ->
            val existing = dao.card(bucketFingerprint, card.cloudComicId)
            val storedCard = if (card.layoutKind == CloudCardLayout.SERIES) {
                card.copy(
                    seriesGeneration = existing?.seriesGeneration ?: card.seriesGeneration,
                    providerId = card.providerId ?: existing?.providerId,
                    providerSeriesId = card.providerSeriesId ?: existing?.providerSeriesId,
                )
            } else {
                card
            }
            storedCard.toEntity(
                bucketFingerprint,
                chunkIndex,
                generation,
                detailHydrated = if (card.layoutKind == CloudCardLayout.SERIES) {
                    true
                } else {
                    existing?.detailHydrated ?: false
                },
                // The remote index never carries `coverPath` (it's
                // deliberately excluded from CloudCollectionIndex's codec —
                // it names a path on THIS device), so a chunk rewrite must
                // preserve whatever this row already had on-device or a
                // durable cover written by a prior sync/hydration pass would
                // be silently wiped on the next re-walk.
                coverPath = card.coverPath ?: existing?.coverPath,
            )
        }
        dao.upsertCards(entities)
        entities.forEach { entity ->
            dao.updateCard(entity)
            if (entity.layoutKind == CloudCardLayout.SINGLE_CHAPTER.name) {
                dao.replaceChapters(
                    entity.bucketFingerprint,
                    entity.cloudComicId,
                    listOf(entity.toLegacyChapter()),
                )
            }
        }
    }

    /**
     * Tier-2 write for one comic. Only the fields not already covered by the
     * tier-1 card row are persisted (tags/artists/groups/category/providerId/
     * truncatedFetchedPages) — title/pageCount/language/acquisitionRef/
     * collectedAtEpochMs live on [CloudMirrorCardEntity] already. Also flips
     * the card's `detailHydrated` flag once this comic's detail lands, and
     * (S4 Task 1, owner bug C4) opportunistically fills in a durable
     * on-device cover via [coverSync] for a card that doesn't have one yet —
     * so a comic hydrated from an index chunk (never uploaded from this
     * device) still ends up with a local-first cover rather than needing a
     * live R2 read on every shelf render forever.
     *
     * S4 Task 1 review (M4): the `detailHydrated` flip and the `coverPath`
     * fill each go through their own targeted DAO `UPDATE`
     * ([CloudMirrorDao.markDetailHydrated] / [CloudMirrorDao.setCoverPath])
     * instead of a read-modify-write whole-row card write. [coverSync] can
     * take a while (a network read); a
     * whole-row replace built from a `card` snapshot taken before that await
     * would silently revert any other targeted write (e.g.
     * [CloudHydrationMirror.setTrashed]) that lands on this same row while
     * this suspends. [CancellationException] is rethrown, never swallowed —
     * a cancelled hydration walk must cancel, not silently drop its cover.
     */
    override suspend fun upsertDetail(bucketFingerprint: String, meta: CloudComicMeta) {
        // dao.upsertDetail is a full-row REPLACE and CloudComicMeta carries no
        // snapshot column: without carrying the stored seriesSnapshotJson over,
        // every tier-2 write would null a snapshot an earlier hydration or
        // chapter sync recorded (F3 (b), spec §3.3).
        val existingDetail = dao.detail(bucketFingerprint, meta.cloudComicId)
        dao.upsertDetail(
            meta.toEntity(bucketFingerprint)
                .copy(seriesSnapshotJson = existingDetail?.seriesSnapshotJson),
        )
        val card = dao.card(bucketFingerprint, meta.cloudComicId) ?: return
        if (!card.detailHydrated) {
            dao.markDetailHydrated(bucketFingerprint, meta.cloudComicId)
        }
        syncGalleryChapter(bucketFingerprint, meta.cloudComicId)
        val sync = coverSync
        if (card.coverPath == null && sync != null) {
            val coverPath = try {
                sync.invoke(meta.cloudComicId, card.coverThumbObjectKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagLog.w(TAG, "coverSync failed for ${meta.cloudComicId}", error)
                null
            }
            if (coverPath != null) {
                dao.setCoverPath(bucketFingerprint, meta.cloudComicId, coverPath)
            }
        }
    }

    override suspend fun putState(
        bucketFingerprint: String,
        totalCards: Int,
        lastCompletedChunk: Int,
        hydrationComplete: Boolean,
        generation: Int,
    ) {
        dao.putState(
            CloudMirrorStateEntity(
                bucketFingerprint = bucketFingerprint,
                totalCards = totalCards,
                lastCompletedChunk = lastCompletedChunk,
                hydrationComplete = hydrationComplete,
                generation = generation,
            ),
        )
    }

    /** Live shelf feed: active (non-trashed) cards for one bucket, newest first. */
    fun observeActiveCards(bucketFingerprint: String): Flow<List<CloudCollectionCard>> =
        dao.observeActiveCards(bucketFingerprint).map { rows -> rows.map { it.toDomain() } }

    /** Snapshot form of [observeActiveCards] — used by the hydration engine's progress proof. */
    override suspend fun activeCards(bucketFingerprint: String): List<CloudCollectionCard> =
        dao.activeCards(bucketFingerprint).map { it.toDomain() }

    suspend fun allCards(bucketFingerprint: String): List<CloudCollectionCard> =
        dao.allCards(bucketFingerprint).map { it.toDomain() }

    override suspend fun state(bucketFingerprint: String): CloudMirrorHydrationState? =
        dao.state(bucketFingerprint)?.let {
            CloudMirrorHydrationState(
                totalCards = it.totalCards,
                lastCompletedChunk = it.lastCompletedChunk,
                hydrationComplete = it.hydrationComplete,
                generation = it.generation,
            )
        }

    /** Live form of [state] — drives the Cloud tab's "Importing · N/M" progress strip. */
    fun observeState(bucketFingerprint: String): Flow<CloudMirrorHydrationState?> =
        dao.observeState(bucketFingerprint).map { entity ->
            entity?.let {
                CloudMirrorHydrationState(
                    totalCards = it.totalCards,
                    lastCompletedChunk = it.lastCompletedChunk,
                    hydrationComplete = it.hydrationComplete,
                    generation = it.generation,
                )
            }
        }

    /** True when this bucket has never finished hydrating (no row, or an in-progress one). */
    suspend fun hydrationIncomplete(bucketFingerprint: String): Boolean =
        dao.state(bucketFingerprint)?.hydrationComplete != true

    override suspend fun card(bucketFingerprint: String, cloudComicId: String): CloudCollectionCard? =
        dao.card(bucketFingerprint, cloudComicId)?.toDomain()

    /** Active SERIES card for [identity]; acquisition URLs are locators only. */
    suspend fun activeSeriesCard(
        bucketFingerprint: String,
        identity: SeriesIdentityV2,
    ): CloudCollectionCard? =
        dao.activeCards(bucketFingerprint)
            .asSequence()
            .map { it.toDomain() }
            .firstOrNull { it.matchesSeriesIdentity(identity) }

    /** Tier-2 cursor: next active-or-trashed card still awaiting `meta.json` detail. */
    override suspend fun nextUnhydratedCard(bucketFingerprint: String): CloudCollectionCard? =
        dao.nextUnhydratedCard(bucketFingerprint)?.toDomain()

    override suspend fun seriesNeedsHydration(
        bucketFingerprint: String,
        card: CloudCollectionCard,
    ): Boolean {
        val existing = dao.card(bucketFingerprint, card.cloudComicId) ?: return true
        if (existing.layoutKind != CloudCardLayout.SERIES.name) return true
        if (existing.seriesGeneration != card.seriesGeneration) return true
        return card.chapterCount > 0 && dao.chapterCount(bucketFingerprint, card.cloudComicId) == 0
    }

    override suspend fun replaceSeriesChapters(
        bucketFingerprint: String,
        card: CloudCollectionCard,
        manifest: CloudSeriesManifest,
    ) {
        val existing = dao.detail(bucketFingerprint, card.cloudComicId)
        val existingSnapshot = existing?.seriesSnapshotJson?.let {
            runCatching { decodeSeriesSnapshot(it) }.getOrNull()
        }
        val mergedSnapshot = preferNewerSeriesSnapshot(existingSnapshot, manifest.detailSnapshot)
        if (mergedSnapshot != null) {
            val encoded = encodeSeriesSnapshot(mergedSnapshot)
            dao.upsertDetail(
                (existing ?: CloudMirrorDetailEntity(
                    bucketFingerprint = bucketFingerprint,
                    cloudComicId = card.cloudComicId,
                    tagsJson = "[]",
                    artistsJson = "[]",
                    groupsJson = "[]",
                    category = null,
                    providerId = manifest.providerId,
                    truncatedFetchedPages = null,
                )).copy(
                    providerId = manifest.providerId,
                    seriesSnapshotJson = encoded,
                ),
            )
        }
        dao.replaceChapters(
            fp = bucketFingerprint,
            id = card.cloudComicId,
            chapters = manifest.chapters.map { descriptor ->
                CloudMirrorChapterEntity(
                    bucketFingerprint = bucketFingerprint,
                    cloudComicId = card.cloudComicId,
                    chapterIdentity = descriptor.providerChapterId,
                    chapterKey = descriptor.chapterKey,
                    ordinal = descriptor.readingOrder,
                    label = descriptor.label,
                    groupLabel = descriptor.group,
                    pageCount = descriptor.pageCount,
                    coverObjectKey = descriptor.coverObjectKey,
                    acquisitionRef = manifest.seriesAcquisitionRef,
                    sourceState = descriptor.sourceState,
                    trashedAtEpochMs = card.trashedAtEpochMs,
                    detailHydrated = true,
                    generation = manifest.generation,
                )
            },
        )
        // The remote card is the generation the hydration predicate compares;
        // the series payload's generation is informational and may be repaired
        // independently from the index card.
        dao.setSeriesGeneration(bucketFingerprint, card.cloudComicId, card.seriesGeneration)
        dao.setSeriesIdentity(
            bucketFingerprint,
            card.cloudComicId,
            manifest.providerId.takeIf { it.isNotBlank() },
            manifest.providerSeriesId?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun markSeriesHydrationMissing(
        bucketFingerprint: String,
        card: CloudCollectionCard,
    ) {
        dao.deleteChapters(bucketFingerprint, card.cloudComicId)
    }

    suspend fun chapterRows(bucketFingerprint: String, cloudComicId: String): List<CloudMirrorChapterEntity> =
        dao.chapters(bucketFingerprint, cloudComicId)

    suspend fun seriesDetailSnapshot(bucketFingerprint: String, cloudComicId: String): SeriesDetailSnapshot? =
        dao.detail(bucketFingerprint, cloudComicId)?.seriesSnapshotJson?.let {
            runCatching { decodeSeriesSnapshot(it) }.getOrNull()
        }

    /**
     * F3 (b) (spec §3.3): prefer-newer merge into the mirror's
     * `seriesSnapshotJson` — the [replaceSeriesChapters] idiom. Older
     * incoming text never regresses newer stored text; unrelated columns on
     * the detail row are preserved.
     */
    override suspend fun upsertLiveSeriesSnapshot(
        bucketFingerprint: String,
        cloudComicId: String,
        snapshot: SeriesDetailSnapshot,
    ) {
        val existing = dao.detail(bucketFingerprint, cloudComicId)
        val existingSnapshot = existing?.seriesSnapshotJson?.let {
            runCatching { decodeSeriesSnapshot(it) }.getOrNull()
        }
        val merged = preferNewerSeriesSnapshot(existingSnapshot, snapshot) ?: return
        dao.upsertDetail(
            (existing ?: CloudMirrorDetailEntity(
                bucketFingerprint = bucketFingerprint,
                cloudComicId = cloudComicId,
                tagsJson = "[]",
                artistsJson = "[]",
                groupsJson = "[]",
                category = null,
                providerId = null,
                truncatedFetchedPages = null,
            )).copy(seriesSnapshotJson = encodeSeriesSnapshot(merged)),
        )
    }

    /** Live chapter completion seam used by serialized cloud ingest. */
    override suspend fun upsertLiveChapter(
        bucketFingerprint: String,
        card: CloudCollectionCard,
        descriptor: com.easemic.app.sync.CloudSeriesChapterDescriptor,
        generation: Int,
    ) {
        dao.upsertChapters(
            listOf(
                CloudMirrorChapterEntity(
                    bucketFingerprint = bucketFingerprint,
                    cloudComicId = card.cloudComicId,
                    chapterIdentity = descriptor.providerChapterId,
                    chapterKey = descriptor.chapterKey,
                    ordinal = descriptor.readingOrder,
                    label = descriptor.label,
                    groupLabel = descriptor.group,
                    pageCount = descriptor.pageCount,
                    coverObjectKey = descriptor.coverObjectKey,
                    acquisitionRef = card.acquisitionRef,
                    sourceState = descriptor.sourceState,
                    trashedAtEpochMs = card.trashedAtEpochMs,
                    detailHydrated = true,
                    generation = generation,
                ),
            ),
        )
    }

    /** Skip-without-detail path: flips the flag so a confirmed-missing `meta.json` isn't retried forever. */
    override suspend fun markDetailHydrated(bucketFingerprint: String, cloudComicId: String) {
        dao.markDetailHydrated(bucketFingerprint, cloudComicId)
        syncGalleryChapter(bucketFingerprint, cloudComicId)
    }

    /**
     * Full detail requires both tiers: the tier-1 card (title/pageCount/
     * language/acquisitionRef/collectedAtEpochMs) and the tier-2 detail row
     * (tags/artists/groups/category/providerId/truncatedFetchedPages). Null
     * when either is missing — including when tier-2 simply hasn't hydrated
     * yet for this comic (check [card]'s `detailHydrated`-backed state first).
     * `contentRevision` is not tracked by the mirror (not needed for local
     * shelf/detail display) and defaults to [CloudComicMeta]'s default of 1.
     */
    suspend fun detail(bucketFingerprint: String, cloudComicId: String): CloudComicMeta? {
        val card = dao.card(bucketFingerprint, cloudComicId) ?: return null
        val detail = dao.detail(bucketFingerprint, cloudComicId) ?: return null
        return CloudComicMeta(
            cloudComicId = cloudComicId,
            title = card.title,
            tags = detail.tagsJson.jsonToStringList().filterNot { it.startsWith(TRIMMED_AD_PAGES_TAG_PREFIX) },
            artists = detail.artistsJson.jsonToStringList(),
            groups = detail.groupsJson.jsonToStringList(),
            category = detail.category,
            language = card.language,
            pageCount = card.pageCount ?: 0,
            providerId = detail.providerId ?: "",
            acquisitionRef = card.acquisitionRef ?: "",
            collectedAtEpochMs = card.collectedAtEpochMs,
            truncatedFetchedPages = detail.truncatedFetchedPages,
            trimmedAdPages = detail.tagsJson.jsonToStringList()
                .firstOrNull { it.startsWith(TRIMMED_AD_PAGES_TAG_PREFIX) }
                ?.removePrefix(TRIMMED_AD_PAGES_TAG_PREFIX)
                ?.toIntOrNull()
                ?.takeIf { it > 0 },
        )
    }

    /** Drops every mirror row for [bucketFingerprint] (all three tables). */
    suspend fun wipe(bucketFingerprint: String) {
        dao.deleteCards(bucketFingerprint)
        dao.deleteDetail(bucketFingerprint)
        dao.deleteState(bucketFingerprint)
    }

    /**
     * Rebind-to-a-different-bucket wipe (loyal-pipelines Slice B pump task
     * §2): drops every OTHER bucket's mirror rows, leaving
     * [bucketFingerprint]'s own rows untouched. A rebind that resolves back
     * to the *same* bucket therefore never loses its already-hydrated
     * cards — the pump's next kick just becomes a cheap generation check.
     */
    suspend fun wipeAllExcept(bucketFingerprint: String) {
        dao.deleteCardsExcept(bucketFingerprint)
        dao.deleteDetailExcept(bucketFingerprint)
        dao.deleteStateExcept(bucketFingerprint)
    }

    /** Disconnect wipe: drops every mirror row for every bucket. */
    suspend fun wipeAll() {
        dao.deleteAllCards()
        dao.deleteAllDetail()
        dao.deleteAllState()
    }

    suspend fun deleteCard(bucketFingerprint: String, cloudComicId: String) {
        dao.deleteCard(bucketFingerprint, cloudComicId)
    }

    suspend fun setTrashed(bucketFingerprint: String, cloudComicId: String, trashedAtEpochMs: Long?) {
        dao.setTrashed(bucketFingerprint, cloudComicId, trashedAtEpochMs)
        val card = dao.card(bucketFingerprint, cloudComicId)
        if (card?.layoutKind == CloudCardLayout.SINGLE_CHAPTER.name) {
            dao.replaceChapters(
                bucketFingerprint,
                cloudComicId,
                listOf(card.copy(trashedAtEpochMs = trashedAtEpochMs).toLegacyChapter()),
            )
        }
    }

    /**
     * S4 Task 1 (owner bug C4): persists a just-written durable on-device
     * cover path for one card. Used by the UI's local-first cover read after
     * a one-off `downloadThumb` fallback — the exact scenario [coverSync]
     * doesn't cover, since that only fires from tier-2 hydration, not from a
     * UI-triggered fallback read on an already-hydrated card.
     */
    suspend fun setCoverPath(bucketFingerprint: String, cloudComicId: String, coverPath: String) {
        dao.setCoverPath(bucketFingerprint, cloudComicId, coverPath)
    }

    /** Updates the durable cloud cover key without replacing the local cover path. */
    suspend fun setCoverThumbObjectKey(
        bucketFingerprint: String,
        cloudComicId: String,
        coverObjectKey: String,
    ) {
        dao.setCoverThumbObjectKey(bucketFingerprint, cloudComicId, coverObjectKey)
    }

    /**
     * A22 §4 own-upload generation stamp. This device's upload moved the cloud
     * manifest from [generations].previousGeneration to newGeneration; when the
     * local state row is fully hydrated at exactly the previous generation
     * ([ownUploadGenerationStampApplies]), adopt the new one in place —
     * totalCards = the new comic count, checkpoint/complete flags untouched —
     * so the next [CloudHydrationEngine.hydrateStep] is a single
     * generation-equal GET instead of a needless full re-walk. Returns false
     * (writing nothing) in every other case; the next check then re-walks and
     * picks up whatever changed. Call BEFORE [upsertLiveCard] so the live card
     * reads the stamped generation.
     *
     * Concurrency (reviewer must-fix): the [state] read above is only a cheap
     * precondition to skip the DAO call entirely for the common "doesn't
     * apply" cases (no row, mid-walk, null/invalid generations) — it is NOT
     * relied on for correctness. The actual write goes through
     * [CloudMirrorDao.stampGenerationIfAtPrevious], a single conditional
     * `UPDATE ... WHERE generation = :previousGeneration AND
     * hydrationComplete = 1`, so a concurrent hydration walk that rewrites
     * this row to a DIFFERENT generation between this method's precondition
     * read and its write can never be clobbered: the WHERE clause
     * re-validates the row as it stands AT WRITE TIME, not the (possibly
     * stale) snapshot read above. Without this, a diverged row would be
     * stamped with OUR generation and this device's just-uploaded card would
     * then be pruned as a ghost of the generation we wrongly claimed.
     */
    suspend fun stampGeneration(bucketFingerprint: String, generations: CloudIndexGenerations): Boolean {
        val existing = state(bucketFingerprint)
        if (!ownUploadGenerationStampApplies(existing, generations)) return false
        val previous = checkNotNull(generations.previousGeneration)
        return dao.stampGenerationIfAtPrevious(
            fp = bucketFingerprint,
            previousGeneration = previous,
            newGeneration = generations.newGeneration,
            totalCards = generations.comicCount,
        ) == 1
    }

    /**
     * Upload-completion upsert (loyal-pipelines Slice B §2, live updates):
     * inserts a just-uploaded comic's card outside of any hydration chunk
     * walk, so the shelf reflects it immediately without waiting for the
     * next hydration pass.
     *
     * Stamped with the bucket's *current* state generation (or 0 when no
     * state row exists yet) rather than [LIVE_CARD_CHUNK_INDEX]'s own concept
     * of a generation, because every cloud-side mutation (this upload
     * included) bumps the upstream manifest generation — so even if this
     * stamp turns out stale by the time the next hydration walk runs, that
     * walk's generation-mismatch check will simply trigger a full re-walk
     * that rediscovers and correctly re-stamps this card. [chunkIndex] is set
     * to the [LIVE_CARD_CHUNK_INDEX] sentinel (not part of any index chunk).
     * The comic is treated as fully tier-2 hydrated immediately since [meta]
     * — the same object just uploaded — is available right here; no need to
     * wait for a later detail trickle pass.
     */
    override suspend fun upsertLiveCard(bucketFingerprint: String, card: CloudCollectionCard, meta: CloudComicMeta) {
        val generation = dao.state(bucketFingerprint)?.generation ?: 0
        val entity = card.toEntity(
            bucketFingerprint,
            chunkIndex = LIVE_CARD_CHUNK_INDEX,
            generation = generation,
            detailHydrated = true,
        )
        dao.upsertCards(
            listOf(entity),
        )
        dao.updateCard(entity)
        if (entity.layoutKind == CloudCardLayout.SINGLE_CHAPTER.name) {
            dao.replaceChapters(bucketFingerprint, card.cloudComicId, listOf(entity.toLegacyChapter()))
        }
        upsertDetail(bucketFingerprint, meta)
    }

    /**
     * Ghost-card prune (see [CloudMirrorCardEntity.generation]): removes cards
     * not stamped with [keepGeneration] plus their now-orphaned detail rows.
     */
    override suspend fun pruneStaleCards(bucketFingerprint: String, keepGeneration: Int) {
        dao.deleteCardsNotOfGeneration(bucketFingerprint, keepGeneration)
        dao.deleteDetailNotInCards(bucketFingerprint)
    }

    private fun CloudCollectionCard.toEntity(
        bucketFingerprint: String,
        chunkIndex: Int,
        generation: Int,
        detailHydrated: Boolean,
        coverPath: String? = this.coverPath,
    ): CloudMirrorCardEntity = CloudMirrorCardEntity(
        bucketFingerprint = bucketFingerprint,
        cloudComicId = cloudComicId,
        title = title,
        coverThumbObjectKey = coverThumbObjectKey,
        collectedAtEpochMs = collectedAtEpochMs,
        pageCount = pageCount,
        language = language,
        acquisitionRef = acquisitionRef,
        trashedAtEpochMs = trashedAtEpochMs,
        chunkIndex = chunkIndex,
        detailHydrated = detailHydrated,
        generation = generation,
        coverPath = coverPath,
        layoutKind = layoutKind.name,
        chapterCount = chapterCount,
        readableChapterCount = readableChapterCount,
        seriesMetaObjectKey = seriesMetaObjectKey,
        seriesGeneration = seriesGeneration,
        providerId = providerId,
        providerSeriesId = providerSeriesId,
    )

    private fun CloudMirrorCardEntity.toDomain(): CloudCollectionCard = CloudCollectionCard(
        cloudComicId = cloudComicId,
        title = title,
        coverThumbObjectKey = coverThumbObjectKey,
        collectedAtEpochMs = collectedAtEpochMs,
        pageCount = pageCount,
        language = language,
        acquisitionRef = acquisitionRef,
        trashedAtEpochMs = trashedAtEpochMs,
        coverPath = coverPath,
        layoutKind = runCatching { CloudCardLayout.valueOf(layoutKind) }.getOrDefault(CloudCardLayout.SINGLE_CHAPTER),
        chapterCount = chapterCount,
        readableChapterCount = readableChapterCount,
        seriesMetaObjectKey = seriesMetaObjectKey,
        seriesGeneration = seriesGeneration,
        providerId = providerId,
        providerSeriesId = providerSeriesId,
    )

    private fun CloudMirrorCardEntity.toLegacyChapter(): CloudMirrorChapterEntity = CloudMirrorChapterEntity(
        bucketFingerprint = bucketFingerprint,
        cloudComicId = cloudComicId,
        chapterIdentity = "legacy:0",
        chapterKey = null,
        ordinal = 0,
        label = null,
        groupLabel = null,
        pageCount = pageCount,
        coverObjectKey = coverThumbObjectKey,
        acquisitionRef = acquisitionRef,
        sourceState = "ACTIVE",
        trashedAtEpochMs = trashedAtEpochMs,
        detailHydrated = detailHydrated,
        generation = generation,
    )

    private suspend fun syncGalleryChapter(bucketFingerprint: String, cloudComicId: String) {
        val card = dao.card(bucketFingerprint, cloudComicId)
        if (card?.layoutKind == CloudCardLayout.SINGLE_CHAPTER.name) {
            dao.replaceChapters(bucketFingerprint, cloudComicId, listOf(card.toLegacyChapter()))
        }
    }

    private fun CloudComicMeta.toEntity(bucketFingerprint: String): CloudMirrorDetailEntity {
        val rawTags = tags.filterNot { it.startsWith(TRIMMED_AD_PAGES_TAG_PREFIX) }
        val storedTags = if (trimmedAdPages != null && trimmedAdPages > 0) {
            rawTags + "$TRIMMED_AD_PAGES_TAG_PREFIX$trimmedAdPages"
        } else {
            rawTags
        }
        return CloudMirrorDetailEntity(
            bucketFingerprint = bucketFingerprint,
            cloudComicId = cloudComicId,
            tagsJson = storedTags.toJsonArrayString(),
            artistsJson = artists.toJsonArrayString(),
            groupsJson = groups.toJsonArrayString(),
            category = category,
            providerId = providerId,
            truncatedFetchedPages = truncatedFetchedPages,
        )
    }

    private fun List<String>.toJsonArrayString(): String = JSONArray(this).toString()

    private fun String.jsonToStringList(): List<String> {
        if (isBlank()) return emptyList()
        val array = JSONArray(this)
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotBlank()) add(value)
            }
        }
    }

    companion object {
        /** [CloudMirrorCardEntity.chunkIndex] sentinel for [upsertLiveCard]: "live, not from a chunk". */
        private const val LIVE_CARD_CHUNK_INDEX = -1
        private const val TRIMMED_AD_PAGES_TAG_PREFIX = "__pcl_trimmed_ads__:"
    }
}
