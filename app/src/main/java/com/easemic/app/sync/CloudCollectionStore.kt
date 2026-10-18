package com.easemic.app.sync

import com.easemic.app.feature.download.SourceThrottlePolicy
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.source.SeriesDetailSnapshot
import com.easemic.app.feature.source.over
import com.easemic.app.feature.source.sameSeriesText
import com.easemic.app.feature.source.valueOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bucket contents this app version cannot read (e.g. legacy sealed objects).
 * Fail closed; never clobber. [MESSAGE] is the locked user-facing copy — UI
 * layers surface it verbatim instead of a generic "list failed".
 */
class UnreadableCloudLibraryException(cause: Throwable? = null) : Exception(MESSAGE, cause) {
    companion object {
        const val MESSAGE: String =
            "This bucket holds data this app version cannot read. Empty it and connect again."
    }
}

/** A22 §4: an upload's index card plus the manifest generations the index rewrite moved the bucket from/to. */
data class CloudArchiveUpload(
    val card: CloudCollectionCard,
    val generations: CloudIndexGenerations?,
)

/**
 * Layered Cloud Collection archive on an [ObjectStore]. Objects are
 * uploaded as-is: images keep their bytes, a sniffed extension and a sniffed
 * `Content-Type`; JSON stays JSON under `application/json`, so any S3 client
 * can read the library — plaintext by default. An opted-in library instead
 * hands this store a [client] already wrapped by CloudLibraryGate, which
 * seals/unseals object contents transparently; keys and layout are unchanged.
 *
 * Upload order: page packs + the cover object + thumbnail packs (one shared
 * pool) → pages.manifest → meta → collection index.
 *
 * #C51 (manifest v5): high-resolution pages travel as byte-sized STORED zips —
 * one `pages/pack-NNNN.zip` per ~[PagePack.TARGET_BYTES] of consecutive ordinals
 * — and the manifest records each page's pack, data offset and length, so a
 * reader fetches a page as an HTTP `Range` of its pack ([downloadPage]). A
 * single [ObjectStore.listObjects] call per upload — of the whole comic prefix —
 * establishes which packs the bucket already has: a pack is skipped when its
 * listed SIZE equals the locally built pack's, which holds because packs are
 * deterministic. The bucket, not the local checkpoint, stays authoritative.
 *
 * The card's COVER stays a standalone content-addressed full-resolution page-0
 * object (`pages/<sha256hex>.<ext>`, the hash of the page's PLAINTEXT bytes,
 * computed here above any [CloudLibraryGate]/`EncryptedObjectStore` seal), so
 * `downloadCover`, the cover cache and the durable cover are untouched by the
 * layout change. Thumbs travel as ordinal-keyed STORED zips — one
 * `thumbs/pack-NNNN.zip` per [ThumbPack.SIZE] ordinals (#C47-C) — not
 * content-addressed, so a present pack key says nothing about its content and
 * every upload overwrites them. Comics written under the older layouts (v1
 * ordinal-keyed `pages/0000.<ext>`, v2–v4 hash-keyed page objects) remain
 * readable; only new uploads use v5.
 */
