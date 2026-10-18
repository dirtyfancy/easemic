package com.easemic.app.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-comic object manifest. [pageExts] / [thumbExts] hold the sniffed
 * extension of each uploaded object so readers can rebuild its key without
 * listing the bucket. Both are empty for manifests written before extensions
 * existed; callers fall back to [ImageContentType.JPEG].
 *
 * [version] distinguishes the layout: `1` (default, legacy/absent "version"
 * field) means pages are ordinal-keyed (`pages/0000.<ext>`); `2` means pages
 * are content-addressed (`pages/<sha256hex>.<ext>`, see
 * [CloudCollectionKeys.pageKeyByHash]) and [pageHashes] / [pageBytes] hold the
 * per-ordinal digest and byte size recorded at upload time; `3` (#C5) keeps v2's
 * pages exactly and redefines [thumbExts] as ORDINAL-ALIGNED per-page grid
 * thumbs — see [CloudPageManifest.encodeV3]; `4` (#C47-C) keeps v3's `pages`
 * and ordinal-aligned [thumbExts] exactly and adds [thumbPackSize], the count
 * of consecutive ordinals packed into each `thumbs/pack-NNNN.zip` — see
 * [CloudPageManifest.encodeV4]; `5` (#C51) keeps v4's `pages`/`thumbs` exactly
 * and adds [pagePacks] + [pageOffsets]: high-resolution pages travel inside
 * byte-sized STORED zips (`pages/pack-NNNN.zip`, see [PagePack]) and a page is
 * an HTTP `Range` of its pack — see [CloudPageManifest.encodeV5]. Only v5 is
 * written now.
 *
 * #C5 no-migration ruling (owner, 2026-09-10, deliberate — not an oversight):
 * the owner authorized a total reset. App data is wiped by the next build and
 * the `pcl-library` bucket is being deleted, so v1/v2 buckets no longer exist
 * in the wild. The v1/v2 decode branches survive ONLY because they are already
 * written and free; nothing new should be added to support old layouts, and no
 * migration path exists or should be written. A fresh bucket is the only case
 * that must work.
 */
data class CloudPageManifestPayload(
    val pageCount: Int,
    val format: Int = 1,
    val version: Int = 1,
    val pageExts: List<String> = emptyList(),
    val thumbExts: List<String> = emptyList(),
    val pageHashes: List<String> = emptyList(),
    val pageBytes: List<Long> = emptyList(),
    val thumbPackSize: Int = 0,
    /** #C51 (v5): the comic's page packs in index order; empty for v1–v4. */
    val pagePacks: List<CloudPagePackEntry> = emptyList(),
    /** #C51 (v5): per ordinal, the page's data offset INSIDE its pack; length = [pageBytes]`[ordinal]`. */
    val pageOffsets: List<Long> = emptyList(),
)

/**
 * #C51: one page pack. Its index in [CloudPageManifestPayload.pagePacks] IS the
 * pack index — `pages/pack-<index>.zip` ([CloudCollectionKeys.pagePackKey]) — and
 * [first]/[count] are the consecutive ordinals it holds. [bytes] is the whole pack
 * object's size, which the presence-by-size resume compares against.
 */
data class CloudPagePackEntry(val first: Int, val count: Int, val bytes: Long)

/** One page's manifest-v2 record: content-addressed key material + honest metadata. */
data class CloudManifestPageEntryV2(
    val ordinal: Int,
    val hash: String,
    val ext: String,
    val mime: String,
    val bytes: Long,
)

object CloudPageManifest {
    /**
     * Content-addressed manifest (v2): pages carry their SHA-256 hash (of the
     * PLAINTEXT bytes, computed before any seal) alongside ordinal/ext/mime/
     * bytes, so a reader resolves `ordinal -> pages/<hash>.<ext>` and an
     * uploader resolves "is this hash already on the bucket" without a HEAD.
     * Thumbs stay ordinal-keyed (unaffected by this task).
     */
    fun encodeV2(
        pages: List<CloudManifestPageEntryV2>,
        thumbExts: List<String> = emptyList(),
    ): ByteArray {
        val ordered = pages.sortedBy { it.ordinal }
        val pagesArray = JSONArray()
        ordered.forEach { page ->
            pagesArray.put(
                JSONObject()
                    .put("ordinal", page.ordinal)
                    .put("hash", page.hash)
                    .put("ext", page.ext)
                    .put("mime", page.mime)
                    .put("bytes", page.bytes),
            )
        }
        val json = JSONObject()
            .put("version", 2)
            .put("pageCount", ordered.size)
            .put("format", 1)
            .put("pages", pagesArray)
            .put("thumbs", entries(thumbExts))
            .toString()
        return json.toByteArray(Charsets.UTF_8)
    }

    /**
     * Per-page thumbnail manifest (v3, #C5). Identical to [encodeV2] on the
     * `pages` side; the difference is entirely what `thumbs` MEANS:
     *
     * - v2 `thumbs` held AT MOST ONE entry — the 320px cover derivative — and
     *   ordinal 0 of that array was the cover, not page 0's grid thumb.
     * - v3 `thumbs` is ordinal-ALIGNED WITH `pages`: one compressed derivative
     *   per page, so `thumbs[N]` is the grid thumb for ordinal N. The cover is
     *   NOT in this array at all — it stays the full-resolution
     *   `pages/<hash0>.<ext>` object so the gallery cover isn't blurry.
     *
     * The version bump is load-bearing: a v2 array's single cover entry would
     * otherwise be silently misread as page 0's grid thumb, leaving every other
     * ordinal with no thumb and page 0 with the wrong one.
     */
    fun encodeV3(
        pages: List<CloudManifestPageEntryV2>,
        thumbExts: List<String>,
    ): ByteArray {
        val ordered = pages.sortedBy { it.ordinal }
        val pagesArray = JSONArray()
        ordered.forEach { page ->
            pagesArray.put(
                JSONObject()
                    .put("ordinal", page.ordinal)
                    .put("hash", page.hash)
                    .put("ext", page.ext)
                    .put("mime", page.mime)
                    .put("bytes", page.bytes),
            )
        }
        val json = JSONObject()
            .put("version", 3)
            .put("pageCount", ordered.size)
            .put("format", 1)
            .put("pages", pagesArray)
            .put("thumbs", entries(thumbExts))
            .toString()
        return json.toByteArray(Charsets.UTF_8)
    }

    /**
     * Packed-thumbnail manifest (v4, #C47-C). Identical to [encodeV3] — the
     * `pages` array and [thumbExts]'s per-ordinal meaning are UNCHANGED; an
     * entry's `ext` still names `NNNN.<ext>`, but that name now lives INSIDE
     * a pack archive rather than as a standalone object. [thumbPackSize] is
     * the count of consecutive ordinals packed into one STORED zip (see
     * [ThumbPack]); a reader derives `pack index = ordinal / thumbPackSize`
     * and the pack's key from [CloudCollectionKeys.thumbPackKey] with that
     * pack's first ordinal (`index * thumbPackSize`).
     *
     * Only v4 is written from now on; [encodeV3] stays only for tests and as
     * the v3 compatibility seed (decoding pre-#C47-C manifests).
     */
    fun encodeV4(
        pages: List<CloudManifestPageEntryV2>,
        thumbExts: List<String>,
        thumbPackSize: Int,
    ): ByteArray {
        require(thumbPackSize > 0) { "thumbPackSize must be > 0" }
        val ordered = pages.sortedBy { it.ordinal }
        val pagesArray = JSONArray()
        ordered.forEach { page ->
            pagesArray.put(
                JSONObject()
                    .put("ordinal", page.ordinal)
                    .put("hash", page.hash)
                    .put("ext", page.ext)
                    .put("mime", page.mime)
                    .put("bytes", page.bytes),
            )
        }
        val json = JSONObject()
            .put("version", 4)
            .put("pageCount", ordered.size)
            .put("format", 1)
            .put("pages", pagesArray)
            .put("thumbs", entries(thumbExts))
            .put("thumbPackSize", thumbPackSize)
            .toString()
        return json.toByteArray(Charsets.UTF_8)
    }

    /**
     * Page-pack manifest (v5, #C51). Identical to [encodeV4] on the `pages` and
     * `thumbs` sides — every field keeps its meaning — plus the page-pack index:
     *
     * ```json
     * "pagePacks":   [ { "first": 0, "count": 30, "bytes": 33421312 }, … ],
     * "pageOffsets": [ 40, 1048645, … ]
     * ```
     *
     * `pagePacks[i]` describes `pages/pack-%04d.zip` with `i` as the pack INDEX;
     * `pageOffsets[ordinal]` is that page's data offset inside its pack and its
     * length is the `pages` entry's own `bytes`. [pageHashes] stay recorded — they
     * are content facts, and page 0's still names the standalone cover object — but
     * they no longer name the page objects a reader fetches.
     *
     * Nothing encryption-specific is added: #C50's chunked AEAD maps plaintext ranges
     * to ciphertext chunks INSIDE the encrypted store, so this stays a plaintext-offset
     * index.
     */
    fun encodeV5(
        pages: List<CloudManifestPageEntryV2>,
        thumbExts: List<String>,
        thumbPackSize: Int,
        pagePacks: List<CloudPagePackEntry>,
        pageOffsets: List<Long>,
    ): ByteArray {
        require(thumbPackSize > 0) { "thumbPackSize must be > 0" }
        val ordered = pages.sortedBy { it.ordinal }
        require(pageOffsets.size == ordered.size) { "pageOffsets ${pageOffsets.size} != pages ${ordered.size}" }
        require(pagePacks.isNotEmpty() || ordered.isEmpty()) { "a v5 manifest with pages needs pagePacks" }
        val pagesArray = JSONArray()
        ordered.forEach { page ->
            pagesArray.put(
                JSONObject()
                    .put("ordinal", page.ordinal)
                    .put("hash", page.hash)
                    .put("ext", page.ext)
                    .put("mime", page.mime)
                    .put("bytes", page.bytes),
            )
        }
        val packsArray = JSONArray()
        pagePacks.forEach { pack ->
            packsArray.put(
                JSONObject()
                    .put("first", pack.first)
                    .put("count", pack.count)
                    .put("bytes", pack.bytes),
            )
        }
        val offsetsArray = JSONArray()
        pageOffsets.forEach { offsetsArray.put(it) }
        val json = JSONObject()
            .put("version", 5)
            .put("pageCount", ordered.size)
            .put("format", 1)
            .put("pages", pagesArray)
            .put("thumbs", entries(thumbExts))
            .put("thumbPackSize", thumbPackSize)
            .put("pagePacks", packsArray)
            .put("pageOffsets", offsetsArray)
            .toString()
        return json.toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): CloudPageManifestPayload {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val version = json.optInt("version", 1)
        if (version > CloudFormatVersions.PAGE_MANIFEST) {
            throw NewerCloudFormatException("page manifest", version)
        }
        if (version >= 2) {
            val pagesArray = json.optJSONArray("pages")
            val ordered = (0 until (pagesArray?.length() ?: 0))
                .mapNotNull { i -> pagesArray?.optJSONObject(i) }
                .sortedBy { it.optInt("ordinal", 0) }
            val pageCount = json.getInt("pageCount")
            val pagePacks = packs(json.optJSONArray("pagePacks"))
            val pageOffsets = offsets(json.optJSONArray("pageOffsets"))
            if (version >= 5) {
                // Fail closed: a v5 manifest whose index is missing or does not tile the
                // pages would address the WRONG bytes for an ordinal. Counts only — no key
                // material in the message.
                check(pagePacks.isNotEmpty() || pageCount == 0) { "v5 manifest without pagePacks" }
                check(pageOffsets.size == pageCount) {
                    "v5 manifest pageOffsets ${pageOffsets.size} != pageCount $pageCount"
                }
                var next = 0
                pagePacks.forEach { pack ->
                    check(pack.first == next && pack.count > 0) {
                        "v5 manifest pagePacks do not tile 0 until $pageCount (${pagePacks.size} packs)"
                    }
                    next += pack.count
                }
                check(next == pageCount) {
                    "v5 manifest pagePacks cover $next of $pageCount pages"
                }
            }
            return CloudPageManifestPayload(
                pageCount = pageCount,
                format = json.optInt("format", 1),
                version = version,
                pageExts = ordered.map { it.optString("ext", "") },
                thumbExts = exts(json.optJSONArray("thumbs")),
                pageHashes = ordered.map { it.optString("hash", "") },
                pageBytes = ordered.map { it.optLong("bytes", 0L) },
                thumbPackSize = json.optInt("thumbPackSize", 0),
                pagePacks = pagePacks,
                pageOffsets = pageOffsets,
            )
        }
        return CloudPageManifestPayload(
            pageCount = json.getInt("pageCount"),
            format = json.optInt("format", 1),
            pageExts = exts(json.optJSONArray("pages")),
            thumbExts = exts(json.optJSONArray("thumbs")),
        )
    }

    /** #C51: the v5 page-pack table, in index order; empty for v1–v4. */
    private fun packs(array: JSONArray?): List<CloudPagePackEntry> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            CloudPagePackEntry(
                first = item.optInt("first", -1),
                count = item.optInt("count", 0),
                bytes = item.optLong("bytes", 0L),
            )
        }
    }

    /** #C51: per-ordinal data offsets inside the page's pack. */
    private fun offsets(array: JSONArray?): List<Long> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.optLong(it, -1L) }
    }

    private fun entries(exts: List<String>): JSONArray {
        val array = JSONArray()
        exts.forEachIndexed { ordinal, ext ->
            array.put(JSONObject().put("ordinal", ordinal).put("ext", ext))
        }
        return array
    }

    /**
     * Reads the recorded extensions in ordinal order. A missing or blank `ext`
     * truncates the list there rather than shifting later ordinals onto the
     * wrong entry; callers fall back to [ImageContentType.JPEG] for every
     * ordinal past the truncation point.
     */
    private fun exts(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val ext = array.optJSONObject(i)?.optString("ext").orEmpty()
                if (ext.isBlank()) return@buildList
                add(ext)
            }
        }
    }
}
