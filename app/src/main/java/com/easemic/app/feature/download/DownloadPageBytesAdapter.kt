package com.easemic.app.feature.download

import com.easemic.app.feature.source.ComicSourceRegistry
import com.easemic.app.sync.redactNamespace
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Acquisition / token locator for page fetch (spec §4.1). Never part of
 * [GalleryIdentityV1] — Front token rotation updates the locator only.
 */
data class DownloadLocator(
    val acquisitionUrl: String,
    val title: String? = null,
    /** When set, worker fetches pages from the user's R2 bucket, not upstream. */
    val cloudComicId: String? = null,
    val coverUrl: String? = null,
    /** Raw `namespace:label` catalog tags (A32 §2); empty for a legacy locator. */
    val tags: List<String> = emptyList(),
    val language: String? = null,
    val category: String? = null,
    /** #C12: the OPDS feed this acquisition was listed in, for the existence check; null for every other source. */
    val feedUrl: String? = null,
    /** Browse source that supplied the row; distinct from canonical download identity. */
    val browseSourceId: String? = null,
) {
    fun routesToCloudCollection(): Boolean = !cloudComicId.isNullOrBlank()
}

/** Manifest snapshot used to pin [DownloadJob.expectedPageCount]. */
data class DownloadPageManifest(
    val expectedPageCount: Int,
    val title: String? = null,
    /**
     * #C65: the gallery plan's own tags/artists/groups/language/category, when
     * the adapter fetched a full plan that carries them (E-Hentai, nhentai) —
     * null for every other adapter (cloud collection, OPDS, r2source), which
     * has no catalog facts of its own to offer.
     */
    val catalogFacts: DownloadCommitMetadata? = null,
)

/** Metadata returned by the disk-backed page transfer seam. */
data class PageTransferResult(
    val bytesWritten: Long,
    val expectedBytes: Long? = null,
    val expectedSha1: String? = null,
    val resolvedUrl: String? = null,
    val transferredBytes: Long = bytesWritten,
)

/** A source-owned transfer failure whose partial file must not be resumed. */
class InvalidPageTransferException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A disconnect, timeout, or watchdog cut leaves the downloaded prefix resumable. */
class ResumablePageTransferException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * #C65: the one place a gallery plan's separate tags/artists/groups become the
 * raw `namespace:label` list [DownloadCommitMetadata] expects — mirrors
 * [com.easemic.app.feature.browse.locatorCatalogTags], which
 * does the same re-attachment for a `BrowseFeedItem`. Both e-h and nh parsers
 * route creators OUT of their tag lists into dedicated artists/groups fields;
 * re-namespacing here is what lets [DownloadCommitMetadata.creators] lift them
 * back out. Already-namespaced values are left alone rather than namespaced
 * twice. Empty everywhere yields `null` — a plan with no catalog facts at all
 * offers nothing to fill.
 */
internal fun galleryPlanCatalogFacts(
    tags: List<String>,
    artists: List<String>,
    groups: List<String>,
    language: String?,
    category: String?,
): DownloadCommitMetadata? {
    val combined = artists.namespacedAs("artist") + groups.namespacedAs("group") +
        tags.map { it.trim() }.filter { it.isNotEmpty() }
    val cleanLanguage = language?.trim()?.takeIf { it.isNotEmpty() }
    val cleanCategory = category?.trim()?.takeIf { it.isNotEmpty() }
    if (combined.isEmpty() && cleanLanguage == null && cleanCategory == null) return null
    return DownloadCommitMetadata(tags = combined, language = cleanLanguage, category = cleanCategory)
}

/** Already-namespaced values are left alone rather than namespaced twice. */
private fun List<String>.namespacedAs(namespace: String): List<String> = mapNotNull { raw ->
    val value = raw.trim()
    when {
        value.isEmpty() -> null
        value.substringBefore(':', "").trim().lowercase() == namespace ->
            value.substringAfter(':').trim().takeIf { it.isNotEmpty() }?.let { "$namespace:$it" }
        else -> "$namespace:$value"
    }
}

