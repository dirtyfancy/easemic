package com.easemic.app.feature.download

import com.easemic.app.model.LibraryItem

/**
 * Library local-availability association for a finished download (spec §4.6).
 *
 * Created only on successful FINISH: attaches [downloadLocationUri] +
 * [downloadRevision]. Distinct from Collect/R2 and from Import/SAF ownership.
 */
data class DownloadLibraryAssociation(
    val identity: GalleryIdentityV1,
    val downloadLocationUri: String,
    val downloadRevision: Int,
    val locallyAvailable: Boolean = true,
    /**
     * Stable ownership key for this download association. Never equals an
     * Import/SAF ownership key even when file paths overlap.
     */
    val ownershipKey: String = downloadOwnershipKey(identity),
)

fun downloadOwnershipKey(identity: GalleryIdentityV1): String =
    "download:v${GalleryIdentityV1.IDENTITY_VERSION}:" +
        "${identity.providerNamespace}:${identity.canonicalItemId}"

/** Canonical dedup key for a gallery. Default scheme; real-data alignment is a later task. */
fun GalleryIdentityV1.acquisitionKey(): String = "${providerNamespace}://${canonicalItemId}"

fun parseDownloadOwnershipKey(ownership: String): GalleryIdentityV1? {
    val prefix = "download:v${GalleryIdentityV1.IDENTITY_VERSION}:"
    if (!ownership.startsWith(prefix)) return null
    val rest = ownership.removePrefix(prefix)
    val known = listOf(
        GalleryIdentityV1.EHENTAI,
        GalleryIdentityV1.NHENTAI,
        GalleryIdentityV1.CLOUD_COLLECTION,
    )
    for (namespace in known) {
        val head = "$namespace:"
        if (rest.startsWith(head)) {
            val id = rest.removePrefix(head)
            if (id.isNotEmpty()) return GalleryIdentityV1(namespace, id)
        }
    }
    if (rest.startsWith("opds:")) {
        val after = rest.removePrefix("opds:")
        // A32 S2 (J6): split on the FIRST ':' after the config id, not the last.
        // The config id is a generated UUID and therefore never contains ':'
        // (`OpdsCatalogConnect`'s idFactory), while an OPDS *entry* id routinely
        // does -- `urn:uuid:<...>` and bare `http://host/path` are both normal
        // Komga/Kavita ids. Splitting on the last ':' shredded exactly those
        // into a wrong identity, which silently made the row's catalog files
        // unreachable to every identity-keyed operation (including A32's new
        // ... Delete download, which then quietly deleted nothing).
        val split = after.indexOf(':')
        if (split <= 0 || split == after.lastIndex) return null
        val configId = after.substring(0, split)
        val itemId = after.substring(split + 1)
        if (configId.isEmpty() || itemId.isEmpty()) return null
        return GalleryIdentityV1(GalleryIdentityV1.opdsNamespace(configId), itemId)
    }
    return null
}

/**
 * #E2 4b Task A3: the series identity behind a serialized source's download claim (`download:v1:<ns>:<seriesId>`).
 * Kept apart from [parseDownloadOwnershipKey] on purpose: that one answers "is this a gallery download", and its
 * consumers (device archive export, gallery delete) must keep skipping a serialized series.
 */
fun parseSerializedOwnershipKey(ownership: String): GalleryIdentityV1? {
    if (parseDownloadOwnershipKey(ownership) != null) return null
    val prefix = "download:v${GalleryIdentityV1.IDENTITY_VERSION}:"
    if (!ownership.startsWith(prefix)) return null
    val rest = ownership.removePrefix(prefix)
    if (rest.startsWith("opds:")) return null
    val split = rest.indexOf(':')
    if (split <= 0 || split == rest.lastIndex) return null
    return GalleryIdentityV1(rest.substring(0, split), rest.substring(split + 1))
}

fun isCatalogDownloadUri(uri: String?): Boolean {
    if (uri.isNullOrBlank()) return false
    return uri.contains("ehentai-downloads") ||
        uri.contains("nhentai-downloads") ||
        // Legacy censored-build trees keep matching so content downloaded
        // before the rename still counts as catalog-downloaded.
        uri.contains("front-catalog-downloads") ||
        uri.contains("index-catalog-downloads") ||
        uri.contains("opds-downloads") ||
        uri.contains("cloud-collection-downloads") ||
        uri.contains("serialized-downloads")
}

