package com.easemic.app.feature.download

import com.easemic.app.feature.browse.RemoteProgressPolicy
import com.easemic.app.feature.browse.RemoteReadingProgressStore
import com.easemic.app.feature.collect.CloudSyncQueueItem
import com.easemic.app.feature.collect.CloudSyncStage
import com.easemic.app.feature.collect.CollectBookmarkStore
import com.easemic.app.feature.library.galleryDedupKey
import com.easemic.app.sync.DiagLog
import com.easemic.app.sync.redactAcquisition
import com.easemic.app.sync.redactNamespace
import java.io.File

/**
 * #C19 audit F12 (owner decision 2026-09-19: delete, no owner action). Before
 * `cbb2277d` a Detail collect could mint the double-prefixed namespace
 * `opds:opds:<uuid>`; the owner's phone still carries its rows, dirs and
 * bookmarks. This pass runs on EVERY start and is a no-op after the first —
 * no one-shot marker (the #C13 lesson: a burnt seed is a bug that hides).
 *
 * Order matters: [rewriteBookmarks] runs SYNCHRONOUSLY in AppGraph.start()
 * because `opdsNamespace` is idempotent — a bookmark already carrying the bad
 * prefix would propagate it through the cloud catch-up sweep. [purge] runs on
 * IO afterwards. Cloud objects are never touched (a duplicate object tree in
 * the bucket is an owner decision — the sweep does not even look).
 */