/** Worker-facing disk transfer contract. It intentionally has no ByteArray page API. */
interface DownloadPageTransferAdapter {
    /** Online file transfer is independent of the worker's deadline/streaming capability. */
    fun prefersFileBackedOnlineRead(identity: GalleryIdentityV1, locator: DownloadLocator): Boolean = false

    /** Source transport owns wire spacing; the worker still limits concurrent operations. */
    fun ownsRequestPacing(identity: GalleryIdentityV1, locator: DownloadLocator): Boolean = false

    /** True when the adapter owns a streaming response and its dynamic budget. */
    fun usesDiskBackedTransfer(identity: GalleryIdentityV1, locator: DownloadLocator): Boolean = false

    /** True when manifest acquisition also owns a progress-aware body deadline. */
    fun usesProgressAwareManifest(identity: GalleryIdentityV1, locator: DownloadLocator): Boolean = false

    fun supports(identity: GalleryIdentityV1): Boolean

    fun fetchManifest(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): DownloadPageManifest

    /**
     * Manifest entry whose body can outlive the flat cap. The default keeps
     * the blocking fetch. A source that owns the socket overrides this so
     * cancellation closes that socket.
     */
    suspend fun fetchManifestForWorker(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): DownloadPageManifest = runInterruptible(Dispatchers.IO) {
        fetchManifest(identity, locator)
    }

    suspend fun fetchPageToFile(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
        destination: Path,
        offset: Long,
    ): PageTransferResult

    fun invalidatePlan(identity: GalleryIdentityV1) {}
}

/**
 * Reader/test/cloud compatibility adapter. The worker is typed to
 * [DownloadPageTransferAdapter]; this deprecated ByteArray seam remains only
 * for callers that still materialize a page directly.
 */
interface DownloadPageBytesAdapter : DownloadPageTransferAdapter {
    @Deprecated("Worker downloads use fetchPageToFile; retain only for reader compatibility")
    fun fetchPageBytes(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
    ): ByteArray

    /**
     * Streams one page into the supplied unpublished `.part` path.  The
     * default is deliberately additive: old adapters still work while the
     * worker moves to the disk-backed path.  A non-zero [offset] is a hint to
     * resume; a legacy bytes-only adapter cannot do that and therefore
     * truncates the destination before writing a fresh response.
     */
    override suspend fun fetchPageToFile(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
        destination: Path,
        offset: Long,
    ): PageTransferResult = withContext(Dispatchers.IO) {
        Files.createDirectories(destination.parent)
        val bytes = runInterruptible(Dispatchers.IO) { fetchPageBytes(identity, locator, ordinal) }
        Files.newOutputStream(
            destination,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
            java.nio.file.StandardOpenOption.WRITE,
        ).use { it.write(bytes) }
        PageTransferResult(bytesWritten = bytes.size.toLong())
    }

    /**
     * Drops any cached manifest/plan for [identity] so the NEXT
     * [fetchManifest]/[fetchPageBytes] call re-fetches upstream instead of
     * returning a stale in-memory copy (S3 Task 9 SEND_BACK item 1, owner
     * bug backlog): a `PAGE_COUNT_MISMATCH_PENDING` re-check is worthless if
     * the adapter just hands back the SAME cached count it already
     * disagreed with. Default no-op — only adapters that cache a plan
     * (Front/Index) need to override this.
     */
}

/**
 * Routes each identity to its registered source's pages (#E2 Slice 2: by
 * namespace, and only when they support it; an `opds:*` identity whose provider
 * was removed falls back to [opdsFallback]), or Cloud Collection when
 * [DownloadLocator.routesToCloudCollection] or the identity is
 * [GalleryIdentityV1.CLOUD_COLLECTION]; or, ahead of both, the slice-C2
 * old-bucket migration adapter ([cloudSource]) when the identity is
 * `r2source:*`.
 *
 * [cloudSource] MUST be checked before [cloud]: an r2source job's
 * [DownloadLocator.cloudComicId] is the OLD bucket's id (so
 * [DownloadLocator.routesToCloudCollection] is also true for it), and handing
 * that id to [cloud] would fetch from the CURRENT bucket under a foreign id —
 * silently corrupting or 404ing the migration instead of reading the
 * takedown-proof old-bucket copy.
 */
