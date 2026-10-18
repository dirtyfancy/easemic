package com.easemic.app.feature.download

import com.easemic.app.importing.nhentai.NhentaiClient
import com.easemic.app.importing.nhentai.NhentaiException
import com.easemic.app.importing.nhentai.NhentaiGalleryPlan
import com.easemic.app.importing.nhentai.NhentaiSlowNodeException
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Index (nhentai) page-bytes adapter. Identity canonical id is the gallery id;
 * locator acquisition is optional metadata.
 *
 * This adapter is a process-wide singleton (wired once in AppGraph) shared by
 * every concurrently-downloading gallery. SEND_BACK M2 (2026-09-05 review of
 * S6-A Task 3): the OLD single-slot `@Volatile private var cached` meant a
 * page fetch on gallery B evicted gallery A's cached plan, so with 2+
 * concurrent nh galleries every interleaved page fetch re-issued a gated
 * `fetchGallery` API GET instead of a cheap in-memory hit. A small LRU (same
 * capacity/shape as FrontDownloadPageAdapter's) keeps a handful of
 * recently-active galleries' plans warm without evicting each other.
 */
class IndexDownloadPageAdapter(
    private val client: () -> NhentaiClient,
    private val imageGet: (imageUrl: String) -> ByteArray,
    private val imageTransfer: PageImageGet? = imageGet as? PageImageGet,
) : DownloadPageBytesAdapter {
    override fun usesDiskBackedTransfer(identity: GalleryIdentityV1, locator: DownloadLocator): Boolean =
        supports(identity) && imageTransfer != null

    // accessOrder=true means even a plain `planCache[id]` READ mutates the
    // LinkedHashMap's internal link order, so every access — get/put/remove
    // — must be serialized under [planCacheLock] (mirrors
    // FrontDownloadPageAdapter's B1 fix). The network fetch in [loadPlan]
    // stays OUTSIDE the lock; only the cache read and the cache write are
    // guarded.
    private val planCacheLock = Any()
    private val planCache = object : LinkedHashMap<Long, CachedPlan>(
        PLAN_CACHE_CAPACITY,
        0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CachedPlan>): Boolean =
            size > PLAN_CACHE_CAPACITY
    }

    // #E19 Task 5: the sticky i1–i4 host index per gallery id (0 = the host
    // the plan already gives). Advanced only by a switch — see
    // [transferWithHostFailover]; this adapter is a process-wide singleton, so
    // the map is concurrent and keyed by gallery, never by ordinal.
    private val hostIndexByGallery = ConcurrentHashMap<Long, Int>()

    override fun supports(identity: GalleryIdentityV1): Boolean =
        identity.providerNamespace == GalleryIdentityV1.NHENTAI

    override fun fetchManifest(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): DownloadPageManifest {
        require(supports(identity))
        val plan = loadPlan(identity)
        val count = plan.pageCount?.takeIf { it > 0 } ?: plan.imageUrls.size
        return DownloadPageManifest(
            expectedPageCount = count,
            title = locator.title?.takeIf { it.isNotBlank() } ?: plan.title.takeIf { it.isNotBlank() },
            // The parser retains every namespaced tag; creators stay in dedicated fields.
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
        require(supports(identity))
        val plan = loadPlan(identity)
        require(ordinal in plan.imageUrls.indices) {
            "Index ordinal $ordinal out of ${plan.imageUrls.size}"
        }
        return imageGet(plan.imageUrls[ordinal])
    }

    override suspend fun fetchPageToFile(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
        destination: Path,
        offset: Long,
    ): PageTransferResult {
        require(supports(identity))
        val plan = loadPlan(identity)
        require(ordinal in plan.imageUrls.indices) {
            "Index ordinal $ordinal out of ${plan.imageUrls.size}"
        }
        val transfer = imageTransfer
        if (transfer == null) {
            return super.fetchPageToFile(identity, locator, ordinal, destination, offset)
        }
        return transferWithHostFailover(transfer, identity, plan, ordinal, destination, offset)
    }

    /**
     * #E19 Task 5: nhentai's image hosts i1–i4 are same-path IPv4-only
     * mirrors with no quota, so the host list IS the failover. Index 0 is the
     * URL the gallery plan already gives (`i.nhentai.net`) — never rewritten;
     * indices 1–4 rewrite that URL's host, wrapping after i4. The index is
     * STICKY per gallery and advanced only by a switch, so once a slow host
     * has been escaped every later page of the gallery starts on the good
     * one; [invalidatePlan] forgets it with the plan.
     *
     * A switch is offered to the transport (allowSlowSwitch) only on the FIRST
     * attempt of a call — a slow node aborts at ~3 s and the retried attempt
     * on the next host runs until it finishes, fails, or the watchdog cuts it,
     * so one call can never cycle hosts every few seconds. Hosts switch on a
     * slow node (resuming from the bytes on disk), an HTTP 5xx, and the
     * first-byte timeout; never on a 404, any other 4xx, cancellation, or a
     * stall after the first byte (the worker's retry owns that one). A host
     * answering 200 to the resumed `Range` request truncates and restarts,
     * exactly as before #E19.
     */
    private suspend fun transferWithHostFailover(
        transfer: PageImageGet,
        identity: GalleryIdentityV1,
        plan: NhentaiGalleryPlan,
        ordinal: Int,
        destination: Path,
        offset: Long,
    ): PageTransferResult {
        val galleryId = identity.canonicalItemId.toLong()
        val baseUrl = plan.imageUrls[ordinal]
        var offsetUsed = offset
        repeat(TRANSFER_ATTEMPTS) { attempt ->
            val index = hostIndexByGallery[galleryId] ?: DEFAULT_HOST_INDEX
            val target = if (index == DEFAULT_HOST_INDEX) {
                baseUrl
            } else {
                baseUrl.replaceFirst(IMAGE_HOST, "https://i$index.nhentai.net")
            }
            try {
                return transfer.transferTo(
                    target,
                    destination,
                    offsetUsed,
                    allowSlowSwitch = attempt == FIRST_ATTEMPT,
                )
            } catch (slow: NhentaiSlowNodeException) {
                if (attempt != FIRST_ATTEMPT) throw slow
                hostIndexByGallery[galleryId] = index % IMAGE_HOST_COUNT + 1
                offsetUsed = slow.bytesWritten
            } catch (transport: FetchTransportException) {
                if (attempt != FIRST_ATTEMPT || transport.statusCode !in 500..599) throw transport
                hostIndexByGallery[galleryId] = index % IMAGE_HOST_COUNT + 1
            } catch (stall: IOException) {
                val firstByteTimeout = stall.message?.startsWith(NhentaiClient.FIRST_BYTE_STALL_PREFIX) == true
                if (attempt != FIRST_ATTEMPT || !firstByteTimeout) throw stall
                hostIndexByGallery[galleryId] = index % IMAGE_HOST_COUNT + 1
            }
        }
        error("unreachable")
    }

    override fun invalidatePlan(identity: GalleryIdentityV1) {
        if (!supports(identity)) return
        val galleryId = identity.canonicalItemId.toLongOrNull() ?: return
        synchronized(planCacheLock) { planCache.remove(galleryId) }
        // A fresh plan forgets the switched host: the plan's own URLs name the
        // default host again, and a host chosen for the old plan's pages has
        // no reason to survive a re-fetch.
        hostIndexByGallery.remove(galleryId)
    }

    private fun loadPlan(identity: GalleryIdentityV1): NhentaiGalleryPlan {
        val galleryId = identity.canonicalItemId.toLong()
        val hit = synchronized(planCacheLock) { planCache[galleryId] }
        if (hit != null) {
            return hit.plan
        }
        val plan = try {
            client().fetchGallery(galleryId)
        } catch (error: NhentaiException) {
            throw nhentaiErrorToTransport(error)
        }
        synchronized(planCacheLock) { planCache[galleryId] = CachedPlan(galleryId = galleryId, plan = plan) }
        return plan
    }

    private data class CachedPlan(
        val galleryId: Long,
        val plan: NhentaiGalleryPlan,
    )

    private companion object {
        /** Recently-active galleries whose plans stay warm without evicting each other. */
        const val PLAN_CACHE_CAPACITY = 4

        /**
         * #E19 Task 5: one switch per [fetchPageToFile] call — first attempt
         * on the sticky host (the transport may abort it as slow), the
         * retried attempt on the next host runs to a terminal state.
         */
        const val TRANSFER_ATTEMPTS = 2
        const val FIRST_ATTEMPT = 0

        /** Host index 0 is the URL the gallery plan already gives; 1–4 are i1–i4. */
        const val DEFAULT_HOST_INDEX = 0
        const val IMAGE_HOST_COUNT = 4

        /** Matches the plan's image-url host so i1–i4 can be swapped in (`i` never matches `i5+`). */
        val IMAGE_HOST = Regex("""^https://i[1-4]?\.nhentai\.net""")
    }
}
