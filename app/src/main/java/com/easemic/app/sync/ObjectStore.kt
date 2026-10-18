package com.easemic.app.sync

import java.io.File

/** #C51: one object's key and size as the bucket listed it; [sizeBytes] is -1 when the transport cannot tell. */
data class ObjectSummary(val key: String, val sizeBytes: Long)

/** The `bytes=<start>-<end>/…` shape every range-honouring transport validates against (#E15 T4). */
private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/""")

/**
 * #E15 T4: a `206` must carry the span the request asked for. An absent or
 * unparseable header is accepted — WebDAV's long-standing rule; a header that
 * parses but names another start or end is a wrong-bytes answer and is not.
 */
internal fun contentRangeMatchesRequest(header: String?, offset: Long, lastInclusive: Long): Boolean {
    if (header.isNullOrBlank()) return true
    val span = CONTENT_RANGE.find(header) ?: return true
    return span.groupValues[1].toLong() == offset && span.groupValues[2].toLong() == lastInclusive
}

/**
 * Byte transport for the user's bucket. S3/SigV4 and OAuth REST both implement
 * this so the Cloud Collection / page stores do not care who hosts the bucket.
 *
 * Bytes go up and come back unchanged; [mime] is the object's declared
 * `Content-Type` so any S3 client (or a browser) opens what it finds.
 */
interface ObjectStore {
    fun upload(objectKey: String, bytes: ByteArray, mime: String = DEFAULT_MIME): Int
    fun uploadFile(objectKey: String, file: File, mime: String = DEFAULT_MIME): Int
    fun download(objectKey: String): ByteArray
    fun downloadToFile(objectKey: String, dest: File)
    fun listKeys(): List<String>

    /** Material used to derive opaque serialized-chapter directory names. */
    fun chapterKeyMaterial(): ByteArray = PLAINTEXT_CHAPTER_KEY_MATERIAL.copyOf()

    /** Whether this transport can serve a byte range without materialising the whole object. */
    val supportsEfficientRange: Boolean
        get() = false

    /**
     * #C50: the page-pack size this transport wants. One failed PUT costs a whole pack, and a
     * drive behind a slow link or a proxy window cannot take 32 MiB in one request.
     */
    val preferredPackBytes: Long
        get() = PagePack.TARGET_BYTES

    /**
     * #C51: bytes `[offset, offset + length)` of [objectKey]. Default = the
     * honest fallback (whole object, sliced) so every wrapper keeps working;
     * transports that speak HTTP `Range` override it and MUST return exactly
     * [length] bytes. A page of a v5 layout is a range of its pack, so this is
     * the read seam the whole page path now rests on.
     */
    fun downloadRange(objectKey: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "offset must be >= 0" }
        require(length > 0) { "length must be > 0" }
        val whole = download(objectKey)
        require(offset + length <= whole.size) { "range past the end of the object" }
        return whole.copyOfRange(offset.toInt(), (offset + length).toInt())
    }

    /**
     * #D17: [downloadRange] for a caller that knows the object's size as this store presents it (the size
     * [listObjects] reports — a v5 manifest records every pack's). Default: the size is not needed. The encrypted
     * store uses it to fetch a v2 header and exactly the range's chunks at once instead of every chunk from byte 0;
     * a store that wraps another must forward it (`ObjectStoreRangeDelegationTest`).
     */
    fun downloadRange(objectKey: String, offset: Long, length: Int, objectSize: Long): ByteArray =
        downloadRange(objectKey, offset, length)

    /**
     * #E29: bytes `[offset, offset + length)` written to [dest]. The default fails closed.
     * A whole-object byte download here would hide a transport that forgot the file override.
     */
    fun downloadRangeToFile(objectKey: String, offset: Long, length: Int, objectSize: Long, dest: File) {
        throw UnsupportedOperationException("downloadRangeToFile")
    }

    /**
     * #C51: keys under [prefix] WITH sizes. Default: keys only, size unknown
     * (-1) — a pack's presence then cannot be trusted and it is re-PUT, which
     * is correct but slower.
     */
    fun listObjects(prefix: String): List<ObjectSummary> = listKeys(prefix).map { ObjectSummary(it, -1L) }

    /**
     * Keys under [prefix] only. Default filters a full listing so every
     * wrapper keeps working; transports that can ask the server for a prefix
     * (S3 `prefix=`, Cloudflare `?prefix=`) override it — a per-comic prefix
     * is one request instead of paging the whole bucket, which is what made a
     * mid-upload resume cost a minute before its first PUT.
     */
    fun listKeys(prefix: String): List<String> = listKeys().filter { it.startsWith(prefix) }

    fun listUsage(): R2BucketUsage {
        val keys = listKeys()
        return R2BucketUsage(objectCount = keys.size, totalBytes = 0L)
    }
    fun delete(objectKey: String): Int

    companion object {
        /** Fallback when the caller has nothing better to declare. */
        const val DEFAULT_MIME: String = "application/octet-stream"

        /** Every Cloud Collection / page JSON object. */
        const val JSON_MIME: String = "application/json"
    }
}