class RegistryDownloadPageAdapter(
    private val registry: ComicSourceRegistry,
    private val cloud: DownloadPageBytesAdapter? = null,
    private val cloudSource: DownloadPageBytesAdapter? = null,
    private val opdsFallback: DownloadPageBytesAdapter,
) : DownloadPageBytesAdapter {
    override fun prefersFileBackedOnlineRead(identity: GalleryIdentityV1, locator: DownloadLocator): Boolean =
        requireAdapter(identity, locator).prefersFileBackedOnlineRead(identity, locator)

    override fun ownsRequestPacing(identity: GalleryIdentityV1, locator: DownloadLocator): Boolean =
        requireAdapter(identity, locator).ownsRequestPacing(identity, locator)

    override fun usesDiskBackedTransfer(identity: GalleryIdentityV1, locator: DownloadLocator): Boolean =
        requireAdapter(identity, locator).usesDiskBackedTransfer(identity, locator)

    override fun usesProgressAwareManifest(identity: GalleryIdentityV1, locator: DownloadLocator): Boolean =
        requireAdapter(identity, locator).usesProgressAwareManifest(identity, locator)

    override fun supports(identity: GalleryIdentityV1): Boolean =
        cloudSource?.supports(identity) == true ||
            cloud?.supports(identity) == true ||
            sourcePages(identity) != null

    override fun fetchManifest(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): DownloadPageManifest =
        requireAdapter(identity, locator).fetchManifest(identity, locator)

    override suspend fun fetchManifestForWorker(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): DownloadPageManifest =
        requireAdapter(identity, locator).fetchManifestForWorker(identity, locator)

    override fun fetchPageBytes(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
    ): ByteArray =
        requireAdapter(identity, locator).fetchPageBytes(identity, locator, ordinal)

    override suspend fun fetchPageToFile(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
        ordinal: Int,
        destination: Path,
        offset: Long,
    ): PageTransferResult =
        requireAdapter(identity, locator).fetchPageToFile(identity, locator, ordinal, destination, offset)

    // As the composite did: invalidation goes to the identity's source slot only, never to cloudSource. A
    // cloud_collection identity reaches the cloud collection's pages here, whose invalidatePlan is the
    // interface's no-op (it keeps no plan cache).
    override fun invalidatePlan(identity: GalleryIdentityV1) {
        sourcePages(identity)?.invalidatePlan(identity)
    }

    private fun requireAdapter(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): DownloadPageBytesAdapter =
        resolve(identity, locator)
            ?: error("No download page adapter for namespace ${redactNamespace(identity.providerNamespace)}")

    private fun resolve(
        identity: GalleryIdentityV1,
        locator: DownloadLocator,
    ): DownloadPageBytesAdapter? {
        if (cloudSource != null && cloudSource.supports(identity)) {
            return cloudSource
        }
        if (cloud != null &&
            (locator.routesToCloudCollection() || cloud.supports(identity))
        ) {
            return cloud
        }
        return sourcePages(identity)
    }

    private fun sourcePages(identity: GalleryIdentityV1): DownloadPageBytesAdapter? {
        // Each slot routes only identities its adapter accepts, as the composite's supports() checks did:
        // archives, cloud manifests and old job rows can carry any namespace.
        registry.source(identity.providerNamespace)?.pages?.takeIf { it.supports(identity) }?.let { return it }
        // A deleted OPDS provider still has a valid persisted identity. The old
        // composite adapter accepted every opds:* namespace, so preserve that
        // compatibility path after the registry lookup misses.
        if (identity.providerNamespace.startsWith(GalleryIdentityV1.OPDS_PREFIX)) return opdsFallback
        return null
    }
}
