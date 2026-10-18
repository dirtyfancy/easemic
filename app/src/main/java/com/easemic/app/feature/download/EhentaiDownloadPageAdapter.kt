package com.easemic.app.feature.download

import com.easemic.app.feature.browse.BrowseFeedRepository
import com.easemic.app.importing.ehentai.EhentaiClient
import com.easemic.app.importing.ehentai.EhentaiException
import com.easemic.app.importing.ehentai.EhentaiGalleryPlan
import com.easemic.app.importing.ehentai.EhentaiPageImage
import com.easemic.app.importing.ehentai.EhentaiSlowNodeException
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Path
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException

/**
 * Front (E-Hentai) page-bytes adapter. Identity is numeric gid; token lives only
 * on [DownloadLocator] so rotation does not duplicate jobs (spec §4.1).
 *
 * This adapter is a process-wide singleton (wired once in AppGraph) shared by
 * every concurrently-downloading gallery, so all cross-gallery state below is
 * explicitly keyed by gid — never by ordinal alone — per SEND_BACK item 8
 * (owner bug backlog): a reader on gallery B must never disturb gallery A's
 * cached plan or rotation streak.
 */
class FrontDownloadPageAdapter(
    private val client: () -> EhentaiClient,
    private val imageGet: (imageUrl: String) -> ByteArray,
    private val imageTransfer: PageImageGet? = imageGet as? PageImageGet,
) : DownloadPageBytesAdapter {
    override fun usesDiskBackedTransfer(identity: GalleryIdentityV1, locator: DownloadLocator): Boolean =
        supports(identity) && imageTransfer != null


    // SEND_BACK item 8: a single-slot plan cache meant a reader on gallery B
    // evicted gallery A's plan (and, worse, wiped gallery A's H@H rotation
    // streak below) on every interleaved read. A small LRU keeps a handful
    // of recently-active galleries' plans warm; only the plan actually
    // evicted from the LRU (or explicitly invalidated/replaced for its OWN
    // gid) has its rotation state cleared — never a different gid's.
    // B1 (owner bug backlog, S3 re-review): this adapter is a process
    // singleton read/written concurrently by the download worker thread AND
    // the reader (PrivateComicLibraryApp fetchPageBytes). accessOrder=true
    // means even a plain `planCache[gid]` READ mutates the LinkedHashMap's
    // internal link order, so every access — get/put/remove, including the
    // eviction callback — must be serialized under [planCacheLock]. The
    // network fetch in [loadPlan] stays OUTSIDE the lock; only the cache
    // read and the cache write are guarded.
    private val planCacheLock = Any()
    private val planCache = object : LinkedHashMap<Long, CachedPlan>(
        PLAN_CACHE_CAPACITY,
        0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CachedPlan>): Boolean {
            val evict = size > PLAN_CACHE_CAPACITY
            if (evict) clearRotationStateFor(eldest.key)
            return evict
        }
    }

    // Task 9 defect 4 (owner bug backlog): H@H node rotation. A dead/slow
    // H@H node otherwise keeps resolving to the SAME broken node on every
    // JITTER retry — after ROTATION_FAILURE_THRESHOLD consecutive
    // image-byte failures for the SAME (gid, ordinal), the next attempt
    // re-fetches the page view via its own `?nl=<token>` reload link
    // instead of the plain cached path, which e-hentai resolves to a
    // different node. Keyed by (gid, ordinal) — SEND_BACK item 8 — so an
    // interleaved reader on a DIFFERENT gallery never wipes this gallery's
    // streak; cleared only for a gid whose OWN plan is invalidated/reloaded
    // (see [loadPlan] / [invalidatePlan]).
    private val ordinalFailureStreak = ConcurrentHashMap<Pair<Long, Int>, Int>()
    private val ordinalReloadToken = ConcurrentHashMap<Pair<Long, Int>, String>()

    // #C38/D5 (2026-09-15, C40 §C-5): H@H reload tokens already tried for this
    // (gid, ordinal). e-hentai can hand back the SAME `?nl=<token>` node it
    // just served; rotating to it again is wasted. Once a token is tried we do
    // not rotate to it a second time — the base path is used and the loop's
    // bounded in-place retries + backoff take over. Cleared with the streak.
    private val ordinalTriedReloadTokens = ConcurrentHashMap<Pair<Long, Int>, MutableSet<String>>()

    // #E19 Task 4: `?nl=` re-rolls spent by the proactive slow-node switch,
    // per gallery. A doomed page (rate below the slow floor that cannot finish
    // inside the node's 180 s session cap) switches at ~3 s instead of the
    // node's own cut, spending the SAME reload it would have spent at second
    // ~180 — quota-neutral — but a pathological gallery must not burn
    // unlimited 50-hit reloads, so the budget caps re-rolls per gallery
    // (fresh plan = fresh budget, see [clearRotationStateFor]).
    private val gallerySlowRerolls = ConcurrentHashMap<Long, Int>()

    // Owner 2026-09-09 / EhViewer research 2026-09-10: the gallery `showkey`
    // learned from the FIRST page-view HTML lets every later page resolve via
    // the api.php `showpage` JSON call — no main-site page view per image, so
    // 3 in flight (#C48: EhViewer parity, no spacing) is what the site's own
    // reader does, not a "too fast" pattern. Keyed by gid; dropped with the plan.
    private val showKeyByGid = ConcurrentHashMap<Long, String>()

    override fun supports(identity: GalleryIdentityV1): Boolean =
        identity.providerNamespace == GalleryIdentityV1.EHENTAI

    override fun fetchManifest(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): DownloadPageManifest {
        require(supports(identity))
        val plan = loadPlan(identity, locator)
        return DownloadPageManifest(
            expectedPageCount = plan.pageViewPaths.size,
            title = locator.title?.takeIf { it.isNotBlank() } ?: plan.title.takeIf { it.isNotBlank() },
            // #C65: the plan already parsed every tag/artist/group/language/
            // category off the live gallery page — the locator only has
            // whatever the enqueue tap happened to carry.
            catalogFacts = galleryPlanCatalogFacts(
                tags = plan.tags,
                artists = plan.artists,
                groups = plan.groups,
                language = plan.language,
                category = plan.category,
            ),
        )
    }

    override fun fetchPageBytes(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
    ): ByteArray {
        val resolved = resolvePageForOrdinal(identity, locator, ordinal)
        return try {
            val bytes = imageGet(resolved.image.imageUrl)
            ordinalFailureStreak.remove(resolved.key)
            ordinalTriedReloadTokens.remove(resolved.key)
            bytes
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ordinalFailureStreak[resolved.key] = resolved.streak + 1
            throw error
        }
    }

    override suspend fun fetchPageToFile(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
        destination: Path,
        offset: Long,
    ): PageTransferResult = transferWithSlowReroll(identity, locator, ordinal, destination, offset)

    /**
     * One disk-transfer attempt for [ordinal], with the #E19 Task 4 slow-node
     * switch: [EhentaiSlowNodeException] means this attempt's rate projected
     * past the H@H node's 180 s session cap, so the node would cut the socket
     * later and the same `?nl=` reload would be spent anyway. Answer it by
     * re-rolling ONCE through the existing rotation machinery — bump the
     * failure streak to [ROTATION_FAILURE_THRESHOLD] so the re-resolve takes
     * the `?nl=<token>` branch, and resume from [EhentaiSlowNodeException.bytesWritten]
     * through the provenance guard (the same `/h/<sha1>-<bytes>` file id
     * resumes; a different id truncates). A re-roll that fails the same way is
     * not answered with a second re-roll — the loop's JITTER backoff owns it
     * from there, exactly like any other transfer failure.
     */
    private suspend fun transferWithSlowReroll(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
        destination: Path,
        offset: Long,
        rerolled: Boolean = false,
    ): PageTransferResult {
        val resolved = resolvePageForOrdinal(identity, locator, ordinal)
        val transfer = imageTransfer
        if (transfer == null) {
            // Test and legacy adapters may still provide only a bytes getter.
            // Preserve the old path's fresh-write and deadline semantics.
            return try {
                val bytes = kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                    imageGet(resolved.image.imageUrl)
                }
                Files.createDirectories(destination.parent)
                Files.newOutputStream(
                    destination,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE,
                ).use { it.write(bytes) }
                ordinalFailureStreak.remove(resolved.key)
                ordinalTriedReloadTokens.remove(resolved.key)
                PageTransferResult(bytesWritten = bytes.size.toLong(), transferredBytes = bytes.size.toLong())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ordinalFailureStreak[resolved.key] = resolved.streak + 1
                throw error
            }
        }

        val resumeOffset = reconcileProvenance(resolved.image.imageUrl, destination, offset)
        val token = ordinalReloadToken[resolved.key]
        val alreadyTried = ordinalTriedReloadTokens[resolved.key]?.contains(token) == true
        val rerollsSpent = gallerySlowRerolls[resolved.key.first] ?: 0
        // The transport aborts only when this call can actually re-roll. Otherwise a
        // slow page would be cut every few seconds and the worker would burn reloads.
        val allowSlowSwitch = !rerolled &&
            !token.isNullOrBlank() &&
            !alreadyTried &&
            rerollsSpent < SLOW_REROLL_BUDGET
        return try {
            try {
                transfer.transferTo(
                    resolved.image.imageUrl,
                    destination,
                    resumeOffset,
                    allowSlowSwitch = allowSlowSwitch,
                ).also {
                    ordinalFailureStreak.remove(resolved.key)
                    ordinalTriedReloadTokens.remove(resolved.key)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ordinalFailureStreak[resolved.key] = resolved.streak + 1
                throw error
            }
        } catch (slow: EhentaiSlowNodeException) {
            if (rerolled) throw slow
            val token = ordinalReloadToken[resolved.key]
            val alreadyTried = ordinalTriedReloadTokens[resolved.key]?.contains(token) == true
            // Nothing better to rotate to: no token learned yet, or one whose
            // node e-hentai already repeated — the plain JITTER path takes over.
            if (token.isNullOrBlank() || alreadyTried) throw slow
            val used = gallerySlowRerolls.merge(resolved.key.first, 1, Int::plus) ?: 1
            if (used > SLOW_REROLL_BUDGET) throw slow
            // Force the `?nl=<token>` branch on the re-resolve below.
            ordinalFailureStreak[resolved.key] = ROTATION_FAILURE_THRESHOLD
            transferWithSlowReroll(
                identity,
                locator,
                ordinal,
                destination,
                slow.bytesWritten,
                rerolled = true,
            )
        }
    }

    /**
     * Resume-provenance guard (unchanged from the pre-#E19 transfer tail): a
     * non-zero [offset] survives only when the `.part.meta` marker matches the
     * image URL's `/h/<sha1>-<bytes>` file id; anything else truncates the
     * destination and restarts from byte 0. Persists the marker before any
     * bytes are requested, so a later failure keeps it alongside the retained
     * prefix for resume.
     */
    private fun reconcileProvenance(imageUrl: String, destination: Path, offset: Long): Long {
        val fileId = parseHathFileId(imageUrl)
        val provenance = destination.resolveSibling("${destination.fileName}.meta")
        var resumeOffset = offset
        if (resumeOffset > 0L) {
            // Files.readString/writeString need API 36.1 on Android (minSdk 29): use the byte forms.
            val previous = runCatching { String(Files.readAllBytes(provenance), StandardCharsets.UTF_8).trim() }.getOrNull()
            val current = fileId?.let { "${it.sha1}-${it.bytes}" }
            if (current == null || previous != current) {
                Files.newOutputStream(
                    destination,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                ).use { }
                runCatching { Files.deleteIfExists(provenance) }
                resumeOffset = 0L
            }
        }
        fileId?.let { id ->
            runCatching {
                Files.write(
                    provenance,
                    "${id.sha1}-${id.bytes}".toByteArray(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE,
                )
            }
        }
        return resumeOffset
    }

    private data class ResolvedPageImage(
        val image: EhentaiPageImage,
        val key: Pair<Long, Int>,
        val streak: Int,
    )

    /** Resolve the page and update H@H rotation state once for both seams. */
    private fun resolvePageForOrdinal(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
    ): ResolvedPageImage {
        require(supports(identity))
        val gid = identity.canonicalItemId.toLong()
        val plan = loadPlan(identity, locator)
        require(ordinal in plan.pageViewPaths.indices) {
            "Front ordinal $ordinal out of ${plan.pageViewPaths.size}"
        }
        val key = gid to ordinal
        val streak = ordinalFailureStreak[key] ?: 0
        val basePagePath = plan.pageViewPaths[ordinal]
        val learnedToken = ordinalReloadToken[key]
        val alreadyTried = ordinalTriedReloadTokens[key]?.contains(learnedToken) == true
        // #C38/D5: rotate only to a node we have NOT tried yet. A token we have
        // already burned (site repeated it) buys nothing, so fall back to the
        // base path in that case rather than re-hitting the same dead node.
        val rotate = streak >= ROTATION_FAILURE_THRESHOLD && !learnedToken.isNullOrBlank() && !alreadyTried
        val pagePath = if (rotate) rotatedPagePath(basePagePath, learnedToken) else basePagePath
        if (rotate) {
            ordinalTriedReloadTokens.getOrPut(key) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
                .add(learnedToken!!)
        }
        val pageImage = try {
            resolvePageImage(gid = gid, pagePath = pagePath, ordinal = ordinal, rotating = rotate)
        } catch (error: EhentaiException) {
            throw ehentaiErrorToTransport(error)
        }
        pageImage.reloadToken?.let { ordinalReloadToken[key] = it }
        return ResolvedPageImage(image = pageImage, key = key, streak = streak)
    }

    override fun invalidatePlan(identity: GalleryIdentityV1) {
        if (!supports(identity)) return
        val gid = identity.canonicalItemId.toLongOrNull() ?: return
        synchronized(planCacheLock) { planCache.remove(gid) }
        clearRotationStateFor(gid)
        showKeyByGid.remove(gid)
    }

    /**
     * API-first page resolution: with a cached showkey (and not in H@H node
     * rotation, which needs the HTML `?nl=` form) ask `showpage`; on any API
     * failure drop the showkey and fall back to the HTML page view, which
     * relearns it. The first page of a gallery always takes the HTML path.
     */
    private fun resolvePageImage(
        gid: Long,
        pagePath: String,
        ordinal: Int,
        rotating: Boolean,
    ): EhentaiPageImage {
        val showKey = showKeyByGid[gid]
        val imgKey = pageImgKey(pagePath)
        if (!rotating && showKey != null && imgKey != null) {
            try {
                return client().fetchPageImageViaApi(
                    galleryId = gid,
                    pageNumber = ordinal + 1,
                    imgKey = imgKey,
                    showKey = showKey,
                )
            } catch (error: EhentaiException.Parse) {
                showKeyByGid.remove(gid)
            }
        }
        val fromHtml = client().fetchPageImage(pageViewPath = pagePath, pageNumber = ordinal + 1)
        fromHtml.showKey?.let { showKeyByGid[gid] = it }
        return fromHtml
    }

    /** `/s/<imgkey>/<gid>-<n>` → imgkey. */
    private fun pageImgKey(pagePath: String): String? =
        PAGE_IMG_KEY_REGEX.find(pagePath)?.groupValues?.getOrNull(1)

    /**
     * Appends the H@H reload token as `?nl=<token>` (or `&nl=` when the base
     * path already carries a query string) so the next page-view fetch is
     * resolved against a different node. Falls back to the unmodified base
     * path when no token has been learned yet for this ordinal — the plain
     * retry still gets the ordinary JITTER backoff from [DownloadWorkerLoop].
     */
    private fun rotatedPagePath(basePath: String, reloadToken: String?): String {
        if (reloadToken.isNullOrBlank()) return basePath
        val separator = if (basePath.contains("?")) "&" else "?"
        return "$basePath${separator}nl=$reloadToken"
    }

    private fun loadPlan(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): EhentaiGalleryPlan {
        val (gid, token) = requireNotNull(
            BrowseFeedRepository.parseEhentaiAcquisition(locator.acquisitionUrl),
        ) { "Front locator must be a ehentai://gid/token acquisition" }
        require(gid.toString() == identity.canonicalItemId) {
            "Front locator gid $gid does not match identity ${identity.canonicalItemId}"
        }
        val hit = synchronized(planCacheLock) { planCache[gid] }
        if (hit != null && hit.token == token) {
            return hit.plan
        }
        val plan = try {
            client().fetchGallery(galleryId = gid, token = token)
        } catch (error: EhentaiException) {
            throw ehentaiErrorToTransport(error)
        }
        synchronized(planCacheLock) { planCache[gid] = CachedPlan(token = token, plan = plan) }
        // A freshly loaded (or token-changed) plan for THIS gid means any
        // previously tracked ordinals for THIS gallery belong to the old
        // plan — clear only this gallery's rotation state, never a
        // different gallery's (SEND_BACK item 8).
        clearRotationStateFor(gid)
        return plan
    }

    private fun clearRotationStateFor(gid: Long) {
        ordinalFailureStreak.keys.removeAll { it.first == gid }
        ordinalReloadToken.keys.removeAll { it.first == gid }
        ordinalTriedReloadTokens.keys.removeAll { it.first == gid }
        // #E19 Task 4: a fresh plan is a fresh slow-node re-roll budget.
        gallerySlowRerolls.remove(gid)
    }

    private data class CachedPlan(
        val token: String,
        val plan: EhentaiGalleryPlan,
    )

    private companion object {
        private val PAGE_IMG_KEY_REGEX = Regex("""/s/([0-9a-zA-Z]+)/\d+-\d+""")

        /** Consecutive same-(gid, ordinal) image-byte failures before trying a different H@H node. */
        const val ROTATION_FAILURE_THRESHOLD = 1

        /**
         * #E19 Task 4: max `?nl=` re-rolls the slow-node switch may spend per
         * gallery (20 x 50 = 1,000 hits vs regen 3-5/min) — a safety cap
         * against pathological galleries, not the expected operating point.
         */
        const val SLOW_REROLL_BUDGET = 20

        /** Recently-active galleries whose plans stay warm without evicting each other. */
        const val PLAN_CACHE_CAPACITY = 4
    }
}