class OpdsDoubleNamespaceCleanup(
    private val loadAllJobs: suspend () -> List<DownloadJob>,
    private val purgeJob: suspend (DownloadJob, deleteCatalogTree: Boolean) -> Unit,
    private val queueRows: () -> List<CloudSyncQueueItem>,
    private val removeQueueRow: (String) -> Unit,
    /**
     * NEW-3: the app's own delete verb refuses to unlink while a cloud upload
     * is in flight (`cloudUploadInFlight`, the C4 §2.1 fix). The sweep refuses
     * too — the ingest is reading that row's staging revision.
     *
     * r4 NEW-11: the refusal covers BOTH halves. The row is left in place AND
     * its two namespace-rooted directories — the staging pages and the locator
     * — are held back from the directory sweep in [purge], which used to delete
     * exactly what the skip had just protected. The next start takes the row
     * once it is terminal.
     */
    private val uploadInFlight: (DownloadJob) -> Boolean = { false },
    private val filesDir: File,
    private val locatorRoot: File,
    private val readyPagesRoot: File,
    private val log: (String) -> Unit = { DiagLog.i(TAG, it) },
) {
    data class PurgeSummary(val jobs: Int, val queueRows: Int, val dirs: Int, val skipped: Int = 0)

    data class BookmarkSummary(val rewritten: Int, val removed: Int)

    /**
     * Rows, then queue rows, then the two on-disk trees that spell the
     * namespace in their directory name — except the trees of a row this pass
     * skipped, which stay whole (r4 NEW-11). The shelf tree has TWO shapes and
     * this pass meets both: written after #C20 HIGH-1 it is per-namespace and
     * belongs to the doubled row alone (delete it); written before it, it is
     * namespace-blind and a canonical row at the same revision owns the very
     * same directory (keep it). [sharesCatalogTree] decides which. Either way
     * only a job row can find that tree — that is what [purgeJob] is for, per
     * row. A legacy tree this purge leaves behind that nothing references any
     * more is reclaimed by [reclaimOrphanLegacyCatalogTrees], which runs right
     * AFTER this purge with the Library's page rows as the authority (r2
     * MEDIUM-2).
     */
    suspend fun purge(): PurgeSummary {
        var jobs = 0
        var rows = 0
        var dirs = 0
        var skipped = 0
        val held = mutableSetOf<String>()
        // NEW-2: this runs from a bare `launch` on a scope with NO
        // CoroutineExceptionHandler, and the C2 staging reclaim runs after it in
        // the same block. A throw out of ANY seam here would crash the start and
        // skip that reclaim, which is the #C18-F7 lesson two files over.
        return runCatching {
            val all = loadAllJobs()
            all.filter { isDoubled(it.identity.providerNamespace) }.forEach { job ->
                if (
                    job.state == DownloadJobState.WAIT ||
                    job.state == DownloadJobState.DOWNLOAD ||
                    uploadInFlight(job)
                ) {
                    skipped += 1
                    held += job.identity.providerNamespace
                    log("skipped job ${job.id} state=${job.state} — active or uploading; next start")
                    return@forEach
                }
                // NEW-1, retightened by #C20 re-review HIGH-2(a): the question
                // is whether a canonical row owns the same DIRECTORY, not the
                // same (id, revision). Post-#C20-HIGH-1 the doubled row owns a
                // namespaced tree of its own, and answering the old question
                // leaked it permanently — the job row is purged in this same
                // pass, so no later sweep could ever find it again.
                val treeShared = all.any {
                    !isDoubled(it.identity.providerNamespace) &&
                        it.identity.canonicalItemId == job.identity.canonicalItemId &&
                        it.downloadRevision == job.downloadRevision &&
                        sharesCatalogTree(job, it)
                }
                runCatching { purgeJob(job, !treeShared) }
                    .onSuccess {
                        jobs += 1
                        log(
                            "purged job ${job.id} ns=${redactNamespace(job.identity.providerNamespace)} " +
                                "tree=${if (treeShared) "kept (shared with a canonical row)" else "deleted"}",
                        )
                    }
                    .onFailure { log("purge failed for job ${job.id}: ${it::class.simpleName}") }
            }
            queueRows().filter { it.providerId?.let(::isDoubled) == true }.forEach { row ->
                // MEDIUM-2 (#C20 audit): only a parked row may go. Anything else
                // is queued or running — the same "in flight" reading
                // `cloudUploadInFlight` gives the job predicate above — and a
                // removed row would make the running ingest finish into nothing.
                if (row.stage != CloudSyncStage.FAILED) {
                    skipped += 1
                    log("skipped queue row stage=${row.stage} — in flight; next start")
                    return@forEach
                }
                removeQueueRow(row.id)
                rows += 1
            }
            // r4 NEW-11: a skipped row's staging pages and locator live INSIDE
            // these namespace-rooted directories, so a sweep that does not
            // exclude them unlinks precisely what the skip above refused to
            // unlink — and it takes the locator with it, so the next start
            // reads `uploadInFlight` as false and purges the row unguarded.
            // Each held name is derived the way the store that WROTE it does
            // rather than re-spelled here.
            listOf(
                Triple(locatorRoot, LOCATOR_DIR_PREFIX, held.mapTo(mutableSetOf(), ::locatorDirName)),
                Triple(readyPagesRoot, BAD_PREFIX, held.mapTo(mutableSetOf(), ::readyDirName)),
            ).forEach { (root, prefix, heldNames) ->
                root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(prefix) }.forEach { dir ->
                    if (dir.name in heldNames) {
                        // #C52 round 4 (re-review R6): redactAcquisition, NOT redactNamespace.
                        // `dir.name` is a filesystem directory name, not a providerNamespace, and
                        // every doubled namespace directory is the same LENGTH -- collapsing them
                        // all to `nsLen=<n>` made the kept line and the deleted line of a
                        // DESTRUCTIVE sweep indistinguishable on the owner's phone. `len=<n>
                        // h8=<8 hex>` hides exactly as much and gives each directory a stable,
                        // distinct token, so "which one did it delete" stays answerable.
                        log("kept ${root.name}/${redactAcquisition(dir.name)} — its row is active or uploading; next start")
                        return@forEach
                    }
                    if (dir.deleteRecursively()) {
                        dirs += 1
                        log("deleted ${root.name}/${redactAcquisition(dir.name)}")
                    }
                }
            }
            if (jobs + rows + dirs + skipped > 0) {
                log("summary jobs=$jobs queueRows=$rows dirs=$dirs skipped=$skipped")
            }
            PurgeSummary(jobs, rows, dirs, skipped)
        }.getOrElse {
            log("purge aborted: ${it::class.simpleName}")
            PurgeSummary(jobs, rows, dirs, skipped)
        }
    }

    /**
     * True when [job] and [sibling] resolve to ONE catalog tree, which is the
     * only reason the doubled row's tree may not be deleted.
     *
     * Two shapes exist on disk and this pass meets both. A tree written AFTER
     * #C20 HIGH-1 is per-namespace, so the two rows name different directories
     * and the doubled one is the doubled row's alone. A tree written BEFORE it
     * is namespace-blind (`<root>/<item>/r<rev>`), so the two rows name the SAME
     * directory and deleting it would take the canonical comic's pages with it —
     * nothing on disk is ever moved (r2 BLOCKING-1), so the legacy shape is
     * always live here. "The new path is absent" is what separates them: the
     * doubled row's namespaced tree does not exist, so the pages it points at
     * can only be in the shared legacy dir.
     *
     * `filesDir` is an explicit constructor argument (r2 MEDIUM-1): the app
     * files dir the tree derivation expects, pinned rather than recovered from
     * `readyPagesRoot`'s parent by coincidence.
     */
    private fun sharesCatalogTree(job: DownloadJob, sibling: DownloadJob): Boolean {
        val own = catalogDownloadRevisionDirectory(filesDir, job.identity, job.downloadRevision).absoluteFile
        val theirs = catalogDownloadRevisionDirectory(filesDir, sibling.identity, sibling.downloadRevision).absoluteFile
        if (own == theirs) return true
        if (own.exists()) return false
        val ownLegacy = legacyCatalogDownloadRevisionDirectory(filesDir, job.identity, job.downloadRevision)?.absoluteFile
        val siblingLegacy =
            legacyCatalogDownloadRevisionDirectory(filesDir, sibling.identity, sibling.downloadRevision)?.absoluteFile
        return ownLegacy != null && ownLegacy == siblingLegacy
    }

    companion object {
        const val TAG = "OpdsCleanup"
        const val BAD_PREFIX = "opds:opds:"

        /** `FileDownloadLocatorStore.fileFor` sanitises ':' to '_'. */
        const val LOCATOR_DIR_PREFIX = "opds_opds_"

        /**
         * r4 NEW-11: the two directory names one namespace owns, mirroring the
         * store that writes each. `ReadyPageStore.sanitize` folds only `\`, `/`
         * and NUL, so a namespace keeps its colons; `FileDownloadLocatorStore.fileFor`
         * folds ':' as well. Both then go through [BoundedPathSegment.of], which
         * returns a 46-byte namespace verbatim but would digest a longer one —
         * so the derivation, not the raw string, is what the sweep compares.
         */
        private fun readyDirName(namespace: String): String =
            BoundedPathSegment.of(namespace.replace(Regex("[\\\\/\\u0000]"), "_"))

        private fun locatorDirName(namespace: String): String =
            BoundedPathSegment.of(namespace.replace(Regex("[\\\\/\\u0000:]"), "_"))

        fun isDoubled(namespace: String): Boolean = namespace.startsWith(BAD_PREFIX)

        /**
         * Strips ONE leading `opds:` from a doubled value — a namespace
         * `opds:opds:<uuid>` or an id shaped `opds:opds:<uuid>:<cid>`. A value
         * not starting with the bad prefix is returned unchanged.
         */
        fun canonical(namespace: String): String =
            if (isDoubled(namespace)) namespace.removePrefix(GalleryIdentityV1.OPDS_PREFIX) else namespace

        /**
         * A doubled bookmark whose comic has a canonical sibling (same
         * acquisition, or already holding the very id the rewrite would
         * produce) is a duplicate and is removed; one with NO sibling is the
         * comic's only bookmark, and deleting it would uncollect the comic, so
         * it is rewritten in place (id and providerId; title, cover,
         * acquisition and feed kept). The id clause matters: `upsert` replaces
         * by id, so without it a rewrite would overwrite a good canonical
         * bookmark with the bad one's (possibly stale-key) acquisition URL.
         */
        fun rewriteBookmarks(
            store: CollectBookmarkStore,
            progress: RemoteReadingProgressStore? = null,
            log: (String) -> Unit = { DiagLog.i(TAG, it) },
        ): BookmarkSummary = runCatching {
            // NEW-5: this runs SYNCHRONOUSLY on the main thread from
            // Application.onCreate. A throw out of `readAll`/`upsert`/`remove`
            // would fail onCreate and the app could not start at all.
            val all = store.readAll()
            val doubled = all.filter { isDoubled(it.providerId) }
            if (doubled.isEmpty()) return@runCatching BookmarkSummary(0, 0)
            var rewritten = 0
            var removed = 0
            doubled.forEach { bad ->
                // NEW-6: every other identity comparison in this feature goes
                // through `galleryDedupKey`, so this one does too — but be
                // honest about what that buys HERE. r4 LOW #2: the key's
                // e-h/nh/cloud short-circuits cannot fire on an OPDS
                // acquisition URL, so it falls through to
                // `normalizeAcquisitionKey` = `trim().lowercase()`
                // (`ShelfEntry.kt:68`). What the switch buys is case- and
                // whitespace-insensitivity plus the null guard; a ROTATED OPDS
                // key still differs character by character and is caught only
                // by the `it.id == canonical(bad.id)` clause below. A null key
                // (blank acquisition) is "cannot tell" and never vouches — the
                // removal is irreversible, the rewrite is not.
                val badKey = galleryDedupKey(bad.acquisitionUrl)
                val sibling = all.any {
                    !isDoubled(it.providerId) &&
                        (
                            (
                                it.providerId == canonical(bad.providerId) &&
                                    badKey != null &&
                                    galleryDedupKey(it.acquisitionUrl) == badKey
                                ) ||
                                it.id == canonical(bad.id)
                            )
                }
                store.remove(bad.id, bad.providerId)
                if (sibling) {
                    removed += 1
                } else {
                    val canonicalId = canonical(bad.id)
                    val canonicalProviderId = canonical(bad.providerId)
                    store.upsert(bad.copy(id = canonicalId, providerId = canonicalProviderId))
                    // NEW-4: the reading position is keyed on BOTH halves the
                    // rewrite just changed — migrate it or the comic reopens at
                    // page 1 with no trace of why.
                    progress?.rekey(
                        RemoteProgressPolicy.keyForIds(bad.providerId, bad.id),
                        RemoteProgressPolicy.keyForIds(canonicalProviderId, canonicalId),
                    )
                    rewritten += 1
                }
            }
            log("bookmarks rewritten=$rewritten removed=$removed")
            BookmarkSummary(rewritten, removed)
        }.getOrElse {
            log("bookmark rewrite aborted: ${it::class.simpleName}")
            BookmarkSummary(0, 0)
        }
    }
}