/**
 * True when this row's pages live in a catalog-download tree the app fetched
 * itself, rather than in a folder the user granted (A36: the one fact that
 * separates "rescan its folder" from "the source says the rest is gone").
 */
fun LibraryItem.isCatalogDownloadRow(): Boolean =
    pages.any { isCatalogDownloadUri(it.documentUri) }

/** Catalog-download rows leave On device / Downloads when local files are gone. Import grant-loss stays listed. */
fun LibraryItem.appearsOnLibraryShelf(): Boolean = !isCatalogDownloadRow() || isAvailable

/**
 * Default download location URI for a job revision (catalog-download path shape
 * so Library Downloads segment can recognize finished local availability).
 */
fun defaultDownloadLocationUri(
    identity: GalleryIdentityV1,
    downloadRevision: Int,
    filesDir: java.io.File = java.io.File(System.getProperty("java.io.tmpdir"), "pcl-app-files"),
): String {
    val rootSegment = catalogDownloadRootSegment(identity)
    // #C20 audit HIGH-1: the OPDS tree used to be namespace-blind — two
    // sources whose entry ids collide (Kavita chapter ids are small integers)
    // shared ONE directory, so a second download overwrote the first's pages
    // and a Collect uploaded a mix of both. Every non-official namespace now
    // owns a directory level of its own; the three official trees are
    // unchanged so nothing already on disk moves.
    val namespaceSegment = catalogDownloadNamespaceSegment(identity)
    // #C18: bounded because an OPDS id is a whole acquisition URL and
    // percent-encoded CJK exceeds the 255-BYTE name limit. Short ids pass
    // through verbatim, so existing shelf trees keep their location — and
    // deleteCatalogDownloadRevision prunes via this same function, so write and
    // delete cannot disagree on the name.
    val item = BoundedPathSegment.of(
        identity.canonicalItemId.replace(Regex("[\\\\/\\u0000]"), "_"),
    )
    val relative = listOfNotNull(rootSegment, namespaceSegment, item, "r$downloadRevision").joinToString("/")
    val dest = java.io.File(filesDir, relative)
    // #C18-F13: encode. An OPDS id can carry a space (a decoded `%20` from the
    // chapter filename Kavita appends) or CJK; string-building `file://` + the
    // raw path made `URI()` throw inside tryFinish, so FINISH never committed
    // and the pump re-ran the pass forever. This constructor percent-encodes
    // exactly what `File(URI)` (the reader and the delete path) decodes, and —
    // unlike `File.toURI()` — it does not depend on whether the directory
    // already exists, so one identity+revision always names one string.
    return java.net.URI("file", "", dest.absoluteFile.invariantSeparatorsPath, null).toString()
}

/**
 * The `<ns>-downloads` root segment a catalog download tree lives under. One
 * definition so the delete path ([deleteCatalogDownloadRevision]) prunes up to
 * exactly the root [defaultDownloadLocationUri] wrote below.
 */
internal fun catalogDownloadRootSegment(identity: GalleryIdentityV1): String =
    when {
        identity.providerNamespace == GalleryIdentityV1.EHENTAI -> "ehentai-downloads"
        identity.providerNamespace == GalleryIdentityV1.NHENTAI -> "nhentai-downloads"
        identity.providerNamespace == GalleryIdentityV1.CLOUD_COLLECTION -> "cloud-collection-downloads"
        identity.providerNamespace.startsWith(GalleryIdentityV2.SERIALIZED_STORAGE_NAMESPACE_PREFIX) ->
            "serialized-downloads"
        else -> "opds-downloads"
    }

/**
 * #C20 audit HIGH-1: the directory level that keeps two OPDS sources apart.
 * Null for the three official namespaces (their trees predate this and are
 * constants — nothing to keep apart, nothing on disk to move). Spelled the way
 * `FileDownloadLocatorStore.fileFor` spells a namespace (':' folded to '_') and
 * bounded like every other segment, so `opds:<uuid>` reads `opds_<uuid>`.
 */
internal fun catalogDownloadNamespaceSegment(identity: GalleryIdentityV1): String? =
    when (identity.providerNamespace) {
        GalleryIdentityV1.EHENTAI,
        GalleryIdentityV1.NHENTAI,
        GalleryIdentityV1.CLOUD_COLLECTION,
        -> null
        else -> BoundedPathSegment.of(identity.providerNamespace.replace(Regex("[\\\\/\\u0000:]"), "_"))
    }

