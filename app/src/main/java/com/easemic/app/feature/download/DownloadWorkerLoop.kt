package com.easemic.app.feature.download

import com.easemic.app.sync.DiagLog
import com.easemic.app.sync.redactIdentity
import com.easemic.app.importing.opds.OpdsCatalogClient
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Job-store surface used by [DownloadWorkerLoop]. Implemented by
 * [InMemoryDownloadWorkerHost] (JVM / DownloadCore) and
 * [RoomDownloadWorkerHost] (production).
 */
interface DownloadWorkerHost {
    suspend fun consumptionQueue(): List<DownloadJob>
    suspend fun markDownloading(jobId: String): DownloadJob
    suspend fun applyUpstreamPageCount(jobId: String, upstreamCount: Int): DownloadJob
    suspend fun markPageReady(jobId: String, revision: Int, ordinal: Int)
    suspend fun readyOrdinals(jobId: String, revision: Int): Set<Int>
    /**
     * WAIT/DOWNLOAD → FAILED (or any state → FAILED, historically unguarded).
     * [failureClass]/[failureDetail] optionally stamp the reason in the same
     * write, so a hard-fail site records WHY without needing a separate
     * [recordFailure] call (Task 8, owner bug #6: previously every hard-fail
     * left `failureClass`/`failureDetail` null forever). Both default to null
     * so the pre-existing single-arg call sites keep their old behavior
     * exactly (no reason stamped, whatever was already there is preserved).
     */
    suspend fun markFailed(
        jobId: String,
        failureClass: String? = null,
        failureDetail: String? = null,
    ): DownloadJob
    suspend fun requireJob(jobId: String): DownloadJob
    suspend fun tryFinish(
        jobId: String,
        title: String,
        readyPagePaths: List<Path>,
        /** A32 §2: catalog facts from the locator, persisted with the Library row. */
        metadata: DownloadCommitMetadata = DownloadCommitMetadata(),
    ): DownloadJob

    /**
     * WAIT/DOWNLOAD → SEALED (truncation wind-down or full completion).
     * [failureClass]/[failureDetail] REPLACE the row's recorded reason (P1,
     * polish backlog): a truncation wind-down stamps the verdict that caused
     * it, a full completion passes nulls and thereby clears the last
     * transient record. Defaulted so pre-existing call sites keep the
     * "full completion" behaviour.
     */
    suspend fun markSealed(
        jobId: String,
        truncatedAt: Int?,
        failureClass: String? = null,
        failureDetail: String? = null,
    ): DownloadJob

    /**
     * A36: [markSealed]'s truncation wind-down for a job that carries
     * [DownloadJob.intentLocal] and has ≥ 1 READY page — same SEALED row
     * (state, [DownloadJob.sealedTruncatedAt], recorded verdict), plus the
     * Library commit for the k pages that were fetched, as an INCOMPLETE row.
     * A cloud-only job never comes here; it has no Library association to
     * materialize and keeps [markSealed].
     */
    suspend fun sealTruncatedWithLocalPages(
        jobId: String,
        truncatedAt: Int,
        title: String,
        readyPagePaths: List<Path>,
        failureClass: String? = null,
        failureDetail: String? = null,
        metadata: DownloadCommitMetadata = DownloadCommitMetadata(),
    ): DownloadJob

    /**
     * WAIT/DOWNLOAD → GONE (source confirmed gone, zero pages fetched).
     * [failureClass]/[failureDetail] REPLACE the row's recorded reason (P1,
     * polish backlog), exactly like [markSealed]: without them a terminal
     * GONE row keeps the first 404's transient JITTER record.
     */
    suspend fun markGone(
        jobId: String,
        failureClass: String? = null,
        failureDetail: String? = null,
    ): DownloadJob

    /** Records classification + resume window without changing job state. */
    suspend fun recordFailure(
        jobId: String,
        failureClass: String,
        detail: String?,
        resumeNotBeforeMs: Long,
    ): DownloadJob

    /** All persisted jobs regardless of state — used to compute [earliestResumeAfter]. */
    suspend fun allJobs(): List<DownloadJob>

    /**
     * #C70: true when the bound bucket already satisfies [job] — see
     * [cloudCopySatisfiesDownload]. Consulted by [DownloadWorkerLoop.processJob]
     * before any locator/manifest work, so a WAIT/DOWNLOAD job the current
     * bucket's ledger already has is retired via [retireAlreadySynced] instead
     * of re-fetching bytes only to release them right after (the loop the
     * spec's phone evidence traced). Defaults to `false` so every pre-#C70
     * test host (none of which override it) keeps fetching exactly as before.
     */
    suspend fun cloudCopySatisfies(job: DownloadJob): Boolean = false

    /**
     * #C70: retires a job [cloudCopySatisfies] found already satisfied.
     * Reuses the WAIT/DOWNLOAD → NONE transition the store's own `cancel`
     * uses (spec §4.7) — no new schema state, and no pages are deleted: an
     * orphaned `.part` temp is swept the same way a cancel sweeps one, but
     * any READY page already on disk is left for the existing
     * release/cleanup paths to find. Only called when [cloudCopySatisfies]
     * is true, which no pre-#C70 test host ever returns — so leaving this
     * abstract (no default) is safe; only [InMemoryDownloadWorkerHost] and
     * [RoomDownloadWorkerHost] implement the interface directly, and both
     * are updated in the same change.
     */
    suspend fun retireAlreadySynced(jobId: String): DownloadJob
}

/** Wraps [DownloadCore] for unit tests. */
class InMemoryDownloadWorkerHost(
    private val core: DownloadCore,
) : DownloadWorkerHost {
    override suspend fun consumptionQueue(): List<DownloadJob> = core.consumptionQueue()

    override suspend fun markDownloading(jobId: String): DownloadJob =
        core.markDownloading(jobId)

    override suspend fun applyUpstreamPageCount(jobId: String, upstreamCount: Int): DownloadJob =
        core.applyUpstreamPageCount(jobId, upstreamCount)

    override suspend fun markPageReady(jobId: String, revision: Int, ordinal: Int) {
        core.markPageReady(jobId, ordinal)
    }

    override suspend fun readyOrdinals(jobId: String, revision: Int): Set<Int> =
        core.readyOrdinals(jobId)

    override suspend fun markFailed(
        jobId: String,
        failureClass: String?,
        failureDetail: String?,
    ): DownloadJob = core.markFailed(jobId, failureClass, failureDetail)

    override suspend fun requireJob(jobId: String): DownloadJob = core.requireJob(jobId)

    override suspend fun retireAlreadySynced(jobId: String): DownloadJob =
        core.cancel(jobId)

    override suspend fun tryFinish(
        jobId: String,
        title: String,
        readyPagePaths: List<Path>,
        metadata: DownloadCommitMetadata,
    ): DownloadJob {
        val job = core.requireJob(jobId)
        val location = defaultDownloadLocationUri(job.storageIdentity, job.downloadRevision)
        return core.tryFinish(jobId, downloadLocationUri = location)
    }

    override suspend fun markSealed(
        jobId: String,
        truncatedAt: Int?,
        failureClass: String?,
        failureDetail: String?,
    ): DownloadJob = core.markSealed(jobId, truncatedAt, failureClass, failureDetail)

    override suspend fun sealTruncatedWithLocalPages(
        jobId: String,
        truncatedAt: Int,
        title: String,
        readyPagePaths: List<Path>,
        failureClass: String?,
        failureDetail: String?,
        metadata: DownloadCommitMetadata,
    ): DownloadJob = core.sealTruncatedWithLocalPages(
        jobId = jobId,
        truncatedAt = truncatedAt,
        failureClass = failureClass,
        failureDetail = failureDetail,
    )

    override suspend fun markGone(
        jobId: String,
        failureClass: String?,
        failureDetail: String?,
    ): DownloadJob = core.markGone(jobId, failureClass, failureDetail)

    override suspend fun recordFailure(
        jobId: String,
        failureClass: String,
        detail: String?,
        resumeNotBeforeMs: Long,
    ): DownloadJob = core.recordFailure(jobId, failureClass, detail, resumeNotBeforeMs)

    override suspend fun allJobs(): List<DownloadJob> = core.allJobs()
}

/**
 * Room-backed host. [tryFinish] builds [DocumentRef] pages from READY paths and
 * commits Library association in the same transaction (spec §4.4).
 */
class RoomDownloadWorkerHost(
    private val store: RoomDownloadJobStore,
    private val filesDir: java.io.File,
    /**
     * #C70: the two facts [cloudCopySatisfiesDownload] needs beyond the job
     * itself. [releaseAfterUpload] mirrors the "keep only the cloud copy"
     * setting read everywhere else in this file
     * ([com.easemic.app.AppGraph.cloudReleaseAfterUploadStore]).
     * [knownSyncedToCurrentBucket] takes the job's locator's `acquisitionUrl`
     * (the same shape [com.easemic.app.AppGraph.releaseLocalPagesForCloudOnlyJobs]
     * reads) and answers against the CURRENTLY bound bucket's ledger. Both
     * default to "never satisfied" so a host built without wiring them (there
     * are none today, but a future test construction is free to omit them)
     * never skips a fetch.
     */
    private val releaseAfterUpload: suspend () -> Boolean = { false },
    private val knownSyncedToCurrentBucket: suspend (DownloadJob) -> Boolean = { false },
) : DownloadWorkerHost {
    override suspend fun consumptionQueue(): List<DownloadJob> = store.consumptionQueue()

    override suspend fun markDownloading(jobId: String): DownloadJob =
        store.updateState(jobId, DownloadJobState.DOWNLOAD)

    override suspend fun applyUpstreamPageCount(jobId: String, upstreamCount: Int): DownloadJob =
        store.applyUpstreamPageCount(jobId, upstreamCount)

    override suspend fun markPageReady(jobId: String, revision: Int, ordinal: Int) {
        store.markPageReady(jobId, revision, ordinal)
    }

    override suspend fun readyOrdinals(jobId: String, revision: Int): Set<Int> =
        store.readyOrdinals(jobId, revision)

    override suspend fun markFailed(
        jobId: String,
        failureClass: String?,
        failureDetail: String?,
    ): DownloadJob = store.markFailed(jobId, failureClass, failureDetail)

    override suspend fun requireJob(jobId: String): DownloadJob = store.requireJob(jobId)

    override suspend fun retireAlreadySynced(jobId: String): DownloadJob =
        store.cancel(jobId)

    override suspend fun cloudCopySatisfies(job: DownloadJob): Boolean =
        cloudCopySatisfiesDownload(
            intentCloud = job.intentCloud,
            intentLocal = job.intentLocal,
            releaseAfterUpload = releaseAfterUpload(),
            knownSyncedToCurrentBucket = knownSyncedToCurrentBucket(job),
        )

    override suspend fun tryFinish(
        jobId: String,
        title: String,
        readyPagePaths: List<Path>,
        metadata: DownloadCommitMetadata,
    ): DownloadJob {
        val job = store.requireJob(jobId)
        val (location, pages) = materializeCatalogDownloadPages(
            filesDir = filesDir,
            identity = job.storageIdentity,
            downloadRevision = job.downloadRevision,
            readyPagePaths = readyPagePaths,
        )
        return store.tryFinish(
            jobId = jobId,
            downloadLocationUri = location,
            title = title.ifBlank { job.identity.canonicalItemId },
            pages = pages,
            metadata = metadata,
        )
    }

    override suspend fun markSealed(
        jobId: String,
        truncatedAt: Int?,
        failureClass: String?,
        failureDetail: String?,
    ): DownloadJob = store.markSealed(jobId, truncatedAt, failureClass, failureDetail)

    /**
     * A36: identical page materialization to [tryFinish] — same
     * [materializeCatalogDownloadPages] call, same tree, same
     * [DocumentRef][com.easemic.app.model.DocumentRef]
     * shape — over the k READY paths instead of all `expected` of them, so a
     * truncated row's files are laid out exactly like a complete one's and
     * every identity-keyed operation (delete, ingest, re-download) finds them.
     */
    override suspend fun sealTruncatedWithLocalPages(
        jobId: String,
        truncatedAt: Int,
        title: String,
        readyPagePaths: List<Path>,
        failureClass: String?,
        failureDetail: String?,
        metadata: DownloadCommitMetadata,
    ): DownloadJob {
        val job = store.requireJob(jobId)
        val (location, pages) = materializeCatalogDownloadPages(
            filesDir = filesDir,
            identity = job.storageIdentity,
            downloadRevision = job.downloadRevision,
            readyPagePaths = readyPagePaths,
        )
        return store.sealTruncatedWithLocalPages(
            jobId = jobId,
            downloadLocationUri = location,
            truncatedAt = truncatedAt,
            title = title.ifBlank { job.identity.canonicalItemId },
            pages = pages,
            failureClass = failureClass,
            failureDetail = failureDetail,
            metadata = metadata,
        )
    }

    override suspend fun markGone(
        jobId: String,
        failureClass: String?,
        failureDetail: String?,
    ): DownloadJob = store.markGone(jobId, failureClass, failureDetail)

    override suspend fun recordFailure(
        jobId: String,
        failureClass: String,
        detail: String?,
        resumeNotBeforeMs: Long,
    ): DownloadJob = store.recordFailure(jobId, failureClass, detail, resumeNotBeforeMs)

    override suspend fun allJobs(): List<DownloadJob> = store.loadAllJobs()
}

