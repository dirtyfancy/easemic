package com.easemic.app.sync

import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.importing.CatalogRequestGate
import com.easemic.app.importing.ehentai.EhentaiClient
import com.easemic.app.importing.nhentai.NhentaiClient
import com.easemic.app.importing.nhentai.NhentaiHttpGet
import com.easemic.app.importing.nhentai.NhentaiHttpResponse
import com.easemic.app.importing.opds.OpdsCatalogClient
import com.easemic.app.sync.adfilter.TrailingAdStripper
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReferenceArray

/** S4 Task 1 review (M7): log tag for [CloudCollectionIngest]'s cover-thumbnail fallback path. */
private const val TAG_CLOUD_INGEST_COVER = "CloudIngestCover"

data class CloudCollectionIngestRequest(
    val cloudComicId: String,
    val titleHint: String,
    val providerId: String,
    val acquisitionUrl: String,
    val indexGalleryId: Long? = null,
    val frontGalleryId: Long? = null,
    val frontToken: String? = null,
    /**
     * When set to a complete, non-empty local page set whose length meets the
     * upstream plan page count (e.g. FINISH catalog download files), the upload
     * uses these files (capped to the plan count) and skips upstream page image
     * GETs. Incomplete / missing entries or under-count vs plan fall back to
     * upstream fetch.
     */
    val localPageFiles: List<File>? = null,
    /**
     * True for a SEALED cloud-intent download job handoff (spec §1.1): skip
     * the upstream manifest/plan fetch entirely and upload [localPageFiles]
     * as-is — device-resident pages are authoritative, there is no live plan
     * count to reconcile against. False (default) preserves the legacy
     * Collect ingest path (`ingestIndex`/`ingestFront`), which always fetches
     * a live plan first and only opportunistically substitutes local files
     * via [usableLocalPages].
     */
    val sealedLocalOnly: Boolean = false,
    /** Threaded from the sealed download job's `sealedTruncatedAt` (spec §1.3 option A); null = complete. */
    val truncatedFetchedPages: Int? = null,
    /**
     * The meta this upload must carry forward. A non-null value does NOT mean
     * "migration": on the Slice-C2 old-bucket migration branch it is the OLD
     * bucket's [CloudComicMeta] for this comic, freshly read at upload time
     * (never cached — the old bucket is read-only, never deleted, so a fresh
     * read is always safe and never stale); on the ordinary sealed-local path
     * it is the download locator's own catalog facts (#C30,
     * `feature/collect/LocatorCloudMeta.kt`), which the enqueue recorded and
     * which the upload used to drop. When set,
     * [CloudCollectionIngest.ingestSealedLocalOnly] uses its
     * title/tags/artists/groups/category/language/providerId instead of the
     * request's bare [titleHint]/[providerId]; `pageCount` and
     * `collectedAtEpochMs` are always the ingest's own. Null only when neither
     * exists — a locator with no facts (pre-A32, OPDS) or migration's honest
     * degradation when the old bucket's meta couldn't be read at upload time
     * (e.g. its store became unavailable mid-migration): the legacy
     * sealed-handoff shape is written exactly (title/pageCount/providerId
     * only, empty tags/artists/groups, null category/language) and pages
     * already fetched are landed rather than lost, at the documented cost of
     * the richer fields.
     */
    val preservedMeta: CloudComicMeta? = null,
)

sealed class CloudCollectionIngestResult {
    /**
     * [card]/[meta] are the just-uploaded tier-1 card and tier-2 detail — null
     * only for legacy positional-constructor call sites (tests). Populated by
     * every production ingest path so [com.easemic.app.feature.collect.CloudIngestRunner]
     * can live-upsert the local mirror the moment an upload completes, instead
     * of waiting for the next hydration pass to discover it.
     */
    data class Synced(
        val cloudComicId: String,
        val pageCount: Int,
        val card: CloudCollectionCard? = null,
        val meta: CloudComicMeta? = null,
        /**
         * A22 §4: the manifest generations this upload's index rewrite moved
         * the bucket from/to (null only for legacy positional call sites —
         * tests). `CloudIngestRunner` forwards it to `onSynced` so AppGraph
         * can stamp the local mirror instead of forcing a full re-walk.
         */
        val indexGenerations: CloudIndexGenerations? = null,
    ) : CloudCollectionIngestResult()
    data class Failed(val message: String) : CloudCollectionIngestResult()

    /** C37-f: the account is rate-limited for [retryAfterSeconds]; not a failure of this comic and not an attempt spent. */
    data class RateLimited(val retryAfterSeconds: Int) : CloudCollectionIngestResult()
}

