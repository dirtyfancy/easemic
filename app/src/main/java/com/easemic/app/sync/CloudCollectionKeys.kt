package com.easemic.app.sync

/**
 * Object keys for the Cloud Collection layout — plaintext by default; an
 * opted-in library seals object contents via CloudLibraryGate/PcloCodec, but
 * keys and layout stay identical either way, so any S3 client can list the
 * bucket and open what it finds.
 *
 * ```text
 * comics/<uuid>/meta.json
 * comics/<uuid>/pages.manifest.json
 * comics/<uuid>/pages/0000.jpg       comics/<uuid>/thumbs/0000.jpg
 * comics/<uuid>/pages/pack-0000.zip  comics/<uuid>/thumbs/pack-0000.zip
 * collection/index/<n>.json          collection/manifest.json
 * collection/trash/index/<n>.json    collection/trash/manifest.json
 * ```
 *
 * Since #C47-C new uploads pack thumbs 24 per object; per-page
 * `thumbs/NNNN.<ext>` objects remain readable for comics uploaded before it.
 * Since #C51 new uploads also pack PAGES, ~32 MiB per object (see [PagePack]),
 * plus one standalone content-addressed page-0 object that stays the card's cover.
 *
 * **URL-safety contract (#C51, folding in #C24 LOW-1/LOW-2):** every key this
 * object produces matches `^[a-z0-9./-]+$`. `R2ObjectStore.objectUrl` does NOT
 * percent-encode keys and `listUrl` is the only query builder, so a key that
 * needed encoding would be sent raw; callers that build query values pass them
 * fully percent-encoded themselves. `CloudCollectionKeysTest.everyKeyHelperIsUrlSafe`
 * pins it.
 *
 * Ordinals are 0-based and zero-padded to 4 digits so bucket listings sort
 * naturally. Extensions come from [ImageContentType.sniff] at upload time and
 * are recorded per object in the comic's `pages.manifest.json`.
 *
 * #C5 (owner design, 2026-09-10) fixes what each namespace is FOR:
 * - `pages/` holds full-resolution objects. Since #C51 the reader reads pages
 *   as `Range` windows of `pack-NNNN.zip` (see [pagePackKey]); the gallery
 *   COVER alone stays a standalone content-addressed (SHA-256) page-0 object —
 *   a cover must not be a blurry derivative.
 * - `thumbs/` holds one COMPRESSED derivative per page, ordinal-keyed, read by
 *   the detail preview grid. The cover is deliberately NOT duplicated here.
 *
 * No migration exists and none should be written: the owner authorized a total
 * reset, so app data is wiped and the old bucket deleted. A fresh bucket is the
 * only supported case — that is a ruling, not an oversight.
 */
object CloudCollectionKeys {
    fun metaKey(cloudComicId: String): String =
        "comics/${requireId(cloudComicId)}/meta.json"

    /**
     * A page's COMPRESSED grid derivative (#C5) — what the detail-screen
     * preview grid reads, one per page, so the grid loads without touching
     * full-resolution objects. Target ~320px longest edge, JPEG q80 (the same
     * bounded decode + re-encode the cover path already used).
     *
     * Thumbs stay ORDINAL-keyed while pages are hash-keyed, deliberately: the
     * grid's access pattern is "give me ordinal N", which an ordinal key answers
     * with no manifest hash lookup. This cannot break under a re-upload with a
     * different page count, because a re-upload rewrites `pages.manifest.json`
     * with the new `pageCount` and `thumbs` array in the same pass, and the grid
     * only ever iterates ordinals `0 until pageCount` from that manifest — a
     * stale higher-ordinal thumb object left on the bucket is unreferenced, and
     * a shorter re-upload's ordinals are all overwritten in place. (Unlike
     * pages, thumbs are cheap to re-PUT, so they need no content-addressed
     * dedupe.)
     */
    fun thumbKey(cloudComicId: String, ordinal: Int, ext: String): String =
        "comics/${requireId(cloudComicId)}/thumbs/${requireOrdinal(ordinal)}.${requireObjectExt(ext)}"

    /**
     * #C47-C: thumbs travel as one STORED zip per `thumbPackSize` consecutive
     * ordinals (see [ThumbPack]), named by the pack's FIRST ordinal so listings
     * sort naturally. Lives under the comic prefix (a discard's prefix delete
     * removes it) and cannot collide with the legacy per-page `thumbs/NNNN.<ext>`
     * names, which v3 comics keep using.
     */
    fun thumbPackKey(cloudComicId: String, firstOrdinal: Int): String =
        "comics/${requireId(cloudComicId)}/thumbs/pack-${requireOrdinal(firstOrdinal)}.zip"

    /** Legacy v1 layout: ordinal-keyed. Kept so pre-existing comics stay readable. */
    fun pageKey(cloudComicId: String, ordinal: Int, ext: String): String =
        "comics/${requireId(cloudComicId)}/pages/${requireOrdinal(ordinal)}.${requireObjectExt(ext)}"