/**
 * #C20 re-review HIGH-2: where a NON-official namespace's tree lived BEFORE the
 * per-namespace level (`<root>/<item>/r<rev>`). Null for the three official
 * namespaces, whose trees never moved.
 *
 * Nothing on disk is ever moved to the new shape (re-review r2 BLOCKING-1 —
 * the Library's `pages.documentUri` rows are absolute `file://` paths into
 * these trees). [resolveLocalCatalogPagesForIngest] resolves both shapes for
 * readers, and [reclaimOrphanLegacyCatalogTrees] gives a legacy tree nothing
 * references any more its lifecycle.
 *
 * Note what this shape implies and both of those and
 * `OpdsDoubleNamespaceCleanup` rely on: being namespace-blind, TWO
 * identities that differ only in their namespace resolve to ONE legacy
 * directory — which is precisely the collision #C20 HIGH-1 fixed.
 */
fun legacyCatalogDownloadRevisionDirectory(
    filesDir: java.io.File,
    identity: GalleryIdentityV1,
    downloadRevision: Int,
): java.io.File? {
    if (catalogDownloadNamespaceSegment(identity) == null) return null
    // Spelled by the same rule the pre-A1 `defaultDownloadLocationUri` used, so
    // the readers and the sweep name exactly what the older build wrote.
    val item = BoundedPathSegment.of(identity.canonicalItemId.replace(Regex("[\\\\/\\u0000]"), "_"))
    return java.io.File(filesDir, "${catalogDownloadRootSegment(identity)}/$item/r$downloadRevision")
}

fun catalogDownloadRevisionDirectory(
    filesDir: java.io.File,
    identity: GalleryIdentityV1,
    downloadRevision: Int,
): java.io.File = java.io.File(java.net.URI(defaultDownloadLocationUri(identity, downloadRevision, filesDir)))

/**
 * Deletes the NAMESPACED revision tree (`<root>/<ns>/<item>/r<rev>`, or
 * `<root>/<item>/r<rev>` for the three official namespaces, whose trees never
 * had a namespace level) and prunes the parents it empties.
 *
 * r3 MEDIUM-3, said out loud: this governs the namespaced shape ONLY. A legacy
 * (namespace-blind) tree written by a pre-#C20 build is DELIBERATELY left
 * where it is — the Library's `pages.documentUri` rows are absolute `file://`
 * paths into it and moving or unlinking it here would break a comic that is
 * still on the shelf. [reclaimOrphanLegacyCatalogTrees] owns that shape and
 * takes it at the next start, once nothing references it any more.
 *
 * Return value unchanged and deliberately generous: true when the namespaced
 * tree is gone OR was never there. It is NOT a claim that every byte of this
 * comic left the device.
 */
fun deleteCatalogDownloadRevision(
    filesDir: java.io.File,
    identity: GalleryIdentityV1,
    downloadRevision: Int,
): Boolean {
    val dir = catalogDownloadRevisionDirectory(filesDir, identity, downloadRevision)
    val removed = !dir.exists() || dir.deleteRecursively()
    // F2: the tree is files/<ns>-downloads/<id>/r<rev> for the three official
    // namespaces and files/<ns>-downloads/<namespace>/<id>/r<rev> for every
    // other one (#C20 HIGH-1). Deleting r<rev> used to strand the now-empty
    // parents forever. Prune empty parents upward — one level or two, whichever
    // this identity has — stopping at (never deleting) the <ns>-downloads root.
    val root = java.io.File(filesDir, catalogDownloadRootSegment(identity)).absoluteFile
    var parent = dir.absoluteFile.parentFile
    while (
        parent != null &&
        parent != root &&
        parent.path.startsWith(root.path + java.io.File.separator) &&
        parent.isDirectory &&
        parent.list()?.isEmpty() == true
    ) {
        if (!parent.delete()) break
        parent = parent.parentFile
    }
    return removed
}

/**
 * Copy READY temp pages into the catalog-download tree used by Library →
 * Downloads. Page URIs must contain that tree so [isCatalogDownloadUri] matches
 * and app-private grant checks see the real filesDir.
 */
fun materializeCatalogDownloadPages(
    filesDir: java.io.File,
    identity: GalleryIdentityV1,
    downloadRevision: Int,
    readyPagePaths: List<java.nio.file.Path>,
): Pair<String, List<com.easemic.app.model.DocumentRef>> {
    val locationUri = defaultDownloadLocationUri(identity, downloadRevision, filesDir)
    val destDir = java.io.File(java.net.URI(locationUri))
    destDir.mkdirs()
    val pages = readyPagePaths.mapIndexed { index, src ->
        val dest = destDir.toPath().resolve(src.fileName.toString())
        java.nio.file.Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        com.easemic.app.model.DocumentRef(
            documentId = dest.fileName.toString(),
            uri = dest.toUri().toString(),
            displayName = "page-${index + 1}",
            mimeType = "image/*",
        )
    }
    return locationUri to pages
}