/**
 * C36: fetch counts and upload counts are DIFFERENT QUANTITIES, and a single
 * row counter cannot carry both honestly. [onFetch] counts pages acquired;
 * [onUpload] counts pages made durable on the bucket. For an
 * already-downloaded comic the fetch phase is a synchronous announcement that
 * reaches `N/N` in the frame the tap is accepted — so without a boundary
 * between the two phases the user reads a finished-looking `31/31` while the
 * upload has not sent a byte.
 *
 * [onUploadStarting] IS that boundary. From the instant it fires the row must
 * show the real uploaded count — 0 on a first upload, the durable
 * checkpoint's count on a resume — and it fires BEFORE thumbnail preparation,
 * page hashing and the bucket listing, all of which precede the first PUT and
 * used to be spent displaying the fetch count. [onUpload] then reports one
 * event per page completed, including pages skipped because their content
 * hash is already on the bucket: a hash-skipped page is already durable, so
 * counting it as uploaded is the truth, not a shortcut.
 */
interface CloudCollectionIngestProgress {
    fun onFetch(ready: Int, expected: Int, deltaBytes: Int)

    /** The fetch→upload boundary. See the interface doc: [uploadedSoFar] is the REAL uploaded count. */
    fun onUploadStarting(uploadedSoFar: Int, expected: Int)

    /**
     * The checkpoint's ordinal-ordered count. Since #C46 D1 this no longer
     * drives the Activity row (it lags the wire by the head-of-line page);
     * [onUploadProgress] does. Kept for callers that want the contiguous prefix.
     */
    fun onUpload(ready: Int, expected: Int, deltaBytes: Int)

    /** #C46 D1: completion-order page events and the finishing tail. */
    fun onUploadProgress(event: CloudUploadProgress) = Unit
}

object CloudCollectionIngestProgressNone : CloudCollectionIngestProgress {
    override fun onFetch(ready: Int, expected: Int, deltaBytes: Int) = Unit
    override fun onUploadStarting(uploadedSoFar: Int, expected: Int) = Unit
    override fun onUpload(ready: Int, expected: Int, deltaBytes: Int) = Unit
}

/**
 * Uploads the layered archive to cloud. Does not write Library Downloads.
 *
 * Prefer [CloudCollectionIngestRequest.localPageFiles] when Collect targets an
 * already-downloaded comic (upload straight from local). Collect-only (no usable
 * local pages) fetches upstream → durable temp checkpoint → R2.
 *
 * Mid-job [CloudCollectionIngestResult.Failed] keeps the per-comic workDir +
 * checkpoint so auto-resume continues from the last fetched/uploaded page.
 * ONLY [CloudCollectionIngestResult.Synced] clears it (SF-high): an
 * interrupted ingest — a system stop or a REPLACE-ing user kick, which arrive
 * here as `Failed(CANCELLED)` — is precisely the case that must resume, and
 * the queue parks its row WAITING with its page count expecting exactly that
 * (C35-a). Wiping `pages/` + `checkpoint.json` underneath such a row made
 * every "resume" re-fetch and re-upload the whole comic from page zero.
 */