/**
 * Minimal in-process download worker (Task 10).
 *
 * Spec §4.3: default one active gallery. Takes WAIT → DOWNLOAD, fetches via
 * adapters into [ReadyPageStore], marks READY, [tryFinish] when complete,
 * FAILED on blocking failure / upstream count disagreement.
 *
 * Prefer WorkManager (DownloadPumpWorker) over Application/AppGraph coroutine
 * scope: the pump survives process death and lock-screen.
 */
/**
 * #C38/D3: the length of the longest run of ordinals 0,1,2,… present in
 * [ready]. With the sibling-discard removed from the download pool, a READY set
 * left by a truncation (source went away mid-gallery) may be non-contiguous
 * (e.g. {0,1,3}); the truncation seal commits only this gap-free prefix so the
 * Library row and reader never see a hole. Returns 0 when ordinal 0 is missing.
 */
internal fun longestContiguousReadyPrefix(ready: Set<Int>): Int {
    var n = 0
    while (n in ready) n++
    return n
}

/**
 * C14: an authoritative existence verdict for a gallery, used only as a VETO at
 * the GONE seal boundary (never as a gate). See [DownloadWorkerLoop]'s
 * `livenessOracle`.
 */
enum class Liveness { ALIVE, DEAD, UNKNOWN }

/**
 * #15 + #C10: the namespace whose [SourceThrottlePolicy] governs this job's
 * fetches — the store that actually ANSWERS. A cloud-routed locator fetches
 * from the user's bucket regardless of the identity's upstream namespace, so
 * it is paced, classified and backed off as CLOUD; anything else is the
 * identity's own namespace. Pacing (processJob) and failure routing
 * (routeManifestFailure / routePerPageFailure) MUST read the same answer —
 * the routers used to re-derive it from the identity, so a legacy split-shape
 * row (upstream identity + cloud locator, the pre-#15 shape) was paced as
 * CLOUD but judged as e-hentai: a cloud 404 became 8 JITTER retries instead
 * of a seal, a cloud 429 parked 30 s instead of 5 s.
 *
 * NOT for [DownloadWorkerLoop.vetoGoneIfAlive]: the liveness oracle asks
 * about the identity's upstream, not about who answered the fetch.
 */
internal fun fetchTargetNamespace(identity: GalleryIdentityV1, locator: DownloadLocator): String =
    if (locator.routesToCloudCollection()) GalleryIdentityV1.CLOUD_COLLECTION else identity.providerNamespace

data class BoundedPassResult(
    val finishedJobs: Int,
    val remainingJobIds: List<String>,
)