    /**
     * #C51: a page PACK — one STORED zip of consecutive ordinals, about
     * [PagePack.TARGET_BYTES] of pages (see [PagePack]). Named by the pack INDEX,
     * NOT by its first ordinal: unlike thumb packs, page packs are byte-sized, so
     * the ordinal→pack mapping lives in the manifest (`pagePacks`) and only the
     * index can name the object. Lives under the comic prefix, so a discard's
     * prefix delete removes it, and cannot collide with the hash-keyed page
     * objects (a `pack-` prefix is not 64 hex characters).
     */
    fun pagePackKey(cloudComicId: String, packIndex: Int): String =
        "comics/${requireId(cloudComicId)}/pages/${PagePack.fileName(packIndex)}"

    /**
     * Content-addressed page key (manifest v2): identical bytes always resolve
     * to the same object, so a re-collect or a killed-mid-upload resume never
     * re-PUTs a page already on the bucket. [hash] is the lowercase hex
     * SHA-256 of the page's PLAINTEXT bytes, computed before any seal.
     */
    fun pageKeyByHash(cloudComicId: String, hash: String, ext: String): String =
        "comics/${requireId(cloudComicId)}/pages/${requireHash(hash)}.${requireObjectExt(ext)}"

    fun pagesManifestKey(cloudComicId: String): String =
        "comics/${requireId(cloudComicId)}/pages.manifest.json"

    /** Format-2 series summary. The series id is stable across all chapters. */
    fun seriesManifestKey(seriesCloudId: String): String =
        "comics/${requireId(seriesCloudId)}/series.json"

    /** Content-addressed source cover shared by every chapter of a series. */
    fun seriesCoverKey(seriesCloudId: String, hash: String, ext: String): String =
        "comics/${requireId(seriesCloudId)}/cover/${requireHash(hash)}.${requireObjectExt(ext)}"

    /** Opaque per-chapter prefix; [chapterKey] is never a provider id. */
    fun chapterPrefix(seriesCloudId: String, chapterKey: String): String =
        "comics/${requireId(seriesCloudId)}/chapters/${requireChapterKey(chapterKey)}/"

    fun chapterMetaKey(seriesCloudId: String, chapterKey: String): String =
        chapterPrefix(seriesCloudId, chapterKey) + "meta.json"

    fun chapterPagesManifestKey(seriesCloudId: String, chapterKey: String): String =
        chapterPrefix(seriesCloudId, chapterKey) + "pages.manifest.json"

    fun chapterPagePackKey(seriesCloudId: String, chapterKey: String, packIndex: Int): String {
        require(packIndex >= 0) { "packIndex must be >= 0" }
        return chapterPrefix(seriesCloudId, chapterKey) + "pages/${PagePack.fileName(packIndex)}"
    }

    fun chapterThumbPackKey(seriesCloudId: String, chapterKey: String, firstOrdinal: Int): String =
        chapterPrefix(seriesCloudId, chapterKey) + "thumbs/pack-${requireOrdinal(firstOrdinal)}.zip"

    fun chapterPageKeyByHash(seriesCloudId: String, chapterKey: String, hash: String, ext: String): String =
        chapterPrefix(seriesCloudId, chapterKey) + "pages/${requireHash(hash)}.${requireObjectExt(ext)}"

    fun collectionManifestKey(): String = "collection/manifest.json"

    fun comicPrefix(cloudComicId: String): String =
        "comics/${requireId(cloudComicId)}/"

    fun indexChunkKey(chunkIndex: Int): String {
        require(chunkIndex >= 0) { "chunkIndex must be >= 0" }
        return "collection/index/$chunkIndex.json"
    }

    fun trashManifestKey(): String = "collection/trash/manifest.json"

    fun trashIndexChunkKey(chunkIndex: Int): String {
        require(chunkIndex >= 0) { "chunkIndex must be >= 0" }
        return "collection/trash/index/$chunkIndex.json"
    }

    /** Keyslot object at the bucket root; its presence marks the library encrypted. */
    fun lockKey(): String = "library.lock"

    /** #E118: Grow-only set of 64-bit dHash fingerprints for known sponsor ad pages. */
    fun adFingerprintsKey(): String = "meta/ad-fingerprints.json"

    private fun requireId(cloudComicId: String): String {
        val id = cloudComicId.trim()
        require(id.isNotEmpty()) { "cloudComicId required" }
        require(!id.contains("..") && !id.contains('/')) { "bad cloudComicId" }
        return id
    }

    private fun requireChapterKey(chapterKey: String): String {
        val key = chapterKey.trim().lowercase()
        require(key.isNotEmpty() && key.matches(Regex("[a-z0-9-]+"))) { "bad chapterKey" }
        return key
    }
}

/** 0-based ordinal, zero-padded to 4 digits so bucket listings sort naturally. */
internal fun requireOrdinal(ordinal: Int): String {
    require(ordinal >= 0) { "ordinal must be >= 0" }
    return "%04d".format(ordinal)
}

/** Sniffed file extension; must stay a single safe path segment suffix. */
internal fun requireObjectExt(ext: String): String {
    val value = ext.trim().lowercase()
    require(value.isNotEmpty()) { "ext required" }
    require(value.all { it in 'a'..'z' || it in '0'..'9' }) { "bad ext" }
    return value
}

/** 64 lowercase hex characters — a SHA-256 digest, nothing else. */
internal fun requireHash(hash: String): String {
    val value = hash.trim().lowercase()
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { "bad hash" }
    return value
}