class CloudCollectionIngest(
    private val storeFactory: () -> CloudCollectionStore,
    private val indexClientFactory: () -> NhentaiClient = {
        NhentaiClient(beforeRequest = { CatalogRequestGate.Shared.acquire() })
    },
    private val indexImageGet: NhentaiHttpGet = NhentaiHttpGet { url ->
        NhentaiClient.defaultGet(url)
    },
    private val frontClientFactory: () -> EhentaiClient = {
        error("front client not configured")
    },
    private val workRoot: File,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val progress: CloudCollectionIngestProgress = CloudCollectionIngestProgressNone,
    /**
     * Produces one compressed derivative per page for `thumbs/`. The first
     * page's original bytes are handled separately for the durable cover:
     * production validates and persists those bytes unchanged so the shelf
     * cover does not inherit the 320px preview limit.
     */
    private val thumbnailer: (File) -> ByteArray? = { null },
    /**
     * #E35: borrows page 0 while its staging lease is held and copies it to a
     * durable on-device path, returning that path so the Cloud shelf can
     * render covers with zero R2 reads once synced. Null (the default) means
     * [CloudCollectionCard.coverPath] is never set — the exact behavior every
     * existing call site had before this seam existed.
     */
    private val coverWriter: ((cloudComicId: String, source: File) -> String?)? = null,
    /**
     * #E15 T4: a page larger than this is never READ for its grid thumb when
     * the thumbnailer returns nothing — the pack entry becomes
     * [RAW_THUMB_FALLBACK_PLACEHOLDER] instead of the raw page bytes (which
     * made a 20 MiB animated page a 20 MiB thumb-pack entry beside the cover
     * PUT). Injectable so tests run small bodies.
     */
    private val rawThumbFallbackMaxBytes: Long = RAW_THUMB_FALLBACK_MAX_BYTES,
    /**
     * #E118: Optional trailing commercial ad stripper powered by TypeSafe Jev
     * + perceptual dHash fingerprints. Runs before [uploadWithCheckpoint] when
     * both [adStripper] and [typesafeApiKey] are non-null and the gallery's
     * tags include `extraneous ads`.
     */
    private val adStripper: TrailingAdStripper? = null,
    private val typesafeApiKey: () -> String? = { null },
) {
    /**
     * Fix-audit MEDIUM-2 (spec §2.5 memory bound): the number of decoded
     * thumbnails still strongly reachable in [MemoisingThumbSupplier] the
     * moment its upload returned. The bound requires each consumed thumb's
     * bytes to be released as its pack lane pulls it, so a completed upload
     * retains ZERO. -1 until an upload has run. A test seam, not production
     * state.
     */
    internal var lastThumbSupplierRetained: Int = -1
        private set

    fun ingest(
        request: CloudCollectionIngestRequest,
        shouldAbort: () -> Boolean = { false },
    ): CloudCollectionIngestResult {
        // Owner 2026-09-09: OPDS comics may be collected to cloud, but only via
        // the fetch-job route that hands over local pages (sealedLocalOnly) —
        // this ingest has no way to pull an http(s) OPDS acquisition itself.
        if (OpdsCloudIngestGuard.mustRefuseDirectIngest(request.acquisitionUrl, hasLocalPages = request.sealedLocalOnly)) {
            return CloudCollectionIngestResult.Failed("CLOUD_INGEST_UNSUPPORTED_SOURCE")
        }
        val workDir = cloudIngestWorkDir(workRoot, request.cloudComicId).also { it.mkdirs() }
        val result = try {
            when {
                request.sealedLocalOnly -> ingestSealedLocalOnly(request, workDir, shouldAbort)
                request.indexGalleryId != null -> ingestIndex(request, workDir, shouldAbort)
                request.frontGalleryId != null && request.frontToken != null ->
                    ingestFront(request, workDir, shouldAbort)
                else -> CloudCollectionIngestResult.Failed("CLOUD_INGEST_UNSUPPORTED_SOURCE")
            }
        } catch (unreadable: UnreadableCloudLibraryException) {
            // Fail closed: an unreadable bucket is not a transient ingest
            // failure. Propagate so callers surface the locked copy and
            // auto-resume does not retry a bucket that can never work. The
            // workDir stays put, exactly as a retained Failed would leave it.
            throw unreadable
        } catch (newer: NewerCloudFormatException) {
            // A newer cloud object is a terminal compatibility boundary, not
            // a transient ingest failure. Propagate so the queue can block
            // with the update copy instead of retrying or writing around it.
            throw newer
        } catch (locked: LibraryLockedException) {
            // Same fail-closed contract: an encrypted library the app hasn't
            // unlocked yet is not a transient failure either.
            throw locked
        } catch (damaged: DamagedLibraryLockException) {
            // The keyslot itself is unreadable — distinct message, same
            // propagate-don't-flatten contract.
            throw damaged
        } catch (rateLimited: CloudRateLimitedException) {
            // C37-f: Cloudflare blocks the whole ACCOUNT for its Retry-After
            // window, so this is neither this comic's failure nor an attempt
            // worth spending — the caller waits the window out and resumes
            // from the durable checkpoint (which is why the workDir is
            // retained below, exactly like a transient Failed).
            CloudCollectionIngestResult.RateLimited(rateLimited.retryAfterSeconds)
        } catch (error: Exception) {
            CloudCollectionIngestResult.Failed(error.message ?: "CLOUD_INGEST_FAILED")
        }
        if (!retainCloudIngestWorkDir(result)) {
            cloudIngestClearWorkDir(workRoot, request.cloudComicId)
        }
        return result
    }

    /**
     * Upload-only path for a SEALED cloud-intent download job handoff (spec
     * §1.1/§1.2). Never touches the network for a manifest/plan — the fetch
     * pipeline already owns page acquisition and has declared the gallery
     * done (or truncation-complete). [request.localPageFiles] is authoritative:
     * a missing/empty/unreadable set here is a bug or race in the handoff,
     * not something to paper over by falling back to [ingestIndex]/[ingestFront].
     */
    private fun ingestSealedLocalOnly(
        request: CloudCollectionIngestRequest,
        workDir: File,
        shouldAbort: () -> Boolean,
    ): CloudCollectionIngestResult {
        val localPages = request.localPageFiles
        if (localPages.isNullOrEmpty() || localPages.any { !it.isFile || it.length() <= 0L }) {
            return CloudCollectionIngestResult.Failed("CLOUD_INGEST_PAGES_MISSING_POST_SEAL")
        }
        announceLocalFetch(localPages, shouldAbort)?.let { return it }
        writeCloudIngestCheckpoint(
            workDir,
            CloudIngestCheckpoint(
                fetchedCount = localPages.size,
                uploadedCount = readCloudIngestCheckpoint(workDir)?.uploadedCount ?: 0,
            ),
        )
        if (shouldAbort()) {
            return CloudCollectionIngestResult.Failed(CANCELLED)
        }
        val preserved = request.preservedMeta
        val candidateTags = preserved?.tags ?: emptyList()
        val store = storeFactory()
        val stripOutcome = adStripper?.stripIfApplicable(
            pageFiles = localPages,
            tags = candidateTags,
            apiKey = typesafeApiKey(),
            cloudStore = store.client,
            shouldAbort = shouldAbort,
        )
        val finalPages = stripOutcome?.keptFiles ?: localPages
        val trimmedAdCount = stripOutcome?.trimmedCount?.takeIf { it > 0 } ?: preserved?.trimmedAdPages
        val meta = CloudComicMeta(
            cloudComicId = request.cloudComicId,
            title = preserved?.title?.takeIf { it.isNotBlank() } ?: request.titleHint,
            tags = candidateTags,
            artists = preserved?.artists ?: emptyList(),
            groups = preserved?.groups ?: emptyList(),
            category = preserved?.category,
            language = preserved?.language,
            pageCount = finalPages.size,
            providerId = preserved?.providerId?.takeIf { it.isNotBlank() } ?: request.providerId,
            // HIGH regression fix: a migrated card must carry the OLD
            // bucket's ORIGINAL source acquisition forward, never the
            // synthetic r2source:// URL the row was enqueued under —
            // CloudCollectLifecycle.cardMatches compares this against a
            // bookmark's acquisitionUrl on the next reconcile, and only the
            // original source URL can ever match. Without this a migrated
            // comic is invisible to `presentInIndex` and gets RECOLLECTed
            // from source (duplicating a still-live comic, or dropping a
            // taken-down one straight back out of the library — defeating
            // migration). markDone (AppGraph's onSynced hook) is driven off
            // the ROW's r2source acquisition separately (threaded through
            // CloudIngestRunner's onSynced call), never off this field.
            acquisitionRef = preserved?.acquisitionRef?.takeIf { it.isNotBlank() } ?: request.acquisitionUrl,
            collectedAtEpochMs = clockMs(),
            // LOW-1 fix: CloudIngestRunner always forces
            // request.truncatedFetchedPages to null for an r2source row (no
            // live plan to reconcile a truncation count against) — the OLD
            // bucket's own truncatedFetchedPages (preserved) is the only
            // surviving source of truth, so it must win when present.
            truncatedFetchedPages = preserved?.truncatedFetchedPages ?: request.truncatedFetchedPages,
            trimmedAdPages = trimmedAdCount,
        )
        val upload = uploadWithCheckpoint(workDir, meta, finalPages, store)
        return CloudCollectionIngestResult.Synced(request.cloudComicId, finalPages.size, upload.card, meta, upload.generations)
    }

    private fun ingestIndex(
        request: CloudCollectionIngestRequest,
        workDir: File,
        shouldAbort: () -> Boolean,
    ): CloudCollectionIngestResult {
        val plan = indexClientFactory().fetchGallery(request.indexGalleryId!!)
        val localPages = usableLocalPages(
            request.localPageFiles,
            plan.imageUrls.size,
            trimmedAdPages = request.preservedMeta?.trimmedAdPages ?: 0,
        )
        val pageFiles = if (localPages != null) {
            announceLocalFetch(localPages, shouldAbort)?.let { return it }
            writeCloudIngestCheckpoint(
                workDir,
                CloudIngestCheckpoint(
                    fetchedCount = localPages.size,
                    uploadedCount = readCloudIngestCheckpoint(workDir)?.uploadedCount ?: 0,
                ),
            )
            localPages
        } else {
            if (plan.imageUrls.isEmpty()) {
                return CloudCollectionIngestResult.Failed("CLOUD_INGEST_EMPTY")
            }
            when (
                val fetched = fetchUpstreamPages(
                    workDir = workDir,
                    expected = plan.imageUrls.size,
                    shouldAbort = shouldAbort,
                ) { index ->
                    val url = plan.imageUrls[index]
                    CatalogRequestGate.Shared.acquire()
                    indexImageGet.get(url)
                }
            ) {
                null -> return CloudCollectionIngestResult.Failed(CANCELLED)
                is PageFetchOutcome.Failed -> return CloudCollectionIngestResult.Failed(fetched.message)
                is PageFetchOutcome.Ready -> fetched.files
            }
        }
        if (shouldAbort()) {
            return CloudCollectionIngestResult.Failed(CANCELLED)
        }
        val store = storeFactory()
        val stripOutcome = adStripper?.stripIfApplicable(
            pageFiles = pageFiles,
            tags = plan.tags,
            apiKey = typesafeApiKey(),
            cloudStore = store.client,
            shouldAbort = shouldAbort,
        )
        val finalPages = stripOutcome?.keptFiles ?: pageFiles
        val trimmedAdCount = stripOutcome?.trimmedCount?.takeIf { it > 0 } ?: request.preservedMeta?.trimmedAdPages
        val meta = CloudComicMeta(
            cloudComicId = request.cloudComicId,
            title = request.titleHint.ifBlank { plan.title },
            tags = plan.tags,
            artists = plan.artists,
            groups = plan.groups,
            category = plan.category,
            language = plan.language,
            pageCount = finalPages.size,
            providerId = request.providerId.ifBlank { NhentaiEnableStore.SOURCE_ID },
            acquisitionRef = request.acquisitionUrl,
            collectedAtEpochMs = clockMs(),
            trimmedAdPages = trimmedAdCount,
        )
        val upload = uploadWithCheckpoint(workDir, meta, finalPages, store)
        return CloudCollectionIngestResult.Synced(request.cloudComicId, finalPages.size, upload.card, meta, upload.generations)
    }

    private fun ingestFront(
        request: CloudCollectionIngestRequest,
        workDir: File,
        shouldAbort: () -> Boolean,
    ): CloudCollectionIngestResult {
        val client = frontClientFactory()
        val plan = client.fetchGallery(request.frontGalleryId!!, request.frontToken!!)
        val localPages = usableLocalPages(
            request.localPageFiles,
            plan.pageViewPaths.size,
            trimmedAdPages = request.preservedMeta?.trimmedAdPages ?: 0,
        )
        val pageFiles = if (localPages != null) {
            announceLocalFetch(localPages, shouldAbort)?.let { return it }
            writeCloudIngestCheckpoint(
                workDir,
                CloudIngestCheckpoint(
                    fetchedCount = localPages.size,
                    uploadedCount = readCloudIngestCheckpoint(workDir)?.uploadedCount ?: 0,
                ),
            )
            localPages
        } else {
            if (plan.pageViewPaths.isEmpty()) {
                return CloudCollectionIngestResult.Failed("CLOUD_INGEST_EMPTY")
            }
            when (
                val fetched = fetchUpstreamPages(
                    workDir = workDir,
                    expected = plan.pageViewPaths.size,
                    shouldAbort = shouldAbort,
                ) { index ->
                    val path = plan.pageViewPaths[index]
                    val image = client.fetchPageImage(path, index + 1)
                    NhentaiClient.defaultGet(image.imageUrl)
                }
            ) {
                null -> return CloudCollectionIngestResult.Failed(CANCELLED)
                is PageFetchOutcome.Failed -> return CloudCollectionIngestResult.Failed(fetched.message)
                is PageFetchOutcome.Ready -> fetched.files
            }
        }
        if (shouldAbort()) {
            return CloudCollectionIngestResult.Failed(CANCELLED)
        }
        val store = storeFactory()
        val stripOutcome = adStripper?.stripIfApplicable(
            pageFiles = pageFiles,
            tags = plan.tags,
            apiKey = typesafeApiKey(),
            cloudStore = store.client,
            shouldAbort = shouldAbort,
        )
        val finalPages = stripOutcome?.keptFiles ?: pageFiles
        val trimmedAdCount = stripOutcome?.trimmedCount?.takeIf { it > 0 } ?: request.preservedMeta?.trimmedAdPages
        val meta = CloudComicMeta(
            cloudComicId = request.cloudComicId,
            title = request.titleHint.ifBlank { plan.title },
            tags = plan.tags,
            artists = plan.artists,
            groups = plan.groups,
            category = plan.category,
            language = plan.language,
            pageCount = finalPages.size,
            providerId = request.providerId,
            acquisitionRef = request.acquisitionUrl,
            collectedAtEpochMs = clockMs(),
            trimmedAdPages = trimmedAdCount,
        )
        val upload = uploadWithCheckpoint(workDir, meta, finalPages, store)
        return CloudCollectionIngestResult.Synced(request.cloudComicId, finalPages.size, upload.card, meta, upload.generations)
    }

    private sealed class PageFetchOutcome {
        data class Ready(val files: List<File>) : PageFetchOutcome()
        data class Failed(val message: String) : PageFetchOutcome()
    }

    /**
     * Fetch missing pages into the durable checkpoint directory. Already-present
     * non-empty `%04d.bin` files are skipped (resume). Returns null when aborted.
     */
    private fun fetchUpstreamPages(
        workDir: File,
        expected: Int,
        shouldAbort: () -> Boolean,
        getPage: (index: Int) -> NhentaiHttpResponse,
    ): PageFetchOutcome? {
        val pagesDir = File(workDir, "pages").also { it.mkdirs() }
        val prior = readCloudIngestCheckpoint(workDir)
        // Page files are source of truth for fetch resume. JSON fetchedCount is
        // advisory (UI / upload sidecar) — never skip a missing/empty hole.
        val resumeFrom = cloudIngestNextFetchIndex(pagesDir, expected).coerceAtMost(expected)
        if (resumeFrom > 0) {
            progress.onFetch(resumeFrom, expected, 0)
        }
        for (index in resumeFrom until expected) {
            if (shouldAbort()) {
                return null
            }
            val response = getPage(index)
            if (response.code !in 200..299 || response.body.isEmpty()) {
                writeCloudIngestCheckpoint(
                    workDir,
                    CloudIngestCheckpoint(
                        fetchedCount = index,
                        uploadedCount = prior?.uploadedCount ?: 0,
                    ),
                )
                return PageFetchOutcome.Failed("CLOUD_INGEST_PAGE_${response.code}")
            }
            val file = cloudIngestPageFile(pagesDir, index)
            file.writeBytes(response.body)
            writeCloudIngestCheckpoint(
                workDir,
                CloudIngestCheckpoint(
                    fetchedCount = index + 1,
                    uploadedCount = prior?.uploadedCount ?: 0,
                ),
            )
            progress.onFetch(index + 1, expected, response.body.size)
        }
        val files = (0 until expected).map { cloudIngestPageFile(pagesDir, it) }
        if (files.any { !it.isFile || it.length() <= 0L }) {
            return PageFetchOutcome.Failed("CLOUD_INGEST_CHECKPOINT_INCOMPLETE")
        }
        writeCloudIngestCheckpoint(
            workDir,
            CloudIngestCheckpoint(
                fetchedCount = expected,
                uploadedCount = prior?.uploadedCount ?: 0,
            ),
        )
        return PageFetchOutcome.Ready(files)
    }

    private fun uploadWithCheckpoint(
        workDir: File,
        meta: CloudComicMeta,
        pageFiles: List<File>,
        store: CloudCollectionStore = storeFactory(),
    ): CloudArchiveUpload {
        // Re-review L-c: cleared up front, so a FAILED upload reports -1 rather
        // than the previous upload's number (the write below is on the success
        // path only).
        lastThumbSupplierRetained = -1
        val prior = readCloudIngestCheckpoint(workDir)
        val start = (prior?.uploadedCount ?: 0).coerceIn(0, pageFiles.size)
        // C36: the fetch→upload boundary, announced BEFORE any of the work
        // below. Thumbnail prep, page hashing and the bucket listing all run
        // before the first PUT, and the fetch count (N/N for an
        // already-downloaded comic) used to sit on screen through all of it.
        // See CloudCollectionIngestProgress.
        progress.onUploadStarting(start, pageFiles.size)
        // #C5: prepare one compressed derivative per page with bounded local
        // parallelism. The old serial loop made every upload pay all decode
        // time before the first cloud PUT could begin.
        //
        // #C21 §2.5 (audit B1): the bounded decode pool is unchanged, but it is
        // no longer a BARRIER — the supplier hands each ordinal to the pack
        // lane that asks for it, so the decode phase overlaps the page PUTs
        // instead of preceding them (~23 s recovered on the owner's 610-page
        // comic). Closed only after the cover derivative below has been read.
        return MemoisingThumbSupplier(pageFiles, thumbnailer, rawThumbFallbackMaxBytes).use { thumbs ->
            val upload = store.uploadComicArchiveWithIndex(
                meta = meta,
                pageFiles = pageFiles,
                thumbSupplier = thumbs,
                startPageOrdinal = start,
                // #C51: page packs are streamed into the per-comic work dir, never held in
                // memory. `cloudIngestClearWorkDir` removes the whole dir on Synced; each
                // pack file is deleted as soon as it is durable, so a big comic never keeps
                // a second copy of itself on disk.
                packsDir = File(workDir, "packs"),
                onProgress = { event -> progress.onUploadProgress(event) },
            ) { ready, total, bytes ->
                writeCloudIngestCheckpoint(
                    workDir,
                    CloudIngestCheckpoint(
                        fetchedCount = prior?.fetchedCount?.coerceAtLeast(pageFiles.size) ?: pageFiles.size,
                        // The checkpoint claims only what THIS call just
                        // re-verified (hashed present on the bucket or freshly
                        // PUT) — never the clamped floor below, or a crash right
                        // after write could claim a page never actually checked.
                        uploadedCount = ready,
                    ),
                )
                // S1 (C36 review): CloudCollectionStore's raw per-page callback
                // always counts up from 1 regardless of where the upload resumed
                // (see uploadComicArchiveWithIndex) — its own pre-announce fires
                // `start` once, then the per-page loop fires 1..N from scratch.
                // Reported straight through, a resume from checkpoint 2/5 renders
                // 2 -> 1 -> 2 -> 3 -> 4 -> 5 on screen: the row visibly regresses
                // the moment uploading begins. The row must never claim fewer
                // pages than it already announced, so clamp what reaches the UI
                // to the checkpoint's floor; the checkpoint write above is
                // unaffected and keeps recording the store's own honest count.
                progress.onUpload(maxOf(ready, start), total, bytes)
            }
            // #E35: borrow page 0 synchronously, only after its own thumbnail decoded.
            // The writer copies it before this ingest's staging lease is released.
            // A thumbnail failure still falls back to a raw R2 grid thumb, but
            // does not leave an unvalidated local file behind.
            //
            // #C21 §2.5: the decode is already done (the packs consumed it), so
            // this reads the memo — it never starts a second decode, and it is
            // still ordinal 0's OWN decode that gates the durable cover.
            lastThumbSupplierRetained = thumbs.retainedCount()
            val coverPath = if (coverWriter != null && thumbs.coverDecoded()) {
                pageFiles.firstOrNull()?.let { coverWriter.invoke(meta.cloudComicId, it) }
            } else null
            if (coverPath != null) upload.copy(card = upload.card.copy(coverPath = coverPath)) else upload
        }
    }

    /**
     * #C21 §2.5 (audit B1): one bounded decode pool, started the moment the
     * upload begins, feeding the pack lanes as they ask for ordinals. Each
     * page is decoded exactly ONCE and [get] is called exactly once per ordinal
     * by its pack lane.
     *
     * The decode lambda swallows its own throwable and records it, which is
     * what keeps the fallback per-thumb: one page that will not decode falls
     * back to its raw bytes and the other 609 are unaffected (the pre-#C21
     * behaviour, preserved message for message).
     *
     * Fix-audit MEDIUM-2 / re-review BLOCKING-2 — what is and is NOT delivered.
     * DELIVERED: release-on-consume. Each ordinal's decoded bytes are released
     * as its pack lane pulls it (the slot is cleared), so no thumbnail survives
     * into the manifest/index tail, and a RESUMED upload — where most pages are
     * already present, so no page PUTs are queued and the pack lanes run at
     * once — really is bounded. Only ordinal 0's decode OUTCOME (a boolean, not
     * its bytes) is kept, for the durable cover gate.
     * NOT DELIVERED: spec §2.5's "at most 6 packs × 24 thumbs in flight" bound.
     * The page-phase PEAK is still ≈N, because `init` submits all N decodes
     * immediately while the pack lanes are queued in the SAME upload pool
     * BEHIND every page PUT — so the 4-thread decode pool finishes every
     * derivative long before the first `get()` fires, and every slot is full in
     * between. Delivering the bound needs a sliding submit window (keep
     * ~THUMBNAIL_PARALLELISM + ThumbPack.SIZE outstanding, topped up inside
     * `get`) plus a peak pin; that is still open, not done here.
     */
    private class MemoisingThumbSupplier(
        private val pageFiles: List<File>,
        thumbnailer: (File) -> ByteArray?,
        private val rawThumbFallbackMaxBytes: Long,
    ) : CloudCollectionStore.ThumbSupplier, AutoCloseable {
        private val executor = Executors.newFixedThreadPool(THUMBNAIL_PARALLELISM) { runnable ->
            Thread(runnable, "cloud-thumb-decode").apply { isDaemon = true }
        }

        // Fix-audit MEDIUM-2: a per-ordinal slot cleared on read (see [get]),
        // so a decoded thumb is reachable only between its decode and the one
        // pull by its pack lane.
        private val decoded = AtomicReferenceArray<Future<ByteArray?>?>(pageFiles.size)

        // Fix-audit MEDIUM-2: ordinal 0's decode outcome, captured as [get]
        // consumes the slot, so the cover derivative can still gate on "did the
        // page-0 thumbnail decode" after the bytes themselves have been released.
        @Volatile private var ordinalZeroDecoded: Boolean? = null

        init {
            pageFiles.forEachIndexed { ordinal, file ->
                decoded.set(
                    ordinal,
                    executor.submit<ByteArray?> {
                        try {
                            thumbnailer(file)
                        } catch (error: Exception) {
                            DiagLog.w(
                                TAG_CLOUD_INGEST_COVER,
                                "thumbnailer failed for ${OpdsCatalogClient.redactUrl(file.name)} (ordinal $ordinal); using raw page bytes as grid thumb",
                                error,
                            )
                            null
                        }
                    },
                )
            }
        }

        override val size: Int get() = pageFiles.size

        /**
         * Blocks until this ordinal's derivative exists, then RELEASES it: the
         * slot is cleared so the bytes are collectable once the pack lane that
         * called this drops them (spec §2.5 memory bound). Called exactly once
         * per ordinal, by its pack lane — so the fallback warning below fires
         * at most once per page, and a second read (a null slot) is a contract
         * violation.
         */
        override fun get(ordinal: Int): ByteArray {
            val future = decoded.getAndSet(ordinal, null)
                ?: throw IllegalStateException("thumb $ordinal read twice")
            val bytes = awaitDecoded(ordinal, future)
            if (ordinal == 0) ordinalZeroDecoded = bytes != null
            return bytes ?: run {
                val page = pageFiles[ordinal]
                if (page.length() > rawThumbFallbackMaxBytes) {
                    // #E15 T4: reading a 20 MiB animated page here made the thumb
                    // pack itself page-sized — three page-sized arrays beside the
                    // cover PUT, five when encrypted. The grid gets a neutral
                    // placeholder instead and the page file is never read.
                    DiagLog.w(
                        TAG_CLOUD_INGEST_COVER,
                        "page ${OpdsCatalogClient.redactUrl(page.name)} is ${page.length()} bytes, above the " +
                            "$rawThumbFallbackMaxBytes raw grid-thumb fallback (ordinal $ordinal); packing a placeholder thumb",
                    )
                    return rawThumbnailFallback(page, rawThumbFallbackMaxBytes)
                }
                DiagLog.w(
                    TAG_CLOUD_INGEST_COVER,
                    "thumbnailer returned no bytes for ${OpdsCatalogClient.redactUrl(page.name)} (ordinal $ordinal); using raw page bytes as grid thumb",
                )
                rawThumbnailFallback(page, rawThumbFallbackMaxBytes)
            }
        }

        /** The thumbnailer's OWN output for [future], or null when it failed or returned nothing. */
        private fun awaitDecoded(ordinal: Int, future: Future<ByteArray?>): ByteArray? = try {
            future.get()
        } catch (error: ExecutionException) {
            // The lambda above already swallows and records; this is only
            // reachable for a Throwable it cannot catch (an Error).
            DiagLog.w(TAG_CLOUD_INGEST_COVER, "thumbnailer failed for ordinal $ordinal", error.cause)
            null
        }

        /**
         * Whether ordinal 0's thumbnail decoded — the gate for the durable
         * cover. Uses the outcome captured when [get] consumed the slot; falls
         * back to peeking the still-present Future for an upload that threw
         * before pack 0 ran (the slot is then untouched).
         */
        fun coverDecoded(): Boolean =
            ordinalZeroDecoded
                ?: (decoded.get(0)?.let { future -> runCatching { future.get() }.getOrNull() != null } ?: false)

        /** Fix-audit MEDIUM-2: decoded thumbs still held (non-null slots). Zero once every pack lane has pulled its ordinals. */
        fun retainedCount(): Int = (0 until pageFiles.size).count { decoded.get(it) != null }

        override fun close() {
            executor.shutdownNow()
        }
    }

    private fun announceLocalFetch(
        localPages: List<File>,
        shouldAbort: () -> Boolean,
    ): CloudCollectionIngestResult? {
        localPages.forEachIndexed { index, file ->
            if (shouldAbort()) {
                return CloudCollectionIngestResult.Failed(CANCELLED)
            }
            progress.onFetch(
                index + 1,
                localPages.size,
                file.length().toInt().coerceAtLeast(0),
            )
        }
        return null
    }

    companion object {
        const val CANCELLED = "CLOUD_INGEST_CANCELLED"
        const val THUMBNAIL_PARALLELISM: Int = 4

        /**
         * #E15 T4: the largest page whose RAW bytes may still become its grid
         * thumb when the thumbnailer returns nothing (C33 behaviour, capped).
         * Above it the thumb pack gets [RAW_THUMB_FALLBACK_PLACEHOLDER] and the
         * page file is not read at all — a raw multi-MiB page in the thumb pack
         * held up to five page-sized arrays alongside the cover PUT.
         */
        const val RAW_THUMB_FALLBACK_MAX_BYTES: Long = 1L shl 20

        /**
         * #E15 T4: what a page above [RAW_THUMB_FALLBACK_MAX_BYTES] contributes
         * to its thumb pack: a valid 1×1 neutral-gray PNG, 67 bytes. The grid
         * renders a neutral cell; the real page still lives in its page pack.
         */
        val RAW_THUMB_FALLBACK_PLACEHOLDER: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(),
            0x0A.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(), 0x49.toByte(), 0x48.toByte(),
            0x44.toByte(), 0x52.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x08.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x3A.toByte(), 0x7E.toByte(), 0x9B.toByte(), 0x55.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x0A.toByte(), 0x49.toByte(), 0x44.toByte(), 0x41.toByte(), 0x54.toByte(), 0x78.toByte(), 0xDA.toByte(),
            0x63.toByte(), 0x68.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x82.toByte(), 0x00.toByte(),
            0x81.toByte(), 0xDA.toByte(), 0x45.toByte(), 0x08.toByte(), 0x3B.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte(), 0xAE.toByte(),
            0x42.toByte(), 0x60.toByte(), 0x82.toByte(),
        )
    }
}