class CloudCollectionStore(
    internal val client: ObjectStore,
    private val chunkSize: Int = CloudCollectionIndex.DEFAULT_CHUNK_SIZE,
    private val seriesIndexJournal: SeriesIndexJournal =
        (client as? SeriesIndexObjectStore)?.journal ?: SeriesIndexJournal(),
    /**
     * Problem sink (P4, owner principle "never silent; WARN for real
     * problems"). The only thing routed here today is a swallowed
     * [ObjectStore.listKeys] failure inside [discardComic] — the permanent
     * delete path, where losing the authoritative prefix listing can strand
     * objects on the bucket forever. That is a real problem, not a routine
     * outcome, so it is WARN. Injectable so tests can assert what was
     * recorded; production writes to logcat.
     */
    private val log: (String) -> Unit = { message -> android.util.Log.w("CloudCollect", message) },
) {
    /**
     * Per-comic object manifests, memoised for the lifetime of this store so a
     * page/thumb read loop resolves extensions without a GET per object. Refreshed
     * on upload, dropped on discard.
     */
    private val manifests = ConcurrentHashMap<String, CloudPageManifestPayload>()

    /** Per-comic gate so concurrent page fetches share one manifest GET (C10). */
    private val manifestLocks = ConcurrentHashMap<String, Any>()

    /**
     * #C47-C: the last [THUMB_PACK_MEMO] thumb packs read through this
     * instance, LRU by access (keyed by object key). Only ever touched under
     * `synchronized(thumbPacks)` — an access-ordered LinkedHashMap reorders on
     * every get, so even reads mutate it.
     */
    private val thumbPacks = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > THUMB_PACK_MEMO
    }

    /**
     * Bumped by [forgetThumbPacks]. A pack download runs outside the memo lock,
     * so a reader that fetched a pack BEFORE a re-upload/discard forgot it must
     * not insert those (now stale) bytes afterwards: the insert is skipped when
     * the epoch moved while the download was in flight (audit MEDIUM-3).
     */
    private val thumbPackEpoch = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * #C51: the last [PAGE_WINDOW_MEMO] page-pack RANGE windows read through this instance,
     * LRU by access, keyed by `(pack key, window start)`. A lookup is a containment scan over
     * at most three entries, so the reader's serial walk and the 6-lane pump both find the
     * window a neighbour already fetched. Guards [pageWindowsInFlight] too — the monitor is
     * held only for bookkeeping, never across a request.
     */
    private val pageWindows = object : LinkedHashMap<Pair<String, Long>, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Long>, ByteArray>?): Boolean =
            size > PAGE_WINDOW_MEMO
    }

    /**
     * #C51 single-flight: windows currently being fetched, keyed exactly like [pageWindows].
     * Six lanes asking for pages inside one window wait for it instead of issuing six
     * requests for the same bytes; a window of a DIFFERENT pack is never blocked, because
     * the monitor is dropped before the request goes out.
     */
    private val pageWindowsInFlight = HashMap<Pair<String, Long>, PageWindowRequest>()

    /**
     * Bumped by [forgetPageWindows]. A window request runs outside the memo lock, so a reader
     * that started BEFORE a re-upload/discard forgot the comic must not insert those (now
     * stale) bytes afterwards — the same epoch guard as the thumb packs (#C47 audit MEDIUM-3).
     */
    private val pageWindowEpoch = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * #C51: one TILE of a page pack — the ordinals `[first, last]` it serves and the byte
     * range `[start, start + length)` of the pack object that covers them. Produced by
     * [packWindows]; a pure function of the manifest, so the read path and anyone counting
     * requests (the device probe) agree by construction.
     */
    internal data class PageWindow(
        val packIndex: Int,
        val first: Int,
        val last: Int,
        val start: Long,
        val length: Int,
    )

    private class PageWindowRequest(
        val future: java.util.concurrent.CompletableFuture<ByteArray> = java.util.concurrent.CompletableFuture(),
    ) {
        /** Rethrows the owner's failure to every waiter, so a retry re-fetches instead of serving nothing. */
        fun await(): ByteArray =
            try {
                future.get()
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            }
    }

    init {
        require(chunkSize > 0) { "chunkSize must be > 0" }
    }

    /**
     * #C21 §2.5 (audit B1): lazily-produced grid thumbnails — [size] ordinals,
     * [get] blocks until that ordinal's bytes exist. A supplier lets the thumb
     * packs be built from inside the upload pool while the page PUTs are
     * already flying, instead of decoding every derivative to completion
     * before the first socket opens.
     *
     * [get] is called exactly ONCE per ordinal, by the pack lane that owns it,
     * so an implementation may (and `CloudCollectionIngest`'s does) log a
     * fallback there without repeating itself.
     */
    interface ThumbSupplier {
        val size: Int
        fun get(ordinal: Int): ByteArray
    }

    /** The pre-#C21 shape: bytes the caller already decoded, handed over as-is. */
    private class MaterialisedThumbs(private val thumbs: List<ByteArray>) : ThumbSupplier {
        override val size: Int get() = thumbs.size
        override fun get(ordinal: Int): ByteArray = thumbs[ordinal]
    }

    /** Legacy shape (card only); production ingest uses [uploadComicArchiveWithIndex] to learn the generations. */
    fun uploadComicArchive(
        meta: CloudComicMeta,
        pageFiles: List<File>,
        thumbBytes: List<ByteArray> = emptyList(),
        thumbSupplier: ThumbSupplier? = null,
        startPageOrdinal: Int = 0,
        onProgress: ((event: CloudUploadProgress) -> Unit)? = null,
        onPageUploaded: ((ready: Int, total: Int, bytes: Int) -> Unit)? = null,
    ): CloudCollectionCard = uploadComicArchiveWithIndex(
        meta = meta,
        pageFiles = pageFiles,
        thumbBytes = thumbBytes,
        thumbSupplier = thumbSupplier,
        startPageOrdinal = startPageOrdinal,
        onProgress = onProgress,
        onPageUploaded = onPageUploaded,
    ).card

    fun uploadComicArchiveWithIndex(
        meta: CloudComicMeta,
        pageFiles: List<File>,
        thumbBytes: List<ByteArray> = emptyList(),
        /**
         * #C21 §2.5: when non-null this WINS over [thumbBytes] — the packs pull
         * each ordinal from it inside their own lane, so decoding overlaps the
         * page PUTs instead of preceding them.
         */
        thumbSupplier: ThumbSupplier? = null,
        startPageOrdinal: Int = 0,
        /**
         * #C51: where the page packs are BUILT. The ingest hands in its per-comic work dir
         * (`<workDir>/packs`), which its own cleanup removes on `Synced`; anything else gets
         * a temp dir removed when the pool closes. Never null in production.
         */
        packsDir: File? = null,
        /** #C51: the pack size target; #C50: the transport's own preference unless a test overrides it. */
        packTargetBytes: Long = client.preferredPackBytes,
        /** Internal layout seam used by format-2 chapters; null keeps the gallery contract. */
        objectLayout: CloudObjectLayout? = null,
        /** Metadata object written after the v5 objects. Defaults to gallery meta.json. */
        metadataKeyOverride: String? = null,
        metadataBytesOverride: ByteArray? = null,
        /** Chapter uploads stop after durable objects and publish their own series tail. */
        publishIndex: Boolean = true,
        /** Format-2 chapter metadata is an announcement and must stay after all v5 objects. */
        metadataAlwaysTail: Boolean = false,
        /**
         * #C46 D1: completion-order events for the UI. See [CloudUploadProgress].
         *
         * Declared BEFORE [onPageUploaded] on purpose: [onPageUploaded] is the
         * LAST parameter and two callers pass it as a trailing lambda
         * (`CloudCollectionIngest.uploadWithCheckpoint`, and a parallel-upload
         * test). Moving it off the end would silently rebind those lambdas.
         */
        onProgress: ((event: CloudUploadProgress) -> Unit)? = null,
        onPageUploaded: ((ready: Int, total: Int, bytes: Int) -> Unit)? = null,
    ): CloudArchiveUpload {
        require(pageFiles.isNotEmpty()) { "pageFiles required" }
        require(meta.pageCount == pageFiles.size) {
            "pageCount ${meta.pageCount} != files ${pageFiles.size}"
        }
        require(startPageOrdinal in 0..pageFiles.size) {
            "startPageOrdinal $startPageOrdinal out of range for ${pageFiles.size} pages"
        }
        // Trimmed so the manifest memo keys match the `.trim()` used by every read.
        val id = meta.cloudComicId.trim()
        val layout = objectLayout ?: GalleryCloudObjectLayout(id)
        val metadataKey = metadataKeyOverride ?: layout.metaKey()
        val metadataBytes = metadataBytesOverride ?: CloudComicMeta.encode(meta)

        if (startPageOrdinal > 0) {
            onPageUploaded?.invoke(startPageOrdinal, pageFiles.size, 0)
        }
        // #C21 §2.5: the count is all that is needed up front — the BYTES are
        // pulled ordinal by ordinal inside the pack lanes, and each one's
        // extension is sniffed there and recorded in [thumbExtSlots]. Nothing
        // here touches a thumbnail, so no decode can delay the first PUT.
        val thumbs: ThumbSupplier = thumbSupplier ?: MaterialisedThumbs(thumbBytes)
        val thumbCount = thumbs.size
        val thumbExtSlots = arrayOfNulls<String>(thumbCount)
        val packCount = ThumbPack.packCount(thumbCount)
        // #C46 D1: tail objects = every thumbnail pack PUT + manifest + meta + index.
        val tailTotal = packCount + 3
        // #C46 D5: the on-device trace of this upload — ids and counts only,
        // never a key, a bucket name or a credential.
        //
        // #C21 §2.4 (audit H1): logged HERE, at the very top, before the local
        // hashing pass and the presence LIST. It used to be logged after both,
        // so `finish ms=` excluded a phase measured at ~1.2 s of a 12.0 s
        // upload and no logcat could show it. `present=` cannot be known yet —
        // it is the LIST's answer — so it moved to the `upload prepared` stamp
        // below, which is that hidden phase's own cost.
        val uploadStartedMs = System.currentTimeMillis()
        DiagLog.i("CloudSync", "upload start id=$id pages=${pageFiles.size} thumbs=$thumbCount packs=$packCount")

        // Set-difference resume, pure S3: ONE prefix-scoped listing per upload
        // (this comic only — never the whole bucket, which paged through
        // thousands of keys before a resumed upload could PUT its first page).
        // Presence is by content hash, never ETag — required for encrypted
        // libraries, whose ciphertext is non-deterministic.
        val comicPrefix = layout.prefix()
        val pagesPrefix = comicPrefix + "pages/"
        // #C51: packs are STREAMED to disk and PUT from there — six lanes × 32 MiB of pack
        // in RAM is not acceptable on the owner's phone. The ingest hands in its work dir;
        // every other caller gets a temp dir this call owns and removes.
        val ownedPacksDir = if (packsDir == null) {
            java.nio.file.Files.createTempDirectory("pcl-packs").toFile()
        } else {
            null
        }
        val packsRoot = (packsDir ?: ownedPacksDir!!).also { it.mkdirs() }

        // Parallel page PUTs (owner 2026-09-09: the bucket is the user's own
        // R2/S3 — no rate limit to respect, go as fast as the link allows).
        // Progress is still reported in ordinal order so the ingest
        // checkpoint's uploadedCount stays a contiguous prefix; a page that
        // fails after its retries fails the whole call, and the set-difference
        // resume above picks up whatever siblings did land.
        //
        // One shared pool: thumb PUTs are queued right behind the page PUTs so
        // the tiny, latency-bound thumb requests overlap the bandwidth-bound
        // page transfers instead of forming a second serial phase (C37-e).
        //
        // #C21 §2.4: the pool is opened BEFORE the hashing pass so the two
        // requests that depend on nothing local — the presence LIST and the
        // index prefetch — fly while the CPU hashes.
        val pageEntries = ArrayList<CloudManifestPageEntryV2>(pageFiles.size)
        // Assigned by the hashing pass INSIDE the pool's try block (#C21 §2.4)
        // and read again by the manifest/cover tail below it.
        val pageExts: List<String>
        val pageHashes: List<String>
        val pageSizes: List<Long>
        // Fix-audit HIGH-1: decided from the presence LIST below, read again by
        // the tail after the pool closes (same shape as the three above).
        val alreadyCarded: Boolean
        // #C51: the v5 page index, built by the pack lanes and read by the manifest encode.
        val pagePacks: List<CloudPagePackEntry>
        val pageOffsets: List<Long>
        val executor = Executors.newFixedThreadPool(UPLOAD_PARALLELISM) { runnable ->
            Thread(runnable, "cloud-put").apply { isDaemon = true }
        }
        // #C21 §2.3 (audit B2 steps 3+4): the collection index read used to sit
        // in the serial tail. It depends on nothing this upload produces, so it
        // overlaps the page phase; `upsertCollectionIndex` re-reads ONLY the
        // manifest under the lock and reuses these chunks when the generation is
        // unchanged. Null when the prefetch failed — that just means the full
        // read happens under the lock, exactly as before.
        var prefetchedIndex: IndexSnapshot? = null
        var indexPrefetch: java.util.concurrent.FutureTask<IndexSnapshot?>? = null
        try {
            // Fix-audit MEDIUM-1: the prefetch runs on its OWN daemon thread,
            // not one of the UPLOAD_PARALLELISM lanes. A 200-comic index is
            // `1 + chunks` serial GETs (manifest, then each chunk), which used
            // to hold a page lane for that whole time (a 5-wide page phase on a
            // multi-chunk index). Daemon and bounded by the RTTs it issues; a
            // failed upload lets it finish on its own — it is never observed.
            indexPrefetch = if (!publishIndex) null else java.util.concurrent.FutureTask<IndexSnapshot?> {
                // A failed prefetch is NOT an upload failure: it is an
                // optimisation, and the authoritative read still happens under
                // the lock (where a real outage fails the upload as it always did).
                try {
                    readIndexSnapshot(
                        CloudCollectionKeys::indexChunkKey,
                        CloudCollectionKeys.collectionManifestKey(),
                    )
                } catch (error: NewerCloudFormatException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
            indexPrefetch?.let { Thread(it, "cloud-index-prefetch").apply { isDaemon = true }.start() }
            // #C21 §2.4 (audit H1): the LIST depends only on the prefix, so it
            // runs CONCURRENTLY with the hashing loop below instead of after
            // it. It is still joined before the set-difference decision, so
            // that decision still sees an authoritative listing before the
            // first PUT. The lane measures its own wall time for the trace.
            val listMs = java.util.concurrent.atomic.AtomicLong(0L)
            // #C51: listObjects, not listKeys — the pack presence check needs the SIZES the
            // listing already carries. A transport that cannot report them answers -1 and
            // every pack is re-PUT, which is correct and merely slower.
            val listFuture: Future<List<ObjectSummary>> = executor.submit<List<ObjectSummary>> {
                val listStartedMs = System.currentTimeMillis()
                try {
                    client.listObjects(comicPrefix)
                } finally {
                    listMs.set(System.currentTimeMillis() - listStartedMs)
                }
            }
            // Every page is sniffed + hashed (even ones a checkpoint claims are
            // done) so the manifest describes the whole comic honestly and a lying
            // checkpoint can never skip a PUT the bucket doesn't actually have.
            // Hashes are streamed off disk: a 40-page comic is tens of MB and must
            // never sit in memory all at once (owner's phone, 2026-09-09).
            val hashStartedMs = System.currentTimeMillis()
            val pageTypes = pageFiles.map { contentTypeOf(it) }
            pageExts = pageTypes.map { it.extension }
            pageHashes = pageFiles.map { AwsSigV4.sha256HexOfFile(it) }
            pageSizes = pageFiles.map { it.length() }
            val hashMs = System.currentTimeMillis() - hashStartedMs

            // C37-f: a listing failure is a failure — an empty set here used to
            // mean "re-PUT everything", which turned every transient 5xx into a
            // full re-upload of an already-present comic. The `get()` rethrows
            // the lane's cause, so that semantics is unchanged by #C21 §2.4.
            val listed = try {
                listFuture.get()
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            }
            // Fix-audit HIGH-1: a pages manifest on the bucket means this id
            // has (or is about to have) a card. Every writer PUTs the manifest
            // before the card and `discardComic` deletes both, so "manifest
            // present" covers every carded case; a manifest without a card (a
            // crashed first upload) merely takes the serial path, which is
            // harmless.
            alreadyCarded = listed.any { it.key == layout.pagesManifestKey() }
            if (alreadyCarded) {
                // Do not overwrite a page manifest written by a newer app. A
                // malformed/temporarily unavailable manifest keeps the legacy
                // resume behavior; only the typed newer-format verdict blocks.
                try {
                    val existingManifest = downloadOrNull(layout.pagesManifestKey())
                    if (existingManifest != null) decodeOrUnreadable { CloudPageManifest.decode(existingManifest) }
                } catch (error: NewerCloudFormatException) {
                    throw error
                } catch (_: Exception) {
                    // The existing upload path decides how to handle other
                    // read failures after the authoritative prefix listing.
                }
            }
            val listedSizes = listed.associate { it.key to it.sizeBytes }
            val presentHashes = listed.asSequence()
                .map { it.key }
                .filter { it.startsWith(pagesPrefix) }
                .mapNotNull { key -> hashFromPageKey(key, pagesPrefix) }
                .toSet()
            // #C51: the packs the bucket already lists for this comic — a resume hint only;
            // whether each is USABLE is decided by its size once the local pack is built.
            val presentPacks = listed.count {
                it.key.startsWith(pagesPrefix) && it.key.substringAfterLast('/').startsWith("pack-")
            }

            // #C51: consecutive ordinals, ~32 MiB per pack, never splitting a page.
            val groups = PagePack.group(pageSizes, packTargetBytes)
            val packOffsetSlots = arrayOfNulls<List<Long>>(groups.size)
            val packByteSlots = LongArray(groups.size)
            val pageCompletions = AtomicInteger(0)
            // #C21 §2.4: the cost of everything between `upload start` and the
            // first PUT, in one line — the phase the old trace hid entirely.
            DiagLog.i(
                "CloudSync",
                "upload prepared id=$id listMs=${listMs.get()} hashMs=$hashMs presentPacks=$presentPacks",
            )
            val pagePackPuts: List<Future<*>> = groups.mapIndexed { packIndex, group ->
                executor.submit {
                    val endExclusive = group.first + group.count
                    val file = File(packsRoot, PagePack.fileName(packIndex))
                    val written = PagePack.write(
                        out = file,
                        firstOrdinal = group.first,
                        pages = pageFiles.subList(group.first, endExclusive),
                        exts = pageExts.subList(group.first, endExclusive),
                    )
                    packOffsetSlots[packIndex] = written.offsets
                    packByteSlots[packIndex] = written.bytes
                    val key = layout.pagePackKey(packIndex)
                    // Presence by SIZE (spec amendment): the same pages always build the same
                    // pack, entry names and STORED sizes included, so a listed object of
                    // exactly this size IS this pack. Never an ETag — an encrypted library's
                    // ciphertext is non-deterministic, and there it simply never matches.
                    if (listedSizes[key] != written.bytes) {
                        putWithRetryFile(key, file, PagePack.MIME)
                    }
                    // The pack has served its purpose; a resume rebuilds it from the pages
                    // (spec amendment), so keeping it would only double the work dir on disk.
                    file.delete()
                    val done = pageCompletions.addAndGet(group.count)
                    onProgress?.invoke(
                        CloudUploadProgress.Page(
                            done = done,
                            total = pageFiles.size,
                            bytes = written.bytes.toInt(),
                        ),
                    )
                    // #C46 D5: one line per pack — a comic is at most a couple of dozen.
                    DiagLog.i("CloudSync", "upload page done=$done/${pageFiles.size}")
                }
            }
            // #C51 (spec amendment): the card's cover stays the standalone full-resolution
            // page-0 object, so `downloadCover`, the cover cache and the durable cover are
            // untouched by the layout change. One extra object per comic, content-addressed
            // like before — skipped when the bucket already has that hash.
            val hash0 = pageHashes.firstOrNull()
            val coverPut: Future<*>? = if (hash0 == null || hash0 in presentHashes) {
                null
            } else {
                executor.submit {
                    putWithRetryFile(
                        layout.pageKeyByHash(hash0, pageExts[0]),
                        pageFiles[0],
                        pageTypes[0].mime,
                    )
                }
            }
            // #C21 §2.2 (audit B2 step 2): on a FIRST upload meta.json rides the
            // SAME pool as the pages — submitted right behind them, ahead of the
            // thumb packs — instead of costing its own serial round trip after
            // the manifest. That is safe because the id is unreachable until its
            // index card lands (the very last write): every `downloadMeta`
            // caller reaches the id through a card, so an early meta.json cannot
            // be observed, not even after a failure.
            //
            // Fix-audit HIGH-1: on a RE-upload the card ALREADY exists, so the
            // same early PUT would publish a meta.json that a failed page could
            // leave contradicting both the card and the v4 pages manifest (and
            // `CloudHydrationEngine` copies it into Detail). A carded id
            // therefore keeps the serial tail PUT below, which is ordered after
            // the pages manifest — one round trip back, only on a re-upload, and
            // never on the backlog of first uploads #C21 is about.
            val metaPut: Future<*>? = if (alreadyCarded || metadataAlwaysTail) {
                null
            } else {
                executor.submit {
                    put(metadataKey, metadataBytes)
                }
            }
            // #C47-C: thumbs travel as STORED zips of ThumbPack.SIZE ordinals —
            // one PUT per pack (26 objects for a 610-page comic, not 610), still
            // queued behind the page PUTs in the shared pool (C37-e overlap).
            // Packs are ordinal-keyed like the thumbs they replace, so for the
            // same reason (a present key says nothing about its content) they
            // are always re-PUT: overwriting in place is cheap and self-heals a
            // bad thumb on resume. Encoded inside the lane, so at most
            // UPLOAD_PARALLELISM packs (~320 KB each at the measured median
            // thumb) are in memory at once.
            val packsDone = AtomicInteger(0)
            val packPuts: List<Future<*>> = (0 until packCount).map { packIndex ->
                val first = ThumbPack.firstOrdinal(packIndex)
                val endExclusive = minOf(first + ThumbPack.SIZE, thumbCount)
                val key = layout.thumbPackKey(first)
                executor.submit {
                    // #C21 §2.5: the lane pulls its OWN 24 ordinals — a blocking
                    // read that waits only on those thumbs, never on the whole
                    // comic's decode. Each is sniffed here and its extension
                    // recorded for the manifest; the pack join below is what
                    // publishes those writes to the manifest encode (Future.get
                    // is the happens-before), which is why the manifest must
                    // stay after the join.
                    val packThumbs = (first until endExclusive).map { thumbs.get(it) }
                    val packExts = packThumbs.mapIndexed { offset, bytes ->
                        contentTypeOf(bytes).extension.also { ext -> thumbExtSlots[first + offset] = ext }
                    }
                    putWithRetry(
                        key,
                        { ThumbPack.encode(first, packThumbs, packExts) },
                        ThumbPack.MIME,
                    )
                    packsDone.incrementAndGet()
                }
            }
            pageFiles.indices.forEach { ordinal ->
                // #C51: an ordinal is durable once the PACK holding it is. Consecutive
                // ordinals of one pack join the same future, so the checkpoint still climbs
                // 1..N in ORDINAL order — it just advances a pack at a time.
                try {
                    pagePackPuts[PagePack.packIndexOf(groups, ordinal)].get()
                } catch (error: ExecutionException) {
                    throw error.cause ?: error
                }
                pageEntries += CloudManifestPageEntryV2(
                    ordinal = ordinal,
                    hash = pageHashes[ordinal],
                    ext = pageExts[ordinal],
                    mime = pageTypes[ordinal].mime,
                    bytes = pageSizes[ordinal],
                )
                onPageUploaded?.invoke(ordinal + 1, pageFiles.size, pageSizes[ordinal].toInt())
            }
            // The cover object is a page object too: it must be durable before the card
            // that points at it, and a failure here must fail the upload.
            try {
                coverPut?.get()
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            }
            // Every lane has been joined, so its slots are published to this thread.
            val offsets = LongArray(pageFiles.size)
            groups.forEachIndexed { packIndex, group ->
                val written = checkNotNull(packOffsetSlots[packIndex]) { "pack $packIndex wrote no offsets" }
                (0 until group.count).forEach { i -> offsets[group.first + i] = written[i] }
            }
            pageOffsets = offsets.toList()
            pagePacks = groups.mapIndexed { packIndex, group ->
                CloudPagePackEntry(first = group.first, count = group.count, bytes = packByteSlots[packIndex])
            }
            // #C46 D1: every page is joined — the row may now say "finishing".
            // A literal 0, not packsDone: the FIRST tail announcement is what
            // flips the row's label, and on a fast link the packs can already
            // have landed, which would make the row skip straight to P/P+3.
            onProgress?.invoke(CloudUploadProgress.Finishing(done = 0, total = tailTotal))
            packPuts.forEach { future ->
                try {
                    future.get()
                } catch (error: ExecutionException) {
                    throw error.cause ?: error
                }
            }
            // Packs are all durable now; announce the count once (a pack that
            // landed before the Finishing(0) line above is included here).
            onProgress?.invoke(CloudUploadProgress.Finishing(done = packsDone.get(), total = tailTotal))
            DiagLog.i("CloudSync", "upload tail thumbs done id=$id packs=$packCount")
            // #C21 §2.2: a first upload's meta was queued with the pages, so
            // joining it here is free on any real link — but it MUST be joined
            // before the pool closes, or `shutdownNow` would interrupt a
            // still-flying PUT. Its announcement and trace line stay below the
            // manifest (see the tail), so the UI event sequence is
            // byte-identical to before. Null on a re-upload (fix-audit HIGH-1):
            // that PUT happens serially in the tail instead.
            try {
                metaPut?.get()
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            }
            // #C21 §2.3: collected before the pool closes, for the same reason. A failed prefetch is only an
            // optimisation miss: upsertCollectionIndex re-reads the index manifest under the lock before it writes,
            // so a newer-format index still stops the upload there (#E1), and page objects are content-addressed.
            prefetchedIndex = try {
                indexPrefetch?.get()
            } catch (error: ExecutionException) {
                null
            }
        } finally {
            executor.shutdownNow()
            ownedPacksDir?.deleteRecursively()
        }
        // Publish the manifest only after every page and every pack is
        // durable. A reader must never observe a v5 manifest that points at a
        // missing pack after a transient failure.
        // #C21 §2.5: every slot was filled by the pack lane that encoded that
        // ordinal, and every pack has been joined above — so the manifest sees
        // a complete set. DEFAULT_EXT is the belt-and-braces fallback for a
        // count with no pack (impossible today: packCount covers every ordinal).
        val thumbExts = thumbExtSlots.map { it ?: DEFAULT_EXT }
        put(
            layout.pagesManifestKey(),
            CloudPageManifest.encodeV5(
                pages = pageEntries,
                thumbExts = thumbExts,
                thumbPackSize = ThumbPack.SIZE,
                pagePacks = pagePacks,
                pageOffsets = pageOffsets,
            ),
        )
        if (layout is GalleryCloudObjectLayout) {
            manifests[layout.pagesManifestKey()] = CloudPageManifestPayload(
                pageCount = pageFiles.size,
                version = 5,
                pageExts = pageExts,
                thumbExts = thumbExts,
                pageHashes = pageHashes,
                pageBytes = pageSizes,
                thumbPackSize = ThumbPack.SIZE,
                pagePacks = pagePacks,
                pageOffsets = pageOffsets,
            )
        }
        // The packs just rewritten supersede anything this instance memoised for
        // the comic (a re-collect through the long-lived download-worker store).
        forgetThumbPacks(id)
        forgetPageWindows(id)
        onProgress?.invoke(CloudUploadProgress.Finishing(done = packCount + 1, total = tailTotal))
        DiagLog.i("CloudSync", "upload tail manifest id=$id")
        // #C21 §2.2 + fix-audit HIGH-1: a first upload's meta.json is already
        // durable (PUT in the page pool above, joined before the pool closed);
        // a re-upload's is PUT here, strictly AFTER the pages manifest, so a
        // failed page can never leave a meta.json the existing card and the v4
        // manifest contradict. The announcement and the trace line stay exactly
        // where the serial PUT used to be — both cases — so `tailTotal` and the
        // Finishing sequence the Activity row renders are unchanged.
        if (alreadyCarded || metadataAlwaysTail) {
            put(metadataKey, metadataBytes)
        }
        onProgress?.invoke(CloudUploadProgress.Finishing(done = packCount + 2, total = tailTotal))
        DiagLog.i("CloudSync", "upload tail meta id=$id")

        // #C5: the cover is the FULL-RESOLUTION page-0 object, never a
        // `thumbs/` entry. `thumbs/` now holds one small grid derivative per
        // page for the detail preview grid; pointing the card at thumbs/0000
        // would make every gallery cover a 320px blur-up.
        val coverKey = run {
            val hash0 = pageHashes.firstOrNull()
            val ext0 = pageExts.firstOrNull() ?: DEFAULT_EXT
            if (hash0 != null) {
                layout.pageKeyByHash(hash0, ext0)
            } else {
                layout.pageKey(0, ext0)
            }
        }
        if (!publishIndex) {
            return CloudArchiveUpload(
                card = CloudCollectionCard(
                    cloudComicId = id,
                    title = meta.title,
                    coverThumbObjectKey = coverKey,
                    collectedAtEpochMs = meta.collectedAtEpochMs,
                    pageCount = meta.pageCount,
                    language = meta.language,
                    acquisitionRef = meta.acquisitionRef.takeIf { it.isNotBlank() },
                ),
                generations = CloudIndexGenerations(null, 0, 0),
            )
        }
        return upsertCollectionIndex(meta = meta, coverKey = coverKey, prefetched = prefetchedIndex).also {
            // Fix-audit MEDIUM-3 + re-review MEDIUM-A: the serial tail after the
            // last page byte is `3 + C` round trips on a FIRST upload and
            // `4 + C` on a RE-upload, where C is the number of index CHUNKS the
            // rewrite PUTs: pages manifest PUT, [the meta PUT — re-upload only,
            // which fix-audit HIGH-1 keeps here for a carded comic], lock-time
            // index manifest GET, C chunk PUTs, index manifest PUT. C is 1 for a
            // library of ≤ 100 comics (`CloudCollectionIndex.DEFAULT_CHUNK_SIZE`),
            // so the headline is FOUR serial round trips for a first upload and
            // FIVE for a re-upload, each growing by one per further chunk —
            // `rewriteIndex` PUTs and `readIndexCards` GET chunks serially.
            // Both cases are pinned by `CloudCollectionStoreLatencyTest`.
            onProgress?.invoke(CloudUploadProgress.Finishing(done = packCount + 3, total = tailTotal))
            DiagLog.i("CloudSync", "upload finish id=$id ms=${System.currentTimeMillis() - uploadStartedMs}")
        }
    }

    /**
     * Rewrites this comic's tier-1 index card from [meta] without re-uploading
     * page objects, recomputing its cover key from the pages manifest.
     *
     * #C5: [hasThumb] is accepted but IGNORED. It used to steer the cover at
     * `thumbs/0000.<ext>`, but `thumbs/` now holds compressed 320px grid
     * derivatives, and a gallery cover must stay full resolution — so the cover
     * is always the full-res page-0 object. The parameter is kept only so the
     * call sites outside this file need no edit; it is deliberately dead, not
     * an oversight, and can be dropped when those callers are next touched.
     */
    /**
     * Publishes newer serialized text/facts without touching chapter page or
     * thumbnail objects. The series identity is checked before any write and
     * the existing summary lock/journal/index path is reused.
     */
    fun updateSeriesDetail(
        seriesCloudId: String,
        identity: com.easemic.app.feature.download.SeriesIdentityV2,
        snapshot: com.easemic.app.feature.source.SeriesDetailSnapshot,
    ): Boolean {
        val id = seriesCloudId.trim()
        require(id.isNotEmpty()) { "seriesCloudId required" }
        val updated = seriesLock(id).withLock {
            val base = readSeriesManifestOrNull(id) ?: return@withLock null
            require(base.providerId == identity.providerNamespace &&
                base.providerSeriesId == identity.canonicalSeriesId) {
                "series identity mismatch"
            }
            val existing = base.detailSnapshot
            val merged = when {
                existing == null -> snapshot
                snapshot.observedAtMillis < existing.observedAtMillis -> existing
                else -> snapshot.copy(metadata = snapshot.metadata.over(existing.metadata))
            }
            if (existing == merged) return@withLock base
            fun <T> suppliedOrLegacy(
                field: com.easemic.app.feature.source.DetailField<T>,
                legacy: T?,
            ): T? = when (field) {
                com.easemic.app.feature.source.DetailField.Omitted -> legacy
                is com.easemic.app.feature.source.DetailField.Value -> field.value
            }
            val next = base.copy(
                generation = base.generation + 1,
                title = merged.metadata.title.valueOrNull()?.takeIf { it.isNotBlank() } ?: base.title,
                author = suppliedOrLegacy(merged.metadata.authors, base.author?.let { listOf(it) })
                    ?.joinToString(", "),
                tags = suppliedOrLegacy(
                    if (base.providerId == "zaimanhua") merged.metadata.genres else merged.metadata.tags,
                    base.tags,
                ).orEmpty(),
                description = suppliedOrLegacy(merged.metadata.synopsis, base.description),
                detailSnapshot = merged,
            )
            seriesIndexJournal.mark(id, next.generation, System.currentTimeMillis())
            put(CloudCollectionKeys.seriesManifestKey(id), CloudSeriesManifest.encode(next))
            next
        } ?: return false
        publishSeriesIndex(id, completed = updated)
        return true
    }

    fun reindexCard(meta: CloudComicMeta, @Suppress("UNUSED_PARAMETER") hasThumb: Boolean) {
        val manifest = pagesManifestOrNull(meta.cloudComicId)
        val ext = manifest?.pageExts?.firstOrNull() ?: DEFAULT_EXT
        val hash0 = manifest?.pageHashes?.firstOrNull()
        val coverKey = if (manifest != null && manifest.version >= 2 && !hash0.isNullOrEmpty()) {
            CloudCollectionKeys.pageKeyByHash(meta.cloudComicId, hash0, ext)
        } else {
            CloudCollectionKeys.pageKey(meta.cloudComicId, 0, ext)
        }
        upsertCollectionIndex(meta, coverKey)
    }

    fun downloadMeta(cloudComicId: String): CloudComicMeta {
        val bytes = client.download(CloudCollectionKeys.metaKey(cloudComicId))
        return decodeOrUnreadable { CloudComicMeta.decode(bytes) }
    }

    /** Writes one serialized chapter and then publishes its series summary/card. */
    fun uploadSeriesChapter(
        series: CloudSeriesManifest,
        chapter: CloudChapterMeta,
        pageFiles: List<File>,
        thumbBytes: List<ByteArray> = emptyList(),
    ): CloudArchiveUpload = uploadSeriesChapter(
        CloudSeriesChapterUploadRequest(
            series = series,
            chapter = chapter,
            pageFiles = pageFiles,
            thumbBytes = thumbBytes,
        ),
    )

    /**
     * Returns the stable opaque key already recorded for [providerChapterId],
     * or derives the key from this bucket's key material when the chapter is
     * new.  Queue runners use the same rule as the writer so a retry resumes
     * the exact chapter checkpoint instead of creating a second directory.
     */
    fun chapterKeyFor(seriesCloudId: String, providerChapterId: String): String =
        chapterKeyFor(seriesCloudId, providerChapterId, readSeriesManifestOrNull(seriesCloudId.trim()))

    /** Same rule resolved against an already-decoded summary — see [uploadSeriesChapter]. */
    fun chapterKeyFor(
        seriesCloudId: String,
        providerChapterId: String,
        existingManifest: CloudSeriesManifest?,
    ): String {
        val id = seriesCloudId.trim()
        require(id.isNotEmpty()) { "seriesCloudId required" }
        require(providerChapterId.isNotBlank()) { "providerChapterId required" }
        val existing = existingManifest?.chapters?.firstOrNull {
            it.providerChapterId == providerChapterId
        }?.chapterKey
        return existing ?: safeChapterKey(id, providerChapterId, client.chapterKeyMaterial())
    }

    /** Reuses the pump/hydration pass. False leaves durable work for the existing retry policy. */
    fun repairSeriesIndexes(
        force: Boolean = false,
        forceSeries: (CloudSeriesManifest) -> Boolean = { false },
    ): Boolean {
        val pendingTailIds = PENDING_CHAPTER_TAILS.keys.toList()
        for (id in pendingTailIds) {
            seriesLock(id).withLock {
                val buffered = PENDING_CHAPTER_TAILS[id]?.toList().orEmpty()
                if (buffered.isEmpty()) return@withLock
                val candidate = buffered.first().request.series.copy(seriesCloudId = id)
                if (!force && !forceSeries(candidate)) return@withLock
                PENDING_CHAPTER_TAILS.remove(id)
                writeSeriesSummary(id, buffered)
            }
        }
        seriesIndexJournal.dirtyIds().forEach { publishSeriesIndex(it, force, forceSeries = forceSeries) }
        return seriesIndexJournal.dirtyIds().isEmpty() && PENDING_CHAPTER_TAILS.isEmpty()
    }

    /**
     * #E111 self-heal: rebuilds missing chapter descriptors in `series.json`
     * from durable per-chapter `meta.json` objects when an earlier upload batch's
     * deferred tail was stranded (e.g. by concurrent sibling timing or process restart).
     */
    fun healSeriesManifestFromChapterMetas(
        seriesCloudId: String,
        syncedChapterKeys: Map<String, String>,
    ): CloudSeriesManifest? {
        if (syncedChapterKeys.isEmpty()) return null
        val id = seriesCloudId.trim()
        val next = seriesLock(id).withLock {
            val base = readSeriesManifestOrNull(id) ?: return null
            val descriptors = base.chapters.toMutableList()
            val presentIds = descriptors.mapTo(mutableSetOf()) { it.providerChapterId }
            var added = false
            syncedChapterKeys.asSequence()
                .filterNot { (missingId, _) -> missingId in presentIds }
                .forEach { (_, missingKey) ->
                    val missingMeta = downloadOrNull(CloudCollectionKeys.chapterMetaKey(id, missingKey))
                        ?.let { decodeOrUnreadable { CloudChapterMeta.decode(it) } }
                        ?: return@forEach
                    descriptors += missingMeta.toSeriesDescriptor()
                    presentIds += missingMeta.providerChapterId
                    added = true
                }
            if (!added) return null
            val updated = base.copy(
                chapters = descriptors.sortedBy { it.readingOrder },
                generation = base.generation + 1,
            )
            seriesIndexJournal.mark(id, updated.generation, System.currentTimeMillis())
            put(CloudCollectionKeys.seriesManifestKey(id), CloudSeriesManifest.encode(updated))
            DiagLog.i("CloudTail", "series healed generation=${updated.generation} chapters=${updated.chapters.size} id=$id")
            updated
        }
        publishSeriesIndex(id, force = true, completed = next)
        return next
    }

    private fun seriesLock(id: String): ReentrantLock = SERIES_LOCKS[(id.hashCode() and Int.MAX_VALUE) % SERIES_LOCKS.size]

    /** A busy derived-index writer never queues a chapter behind its network request. */
    private fun publishSeriesIndex(
        id: String,
        force: Boolean = false,
        completed: CloudSeriesManifest? = null,
        forceSeries: (CloudSeriesManifest) -> Boolean = { false },
    ): CloudIndexGenerations? {
        var indexLocked = false
        try {
            val lock = seriesLock(id)
            if (!lock.tryLock()) return null
            val pending: SeriesIndexJournal.Pending
            val summary: CloudSeriesManifest
            try {
                val dirty = seriesIndexJournal.pending(id) ?: return null
                val remote = completed?.takeIf { it.generation == dirty.generation } ?: readSeriesManifestOrNull(id)
                if (remote == null) {
                    // Process death before the first required summary PUT: the chapter queue still owns retry.
                    seriesIndexJournal.complete(id, dirty.revision)
                    return null
                }
                summary = remote
                pending = seriesIndexJournal.claim(id, force || forceSeries(summary)) ?: return null
            } finally {
                lock.unlock()
            }
            if (!INDEX_MUTATION_LOCK.tryLock()) return null
            indexLocked = true
            // A newer local summary/repair may have passed this snapshot while the lock was free.
            if (seriesIndexJournal.pending(id)?.revision != pending.revision) return null
            // Trash is authoritative even when an old active chunk survived a timed-out write.
            if (listAllTrashCards().any { it.cloudComicId == id }) {
                seriesIndexJournal.complete(id, pending.revision)
                return null
            }
            val previous = readManifestOrNull(CloudCollectionKeys.collectionManifestKey())
            val cards = readIndexCards(previous, CloudCollectionKeys::indexChunkKey)
            val card = summary.indexCard(pending.collectedAtMs)
            val generations = rewriteIndex(
                listOf(card) + cards.filterNot { it.cloudComicId == id },
                CloudCollectionKeys::indexChunkKey, CloudCollectionKeys.collectionManifestKey(),
                previous, isTrash = false, singleAttempt = true,
            )
            seriesIndexJournal.complete(id, pending.revision)
            DiagLog.i("CloudTail", "index repaired seriesGeneration=${summary.generation} indexGeneration=${generations.newGeneration}")
            return generations
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException || error is InterruptedException || Thread.currentThread().isInterrupted) throw error
            // Required chapter objects already landed; only the derived summary is pending.
            DiagLog.w("CloudTail", "index pending cause=${error.javaClass.simpleName}")
            return null
        } finally {
            if (indexLocked) INDEX_MUTATION_LOCK.unlock()
        }
    }

    private fun CloudSeriesManifest.indexCard(collectedAtMs: Long) = CloudCollectionCard(
        cloudComicId = seriesCloudId, title = title, coverThumbObjectKey = coverObjectKey ?: "",
        collectedAtEpochMs = collectedAtMs, pageCount = null, acquisitionRef = seriesAcquisitionRef,
        layoutKind = CloudCardLayout.SERIES, chapterCount = chapters.size,
        readableChapterCount = chapters.count { it.sourceState == "ACTIVE" },
        seriesMetaObjectKey = CloudCollectionKeys.seriesManifestKey(seriesCloudId), seriesGeneration = generation,
        providerId = providerId, providerSeriesId = providerSeriesId,
    )

    /** Writes one serialized chapter and then publishes its series summary/card. */
    fun uploadSeriesChapter(request: CloudSeriesChapterUploadRequest): CloudArchiveUpload {
        val requestedSeries = request.series
        val id = requestedSeries.seriesCloudId.trim()
        require(id.isNotEmpty()) { "seriesCloudId required" }
        require(!requestedSeries.providerSeriesId.isNullOrBlank()) { "providerSeriesId required" }
        require(request.chapter.seriesCloudId.trim() == id) { "chapter seriesCloudId mismatch" }
        require(request.chapter.providerChapterId.isNotBlank()) { "providerChapterId required" }

        // Fail before the first chapter PUT when this app cannot decode the
        // bucket's summary/index. A newer summary is never repaired or
        // partially overwritten.
        readManifestOrNull(CloudCollectionKeys.collectionManifestKey())
        // #E21 tail 2: chapter-key resolution uses the summary this attempt
        // already decoded. The runner threads it in [request]; a direct
        // caller reads it here. This is not the merge base. The lock below
        // re-reads series.json, so a chapter another device writes during
        // the page upload is kept. The decoded summary is not cached past
        // this attempt.
        val existingManifest = request.existingSeriesManifest ?: readSeriesManifestOrNull(id)
        val chapterKey = chapterKeyFor(id, request.chapter.providerChapterId, existingManifest)
        val chapterMeta = request.chapter.copy(
            seriesCloudId = id,
            chapterKey = chapterKey,
            providerSeriesId = request.series.providerSeriesId ?: request.chapter.providerSeriesId,
            coverObjectKey = request.chapter.coverObjectKey,
        )
        val layout = ChapterCloudObjectLayout(id, chapterKey)
        val comicMeta = CloudComicMeta(
            cloudComicId = id,
            title = requestedSeries.title,
            pageCount = request.pageFiles.size,
            providerId = requestedSeries.providerId,
            acquisitionRef = requestedSeries.seriesAcquisitionRef,
            collectedAtEpochMs = chapterMeta.collectedAtEpochMs,
        )
        val firstPage = request.pageFiles.firstOrNull()
        val chapterCoverKey = firstPage?.let {
            val type = ImageContentType.sniff(it.inputStream().use { stream ->
                val head = ByteArray(64)
                val count = stream.read(head)
                if (count <= 0) ByteArray(0) else head.copyOf(count)
            })
            CloudCollectionKeys.chapterPageKeyByHash(id, chapterKey, AwsSigV4.sha256HexOfFile(it), type.extension)
        }
        val chapterMetaWithCover = chapterMeta.copy(coverObjectKey = chapterCoverKey)
        val checkpointWorkDir = request.checkpointWorkDir
        val priorUploaded = checkpointWorkDir?.let { readCloudIngestCheckpoint(it)?.uploadedCount ?: 0 } ?: 0
        val objectUpload = uploadComicArchiveWithIndex(
            meta = comicMeta,
            pageFiles = request.pageFiles,
            thumbBytes = request.thumbBytes,
            thumbSupplier = request.thumbSupplier,
            packsDir = request.packsDir ?: checkpointWorkDir?.let { File(it, "packs") },
            packTargetBytes = request.packTargetBytes ?: client.preferredPackBytes,
            startPageOrdinal = priorUploaded.coerceIn(0, request.pageFiles.size),
            objectLayout = layout,
            metadataKeyOverride = CloudCollectionKeys.chapterMetaKey(id, chapterKey),
            metadataBytesOverride = CloudChapterMeta.encode(chapterMetaWithCover),
            publishIndex = false,
            metadataAlwaysTail = true,
            onPageUploaded = if (checkpointWorkDir == null) null else { ready, total, _ ->
                writeCloudIngestCheckpoint(
                    checkpointWorkDir,
                    CloudIngestCheckpoint(
                        fetchedCount = total,
                        uploadedCount = maxOf(priorUploaded, ready),
                    ),
                )
            },
        )

        val summary = seriesLock(id).withLock {
            val buffered = PENDING_CHAPTER_TAILS.remove(id).orEmpty()
            val pendingTail = PendingChapterTail(
                request = request,
                chapterMeta = chapterMetaWithCover,
                chapterCoverKey = objectUpload.card.coverThumbObjectKey,
            )
            if (request.deferTailWhile?.invoke(id) == true) {
                // #E111: siblings are still ready — hold the shared objects.
                // The batch's last chapter writes them once.
                (PENDING_CHAPTER_TAILS[id] ?: mutableListOf<PendingChapterTail>()
                    .also { PENDING_CHAPTER_TAILS[id] = it })
                    .apply { addAll(buffered); add(pendingTail) }
                DiagLog.i("CloudTail", "series tail deferred pending=${buffered.size + 1} id=$id")
                null
            } else {
                writeSeriesSummary(id, buffered + pendingTail)
            }
        }
        return objectUpload.copy(
            card = (summary ?: request.existingSeriesManifest ?: requestedSeries)
                .indexCard(chapterMeta.collectedAtEpochMs),
            generations = summary?.let { publishSeriesIndex(id, completed = it) },
        )
    }

    /** #E111: one buffered chapter whose objects landed while siblings were still ready. */
    private class PendingChapterTail(
        val request: CloudSeriesChapterUploadRequest,
        val chapterMeta: CloudChapterMeta,
        val chapterCoverKey: String?,
    )

    /**
     * #E111: applies every buffered chapter to the series summary and writes
     * series.json ONCE (one generation bump, one journal mark) instead of once
     * per chapter — the per-chapter rewrites are what turned a multi-chapter
     * upload into a request storm the provider's rate limiter answered.
     */
    private fun writeSeriesSummary(id: String, tails: List<PendingChapterTail>): CloudSeriesManifest {
        var next = readSeriesManifestOrNull(id)
            ?: tails.first().request.series.copy(
                seriesCloudId = id,
                coverObjectKey = null,
                generation = 0,
                chapters = emptyList(),
            )
        tails.forEach { tail -> next = applyChapterToSeries(id, next, tail) }
        val collectedAtMs = tails.maxOf { it.chapterMeta.collectedAtEpochMs }
        seriesIndexJournal.mark(id, next.generation, collectedAtMs)
        put(CloudCollectionKeys.seriesManifestKey(id), CloudSeriesManifest.encode(next))
        DiagLog.i("CloudTail", "series durable generation=${next.generation} chapters=${next.chapters.size} batch=${tails.size}")
        return next
    }

    private fun applyChapterToSeries(
        id: String,
        base: CloudSeriesManifest,
        tail: PendingChapterTail,
    ): CloudSeriesManifest {
            val descriptors = base.chapters.toMutableList()
            val descriptor = tail.chapterMeta.copy(coverObjectKey = tail.chapterCoverKey).toSeriesDescriptor()
            val replaceAt = descriptors.indexOfFirst { it.providerChapterId == descriptor.providerChapterId }
            if (replaceAt >= 0) descriptors[replaceAt] = descriptor else descriptors += descriptor

            // Repair summary loss from this device's chapter ledger. A
            // confirmed missing meta is simply a stale ledger row; any other
            // read/decode failure remains fatal and publishes nothing further.
            val presentIds = descriptors.mapTo(mutableSetOf()) { it.providerChapterId }
            tail.request.syncedChapterKeys.asSequence()
                .filterNot { (missingId, _) -> missingId in presentIds }
                .forEach { (missingId, missingKey) ->
                    val missingMeta = downloadOrNull(CloudCollectionKeys.chapterMetaKey(id, missingKey))
                        ?.let { decodeOrUnreadable { CloudChapterMeta.decode(it) } }
                        ?: return@forEach
                    descriptors += missingMeta.toSeriesDescriptor()
                    presentIds += missingMeta.providerChapterId
                }
            val newGeneration = base.generation + 1
            val seriesCoverFile = tail.request.seriesCoverFile
            val seriesCoverUpload = seriesCoverFile?.let { SerializedSourceCoverUpload.fromFile(id, it) }
            val coverKey = if (seriesCoverUpload != null) {
                if (base.coverObjectKey != seriesCoverUpload.key) {
                    putWithRetryFile(seriesCoverUpload.key, checkNotNull(seriesCoverFile), seriesCoverUpload.type.mime)
                }
                seriesCoverUpload.key
            } else {
                base.coverObjectKey
            }
            val requestedText = tail.request.series.detailSnapshot
            val baseText = base.detailSnapshot
            val mergedText = when {
                requestedText == null -> baseText
                baseText == null -> requestedText
                requestedText.observedAtMillis < baseText.observedAtMillis -> baseText
                else -> requestedText.copy(metadata = requestedText.metadata.over(baseText.metadata))
            }
            val nextSeries = base.copy(
                providerId = tail.request.series.providerId,
                providerSeriesId = base.providerSeriesId ?: tail.request.series.providerSeriesId,
                seriesAcquisitionRef = tail.request.series.seriesAcquisitionRef,
                title = mergedText?.metadata?.title?.valueOrNull()?.takeIf { it.isNotBlank() }
                    ?: tail.request.series.title,
                coverObjectKey = coverKey,
                generation = newGeneration,
                chapters = descriptors.sortedBy { it.readingOrder },
                detailSnapshot = mergedText,
                author = when (val field = mergedText?.metadata?.authors) {
                    null, com.easemic.app.feature.source.DetailField.Omitted ->
                        tail.request.series.author ?: base.author
                    is com.easemic.app.feature.source.DetailField.Value ->
                        field.value?.joinToString(", ")
                },
                tags = when (val field = mergedText?.metadata?.let {
                    if (tail.request.series.providerId == "zaimanhua") it.genres else it.tags
                }) {
                    null, com.easemic.app.feature.source.DetailField.Omitted ->
                        if (tail.request.series.tags.isNotEmpty()) tail.request.series.tags else base.tags
                    is com.easemic.app.feature.source.DetailField.Value -> field.value.orEmpty()
                },
                description = when (val field = mergedText?.metadata?.synopsis) {
                    null, com.easemic.app.feature.source.DetailField.Omitted ->
                        tail.request.series.description ?: base.description
                    is com.easemic.app.feature.source.DetailField.Value -> field.value
                },
            )
            return nextSeries
    }

    /**
     * Series-info write-back (F3 (a), spec §3.3): the #E75 cover-only repair
     * generalized to cover AND text, either of which may be omitted. Returns
     * the series' cover object key — [coverFile]'s key when one is given,
     * otherwise the manifest's current one. Null when the series is absent,
     * the identity does not match, or a given [coverFile] cannot be read.
     *
     * Nothing differs -> no cover upload, no manifest PUT, no generation bump:
     * an unchanged cover keeps its old early return, and a [snapshot] whose
     * text does not change the manifest's [CloudSeriesManifest.detailSnapshot]
     * performs no write. A change of either writes the manifest once and bumps
     * its generation once.
     */
    fun updateSeriesInfo(
        seriesCloudId: String,
        identity: SeriesIdentityV2,
        coverFile: File? = null,
        snapshot: SeriesDetailSnapshot? = null,
    ): String? {
        if (coverFile == null && snapshot == null) return null
        val id = seriesCloudId.trim()
        val upload = coverFile?.let { SerializedSourceCoverUpload.fromFile(id, it) }
        if (coverFile != null && upload == null) return null
        val next = seriesLock(id).withLock {
            val base = readSeriesManifestOrNull(id) ?: return null
            if (base.providerId != identity.providerNamespace ||
                base.providerSeriesId != identity.canonicalSeriesId
            ) return null
            val coverChanged = upload != null && base.coverObjectKey != upload.key
            // The series-tail sibling's text merge (DetailField-aware):
            // null/Omitted keeps the base, Value wins when not older.
            val mergedText = when {
                snapshot == null -> base.detailSnapshot
                base.detailSnapshot == null -> snapshot
                snapshot.observedAtMillis < base.detailSnapshot.observedAtMillis -> base.detailSnapshot
                else -> snapshot.copy(metadata = snapshot.metadata.over(base.detailSnapshot.metadata))
            }
            val textChanged = snapshot != null &&
                mergedText != null &&
                !mergedText.sameSeriesText(base.detailSnapshot)
            if (!coverChanged && !textChanged) return upload?.key ?: base.coverObjectKey
            val coverKey = if (upload != null && coverChanged) {
                putWithRetryFile(upload.key, checkNotNull(coverFile), upload.type.mime)
                upload.key
            } else {
                base.coverObjectKey
            }
            val updated = base.copy(
                title = mergedText?.metadata?.title?.valueOrNull()?.takeIf { it.isNotBlank() }
                    ?: base.title,
                coverObjectKey = coverKey,
                generation = base.generation + 1,
                detailSnapshot = mergedText,
            )
            seriesIndexJournal.mark(id, updated.generation, System.currentTimeMillis())
            put(CloudCollectionKeys.seriesManifestKey(id), CloudSeriesManifest.encode(updated))
            updated
        }
        publishSeriesIndex(id, completed = next)
        return upload?.key ?: next.coverObjectKey
    }

    /**
     * #E48: publishes the source's current chapter facts (removed at source,
     * order, titles) into the series summary so another device restores them.
     * One summary PUT when anything differs, none otherwise; chapter objects
     * and per-chapter meta are untouched. Returns the summary written, or null
     * when nothing was written (absent series, identity mismatch, no change).
     */
    fun updateSeriesChapterFacts(
        seriesCloudId: String,
        identity: SeriesIdentityV2,
        facts: List<CloudChapterSourceFact>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): CloudSeriesManifest? {
        if (facts.isEmpty()) return null
        val id = seriesCloudId.trim()
        val next = seriesLock(id).withLock {
            val base = readSeriesManifestOrNull(id) ?: return null
            if (base.providerId != identity.providerNamespace ||
                base.providerSeriesId != identity.canonicalSeriesId
            ) return null
            val chapters = base.chapters.withSourceFacts(facts, nowEpochMs)
            if (chapters == base.chapters) return null
            val updated = base.copy(chapters = chapters, generation = base.generation + 1)
            seriesIndexJournal.mark(id, updated.generation, System.currentTimeMillis())
            put(CloudCollectionKeys.seriesManifestKey(id), CloudSeriesManifest.encode(updated))
            updated
        }
        publishSeriesIndex(id, completed = next)
        return next
    }

    /**
     * The bucket's decoded series summary, or null when absent. An upload
     * attempt reads it for chapter-key resolution and again under
     * [INDEX_MUTATION_LOCK] as the summary merge base (#E21 tail 2). It is
     * not memoised across attempts: another device may write between them.
     */
    fun readSeriesManifestOrNull(seriesCloudId: String): CloudSeriesManifest? {
        val bytes = downloadOrNull(CloudCollectionKeys.seriesManifestKey(seriesCloudId)) ?: return null
        return decodeOrUnreadable { CloudSeriesManifest.decode(bytes) }
    }

    fun downloadSeriesManifest(seriesMetaObjectKey: String): CloudSeriesManifest {
        val bytes = client.download(seriesMetaObjectKey.trim())
        return decodeOrUnreadable { CloudSeriesManifest.decode(bytes) }
    }

    fun downloadChapterMeta(seriesCloudId: String, chapterKey: String): CloudChapterMeta {
        val bytes = client.download(CloudCollectionKeys.chapterMetaKey(seriesCloudId, chapterKey))
        return decodeOrUnreadable { CloudChapterMeta.decode(bytes) }
    }

    /**
     * [ext] is the object's stored extension; when omitted it is resolved from
     * the comic's `pages.manifest.json`.
     *
     * #C51 (v5): the page is a byte RANGE of its pack — see [downloadPageFromPack]; [ext] is
     * not consulted at all, because the manifest addresses bytes, not names. A v2–v4
     * (content-addressed) manifest resolves the ordinal to its recorded hash and downloads
     * `pages/<hash>.<ext>`; a v1/legacy manifest (or none at all) falls back to the
     * ordinal-keyed `pages/NNNN.<ext>` layout, so pre-existing comics stay readable without
     * a re-upload.
     */
    fun downloadPage(cloudComicId: String, ordinal: Int, ext: String? = null): ByteArray {
        val manifest = pagesManifestOrNull(cloudComicId)
        if (manifest != null && manifest.version >= 5) {
            return downloadPageFromPack(cloudComicId.trim(), ordinal, manifest)
        }
        val resolvedExt = ext ?: manifest?.pageExts?.getOrNull(ordinal) ?: DEFAULT_EXT
        val hash = manifest?.takeIf { it.version >= 2 }?.pageHashes?.getOrNull(ordinal)
        val key = if (!hash.isNullOrEmpty()) {
            CloudCollectionKeys.pageKeyByHash(cloudComicId, hash, resolvedExt)
        } else {
            CloudCollectionKeys.pageKey(cloudComicId, ordinal, resolvedExt)
        }
        return client.download(key)
    }

    /**
     * Recorded v5 page length, from the memoised manifest. Null when the manifest is absent or
     * the page has no size, so the reader keeps the byte route. Does not refresh the manifest.
     */
    fun pageBytesOrNull(layout: CloudObjectLayout, ordinal: Int): Long? {
        val manifest = pagesManifestOrNull(layout) ?: return null
        if (manifest.version < 5) return null
        return manifest.pageBytes.getOrNull(ordinal)?.takeIf { it > 0L }
    }

    fun downloadPageToFile(cloudComicId: String, ordinal: Int, dest: File, focused: Boolean = false) {
        downloadPageToFile(GalleryCloudObjectLayout(cloudComicId), ordinal, dest, focused)
    }

    /**
     * A page of at least [PAGE_WINDOW_BYTES] is streamed when the transport reads by range, or when
     * it is alone in its pack. Multi-page packs on REST share the whole-pack byte memo.
     * Anything else, including an unknown pack size, is written from today's byte read.
     */
    fun downloadPageToFile(layout: CloudObjectLayout, ordinal: Int, dest: File, focused: Boolean = false) {
        val manifest = pagesManifestOrNull(layout)
        if (manifest != null && manifest.version >= 5 && fileRoute(manifest, ordinal)) {
            val groups = manifest.pagePacks.map { PagePack.Group(it.first, it.count) }
            val packIndex = PagePack.packIndexOf(groups, ordinal)
            val offset = pageOffsetOf(manifest, ordinal)
            val lengthLong = pageEndOf(manifest, ordinal) - offset
            if (lengthLong <= 0L || lengthLong > Int.MAX_VALUE) {
                throw java.io.IOException("page length does not fit a file range")
            }
            val length = lengthLong.toInt()
            // #E29: one line per page that streams to a file, so `logcat -s CloudOpen` (and the trace file) shows
            // which route a reader page took on a phone.
            CloudOpenTrace.timed(phase = "page-file", extras = { "ordinal=$ordinal bytes=$length" }) {
                client.downloadRangeToFile(
                    layout.pagePackKey(packIndex),
                    offset,
                    length,
                    manifest.pagePacks[packIndex].bytes,
                    dest,
                )
            }
            if (dest.length() != length.toLong()) {
                throw java.io.IOException("short page file: ${dest.length()} of $length")
            }
            return
        }
        val bytes = if (focused) downloadFocusedPage(layout, ordinal) else downloadPage(layout, ordinal)
        dest.outputStream().use { it.write(bytes) }
    }

    private fun fileRoute(manifest: CloudPageManifestPayload, ordinal: Int): Boolean {
        val page = manifest.pageBytes.getOrNull(ordinal) ?: return false
        if (page < PAGE_WINDOW_BYTES) return false
        if (client.supportsEfficientRange) return true
        val groups = manifest.pagePacks.map { PagePack.Group(it.first, it.count) }
        val packIndex = runCatching { PagePack.packIndexOf(groups, ordinal) }.getOrNull() ?: return false
        val pack = manifest.pagePacks.getOrNull(packIndex) ?: return false
        return pack.bytes > 0L && pack.count == 1
    }

    /** Reads one page from a nested format-2 chapter prefix. */
    fun downloadPage(layout: CloudObjectLayout, ordinal: Int, ext: String? = null): ByteArray {
        val manifest = pagesManifestOrNull(layout)
        if (manifest != null && manifest.version >= 5) {
            return downloadPageFromPack(layout, ordinal, manifest)
        }
        val resolvedExt = ext ?: manifest?.pageExts?.getOrNull(ordinal) ?: DEFAULT_EXT
        val hash = manifest?.takeIf { it.version >= 2 }?.pageHashes?.getOrNull(ordinal)
        val key = if (!hash.isNullOrEmpty()) layout.pageKeyByHash(hash, resolvedExt) else layout.pageKey(ordinal, resolvedExt)
        return client.download(key)
    }

    /**
     * Reads one v5 page directly from its pack when the transport can make an efficient
     * range request. Focused reads deliberately bypass the canonical window memo: the first
     * screen should pay for exactly its page, while the normal reader path keeps its window
     * single-flight and memoisation behaviour through [downloadPage].
     */
    fun downloadFocusedPage(cloudComicId: String, ordinal: Int, ext: String? = null): ByteArray {
        val manifest = pagesManifestOrNull(cloudComicId)
        if (manifest == null || manifest.version < 5 || !client.supportsEfficientRange ||
            ordinal < 0 || ordinal >= manifest.pageCount
        ) {
            return downloadPage(cloudComicId, ordinal, ext)
        }
        val pageLength = manifest.pageBytes.getOrNull(ordinal) ?: return downloadPage(cloudComicId, ordinal, ext)
        val pageOffset = manifest.pageOffsets.getOrNull(ordinal) ?: return downloadPage(cloudComicId, ordinal, ext)
        if (pageLength <= 0L || pageLength >= PAGE_WINDOW_BYTES || pageOffset < 0L || pageLength > Int.MAX_VALUE) {
            return downloadPage(cloudComicId, ordinal, ext)
        }
        val groups = manifest.pagePacks.map { PagePack.Group(it.first, it.count) }
        val packIndex = PagePack.packIndexOf(groups, ordinal)
        val packKey = CloudCollectionKeys.pagePackKey(cloudComicId.trim(), packIndex)
        // #D17: the pack's size lets an encrypted library ask for the header and this page's chunks at once.
        val bytes = client.downloadRange(packKey, pageOffset, pageLength.toInt(), objectSize = manifest.pagePacks[packIndex].bytes)
        if (bytes.size != pageLength.toInt()) {
            throw java.io.IOException("short page window: ${bytes.size} of $pageLength")
        }
        return bytes
    }

    /** Focused range read for a nested format-2 chapter prefix. */
    fun downloadFocusedPage(layout: CloudObjectLayout, ordinal: Int, ext: String? = null): ByteArray {
        val manifest = pagesManifestOrNull(layout)
        if (manifest == null || manifest.version < 5 || !client.supportsEfficientRange ||
            ordinal < 0 || ordinal >= manifest.pageCount
        ) {
            return downloadPage(layout, ordinal, ext)
        }
        val pageLength = manifest.pageBytes.getOrNull(ordinal) ?: return downloadPage(layout, ordinal, ext)
        val pageOffset = manifest.pageOffsets.getOrNull(ordinal) ?: return downloadPage(layout, ordinal, ext)
        if (pageLength <= 0L || pageLength >= PAGE_WINDOW_BYTES || pageOffset < 0L || pageLength > Int.MAX_VALUE) {
            return downloadPage(layout, ordinal, ext)
        }
        val groups = manifest.pagePacks.map { PagePack.Group(it.first, it.count) }
        val packIndex = PagePack.packIndexOf(groups, ordinal)
        val packKey = layout.pagePackKey(packIndex)
        val bytes = client.downloadRange(packKey, pageOffset, pageLength.toInt(), objectSize = manifest.pagePacks[packIndex].bytes)
        if (bytes.size != pageLength.toInt()) throw java.io.IOException("short page window: ${bytes.size} of $pageLength")
        return bytes
    }

    /**
     * #C51: one page of a v5 comic.
     *
     * Each pack is TILED into windows: starting at the pack's first page, a window takes
     * whole pages while they still fit in [PAGE_WINDOW_BYTES] (a page larger than that is a
     * window of its own), then the next window starts. The tiling is a pure function of the
     * manifest, which is what makes the window key CANONICAL — six lanes asking for six
     * different pages of one window agree on one key and issue ONE request, and the page a
     * neighbour already fetched is a memo hit rather than a second overlapping range.
     *
     * (The spec wrote the window as `[wanted page, …)`. Anchoring on the CALLER would give
     * every one of the pump's six lanes its own overlapping 4 MiB range — six requests and
     * ~24 MiB for six pages, worse than the per-page GETs this item replaces — and could
     * never satisfy the spec's own "six concurrent readers ⇒ exactly one request" test. The
     * cost of tiling is that opening mid-window fetches the window's earlier pages too:
     * bounded by [PAGE_WINDOW_BYTES], once.)
     *
     * Fail closed: an ordinal outside every pack, or with no recorded offset/length, throws
     * with counts only — never a key.
     */
    private fun downloadPageFromPack(
        cloudComicId: String,
        ordinal: Int,
        manifest: CloudPageManifestPayload,
    ): ByteArray {
        val groups = manifest.pagePacks.map { PagePack.Group(it.first, it.count) }
        val packIndex = PagePack.packIndexOf(groups, ordinal)
        val tile = packWindows(manifest, packIndex).first { ordinal in it.first..it.last }
        val window = pageWindow(
            CloudCollectionKeys.pagePackKey(cloudComicId, packIndex),
            tile.start,
            tile.length,
            packBytes = manifest.pagePacks[packIndex].bytes,
            memoise = tile.first != tile.last,
        )
        val from = (pageOffsetOf(manifest, ordinal) - tile.start).toInt()
        val end = (pageEndOf(manifest, ordinal) - tile.start).toInt()
        return if (from == 0 && end == window.size) window else window.copyOfRange(from, end)
    }

    private fun downloadPageFromPack(
        layout: CloudObjectLayout,
        ordinal: Int,
        manifest: CloudPageManifestPayload,
    ): ByteArray {
        val groups = manifest.pagePacks.map { PagePack.Group(it.first, it.count) }
        val packIndex = PagePack.packIndexOf(groups, ordinal)
        val tile = packWindows(manifest, packIndex).first { ordinal in it.first..it.last }
        val window = pageWindow(
            layout.pagePackKey(packIndex),
            tile.start,
            tile.length,
            packBytes = manifest.pagePacks[packIndex].bytes,
            memoise = tile.first != tile.last,
        )
        val from = (pageOffsetOf(manifest, ordinal) - tile.start).toInt()
        val end = (pageEndOf(manifest, ordinal) - tile.start).toInt()
        return if (from == 0 && end == window.size) window else window.copyOfRange(from, end)
    }

    /**
     * The window `[start, start + length)` of [packKey]: from the memo, from a window another
     * caller already has in flight, or from one new [ObjectStore.downloadRange]. The memo
     * monitor is never held across the request, so a window of a different pack proceeds in
     * parallel.
     *
     * [memoise] is false for a tile that serves one page. Those windows are not retained.
     * The future is completed under this lock and the in-flight entry removed afterwards, so a
     * new caller cannot start another request for the same key before the waiters are released.
     * A changed [pageWindowEpoch] still skips the insert.
     */
    private fun pageWindow(
        packKey: String,
        start: Long,
        length: Int,
        packBytes: Long,
        memoise: Boolean,
    ): ByteArray {
        val key = packKey to start
        var owned: PageWindowRequest? = null
        var waited: PageWindowRequest? = null
        var epoch = 0L
        synchronized(pageWindows) {
            epoch = pageWindowEpoch.get()
            pageWindows[key]?.let { return it }
            val flying = pageWindowsInFlight[key]
            if (flying != null) {
                waited = flying
            } else {
                owned = PageWindowRequest().also { pageWindowsInFlight[key] = it }
            }
        }
        waited?.let { return it.await() }
        val request = checkNotNull(owned)
        try {
            val bytes = client.downloadRange(packKey, start, length, objectSize = packBytes)
            // A short window is the same failure a short page GET was: the caller retries and
            // nothing partial is ever memoised.
            if (bytes.size != length) {
                throw java.io.IOException("short page window: ${bytes.size} of $length")
            }
            synchronized(pageWindows) {
                if (memoise && pageWindowEpoch.get() == epoch) pageWindows[key] = bytes
                request.future.complete(bytes)
                pageWindowsInFlight.remove(key)
            }
            return bytes
        } catch (error: Throwable) {
            synchronized(pageWindows) {
                request.future.completeExceptionally(error)
                pageWindowsInFlight.remove(key)
            }
            throw error
        }
    }

    /**
     * A page's grid thumb. v4 manifest (#C47-C): the entry `NNNN.<ext>` of its
     * 24-ordinal pack — the pack is fetched once and kept in a
     * [THUMB_PACK_MEMO]-pack memo, so the preview strip's serial walk over
     * ordinals 0..23 costs ONE GET. v3 or no manifest: the per-object
     * `thumbs/NNNN.<ext>` GET, byte-for-byte the pre-#C47 path (comics
     * already on the bucket). A pack that lacks the entry (short, corrupt,
     * not a zip) fails THIS ordinal — it never serves another ordinal's bytes.
     */
    fun downloadThumb(cloudComicId: String, ordinal: Int, ext: String? = null): ByteArray {
        val manifest = pagesManifestOrNull(cloudComicId)
        val resolved = ext ?: manifest?.thumbExts?.getOrNull(ordinal) ?: DEFAULT_EXT
        val packSize = manifest?.thumbPackSize ?: 0
        if (packSize <= 0) {
            return client.download(CloudCollectionKeys.thumbKey(cloudComicId, ordinal, resolved))
        }
        // The manifest's OWN pack size, not ThumbPack.SIZE — a later change of
        // the constant must not misaddress a comic already packed.
        val first = ThumbPack.firstOrdinal(ThumbPack.packIndexOf(ordinal, packSize), packSize)
        val key = CloudCollectionKeys.thumbPackKey(cloudComicId, first)
        val (memoised, epoch) = synchronized(thumbPacks) { thumbPacks[key] to thumbPackEpoch.get() }
        val pack = memoised ?: client.download(key).also { bytes ->
            synchronized(thumbPacks) {
                if (thumbPackEpoch.get() == epoch) thumbPacks[key] = bytes
            }
        }
        return ThumbPack.extract(pack, ordinal, resolved)
            ?: throw IllegalStateException("thumb $ordinal missing from its pack (first ordinal $first)")
    }

    /**
     * S4 Task 1 review (M3): reads a card's cover by its truthful,
     * already-computed key ([CloudCollectionCard.coverThumbObjectKey],
     * written once at upload time — see [uploadComicArchiveWithIndex]'s `coverKey`)
     * instead of guessing one via a `pages.manifest` GET plus a default-
     * extension fallback ([downloadThumb]). A blank key retains the legacy
     * fallback only when no series summary exists; a series summary without a
     * source cover fails instead of substituting a chapter page.
     */
    /** Original cover bytes streamed to disk. The blank-key thumb fallback keeps its existing cost. */
    fun downloadCoverToFile(coverThumbObjectKey: String, cloudComicId: String, dest: File) {
        if (coverThumbObjectKey.isNotBlank()) {
            client.downloadToFile(coverThumbObjectKey, dest)
            return
        }
        val series = readSeriesManifestOrNull(cloudComicId.trim())
        if (series != null) {
            val coverKey = series.coverObjectKey ?: throw SeriesCoverAbsentException(series.seriesCloudId, series.generation)
            client.downloadToFile(coverKey, dest)
        } else dest.writeBytes(downloadThumb(cloudComicId, 0))
    }

    fun downloadCover(coverThumbObjectKey: String, cloudComicId: String): ByteArray {
        if (coverThumbObjectKey.isNotBlank()) return client.download(coverThumbObjectKey)
        val series = readSeriesManifestOrNull(cloudComicId.trim())
        if (series != null) {
            val coverKey = series.coverObjectKey ?: throw SeriesCoverAbsentException(series.seriesCloudId, series.generation)
            return client.download(coverKey)
        }
        return downloadThumb(cloudComicId, 0)
    }

    /**
     * S4 Task 1 review (M8): the v2 `pages.manifest`'s recorded thumb count
     * for [cloudComicId], when a manifest is available and it named at least
     * one thumb — a precise walk ceiling for the Detail preview strip so it
     * stops exactly at what was actually uploaded instead of guessing a
     * fixed 24/12 and 404ing the rest. Null when no manifest is reachable or
     * it predates per-comic `thumbExts` (legacy) — callers should fall back
     * to [CloudDetailPreviewProgress.appendSuccesses]'s `stopOnFailure` in
     * that case instead of another fixed guess.
     */
    fun thumbCount(cloudComicId: String): Int? =
        pagesManifestOrNull(cloudComicId)?.thumbExts?.size?.takeIf { it > 0 }

    /**
     * Always fetches and refreshes the memo — the download worker's
     * process-lifetime store relies on this. `memoizedCloudCollectionStore`
     * hands the SAME instance to every download while the binding
     * fingerprint holds, so this is the only surface that re-reads a comic
     * whose pages were re-collected under new content-addressed hashes.
     * Serving the memo here made a re-download replay the old manifest and
     * 404 on every page. Callers that only need a count and can live with a
     * memo hit use [pageCountOrNull].
     */
    fun downloadPagesManifest(cloudComicId: String): CloudPageManifestPayload {
        return downloadPagesManifest(GalleryCloudObjectLayout(cloudComicId.trim()))
    }

    fun downloadPagesManifest(layout: CloudObjectLayout): CloudPageManifestPayload {
        val key = layout.pagesManifestKey()
        return CloudOpenTrace.timed("manifest-get", extras = { "id=${layout.objectId}" }) {
            val bytes = client.download(key)
            decodeOrUnreadable { CloudPageManifest.decode(bytes) }.also { manifests[key] = it }
        }
    }

    /**
     * Memo-first page count, for the reader-open path: a memo hit (the detail
     * screen's instance already read this manifest for its previews) is free,
     * a miss costs exactly one traced `manifest-get`, and a comic with no
     * manifest object at all answers null instead of throwing.
     */
    fun pageCountOrNull(cloudComicId: String): Int? =
        pagesManifestOrNull(cloudComicId)?.pageCount

    fun pageCountOrNull(layout: CloudObjectLayout): Int? =
        pagesManifestOrNull(layout)?.pageCount

    fun listIndexChunk(chunkIndex: Int): CloudCollectionIndexChunk {
        val bytes = client.download(CloudCollectionKeys.indexChunkKey(chunkIndex))
        return decodeOrUnreadable { CloudCollectionIndex.decodeChunk(bytes) }
    }

    /**
     * A24: reads every chunk the manifest declares. A missing or empty chunk
     * inside the declared range is a hole (skipped), so a shelf never silently
     * shows fewer comics than the manifest counts; a chunk that is present but
     * unparseable still fails closed via [loadIndexCardsForMerge].
     */
    fun listAllIndexCards(): List<CloudCollectionCard> =
        listAllCards(CloudCollectionKeys::indexChunkKey, CloudCollectionKeys.collectionManifestKey())

    private fun listAllCards(
        chunkKey: (Int) -> String,
        manifestKey: String,
    ): List<CloudCollectionCard> = readIndexSnapshot(chunkKey, manifestKey).cards

    /** What one read of an index (manifest + every declared chunk) saw — read once, reused by the rewrite under the same lock. */
    private data class IndexSnapshot(val manifest: CloudCollectionManifest?, val cards: List<CloudCollectionCard>)

    /**
     * #C21 §2.1 (audit B2 step 5): the manifest and the cards come back
     * TOGETHER, so a caller that is about to rewrite the index already holds
     * the `previous` manifest [rewriteIndex] needs and no longer has to pay a
     * second GET for it microseconds later under the same lock.
     */
    private fun readIndexSnapshot(
        chunkKey: (Int) -> String,
        manifestKey: String,
    ): IndexSnapshot {
        // A present-but-unparseable manifest means the bucket is not ours to
        // touch: fail before any read-modify-write can clobber it.
        val manifest = readManifestOrNull(manifestKey)
        return IndexSnapshot(manifest, readIndexCards(manifest, chunkKey))
    }

    /**
     * The cards [manifest] declares. Split out of [readIndexSnapshot] for
     * #C21 §2.3: a caller that has just read the manifest under the lock holds
     * the authoritative observation already and needs only its chunks — exactly
     * the pair `listAllCards` has always read (manifest, then its chunks), so
     * the atomicity of that pair is unchanged.
     */
    private fun readIndexCards(
        manifest: CloudCollectionManifest?,
        chunkKey: (Int) -> String,
    ): List<CloudCollectionCard> {
        val cards = mutableListOf<CloudCollectionCard>()
        val declaredChunks = manifest?.declaredChunkCount()
        if (declaredChunks != null) {
            for (chunkIndex in 0 until declaredChunks) {
                cards += loadIndexCardsForMerge(chunkIndex, chunkKey)
            }
            return cards
        }
        // No manifest yet (first Collect, or a bucket whose chunks predate one):
        // probe forward until a chunk comes back empty.
        var chunkIndex = 0
        while (true) {
            val chunkCards = loadIndexCardsForMerge(chunkIndex, chunkKey)
            if (chunkCards.isEmpty()) break
            cards += chunkCards
            chunkIndex += 1
        }
        return cards
    }

    /** The active collection index, manifest and cards from ONE read (see [readIndexSnapshot]). */
    private fun collectionIndexSnapshot(): IndexSnapshot =
        readIndexSnapshot(CloudCollectionKeys::indexChunkKey, CloudCollectionKeys.collectionManifestKey())

    /** The trash index, manifest and cards from ONE read (see [readIndexSnapshot]). */
    private fun trashIndexSnapshot(): IndexSnapshot =
        readIndexSnapshot(CloudCollectionKeys::trashIndexChunkKey, CloudCollectionKeys.trashManifestKey())

    fun downloadCollectionManifest(): CloudCollectionManifest {
        val bytes = client.download(CloudCollectionKeys.collectionManifestKey())
        return decodeOrUnreadable { CloudCollectionIndex.decodeManifest(bytes) }
    }

    /**
     * [coverKey] is the fully resolved object key (thumb- or page-keyed, already hash/ordinal-aware).
     *
     * [prefetched] is an index read this upload already paid for WHILE its
     * pages were flying (#C21 §2.3) — reused only when the manifest re-read
     * under the lock still reports the generation that prefetch saw. Null (the
     * default, and [reindexCard]'s case) means the full read happens here, as
     * it always did.
     */
    private fun upsertCollectionIndex(
        meta: CloudComicMeta,
        coverKey: String,
        prefetched: IndexSnapshot? = null,
    ): CloudArchiveUpload {
        INDEX_MUTATION_LOCK.withLock {
            // #C21 §2.1/§2.3: the manifest re-read here IS the observation the
            // read-modify-write needs, and it is read ONCE — `rewriteIndex`
            // takes it as `previous` instead of fetching it again.
            val manifest = readManifestOrNull(CloudCollectionKeys.collectionManifestKey())
            // Chunks are immutable for a given generation: every writer bumps
            // the generation AFTER rewriting chunks, so an unchanged generation
            // vouches for the prefetched cards. A null manifest (first Collect)
            // vouches for nothing and always re-reads.
            //
            // Cross-device caveat (spec §2.3): INDEX_MUTATION_LOCK is
            // process-local, so a writer on another device can always land
            // between this manifest read and the chunk reads — exactly as it
            // could before this change, since the cards have always been read
            // as "manifest, then its chunks" with no atomicity across the two.
            //
            // Fix-audit MEDIUM-4/5: the prefetch does widen the lost-update
            // window — the boundary moves from `L_m + δ` to `L_m − δ` (δ = the
            // other writer's chunk-PUT→manifest-PUT gap, ≥ 1 RTT), about two
            // round trips wider than before, because we now also see the other
            // writer via the seconds-earlier prefetch GET. Two devices can
            // therefore both publish generation G+1 (before, the re-read under
            // the lock made ours G+2). This is the accepted cost of deleting
            // the duplicate GET (audit B2 step 5): whenever the other writer's
            // bump is observed here the generation differs and the chunks are
            // re-read, and local hydration re-walks the index whenever the
            // generation moves.
            val existingCards = if (prefetched != null && manifest != null &&
                prefetched.manifest?.generation == manifest.generation
            ) {
                prefetched.cards
            } else {
                readIndexCards(manifest, CloudCollectionKeys::indexChunkKey)
            }
            val card = CloudCollectionCard(
                cloudComicId = meta.cloudComicId,
                title = meta.title,
                coverThumbObjectKey = coverKey,
                collectedAtEpochMs = meta.collectedAtEpochMs,
                pageCount = meta.pageCount,
                language = meta.language,
                acquisitionRef = meta.acquisitionRef.takeIf { it.isNotBlank() },
            )
            val merged = listOf(card) + existingCards.filterNot { it.cloudComicId == meta.cloudComicId }
            val generations = rewriteCollectionIndex(merged, previous = manifest)
            // #C46 D5: the card count comes from the list this rewrite just wrote —
            // never a second listAllIndexCards() call for the sake of a log line.
            DiagLog.i("CloudSync", "upload tail index id=${meta.cloudComicId} cards=${merged.size}")
            return CloudArchiveUpload(card, generations)
        }
    }

    fun listAllTrashCards(): List<CloudCollectionCard> =
        listAllCards(CloudCollectionKeys::trashIndexChunkKey, CloudCollectionKeys.trashManifestKey())

    /**
     * #C62: returns whether [cloudComicId] was actually found in the active
     * index and moved to trash, so callers (`tombstoneForUncollect`) can
     * tell a real tombstone apart from a silent no-op without a second read.
     */
    fun tombstoneComic(cloudComicId: String, nowEpochMs: Long): Boolean {
        INDEX_MUTATION_LOCK.withLock {
            val id = cloudComicId.trim()
            // #C21 §2.1: each index is read ONCE here; its own manifest is the
            // `previous` its rewrite records.
            val active = collectionIndexSnapshot()
            val card = active.cards.firstOrNull { it.cloudComicId == id }
                ?: readSeriesManifestOrNull(id)?.indexCard(nowEpochMs)
                ?: return false
            val trash = trashIndexSnapshot()
            rewriteCollectionIndex(active.cards.filterNot { it.cloudComicId == id }, previous = active.manifest)
            rewriteTrashIndex(
                listOf(card.copy(trashedAtEpochMs = nowEpochMs)) +
                    trash.cards.filterNot { it.cloudComicId == id },
                previous = trash.manifest,
            )
            return true
        }
    }

    /**
     * @return true iff a trash card for [cloudComicId] was found and moved back
     * into the active index. SF-4 (C35 audit): callers decide RESTORE from a
     * listing that may already be stale by the time the tap runs (another
     * device purged it, the card was discarded, the id never matched a real
     * card). Silently doing nothing and letting the caller report "Collected."
     * leaves the user with a phantom — nothing restored AND nothing ingested.
     */
    fun restoreComic(cloudComicId: String): Boolean {
        INDEX_MUTATION_LOCK.withLock {
            val id = cloudComicId.trim()
            // #C21 §2.1: one read per index, each rewrite told the manifest its
            // own read observed under this lock.
            val trash = trashIndexSnapshot()
            val card = trash.cards.firstOrNull { it.cloudComicId == id } ?: return false
            val active = collectionIndexSnapshot()
            rewriteTrashIndex(trash.cards.filterNot { it.cloudComicId == id }, previous = trash.manifest)
            rewriteCollectionIndex(
                listOf(card.copy(trashedAtEpochMs = null)) +
                    active.cards.filterNot { it.cloudComicId == id },
                previous = active.manifest,
            )
            return true
        }
    }

    /**
     * Discards at most [maxCards] expired trash cards, oldest-listed first.
     *
     * SF-4 (C35 audit), two bounds, both about [INDEX_MUTATION_LOCK] — the same
     * monitor an in-flight upload needs for its index tail writes and
     * [restoreComic] needs to un-trash a card:
     *  - [maxCards] bounds the WORK. A user with a year of trash would
     *    otherwise pay every expired card's [discardComic] (2N+2 deletes each)
     *    in a single sweep. The retention window is 30 days, so a card expiring
     *    a few minutes — or a few sweeps — late costs nobody anything.
     *  - the lock is taken PER STEP, never across the whole sweep. It used to
     *    wrap the listing, every discard and the final rewrite in one
     *    `synchronized` block, so an upload's tail write could block for as
     *    long as the sweep ran. Each [discardComic] still takes it for itself,
     *    which is all the atomicity a per-comic discard needs.
     */
    fun purgeExpiredTrash(nowEpochMs: Long, maxCards: Int = Int.MAX_VALUE) {
        if (maxCards <= 0) return
        val expired = INDEX_MUTATION_LOCK.withLock { listAllTrashCards() }
            .filter { card ->
                val trashedAt = card.trashedAtEpochMs ?: return@filter false
                CloudCollectLifecycle.shouldPurge(trashedAt, nowEpochMs)
            }
            .take(maxCards)
        if (expired.isEmpty()) return
        expired.forEach { discardComic(it.cloudComicId) }
        // discardComic already drops each card from the trash index under the
        // lock, so this reconciles only if one of them somehow did not —
        // never a write on the happy path.
        val purgedIds = expired.map { it.cloudComicId }.toSet()
        INDEX_MUTATION_LOCK.withLock {
            // #C21 §2.1: the reconcile's own read carries the manifest its
            // (rare) rewrite records as `previous`.
            val remaining = trashIndexSnapshot()
            if (remaining.cards.any { it.cloudComicId in purgedIds }) {
                rewriteTrashIndex(
                    remaining.cards.filterNot { it.cloudComicId in purgedIds },
                    previous = remaining.manifest,
                )
            }
        }
    }

    fun discardComic(cloudComicId: String) {
        INDEX_MUTATION_LOCK.withLock {
            val id = cloudComicId.trim()
            val prefix = CloudCollectionKeys.comicPrefix(id)
            val manifest = try {
                pagesManifestOrNull(id)
            } catch (error: NewerCloudFormatException) {
                throw error
            } catch (_: Exception) {
                null
            }
            // #C21 §2.1: one read per index; each rewrite is handed the
            // manifest that read observed under this lock.
            val existing = collectionIndexSnapshot()
            val trash = trashIndexSnapshot()
            val droppedActive = existing.cards.firstOrNull { it.cloudComicId == id }
            rewriteCollectionIndex(existing.cards.filterNot { it.cloudComicId == id }, previous = existing.manifest)
            val droppedTrash = trash.cards.firstOrNull { it.cloudComicId == id }
            if (droppedTrash != null) {
                rewriteTrashIndex(trash.cards.filterNot { it.cloudComicId == id }, previous = trash.manifest)
            }
            val dropped = droppedActive ?: droppedTrash
            // Authoritative deletion set: everything the bucket actually has
            // under this comic's prefix, discovered by listing — independent
            // of whether a manifest exists, and independent of its version.
            // This is what guarantees pages v1 or v2, thumbs, and any stray
            // object from a layout this app no longer writes all get swept, so
            // the bucket never accumulates objects this comic can no longer reach.
            val listed = runCatching {
                client.listKeys(prefix)
            }.onFailure { error ->
                // P4 (A28 review follow-up, owner principle "never silent"):
                // losing the listing is exactly when a discard degrades to
                // the constructed-key fallback and can leave unreachable
                // objects behind. Swallowing the throwable keeps the discard
                // going (correct — an outage must not abort it) but it must
                // never be unrecorded.
                log(
                    "discard listKeys failed for $prefix (${error.javaClass.simpleName}: " +
                        "${com.easemic.app.importing.opds.OpdsCatalogClient.redactUrl(error.message ?: "")}) — " +
                        "falling back to constructed keys; objects outside them may remain",
                )
            }.getOrDefault(emptyList())
            val constructed = buildList {
                add(CloudCollectionKeys.metaKey(id))
                add(CloudCollectionKeys.pagesManifestKey(id))
                dropped?.coverThumbObjectKey?.takeIf { it.startsWith(prefix) }?.let { add(it) }
                // Belt-and-suspenders only, for when listKeys() itself is
                // unavailable (e.g. transport outage): reconstruct exact keys
                // from a manifest that's actually known (cached or freshly
                // read). Deliberately does NOT guess ordinal-keyed names when
                // no manifest is available — a guess would be wrong for a v2
                // (hash-keyed) comic and achieve nothing; the prefix listing
                // above is the real source of truth whenever it can run.
                if (manifest != null) {
                    val pageCount = manifest.pageCount.coerceAtLeast(0)
                    // #C51: a v5 comic's pages live inside `pages/pack-NNNN.zip`, one object
                    // per pack index, PLUS the standalone content-addressed cover. Without
                    // these names an outage-time discard would strand every pack on the
                    // bucket forever — the exact failure this fallback exists to prevent.
                    if (manifest.version >= 5 && manifest.pagePacks.isNotEmpty()) {
                        manifest.pagePacks.indices.forEach { packIndex ->
                            add(CloudCollectionKeys.pagePackKey(id, packIndex))
                        }
                        manifest.pageHashes.firstOrNull()?.takeIf { it.length == 64 }?.let { hash0 ->
                            add(
                                CloudCollectionKeys.pageKeyByHash(
                                    id,
                                    hash0,
                                    manifest.pageExts.firstOrNull() ?: DEFAULT_EXT,
                                ),
                            )
                        }
                    } else if (manifest.version >= 2 && manifest.pageHashes.isNotEmpty()) {
                        for (ordinal in 0 until pageCount) {
                            val hash = manifest.pageHashes.getOrNull(ordinal) ?: continue
                            add(
                                CloudCollectionKeys.pageKeyByHash(
                                    id,
                                    hash,
                                    manifest.pageExts.getOrNull(ordinal) ?: DEFAULT_EXT,
                                ),
                            )
                        }
                    } else {
                        for (ordinal in 0 until pageCount) {
                            add(
                                CloudCollectionKeys.pageKey(
                                    id,
                                    ordinal,
                                    manifest.pageExts.getOrNull(ordinal) ?: DEFAULT_EXT,
                                ),
                            )
                        }
                    }
                    // #C5: manifest v3 records one thumb ext PER PAGE, so this
                    // reconstructs every `thumbs/<ordinal>.<ext>` object, not
                    // just a cover. #C47-C: a v4 manifest's thumbs live inside
                    // `thumbs/pack-NNNN.zip` instead — one object per
                    // `thumbPackSize` ordinals — so name those; without them
                    // an outage-time discard would strand every pack on the
                    // bucket forever, which is the exact failure this fallback
                    // exists to prevent. The prefix listing above is still the
                    // real sweep; this is the transport-outage fallback.
                    val packSize = manifest.thumbPackSize
                    if (packSize > 0) {
                        for (first in manifest.thumbExts.indices step packSize) {
                            add(CloudCollectionKeys.thumbPackKey(id, first))
                        }
                    } else {
                        manifest.thumbExts.forEachIndexed { ordinal, ext ->
                            add(CloudCollectionKeys.thumbKey(id, ordinal, ext))
                        }
                    }
                }
            }
            // C37-h: delete on the same 6-wide pool the upload path uses. One
            // 249-page comic is ~500 objects; sequential DELETEs took minutes.
            // Per-key semantics are unchanged: a 404 is tolerated (already
            // gone), any other non-2xx or a non-404 throwable fails the discard.
            val deleteExecutor = Executors.newFixedThreadPool(UPLOAD_PARALLELISM) { runnable ->
                Thread(runnable, "cloud-delete").apply { isDaemon = true }
            }
            try {
                val deletes = (constructed + listed).distinct().map { key ->
                    deleteExecutor.submit {
                        val code = try {
                            client.delete(key)
                        } catch (error: Exception) {
                            if (looksLikeMissingObject(error)) 404 else throw error
                        }
                        require(code in 200..299 || code == 404) {
                            "CLOUD_COLLECTION_DELETE_$code key=$key"
                        }
                    }
                }
                deletes.forEach { future ->
                    try {
                        future.get()
                    } catch (error: ExecutionException) {
                        throw error.cause ?: error
                    }
                }
            } finally {
                deleteExecutor.shutdownNow()
            }
            manifests.remove(CloudCollectionKeys.pagesManifestKey(id))
            forgetThumbPacks(id)
            forgetPageWindows(id)
        }
    }

    /**
     * #C47-C: drops this comic's packs from the 2-pack memo. The memo lives as
     * long as the store instance, and `memoizedCloudCollectionStore` hands one
     * instance to every download while the binding holds — so an upload that
     * rewrites the packs, or a discard that removes them, must forget the bytes
     * or a later read could serve a stale pack for that id.
     */
    private fun forgetThumbPacks(cloudComicId: String) {
        val prefix = CloudCollectionKeys.comicPrefix(cloudComicId)
        synchronized(thumbPacks) {
            thumbPacks.keys.removeIf { it.startsWith(prefix) }
            thumbPackEpoch.incrementAndGet()
        }
    }

    /**
     * #C51: drops this comic's page windows from the memo and bumps the epoch, so a window
     * already in flight when an upload rewrote the packs (or a discard removed them) is
     * served to its own caller and then dropped instead of memoised. Same reasoning as
     * [forgetThumbPacks]: `memoizedCloudCollectionStore` hands ONE instance to every
     * download while the binding holds.
     *
     * `internal` so the window tests can move the epoch at a chosen moment.
     */
    internal fun forgetPageWindows(cloudComicId: String) {
        val prefix = CloudCollectionKeys.comicPrefix(cloudComicId.trim())
        synchronized(pageWindows) {
            pageWindows.keys.removeIf { it.first.startsWith(prefix) }
            pageWindowEpoch.incrementAndGet()
        }
    }

    /** [previous] is the manifest the caller observed UNDER the lock — see [rewriteIndex]. */
    private fun rewriteCollectionIndex(
        cards: List<CloudCollectionCard>,
        previous: CloudCollectionManifest?,
    ): CloudIndexGenerations =
        rewriteIndex(
            cards = cards,
            chunkKey = CloudCollectionKeys::indexChunkKey,
            manifestKey = CloudCollectionKeys.collectionManifestKey(),
            previous = previous,
            isTrash = false,
        )

    /** [previous] is the manifest the caller observed UNDER the lock — see [rewriteIndex]. */
    private fun rewriteTrashIndex(
        cards: List<CloudCollectionCard>,
        previous: CloudCollectionManifest?,
    ) {
        rewriteIndex(
            cards = cards,
            chunkKey = CloudCollectionKeys::trashIndexChunkKey,
            manifestKey = CloudCollectionKeys.trashManifestKey(),
            previous = previous,
            isTrash = true,
        )
    }

    /**
     * [previous] is the manifest the caller read — with the cards it is about
     * to rewrite — inside the SAME [INDEX_MUTATION_LOCK] block, via
     * [readIndexSnapshot]. Invariant kept: `previousGeneration` is still the
     * generation observed under the lock; it is simply passed in instead of
     * re-fetched.
     *
     * re-review/#C21 audit B2 step 5: the manifest was re-read here
     * microseconds after the caller read it under the same lock — one RTT per
     * index write, deleted.
     */
    private fun rewriteIndex(
        cards: List<CloudCollectionCard>,
        chunkKey: (Int) -> String,
        manifestKey: String,
        previous: CloudCollectionManifest?,
        isTrash: Boolean,
        singleAttempt: Boolean = false,
    ): CloudIndexGenerations {
        fun writeChunk(key: String, bytes: ByteArray) {
            if (singleAttempt) putOnce(key, bytes, ObjectStore.JSON_MIME) else put(key, bytes)
        }
        val chunks = cards.chunked(chunkSize)
        val previousChunkCount = previous?.declaredChunkCount() ?: 0
        chunks.forEachIndexed { index, chunkCards ->
            writeChunk(
                chunkKey(index),
                CloudCollectionIndex.encodeChunk(
                    CloudCollectionIndexChunk(chunkIndex = index, cards = chunkCards),
                ),
            )
        }
        for (orphan in chunks.size until previousChunkCount) {
            writeChunk(
                chunkKey(orphan),
                CloudCollectionIndex.encodeChunk(
                    CloudCollectionIndexChunk(chunkIndex = orphan, cards = emptyList()),
                ),
            )
        }
        val newGeneration = (previous?.generation ?: 0) + 1
        val collectionFormat = if (isTrash) {
            readManifestOrNull(CloudCollectionKeys.collectionManifestKey())?.format ?: 1
        } else {
            1
        }
        val format = maxOf(
            previous?.format ?: 1,
            collectionFormat,
            if (cards.any { it.layoutKind == CloudCardLayout.SERIES }) 2 else 1,
        )
        val manifest = CloudCollectionManifest(
            format = format, generation = newGeneration, comicCount = cards.size, chunkSize = chunkSize,
        )
        val bytes = CloudCollectionIndex.encodeManifest(manifest)
        if (singleAttempt) {
            try {
                putOnce(manifestKey, bytes, ObjectStore.JSON_MIME)
            } catch (timeout: java.io.IOException) {
                if (Thread.currentThread().isInterrupted) throw timeout
                // #E30: response timeout or watchdog socket close can follow a committed write.
                // Equality includes generation, format, count and chunk size; a prior generation is not proof.
                val landed = try {
                    readManifestOrNull(manifestKey) == manifest
                } catch (cancelled: java.util.concurrent.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (!landed) throw timeout
            }
        } else {
            put(manifestKey, bytes)
        }
        return CloudIndexGenerations(
            previousGeneration = previous?.generation,
            newGeneration = newGeneration,
            comicCount = cards.size,
        )
    }

    /**
     * Missing index object → empty (first Collect).
     * Present but unparseable → [UnreadableCloudLibraryException] (never clobber).
     */
    private fun loadIndexCardsForMerge(
        chunkIndex: Int,
        chunkKey: (Int) -> String = CloudCollectionKeys::indexChunkKey,
    ): List<CloudCollectionCard> {
        val bytes = downloadOrNull(chunkKey(chunkIndex)) ?: return emptyList()
        return decodeOrUnreadable { CloudCollectionIndex.decodeChunk(bytes) }.cards
    }

    /** Missing → null (first Collect); present but unparseable → fail closed. */
    private fun readManifestOrNull(manifestKey: String): CloudCollectionManifest? {
        val bytes = downloadOrNull(manifestKey) ?: return null
        return decodeOrUnreadable { CloudCollectionIndex.decodeManifest(bytes) }
    }

    /**
     * C10: [manifests] alone is check-then-act, so the 6 concurrent page
     * fetches DownloadWorkerLoop runs for one cloud comic all missed the memo
     * and each issued its own `pages.manifest.json` GET. The per-id lock makes
     * the first caller fetch while the rest wait and then hit the memo; locks
     * are per comic, so unrelated comics still read in parallel.
     */
    private fun pagesManifestOrNull(cloudComicId: String): CloudPageManifestPayload? =
        pagesManifestOrNull(GalleryCloudObjectLayout(cloudComicId.trim()))

    private fun pagesManifestOrNull(layout: CloudObjectLayout): CloudPageManifestPayload? {
        val key = layout.pagesManifestKey()
        manifests[key]?.let { return it }
        synchronized(manifestLocks.computeIfAbsent(key) { Any() }) {
            manifests[key]?.let { return it }
            return CloudOpenTrace.timed("manifest-get", extras = { "id=${layout.objectId}" }) {
                val bytes = downloadOrNull(key) ?: return@timed null
                decodeOrUnreadable { CloudPageManifest.decode(bytes) }.also { manifests[key] = it }
            }
        }
    }


    /**
     * Parses the SHA-256 hex digest out of a v2 page key
     * (`<pagesPrefix><hash>.<ext>`); `null` for anything else (a v1
     * ordinal-keyed `NNNN.<ext>` page, or an unrelated object under the
     * prefix), so mixed v1/v2 listings never misread an ordinal as a hash.
     */
    private fun hashFromPageKey(key: String, pagesPrefix: String): String? {
        val rest = key.removePrefix(pagesPrefix)
        val candidate = rest.substringBefore('.')
        return candidate.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    private fun downloadOrNull(objectKey: String): ByteArray? =
        try {
            client.download(objectKey)
        } catch (error: Exception) {
            if (looksLikeMissingObject(error)) null else throw error
        }

    /** Magic-byte sniff of the file's leading bytes; never loads a whole page. */
    private fun contentTypeOf(file: File): ImageContentType {
        val head = ByteArray(SNIFF_BYTES)
        val read = file.inputStream().use { it.read(head) }
        val sniffable = if (read <= 0) ByteArray(0) else head.copyOf(read)
        return ImageContentType.sniff(sniffable)
    }

    /**
     * S4 Task 1 (owner bug C4): thumb bytes now arrive in-memory (the
     * ingest-side thumbnailer's JPEG output), never as a [File] — this
     * overload sniffs the same leading bytes without a temp-file round trip.
     */
    private fun contentTypeOf(bytes: ByteArray): ImageContentType {
        val sniffable = if (bytes.isEmpty()) bytes else bytes.copyOf(minOf(SNIFF_BYTES, bytes.size))
        return ImageContentType.sniff(sniffable)
    }

    /**
     * [mime] defaults to `application/json` because every object this store
     * writes is JSON except pages/thumbs, which pass their sniffed type.
     *
     * Every non-page object write goes through the same bounded retry as
     * pages (C37-f): a 503 on the manifest or the index is exactly as
     * transient as one on a page, and failing the whole upload on it threw
     * away work that was already durable on the bucket.
     */
    private fun put(objectKey: String, bytes: ByteArray, mime: String = ObjectStore.JSON_MIME) {
        putWithRetry(objectKey, { bytes }, mime)
    }

    private fun putOnce(objectKey: String, bytes: ByteArray, mime: String) {
        val code = client.upload(objectKey, bytes, mime)
        require(code in 200..299) { "CLOUD_COLLECTION_UPLOAD_$code key=$objectKey" }
    }

    /**
     * One object PUT with a short bounded retry. Transient failures
     * (transport [java.io.IOException] or server-side 5xx / 429-as-status)
     * get up to [PUT_ATTEMPTS] sends. A 401 gets exactly one extra attempt,
     * separate from that budget: the bearer source has already refreshed by
     * the time the store returned the status (C37-c), so a second send is
     * usually good; a second 401 means a dead grant and the body is not sent
     * a third time. A single "Connection reset" used to fail the whole
     * ingest, which then paid a full-bucket listing + full re-hash before
     * resuming (owner's phone log, 2026-09-09). Other client errors (4xx)
     * are not retried — they will not change.
     *
     * A [CloudRateLimitedException] is NOT retried here: the whole account is
     * blocked for its `retryAfterSeconds`, which local sleeps cannot outlast;
     * the ingest layer waits it out (C37-f).
     */
    private fun putWithRetry(objectKey: String, bytes: () -> ByteArray, mime: String) {
        putWithLadder(sharedLane = false) { putOnce(objectKey, bytes(), mime) }
    }

    /**
     * #C51: the same ladder for a body that stays on DISK. A 32 MiB page pack must never be
     * read into a `ByteArray` here — that is the whole reason packs are streamed into the
     * work dir — so the send goes through [ObjectStore.uploadFile].
     */
    private fun putWithRetryFile(objectKey: String, file: File, mime: String) {
        putWithLadder(sharedLane = true) {
            val code = client.uploadFile(objectKey, file, mime)
            require(code in 200..299) { "CLOUD_COLLECTION_UPLOAD_$code key=$objectKey" }
        }
    }

    /**
     * [sharedLane]: a page PACK (multi-MiB, from disk) takes one of the app-wide [PUT_LANES] for its send; small
     * objects (meta, manifest, thumb packs, index — a few KB) never wait for one, so a comic's finishing writes are
     * not queued behind other comics' packs (#D12 follow-up: "stuck finishing" on the owner's phone).
     */
    private fun putWithLadder(sharedLane: Boolean, send: () -> Unit) {
        var attempt = 0
        var unauthorizedRetried = false
        while (true) {
            try {
                // #D12: one pack send = one lane of the app-wide budget; the retry sleep below holds none.
                if (sharedLane) PUT_LANES.acquire()
                try {
                    send()
                } finally {
                    if (sharedLane) PUT_LANES.release()
                }
                return
            } catch (rateLimited: CloudRateLimitedException) {
                throw rateLimited
            } catch (error: Exception) {
                if (!Thread.currentThread().isInterrupted && !unauthorizedRetried && isUnauthorized(error)) {
                    // The bearer source refreshed once by the time the store
                    // returned 401 (C37-c). Retry exactly once WITHOUT spending
                    // the transient budget; a second 401 means a dead grant —
                    // do not re-send the body again.
                    unauthorizedRetried = true
                    continue
                }
                attempt++
                if (Thread.currentThread().isInterrupted || attempt >= PUT_ATTEMPTS || !isRetryablePutFailure(error)) {
                    throw error
                }
                Thread.sleep(PUT_RETRY_DELAYS_MS[(attempt - 1).coerceAtMost(PUT_RETRY_DELAYS_MS.lastIndex)])
            }
        }
    }

    private fun isUnauthorized(error: Exception): Boolean =
        Regex("""CLOUD_(?:COLLECTION_UPLOAD|HTTP)_401""").containsMatchIn(error.message.orEmpty())

    private fun isRetryablePutFailure(error: Exception): Boolean {
        if (error is java.io.IOException) return true
        val code = Regex("""CLOUD_(?:COLLECTION_UPLOAD|HTTP)_(\d{3})""")
            .find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            ?: return false
        // In production both transports throw CloudRateLimitedException for a
        // 429 (caught and rethrown above); this status-code branch is
        // defence-in-depth only, for a transport that ever reports 429 as a
        // plain HTTP status instead.
        return code == 429 || code >= 500
    }

    companion object {
        /**
         * Concurrent page/thumb PUTs per upload. Cloud storage has no source
         * politeness gate; keep this aligned with the cloud transfer policy's
         * six-wide bound so the uploader does not leave bandwidth idle.
         *
         * #C17 audit A4: this was a hand-copied literal (6) that happened to
         * equal SourceThrottlePolicy.CLOUD.maxConcurrentFetches instead of
         * reading it — reads the policy now so the two cannot silently drift
         * apart (same shape as CloudReaderPrefetcher.LANES).
         */
        val UPLOAD_PARALLELISM: Int get() = SourceThrottlePolicy.CLOUD.maxConcurrentFetches

        /**
         * #D12: the page-PACK lanes are ONE budget for the whole app, not one per comic. Each upload keeps its own pool
         * (its lanes also build packs and read indexes), but a pack send takes a permit here first; small objects don't. Owner phone 2026-09-27: five
         * comics x six private lanes = 30 PUTs of 8 MiB on a 140-210 KB/s uplink — every PUT hit its deadline, retried,
         * and the uploads restarted from zero for ever.
         */
        private val PUT_LANES = java.util.concurrent.Semaphore(SourceThrottlePolicy.CLOUD.maxConcurrentFetches, true)

        /** Attempts per page PUT (1 initial + retries). */
        const val PUT_ATTEMPTS: Int = 3
        private val PUT_RETRY_DELAYS_MS = longArrayOf(500L, 1_500L)

        /**
         * Serializes collection/trash index read-merge-write across instances.
         * Serialized tails use tryLock and a single publication attempt; their
         * required summary writes use SERIES_LOCKS, never this network queue.
         * Gallery/trash mutations retain their existing retry contract.
         */
        private val INDEX_MUTATION_LOCK = ReentrantLock()
        private val SERIES_LOCKS = Array(64) { ReentrantLock() }

        /**
         * #E111: per-series deferred chapter tails, companion-level like
         * [SERIES_LOCKS] because the ingest runner opens a fresh
         * [CloudCollectionStore] per queue row — an instance field would strand
         * every deferred tail the moment its row finished. A tail's chapter
         * objects are already durable; the only thing an entry ever risks to a
         * process death is the series.json descriptor, which the chapter
         * ledger's synced-key repair restores on the series' next write.
         */
        private val PENDING_CHAPTER_TAILS =
            java.util.concurrent.ConcurrentHashMap<String, MutableList<PendingChapterTail>>()

        fun hasPendingChapterTails(): Boolean = PENDING_CHAPTER_TAILS.isNotEmpty()

        /** Naming fallback when a manifest predates per-object extensions. */
        private val DEFAULT_EXT = ImageContentType.JPEG.extension

        /**
         * #C47-C: thumb packs memoised per store instance (see [downloadThumb]).
         * Two packs ≈ 640 KB at the measured median thumb — enough for the
         * preview strip's serial walk plus an interleaved cover-fallback read
         * of another comic, never a whole comic's worth of packs.
         */
        const val THUMB_PACK_MEMO: Int = 2

        /**
         * #C51: one `Range` request covers this many bytes of WHOLE pages of one pack —
         * about four pages at the measured 161 KB median, so the reader's next few pages
         * and the pump's next few lanes are served with no request at all. A page larger
         * than this is fetched alone.
         */
        internal const val PAGE_WINDOW_BYTES: Long = 4L shl 20

        /** #C51: a page's data offset inside its pack. Fail closed with counts only, never a key. */
        private fun pageOffsetOf(manifest: CloudPageManifestPayload, ordinal: Int): Long =
            manifest.pageOffsets.getOrNull(ordinal)
                ?: error("v5 manifest has no offset for ordinal $ordinal of ${manifest.pageCount}")

        /** #C51: one past a page's last byte inside its pack. */
        private fun pageEndOf(manifest: CloudPageManifestPayload, ordinal: Int): Long {
            val length = manifest.pageBytes.getOrNull(ordinal) ?: 0L
            check(length > 0) { "v5 manifest has no length for ordinal $ordinal of ${manifest.pageCount}" }
            return pageOffsetOf(manifest, ordinal) + length
        }

        /**
         * #C51: how the pack at [packIndex] is tiled into windows — THE statement of the rule
         * [downloadPageFromPack] describes, spelled once so the read path and a request count
         * derived from the manifest can never disagree.
         *
         * Whole pages only, never crossing the pack; a page larger than [PAGE_WINDOW_BYTES] is
         * a window of its own. A window spans from its first page's DATA to its last page's
         * data end, and the STORED local header (30 bytes + the entry name) that precedes every
         * later page lies inside it — which is why four 1 MiB pages do not fit a 4 MiB window
         * and three do. Counting `ceil(totalBytes / PAGE_WINDOW_BYTES)` would ignore those
         * headers and under-count.
         */
        internal fun packWindows(manifest: CloudPageManifestPayload, packIndex: Int): List<PageWindow> {
            val pack = manifest.pagePacks[packIndex]
            val endExclusive = pack.first + pack.count
            val windows = ArrayList<PageWindow>()
            var first = pack.first
            while (first < endExclusive) {
                val start = pageOffsetOf(manifest, first)
                var last = first
                while (last + 1 < endExclusive && pageEndOf(manifest, last + 1) - start <= PAGE_WINDOW_BYTES) {
                    last++
                }
                windows += PageWindow(packIndex, first, last, start, (pageEndOf(manifest, last) - start).toInt())
                first = last + 1
            }
            return windows
        }

        /** Every window of every pack of a v5 [manifest], in pack then ordinal order. */
        internal fun windowsFor(manifest: CloudPageManifestPayload): List<PageWindow> =
            manifest.pagePacks.indices.flatMap { packWindows(manifest, it) }

        /**
         * How many [ObjectStore.downloadRange] requests reading every page of a v5 [manifest]
         * in order costs: exactly one per window, because each window is fetched once and every
         * page inside it is then served from the memo.
         */
        internal fun windowCount(manifest: CloudPageManifestPayload): Int =
            manifest.pagePacks.indices.sumOf { packWindows(manifest, it).size }

        /**
         * #C51: windows memoised per store instance — ≈ 12 MiB at [PAGE_WINDOW_BYTES].
         * Three is enough for the page being read plus the runway ahead of it, and never a
         * whole comic's worth of packs.
         */
        internal const val PAGE_WINDOW_MEMO: Int = 3

        /** Magic bytes are all in the first few bytes; never load a whole page to sniff. */
        private const val SNIFF_BYTES = 16

    }
}

private inline fun <T> decodeOrUnreadable(decode: () -> T): T =
    try {
        decode()
    } catch (error: NewerCloudFormatException) {
        throw error
    } catch (error: UnreadableCloudLibraryException) {
        throw error
    } catch (error: Exception) {
        throw UnreadableCloudLibraryException(error)
    }