class DownloadWorkerLoop(
    private val host: DownloadWorkerHost,
    private val readyPageStore: ReadyPageStore,
    private val adapter: DownloadPageTransferAdapter,
    private val locators: DownloadLocatorStore,
    private val maxActiveGalleries: Int = 1,
    /**
     * S6-A (owner setting "max concurrent transfers"): live source for how
     * many gallery slots [tick] should fan out to THIS pass. Defaults to a
     * constant view of [maxActiveGalleries] so existing callers/tests that
     * only set the constructor field keep working unchanged; production
     * wiring (`AppGraph`) instead reads the user's Settings-screen choice
     * fresh on every call so a mid-flight change takes effect on the very
     * next tick without restarting the pump.
     */
    private val maxActiveGalleriesSource: () -> Int = { maxActiveGalleries },
    private val transferMeter: TransferRateMeter? = null,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    /** Injectable for deterministic tests; defaults to a real coroutine delay. */
    private val delayMs: suspend (Long) -> Unit = { ms -> kotlinx.coroutines.delay(ms) },
    /**
     * SEND_BACK M1 (owner principle "rate limiting is per source/IP" —
     * 2026-09-05 review of S6-A Task 3): shared per-source pacing, registry
     * keyed by `providerNamespace` — see [SourcePacingGate]. Defaults to a
     * gate wired to THIS instance's own [clockMs]/[delayMs] seams, so any
     * test that already injects a fake clock/delay gets deterministic
     * pacing for free without a separate test seam. One gate per
     * [DownloadWorkerLoop] instance — production wiring holds this loop
     * `by lazy` for the process lifetime, so per-source state persists
     * correctly across ticks instead of resetting every pass.
     */
    private val pacingGate: SourcePacingGate = SourcePacingGate(nowMs = clockMs, delayMs = delayMs),
    /**
     * C14 (2026-09-16): authoritative liveness oracle consulted ONLY at the
     * GONE seal boundary. A page-body verdict (repeat-gated NotFound, or a
     * takedown literal) has already classified GONE; before sealing, this
     * confirms the death with gdata (e-h) / `/api/v2/galleries/` (nh). It is a
     * VETO, never a gate: [Liveness.ALIVE] downgrades the seal to a retry (the
     * fix for "alive but skipped forever"); [Liveness.DEAD] seals;
     * [Liveness.UNKNOWN] (oracle unreachable/errored) falls back to today's
     * seal, so an outage can never turn a real death into an infinite retry.
     * `null` = today's behaviour (no confirmation). Called at most once per
     * seal, so it adds one API call per gallery death, never per page. Args:
     * `(providerNamespace, locator.acquisitionUrl)`.
     */
    private val livenessOracle: (suspend (String, String) -> Liveness)? = null,
    /**
     * #C12: the OPDS existence check ([OpdsExistenceCheck]), consulted for an
     * `opds:` job on the manifest and per-page routers alike — as a VETO on a
     * 404/410 GONE (a proxy's transient 404 must not seal a listed comic) and
     * as a CONFIRM on the two "gone" shapes no status code names (a 5xx, or a
     * 2xx whose body is not a comic): [Liveness.DEAD] → GONE, otherwise the
     * class the failure already had. Never consulted for a cloud-routed
     * locator (its bytes come from the user's bucket, not the catalog). Args:
     * the job's identity and its locator. `null` = today's behaviour.
     */
    private val opdsExistence: (suspend (GalleryIdentityV1, DownloadLocator) -> Liveness)? = null,
    /**
     * #E2 Slice 2: the registered source's own failure classification for the
     * fetch-target namespace (same arguments as [classifyFetchFailure]). A
     * `null` answer (no source registered for that namespace — a removed OPDS
     * provider, `r2source:*`) or no callback at all = [classifyFetchFailure],
     * today's behaviour.
     */
    private val sourceFailureClassifier: ((
        String,
        Int,
        String?,
        ByteArray,
        String?,
        Map<String, String>,
        FetchRetryContext,
    ) -> FetchFailureClass?)? = null,
    /**
     * Hard TOTAL deadline for a single page fetch (ship-blocking hang fix).
     * `HttpURLConnection.readTimeout` is PER-READ, not per-request — a source
     * that tarpits (drips a byte every < readTimeout) never trips it and the
     * fetch hangs forever, which starves that gallery slot indefinitely (and,
     * at the still-common [maxActiveGalleriesSource] value of 1, the whole
     * pump). 90s is generous vs. the few-second pacing + 30s read timeout,
     * but bounded; a real page never legitimately takes 90s.
     * Injectable so tests can use a tiny value instead of actually waiting.
     */
    private val pageFetchDeadlineMs: Long = 90_000L,
    /**
     * Same hard-deadline defense for the manifest fetch (`fetchManifest`
     * walks e-h's thumb pages, so it is a blocking multi-request call and can
     * tarpit exactly like a page fetch). Larger than [pageFetchDeadlineMs]
     * since it can span several requests.
     */
    private val manifestFetchDeadlineMs: Long = 120_000L,
    /**
     * Fired for any [DownloadJob.intentCloud] job that reaches a terminal
     * completion state: SEALED (full completion of a cloud-only job, or
     * truncation wind-down of any cloud-intent job), or FINISH (full
     * completion of a dual-intent collect+download job — spec §1.1 FIX1: the
     * intents were OR-merged onto one job row by the DAO, so FINISH still
     * carries intentCloud and must still hand off). Wired in AppGraph to
     * enqueue the CloudSyncQueue upload row + kick CloudIngestWorker. Never
     * fires for a job without [DownloadJob.intentCloud]. Keeps this loop
     * UI/queue-free — the callback is the only seam into the upload pipeline.
     */
    private val onCloudHandoff: suspend (DownloadJob) -> Unit = {},
    /**
     * A cloud-intent job that completes inside [runBoundedPass] is handed off
     * through this callback when it is set; otherwise through [onCloudHandoff]
     * as today.
     */
    private val onBoundedPassCloudHandoff: (suspend (DownloadJob) -> Unit)? = null,
    /**
     * E15 Task 3a: process-local in-flight page tracker ([DownloadProgressState]),
     * observable by the Activity row and the foreground notice. `null` = today's
     * behaviour (no tracking); production wiring (AppGraph) always passes one.
     * Every source's page attempts funnel through [fetchOrdinalIntoReady], so
     * this one seam covers E-Hentai, nhentai, OPDS and the cloud collection.
     */
    private val progressState: DownloadProgressState? = null,
) {
    private fun now(): Long = clockMs()

    private fun classifyFailureForSource(
        namespace: String,
        statusCode: Int,
        resolvedUrl: String?,
        responseBody: ByteArray,
        errorMessage: String?,
        responseHeaders: Map<String, String>,
        retryContext: FetchRetryContext,
    ): FetchFailureClass =
        sourceFailureClassifier?.invoke(
            namespace,
            statusCode,
            resolvedUrl,
            responseBody,
            errorMessage,
            responseHeaders,
            retryContext,
        ) ?: classifyFetchFailure(
            namespace,
            statusCode,
            resolvedUrl,
            responseBody,
            errorMessage,
            responseHeaders,
            retryContext,
        )

    /**
     * S3 Task 9 defects 2/3/5 (owner bug backlog): per-(job, ordinal) retry
     * bookkeeping so a repeated same-symptom failure can escalate
     * (JITTER->PACED for e-h 5xx, JITTER->GONE for 404 — see
     * [FetchRetryContext]) and so a run of consecutive JITTERs backs off
     * instead of parking at a flat interval forever. Keyed by
     * (jobId, ordinal); [MANIFEST_ORDINAL_KEY] is the sentinel ordinal used
     * for manifest-level (not per-page) tracking.
     *
     * SEND_BACK item 7 (owner bug backlog): this loop instance is NOT
     * short-lived — [com.easemic.app.AppGraph] constructs
     * exactly one `by lazy` singleton for the entire process lifetime, so an
     * unbounded-looking in-memory map is NOT automatically fine. Entries are
     * removed per-ordinal on that ordinal's own success or GONE
     * classification, AND — for every (job, ordinal) plus the
     * [MANIFEST_ORDINAL_KEY] entry — whenever the job itself reaches any
     * terminal state (FAILED / SEALED / GONE / FINISH), via
     * [clearAllRetryStateForJob] at every terminal-transition call site
     * below, so a long-running process does not accumulate an entry per
     * gallery ever downloaded.
     *
     * S6-A (owner setting "max concurrent transfers"): [tick] can now fan
     * out to more than one gallery slot concurrently (see [tick]'s KDoc), so
     * this is a [ConcurrentHashMap] rather than a plain `mutableMapOf`.
     * Different galleries never share a jobId, so no cross-job atomicity is
     * needed — a straight thread-safe map swap is sufficient. Bulk removal
     * in [clearAllRetryStateForJob] uses `.keys.removeAll`, which
     * [ConcurrentHashMap]'s `KeySetView` supports safely under concurrent
     * access.
     */
    private val ordinalRetryState = ConcurrentHashMap<Pair<String, Int>, OrdinalRetryState>()

    /**
     * #C73 (#C18-F13 follow-up): consecutive passes of a job that THREW (a bug path, not a mapped
     * source failure). In memory on purpose: a process restart gives a job a fresh budget, and a
     * pass that returns normally clears it.
     */
    private val passThrowStrikes = ConcurrentHashMap<String, Int>()

    /**
     * Jobs currently inside [runDownloadPool] or [runBoundedPass]. One set for the loop,
     * so the two claimers cannot process the same chapter.
     */
    private val loopClaims = ConcurrentHashMap.newKeySet<String>()

    private fun takeClaim(queue: List<DownloadJob>, already: Set<String>): DownloadJob? {
        val ranked = selectDownloadTargets(queue, queue.size.coerceAtLeast(1))
        for (candidate in ranked) {
            if (candidate.id in already) continue
            if (loopClaims.add(candidate.id)) return candidate
        }
        return null
    }

    private fun releaseClaim(id: String) {
        loopClaims.remove(id)
    }

    /**
     * #C73: a pass that threw leaves the job in DOWNLOAD with no park, and with a 0 ms source spacing
     * the next claim re-runs it at once (24,070 manifest fetches in 300 s). Park it with a growing
     * window; after [PASS_THROW_MAX_STRIKES] throws in a row it is FAILED with the reason stamped.
     * Never throws itself: this runs on the pump's own error path.
     */
    private suspend fun strikeThrownPass(job: DownloadJob, error: Exception) {
        val strikes = (passThrowStrikes[job.id] ?: 0) + 1
        passThrowStrikes[job.id] = strikes
        val detail = error.javaClass.simpleName
        try {
            if (strikes >= PASS_THROW_MAX_STRIKES) {
                passThrowStrikes.remove(job.id)
                host.markFailed(job.id, PASS_THREW, detail)
                DiagLog.w("DownloadPump", "job=${job.id} pass threw $strikes× in a row ($detail) — FAILED")
            } else {
                val wait = PASS_THROW_BASE_BACKOFF_MS shl (strikes - 1)
                host.recordFailure(job.id, PASS_THREW, detail, resumeNotBeforeMs = clockMs() + wait)
                DiagLog.w("DownloadPump", "job=${job.id} pass threw ($detail), strike $strikes — parked ${wait / 1000} s")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (parkFailure: Exception) {
            DiagLog.w("DownloadPump", "job=${job.id} could not be parked after a thrown pass", parkFailure)
        }
    }

    private data class OrdinalRetryState(
        val lastStatusCode: Int,
        val lastAttemptAtMs: Long,
        val consecutiveJitterCount: Int,
    )

    /**
     * True when the last recorded attempt for this (job, ordinal) failed
     * with the SAME symptom — bucketed via [repeatBucket], so any two 5xx
     * codes count as the same symptom while 404 must match exactly — within
     * [RETRY_ESCALATION_WINDOW_MS]. A different symptom, or a gap longer
     * than the window, is treated as a fresh symptom (first-offense
     * JITTER), not a repeat.
     *
     * SEND_BACK item 7 (owner bug backlog): this is deliberately anchored to
     * the LAST attempt, refreshed on every touch, NOT to when the streak
     * first started — an earlier attempt at fixing this by anchoring to the
     * streak's start time instead broke [perPageJitterBackoffDoublesThenExhausts]:
     * the backoff itself doubles past the window's own duration (3s, 6s,
     * 12s, ... capped at 60s — several strikes in, the cumulative elapsed
     * time since the FIRST strike already exceeds a 2-minute window even
     * though every individual gap is well inside it), so a start-anchored
     * window prematurely "expired" an still-actively-retrying streak and
     * reset its backoff back to the base delay instead of letting it
     * continue escalating toward [JITTER_EXHAUSTION_THRESHOLD]. [Widening
     * RETRY_ESCALATION_WINDOW_MS] is the fix that does not have that failure
     * mode: it tolerates realistic scheduling gaps between ticks (S6-A: up to
     * [maxActiveGalleriesSource]'s current value of gallery slots now run
     * concurrently, and a same-source sibling gallery can hold
     * [SourcePacingGate]'s shared permit/spacing slot for that namespace;
     * brief app backgrounding) without punishing an ever-doubling
     * in-algorithm backoff.
     */
    private fun isRepeatForOrdinal(jobId: String, ordinal: Int, statusCode: Int): Boolean {
        val prior = ordinalRetryState[jobId to ordinal] ?: return false
        return repeatBucket(prior.lastStatusCode) == repeatBucket(statusCode) &&
            (now() - prior.lastAttemptAtMs) <= RETRY_ESCALATION_WINDOW_MS
    }

    /**
     * SEND_BACK item 7 (owner bug backlog): groups every 5xx status into one
     * bucket for repeat detection, since a source flapping between e.g. 500
     * and 503 on consecutive attempts is the SAME "server having trouble"
     * symptom, not two unrelated first-offenses that each reset the streak.
     * 404 (and every other status) still requires an EXACT match — a 404 is
     * a specific, meaningful verdict ("not found") that should not be
     * conflated with a 403 or a 410.
     */
    private fun repeatBucket(statusCode: Int): Int = if (statusCode in 500..599) 500 else statusCode

    private fun retryContextFor(jobId: String, ordinal: Int, statusCode: Int): FetchRetryContext =
        FetchRetryContext(isRepeatForOrdinal = isRepeatForOrdinal(jobId, ordinal, statusCode))

    /**
     * Updates the (job, ordinal) retry-state entry with this attempt's
     * outcome and returns the new consecutive-JITTER count (0 for any
     * non-JITTER classification). GONE is terminal for the ordinal — its
     * entry is dropped outright rather than recorded. A non-JITTER-but-
     * non-GONE outcome (PACED / QUOTA / ABUSE_STOP) still refreshes the
     * status/timestamp so a source stuck repeating the SAME symptom stays on
     * that escalated class instead of flapping back to first-offense JITTER
     * on the very next tick, but resets the JITTER backoff streak to 0 since
     * [jitterBackoffMs] only applies while actually parked as JITTER.
     */
    private fun recordOrdinalAttempt(jobId: String, ordinal: Int, statusCode: Int, cls: FetchFailureClass): Int {
        val key = jobId to ordinal
        if (cls == FetchFailureClass.GONE) {
            ordinalRetryState.remove(key)
            return 0
        }
        val jitterCount = if (cls == FetchFailureClass.JITTER) {
            val priorCount = if (isRepeatForOrdinal(jobId, ordinal, statusCode)) {
                ordinalRetryState[key]?.consecutiveJitterCount ?: 0
            } else {
                0
            }
            priorCount + 1
        } else {
            0
        }
        ordinalRetryState[key] = OrdinalRetryState(statusCode, now(), jitterCount)
        return jitterCount
    }

    /** Clears retry bookkeeping on success — a fresh problem later starts its own streak. */
    private fun clearOrdinalRetryState(jobId: String, ordinal: Int) {
        ordinalRetryState.remove(jobId to ordinal)
    }

    /**
     * SEND_BACK item 7 (owner bug backlog): drops every [ordinalRetryState]
     * entry for [jobId] — every per-ordinal entry AND the
     * [MANIFEST_ORDINAL_KEY] sentinel — called at every place this loop
     * drives a job to a terminal state (FAILED / SEALED / GONE / FINISH).
     * Without this, a job that hard-fails or completes while some OTHER
     * ordinal (or the manifest) still has a lingering retry-state entry
     * would leak that entry for the lifetime of the process, since
     * [ordinalRetryState] is a singleton-scoped map (see its KDoc).
     */
    private fun clearAllRetryStateForJob(jobId: String) {
        ordinalRetryState.keys.removeAll { it.first == jobId }
    }

    /**
     * Every path on which the LOOP moves a job out of DOWNLOAD ends here, and
     * so does the WAIT->DOWNLOAD pickup. #C16 audit F1: it also releases the
     * adapter's cached plan — for OPDS that plan is the whole decompressed
     * archive, which otherwise stayed pinned in this process singleton after
     * FINISH (and served stale bytes to a later re-download of the same
     * acquisition, defeating #C12's delisting check).
     *
     * A job that leaves the queue OUTSIDE the loop (the UI's cancel/uncollect
     * -> `RoomDownloadJobStore.cancel`) is released by the store's
     * `onJobLeftQueue` callback, which AppGraph wires to the same
     * `invalidatePlan` (#C16 re-review item 1: a JITTER-parked job is out of
     * the queue and out of the pool, so cancel is the only exit it takes).
     */
    private fun clearAllRetryStateForJob(job: DownloadJob) {
        clearAllRetryStateForJob(job.id)
        adapter.invalidatePlan(job.adapterIdentity)
    }

    /**
     * Bounded doubling backoff: base delay on the first JITTER for this
     * ordinal, doubling per consecutive same-symptom JITTER, capped at
     * [JITTER_BACKOFF_CAP_MS] so a source stuck JITTERing does not park for
     * longer and longer forever.
     */
    private fun jitterBackoffMs(baseMs: Long, consecutiveJitterCount: Int): Long {
        val exponent = (consecutiveJitterCount - 1).coerceIn(0, 20)
        val scaled = baseMs * (1L shl exponent)
        return if (scaled < 0 || scaled > JITTER_BACKOFF_CAP_MS) JITTER_BACKOFF_CAP_MS else scaled
    }

    /**
     * App-open windowed resume (whole-slice review GAP 1): the immediate
     * consumption queue can be empty solely because every WAIT/DOWNLOAD job is
     * still inside a QUOTA/ban/pacing idle window ([earliestResumeAfter]) —
     * nothing else re-checks [DownloadJob.resumeNotBeforeMs] once it lapses.
     * [DownloadPumpWorker.kickAfter] previously only ran from cold start
     * (AppGraph); this lets the worker's own exit path schedule the same
     * delayed wake-up while the app stays open. Null when no job is windowed
     * into the future.
     */
    suspend fun earliestResumeDelayMs(): Long? =
        earliestResumeAfter(host.allJobs(), now())?.let { (it - now()).coerceAtLeast(0) }

    companion object {
        /**
         * e-h's own ban page states an expiry we don't parse yet (TODO), so we
         * fall back to a conservative floor: whichever is longer of the
         * source's QUOTA idle window or one hour.
         */
        private const val ABUSE_STOP_MIN_WINDOW_MS = 60 * 60 * 1_000L

        /** Sentinel ordinal key for manifest-level (not per-page) retry tracking. */
        private const val MANIFEST_ORDINAL_KEY = -1

        /**
         * S3 Task 9 defects 2/3 (owner bug backlog): a second same-status
         * failure for the SAME (job, ordinal) within this window is treated
         * as a repeat — escalating JITTER->PACED for e-h 5xx, JITTER->GONE
         * for 404 — rather than yet another first-offense transient blip.
         *
         * SEND_BACK item 7 (owner bug backlog): widened from the original
         * 2 minutes to 10. [tick] only ever advances up to
         * [maxActiveGalleriesSource]'s current value's worth of active jobs
         * per pass, so a job can easily sit untouched for several
         * minutes while OTHER active jobs are serviced, and the process can
         * also background/pause between passes — none of that is this
         * ordinal's own symptom clearing up, so a too-short window was
         * silently discarding genuine escalation progress (a real repeat
         * getting scored as a fresh first-offense) any time the worker
         * simply hadn't gotten back around to it fast enough.
         * [resumeNotBeforeMs] is only ever a lower bound, never a scheduling
         * guarantee, so this window must comfortably outlast realistic
         * worst-case scheduling gaps, not just the in-algorithm backoff
         * cadence (see [isRepeatForOrdinal]'s KDoc for why this stays
         * anchored to the LAST attempt rather than the streak's start).
         */
        private const val RETRY_ESCALATION_WINDOW_MS = 10 * 60 * 1_000L

        /** S3 Task 9 defect 5: bounded doubling backoff cap for parked JITTER retries. */
        private const val JITTER_BACKOFF_CAP_MS = 60_000L

        /** S3 Task 9 defect 5: consecutive per-ordinal JITTERs before hard-failing instead of parking again. */
        private const val JITTER_EXHAUSTION_THRESHOLD = 8

        /**
         * #C38/D4 (2026-09-15): how many times a single page is retried IN
         * PLACE on its own lane for a transient (JITTER) failure before the
         * failure escalates to a job-level park. The E-Hentai adapter rotates
         * the H@H node on each retry (C40 §C-4/§C-5), so this is "try a few
         * different nodes for this page" — SXJ uses 5; 3 keeps the worst-case
         * lane time bounded against [pageFetchDeadlineMs]. In-place retries do
         * NOT touch [ordinalRetryState]; the cross-pass JITTER backoff and
         * [JITTER_EXHAUSTION_THRESHOLD] ladder still count PASSES, not these.
         */
        private const val IN_PLACE_PAGE_ATTEMPTS = 3

        /**
         * #C39: upper bound on concurrent gallery lanes in [runDownloadPool],
         * matching MaxConcurrentTransfersStore.MAX. How many actually claim is
         * governed live by [maxActiveGalleriesSource]; extra lanes idle until
         * the user raises the setting.
         */
        private const val MAX_POOL_LANES = 5

        /** #C73: failure class stamped on a job whose pass threw. */
        const val PASS_THREW: String = "PASS_THREW"
        const val PASS_THROW_MAX_STRIKES: Int = 4
        const val PASS_THROW_BASE_BACKOFF_MS: Long = 30_000L
    }
    /**
     * Advance up to [maxActiveGalleriesSource]'s current value of active
     * gallery job(s) by one full pass each (manifest + missing pages +
     * tryFinish). Returns true when any selected job's pass actually
     * completed without throwing — NOT merely when a target was selected
     * (bundle-fix, SEND_BACK review of S6-A Task 3: the old unconditional
     * `true` whenever `targets.isNotEmpty()` lied to [runForever]'s idle
     * logic if every fanned-out child threw and was caught below).
     *
     * Target selection is [selectDownloadTargets]: every row already
     * DOWNLOADing keeps its slot (a fetch in progress is never pre-empted),
     * and any remaining slots are back-filled with WAIT rows in FIFO order —
     * so e.g. cap=3 with 1 DOWNLOAD + 5 WAIT processes 3 rows this pass, not
     * 1.
     *
     * S6-A (owner setting "max concurrent transfers"): when more than one
     * target is selected, each job's full pass runs in its own
     * [kotlinx.coroutines.launch] child inside a [supervisorScope] so N
     * galleries fetch concurrently instead of one full gallery at a time.
     * [supervisorScope] means one job's failure never cancels its siblings;
     * each child additionally catches its own [Exception] (re-throwing
     * [CancellationException] so real cancellation still propagates) so a
     * single job's unexpected throw can't crash the whole pass — the same
     * failure-isolation `processJob`'s own internal routing already gives a
     * single job, just extended across the fan-out. The single-target case
     * stays a direct sequential call (no scope/dispatch overhead) since
     * there is nothing to overlap.
     *
     * SEND_BACK item 7 (owner bug backlog) update: this WAS the single entry
     * point that ever read or wrote [ordinalRetryState], driven by exactly
     * one worker never running concurrently — that assumption no longer
     * holds now that a single [tick] pass can run several [processJob]s at
     * once, which is why [ordinalRetryState] is now a [ConcurrentHashMap]
     * rather than the old plain `mutableMapOf`.
     */
    suspend fun tick(): Boolean {
        val queue = host.consumptionQueue()
        if (queue.isEmpty()) return false

        val slots = maxActiveGalleriesSource()
        val targets = selectDownloadTargets(queue, slots)
        if (targets.isEmpty()) return false

        if (targets.size == 1) {
            // A36 review O3: the single-target path used to let any throw out
            // of processJob escape the pump entirely, while the fan-out path
            // below caught and logged it — so the SAME fault was survivable
            // with two galleries in flight and fatal with one (the common
            // case, since maxActiveGalleries defaults to 1).
            return try {
                processJob(targets.single())
                passThrowStrikes.remove(targets.single().id)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                DiagLog.w("DownloadPump", "tick: processJob failed for job=${targets.single().id}", t)
                strikeThrownPass(targets.single(), t)
                false
            }
        }
        // M4 (spec, emulator verification greps for this exact line):
        DiagLog.i("DownloadPump", "tick: ${targets.size} gallery slot(s) in parallel (cap=$slots)")
        // Bundle (a) (SEND_BACK, same review): this used to return `true`
        // unconditionally whenever >=1 target was selected, even if EVERY
        // child threw and got caught below — the pump's idle logic
        // (runForever) trusts this return value to decide whether to sleep,
        // so that was a lie whenever a whole fan-out pass failed outright.
        // Track whether any child actually completed processJob without
        // throwing, and report that instead.
        val anyAdvanced = AtomicBoolean(false)
        supervisorScope {
            for (job in targets) {
                launch {
                    try {
                        processJob(job)
                        passThrowStrikes.remove(job.id)
                        anyAdvanced.set(true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Exception) {
                        DiagLog.w("DownloadPump", "tick: processJob failed for job=${job.id}", t)
                        strikeThrownPass(job, t)
                    }
                }
            }
        }
        return anyAdvanced.get()
    }

    /**
     * Continuous loop: tick until the consumption queue is empty, then idle
     * briefly so newly enqueued WAIT jobs are picked up.
     */
    suspend fun runForever(
        idleDelayMs: Long = 750L,
        delay: suspend (Long) -> Unit = { ms -> kotlinx.coroutines.delay(ms) },
    ): Nothing {
        while (true) {
            val worked = tick()
            if (!worked) {
                delay(idleDelayMs)
            }
        }
    }

    /**
     * #C39 (2026-09-15, C40 §C-7): a job-level worker POOL that keeps up to
     * `maxActiveGalleriesSource()` galleries in flight and — crucially — admits
     * a gallery ENQUEUED MID-PASS into a free slot instead of waiting for the
     * running gallery's whole [processJob] to finish. [tick] admits only the
     * targets present when it starts and runs them all to completion first, so
     * a Collect/Download tapped while another gallery downloads waited for that
     * gallery to finish (or, at the default cap of 1, to fail) — the owner's
     * 2026-09-13 report that "max concurrent transfers" is inert and later
     * jobs stay WAITING until the first one fails.
     *
     * Up to [MAX_POOL_LANES] lanes are launched (matching
     * [com.easemic.app.feature.settings.MaxConcurrentTransfersStore.MAX]);
     * how many actually claim work is bounded live by [maxActiveGalleriesSource]
     * inside [claimNext], so raising the setting mid-run lets idle lanes start
     * claiming without a pump restart. A lane with nothing to claim but a
     * sibling still in flight re-polls every [pollMs] (and on a sibling
     * release) so a job enqueued mid-pass is picked up within [pollMs] even
     * when the tap bumped no signal (a KEEP kick against an already-running
     * pump is a no-op — the pool must DISCOVER the new row). The pass returns
     * once every lane is idle with nothing claimable and nothing in flight;
     * parked jobs (future resumeNotBeforeMs) leave the consumption queue and
     * are re-driven by [DownloadPumpWorker]'s [earliestResumeDelayMs] kick,
     * exactly as before. Returns true if any gallery was claimed this pass.
     *
     * The per-page pool inside [processJob] and the per-source
     * [SourcePacingGate] still apply, so "N galleries x M page workers" never
     * exceeds a source's own in-flight cap — this only stops galleries from
     * queueing behind each other at the JOB level.
     */
    suspend fun runDownloadPool(pollMs: Long = 250L): Boolean {
        val claims = Mutex()
        val inFlight = mutableSetOf<String>()
        val wake = MutableStateFlow(0)
        val didWork = AtomicBoolean(false)

        suspend fun claimNext(): DownloadJob? = claims.withLock {
            val slots = maxActiveGalleriesSource().coerceAtLeast(1)
            if (inFlight.size >= slots) return@withLock null
            val claimed = takeClaim(host.consumptionQueue(), inFlight) ?: return@withLock null
            inFlight += claimed.id
            claimed
        }
        suspend fun release(id: String) = withContext(NonCancellable) {
            claims.withLock { inFlight -= id }
            releaseClaim(id)
            wake.update { it + 1 }
        }

        coroutineScope {
            repeat(MAX_POOL_LANES) {
                launch {
                    var lastWake = wake.value
                    while (true) {
                        val job = claimNext()
                        if (job == null) {
                            // Nothing claimable now. If a sibling is still in
                            // flight (it may release a slot, or new work may
                            // land), keep waiting — re-poll on a sibling wake
                            // OR every pollMs so a mid-pass tap that signalled
                            // nothing is still discovered. Otherwise the pass
                            // is drained: exit.
                            val keepWaiting = claims.withLock { inFlight.isNotEmpty() }
                            if (!keepWaiting) break
                            withTimeoutOrNull(pollMs) { wake.first { it != lastWake } }
                            lastWake = wake.value
                            continue
                        }
                        didWork.set(true)
                        try {
                            processJob(job)
                            passThrowStrikes.remove(job.id)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (t: Exception) {
                            DiagLog.w("DownloadPump", "runDownloadPool: processJob failed for job=${job.id}", t)
                            strikeThrownPass(job, t)
                        } finally {
                            release(job.id)
                        }
                    }
                }
            }
        }
        return didWork.get()
    }

    /**
     * One pass over [jobIds] on this same loop. Claims through [selectDownloadTargets] and
     * finishes through [processJob]. Stops before a claim once [monotonicClock] reaches
     * [deadline] or [maxFinishedJobs] saves have finished. At the deadline, only this pass's
     * upstream waits (manifest/page fetches and pacing) are cancelled. Local publication,
     * commit and handoff finish; interrupted jobs stay claimable with no failure strike.
     * The `.part` prefix
     * (when a transfer has one) stays for the next [runDownloadPool].
     */
    suspend fun runBoundedPass(
        jobIds: Collection<String>,
        deadline: Long,
        maxFinishedJobs: Int,
        monotonicClock: () -> Long,
    ): BoundedPassResult {
        val order = jobIds.distinct().toList()
        if (order.isEmpty() || maxFinishedJobs <= 0 || monotonicClock() >= deadline) {
            return BoundedPassResult(0, stillClaimable(order))
        }
        val allowed = order.toSet()
        val bounded = BoundedPassCloudHandoff(deadline, monotonicClock)
        val finished = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        fun savedCapReached(inFlightCount: Int = 0): Boolean =
            finished.get() + inFlightCount >= maxFinishedJobs
        val claims = Mutex()
        val inFlight = mutableSetOf<String>()
        val wake = MutableStateFlow(0)
        suspend fun claimNext(): DownloadJob? = claims.withLock {
            if (stop.get() || monotonicClock() >= deadline || savedCapReached()) {
                stop.set(true)
                return@withLock null
            }
            val slots = maxActiveGalleriesSource().coerceAtLeast(1)
            if (inFlight.size >= slots || savedCapReached(inFlight.size)) {
                return@withLock null
            }
            val queue = host.consumptionQueue().filter { it.id in allowed }
            val claimed = takeClaim(queue, inFlight) ?: return@withLock null
            inFlight += claimed.id
            claimed
        }
        try {
            coroutineScope {
                val processing = launch {
                    supervisorScope {
                        repeat(MAX_POOL_LANES) {
                            launch(bounded) {
                                var lastWake = wake.value
                                while (isActive && !stop.get()) {
                                    val job = claimNext()
                                    if (job == null) {
                                        val keepWaiting = claims.withLock { inFlight.isNotEmpty() && !stop.get() }
                                        if (!keepWaiting) break
                                        withTimeoutOrNull(50) { wake.first { it != lastWake } }
                                        lastWake = wake.value
                                        continue
                                    }
                                    try {
                                        processJob(job)
                                        passThrowStrikes.remove(job.id)
                                        val state = host.requireJob(job.id).state
                                        if (state == DownloadJobState.FINISH || state == DownloadJobState.SEALED) {
                                            finished.incrementAndGet()
                                        }
                                    } catch (cancelled: CancellationException) {
                                        val endedByDeadline = stop.get() || monotonicClock() >= deadline
                                        if (!endedByDeadline) throw cancelled
                                    } catch (t: Exception) {
                                        DiagLog.w("DownloadPump", "runBoundedPass: processJob failed for job=${job.id}", t)
                                        strikeThrownPass(job, t)
                                    } finally {
                                        withContext(NonCancellable) {
                                            claims.withLock { inFlight -= job.id }
                                            releaseClaim(job.id)
                                            wake.update { it + 1 }
                                        }
                                    }
                                    if (savedCapReached() || monotonicClock() >= deadline) {
                                        stop.set(true)
                                    }
                                }
                            }
                        }
                    }
                }
                val watcher = launch {
                    while (isActive && processing.isActive) {
                        if (monotonicClock() >= deadline) {
                            stop.set(true)
                            bounded.cancelUpstreamWaits()
                            break
                        }
                        delay(50)
                    }
                }
                processing.join()
                stop.set(true)
                watcher.cancel()
            }
        } catch (cancelled: CancellationException) {
            if (coroutineContext.isActive && (stop.get() || monotonicClock() >= deadline)) {
                // The deadline ended the pass. The worker itself is still running.
            } else {
                throw cancelled
            }
        }
        return BoundedPassResult(finished.get(), stillClaimable(order))
    }

    private suspend fun stillClaimable(order: List<String>): List<String> =
        order.filter { id ->
            val job = runCatching { host.requireJob(id) }.getOrNull() ?: return@filter false
            job.state == DownloadJobState.WAIT || job.state == DownloadJobState.DOWNLOAD
        }

    private suspend fun processJob(initial: DownloadJob) {
        var job = initial
        // #C70: the bound bucket already has this comic for every intent this
        // job carries — checked before markDownloading/the locator read (the
        // spec's ONE gate every fetch passes through), so a rebind that
        // re-arms an already-synced job is retired for free instead of
        // spending a manifest + page fetch only to have the release sweep
        // delete them right after (the loop the phone evidence traced).
        if (host.cloudCopySatisfies(job)) {
            DiagLog.i(
                "DownloadPump",
                "job ${job.id} ${redactIdentity(job.identity)} skipped: already in the bound bucket",
            )
            host.retireAlreadySynced(job.id)
            clearAllRetryStateForJob(job)
            return
        }
        if (job.state == DownloadJobState.WAIT) {
            job = host.markDownloading(job.id)
            // Cheap should-fix (e), S3 re-review: DownloadCore.enqueue
            // revives a cancelled (NONE) job back to WAIT reusing the SAME
            // job id — cancel() itself has no coupling to this loop's
            // in-memory ordinalRetryState, so a cancel + re-enqueue within
            // the escalation window would otherwise silently inherit the
            // old JITTER streak (and any stale manifest-retry entry) on
            // this fresh download attempt. WAIT->DOWNLOAD is the ONE point
            // every resumed attempt passes through — evicting here is safe
            // even for a genuinely fresh pickup (nothing to clear yet) and
            // never fires mid-streak for a JITTER-parked job, which stays
            // DOWNLOAD continuously and never re-enters this branch.
            // And — #C16 re-review item 1 — it drops the adapter plan too: a
            // cancelled job revived through the NONE-revive path keeps its
            // id, so without this the re-download would serve the previous
            // attempt's cached archive with zero HTTP (F4).
            clearAllRetryStateForJob(job)
        }
        if (job.state != DownloadJobState.DOWNLOAD) {
            return
        }

        val initialLocator = locators.get(job.storageIdentity)
        if (initialLocator == null) {
            val detail = "no download locator for ${redactIdentity(job.identity)}"
            DiagLog.w("DownloadPump", "job ${job.id} FAILED NO_LOCATOR: $detail")
            host.markFailed(job.id, "NO_LOCATOR", detail)
            clearAllRetryStateForJob(job)
            return
        }
        // #C65: reassigned below, right after the manifest fetch, once the
        // plan's own catalog facts (if any) have filled whatever this
        // locator was missing.
        var locator: DownloadLocator = initialLocator
        if (!adapter.supports(job.adapterIdentity)) {
            // #C52 round 3 M1: providerNamespace is non-secret for the
            // official sources but an OPDS one is `opds:<configId>` — route
            // it through redactIdentity like every other identity-shaped
            // log/persist site instead of interpolating it raw.
            val detail = "no adapter supports namespace ${redactIdentity(job.identity)}"
            DiagLog.w("DownloadPump", "job ${job.id} FAILED UNSUPPORTED_IDENTITY: $detail")
            host.markFailed(job.id, "UNSUPPORTED_IDENTITY", detail)
            clearAllRetryStateForJob(job)
            return
        }

        // SEND_BACK M1: namespace hoisted once per job for the pacing gate
        // (below) — every upstream fetch for this job, manifest AND each
        // page, goes through the SAME per-source [pacingGate] entry.
        // #15 + #C10: see [fetchTargetNamespace] — the same answer the two
        // failure routers judge this job's failures by.
        val ns = fetchTargetNamespace(job.identity, locator)
        val manifest = try {
            withUpstreamWait(job.id) {
                pacingGate.paced(ns, "job=${job.id} kind=manifest", spacing = !adapter.ownsRequestPacing(job.adapterIdentity, locator)) {
                    if (adapter.usesProgressAwareManifest(job.adapterIdentity, locator)) {
                        // OPDS acquisition streams its archive to disk and owns a
                        // Content-Length/floor-derived budget. The legacy flat
                        // manifest cap would kill a healthy moving archive.
                        // Call the suspend entry so cancellation closes the socket;
                        // runInterruptible cannot.
                        adapter.fetchManifestForWorker(job.adapterIdentity, locator)
                    } else {
                        withTimeout(manifestFetchDeadlineMs) {
                            adapter.fetchManifestForWorker(job.adapterIdentity, locator)
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            // No status ever arrived (tarpit/hung socket) — convert to a
            // classifiable transport failure instead of letting the
            // TimeoutCancellationException (a CancellationException) look
            // like ordinary job cancellation.
            routeManifestFailure(job, locator, manifestTimeoutException())
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            routeManifestFailure(job, locator, t)
            return
        }
        // #C65: the plan already parsed every tag/artist/group/language/
        // category off the live gallery page — fill whatever the locator is
        // still missing (an enqueue tap before detail finished, or a
        // catch-up/adoption refill, records a fact-less locator) so FINISH
        // below commits the gallery's own facts instead of nothing. Never
        // overwrites a fact the locator already has.
        manifest.catalogFacts?.let { facts ->
            locators.fillMissingCatalogFacts(job.storageIdentity, facts)
            locator = locators.get(job.storageIdentity) ?: locator
        }
        if (manifest.expectedPageCount <= 0) {
            val detail = "${manifest.expectedPageCount}"
            DiagLog.w("DownloadPump", "job ${job.id} FAILED EMPTY_MANIFEST: expectedPageCount=$detail")
            host.markFailed(job.id, "EMPTY_MANIFEST", detail)
            clearAllRetryStateForJob(job)
            return
        }

        job = host.applyUpstreamPageCount(job.id, manifest.expectedPageCount)
        if (job.state == DownloadJobState.FAILED) {
            clearAllRetryStateForJob(job)
            return
        }
        // S3 Task 9 fifth defect (owner bug backlog): a short upstream page
        // count vs. the pin, with pages already on device, parks the job for
        // one manifest retry (PAGE_COUNT_MISMATCH_PENDING) instead of
        // proceeding on a possibly-short/stale manifest this tick.
        if (job.failureClass == PAGE_COUNT_MISMATCH_PENDING) {
            // SEND_BACK item 1 (owner bug backlog): the "retry once" park is
            // worthless if the parked adapter is a process-wide singleton
            // that just hands back the SAME cached plan/manifest it already
            // disagreed with — nothing upstream is actually re-checked.
            // Drop the cached plan now so the NEXT tick's fetchManifest call
            // (once resumeNotBeforeMs lapses) re-fetches from upstream.
            adapter.invalidatePlan(job.adapterIdentity)
        }
        if (job.resumeNotBeforeMs > now()) {
            return
        }

        val expected = job.expectedPageCount ?: manifest.expectedPageCount
        val revision = job.downloadRevision
        val roomReady = host.readyOrdinals(job.id, revision)
        // Process restart: Room READY ordinals + on-disk files must re-enter
        // ReadyPageStore before resolveReady / reader paths can see them.
        //
        // A36 review O3: only what hydrate ACTUALLY admitted counts as ready.
        // A READY row can outlive its file (a process death between
        // `deletePublishedRevision` and `deleteReadyPagesForJob`), and taking
        // the Room list at its word made the loop skip fetching a page it did
        // not have — so every read of it failed, every pass, forever. An
        // un-admitted marker is simply re-fetched below.
        val hydrated = readyPageStore.hydrateTrustedOrdinals(job.storageIdentity, revision, roomReady)
        if (hydrated.size < roomReady.size) {
            DiagLog.w(
                "DownloadPump",
                "job ${job.id} has ${roomReady.size - hydrated.size} READY row(s) with no file " +
                    "under r$revision — re-fetching those ordinals (A36 O3)",
            )
        }
        // A18 (owner bug backlog, Kyouei 2308928: 0 READY rows / 11 files):
        // pages the READER published through the shared store (or a run that
        // died between publish and row insert) have no Room row, so
        // hydrateTrusted cannot see them and every one of them would be
        // re-fetched from upstream. Admit whatever is atomically published on
        // disk under this revision (never `.part`, never empty, never beyond
        // the pin, never an ordinal Room already lists) and upsert the missing
        // rows so this pass — and every restart after it — resumes from what
        // is already on the device.
        //
        // ORDER IS DELIBERATE: this runs AFTER applyUpstreamPageCount and the
        // resumeNotBeforeMs check above. A job with orphaned files but 0 Room
        // rows therefore still takes applyUpstreamPageCount's
        // "readyCount == 0 → re-pin, no strike" branch on a short count; the
        // reconcile then admits files inside the re-pinned `expected`. Moving
        // this above the pin check would make orphaned files count as
        // page-count strikes and change the D1 two-strike policy. Don't.
        val alreadyReady = hydrated.toMutableSet()
        val reconciled = readyPageStore.reconcilePublished(
            identity = job.storageIdentity,
            revision = revision,
            limitExclusive = expected,
            skip = hydrated,
        )
        for (ordinal in reconciled) {
            host.markPageReady(job.id, revision, ordinal)
            alreadyReady += ordinal
        }
        if (reconciled.isNotEmpty()) {
            DiagLog.i(
                "DownloadPump",
                "job ${job.id} admitted ${reconciled.size} on-disk page(s) into READY (A18 resume)",
            )
        }

        // #C38 (2026-09-15, C40 D1/D2/D3/D4): pages are fetched by a
        // continuously-refilled worker pool of `poolSize` lanes, NOT by
        // `chunked(window)` + `awaitAll` barriers. Each lane pulls the next
        // pending ordinal the instant it frees, so a slow page never idles a
        // free lane (the old barrier made a chunk's wall clock its slowest
        // page). The shared `SourcePacingGate` still bounds in-flight fetches
        // per source (Semaphore(maxConcurrentFetches) + inter-request spacing),
        // so launching `poolSize` lanes never exceeds the source's own cap —
        // the extra lanes queue on the gate instead of behind a barrier.
        //
        // Per-page failure isolation:
        //  - A transient (JITTER-class) failure is retried IN PLACE up to
        //    [IN_PLACE_PAGE_ATTEMPTS] times on the same lane (the E-Hentai
        //    adapter rotates the H@H node on the retry), WITHOUT parking the
        //    whole job — one dead node no longer freezes every sibling.
        //  - A source-wide verdict (PACED/QUOTA/ABUSE_STOP/GONE) or a
        //    JITTER that survives the in-place retries escalates to a
        //    job-level outcome: the FIRST such outcome wins, the pool drains,
        //    and it is routed exactly once through [routePerPageFailure]
        //    (which keeps the cross-pass JITTER backoff + exhaustion ladder,
        //    the 404→GONE truncation, 509→QUOTA, etc.).
        // Siblings that already succeeded are NEVER discarded (the old
        // `discardPublished` is gone from this path); the READY set may be a
        // non-contiguous prefix and the seal logic tolerates it via
        // [longestContiguousReadyPrefix].
        val poolSize = SourceThrottlePolicy.forNamespace(ns).maxConcurrentFetches.coerceAtLeast(1)
        val pendingOrdinals = (0 until expected).filter { it !in alreadyReady }
        if (pendingOrdinals.isNotEmpty()) {
            val cursor = AtomicInteger(0)
            // A source-WIDE verdict (PACED/QUOTA/ABUSE_STOP/GONE): stops new
            // claims and is routed once — no point fetching more pages from a
            // source that just said "slow down" / "quota" / "gone". Transport
            // JITTER is NOT a source verdict. The LOWEST-ordinal verdict wins
            // (audit M1): a lane that hits a verdict on a lower page replaces a
            // higher one, and a verdict only preempts lanes working on pages AT
            // OR ABOVE it — a lane on a LOWER page finishes its bounded in-place
            // attempts so that page ends up READY or in [transientFailures],
            // never silently unresolved below a GONE seal boundary.
            val verdictFailure = AtomicReference<Pair<Int, Throwable>?>(null)
            fun verdictBlocks(ordinal: Int): Boolean =
                verdictFailure.get()?.let { it.first <= ordinal } == true
            // Pages that failed transiently even after their in-place retries.
            // They do NOT stop the pass (D2: one dead node must not abandon the
            // siblings); the lowest is parked after the pass so the next tick
            // retries only the still-missing pages with a backoff.
            val transientFailures = ConcurrentHashMap<Int, Throwable>()
            val deadlineInterrupted = AtomicBoolean(false)
            coroutineScope {
                repeat(poolSize) {
                    launch(Dispatchers.IO) {
                        while (verdictFailure.get() == null) {
                            // Cancel / park detection (mirrors the old
                            // between-chunks re-check): a job flipped out of
                            // DOWNLOAD (Uncollect, park) stops the lane.
                            if (host.requireJob(job.id).state != DownloadJobState.DOWNLOAD) return@launch
                            val idx = cursor.getAndIncrement()
                            if (idx >= pendingOrdinals.size) return@launch
                            val ordinal = pendingOrdinals[idx]
                            var attempt = 0
                            while (!verdictBlocks(ordinal)) {
                                val failure: Throwable? = try {
                                    fetchOrdinalIntoReady(job, locator, revision, ordinal, ns)
                                    null
                                } catch (timeout: TimeoutCancellationException) {
                                    // Hung socket: no status → JITTER, retried
                                    // on a fresh connection like any transport
                                    // blip (same as the old barrier's mapping).
                                    pageTimeoutException()
                                } catch (cancelled: CancellationException) {
                                    if (currentCoroutineContext()[BoundedPassCloudHandoff]?.expired() == true) {
                                        deadlineInterrupted.set(true)
                                        return@launch
                                    }
                                    throw cancelled
                                } catch (t: Throwable) {
                                    t
                                }
                                if (failure == null) {
                                    host.markPageReady(job.id, revision, ordinal)
                                    clearOrdinalRetryState(job.id, ordinal)
                                    break
                                }
                                val transient = isInPlaceRetryablePageFailure(ns, job.id, ordinal, failure)
                                attempt++
                                if (transient && attempt < IN_PLACE_PAGE_ATTEMPTS) {
                                    // Retry the SAME ordinal in place; the
                                    // adapter's own streak rotates the H@H node.
                                    continue
                                }
                                if (transient) {
                                    // Exhausted in-place: leave the page for a
                                    // post-pass park, keep fetching the rest.
                                    transientFailures[ordinal] = failure
                                    break
                                }
                                // A source-wide verdict: stop new claims; the
                                // lowest-ordinal verdict is the one routed.
                                verdictFailure.updateAndGet { cur ->
                                    if (cur == null || ordinal < cur.first) ordinal to failure else cur
                                }
                                return@launch
                            }
                        }
                    }
                }
            }
            if (deadlineInterrupted.get()) return
            // A cancel/park detected mid-pool must evict retry state and stop
            // this pass, exactly as the old between-chunks check did.
            if (host.requireJob(job.id).state != DownloadJobState.DOWNLOAD) {
                clearAllRetryStateForJob(job)
                return
            }
            // Route exactly ONE failure: the LOWEST failing ordinal across the
            // verdict and every exhausted transient (audit M1, 2026-09-15). This
            // restores the old chunk loop's contract, where a lower retryable
            // failure always beat a higher verdict. It matters for a terminal
            // GONE: `sealTruncated` commits `longestContiguousReadyPrefix`, so
            // sealing while a LOWER page is merely transiently missing would
            // seal at that gap and silently drop every fetched page above it.
            // Routing the lower transient instead parks the job briefly; the
            // next pass re-fetches the gap, and the seal lands only once the
            // prefix below the true GONE page is solid. For a source-wide park
            // verdict (PACED/QUOTA/ABUSE_STOP) this costs at most one short
            // extra pass before the verdict is hit again and applied.
            val failures = HashMap<Int, Throwable>(transientFailures)
            verdictFailure.get()?.let { (ordinal, failure) -> failures[ordinal] = failure }
            if (failures.isNotEmpty()) {
                val ordinal = failures.keys.min()
                routePerPageFailure(job, locator, revision, ordinal, failures.getValue(ordinal))
                return
            }
        }

        val readyPaths = (0 until expected).map { ordinal ->
            readyPageStore.readyPath(job.storageIdentity, revision, ordinal)
        }
        val title = manifest.title ?: locator.title.orEmpty()
        // A32 §2: the locator is the only carrier of the catalog row's facts
        // between the enqueue tap and this FINISH; nothing else here has them.
        // #C65: `locator` above may already be the filled one — see the
        // fillMissingCatalogFacts call right after the manifest fetch.
        val commitMetadata = DownloadCommitMetadata(
            tags = locator.tags,
            language = locator.language,
            category = locator.category,
        )
        // Spec §1.1: three cases on full completion.
        //  - intentLocal (with or without intentCloud, i.e. a plain local
        //    download OR a collect+download whose intents were OR-merged onto
        //    one job): FINISH first — that materializes the Library's
        //    on-device catalog tree — then, if intentCloud is also set, fire
        //    the handoff too so a dual-intent job still reaches cloud instead
        //    of silently never uploading.
        //  - intentCloud only (no intentLocal): SEAL instead of FINISH — a
        //    cloud-only fetch has no Library association to materialize —
        //    then fire the handoff.
        //  - neither flag (legacy job predating intent, or a pure local
        //    download that lost its intent): preserve old behavior, FINISH.
        if (job.intentLocal) {
            host.tryFinish(
                job.id,
                title = title,
                readyPagePaths = readyPaths,
                metadata = commitMetadata,
            )
            if (job.intentCloud) handoffCloudJob(host.requireJob(job.id))
        } else if (job.intentCloud) {
            val sealed = host.markSealed(job.id, truncatedAt = null)
            handoffCloudJob(sealed)
        } else {
            host.tryFinish(
                job.id,
                title = title,
                readyPagePaths = readyPaths,
                metadata = commitMetadata,
            )
        }
        // SEND_BACK item 7: full completion is terminal either way (FINISH
        // or SEALED) — drop any lingering retry-state entries for this job.
        clearAllRetryStateForJob(job)
        transferMeter?.clear(job.id)
    }

    /**
     * A hung manifest fetch (tarpit/stalled socket) never produces a status
     * code or exception on its own — statusCode=0 classifies as
     * [FetchFailureClass.JITTER] via [classifyFetchFailure], the same
     * treatment as any other network-layer blip.
     */
    /**
     * One ordinal: READY short-circuit, else the single-flight upstream fetch
     * under the shared per-source pacing gate + page deadline, publishing into
     * [ReadyPageStore]. Runs on an IO thread inside the parallel window above;
     * throws the raw failure for the caller to route.
     */
    private suspend fun fetchOrdinalIntoReady(
        job: DownloadJob,
        locator: DownloadLocator,
        revision: Int,
        ordinal: Int,
        ns: String,
    ): Boolean {
        val readyNow = readyPageStore.resolveReady(job.storageIdentity, revision, ordinal)
        if (readyNow != null) return false
        // E15 fix 1: the tracker counts transfer time only. [SourcePacingGate.paced]
        // waits for the namespace permit and the spacing slot before running
        // this block, so a page queued for a permit is not in flight yet.
        // The finally fires once per start — success, GONE, a terminal failure,
        // cancellation (including a worker stop) and every other exit of this
        // transfer. [ReadyPageStore.resolveTransfer] invokes this lambda at most
        // once; a retry is a new call and its own bracket.
        readyPageStore.resolveTransfer(
            identity = job.storageIdentity,
            currentRevision = revision,
            ordinal = ordinal,
            transferUpstream = { destination, offset ->
                // Pacing is outside the transfer budget. Streaming adapters
                // enforce their content-length/floor-derived budget and tie
                // cancellation to the active response; legacy adapters retain
                // the injectable deadline below for compatibility.
                // Cancel only the paced transfer, leaving ReadyPageStore publication and
                // Room READY writes outside its scope. Cancellation retains the `.part`.
                withUpstreamWait(job.id) {
                    pacingGate.paced(ns, "job=${job.id} kind=page ordinal=$ordinal", spacing = !adapter.ownsRequestPacing(job.adapterIdentity, locator)) {
                        progressState?.start(job.id, ordinal)
                        try {
                            val result = if (adapter.usesDiskBackedTransfer(job.adapterIdentity, locator)) {
                                adapter.fetchPageToFile(
                                    identity = job.adapterIdentity,
                                    locator = locator,
                                    ordinal = ordinal,
                                    destination = destination,
                                    offset = offset,
                                )
                            } else {
                                // Compatibility adapters retain their injectable deadline;
                                // streaming adapters own their content-length/floor budget.
                                withTimeout(pageFetchDeadlineMs) {
                                    adapter.fetchPageToFile(
                                        identity = job.adapterIdentity,
                                        locator = locator,
                                        ordinal = ordinal,
                                        destination = destination,
                                        offset = offset,
                                    )
                                }
                            }
                            transferMeter?.record(job.id, result.transferredBytes.coerceAtLeast(0L), clockMs())
                            result
                        } finally {
                            progressState?.end(job.id, ordinal)
                        }
                    }
                }
            },
        )
        return true
    }

    private suspend fun <T> withUpstreamWait(jobId: String, block: suspend () -> T): T {
        val bounded = currentCoroutineContext()[BoundedPassCloudHandoff] ?: return block()
        return coroutineScope {
            val wait = checkNotNull(coroutineContext[Job])
            bounded.register(jobId, wait)
            try {
                // Register before checking, so a wait starting after the watcher's snapshot
                // still cannot start a request after the deadline.
                if (bounded.expired()) throw CancellationException("bounded pass deadline")
                block()
            } finally {
                bounded.unregister(jobId, wait)
            }
        }
    }

    private fun manifestTimeoutException(): FetchTransportException = FetchTransportException(
        statusCode = 0,
        resolvedUrl = null,
        bodySample = ByteArray(0),
        message = "manifest fetch exceeded ${manifestFetchDeadlineMs}ms (tarpit/stall)",
    )

    /** Per-page counterpart of [manifestTimeoutException]. */
    private fun pageTimeoutException(): FetchTransportException = FetchTransportException(
        statusCode = 0,
        resolvedUrl = null,
        bodySample = ByteArray(0),
        message = "page fetch exceeded ${pageFetchDeadlineMs}ms (tarpit/stall)",
    )


    /**
     * C14: apply the liveness veto to a [FetchFailureClass.GONE] classification.
     * Returns [FetchFailureClass.JITTER] (retry) when the oracle confirms the
     * gallery is ALIVE; otherwise (DEAD, UNKNOWN, no oracle, or no acquisition
     * URL to check) returns GONE unchanged. Only ever weakens a seal, never
     * creates one.
     *
     * C10 audit A: a GONE the BUCKET produced is not vetoable by the upstream.
     * The routers classify a cloud-routed failure by [fetchTargetNamespace]
     * (CLOUD); the veto then asked the IDENTITY's upstream whether its gallery
     * is alive — a source that never answered this fetch. For a split-shape
     * row (upstream identity + cloud locator) with a live upstream that threw
     * away a correct truncation seal and retried to JITTER_EXHAUSTED. So a
     * cloud-routed fetch short-circuits: the bucket's verdict stands. The
     * explicit-takedown and oracle reads below still use the identity
     * namespace on purpose — for a non-cloud fetch they ARE the upstream.
     */
    private suspend fun vetoGoneIfAlive(
        job: DownloadJob,
        locator: DownloadLocator,
        classified: FetchFailureClass,
        statusCode: Int,
        bodySample: ByteArray,
        notAComic: Boolean = false,
    ): FetchFailureClass {
        if (locator.routesToCloudCollection()) return classified
        // #C12: an OPDS acquisition failure is judged by the catalog, not by
        // its status code — CONFIRM a 5xx / not-a-comic, VETO a 404/410.
        if (job.identity.providerNamespace.startsWith(GalleryIdentityV1.OPDS_PREFIX)) {
            return opdsVerdict(job, locator, classified, statusCode, notAComic)
        }
        if (classified != FetchFailureClass.GONE) return classified
        // Owner decision 2026-09-16: an explicit takedown page seals on sight —
        // the site said the pages are gone; a still-present gdata metadata
        // record must not talk us out of it (see isExplicitTakedown).
        if (isExplicitTakedown(job.identity.providerNamespace, statusCode, bodySample)) {
            DiagLog.i("DownloadPump", "job ${job.id} GONE — explicit takedown page; sealing without the liveness oracle")
            return classified
        }
        val oracle = livenessOracle ?: return classified
        val url = locator.acquisitionUrl.takeIf { it.isNotBlank() } ?: return classified
        val liveness = try {
            oracle(job.identity.providerNamespace, url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DiagLog.w("DownloadPump", "job ${job.id} liveness oracle threw — sealing as today", error)
            Liveness.UNKNOWN
        }
        return when (liveness) {
            Liveness.ALIVE -> {
                DiagLog.i("DownloadPump", "job ${job.id} GONE seal VETOED — liveness oracle says ALIVE; retrying")
                FetchFailureClass.JITTER
            }
            Liveness.DEAD -> {
                DiagLog.i("DownloadPump", "job ${job.id} GONE confirmed DEAD by liveness oracle; sealing")
                FetchFailureClass.GONE
            }
            Liveness.UNKNOWN -> {
                DiagLog.i("DownloadPump", "job ${job.id} liveness oracle UNKNOWN — sealing as today")
                FetchFailureClass.GONE
            }
        }
    }

    /**
     * Manifest-level fetch failure routing. A [FetchTransportException] is
     * classified and routed the same way as a per-page failure — the source
     * stays runnable-later via [DownloadWorkerHost.recordFailure] rather than
     * being hard [DownloadWorkerHost.markFailed]. Only a truly unclassifiable
     * non-transport throwable (a genuine bug, not a source symptom) hard-fails
     * — that is the one case where retrying blindly could spin forever.
     */
    private suspend fun routeManifestFailure(job: DownloadJob, locator: DownloadLocator, t: Throwable) {
        if (t !is FetchTransportException) {
            // #C11 re-audit R5: a raw error's message can embed the acquisition
            // URL (a MalformedURLException always does), and an OPDS catalog's
            // auth key lives in that URL's path — this detail is logged AND
            // persisted as the row's failureDetail, so redact it first.
            val detail = OpdsCatalogClient.redactUrl("${t::class.simpleName}: ${t.message?.take(300)}")
            // #C52 round 5 (round-4 review LOW-10): the throwable is back.
            // Round 3 B3 dropped it on the rationale "DiagLog's scrub would
            // strip an unredacted stack-trace-adjacent message a second time
            // for no benefit" — true of round 3's scrub, false since round 4
            // R7: DiagLog.w now walks up to 3 levels of `t.cause`, printing
            // `ClassName: redactUrl(message)` per level plus a frames-only
            // second line (see DiagLog.w's KDoc). `detail` above is the
            // OUTERMOST message only, and MANIFEST_ERROR is precisely the
            // failure class where the wrapped root cause is the diagnosis —
            // so this is strictly more signal at zero leak cost.
            DiagLog.w("DownloadPump", "job ${job.id} FAILED MANIFEST_ERROR: $detail", t)
            host.markFailed(job.id, "MANIFEST_ERROR", detail)
            clearAllRetryStateForJob(job)
            return
        }
        val ns = fetchTargetNamespace(job.identity, locator)
        val retryContext = retryContextFor(job.id, MANIFEST_ORDINAL_KEY, t.statusCode)
        val cls = vetoGoneIfAlive(
            job, locator,
            classifyFailureForSource(ns, t.statusCode, t.resolvedUrl, t.bodySample, t.message, t.headers, retryContext),
            t.statusCode, t.bodySample,
            notAComic = t.message == OpdsAcquisitionPageBytes.OPDS_NOT_A_COMIC,
        )
        val jitterCount = recordOrdinalAttempt(job.id, MANIFEST_ORDINAL_KEY, t.statusCode, cls)
        when (cls) {
            FetchFailureClass.JITTER -> {
                if (jitterCount >= JITTER_EXHAUSTION_THRESHOLD) {
                    // #C11 audit B1: the per-page path hard-fails after this
                    // many consecutive same-symptom JITTERs; this branch only
                    // parked (doubling, capped at 60 s) and had no terminal
                    // rung, so a manifest that never comes back — a dead OPDS
                    // host, a status-0 transport failure that can never
                    // escalate by classification — was re-probed every 60 s
                    // forever, never FAILED, out of reach of both the bounded
                    // auto-revive and the Retry affordance. Same rung as
                    // per-page: FAILED, where a cloud job still gets its <= 3
                    // revivals and a human Retry starts a fresh streak.
                    val detail = OpdsCatalogClient.redactUrl(t.message ?: "manifest failed $jitterCount consecutive times")
                    DiagLog.w("DownloadPump", "job ${job.id} FAILED JITTER_EXHAUSTED (manifest): $detail")
                    clearAllRetryStateForJob(job)
                    host.markFailed(job.id, "JITTER_EXHAUSTED", detail)
                    return
                }
                // A whole-manifest network blip: retry soon rather than
                // dying. SEND_BACK item 6 (owner bug backlog): this used to
                // be a FLAT park at the source's base interval forever — a
                // source stuck JITTERing on the manifest hot-looped at that
                // same interval indefinitely instead of backing off, unlike
                // the per-page path (see routePerPageFailure). Now doubles
                // per consecutive same-symptom manifest JITTER, same as
                // per-page, capped at JITTER_BACKOFF_CAP_MS; a second
                // same-status manifest failure still escalates via
                // classifyFetchFailure instead (404->GONE, e-h 5xx->PACED).
                val policy = SourceThrottlePolicy.forNamespace(ns)
                host.recordFailure(
                    job.id,
                    cls.label(),
                    t.message ?: "manifest fetch error",
                    now() + jitterBackoffMs(policy.jitterBackoffBaseMs, jitterCount),
                )
            }
            is FetchFailureClass.PACED -> recordPaced(job, ns, cls)
            is FetchFailureClass.QUOTA -> recordQuota(job, ns, cls)
            is FetchFailureClass.ABUSE_STOP -> recordAbuseStop(job, ns, cls)
            FetchFailureClass.GONE -> {
                // A later-tick 404 may still have pages already on device from
                // an earlier successful manifest pass — seal what's there
                // instead of discarding it. Zero pages ready means the
                // gallery never had anything on device to begin with.
                // #C38/D3: the sibling-discard is gone, so the READY set can
                // be non-contiguous; the truncation boundary is the gap-free prefix.
                val readyCount = longestContiguousReadyPrefix(host.readyOrdinals(job.id, job.downloadRevision))
                clearAllRetryStateForJob(job)
                if (readyCount > 0) {
                    // P1 (polish backlog): stamp the SEAL's own reason. The
                    // first 404 of the D1 double-404 path recorded
                    // `JITTER / EHENTAI_HTTP_404` (correct then — one 404 is
                    // a blip); without this the terminally sealed row keeps
                    // advertising that transient class forever.
                    val sealed = sealTruncated(
                        job = job,
                        locator = locator,
                        revision = job.downloadRevision,
                        readyCount = readyCount,
                        failureClass = cls.label(),
                        failureDetail = t.message ?: "source returned HTTP ${t.statusCode} twice",
                    )
                    if (job.intentCloud) {
                        handoffCloudJob(sealed)
                    }
                } else {
                    // P1: zero pages fetched, but the row still carries the
                    // first 404's JITTER record — replace it with the verdict.
                    host.markGone(
                        job.id,
                        failureClass = cls.label(),
                        failureDetail = t.message ?: "source returned HTTP ${t.statusCode} twice",
                    )
                }
            }
        }
    }

    /**
     * #C12: the OPDS half of [vetoGoneIfAlive]. Runs the check for the shapes
     * that need it and maps the answer:
     *  - GONE (404/410): ALIVE → JITTER (veto), DEAD/UNKNOWN → GONE (as today);
     *  - a 5xx (JITTER, or PACED on a same-status repeat), or a 2xx that was
     *    not a comic ([OpdsAcquisitionPageBytes.OPDS_NOT_A_COMIC]): DEAD → GONE
     *    (confirm), ALIVE/UNKNOWN → the class it had (JITTER/PACED ladder);
     *  - anything else (PACED/QUOTA/ABUSE_STOP, a status-0 connection error):
     *    untouched, no check — the catalog cannot answer for those.
     */
    private suspend fun opdsVerdict(
        job: DownloadJob,
        locator: DownloadLocator,
        classified: FetchFailureClass,
        statusCode: Int,
        notAComic: Boolean,
    ): FetchFailureClass {
        val check = opdsExistence ?: return classified
        val isGone = classified == FetchFailureClass.GONE
        // #C12 audit M4: the only "no comic here" shapes are a 5xx and the
        // explicit OPDS_NOT_A_COMIC marker (a 2xx whose body parsed as markup,
        // not a comic). The pre-audit rule keyed the 2xx case off "the body
        // sample is non-empty", which handed the catalog any 2xx-coded
        // transport failure — a truncated read, a proxy hiccup — as if it were
        // a deletion signal. A 5xx classifies JITTER first and PACED on a
        // same-status repeat (catalog UNKNOWN the first time); both get the
        // catalog's word.
        val serverSideShape = statusCode in 500..599 || notAComic
        val needsConfirm = !isGone && serverSideShape &&
            (classified == FetchFailureClass.JITTER || classified is FetchFailureClass.PACED)
        if (!isGone && !needsConfirm) return classified
        val liveness = try {
            check(job.identity, locator)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            DiagLog.w("DownloadPump", "job ${job.id} OPDS existence check threw — keeping ${classified.label()}", error)
            Liveness.UNKNOWN
        }
        return when {
            isGone && liveness == Liveness.ALIVE -> {
                DiagLog.i("DownloadPump", "job ${job.id} OPDS GONE VETOED — the catalog still lists the comic; retrying")
                FetchFailureClass.JITTER
            }
            isGone -> classified
            liveness == Liveness.DEAD -> {
                DiagLog.i("DownloadPump", "job ${job.id} OPDS ${statusCode} confirmed DEAD by the catalog; sealing GONE")
                FetchFailureClass.GONE
            }
            else -> classified
        }
    }

    /**
     * Per-page fetch failure routing (spec §1.2). Unclassifiable non-transport
     * throwables are treated as [FetchFailureClass.JITTER] — same as today's
     * behavior of staying DOWNLOAD and retrying next tick, just now routed
     * through the same symptom table instead of a bare catch-and-return.
     */
    /**
     * #C38/D4: is [failure] a transient JITTER-class failure to retry in place
     * on the same lane, or a source-wide verdict (PACED/QUOTA/ABUSE_STOP/GONE)
     * that must escalate immediately? Pure: classifies but never mutates
     * [ordinalRetryState], so in-place retries do not advance the cross-pass
     * JITTER streak. A non-transport throwable is treated as JITTER, matching
     * [routePerPageFailure]'s catch-all.
     */
    private fun isInPlaceRetryablePageFailure(
        ns: String,
        jobId: String,
        ordinal: Int,
        failure: Throwable,
    ): Boolean {
        if (failure !is FetchTransportException) return true
        val cls = classifyFailureForSource(
            ns,
            failure.statusCode,
            failure.resolvedUrl,
            failure.bodySample,
            failure.message,
            failure.headers,
            retryContextFor(jobId, ordinal, failure.statusCode),
        )
        return cls == FetchFailureClass.JITTER
    }

    private suspend fun routePerPageFailure(
        job: DownloadJob,
        locator: DownloadLocator,
        revision: Int,
        ordinal: Int,
        t: Throwable,
    ) {
        val ns = fetchTargetNamespace(job.identity, locator)
        val transport = t as? FetchTransportException
        val statusCode = transport?.statusCode ?: 0
        val classified = if (transport != null) {
            val retryContext = retryContextFor(job.id, ordinal, statusCode)
            classifyFailureForSource(ns, transport.statusCode, transport.resolvedUrl, transport.bodySample, transport.message, transport.headers, retryContext)
        } else {
            FetchFailureClass.JITTER
        }
        val cls = vetoGoneIfAlive(
            job, locator, classified, statusCode, transport?.bodySample ?: ByteArray(0),
            notAComic = transport?.message == OpdsAcquisitionPageBytes.OPDS_NOT_A_COMIC,
        )
        val jitterCount = recordOrdinalAttempt(job.id, ordinal, statusCode, cls)
        when (cls) {
            FetchFailureClass.JITTER -> {
                if (jitterCount >= JITTER_EXHAUSTION_THRESHOLD) {
                    // S3 Task 9 defect 5 (owner bug backlog): a JITTER park
                    // is supposed to be transient — after this many
                    // consecutive same-symptom retries on the SAME page, the
                    // source is not recovering and an ever-doubling park
                    // just spins the pump forever. Hard-fail instead so it
                    // surfaces rather than parking silently forever.
                    //
                    // SEND_BACK item 7: the whole JOB hard-fails here, not
                    // just this ordinal, so every retry-state entry for the
                    // job (other ordinals, and any manifest entry) must go
                    // too — not only this ordinal's own entry.
                    // #C52 round 3 B2: t.message can embed the acquisition
                    // URL (an OPDS one carries the catalog's auth key in its
                    // path) and this detail is both logged AND persisted as
                    // the row's failureDetail, so redact it before either use.
                    val jitterDetail = OpdsCatalogClient.redactUrl(
                        t.message ?: "page $ordinal failed $jitterCount consecutive times",
                    )
                    DiagLog.w("DownloadPump", "job ${job.id} FAILED JITTER_EXHAUSTED: $jitterDetail")
                    clearAllRetryStateForJob(job)
                    host.markFailed(
                        job.id,
                        "JITTER_EXHAUSTED",
                        jitterDetail,
                    )
                } else {
                    // Transient: stay DOWNLOAD, retry next tick (spec §4.3),
                    // with a doubling backoff per consecutive same-symptom
                    // JITTER on this ordinal so a repeating blip does not
                    // hot-loop the source at a flat interval forever
                    // (bounded by JITTER_BACKOFF_CAP_MS).
                    val policy = SourceThrottlePolicy.forNamespace(ns)
                    host.recordFailure(
                        job.id,
                        cls.label(),
                        t.message,
                        now() + jitterBackoffMs(policy.jitterBackoffBaseMs, jitterCount),
                    )
                }
            }
            is FetchFailureClass.PACED -> recordPaced(job, ns, cls)
            is FetchFailureClass.QUOTA -> recordQuota(job, ns, cls)
            is FetchFailureClass.ABUSE_STOP -> recordAbuseStop(job, ns, cls)
            FetchFailureClass.GONE -> {
                // Truncation wind-down: recompute fresh at seal time — pages
                // marked READY during this pass count too.
                // #C38/D3: gap-free prefix, not the raw count (siblings past
                // the failing ordinal may already be READY now discard is gone).
                val readyCountSoFar = longestContiguousReadyPrefix(host.readyOrdinals(job.id, revision))
                clearAllRetryStateForJob(job)
                if (readyCountSoFar > 0) {
                    // P1 (polish backlog): same seal-reason stamp as the
                    // manifest path — the SEALED row must say GONE, not the
                    // first 404's transient JITTER record.
                    val sealed = sealTruncated(
                        job = job,
                        locator = locator,
                        revision = revision,
                        readyCount = readyCountSoFar,
                        failureClass = cls.label(),
                        failureDetail = t.message ?: "page $ordinal returned HTTP $statusCode twice",
                    )
                    if (job.intentCloud) {
                        handoffCloudJob(sealed)
                    }
                } else {
                    // P1: same verdict stamp as the manifest path.
                    host.markGone(
                        job.id,
                        failureClass = cls.label(),
                        failureDetail = t.message ?: "page $ordinal returned HTTP $statusCode twice",
                    )
                }
            }
        }
    }

    /**
     * A36 (owner bug backlog; owner's phone: Kyouei 22 of 43) — the one place
     * a GONE truncation wind-down is turned into a seal, for both routing
     * paths.
     *
     * A job the user asked to have ON DEVICE ([DownloadJob.intentLocal]) keeps
     * the k pages it did fetch: they are materialized into the catalog
     * download tree exactly as a FINISH would and committed as an INCOMPLETE
     * Library row, so the shelf shows the comic (badged) and the reader can
     * open it. Before this the pages stayed unreachable — a Library row was
     * only ever committed by `tryFinish`, which needs every expected ordinal.
     *
     * A cloud-only job takes the unchanged [DownloadWorkerHost.markSealed]
     * path: it has no Library association to materialize, and its pages go to
     * R2 through `onCloudHandoff` as before.
     *
     * Either way the JOB row is identical — SEALED, `sealedTruncatedAt = k`,
     * the GONE verdict — so nothing about the state machine (S4b/P1) moves.
     *
     * The pages committed are the gap-free prefix `0 until readyCount`, where
     * `readyCount` is [longestContiguousReadyPrefix] of the READY set. Since
     * #C38 removed the sibling-discard, the READY set can be non-contiguous
     * (e.g. {0,1,3}); committing only the prefix keeps the Library row and the
     * reader hole-free, and a page past the gap is simply left on disk.
     * `title` comes from the locator rather than the manifest because the
     * manifest-failure path has no manifest at all — and the locator's title
     * is what the enqueue tap recorded.
     */
    private suspend fun sealTruncated(
        job: DownloadJob,
        locator: DownloadLocator,
        revision: Int,
        readyCount: Int,
        failureClass: String,
        failureDetail: String,
    ): DownloadJob {
        val plainSeal: suspend () -> DownloadJob = {
            host.markSealed(
                job.id,
                truncatedAt = readyCount,
                failureClass = failureClass,
                failureDetail = failureDetail,
            )
        }
        if (!job.intentLocal) return plainSeal()
        val commitMetadata = DownloadCommitMetadata(
            tags = locator.tags,
            language = locator.language,
            category = locator.category,
        )
        val title = locator.title.orEmpty()
        // A36 review M3: nothing was actually truncated. Every pinned page is
        // READY — the fetch loop finished but the process died before
        // tryFinish, and the NEXT pass met the source's 404. Sealing that as
        // "truncated" would badge a complete comic INCOMPLETE while the
        // Detail note stays silent (it needs fetched < expected), i.e. a mark
        // with no explanation. It is a completion, so finish it like one.
        val pinned = job.expectedPageCount
        if (pinned != null && readyCount >= pinned) {
            val finished = host.tryFinish(
                job.id,
                title = title,
                readyPagePaths = (0 until pinned).map { ordinal ->
                    readyPageStore.readyPath(job.storageIdentity, revision, ordinal)
                },
                metadata = commitMetadata,
            )
            if (finished.state == DownloadJobState.FINISH) {
                DiagLog.i(
                    "DownloadPump",
                    "job ${job.id} had every pinned page on device when the source went " +
                        "away — finished instead of sealing truncated (A36)",
                )
                return finished
            }
            // tryFinish declined (an ordinal it disagrees about): fall through
            // rather than leaving the job stuck mid-flight forever.
        }
        val readyPaths = (0 until readyCount).map { ordinal ->
            readyPageStore.readyPath(job.storageIdentity, revision, ordinal)
        }
        // Defensive: `readyCount > 0` is the caller's gate, so an empty list
        // here would mean the ordinals vanished between the two reads. Seal
        // plainly rather than committing a Library row with nothing behind it.
        if (readyPaths.isEmpty()) return plainSeal()
        DiagLog.i(
            "DownloadPump",
            "job ${job.id} sealing truncated at $readyCount page(s) and committing them " +
                "as an INCOMPLETE Library row (A36)",
        )
        return host.sealTruncatedWithLocalPages(
            jobId = job.id,
            truncatedAt = readyCount,
            title = title,
            readyPagePaths = readyPaths,
            failureClass = failureClass,
            failureDetail = failureDetail,
            metadata = commitMetadata,
        )
    }

    private suspend fun recordPaced(job: DownloadJob, ns: String, cls: FetchFailureClass.PACED) {
        val policy = SourceThrottlePolicy.forNamespace(ns)
        val waitSeconds = cls.retryAfterSeconds ?: policy.defaultPacedWaitSeconds
        host.recordFailure(
            job.id,
            cls.label(),
            "Rate limited by source, resuming in ${waitSeconds}s",
            now() + waitSeconds * 1_000L,
        )
    }

    private suspend fun recordQuota(job: DownloadJob, ns: String, cls: FetchFailureClass.QUOTA) {
        val policy = SourceThrottlePolicy.forNamespace(ns)
        host.recordFailure(
            job.id,
            cls.label(),
            "Image quota exhausted, resuming after idle window",
            now() + policy.quotaIdleMs,
        )
    }

    /**
     * ABUSE_STOP just records the classification + a resume window; it never
     * hard-fails. All namespaces get a conservative floor
     * ([ABUSE_STOP_MIN_WINDOW_MS]) so a challenging source (nh/OPDS Cloudflare
     * challenge, e-h IP ban) cannot busy-loop the queue while parked — surfacing
     * a WebView solve flow is a later slice, so for now the job simply sits
     * until the floor elapses (or a future manual kick). e-h additionally
     * honors its own QUOTA idle window when longer than the floor (their ban
     * page states its own expiry, which we do not parse yet — TODO).
     */
    private suspend fun recordAbuseStop(job: DownloadJob, ns: String, cls: FetchFailureClass.ABUSE_STOP) {
        val policy = SourceThrottlePolicy.forNamespace(ns)
        val windowMs = maxOf(policy.quotaIdleMs, ABUSE_STOP_MIN_WINDOW_MS)
        host.recordFailure(job.id, cls.label(), cls.detail, now() + windowMs)
    }

    private suspend fun handoffCloudJob(job: DownloadJob) {
        val bounded = onBoundedPassCloudHandoff
        if (bounded != null && currentCoroutineContext()[BoundedPassCloudHandoff] != null) {
            bounded(job)
        } else {
            onCloudHandoff(job)
        }
    }
}

/** Present on [DownloadWorkerLoop.runBoundedPass] lanes, so a cloud handoff can tell them apart. */
private class BoundedPassCloudHandoff(
    private val deadline: Long,
    private val monotonicClock: () -> Long,
) : AbstractCoroutineContextElement(Key) {
    private val waits = ConcurrentHashMap<String, MutableSet<Job>>()

    fun expired(): Boolean = monotonicClock() >= deadline

    fun register(jobId: String, wait: Job) {
        waits.computeIfAbsent(jobId) { ConcurrentHashMap.newKeySet() }.add(wait)
    }

    fun unregister(jobId: String, wait: Job) {
        waits[jobId]?.remove(wait)
    }

    fun cancelUpstreamWaits() {
        waits.values.forEach { jobs ->
            jobs.forEach { it.cancel(CancellationException("bounded pass deadline")) }
        }
    }

    companion object Key : CoroutineContext.Key<BoundedPassCloudHandoff>
}
