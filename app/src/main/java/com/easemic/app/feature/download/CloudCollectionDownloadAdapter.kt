package com.easemic.app.feature.download

import com.easemic.app.sync.CloudCollectionStore
import com.easemic.app.sync.ObjectStore
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shown when no bucket resolves at all — the only honest reason to refuse a cloud download. */
const val CLOUD_DOWNLOAD_NOT_CONNECTED: String = "Cloud storage is not connected"

/**
 * A43 (owner bug: cloud→device download always failed on an OAuth-only
 * binding). [resolveStore] must be the graph's shared gated resolver
 * (`AppGraph.resolveGatedObjectStore`), which walks S3 working binding ->
 * cached OAuth REST store -> fresh OAuth bootstrap and applies the encryption
 * gate itself. Resolving a static S3 working binding alone made every
 * OAuth-bound device throw [CLOUD_DOWNLOAD_NOT_CONNECTED] even though the
 * cloud shelf listed and read fine everywhere else.
 */
suspend fun resolveCloudCollectionStore(
    resolveStore: suspend () -> ObjectStore?,
): CloudCollectionStore {
    val store = resolveStore() ?: error(CLOUD_DOWNLOAD_NOT_CONNECTED)
    return CloudCollectionStore(store)
}

/**
 * Owner bug C10 (a cloud download crawled next to reading the same comic
 * online, sometimes stalling to 0). [CloudCollectionDownloadAdapter] calls its
 * `store` factory once per page, and each [resolveCloudCollectionStore] built a
 * brand-new [CloudCollectionStore] — whose `pages.manifest.json` memo is a
 * per-INSTANCE field that `downloadPage` reads first to resolve the
 * content-hash page key. So every page paid GET manifest + GET image (2N
 * requests for N pages) and re-walked the gated resolver, where the reader's
 * one long-lived store pays the manifest once (N+1).
 *
 * Memoised on the CURRENT BINDING, never unconditionally: a plain `by lazy`
 * would pin the old bucket across a rebind and read the wrong bucket, which is
 * worse than the slowness. [fingerprint] is the graph's existing
 * `currentBucketFingerprintOrNull` — the same notion of "which bucket am I on"
 * used by the hydration mirror, and it resolves S3-binding-first exactly like
 * [com.easemic.app.AppGraph.resolveGatedObjectStore]. A
 * changed-or-null fingerprint (rebind, sign-out) drops the memo and re-resolves.
 *
 * Safe to keep across token and lock changes: [CloudLibraryGate]'s wrapper
 * resolves lock state per op, so a later lock/unlock is still honoured
 * through a memoised store, and `R2RestObjectStore` refreshes its own access
 * token on 401. #C64: a bind change (even to the SAME bucket) kills every gate
 * wrapper, so the production key is [bindingMemoKey] — fingerprint AND gate
 * generation — never the fingerprint alone.
 */
class MemoizedCloudCollectionStore(
    private val fingerprint: () -> String?,
    private val resolveStore: suspend () -> ObjectStore?,
) {
    private class Memo(val fingerprint: String, val store: CloudCollectionStore)

    @Volatile private var memo: Memo? = null

    /** Serialises resolution so 6 concurrent cloud page fetches build one store. */
    private val mutex = Mutex()

    suspend fun get(): CloudCollectionStore {
        readUsable(fingerprint())?.let { return it }
        return mutex.withLock {
            val current = fingerprint()
            readUsable(current)?.let { return@withLock it }
            // Outside the memo write below: a failed resolve (not connected,
            // locked gate, network) must never be cached, so the next page
            // retries cleanly.
            val resolved = resolveCloudCollectionStore(resolveStore)
            if (current != null) memo = Memo(current, resolved)
            resolved
        }
    }

    /** Returns the memo only while it still matches [current]; drops it otherwise. */
    private fun readUsable(current: String?): CloudCollectionStore? {
        val cached = memo ?: return null
        if (current == null || cached.fingerprint != current) {
            memo = null
            return null
        }
        return cached.store
    }
}

/**
 * #C64: the memo key for [MemoizedCloudCollectionStore] — the bound bucket AND
 * the gate generation, so a same-bucket rebind or Disconnect/rebind drops a
 * memo whose gated store is now dead instead of failing every page after it.
 */
fun bindingMemoKey(fingerprint: String?, generation: Long): String? =
    fingerprint?.let { "$it#g$generation" }

/**
 * [MemoizedCloudCollectionStore] as the `store` factory
 * [CloudCollectionDownloadAdapter] takes.
 *
 * runBlocking is safe here: DownloadWorkerLoop invokes the adapter from inside
 * runInterruptible(Dispatchers.IO), the same background-IO precedent as its own
 * page-fetch gate.
 */
fun memoizedCloudCollectionStore(
    fingerprint: () -> String?,
    resolveStore: suspend () -> ObjectStore?,
): () -> CloudCollectionStore {
    val memo = MemoizedCloudCollectionStore(fingerprint, resolveStore)
    return { runBlocking { memo.get() } }
}

/**
 * Fetches Cloud Collection pages from the user's R2 bucket.
 * Must not call Front/Index/OPDS clients.
 */
class CloudCollectionDownloadAdapter(
    private val store: () -> CloudCollectionStore,
) : DownloadPageBytesAdapter {
    override fun supports(identity: GalleryIdentityV1): Boolean =
        identity.providerNamespace == GalleryIdentityV1.CLOUD_COLLECTION

    override fun fetchManifest(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): DownloadPageManifest {
        val id = requireCloudComicId(identity, locator)
        val collection = store()
        val manifest = collection.downloadPagesManifest(id)
        val title = locator.title?.takeIf { it.isNotBlank() }
            ?: runCatching { collection.downloadMeta(id).title }.getOrNull()
        return DownloadPageManifest(
            expectedPageCount = manifest.pageCount,
            title = title,
        )
    }

    override fun fetchPageBytes(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
    ): ByteArray {
        val id = requireCloudComicId(identity, locator)
        return store().downloadPage(id, ordinal)
    }

    override suspend fun fetchPageToFile(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
        destination: Path,
        offset: Long,
    ): PageTransferResult = withContext(Dispatchers.IO) {
        val id = requireCloudComicId(identity, locator)
        Files.createDirectories(destination.parent)
        // #E33: big pages stream into the part file (#E29's route); the worker's page deadline still bounds this call.
        // downloadPageToFile rewrites the file from its start, so a non-zero offset restarts the page.
        runInterruptible(Dispatchers.IO) { store().downloadPageToFile(id, ordinal, destination.toFile()) }
        PageTransferResult(bytesWritten = Files.size(destination))
    }

    private fun requireCloudComicId(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): String {
        locator.cloudComicId?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (identity.providerNamespace == GalleryIdentityV1.CLOUD_COLLECTION) {
            val id = identity.canonicalItemId.trim()
            require(id.isNotEmpty()) { "cloud collection identity missing id" }
            return id
        }
        error("Cloud download locator missing cloudComicId")
    }
}