/**
 * The per-row delete chain `AppGraph.start()` wires into [OpdsDoubleNamespaceCleanup.purge].
 * Explicit seams so a JVM test can run the REAL file deletes (re-review NEW-7).
 *
 * [deleteCatalogTree] is the NEW-1 decision, taken by the caller
 * ([OpdsDoubleNamespaceCleanup.sharesCatalogTree]): a shelf tree written BEFORE
 * #C20's namespace level, `files/opds-downloads/<sanitised canonicalItemId>/r<rev>`,
 * carries NO namespace, so a doubled row and its canonical sibling name ONE
 * directory and it must NOT be deleted; a tree written after it is
 * `files/opds-downloads/<namespace>/<id>/r<rev>` and belongs to the doubled row
 * alone. It governs the NAMESPACED path only: the legacy one is namespace-blind
 * and never deleted here — nothing on disk is moved (r2 BLOCKING-1), and a
 * legacy tree nothing references any more is reclaimed once at start by
 * [reclaimOrphanLegacyCatalogTrees], where the other rows and the Library's
 * page directories are in scope. Everything else here is namespace-keyed and
 * always safe to unlink.
 */
suspend fun purgeDoubledJobFiles(
    job: DownloadJob,
    deleteCatalogTree: Boolean,
    filesDir: File,
    readyPageStore: ReadyPageStore,
    locatorStore: DownloadLocatorStore,
    removeLibraryAvailability: suspend (GalleryIdentityV1) -> Unit,
    purgeRow: suspend (String) -> Unit,
) {
    readyPageStore.deletePublishedRevision(job.identity, job.downloadRevision)
    readyPageStore.cleanPartTemps(job.identity, job.downloadRevision)
    if (deleteCatalogTree) {
        deleteCatalogDownloadRevision(
            filesDir = filesDir,
            identity = job.identity,
            downloadRevision = job.downloadRevision,
        )
    }
    removeLibraryAvailability(job.identity)
    locatorStore.remove(job.identity)
    purgeRow(job.id)
}
