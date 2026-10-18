package com.easemic.app.feature.collect

import com.easemic.app.feature.bridge.ByoCloudIoCopy
import com.easemic.app.feature.browse.BrowseFeedRepository
import com.easemic.app.importing.ehentai.EhentaiClient
import com.easemic.app.importing.opds.OpdsCatalogClient
import com.easemic.app.sync.CloudCollectionCard
import com.easemic.app.sync.CloudCollectionIngest
import com.easemic.app.sync.CloudCollectionIngestProgress
import com.easemic.app.sync.CloudCollectionIngestRequest
import com.easemic.app.sync.CloudCollectionIngestResult
import com.easemic.app.sync.CloudCollectionStore
import com.easemic.app.sync.CloudArchiveUpload
import com.easemic.app.sync.CloudSeriesChapterUploadRequest
import com.easemic.app.sync.CloudSeriesWriter
import com.easemic.app.sync.CloudComicMeta
import com.easemic.app.sync.CloudIndexGenerations
import com.easemic.app.sync.CloudRateLimitedException
import com.easemic.app.sync.CloudSignIn
import com.easemic.app.sync.CloudUploadProgress
import com.easemic.app.sync.DiagLog
import com.easemic.app.sync.cloudIngestWorkDir
import com.easemic.app.sync.cloudIngestChapterWorkDir
import com.easemic.app.sync.DamagedLibraryLockException
import com.easemic.app.sync.LibraryLockedException
import com.easemic.app.sync.NewerCloudFormatException
import com.easemic.app.sync.ObjectStore
import com.easemic.app.sync.StaleCloudBindingException
import com.easemic.app.sync.adfilter.TrailingAdStripper
import com.easemic.app.sync.redactAcquisition
import com.easemic.app.feature.download.DownloadLocator
import com.easemic.app.feature.download.R2SourceAcquisition
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.GalleryIdentityV2
import com.easemic.app.sync.readCloudIngestCheckpoint
import com.easemic.app.sync.UnreadableCloudLibraryException
import com.easemic.app.sync.WebDavObjectStore
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** What the caller (today's compose coroutine; a future WorkManager pump) needs after a row ran. */
sealed interface CloudIngestOutcome {
    data class Synced(val pages: Int) : CloudIngestOutcome
    data class Failed(val message: String) : CloudIngestOutcome

    /** Store unavailable / library locked or unreadable — row already marked FAILED with honest copy. */
    data class Blocked(val message: String) : CloudIngestOutcome
    data object Cancelled : CloudIngestOutcome

    /**
     * Owner bug A3/C5 fix (T3a): [resolveStore] returned null but a binding
     * IS persisted on this device — a transient failure (e.g. a momentary
     * OAuth-refresh network hiccup right after app reopen), not proof the
     * user never connected cloud storage. Unlike [Blocked], the row was
     * deliberately left WAITING (never [CloudSyncQueue.markFailed]), so
     * [nextRunnableCloudSyncRow] can pick it back up on the pump's next
     * pass. [CloudIngestWorker.doWork] uses this to re-schedule that pass —
     * since C35-b a flat-delay `kickAfter`, not a `Result.retry()` whose
     * exponential backoff climbed toward hours.
     */
    data class Deferred(val message: String) : CloudIngestOutcome

    /**
     * A27 (owner bug backlog, ledger 2026-09-05): [resolveStore] returned
     * null AND [hasBinding] says no cloud storage is bound on this device at
     * all — genuinely different from [Deferred] (a binding exists but is
     * momentarily unresolvable) and from the old pre-A27 [Blocked] behavior
     * for this same condition (which used to [CloudSyncQueue.markFailed]
     * with [ByoCloudIoCopy.BIND], burning a `failedAttempts` and showing the
     * Activity tab a misleading "retrying" line for a row nothing will ever
     * retry on its own). [run] leaves the row exactly as it found it
     * (WAITING) — there is nothing to retry until the user actually binds a
     * bucket, at which point [com.easemic.app.AppGraph.onBucketBound]
     * revives every row parked with this message
     * ([CloudSyncQueue.reviveFailedWithMessage] — a no-op here since the row
     * was never FAILED) and re-runs [com.easemic.app.AppGraph.repairCloudHandoffs].
     * [runCloudIngestPumpLoop] treats this like [Deferred] for claiming
     * (stop claiming new rows; let in-flight siblings finish) but maps the
     * loop result to `WorkerResultKind.SUCCESS`, not `RETRY` — no periodic
     * WorkManager retry loop while unbound; the bind hook is the only
     * wake-up.
     */
    data class Unbound(val message: String) : CloudIngestOutcome

    /**
     * S3 Task 6 (owner bug 2026-09-04): the durable sync ledger already
     * proves this row's comic reached the cloud — [run] refuses to
     * re-upload it and removes the zombie row instead. Defense-in-depth
     * alongside the pump's own presence guard (a row can only reach [run]
     * at all if it slipped past that guard, e.g. a stale replay-kick race).
     */
    data object AlreadySynced : CloudIngestOutcome
}

/**
 * A28 (owner bug backlog, ledger 2026-09-04): true only when the bucket an
 * upload actually landed in ([uploadedToFingerprint], captured by [run]
 * BEFORE `resolveStore()` ran — coordinator review 2026-09-05, M1) is STILL
 * the one currently bound ([currentFingerprint], read again at completion
 * time — M2). A rebind at any point between those two reads (or either
 * fingerprint being unavailable) fails closed — the caller must skip
 * ledgering/stamping/releasing local pages against a binding the upload
 * never actually reached.
 */
fun uploadStillTargetsBoundBucket(uploadedToFingerprint: String?, currentFingerprint: String?): Boolean =
    uploadedToFingerprint != null && uploadedToFingerprint == currentFingerprint

/**
 * A28 (owner bug backlog, ledger 2026-09-04): like [uploadStillTargetsBoundBucket]
 * but returns the confirmed fingerprint itself instead of a Boolean, so a
 * caller can bind it to a non-null local via a plain null-check instead of
 * an unsafe `!!` on a separately-read current fingerprint. Null means skip
 * (rebind detected, or either fingerprint missing).
 */
fun confirmedUploadFingerprint(uploadedToFingerprint: String?, currentFingerprint: String?): String? =
    currentFingerprint?.takeIf { uploadStillTargetsBoundBucket(uploadedToFingerprint, it) }

/**
 * Runs one [CloudSyncQueue] row to completion: resolves the gated product
 * store, mints/looks-up the row's cloudComicId, resolves any already
 * downloaded local pages, then drives [CloudCollectionIngest] with the
 * existing bounded auto-retry/resume. UI-free by construction — a future
 * WorkManager pump can call [run] with the app closed. This is a straight
 * transplant of the body that used to live inline in the compose
 * `collectBookmark` coroutine; behavior is unchanged, callers decide what to
 * do with the returned [CloudIngestOutcome].
 */
/**
 * A28 (owner bug backlog, ledger 2026-09-04; coordinator review 2026-09-05,
 * M2): the exact copy [CloudSyncQueue.markFailed] receives when [CloudIngestRunner.run]
 * detects that the bucket bound at upload completion no longer matches the
 * one the upload actually targeted — parked FAILED with backoff so
 * `nextRunnableCloudSyncRow` re-drives it against whichever bucket ends up
 * bound, instead of the row silently vanishing as Synced.
 */
private const val CLOUD_CHANGED_DURING_UPLOAD_MESSAGE = "cloud storage changed during upload"

private fun serializedRequestKey(acquisition: String, chapterIdentity: String): String =
    "$acquisition\u0000$chapterIdentity"

/**
 * #E111: whether [sibling] — another chapter row of the same series — will
 * still drive a series-tail write after this chapter's own upload finishes.
 * Running/waiting rows obviously will; a FAILED row only counts while it
 * still has auto-resume budget, so a terminally parked sibling never holds
 * a deferred tail hostage (the stranded-tail heal is the ledger's
 * synced-key repair on the series' next write). File-level on purpose: the
 * [CloudIngestRunner.runIngest] default body that calls it is a constructor
 * default, where `this` is not initialized yet.
 */
private fun siblingChapterWillWriteTail(sibling: CloudSyncQueueItem): Boolean =
    when (sibling.stage) {
        CloudSyncStage.FAILED ->
            CloudSyncFailurePolicy.canRetry(sibling.errorMessage) &&
                sibling.failedAttempts < CloudSyncIngestAutoRetry.MAX_ATTEMPTS
        CloudSyncStage.WAITING, CloudSyncStage.FETCHING, CloudSyncStage.UPLOADING -> true
    }

class CloudIngestRunner(
    private val queue: CloudSyncQueue,
    private val gate: CloudSyncIngestGate,
    private val resolveStore: suspend () -> ObjectStore?,
    /**
     * Owner bug A3/C5 fix (T3a): whether a bucket binding is actually
     * persisted on this device, INDEPENDENT of whether [resolveStore] could
     * resolve a live store just now. Lets [run] distinguish "never bound at
     * all" (A27: the row is parked WAITING untouched and [run] returns
     * [CloudIngestOutcome.Unbound] with [ByoCloudIoCopy.BIND]; the bind
     * hook is the only wake-up) from "bound, but transiently unresolvable
     * this attempt" (also left WAITING — see [CloudIngestOutcome.Deferred],
     * which the worker retries with backoff). Defaults to `{ false }`, so a
     * call site that never wires it treats every unresolvable store as
     * unbound (park, no retry loop) rather than as transient.
     */
    private val hasBinding: suspend () -> Boolean = { false },
    /**
     * C37-g: whether the persisted OAuth grant is known-dead (`invalid_grant`).
     * A dead grant is terminal: the row is parked FAILED with the reconnect
     * copy and its retry budget exhausted, never [CloudIngestOutcome.Deferred].
     */
    private val isBindingDead: suspend () -> Boolean = { false },
    /**
     * A28 (owner bug backlog, ledger 2026-09-04): reads the CURRENTLY bound
     * bucket's fingerprint. [run] calls this TWICE per attempt — once
     * BEFORE [resolveStore] runs, so `uploadedToFingerprint` reflects the
     * binding this upload is actually about to target (coordinator review
     * 2026-09-05, M1: reading it only afterward could already see a rebind
     * that happened WHILE [resolveStore] itself was resolving, silently
     * defeating this whole guard), and again at completion time (M2) to
     * catch a rebind that happened mid-upload — see
     * [uploadStillTargetsBoundBucket]. Non-suspend to match
     * [com.easemic.app.AppGraph.currentBucketFingerprintOrNull]'s
     * shape; `{ null }` by default so every existing call site (production
     * and test) keeps today's behavior unless it opts in.
     */
    private val currentBucketFingerprint: () -> String? = { null },
    /**
     * S3 Task 6 (owner bug 2026-09-04): whether the durable sync ledger
     * already proves THIS row's comic reached the cloud, independent of the
     * pump's own presence guard (which normally removes such a row before it
     * ever reaches [run] at all — this is defense-in-depth for a zombie row
     * that slips past that guard, e.g. a stale replay-kick race). Defaults to
     * `{ false }` so every existing call site (production and test) keeps
     * today's behavior unless it opts in.
     */
    private val isKnownSynced: suspend (acquisition: String) -> Boolean = { false },
    /**
     * #E30: the same chapter ledger question [isSerializedChapterSyncedToBucket]
     * answers for the release sweep. A serialized row whose own chapter is
     * already synced is [CloudIngestOutcome.AlreadySynced] before any request
     * is built. Defaults to false so a caller that only wires [isKnownSynced]
     * keeps the gallery guard and does not treat a series as synced.
     */
    private val isSerializedChapterSynced: suspend (
        providerId: String,
        canonicalItemId: String,
        chapterId: String,
    ) -> Boolean = { _, _, _ -> false },
    /**
     * #C66: the rebind gap between `AppGraph.onBucketBound` reviving queued
     * rows and `syncLedgerWithMirror` backfilling the new bucket's ledger
     * (only run on hydration completion, a minute or more later) leaves
     * [isKnownSynced] blind for every comic — so a row for a comic the
     * rebound bucket already has re-drives a full ingest attempt (listing,
     * hashing, re-PUTting thumbs/meta/manifest, rewriting the index) purely
     * because the ledger has not caught up yet. [run] consults this ONLY
     * while [isKnownSynced] said false, and only before hydration completes
     * (a hydration-complete bucket's ledger is already authoritative — see
     * `AppGraph.syncLedgerWithMirror`'s KDoc). Returns the matching card's
     * `cloudComicId` when [row]'s acquisition is already represented in the
     * bound bucket's current index, matched by the same gallery-only rule
     * [com.easemic.app.feature.collect.backfillFromMirror]
     * uses. Serialized rows bypass this preflight and use their identity
     * ledger, so they return null here.
     * `{ null }` by default so every existing call site keeps today's
     * behavior unless it opts in; production wires a per-fingerprint/
     * generation memoised index read (`AppGraph.cloudBoundIndexGate`)
     * so two rows in one pump pass cost one listing, not one per row.
     */
    private val presentInCurrentIndex: suspend (acquisition: String) -> String? = { null },
    /**
     * #C66: records the row's acquisition as synced to the CURRENTLY bound
     * bucket, exactly as `AppGraph.syncLedgerWithMirror`'s own backfill would
     * — so a comic [presentInCurrentIndex] found does not have to be
     * rediscovered by every later pump pass before hydration completes.
     * `{ }` by default; production wires the same `cloudSyncLedgerStore.markSynced`
     * write the gallery backfill uses, keyed by `galleryDedupKey(acquisition)`.
     */
    private val recordSynced: suspend (acquisition: String, cloudComicId: String) -> Unit = { _, _ -> },
    /**
     * C37-f: the clock the rate-limit pause is measured against. Injectable so
     * a test can prove the pause arithmetic without sleeping.
     */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /**
     * C37-f: how this runner waits. Injectable for the same reason as [nowMs]
     * — a test substitutes a recorder and asserts the durations instead of
     * spending them.
     */
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
    /**
     * C37-f: the process-wide rate-limit window. Cloudflare's 429 blocks the
     * whole ACCOUNT, and [com.easemic.app.work.runCloudIngestPumpLoop]
     * drives every concurrent slot through ONE runner instance — so a 429 seen
     * by one slot parks all of them here, instead of the other slots marching
     * straight into the same wall and burning their own budgets.
     */
    private val rateLimitPause: CloudRateLimitPause = CloudRateLimitPause(),
    private val cloudComicIdFor: (acquisitionUrl: String) -> String,
    private val frontClientFactory: () -> EhentaiClient,
    private val localPageFiles: suspend (indexGalleryId: Long?, frontGalleryId: Long?) -> List<File>?,
    private val workRoot: File,
    /** Identity-first lookup for opaque acquisition providers such as OPDS. */
    private val localPageFilesForIdentity: suspend (GalleryIdentityV1) -> List<File>? = { null },
    /** Identity-first sealed-state gate for opaque acquisition providers. */
    private val isSealedDownloadJobForIdentity: suspend (GalleryIdentityV1) -> Boolean = { false },
    /**
     * C2 audit MEDIUM-1: a SEALED job's pages are read from the
     * `download-ready-pages` staging revision for the WHOLE upload (thumbs,
     * hashing, then each PUT lazily), so the ingest is a reader of staging
     * exactly like the online reader — and must hold the staging lease. Without
     * it, a Download tap on that Detail mid-upload adopted the pages into the
     * formal tree, committed FINISH, and C2's reclaim unlinked staging under
     * the running upload (remaining PUTs → FileNotFound → the row failed until
     * a later re-drive). Given the same refs the page resolvers get; returns a
     * lease to close when the attempt loop is done, or null when the job is not
     * SEALED. Production wires `AppGraph.retainSealedStagingForIngest`.
     */
    private val retainSealedPages: suspend (
        persistedIdentity: GalleryIdentityV1?,
        r2Source: Pair<String, String>?,
        indexGalleryId: Long?,
        frontGalleryId: Long?,
    ) -> AutoCloseable? = { _, _, _, _ -> null },
    private val retainSerializedSealedPages: suspend (series: GalleryIdentityV1, chapterIdentity: String) -> AutoCloseable? = { _, _ -> null },
    /** Identity-first truncation metadata lookup for opaque acquisition providers. */
    private val truncatedFetchedPagesForIdentity: suspend (GalleryIdentityV1) -> Int? = { null },
    /**
     * #C30: the catalog facts the ENQUEUE already recorded for this identity
     * (`DownloadLocatorStore.putKeepingCatalogFacts` — raw `namespace:label`
     * tags plus language/category/title). The ordinary sealed-local upload
     * used to pass `preservedMeta = null`, so `comics/<id>/meta.json` was
     * written with EMPTY tags/artists/groups/category/language even though
     * these facts were sitting on the device the whole time; the live-fetch
     * `ingestIndex`/`ingestFront` paths never had the bug because they fill
     * the same fields from the gallery plan. Production wires
     * `AppGraph.downloadLocatorStore.get`. `{ null }` by default so every
     * existing call site keeps today's (empty) behavior, and a locator with
     * no facts — pre-A32, or an OPDS entry, which carries none — yields the
     * same empty meta: nothing is invented.
     */
    private val catalogFactsForIdentity: suspend (GalleryIdentityV1) -> DownloadLocator? = { null },
    /** Lets a caller register the in-flight job under the resolved cloudId. No-op by default. */
    private val registerJob: (cloudId: String) -> Unit = {},
    /**
     * True when this row is backed by a SEALED cloud-intent download job
     * (spec §1.1 fetch→upload handoff). Gates the default [runIngest] onto
     * the upload-only path: no live upstream manifest/plan fetch, and a null
     * [localPageFiles] result fails the row honestly instead of falling
     * through to the legacy fetch-from-source ingest. False (default) keeps
     * every existing/legacy row on the original `ingestIndex`/`ingestFront`
     * behavior, unchanged.
     */
    private val isSealedDownloadJob: suspend (indexGalleryId: Long?, frontGalleryId: Long?) -> Boolean =
        { _, _ -> false },
    /** Threaded into [CloudCollectionIngestRequest.truncatedFetchedPages] for a sealed-backed row (spec §1.3 option A). */
    private val truncatedFetchedPagesFor: suspend (indexGalleryId: Long?, frontGalleryId: Long?) -> Int? =
        { _, _ -> null },
    /**
     * Slice-C2 old-bucket migration: resolves device-local page files already
     * fetched from the OLD bucket for `r2source:<oldFingerprint>` rows (see
     * [R2SourceAcquisition]). Mirrors [localPageFiles]'s contract exactly
     * (null fails the row honestly via the same
     * `CLOUD_INGEST_PAGES_MISSING_POST_SEAL` guard) — kept as a separate hook
     * rather than overloading [localPageFiles] because an r2source row is
     * keyed by (oldFingerprint, oldCloudComicId), not a Front/Index gallery
     * id. No-op default so every existing call site is unaffected.
     */
    private val r2SourceLocalPages: suspend (oldFingerprint: String, oldCloudComicId: String) -> List<File>? =
        { _, _ -> null },
    /**
     * Slice-C2 old-bucket migration ("the hard part"): freshly reads the OLD
     * bucket's [CloudComicMeta] for an r2source row at upload time — never
     * cached, since the old bucket is read-only and never deleted, a fresh
     * read is always safe and can't go stale. Threaded into
     * [CloudCollectionIngestRequest.preservedMeta] so the new bucket's upload
     * carries the old title/tags/artists/groups/category/language forward
     * instead of just page bytes. Returns null — an honest, documented
     * degradation, not a silent one — when the old store is unavailable at
     * upload time (e.g. an encrypted old bucket whose DEK never left process
     * memory across a restart); pages already fetched still land via the
     * legacy sealed-handoff fallback fields (title/pageCount/providerId
     * only). No-op default so every existing call site is unaffected.
     * #C61: production no longer wires this (the Migrate flow that fed it is
     * deleted), so it is the no-op default there; only tests supply a value.
     */
    private val r2SourceOldMeta: suspend (oldFingerprint: String, oldCloudComicId: String) -> CloudComicMeta? =
        { _, _ -> null },
    /**
     * Live-mutation hook (loyal-pipelines Slice B §2): fired once a row lands
     * [CloudCollectionIngestResult.Synced], with that same result's freshly
     * uploaded [CloudCollectionCard]/[CloudComicMeta], so a caller can
     * live-upsert the local Room mirror the instant an upload completes
     * instead of waiting for the next hydration pass to discover it. No-op
     * default; not invoked at all when a result's `card`/`meta` are null
     * (legacy positional-constructor call sites, e.g. tests).
     *
     * [sourceRowAcquisition] is the ROW's own acquisition (`row.id` — the
     * `r2source://<oldFingerprint>/<oldCloudComicId>` URL for a slice-C2
     * migration row) — deliberately SEPARATE from `meta.acquisitionRef`
     * (loyal-pipelines whole-slice-C review HIGH fix). Once a migrated
     * card's `acquisitionRef` carries the OLD bucket's original SOURCE
     * acquisition forward (so a later reconcile recognizes it — see
     * [com.easemic.app.sync.CloudCollectionIngest]),
     * `meta.acquisitionRef` can no longer double as "which r2source job just
     * finished". This field is what still identifies the ROW.
     *
     * [indexGenerations] (A22 §4) is the Synced result's own-upload manifest
     * generations (previous -> new), forwarded verbatim so AppGraph can stamp
     * the local mirror instead of forcing a full re-walk; null for legacy
     * results that never carried them.
     *
     * [uploadedToFingerprint] (A28) is the bucket fingerprint [run] captured
     * via [currentBucketFingerprint] BEFORE `resolveStore()` ran for this
     * upload — see [uploadStillTargetsBoundBucket]. Null for legacy results
     * / test call sites that never carried it.
     */
    private val onSynced: suspend (
        card: CloudCollectionCard,
        meta: CloudComicMeta,
        sourceRowAcquisition: String,
        indexGenerations: CloudIndexGenerations?,
        uploadedToFingerprint: String?,
        providerNamespace: String?,
        canonicalItemId: String?,
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    /**
     * Builds the serialized chapter upload from the already-fetched local
     * series/chapter facts.  The runner deliberately does not source-fetch or
     * invent those facts: AppGraph supplies the local Room/page/thumb view and
     * tests can provide a complete request directly.
     */
    private val serializedUploadRequestFor: suspend (
        row: CloudSyncQueueItem,
        seriesCloudId: String,
        chapterKey: String,
        checkpointWorkDir: File,
        syncedChapterKeys: Map<String, String>,
    ) -> CloudSeriesChapterUploadRequest? = { _, _, _, _, _ -> null },
    /** Ledger/mirror completion for a serialized chapter. */
    private val onSerializedSynced: suspend (
        request: CloudSeriesChapterUploadRequest,
        upload: com.easemic.app.sync.CloudArchiveUpload,
        sourceRowAcquisition: String,
        indexGenerations: CloudIndexGenerations?,
        uploadedToFingerprint: String?,
    ) -> Unit = { _, _, _, _, _ -> },
    /**
     * Existing chapter keys from this device's ledger, used for summary repair.
     * #E21 tail 2: keys resolve from the series.json this attempt already
     * decoded, so this makes no per-chapter summary GET.
     */
    private val syncedChapterKeysFor: suspend (
        series: com.easemic.app.feature.download.SeriesIdentityV2,
        seriesManifest: com.easemic.app.sync.CloudSeriesManifest?,
    ) -> Map<String, String> = { _, _ -> emptyMap() },
    /** Single production resolver: ledger, active identity mirror, then identity-keyed mint. */
    private val serializedCloudIdFor: suspend (series: com.easemic.app.feature.download.SeriesIdentityV2) -> String? = { null },
    /**
     * #E130: false while the bound bucket's mirror cannot yet name an existing series card. Resolving an id then would
     * mint a fresh one beside the series the bucket already holds, so the row waits for the mirror instead.
     */
    private val serializedMirrorReady: suspend (series: com.easemic.app.feature.download.SeriesIdentityV2) -> Boolean = { true },
    /**
     * S4 Task 1 (owner bug C4): forwarded verbatim into the production
     * [CloudCollectionIngest] constructed inside the default [runIngest]
     * below — see that class's own `thumbnailer` doc for behavior. Default
     * `{ null }` preserves this runner's pre-fix behavior exactly (raw
     * first-page bytes as the cover, the same as before this seam existed).
     */
    private val thumbnailer: (File) -> ByteArray? = { null },
    /**
     * S4 Task 1 (owner bug C4): forwarded verbatim into the production
     * [CloudCollectionIngest] constructed inside the default [runIngest]
     * below — see that class's own `coverWriter` doc for behavior. Null (the
     * default) preserves this runner's pre-fix behavior exactly (no durable
     * on-device cover path is ever persisted).
     */
    private val coverWriter: ((cloudComicId: String, source: File) -> String?)? = null,
    /**
     * #E118: Forwarded into the production [CloudCollectionIngest] constructed
     * inside [runIngest] so galleries carrying `extraneous ads` strip trailing
     * sponsor ads when a TypeSafe API key is configured.
     */
    private val adStripper: TrailingAdStripper? = null,
    private val typesafeApiKey: () -> String? = { null },
    /**
     * Lives on the constructor so the default [runIngest] body can record a
     * request before the instance exists. Each runner still gets its own map.
     */
    private val serializedUploadRequests: ConcurrentHashMap<String, CloudSeriesChapterUploadRequest> =
        ConcurrentHashMap(),
    /**
     * The actual ingest attempt, injectable so tests can drive the runner's
     * decision shell (store resolution, gate, result -> outcome mapping,
     * queue mutations) without a live `CloudCollectionIngest`. Defaults to
     * the transplanted production body.
     */
    private val runIngest: suspend (
        row: CloudSyncQueueItem,
        store: ObjectStore,
        cloudId: String,
    ) -> CloudCollectionIngestResult = ingestBody@{ row, store, cloudId ->
        val acquisition = row.id
        if (row.chapterIdentity != GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID) {
            val provider = row.providerId?.trim().orEmpty()
            val canonical = row.canonicalItemId?.trim().orEmpty()
            if (provider.isBlank() || canonical.isBlank()) {
                return@ingestBody CloudCollectionIngestResult.Failed("CLOUD_SERIALIZED_IDENTITY_MISSING")
            }
            val seriesIdentity = com.easemic.app.feature.download.SeriesIdentityV2(provider, canonical)
            val seriesId = cloudId.takeIf { it.isNotBlank() }
                ?: return@ingestBody CloudCollectionIngestResult.Failed("CLOUD_SERIALIZED_ID_MISSING")
            val seriesStore = CloudCollectionStore(store)
            // #E21 tail 2: the attempt-start series.json GET. It feeds chapter-key
            // resolution and the ledger's synced-key map. The writer re-reads
            // under the index lock before merging the summary. Not memoised
            // past this attempt.
            val seriesManifest = seriesStore.readSeriesManifestOrNull(seriesId)
            val chapterKey = seriesStore.chapterKeyFor(seriesId, row.chapterIdentity, seriesManifest)
            val checkpoint = cloudIngestChapterWorkDir(workRoot, seriesId, chapterKey)
            val synced = syncedChapterKeysFor(seriesIdentity, seriesManifest)
            val request = serializedUploadRequestFor(row, seriesId, chapterKey, checkpoint, synced)
                ?: return@ingestBody CloudCollectionIngestResult.Failed("CLOUD_SERIALIZED_FACTS_MISSING")
            require(request.series.seriesCloudId == seriesId) { "serialized series cloud id mismatch" }
            require(request.chapter.providerChapterId == row.chapterIdentity) { "serialized chapter identity mismatch" }
            val normalized = request.copy(
                series = request.series.copy(seriesCloudId = seriesId),
                chapter = request.chapter.copy(seriesCloudId = seriesId, chapterKey = chapterKey),
                checkpointWorkDir = checkpoint,
                syncedChapterKeys = synced,
                existingSeriesManifest = seriesManifest,
                // #E111: while sibling chapters of this series are still in the
                // queue (running, waiting, or auto-resuming), hold this
                // chapter's series.json + index rewrite — the batch's last
                // chapter writes them once. The per-chapter rewrites were the
                // request storm that tripped the provider's rate limiter; the
                // chapter's own objects (pages, meta, cover) still upload in
                // full right now, so throughput is unchanged. A tail stranded
                // by a terminally-failed batch self-heals on the series' next
                // write via [syncedChapterKeys] — its meta object is already
                // durable.
                deferTailWhile = {
                    queue.snapshot().any { sibling ->
                        sibling.chapterIdentity != row.chapterIdentity &&
                            sibling.providerId?.trim() == provider &&
                            sibling.canonicalItemId?.trim() == canonical &&
                            siblingChapterWillWriteTail(sibling)
                    }
                },
            )
            val upload = CloudSeriesWriter(seriesStore).upload(normalized)
            serializedUploadRequests[serializedRequestKey(acquisition, row.chapterIdentity)] = normalized
            val meta = CloudComicMeta(
                cloudComicId = seriesId,
                title = normalized.series.title,
                pageCount = normalized.pageFiles.size,
                providerId = normalized.series.providerId,
                acquisitionRef = normalized.series.seriesAcquisitionRef,
                collectedAtEpochMs = normalized.chapter.collectedAtEpochMs,
            )
            return@ingestBody CloudCollectionIngestResult.Synced(
                cloudComicId = seriesId,
                pageCount = normalized.pageFiles.size,
                card = upload.card,
                meta = meta,
                indexGenerations = upload.generations,
            )
        }
        val r2Source = R2SourceAcquisition.parse(acquisition)
        val persistedIdentity = row.providerId
            ?.takeIf { it.isNotBlank() }
            ?.let { namespace ->
                row.canonicalItemId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { canonical -> GalleryIdentityV1(namespace, canonical) }
            }
        val indexId = if (r2Source == null) BrowseFeedRepository.parseNhentaiAcquisition(acquisition) else null
        val frontRef = if (r2Source == null) BrowseFeedRepository.parseEhentaiAcquisition(acquisition) else null
        // An r2source row is always sealed-local-only by construction (slice
        // C2 migration reads pages from the OLD bucket, never a live
        // upstream plan) — never gated by isSealedDownloadJob, which only
        // knows about Front/Index download jobs.
        // A complete persisted identity is an upload-only handoff boundary.
        // It is deliberately independent of the current job-state lookup:
        // a missing/stale DownloadJob must never make an opaque OPDS row fall
        // through to direct HTTP (which cannot safely reconstruct its source).
        // Resolve the job state even though the persisted identity itself is
        // sufficient to establish the upload-only boundary. This keeps the
        // identity seam observable for production diagnostics and allows
        // callers to distinguish a current SEALED/FINISH job from a stale
        // queue row without ever permitting an unsafe HTTP fallback.
        val identityJobSealed = persistedIdentity?.let { isSealedDownloadJobForIdentity(it) } ?: false
        val sealedBacked = r2Source != null ||
            persistedIdentity != null ||
            identityJobSealed ||
            isSealedDownloadJob(indexId, frontRef?.first)
        // C2 audit MEDIUM-1 + #C20 audit MEDIUM-1: hold the staging lease for
        // the whole attempt loop (see [retainSealedPages]); a FINISH commit
        // meanwhile defers its reclaim until this closes. Taken BEFORE the pages
        // are resolved — both hooks do their own `jobFor(identity)` read, so a
        // FINISH commit landing between "resolve" and "lease" used to leave the
        // ingest holding staging paths with no lease at all, and C2's reclaim
        // then unlinked them under the running upload.
        val stagingLease = if (sealedBacked) {
            if (row.chapterIdentity != GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID && persistedIdentity != null) {
                retainSerializedSealedPages(persistedIdentity, row.chapterIdentity)
            } else {
                retainSealedPages(persistedIdentity, r2Source, indexId, frontRef?.first)
            }
        } else null
        try {
        val localPages = if (r2Source != null) {
            r2SourceLocalPages(r2Source.first, r2Source.second)
        } else if (persistedIdentity != null) {
            localPageFilesForIdentity(persistedIdentity)
        } else {
            localPageFiles(indexId, frontRef?.first)
        }
        if (sealedBacked && localPages == null) {
            // Upload-only guard (spec §1.1/§1.2): a SEALED cloud-intent row's
            // pages live on device already — a null result here means the
            // fetch pipeline sealed without leaving pages behind, a bug/race,
            // not something to paper over by silently hitting upstream.
            // Inside the `try`, so this early return still runs the `finally`
            // that releases the lease taken above.
            return@ingestBody CloudCollectionIngestResult.Failed("CLOUD_INGEST_PAGES_MISSING_POST_SEAL")
        }
        // #C30: the ordinary sealed-local upload used to pass null here, so
        // meta.json was written with EMPTY tags/artists/groups/category/language
        // although the enqueue had recorded them in the locator. Migration
        // (r2Source) keeps the OLD bucket's meta — it is the richer record.
        val preservedMeta = when {
            r2Source != null -> r2SourceOldMeta(r2Source.first, r2Source.second)
            // sealedBacked is implied: a persisted identity sets it (see the sealedBacked definition above)
            persistedIdentity != null -> catalogFactsForIdentity(persistedIdentity)?.let { locator ->
                locatorCloudMeta(
                    cloudComicId = cloudId,
                    providerId = row.providerId.orEmpty(),
                    acquisitionUrl = acquisition,
                    fallbackTitle = row.title,
                    locator = locator,
                )
            }
            // Review LOW-1: this arm is the pre-#C30 empty meta, and it stays
            // because no live path reaches it WITH pages to upload. It needs a
            // row with no persisted identity yet `sealedBacked` true, i.e.
            // isSealedDownloadJob(indexId, frontRef) true — which requires the
            // acquisition to parse as nhentai:// or ehentai://. Every
            // identity-less row comes from CollectCloudIngestRoute.LegacyDirectUpload
            // (CollectCloudIngestRouting.kt:94-99, taken only when
            // BrowseGalleryIdentity.resolve returns Unavailable), and
            // BrowseGalleryIdentity.resolveAcquisition RESOLVES every
            // ehentai:///nhentai:// acquisition before it can get there — so the
            // two conditions are mutually exclusive. (A legacy queue row
            // persisted by a build older than the identity columns would land
            // here and degrade exactly as it did pre-#C30: honest empty meta,
            // never wrong meta.)
            else -> null
        }
        // `coroutineContext` is a suspend-only intrinsic; capture it here (this
        // lambda is itself `suspend`) so the non-suspend `shouldAbort` callback
        // below can read `.isActive` off the captured value instead of the bare
        // (scope-receiver-only) property.
        val activeContext = coroutineContext
        runCloudIngestWithAutoResume(
            ingestOnce = {
                val ingest = CloudCollectionIngest(
                    storeFactory = { CloudCollectionStore(store) },
                    frontClientFactory = frontClientFactory,
                    workRoot = workRoot,
                    thumbnailer = thumbnailer,
                    coverWriter = coverWriter,
                    adStripper = adStripper,
                    typesafeApiKey = typesafeApiKey,
                    progress = object : CloudCollectionIngestProgress {
                        override fun onFetch(ready: Int, expected: Int, deltaBytes: Int) {
                            queue.onProgress(
                                id = acquisition,
                                stage = CloudSyncStage.FETCHING,
                                readyPages = ready,
                                expectedPages = expected,
                                deltaBytes = deltaBytes,
                                nowMs = System.currentTimeMillis(),
                            chapterIdentity = row.chapterIdentity,
                            )
                        }

                        // C36: the fetch→upload boundary. The row flips to
                        // UPLOADING carrying the REAL uploaded count (0, or the
                        // checkpoint's count on resume) before thumbnail prep,
                        // hashing and the bucket listing — instead of sitting on
                        // the fetch count until the first PUT completes.
                        override fun onUploadStarting(uploadedSoFar: Int, expected: Int) {
                            queue.onProgress(
                                id = acquisition,
                                stage = CloudSyncStage.UPLOADING,
                                readyPages = uploadedSoFar,
                                expectedPages = expected,
                                deltaBytes = 0,
                                nowMs = System.currentTimeMillis(),
                            chapterIdentity = row.chapterIdentity,
                            )
                        }

                        // #C46 D1: the ordinal-ordered count no longer drives the
                        // row — it froze the counter behind the head-of-line page
                        // (root cause §2). onUploadProgress does.
                        override fun onUpload(ready: Int, expected: Int, deltaBytes: Int) = Unit

                        override fun onUploadProgress(event: CloudUploadProgress) {
                            applyUploadProgress(
                                queue,
                                acquisition,
                                event,
                                nowMs = System.currentTimeMillis(),
                                chapterIdentity = row.chapterIdentity,
                            )
                        }
                    },
                )
                ingest.ingest(
                    CloudCollectionIngestRequest(
                        cloudComicId = cloudId,
                        titleHint = row.title,
                        providerId = row.providerId.orEmpty(),
                        acquisitionUrl = acquisition,
                        indexGalleryId = indexId,
                        frontGalleryId = frontRef?.first,
                        frontToken = frontRef?.second,
                        localPageFiles = localPages,
                        sealedLocalOnly = sealedBacked,
                        truncatedFetchedPages = if (r2Source == null && sealedBacked) {
                            // A persisted opaque identity is authoritative.
                            // A null result is a valid "complete download"
                            // value, not permission to reinterpret the row
                            // through the legacy Front/Index URL parser.
                            if (persistedIdentity != null) {
                                truncatedFetchedPagesForIdentity(persistedIdentity)
                            } else {
                                truncatedFetchedPagesFor(indexId, frontRef?.first)
                            }
                        } else {
                            null
                        },
                        preservedMeta = preservedMeta,
                    ),
                    shouldAbort = { !activeContext.isActive },
                )
            },
            onTransientFailure = { attempt, message ->
                // #C52 round 3 H5: message can embed the acquisition URL (an
                // OPDS one carries the catalog's auth key in its path); redact once
                // and reuse for both the log line and the persisted row.
                val safeMessage = OpdsCatalogClient.redactUrl(message)
                DiagLog.w(
                    "CloudSync",
                    "ingest failed id=${redactAcquisition(acquisition)} attempt=$attempt/${CloudSyncIngestAutoRetry.MAX_ATTEMPTS} msg=$safeMessage",
                )
                queue.applyIngestFinish(acquisition, CloudSyncIngestFinish.Failed(safeMessage), chapterIdentity = row.chapterIdentity)
            },
            onResume = { attempt ->
                val prior = queue.get(acquisition, row.chapterIdentity)
                queue.onProgress(
                    id = acquisition,
                    stage = CloudSyncStage.WAITING,
                    readyPages = prior?.readyPages ?: 0,
                    expectedPages = prior?.expectedPages,
                    deltaBytes = 0,
                    nowMs = System.currentTimeMillis(),
                chapterIdentity = row.chapterIdentity,
                )
                DiagLog.i(
                    "CloudSync",
                    "ingest auto-resume id=${redactAcquisition(acquisition)} afterAttempt=$attempt ready=${prior?.readyPages}",
                )
            },
            onRateLimited = { seconds ->
                // C37-f: publish the window to every other pump slot before
                // this one sits it out, and park the row WAITING (not FAILED —
                // nothing about this comic went wrong, and no attempt is spent).
                DiagLog.w("CloudSync", "ingest rate-limited id=${redactAcquisition(acquisition)} retryAfter=${seconds}s")
                rateLimitPause.pauseFor(seconds, nowMs())
                val prior = queue.get(acquisition, row.chapterIdentity)
                queue.onProgress(
                    id = acquisition,
                    stage = CloudSyncStage.WAITING,
                    readyPages = prior?.readyPages ?: 0,
                    expectedPages = prior?.expectedPages,
                    deltaBytes = 0,
                    nowMs = nowMs(),
                chapterIdentity = row.chapterIdentity,
                )
                // C35-c: after the onProgress above, which clears any older
                // stamp — the countdown is what makes this pause legible.
                queue.markPausedUntil(acquisition, nowMs() + rateLimitPause.remainingMs(nowMs()), chapterIdentity = row.chapterIdentity)
            },
            delayMs = { ms -> delayMs(ms) },
        )
        } finally {
            stagingLease?.close()
        }
    },
) {
    /**
     * Stage truth on pickup (spec §1.5): a force-stop can redeliver this row
     * to a fresh worker while the durable upload checkpoint ([workRoot] pages
     * survive process death — see [readCloudIngestCheckpoint]) already
     * recorded pages uploaded during a prior attempt. Without this the row
     * would keep showing whatever it last persisted (commonly a stale 0/N —
     * [CloudSyncQueue.publishLocked] deliberately does not durably persist
     * every readyPages tick) until the first new [CloudCollectionIngestProgress]
     * callback fires. Seeds [row]'s readyPages from the checkpoint truth
     * before [runIngest] does any new work; a no-op when there is no
     * checkpoint yet or its uploadedCount does not exceed what [row] already
     * shows.
     */
    private fun seedProgressFromUploadCheckpoint(row: CloudSyncQueueItem) {
        // Serialized rows have a chapter-scoped checkpoint. The chapter key is
        // available only after opening the bucket manifest, so their progress
        // is seeded by the writer's upload callback instead of this gallery
        // preflight helper.
        if (row.chapterIdentity != GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID) return
        val cloudId = cloudComicIdFor(row.id)
        val uploaded = readCloudIngestCheckpoint(cloudIngestWorkDir(workRoot, cloudId))?.uploadedCount ?: return
        if (uploaded <= row.readyPages) return
        queue.onProgress(
            id = row.id,
            stage = row.stage,
            readyPages = uploaded,
            expectedPages = row.expectedPages,
            deltaBytes = 0,
            nowMs = System.currentTimeMillis(),
        chapterIdentity = row.chapterIdentity,
        )
    }

    /**
     * Gallery rows use [isKnownSynced] (acquisition dedup key). Serialized rows
     * use [isSerializedChapterSynced] (series ledger key plus this chapter id),
     * the predicate the release sweep trusts. A series-level hit must not
     * retire a sibling chapter the ledger does not have.
     */
    private suspend fun rowAlreadySynced(row: CloudSyncQueueItem): Boolean {
        if (row.chapterIdentity == GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID) {
            return isKnownSynced(row.id)
        }
        val provider = row.providerId?.trim().orEmpty()
        val canonical = row.canonicalItemId?.trim().orEmpty()
        if (provider.isBlank() || canonical.isBlank()) return false
        return isSerializedChapterSynced(provider, canonical, row.chapterIdentity)
    }

    /** Runs one queue row to completion (with the existing checkpoint auto-resume). */
    suspend fun run(row: CloudSyncQueueItem): CloudIngestOutcome {
        val acquisition = row.id
        // S3 Task 6 (owner bug 2026-09-04): defense-in-depth alongside the
        // pump's own presence guard — if a zombie row for an already-synced
        // comic ever reaches run() anyway, refuse to re-upload it and remove
        // the row instead of driving it through FAILED->WAITING/runIngest.
        if (rowAlreadySynced(row)) {
            queue.remove(acquisition, chapterIdentity = row.chapterIdentity)
            return CloudIngestOutcome.AlreadySynced
        }
        // Owner bug A3/C5 fix (T3c): a FAILED row picked up here (by the
        // pump's bounded auto-resume — see nextRunnableCloudSyncRow) must be
        // driven back to WAITING via the existing progress path BEFORE any
        // new attempt runs, so it never sits showing a stale FAILED
        // stage/errorMessage while genuinely mid-retry, and runIngest never
        // sees a row claiming to already be FAILED. onProgress deliberately
        // does not reset failedAttempts (see CloudSyncQueue.markFailed), so
        // the bounded-retry cap still applies across pickups.
        if (row.stage == CloudSyncStage.FAILED) {
            queue.onProgress(
                id = acquisition,
                stage = CloudSyncStage.WAITING,
                readyPages = row.readyPages,
                expectedPages = row.expectedPages,
                deltaBytes = 0,
                nowMs = System.currentTimeMillis(),
            chapterIdentity = row.chapterIdentity,
            )
        }
        val effectiveRow = queue.get(acquisition, row.chapterIdentity) ?: row
        seedProgressFromUploadCheckpoint(effectiveRow)
        // C37-f: another slot (or an earlier attempt on this one) met a 429,
        // and Cloudflare's block is account-wide — sit the remaining window out
        // before spending anything on this row. Above resolveStore() on
        // purpose: a token exchange is itself an account request, so resolving
        // first would poke the very wall we are waiting on.
        rateLimitPause.remainingMs(nowMs()).takeIf { it > 0 }?.let { remaining ->
            // C35-c: stamp the row BEFORE sleeping it. C37-f slept this window
            // with no row mutation at all, so Activity froze at the row's last
            // count for the whole pause with nothing saying it was waiting on a
            // clock rather than stuck — the owner read it as "进度不变化". A
            // stamp written after the sleep would only become visible once the
            // wait everyone was staring at had already ended. The next
            // onProgress / finish clears it (see CloudSyncQueue.markPausedUntil).
            queue.markPausedUntil(acquisition, nowMs() + remaining, chapterIdentity = row.chapterIdentity)
            delayMs(remaining)
        }
        // A28 (coordinator review 2026-09-05, M1): captured BEFORE
        // resolveStore() runs, not after it returns — resolveStore() itself
        // can straddle a rebind (S3: between the device store save and the
        // binding-cache write; OAuth: the whole refresh round trip), so
        // reading the fingerprint only afterward could already reflect the
        // NEW bucket even though this upload is about to be driven through
        // whatever `store` resolves to. A rebind that lands during
        // resolution is instead caught by the completion-time comparison in
        // the Synced branch below (M2) — fail-safe side is skipping
        // bookkeeping and keeping local pages, never the reverse.
        val uploadedToFingerprint = currentBucketFingerprint()
        var store = resolveStore()
        if (store == null) {
            if (isBindingDead()) {
                DiagLog.w("CloudSync", "ingest blocked: OAuth grant dead id=${redactAcquisition(acquisition)}")
                // Exhaust the auto-resume budget so the pump never re-drives this row;
                // AppGraph.onBucketBound revives rows carrying this exact copy on reconnect.
                // ONE durable write (C37-c review F2): the old
                // `repeat(MAX_ATTEMPTS) { markFailed(...) }` re-serialised the
                // whole queue five times per row, because failedAttempts is part
                // of the publish fingerprint.
                queue.markFailedTerminal(acquisition, ByoCloudIoCopy.RECONNECT, chapterIdentity = row.chapterIdentity)
                return CloudIngestOutcome.Blocked(ByoCloudIoCopy.RECONNECT)
            }
            if (hasBinding()) {
                // #C46 D3: a binding exists but the store is not ready — on a
                // cold start this is the OAuth bootstrap still running when the
                // pump's ~2 s kick lands. Wait in place (≤ 3.5 s) instead of
                // deferring the row to the next pump pass minutes away.
                var waitedMs = 0L
                for ((attempt, waitMs) in STORE_RETRY_WAITS_MS.withIndex()) {
                    delayMs(waitMs)
                    waitedMs += waitMs
                    store = resolveStore()
                    if (store != null) {
                        DiagLog.i("CloudSync", "store ready after retry n=${attempt + 1} waitMs=$waitedMs id=${redactAcquisition(acquisition)}")
                        break
                    }
                }
            }
        }
        if (store == null) {
            if (hasBinding()) {
                // T3a: a binding IS persisted on this device — this is a
                // transient store-resolution failure (e.g. a momentary
                // OAuth-refresh network hiccup right after app reopen), not
                // proof the user never connected cloud storage. Leave the
                // row WAITING (never markFailed here) so
                // nextRunnableCloudSyncRow re-drives it on the pump's next
                // pass instead of a terminal FAILED with the misleading
                // BIND copy.
                DiagLog.w("CloudSync", "ingest deferred: store transiently unavailable id=${redactAcquisition(acquisition)}")
                return CloudIngestOutcome.Deferred(ByoCloudIoCopy.STORE_TEMPORARILY_UNAVAILABLE)
            }
            // A27 (owner bug backlog, ledger 2026-09-05): no binding exists
            // on this device at all -- do NOT markFailed. A terminal FAILED
            // here burns a failedAttempts and shows the Activity tab a
            // misleading "retrying" line for a row that nothing will ever
            // retry on its own; the row simply parks WAITING until the user
            // binds a bucket, at which point AppGraph.onBucketBound revives
            // it and re-runs repairCloudHandoffs.
            DiagLog.i("CloudSync", "ingest parked: no cloud storage bound id=${redactAcquisition(acquisition)}")
            return CloudIngestOutcome.Unbound(ByoCloudIoCopy.BIND)
        }
        if (effectiveRow.chapterIdentity != GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID) {
            val provider = effectiveRow.providerId?.trim().orEmpty()
            val canonical = effectiveRow.canonicalItemId?.trim().orEmpty()
            if (provider.isNotBlank() && canonical.isNotBlank() &&
                !serializedMirrorReady(com.easemic.app.feature.download.SeriesIdentityV2(provider, canonical))
            ) {
                DiagLog.i("CloudSync", "ingest deferred: cloud mirror still loading id=${redactAcquisition(acquisition)}")
                return CloudIngestOutcome.Deferred(CLOUD_MIRROR_LOADING)
            }
        }
        // #C66: the ledger's own [isKnownSynced] guard above can be blind
        // right after a rebind — AppGraph.onBucketBound revives queued rows
        // immediately, but the new bucket's ledger is only backfilled once
        // hydration completes, a minute or more later. Ask the bound
        // bucket's own current index (the same fact hydration will later
        // backfill from) before driving a real upload attempt. Transient
        // failures (including the no-op default returning null) fall straight
        // through to today's behavior; a newer-format verdict blocks before
        // any upload can start.
        val alreadyCardedCloudId = try {
            if (row.chapterIdentity == GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID) {
                presentInCurrentIndex(acquisition)
            } else {
                null
            }
        } catch (_: NewerCloudFormatException) {
            queue.markFailedTerminal(acquisition, NewerCloudFormatException.MESSAGE, chapterIdentity = row.chapterIdentity)
            return CloudIngestOutcome.Blocked(NewerCloudFormatException.MESSAGE)
        } catch (_: Exception) {
            null
        }
        if (alreadyCardedCloudId != null) {
            recordSynced(acquisition, alreadyCardedCloudId)
            DiagLog.i(
                "CloudSync",
                "upload skipped: already in the bound bucket's index id=${redactAcquisition(acquisition)}",
            )
            queue.remove(acquisition, chapterIdentity = row.chapterIdentity)
            return CloudIngestOutcome.AlreadySynced
        }
        return try {
            val result = gate.withPermit(acquisition) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val cloudId = if (effectiveRow.chapterIdentity != GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID) {
                            val provider = effectiveRow.providerId?.trim().orEmpty()
                            val canonical = effectiveRow.canonicalItemId?.trim().orEmpty()
                            if (provider.isBlank() || canonical.isBlank()) {
                                throw IllegalStateException("CLOUD_SERIALIZED_ID_MISSING")
                            }
                            serializedCloudIdFor(
                                com.easemic.app.feature.download.SeriesIdentityV2(provider, canonical),
                            ) ?: throw IllegalStateException("CLOUD_SERIALIZED_ID_MISSING")
                        } else {
                            cloudComicIdFor(acquisition)
                        }
                        registerJob(cloudId)
                        runIngest(effectiveRow, store, cloudId)
                    }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        // Serialized GET/request/PUT failures bypass the gallery
                        // auto-resume loop. Keep Retry-After for the shared pause.
                        if (error is CloudRateLimitedException) {
                            return@getOrElse CloudCollectionIngestResult.RateLimited(error.retryAfterSeconds)
                        }
                        // Fail closed: let the typed handler below surface the
                        // locked/newer-format copy.
                        if (error is NewerCloudFormatException ||
                            error is UnreadableCloudLibraryException ||
                            error is LibraryLockedException ||
                            error is DamagedLibraryLockException ||
                            error is StaleCloudBindingException
                        ) {
                            throw error
                        }
                        CloudCollectionIngestResult.Failed(error.message ?: "CLOUD_INGEST_FAILED")
                    }
                }
            }
            if (!coroutineContext.isActive) {
                // C35-a: the pump was stopped/replaced while the ingest ran.
                // applyIngestFinish(Cancelled) parks the row WAITING with its
                // real page count (it used to delete it), so the next pump
                // resumes from the durable checkpoint.
                queue.applyIngestFinish(acquisition, CloudSyncIngestFinish.Cancelled, chapterIdentity = row.chapterIdentity)
                return CloudIngestOutcome.Cancelled
            }
            when (result) {
                is CloudCollectionIngestResult.Synced -> {
                    val currentFingerprintAtCompletion = currentBucketFingerprint()
                    // A28 (M2): only enforce the match once a starting
                    // fingerprint was actually captured — most callers
                    // (including most of this class's own tests) never wire
                    // fingerprint tracking at all (currentBucketFingerprint
                    // defaults to `{ null }`) and must keep behaving exactly
                    // as before. Once a real starting fingerprint IS
                    // captured, any drift by completion time — including the
                    // bucket becoming fully unbound — fails closed via
                    // uploadStillTargetsBoundBucket.
                    val bucketStillMatches = uploadedToFingerprint == null ||
                        uploadStillTargetsBoundBucket(uploadedToFingerprint, currentFingerprintAtCompletion)
                    if (bucketStillMatches) {
                        // #C52 round 3 Task 8 (H5 completion): onSynced runs
                        // BEFORE applyIngestFinish(Synced) removes the row.
                        // onSynced can throw (e.g. a Room failure durably
                        // writing the ledger — see AppGraph's onSynced KDoc:
                        // "the runner marks the row FAILED and the pump
                        // re-drives it"); the outer catch below is what
                        // redacts and persists that FAILED message, and it
                        // can only do that if the row still exists. Removing
                        // the row first (the prior order) made a crashed
                        // onSynced throw straight past a row that had
                        // already vanished — the exact "vanished Failed row"
                        // regression CloudSyncIngestFinish's own KDoc warns
                        // against, just reached from a different order.
                        //
                        // #C52 round 4 (re-review R5), the two consequences
                        // of keeping the row alive across onSynced:
                        //
                        // (1) THE RE-DRIVE IS IDEMPOTENT. A throw (or a
                        // CancellationException — a worker stop landing
                        // inside onSynced now parks the row WAITING instead
                        // of finding it already gone) hands the pump a row
                        // whose comic IS already in the bucket. Re-running
                        // it is cheap and safe, not a second upload:
                        // CloudCollectionStore.uploadComicArchiveWithIndex
                        // decides what to PUT from the bucket's own presence
                        // LIST, by content hash —
                        // `presentHashes` is built from the listing
                        // (CloudCollectionStore.kt:303-306) and
                        // `needsPut = BooleanArray(...) { presentHashes.add(...) }`
                        // (CloudCollectionStore.kt:312) is false for every
                        // page the bucket already has — and the index write
                        // is an upsert keyed on cloudComicId
                        // (`listOf(card) + existingCards.filterNot { it.cloudComicId == ... }`,
                        // CloudCollectionStore.kt:807), so re-publishing
                        // produces the same one card, never a duplicate.
                        //
                        // (2) THE DETAIL DOWNLOAD SWITCH STAYS LOCKED across
                        // onSynced, i.e. across the local-page release
                        // (`cloudUploadInFlight`, PrivateComicLibraryApp.kt).
                        // That is a STRICT IMPROVEMENT over #C4's window,
                        // where a delete landing mid-release could lose both
                        // copies: the lock now spans the release as well as
                        // the upload.
                        //
                        // #C52 round 5 (round-4 review MED-3), the two bounds
                        // (1) does NOT supply:
                        //
                        // (3) A CANCELLATION LANDING AFTER THE LOCAL PAGES
                        // ARE ALREADY GONE. onSynced's last act is
                        // `cloudHostedLocalRelease.afterConfirmedUpload`
                        // (AppGraph.kt:1751), which DELETES this comic's
                        // local pages — so (1)'s presence-LIST argument,
                        // which assumes a re-drive can still read local
                        // files, says nothing about a cancel that lands
                        // after that point. What does bound it is ORDERING
                        // plus the PICKUP GUARD: AppGraph writes the durable
                        // ledger row and then PROVES it by reading it back
                        // (`ledgerWritten = cloudSyncLedgerStore.isSynced(fp,
                        // acquisitionKey)`, AppGraph.kt:1722-1729) BEFORE the
                        // release, and the release itself returns
                        // LedgerNotWritten without touching a file unless
                        // that flag is true (CloudHostedLocalRelease.kt:103-106).
                        // Local pages can therefore only be gone once the
                        // ledger already says "synced" — and run()'s very
                        // first act is `if (isKnownSynced(acquisition)) {
                        // queue.remove(acquisition, chapterIdentity = row.chapterIdentity); return AlreadySynced }`
                        // (this file, :558-561). A row parked by a
                        // cancellation after the release is REMOVED at the
                        // next pickup: never re-uploaded, and never asked for
                        // pages that no longer exist. Pinned by
                        // CloudIngestRunnerTest's
                        // aCancellationAfterTheLocalReleaseIsRemovedAtTheNextPickupNotReUploaded.
                        //
                        // (4) THE LIBRARY-LOCK FAMILY CANNOT COME FROM HERE.
                        // The catch below routes UnreadableCloudLibraryException /
                        // LibraryLockedException / DamagedLibraryLockException
                        // to Blocked rather than FAILED; none of the three is
                        // reachable from onSynced, which touches only Room
                        // (the cloud mirror store and the sync ledger), the
                        // migration record and the local-page file release.
                        // All three are raised by the object-store side during
                        // ingest — CloudCollectionStore.kt:1331,
                        // CloudLibraryGate.kt:138 and LibraryKeyslot.kt:100,
                        // :127, :130, :135, :137, :148 are the only throw
                        // sites in app/src/main.
                        if (result.card != null && result.meta != null) {
                            if (effectiveRow.chapterIdentity != GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID) {
                                serializedUploadRequests.remove(
                                    serializedRequestKey(acquisition, effectiveRow.chapterIdentity),
                                )?.let { request ->
                                    onSerializedSynced(
                                        request,
                                        CloudArchiveUpload(result.card, result.indexGenerations ?: CloudIndexGenerations(null, 0, 0)),
                                        acquisition,
                                        result.indexGenerations,
                                        uploadedToFingerprint,
                                    )
                                    // #E80: drop this chapter's durable upload workdir (packs/ + checkpoint.json) once its ledger write landed.
                                    request.checkpointWorkDir?.let { workDir ->
                                        try {
                                            workDir.deleteRecursively()
                                        } catch (_: IOException) {
                                        } catch (_: SecurityException) {
                                        }
                                    }
                                }
                            } else {
                                onSynced(
                                    result.card,
                                    result.meta,
                                    acquisition,
                                    result.indexGenerations,
                                    uploadedToFingerprint,
                                    effectiveRow.providerId,
                                    effectiveRow.canonicalItemId,
                                )
                            }
                        }
                        queue.applyIngestFinish(acquisition, CloudSyncIngestFinish.Synced, chapterIdentity = row.chapterIdentity)
                        CloudIngestOutcome.Synced(result.pageCount)
                    } else {
                        // A28 (coordinator review 2026-09-05, M2): the bucket
                        // bound at completion no longer matches the one this
                        // upload actually targeted. Do NOT
                        // applyIngestFinish(Synced) — that would remove the
                        // row as if it landed in the CURRENTLY bound bucket
                        // — and do NOT call onSynced (AppGraph's own
                        // uploadStillTargetsBoundBucket guard there is a
                        // second line of defense, not the only one). Park
                        // the row FAILED with backoff so
                        // nextRunnableCloudSyncRow re-drives it against
                        // whichever bucket ends up bound, instead of the
                        // collect silently vanishing from Activity/shelf.
                        DiagLog.w(
                            "CloudSync",
                            "ingest completed but bucket changed mid-upload id=${redactAcquisition(acquisition)} " +
                                "uploadedTo=$uploadedToFingerprint current=$currentFingerprintAtCompletion",
                        )
                        queue.markFailed(acquisition, CLOUD_CHANGED_DURING_UPLOAD_MESSAGE, chapterIdentity = row.chapterIdentity)
                        CloudIngestOutcome.Failed(CLOUD_CHANGED_DURING_UPLOAD_MESSAGE)
                    }
                }
                is CloudCollectionIngestResult.RateLimited -> {
                    // Serialized uploads return here on a typed 429; galleries
                    // normally absorb it inside runCloudIngestWithAutoResume.
                    // Same contract either way — publish the account-wide
                    // window, park the row WAITING with no attempt spent, and
                    // let the pump re-drive it once the window clears.
                    DiagLog.w(
                        "CloudSync",
                        "ingest rate-limited id=${redactAcquisition(acquisition)} retryAfter=${result.retryAfterSeconds}s",
                    )
                    rateLimitPause.pauseFor(result.retryAfterSeconds, nowMs())
                    val prior = queue.get(acquisition, row.chapterIdentity)
                    queue.onProgress(
                        id = acquisition,
                        stage = CloudSyncStage.WAITING,
                        readyPages = prior?.readyPages ?: 0,
                        expectedPages = prior?.expectedPages,
                        deltaBytes = 0,
                        nowMs = nowMs(),
                    chapterIdentity = row.chapterIdentity,
                    )
                    // C35-c: after the onProgress above, which clears any older
                    // stamp. This slot is handing the row back to the pump, so
                    // the window is all the row has left to say.
                    queue.markPausedUntil(acquisition, nowMs() + rateLimitPause.remainingMs(nowMs()), chapterIdentity = row.chapterIdentity)
                    CloudIngestOutcome.Deferred("CLOUD_HTTP_429")
                }
                is CloudCollectionIngestResult.Failed -> {
                    if (result.message == CloudCollectionIngest.CANCELLED) {
                        // C35-a: the ingest noticed the cancellation itself.
                        // Same contract as the catch below — an interruption
                        // parks the row resumable, it does not abandon the
                        // comic. Only the user's own Cancel removes a row.
                        queue.applyIngestFinish(acquisition, CloudSyncIngestFinish.Cancelled, chapterIdentity = row.chapterIdentity)
                        CloudIngestOutcome.Cancelled
                    } else if (isBindingDead()) {
                        // B1 (audit): the grant died DURING this upload (store was
                        // alive when run() resolved it). Park terminal with the
                        // reconnect copy so onBucketBound revives it on Connect —
                        // exactly like a row whose store was already null at the top.
                        DiagLog.w("CloudSync", "ingest failed on a dead grant id=${redactAcquisition(acquisition)} — parking terminal reconnect")
                        queue.markFailedTerminal(acquisition, ByoCloudIoCopy.RECONNECT, chapterIdentity = row.chapterIdentity)
                        CloudIngestOutcome.Blocked(ByoCloudIoCopy.RECONNECT)
                    } else if (CloudSignIn.rejected(result.message)) {
                        // #D16: a refused sign-in is global like a full drive — every queued comic would ask the
                        // same drive with the same refused password. Park in ONE write; a renewal, a rebind or a
                        // cloud check the drive accepts revives it (onBucketBound / the catch-up sweep).
                        DiagLog.w("CloudSync", "ingest refused: cloud sign-in id=${redactAcquisition(acquisition)} — parking terminal")
                        queue.markFailedTerminal(acquisition, CloudSignIn.KEY, chapterIdentity = row.chapterIdentity)
                        CloudIngestOutcome.Blocked(CloudSignIn.KEY)
                    } else if (result.message == WebDavObjectStore.QUOTA_FULL) {
                        // #C81: a full drive is global like a locked library — every queued comic
                        // hits the same wall. Park terminal in one write (no five fresh uploads per
                        // comic against a full drive); Retry after freeing space, or a rebind, revives it.
                        DiagLog.w("CloudSync", "ingest stopped: cloud storage full id=${redactAcquisition(acquisition)} — parking terminal")
                        queue.markFailedTerminal(acquisition, WebDavObjectStore.QUOTA_FULL, chapterIdentity = row.chapterIdentity)
                        CloudIngestOutcome.Blocked(WebDavObjectStore.QUOTA_FULL)
                    } else {
                        // #C52 round 3 H5: same acquisition-URL-in-message risk as
                        // onTransientFailure above.
                        val safeMessage = OpdsCatalogClient.redactUrl(result.message)
                        DiagLog.w("CloudSync", "ingest failed id=${redactAcquisition(acquisition)} msg=$safeMessage")
                        if (CloudSyncFailurePolicy.requiresInputRepair(safeMessage)) {
                            queue.markFailedTerminal(acquisition, safeMessage, chapterIdentity = row.chapterIdentity)
                        } else {
                            queue.applyIngestFinish(acquisition, CloudSyncIngestFinish.Failed(safeMessage), chapterIdentity = row.chapterIdentity)
                        }
                        CloudIngestOutcome.Failed(safeMessage)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // C35-a: a worker stop (A14) or a REPLACE-ing user kick lands
            // here. This used to remove(id) — a half-uploaded comic vanished
            // from Activity and nothing ever resumed it, which is also why
            // REPLACE could not be used to preempt a backoff. It now parks
            // the row WAITING with its checkpoint; the rethrow keeps
            // structured concurrency intact.
            queue.applyIngestFinish(acquisition, CloudSyncIngestFinish.Cancelled, chapterIdentity = row.chapterIdentity)
            throw cancelled
        } catch (_: NewerCloudFormatException) {
            queue.markFailedTerminal(acquisition, NewerCloudFormatException.MESSAGE, chapterIdentity = row.chapterIdentity)
            CloudIngestOutcome.Blocked(NewerCloudFormatException.MESSAGE)
        } catch (_: UnreadableCloudLibraryException) {
            // Fail closed: surface the locked copy verbatim.
            queue.applyIngestFinish(
                acquisition,
                CloudSyncIngestFinish.Failed(UnreadableCloudLibraryException.MESSAGE),
                chapterIdentity = row.chapterIdentity,
            )
            CloudIngestOutcome.Blocked(UnreadableCloudLibraryException.MESSAGE)
        } catch (_: LibraryLockedException) {
            // #C63: a locked library is as global as a dead OAuth grant — no
            // OTHER queued row can open the library either, so every one of
            // them would hit this same exception in turn. markFailedTerminal
            // (ONE write, like the RECONNECT arm above) parks the row where a
            // fully-spent retry budget would leave it instead of burning
            // failedAttempts one queued row at a time (cap 5, ~12 min to
            // exhaust); AppGraph.onBucketBound revives it once unlocked.
            queue.markFailedTerminal(acquisition, LibraryLockedException.MESSAGE, chapterIdentity = row.chapterIdentity)
            CloudIngestOutcome.Blocked(LibraryLockedException.MESSAGE)
        } catch (_: DamagedLibraryLockException) {
            queue.applyIngestFinish(
                acquisition,
                CloudSyncIngestFinish.Failed(DamagedLibraryLockException.MESSAGE),
                chapterIdentity = row.chapterIdentity,
            )
            CloudIngestOutcome.Blocked(DamagedLibraryLockException.MESSAGE)
        } catch (_: StaleCloudBindingException) {
            // #C64: the bound bucket changed under this upload. Park the row
            // WAITING with its checkpoint, like a worker stop; the next claim
            // resolves a store for the bucket that is bound now.
            queue.applyIngestFinish(acquisition, CloudSyncIngestFinish.Cancelled, chapterIdentity = row.chapterIdentity)
            CloudIngestOutcome.Cancelled
        } catch (error: Exception) {
            if (CloudSignIn.rejected(error)) {
                DiagLog.w("CloudSync", "ingest refused (thrown): cloud sign-in id=${redactAcquisition(acquisition)} — parking terminal")
                queue.markFailedTerminal(acquisition, CloudSignIn.KEY, chapterIdentity = row.chapterIdentity)
                return CloudIngestOutcome.Blocked(CloudSignIn.KEY)
            }
            // #C52 round 3 H5: error.message can embed the acquisition URL
            // (e.g. a MalformedURLException); redact before it reaches the
            // log line or the persisted row.
            val message = OpdsCatalogClient.redactUrl(error.message ?: "CLOUD_INGEST_FAILED")
            DiagLog.w("CloudSync", "ingest crashed id=${redactAcquisition(acquisition)} msg=$message")
            queue.applyIngestFinish(acquisition, CloudSyncIngestFinish.Failed(message), chapterIdentity = row.chapterIdentity)
            CloudIngestOutcome.Failed(message)
        }
    }

    companion object {
        /** #E130: a serialized upload waiting for the bound bucket's mirror to load its series cards. */
        const val CLOUD_MIRROR_LOADING = "CLOUD_MIRROR_LOADING"
        /** #C46 D3: in-place store-resolution retries on a cold start, ≤ 3.5 s total. */
        internal val STORE_RETRY_WAITS_MS: List<Long> = listOf(500L, 1_000L, 2_000L)
    }
}

/**
 * #C46 D1: a [CloudUploadProgress] event onto the queue row. `Page` carries
 * the completion count; `Finishing` pins the counter at the page total and
 * raises the row's finishing flag so the Activity tab names the tail.
 */
internal fun applyUploadProgress(
    queue: CloudSyncQueue,
    id: String,
    event: CloudUploadProgress,
    nowMs: Long,
    chapterIdentity: String? = null,
) {
    when (event) {
        is CloudUploadProgress.Page -> queue.onProgress(
            id = id,
            stage = CloudSyncStage.UPLOADING,
            readyPages = event.done,
            expectedPages = event.total,
            deltaBytes = event.bytes,
            nowMs = nowMs,
            chapterIdentity = chapterIdentity,
        )
        is CloudUploadProgress.Finishing -> {
            val expected = queue.get(id, chapterIdentity ?: GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID)?.expectedPages
            queue.onProgress(
                id = id,
                stage = CloudSyncStage.UPLOADING,
                readyPages = expected ?: queue.get(id, chapterIdentity ?: GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID)?.readyPages ?: 0,
                expectedPages = expected,
                deltaBytes = 0,
                nowMs = nowMs,
                finishing = true,
                chapterIdentity = chapterIdentity,
            )
        }
    }
}