/**
 * Ordinals 0..[expected]-1 of [dir] as ordered files, or null when [dir] is not
 * a directory or any one of them is missing or empty.
 */
private fun completePagesIn(dir: java.io.File?, expected: Int): List<java.io.File>? {
    if (dir == null || !dir.isDirectory) return null
    val pages = (0 until expected).map { ordinal -> java.io.File(dir, ordinal.toString()) }
    return if (pages.any { !it.isFile || it.length() <= 0L }) null else pages
}

/**
 * Complete FINISH catalog-download revision as ordered page files for cloud
 * ingest. Returns null when any expected ordinal is missing or empty so callers
 * can fall back to upstream fetch.
 *
 * r3 MEDIUM-2: the choice between the two shapes is about the ORDINALS, not
 * about which directory exists. `materializeCatalogDownloadPages` mkdirs()
 * before it copies (and `adoptSealedLocalPages` re-materialises at the SAME
 * revision, so both shapes really can coexist for one identity+revision), so an
 * empty namespaced dir left behind by a death mid-copy used to shadow a
 * complete legacy tree one directory over — for ever, parking the Collect
 * `LOCAL_PAGES_INCOMPLETE`. The namespaced tree still wins when it satisfies
 * the ordinals; the legacy one is tried only when it does not.
 */
fun resolveLocalCatalogPagesForIngest(
    filesDir: java.io.File,
    identity: GalleryIdentityV1,
    downloadRevision: Int,
    expectedPageCount: Int,
): List<java.io.File>? {
    if (expectedPageCount <= 0) return null
    return completePagesIn(catalogDownloadRevisionDirectory(filesDir, identity, downloadRevision), expectedPageCount)
        ?: completePagesIn(legacyCatalogDownloadRevisionDirectory(filesDir, identity, downloadRevision), expectedPageCount)
}

/**
 * SEALED (fetch-complete or truncation-sealed) download job pages as ordered
 * files for the cloud upload handoff (spec §1.1/§1.2). Unlike
 * [resolveLocalCatalogPagesForIngest], this reads straight off the
 * [ReadyPageStore] on-disk tree via [ReadyPageStore.readyPath] — never that
 * store's in-memory published-keys set, since a terminal SEALED job is never
 * re-processed by [DownloadWorkerLoop] (whose `hydrateTrusted` call only runs
 * for jobs still in the WAIT/DOWNLOAD consumption queue) and so would never
 * get re-admitted into memory after a process restart. Returns null when any
 * expected ordinal's file is missing or empty so callers fail the row
 * honestly instead of silently falling back to an upstream fetch.
 */
fun resolveSealedReadyPagesForIngest(
    readyPageStore: ReadyPageStore,
    identity: GalleryIdentityV1,
    downloadRevision: Int,
    pageCount: Int,
): List<java.io.File>? {
    if (pageCount <= 0) return null
    val pages = (0 until pageCount).map { ordinal ->
        readyPageStore.readyPath(identity, downloadRevision, ordinal).toFile()
    }
    if (pages.any { !it.isFile || it.length() <= 0L }) return null
    return pages
}

/**
 * True for a download job whose on-device pages are ready for the cloud
 * upload-only handoff (spec §1.1): SEALED always qualifies, and — since
 * FIX1's completion-branch rewrite — so does a FINISH job that also carries
 * [DownloadJob.intentCloud] (a dual-intent collect+download, OR-merged by the
 * DAO onto one row, which FINISHes rather than SEALs). Gates
 * [com.easemic.app.AppGraph.cloudIngestRunner] onto the
 * device-pages path instead of a legacy fetch-from-source hit, and gates the
 * collect-time direct handoff (FIX3) for an already-terminal job that the
 * pump will never re-process. A plain FINISH job (local-only, no
 * intentCloud) does not qualify — it has nothing waiting for cloud.
 */
fun isCloudIngestReadyDownloadJob(job: DownloadJob?): Boolean {
    if (job == null) return false
    return job.state == DownloadJobState.SEALED ||
        (job.state == DownloadJobState.FINISH && job.intentCloud)
}