/**
 * Local pages are usable only when the first [expectedPageCount] entries exist,
 * are non-empty, and the list length is at least [expectedPageCount] (plan
 * imageUrls / pageViewPaths). Shorter complete-looking lists must not upload an
 * under-count (callers fall back to upstream fetch). Longer lists take only
 * [expectedPageCount] so overshoot does not upload extra pages.
 */
internal fun usableLocalPages(
    files: List<File>?,
    expectedPageCount: Int,
    trimmedAdPages: Int = 0,
): List<File>? {
    if (files.isNullOrEmpty()) return null
    if (expectedPageCount <= 0) return null
    val effectiveExpected = if (trimmedAdPages > 0 && files.size + trimmedAdPages == expectedPageCount) {
        files.size
    } else {
        expectedPageCount
    }
    if (files.size < effectiveExpected) return null
    val selected = files.take(effectiveExpected)
    if (selected.any { !it.isFile || it.length() <= 0L }) return null
    return selected
}

fun retainCloudIngestWorkDir(result: CloudCollectionIngestResult): Boolean =
    // C37-f: a RateLimited attempt resumes from the very same checkpoint once
    // the account's window clears — wiping its workDir would re-fetch and
    // re-upload every page already done, straight back into the rate limit.
    //
    // SF-high (C35 audit): CANCELLED is retained for the same reason, and used
    // to be the one exception. An interruption is not the user abandoning the
    // comic — `CloudSyncQueue.markInterrupted` parks the row WAITING with its
    // real page count so the next pump resumes it — so throwing the checkpoint
    // away turned every stop/REPLACE into a full re-fetch. Only Synced clears.
    result is CloudCollectionIngestResult.RateLimited ||
        result is CloudCollectionIngestResult.Failed
