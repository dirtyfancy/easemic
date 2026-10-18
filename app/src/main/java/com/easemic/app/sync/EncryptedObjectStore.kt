package com.easemic.app.sync

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Seals every content object with [PcloCodec] under the library DEK and
 * declares octet-stream (real types would advertise what ciphertext holds).
 * `library.lock` bypasses sealing — it is the plaintext keyslot. Listing and
 * deletion are metadata operations and pass through untouched.
 *
 * Writes are PCLO v2 (#C67: per-object key, 64 KiB authenticated chunks, range-readable); v1 objects still open.
 * Whole-file v2 reads decrypt from a ciphertext scratch with bounded buffers. v1 keeps whole-object GCM.
 */
class EncryptedObjectStore(
    private val inner: ObjectStore,
    private val dek: ByteArray,
    /**
     * #C50: opened v2 headers by key. Every request to a drive is a ~1 s round trip, so a
     * reader's later windows of the same pack must not re-fetch the header. Shared by the
     * gate across its short-lived stores (one per call) for one binding generation.
     */
    private val headers: V2HeaderMemo = V2HeaderMemo(),
) : ObjectStore {
    override fun chapterKeyMaterial(): ByteArray = encryptedChapterKeyMaterial(dek).copyOf()

    /**
     * #C67: a v2 object is read by range — header plus only the chunks covering the range — whenever the transport
     * itself serves ranges efficiently. A v1 object (older bucket) still costs the whole object.
     */
    override val supportsEfficientRange: Boolean get() = inner.supportsEfficientRange

    override val preferredPackBytes: Long get() = inner.preferredPackBytes

    override fun upload(objectKey: String, bytes: ByteArray, mime: String): Int {
        headers.forget(objectKey)
        if (objectKey == CloudCollectionKeys.lockKey()) {
            return inner.upload(objectKey, bytes, mime)
        }
        return inner.upload(objectKey, PcloCodec.sealV2(dek, bytes), ObjectStore.DEFAULT_MIME)
    }

    /**
     * Sealed disk-to-disk ([PcloCodec.sealV2ToFile]) and handed to the transport as a file, so a page pack is never
     * held in memory — reading it whole and sealing it into a second whole copy crash-looped the owner's phone
     * (2026-09-27, 5 comic uploads in flight). The lock is tiny and stays unsealed.
     */
    override fun uploadFile(objectKey: String, file: File, mime: String): Int {
        if (objectKey == CloudCollectionKeys.lockKey()) return upload(objectKey, file.readBytes(), mime)
        headers.forget(objectKey)
        val sealed = File.createTempFile("pclo-", ".sealed", file.parentFile)
        try {
            PcloCodec.sealV2ToFile(dek, file, sealed)
            return inner.uploadFile(objectKey, sealed, ObjectStore.DEFAULT_MIME)
        } finally {
            sealed.delete()
        }
    }

    override fun download(objectKey: String): ByteArray {
        val raw = inner.download(objectKey)
        if (objectKey == CloudCollectionKeys.lockKey()) return raw
        return PcloCodec.open(dek, raw)
    }

    override fun downloadToFile(objectKey: String, dest: File) {
        if (objectKey == CloudCollectionKeys.lockKey()) return inner.downloadToFile(objectKey, dest)
        val scratch = cipherScratch(dest)
        try {
            inner.downloadToFile(objectKey, scratch)
            if (Thread.currentThread().isInterrupted) throw InterruptedException("cover decryption interrupted")
            val prefix = readPrefix(scratch, PcloCodec.V2_HEADER)
            if (!PcloCodec.looksSealed(prefix)) throw PcloFormatException("PCLO_BAD_MAGIC")
            when (prefix[4]) {
                PcloCodec.FORMAT_VERSION -> if (scratch.length() <= 17L) throw PcloFormatException("PCLO_TRUNCATED")
                PcloCodec.FORMAT_VERSION_V2 -> if (scratch.length() < PcloCodec.V2_HEADER) throw PcloFormatException("PCLO_TRUNCATED")
                else -> throw PcloFormatException("PCLO_UNSUPPORTED_VERSION")
            }
            if (prefix[4] == PcloCodec.FORMAT_VERSION_V2) {
                // A whole read uses this file's authenticated header, never a range memo from another upload.
                val header = PcloCodec.openV2Header(dek, prefix)
                if (header.plainSize > Int.MAX_VALUE) throw PcloFormatException("PCLO_SIZE_TOO_LARGE")
                if (scratch.length() != PcloCodec.V2Layout.sealedSize(header.plainSize)) {
                    throw PcloFormatException("PCLO_TRUNCATED")
                }
                decryptScratch(header, scratch, 0L, 0L, header.plainSize.toInt(), dest)
            } else {
                // Legacy v1: retain the existing codec semantics, using the single already-downloaded object.
                dest.writeBytes(PcloCodec.open(dek, scratch.readBytes()))
            }
        } catch (failure: Throwable) {
            truncateFile(dest)
            throw failure
        } finally {
            scratch.delete()
        }
    }

    override fun downloadRangeToFile(objectKey: String, offset: Long, length: Int, objectSize: Long, dest: File) {
        require(offset >= 0) { "offset must be >= 0" }
        require(length > 0) { "length must be > 0" }
        truncateFile(dest)
        if (!inner.supportsEfficientRange) {
            writeInefficientRange(objectKey, offset, length, dest)
            return
        }
        if (objectSize < offset + length || headers.get(objectKey) != null) {
            writeUnsizedRange(objectKey, offset, length, dest, allowMemoRetry = true)
            return
        }
        writeSizedRange(objectKey, offset, length, objectSize, dest)
    }

    /** Same geometry as the sized byte read, but the ciphertext span is a file. */
    private fun writeSizedRange(objectKey: String, offset: Long, length: Int, objectSize: Long, dest: File) {
        val first = offset / PcloCodec.V2_CHUNK
        val lastChunk = ((offset + length - 1) / PcloCodec.V2_CHUNK).toInt()
        val (lastStart, lastLen) = PcloCodec.V2Layout.chunkSpan(objectSize, lastChunk)
        val spanEnd = lastStart + lastLen
        if (spanEnd > Int.MAX_VALUE) throw IOException("range span does not fit")
        if (first == 0L) {
            val scratch = cipherScratch(dest)
            try {
                try {
                    inner.downloadRangeToFile(objectKey, 0, spanEnd.toInt(), objectSize, scratch)
                } catch (_: IOException) {
                    truncateFile(dest)
                    writeUnsizedRange(objectKey, offset, length, dest, allowMemoRetry = true)
                    return
                } catch (_: IllegalArgumentException) {
                    truncateFile(dest)
                    writeUnsizedRange(objectKey, offset, length, dest, allowMemoRetry = true)
                    return
                }
                val header = openedV2Header(objectKey, readPrefix(scratch, PcloCodec.V2_HEADER))
                if (header == null) {
                    writeByteRange(objectKey, offset, length, dest)
                    return
                }
                if (header.plainSize != objectSize) {
                    truncateFile(scratch)
                    truncateFile(dest)
                    streamChunks(objectKey, header, offset, length, dest)
                    return
                }
                decryptScratch(header, scratch, bufferStart = 0L, offset, length, dest)
            } finally {
                scratch.delete()
            }
            return
        }
        val spanStart = PcloCodec.V2Layout.chunkOffset(first)
        val spanLen = (spanEnd - spanStart).toInt()
        val headerRead = CompletableFuture.supplyAsync(
            { readFileHeader(objectKey, dest) },
            HEADER_READS,
        )
        val scratch = cipherScratch(dest)
        var chunkFailure: Exception? = null
        try {
            try {
                inner.downloadRangeToFile(objectKey, spanStart, spanLen, objectSize, scratch)
            } catch (failure: Exception) {
                chunkFailure = failure
                truncateFile(scratch)
            }
            val headerBytes = try {
                headerRead.join()
            } catch (failed: CompletionException) {
                val cause = failed.cause ?: failed
                if (cause is IOException || cause is IllegalArgumentException) {
                    truncateFile(dest)
                    writeUnsizedRange(objectKey, offset, length, dest, allowMemoRetry = true)
                    return
                }
                throw cause
            }
            val header = openedV2Header(objectKey, headerBytes)
            if (header == null) {
                writeByteRange(objectKey, offset, length, dest)
                return
            }
            if (header.plainSize != objectSize) {
                truncateFile(scratch)
                truncateFile(dest)
                streamChunks(objectKey, header, offset, length, dest)
                return
            }
            if (chunkFailure != null) {
                if (chunkFailure is IllegalArgumentException) throw PcloFormatException("PCLO_TRUNCATED", chunkFailure)
                throw chunkFailure
            }
            try {
                decryptScratch(header, scratch, bufferStart = spanStart, offset, length, dest)
            } catch (failed: PcloFormatException) {
                headers.forget(objectKey)
                truncateFile(scratch)
                truncateFile(dest)
                writeUnsizedRange(objectKey, offset, length, dest, allowMemoRetry = false)
            }
        } finally {
            scratch.delete()
        }
    }

    /**
     * The three-argument file path. A memoised header that no longer opens the chunks is forgotten once,
     * and the scratch plus the output are truncated before the fresh span.
     */
    private fun writeUnsizedRange(
        objectKey: String,
        offset: Long,
        length: Int,
        dest: File,
        allowMemoRetry: Boolean,
    ) {
        if (!inner.supportsEfficientRange) {
            writeInefficientRange(objectKey, offset, length, dest)
            return
        }
        headers.get(objectKey)?.let { memo ->
            try {
                streamChunks(objectKey, memo, offset, length, dest)
                return
            } catch (failed: PcloFormatException) {
                headers.forget(objectKey)
                truncateFile(dest)
                if (!allowMemoRetry) throw failed
            } catch (failed: IllegalArgumentException) {
                headers.forget(objectKey)
                truncateFile(dest)
                if (!allowMemoRetry) throw PcloFormatException("PCLO_TRUNCATED", failed)
            }
        }
        if (offset + length <= HEADER_BUNDLE_BYTES) {
            val end = PcloCodec.V2Layout.chunkOffset(((offset + length - 1) / PcloCodec.V2_CHUNK) + 1)
            val scratch = cipherScratch(dest)
            try {
                val bundled = try {
                    inner.downloadRangeToFile(objectKey, 0, end.toInt(), end, scratch)
                    true
                } catch (_: IOException) {
                    false
                } catch (_: IllegalArgumentException) {
                    false
                }
                if (bundled) {
                    val header = openedV2Header(objectKey, readPrefix(scratch, PcloCodec.V2_HEADER))
                    if (header != null) {
                        decryptScratch(header, scratch, bufferStart = 0L, offset, length, dest)
                        return
                    }
                }
            } finally {
                scratch.delete()
            }
        }
        val headerBytes = try {
            readFileHeader(objectKey, dest)
        } catch (failed: IOException) {
            // A file-route page is at least 4 MiB. A header read that fails is a transport
            // failure, not a short v1 object, so it must not fall through to the whole object.
            throw failed
        } catch (failed: IllegalArgumentException) {
            throw PcloFormatException("PCLO_TRUNCATED", failed)
        }
        val header = openedV2Header(objectKey, headerBytes)
        if (header == null) {
            writeByteRange(objectKey, offset, length, dest)
            return
        }
        streamChunks(objectKey, header, offset, length, dest)
    }

    /** Whole sealed object on disk, then the selected chunks. Legacy v1 keeps the byte path. */
    private fun writeInefficientRange(objectKey: String, offset: Long, length: Int, dest: File) {
        val scratch = cipherScratch(dest)
        try {
            inner.downloadToFile(objectKey, scratch)
            val header = openedV2Header(objectKey, readPrefix(scratch, PcloCodec.V2_HEADER))
            if (header == null) {
                writeByteRange(objectKey, offset, length, dest)
                return
            }
            if (scratch.length() != PcloCodec.V2Layout.sealedSize(header.plainSize)) {
                throw PcloFormatException("PCLO_TRUNCATED")
            }
            decryptScratch(header, scratch, bufferStart = 0L, offset, length, dest)
        } finally {
            scratch.delete()
        }
    }

    private fun streamChunks(objectKey: String, header: PcloCodec.V2Header, offset: Long, length: Int, dest: File) {
        require(offset + length <= header.plainSize) { "range past the end of the object" }
        val first = (offset / PcloCodec.V2_CHUNK).toInt()
        val last = ((offset + length - 1) / PcloCodec.V2_CHUNK).toInt()
        val start = PcloCodec.V2Layout.chunkSpan(header.plainSize, first).first
        val (lastStart, lastLen) = PcloCodec.V2Layout.chunkSpan(header.plainSize, last)
        val wanted = (lastStart + lastLen - start).toInt()
        val scratch = cipherScratch(dest)
        try {
            try {
                inner.downloadRangeToFile(objectKey, start, wanted, header.plainSize, scratch)
            } catch (error: IllegalArgumentException) {
                throw PcloFormatException("PCLO_TRUNCATED", error)
            }
            if (scratch.length() != wanted.toLong()) throw PcloFormatException("PCLO_TRUNCATED")
            decryptScratch(header, scratch, bufferStart = start, offset, length, dest)
        } finally {
            scratch.delete()
        }
    }

    private fun decryptScratch(
        header: PcloCodec.V2Header,
        scratch: File,
        bufferStart: Long,
        offset: Long,
        length: Int,
        dest: File,
    ) {
        require(offset + length <= header.plainSize) { "range past the end of the object" }
        val chunk = PcloCodec.V2_CHUNK
        val first = (offset / chunk).toInt()
        val last = if (length == 0) 0 else ((offset + length - 1) / chunk).toInt()
        val lastIndex = PcloCodec.V2Layout.chunkCount(header.plainSize) - 1
        val (endStart, endLen) = PcloCodec.V2Layout.chunkSpan(header.plainSize, last)
        if (bufferStart + scratch.length() < endStart + endLen) throw PcloFormatException("PCLO_TRUNCATED")
        RandomAccessFile(scratch, "r").use { input ->
            FileOutputStream(dest).use { output ->
                var written = 0
                val buf = ByteArray(chunk + PcloCodec.V2_TAG)
                // #E29: one cipher and one plaintext buffer for the whole page, not two arrays per 64 KiB chunk.
                val plain = ByteArray(chunk)
                val opener = header.chunkOpener()
                for (i in first..last) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("cover decryption interrupted")
                    val (cStart, cLen) = PcloCodec.V2Layout.chunkSpan(header.plainSize, i)
                    input.seek(cStart - bufferStart)
                    input.readFully(buf, 0, cLen)
                    val plainLen = opener.open(i.toLong(), i == lastIndex, buf, 0, cLen, plain)
                    val from = if (i == first) (offset - first.toLong() * chunk).toInt() else 0
                    val take = minOf(plainLen - from, length - written)
                    output.write(plain, from, take)
                    written += take
                }
            }
        }
    }

    /** v1 and any non-v2 object: the existing byte range, outside the v2 bound. */
    private fun writeByteRange(objectKey: String, offset: Long, length: Int, dest: File) {
        val bytes = downloadRange(objectKey, offset, length)
        FileOutputStream(dest).use { it.write(bytes) }
    }

    private fun readPrefix(file: File, count: Int): ByteArray {
        val got = minOf(count.toLong(), file.length()).toInt()
        if (got <= 0) return ByteArray(0)
        val buf = ByteArray(got)
        RandomAccessFile(file, "r").use { it.readFully(buf) }
        return buf
    }

    /** Even a 73-byte header must use bounded transport when the server ignores or over-serves Range. */
    private fun readFileHeader(objectKey: String, dest: File): ByteArray {
        val scratch = cipherScratch(dest)
        try {
            inner.downloadRangeToFile(objectKey, 0, PcloCodec.V2_HEADER, PcloCodec.V2_HEADER.toLong(), scratch)
            if (scratch.length() != PcloCodec.V2_HEADER.toLong()) throw PcloFormatException("PCLO_TRUNCATED")
            return readPrefix(scratch, PcloCodec.V2_HEADER)
        } finally {
            scratch.delete()
        }
    }

    private fun cipherScratch(dest: File): File {
        val parent = dest.parentFile ?: dest.absoluteFile.parentFile
        parent?.mkdirs()
        return File.createTempFile("e29-cipher-", ".part", parent)
    }

    private fun truncateFile(file: File) {
        FileOutputStream(file).use { }
    }

    /**
     * #C67: bytes `[offset, offset + length)` of the PLAINTEXT. A v2 object is fetched as its header plus the chunks
     * covering the range, each chunk authenticated on its own; a v1 object, or a transport without efficient ranges,
     * falls back to the whole object (decrypted, then sliced).
     */
    override fun downloadRange(objectKey: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "offset must be >= 0" }
        require(length > 0) { "length must be > 0" }
        if (!inner.supportsEfficientRange) return sliceOfWhole(objectKey, offset, length)
        headers.get(objectKey)?.let { memo ->
            // #C50 review Sev1: another device may have re-uploaded this key since we opened it —
            // every seal mints a fresh object key, so the memoised header no longer opens the chunks
            // (or no longer matches the size). Forget it and read the header again, once.
            try {
                return readChunks(objectKey, memo, offset, length)
            } catch (_: PcloFormatException) {
                headers.forget(objectKey)
            } catch (_: IllegalArgumentException) {
                headers.forget(objectKey)
            }
        }
        if (offset + length <= HEADER_BUNDLE_BYTES) {
            // #C50: a range near the start of an object we have not opened yet (a comic's first
            // page is at the start of pack 0) — fetch header + covering chunks in ONE request.
            // On a drive each request is a 1-3 s round trip.
            val end = PcloCodec.V2Layout.chunkOffset(((offset + length - 1) / PcloCodec.V2_CHUNK) + 1)
            val bundle = try {
                inner.downloadRange(objectKey, 0, end.toInt())
            } catch (_: java.io.IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (bundle != null && bundle.size >= PcloCodec.V2_HEADER && PcloCodec.looksSealed(bundle) &&
                bundle[4] == PcloCodec.FORMAT_VERSION_V2
            ) {
                val opened = PcloCodec.openV2Header(dek, bundle.copyOfRange(0, PcloCodec.V2_HEADER))
                headers.put(objectKey, opened)
                return decryptSpan(opened, bundle, bufferStart = 0L, offset, length)
            }
            // A short object (v1, or smaller than the bundle) answers the range short or refuses
            // it: fall through to the general path, which reads just the header.
        }
        // One request for version + header. A v1 object shorter than a v2 header makes the
        // range fail; that (and any non-v2 answer) takes the whole-object path.
        val headerBytes = try {
            inner.downloadRange(objectKey, 0, PcloCodec.V2_HEADER)
        } catch (short: java.io.IOException) {
            return sliceOfWhole(objectKey, offset, length)
        } catch (short: IllegalArgumentException) {
            return sliceOfWhole(objectKey, offset, length)
        }
        if (!PcloCodec.looksSealed(headerBytes) || headerBytes[4] != PcloCodec.FORMAT_VERSION_V2) {
            return sliceOfWhole(objectKey, offset, length)
        }
        val header = PcloCodec.openV2Header(dek, headerBytes).also { headers.put(objectKey, it) }
        return readChunks(objectKey, header, offset, length)
    }

    /**
     * #D17: [downloadRange] knowing the object's PLAINTEXT size (the v5 manifest records every pack's). For a v2
     * object whose header is not memoised, nothing outside the range is fetched: when the range's chunks start at
     * chunk 0 it is ONE request from byte 0 (header + those chunks — #C50's first page, whatever the range's length);
     * otherwise the 73-byte header and exactly the range's chunks go out as two concurrent requests. Resuming at
     * ordinal 6 used to fetch every chunk from byte 0 (3.1 MB for a 0.5 MB page, emulator 2026-09-29), and past 4 MiB
     * the header and the chunks were read one after the other.
     *
     * [objectSize] is a hint. The opened header's size is the truth: on a mismatch (a stale manifest) the fetched
     * chunks are dropped and read again with the real header; a v1 object is read whole. The chunk read goes out
     * before the header is known, so its failure is judged once the header is: only a failure of the right span is
     * reported. A header read that fails short takes the three-argument path; any other header failure (a 429, a 401)
     * is reported at once, as the three-argument read reports it.
     */
    override fun downloadRange(objectKey: String, offset: Long, length: Int, objectSize: Long): ByteArray {
        require(offset >= 0) { "offset must be >= 0" }
        require(length > 0) { "length must be > 0" }
        if (!inner.supportsEfficientRange || objectSize < offset + length || headers.get(objectKey) != null) {
            return downloadRange(objectKey, offset, length)
        }
        val first = offset / PcloCodec.V2_CHUNK
        val (lastStart, lastLen) = PcloCodec.V2Layout.chunkSpan(objectSize, ((offset + length - 1) / PcloCodec.V2_CHUNK).toInt())
        val spanEnd = lastStart + lastLen
        if (first == 0L) {
            val bundle = try {
                inner.downloadRange(objectKey, 0, spanEnd.toInt())
            } catch (_: java.io.IOException) {
                return downloadRange(objectKey, offset, length)
            } catch (_: IllegalArgumentException) {
                return downloadRange(objectKey, offset, length)
            }
            val header = openedV2Header(objectKey, bundle) ?: return sliceOfWhole(objectKey, offset, length)
            if (header.plainSize != objectSize) return readChunks(objectKey, header, offset, length)
            return decryptSpan(header, bundle, bufferStart = 0L, offset, length)
        }
        val spanStart = PcloCodec.V2Layout.chunkOffset(first)
        val headerRead = CompletableFuture.supplyAsync({ inner.downloadRange(objectKey, 0, PcloCodec.V2_HEADER) }, HEADER_READS)
        // #D17 review A: a span worked out for a v2 object of [objectSize] can start past the end of a v1 or shorter
        // object, which a transport answers with a 416 (a CloudHttpException, final to the reader), so no failure of
        // this read is judged before the header says what the object is.
        var chunkFailure: Exception? = null
        val chunks = try {
            inner.downloadRange(objectKey, spanStart, (spanEnd - spanStart).toInt())
        } catch (failure: Exception) {
            chunkFailure = failure
            null
        }
        val headerBytes = try {
            headerRead.join()
        } catch (failed: CompletionException) {
            val cause = failed.cause ?: failed
            if (cause is java.io.IOException || cause is IllegalArgumentException) return downloadRange(objectKey, offset, length)
            throw cause
        }
        val header = openedV2Header(objectKey, headerBytes) ?: return sliceOfWhole(objectKey, offset, length)
        if (header.plainSize != objectSize) return readChunks(objectKey, header, offset, length)
        if (chunks == null) {
            // The span was the right one: the transport's failure, reported the way readChunks reports it.
            val failure = checkNotNull(chunkFailure)
            if (failure is IllegalArgumentException) throw PcloFormatException("PCLO_TRUNCATED", failure)
            throw failure
        }
        return try {
            decryptSpan(header, chunks, bufferStart = spanStart, offset, length)
        } catch (_: PcloFormatException) {
            // #D17 review A: the header and the chunks can come from two uploads of this key when another device
            // re-sealed it between the two requests. Read again, header first, as the memoised path does (#C50).
            headers.forget(objectKey)
            downloadRange(objectKey, offset, length)
        }
    }

    /** The v2 header opening [bytes] (memoised under [objectKey]), or null when [bytes] does not start a v2 object. */
    private fun openedV2Header(objectKey: String, bytes: ByteArray): PcloCodec.V2Header? {
        if (bytes.size < PcloCodec.V2_HEADER || !PcloCodec.looksSealed(bytes) || bytes[4] != PcloCodec.FORMAT_VERSION_V2) {
            return null
        }
        return PcloCodec.openV2Header(dek, bytes.copyOfRange(0, PcloCodec.V2_HEADER)).also { headers.put(objectKey, it) }
    }

    /** The chunks covering plaintext `[offset, offset + length)` of a v2 object whose opened header is [header]. */
    private fun readChunks(objectKey: String, header: PcloCodec.V2Header, offset: Long, length: Int): ByteArray {
        require(offset + length <= header.plainSize) { "range past the end of the object" }
        val first = (offset / PcloCodec.V2_CHUNK).toInt()
        val last = ((offset + length - 1) / PcloCodec.V2_CHUNK).toInt()
        val start = PcloCodec.V2Layout.chunkSpan(header.plainSize, first).first
        val (lastStart, lastLen) = PcloCodec.V2Layout.chunkSpan(header.plainSize, last)
        val wanted = (lastStart + lastLen - start).toInt()
        // Review Sev2: a transport that cannot serve the whole span (a truncated object) is a format failure, not a
        // crash — the authenticated size says these bytes must exist.
        val cipher = try {
            inner.downloadRange(objectKey, start, wanted)
        } catch (error: IllegalArgumentException) {
            throw PcloFormatException("PCLO_TRUNCATED", error)
        }
        if (cipher.size != wanted) throw PcloFormatException("PCLO_TRUNCATED")
        return decryptSpan(header, cipher, bufferStart = start, offset, length)
    }

    /** Opens the chunks covering plaintext `[offset, offset + length)` out of [buffer], which starts at sealed byte [bufferStart]. */
    private fun decryptSpan(header: PcloCodec.V2Header, buffer: ByteArray, bufferStart: Long, offset: Long, length: Int): ByteArray {
        require(offset + length <= header.plainSize) { "range past the end of the object" }
        val chunk = PcloCodec.V2_CHUNK
        val first = (offset / chunk).toInt()
        val last = ((offset + length - 1) / chunk).toInt()
        val lastIndex = PcloCodec.V2Layout.chunkCount(header.plainSize) - 1
        val (lastStart, lastLen) = PcloCodec.V2Layout.chunkSpan(header.plainSize, last)
        if (bufferStart + buffer.size < lastStart + lastLen) throw PcloFormatException("PCLO_TRUNCATED")
        val out = ByteArray(length)
        var written = 0
        for (i in first..last) {
            val (cStart, cLen) = PcloCodec.V2Layout.chunkSpan(header.plainSize, i)
            val plain = header.openChunk(i.toLong(), i == lastIndex, buffer, (cStart - bufferStart).toInt(), cLen)
            val from = if (i == first) (offset - first.toLong() * chunk).toInt() else 0
            val take = minOf(plain.size - from, length - written)
            System.arraycopy(plain, from, out, written, take)
            written += take
        }
        return out
    }

    private fun sliceOfWhole(objectKey: String, offset: Long, length: Int): ByteArray {
        val whole = download(objectKey)
        require(offset + length <= whole.size) { "range past the end of the object" }
        return whole.copyOfRange(offset.toInt(), (offset + length).toInt())
    }

    override fun listKeys(): List<String> = inner.listKeys()

    override fun listKeys(prefix: String): List<String> = inner.listKeys(prefix)

    /**
     * #C51/#C67: listing passes through, but a v2 object's listed size is translated back to its PLAINTEXT size
     * (a v2 sealed size is an exact function of it), so the upload resume's presence-by-size recognises a pack it
     * already sealed and skips re-PUTting it. A size that fits no v2 object (v1, foreign) is reported unknown (-1),
     * which makes that pack re-PUT — correct, slower.
     */
    override fun listObjects(prefix: String): List<ObjectSummary> =
        inner.listObjects(prefix).map { summary ->
            if (summary.key == CloudCollectionKeys.lockKey() || summary.sizeBytes < 0) {
                summary
            } else {
                summary.copy(sizeBytes = PcloCodec.V2Layout.plainSizeOrNull(summary.sizeBytes) ?: -1L)
            }
        }

    /** Storage usage stays the SEALED bytes — what the bucket actually bills; only [listObjects] maps sizes back. */
    override fun listUsage(): R2BucketUsage = inner.listUsage()

    override fun delete(objectKey: String): Int {
        headers.forget(objectKey)
        return inner.delete(objectKey)
    }
}

private const val HEADER_BUNDLE_BYTES: Long = 4L * 1024 * 1024

/**
 * #D17: runs a v2 header read beside the chunk read of the same range — at most one per page read that finds no
 * memoised header, i.e. the first read of each pack. Daemon threads, made on demand and reaped when idle.
 */
private val HEADER_READS: ExecutorService =
    Executors.newCachedThreadPool { runnable -> Thread(runnable, "pclo-header").apply { isDaemon = true } }

/** #C50: a small LRU of opened v2 headers (≈ 100 B each), keyed by object key. */
class V2HeaderMemo(private val capacity: Int = 256) {
    private val map = object : LinkedHashMap<String, PcloCodec.V2Header>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PcloCodec.V2Header>?): Boolean = size > capacity
    }

    @Synchronized fun get(key: String): PcloCodec.V2Header? = map[key]

    @Synchronized fun put(key: String, header: PcloCodec.V2Header) {
        map[key] = header
    }

    @Synchronized fun forget(key: String) {
        map.remove(key)
    }
}
