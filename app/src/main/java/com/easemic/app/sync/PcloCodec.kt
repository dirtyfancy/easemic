package com.easemic.app.sync

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Sealed object could not be opened: not PCLO, truncated, tampered, or wrong DEK. */
class PcloFormatException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Content-object sealing for encrypted libraries under the library DEK. No per-object KDF — the keyslot
 * ([LibraryKeyslot]) already paid for one.
 *
 * v1: `PCLO` + 1 + 12-byte IV + AES-256-GCM(whole object). Still opened; no longer written.
 *
 * v2 (#C67, spec `2026-09-26-c67-pclo-v2-chunked-design.md`, after rclone crypt / Cryptomator / age):
 * `PCLO` + 2 + 12-byte header nonce + AES-GCM(DEK, header nonce, random 32-byte object key) [48 bytes], then the
 * plaintext in [V2_CHUNK] chunks, each AES-GCM(object key, nonce = 11-byte big-endian chunk index ‖ final flag,
 * AAD = header nonce) → ciphertext ‖ 16-byte tag. The index + final flag (age STREAM) make reordering and
 * truncation fail; the AAD binds every chunk to its own object (Cryptomator). Chunk `i` sits at a fixed ciphertext
 * offset, so a plaintext range is read by fetching the header and only the chunks covering it ([V2Layout]).
 */
object PcloCodec {
    private const val MAGIC0 = 'P'.code
    private const val MAGIC1 = 'C'.code
    private const val MAGIC2 = 'L'.code
    private const val MAGIC3 = 'O'.code
    const val FORMAT_VERSION: Byte = 1
    const val FORMAT_VERSION_V2: Byte = 2
    private const val IV_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val HEADER_LEN = 5 + IV_LEN

    const val V2_CHUNK: Int = 64 * 1024
    const val V2_TAG: Int = 16
    private const val OBJECT_KEY_LEN = 32
    /** Sealed header payload: the object key and the plaintext size (8 bytes, big-endian). */
    private const val HEADER_PAYLOAD = OBJECT_KEY_LEN + 8
    /** magic + version + header nonce + sealed (object key ‖ plaintext size). */
    const val V2_HEADER: Int = 5 + IV_LEN + HEADER_PAYLOAD + V2_TAG
    private val random = SecureRandom()

    fun seal(dek: ByteArray, plaintext: ByteArray): ByteArray {
        require(dek.size == 32) { "dek must be 256-bit" }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val ciphertext = gcm(Cipher.ENCRYPT_MODE, dek, iv, null, plaintext, "PCLO_SEAL_FAILED")
        return ByteArray(HEADER_LEN + ciphertext.size).also { out ->
            writeMagic(out, FORMAT_VERSION)
            System.arraycopy(iv, 0, out, 5, IV_LEN)
            System.arraycopy(ciphertext, 0, out, HEADER_LEN, ciphertext.size)
        }
    }

    fun sealV2(dek: ByteArray, plaintext: ByteArray): ByteArray {
        require(dek.size == 32) { "dek must be 256-bit" }
        val headerNonce = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val objectKey = ByteArray(OBJECT_KEY_LEN).also { random.nextBytes(it) }
        val payload = ByteBuffer.allocate(HEADER_PAYLOAD).put(objectKey).putLong(plaintext.size.toLong()).array()
        val sealedKey = gcm(Cipher.ENCRYPT_MODE, dek, headerNonce, null, payload, "PCLO_SEAL_FAILED")
        val chunks = V2Layout.chunkCount(plaintext.size.toLong())
        val out = ByteArray(V2Layout.sealedSize(plaintext.size.toLong()).toInt())
        writeMagic(out, FORMAT_VERSION_V2)
        System.arraycopy(headerNonce, 0, out, 5, IV_LEN)
        System.arraycopy(sealedKey, 0, out, 5 + IV_LEN, sealedKey.size)
        var pos = V2_HEADER
        for (i in 0 until chunks) {
            val from = i * V2_CHUNK
            val to = minOf(plaintext.size, from + V2_CHUNK)
            val ct = gcm(
                Cipher.ENCRYPT_MODE, objectKey, chunkNonce(i.toLong(), i == chunks - 1), headerNonce,
                plaintext.copyOfRange(from, to), "PCLO_SEAL_FAILED",
            )
            System.arraycopy(ct, 0, out, pos, ct.size)
            pos += ct.size
        }
        return out
    }

    /**
     * [sealV2] from disk to disk, one [V2_CHUNK] at a time — byte-identical layout, but memory stays at one chunk
     * instead of twice the object (owner phone 2026-09-27: 5 encrypted comic uploads x 6 lanes of 8-32 MiB packs, each
     * read whole and then copied whole by [sealV2], exhausted the 384 MB heap and crash-looped the app).
     */
    fun sealV2ToFile(dek: ByteArray, source: java.io.File, dest: java.io.File) {
        require(dek.size == 32) { "dek must be 256-bit" }
        val size = source.length()
        val headerNonce = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val objectKey = ByteArray(OBJECT_KEY_LEN).also { random.nextBytes(it) }
        val payload = ByteBuffer.allocate(HEADER_PAYLOAD).put(objectKey).putLong(size).array()
        val sealedKey = gcm(Cipher.ENCRYPT_MODE, dek, headerNonce, null, payload, "PCLO_SEAL_FAILED")
        val chunks = V2Layout.chunkCount(size)
        val head = ByteArray(5 + IV_LEN)
        writeMagic(head, FORMAT_VERSION_V2)
        System.arraycopy(headerNonce, 0, head, 5, IV_LEN)
        val buf = ByteArray(V2_CHUNK)
        source.inputStream().use { input ->
            java.io.BufferedOutputStream(dest.outputStream(), 1 shl 16).use { out ->
                out.write(head)
                out.write(sealedKey)
                for (i in 0 until chunks) {
                    var filled = 0
                    while (filled < V2_CHUNK) {
                        val n = input.read(buf, filled, V2_CHUNK - filled)
                        if (n < 0) break
                        filled += n
                    }
                    val expected = if (i == chunks - 1) (size - i.toLong() * V2_CHUNK).toInt() else V2_CHUNK
                    if (filled != expected) throw java.io.IOException("source changed while sealing")
                    out.write(
                        gcm(
                            Cipher.ENCRYPT_MODE, objectKey, chunkNonce(i.toLong(), i == chunks - 1), headerNonce,
                            buf.copyOf(filled), "PCLO_SEAL_FAILED",
                        ),
                    )
                }
            }
        }
    }

    fun open(dek: ByteArray, sealed: ByteArray): ByteArray {
        require(dek.size == 32) { "dek must be 256-bit" }
        if (!looksSealed(sealed)) throw PcloFormatException("PCLO_BAD_MAGIC")
        return when (sealed[4]) {
            FORMAT_VERSION -> openV1(dek, sealed)
            FORMAT_VERSION_V2 -> openV2(dek, sealed)
            else -> throw PcloFormatException("PCLO_UNSUPPORTED_VERSION")
        }
    }

    private fun openV1(dek: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size <= HEADER_LEN) throw PcloFormatException("PCLO_TRUNCATED")
        val iv = sealed.copyOfRange(5, HEADER_LEN)
        return gcm(Cipher.DECRYPT_MODE, dek, iv, null, sealed.copyOfRange(HEADER_LEN, sealed.size), "PCLO_OPEN_FAILED")
    }

    private fun openV2(dek: ByteArray, sealed: ByteArray): ByteArray {
        val header = openV2Header(dek, sealed)
        // The authenticated size fixes the exact length: a dropped or appended chunk is caught here.
        if (sealed.size.toLong() != V2Layout.sealedSize(header.plainSize)) throw PcloFormatException("PCLO_TRUNCATED")
        val out = ByteArray(header.plainSize.toInt())
        val chunks = V2Layout.chunkCount(header.plainSize)
        for (i in 0 until chunks) {
            val (start, len) = V2Layout.chunkSpan(header.plainSize, i)
            val plain = header.openChunk(i.toLong(), i == chunks - 1, sealed, start.toInt(), len)
            System.arraycopy(plain, 0, out, i * V2_CHUNK, plain.size)
        }
        return out
    }

    /** The opened v2 header: what a chunk-level reader needs to open chunks on its own. */
    class V2Header internal constructor(
        private val headerNonce: ByteArray,
        private val objectKey: ByteArray,
        /** The authenticated plaintext size. */
        val plainSize: Long,
    ) {
        fun openChunk(index: Long, final: Boolean, buf: ByteArray, off: Int, len: Int): ByteArray {
            if (len < V2_TAG || len > V2_CHUNK + V2_TAG) throw PcloFormatException("PCLO_TRUNCATED")
            return gcm(
                Cipher.DECRYPT_MODE, objectKey, chunkNonce(index, final), headerNonce,
                buf.copyOfRange(off, off + len), "PCLO_OPEN_FAILED",
            )
        }

        /** #E29: an opener for one read. See [ChunkOpener]. */
        fun chunkOpener(): ChunkOpener = ChunkOpener(objectKey, headerNonce)
    }

    /**
     * #E29: opens chunk after chunk into the caller's buffer with one cipher, so a page of many chunks allocates no
     * array per chunk ([V2Header.openChunk] copies the input and returns a new plaintext array each time). Not
     * thread-safe: one per read.
     */
    class ChunkOpener internal constructor(objectKey: ByteArray, private val headerNonce: ByteArray) {
        private val key = SecretKeySpec(objectKey, "AES")
        private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")

        /** Opens chunk [index] from `buf[off, off + len)` into [out] at 0 and returns the plaintext length. */
        fun open(index: Long, final: Boolean, buf: ByteArray, off: Int, len: Int, out: ByteArray): Int {
            if (len < V2_TAG || len > V2_CHUNK + V2_TAG) throw PcloFormatException("PCLO_TRUNCATED")
            return try {
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, chunkNonce(index, final)))
                cipher.updateAAD(headerNonce)
                cipher.doFinal(buf, off, len, out, 0)
            } catch (error: GeneralSecurityException) {
                throw PcloFormatException("PCLO_OPEN_FAILED", error)
            }
        }
    }

    /** Opens the first [V2_HEADER] bytes of a v2 object. */
    fun openV2Header(dek: ByteArray, headerBytes: ByteArray): V2Header {
        require(dek.size == 32) { "dek must be 256-bit" }
        if (headerBytes.size < V2_HEADER || !looksSealed(headerBytes) || headerBytes[4] != FORMAT_VERSION_V2) {
            throw PcloFormatException("PCLO_TRUNCATED")
        }
        val headerNonce = headerBytes.copyOfRange(5, 5 + IV_LEN)
        val payload = gcm(
            Cipher.DECRYPT_MODE, dek, headerNonce, null,
            headerBytes.copyOfRange(5 + IV_LEN, V2_HEADER), "PCLO_OPEN_FAILED",
        )
        val buf = ByteBuffer.wrap(payload)
        val objectKey = ByteArray(OBJECT_KEY_LEN).also { buf.get(it) }
        val plainSize = buf.long
        if (plainSize < 0) throw PcloFormatException("PCLO_OPEN_FAILED")
        return V2Header(headerNonce, objectKey, plainSize)
    }

    /** Ciphertext geometry of a v2 object; pure arithmetic, no keys. */
    object V2Layout {
        private const val SEALED_CHUNK = (V2_CHUNK + V2_TAG).toLong()

        /** An empty plaintext still has one (empty, final) chunk, so the final flag always authenticates. */
        fun chunkCount(plainSize: Long): Int = maxOf(1L, (plainSize + V2_CHUNK - 1) / V2_CHUNK).toInt()

        fun sealedSize(plainSize: Long): Long = V2_HEADER + plainSize + chunkCount(plainSize).toLong() * V2_TAG

        fun chunkOffset(index: Long): Long = V2_HEADER + index * SEALED_CHUNK

        /**
         * The plaintext size of a v2 object from its sealed size alone, or null when [sealedSize] is not the size of
         * any v2 object (a v1 object, a foreign file). Unauthenticated: a listing hint only (the upload resume's
         * presence-by-size), never a read decision.
         */
        fun plainSizeOrNull(sealedSize: Long): Long? {
            val body = sealedSize - V2_HEADER
            if (body < V2_TAG) return null
            val full = body / SEALED_CHUNK
            val rest = body % SEALED_CHUNK
            val plain = when {
                rest == 0L && full > 0 -> full * V2_CHUNK
                rest >= V2_TAG -> full * V2_CHUNK + (rest - V2_TAG)
                else -> return null
            }
            return plain.takeIf { sealedSize(it) == sealedSize }
        }

        /** Ciphertext (offset, length) of chunk [index] of an object of [plainSize] plaintext bytes. */
        fun chunkSpan(plainSize: Long, index: Int): Pair<Long, Int> {
            val plainLen = minOf(V2_CHUNK.toLong(), plainSize - index.toLong() * V2_CHUNK).coerceAtLeast(0L)
            return chunkOffset(index.toLong()) to (plainLen + V2_TAG).toInt()
        }
    }

    fun looksSealed(bytes: ByteArray): Boolean =
        bytes.size >= 5 &&
            bytes[0] == MAGIC0.toByte() &&
            bytes[1] == MAGIC1.toByte() &&
            bytes[2] == MAGIC2.toByte() &&
            bytes[3] == MAGIC3.toByte()

    private fun writeMagic(out: ByteArray, version: Byte) {
        out[0] = MAGIC0.toByte()
        out[1] = MAGIC1.toByte()
        out[2] = MAGIC2.toByte()
        out[3] = MAGIC3.toByte()
        out[4] = version
    }

    private fun chunkNonce(index: Long, final: Boolean): ByteArray =
        ByteBuffer.allocate(IV_LEN).apply {
            put(0) ; put(0) ; put(0) // bytes 0..2 of the 11-byte big-endian counter (a Long fills 3..10)
            putLong(index)
            put(if (final) 1 else 0)
        }.array()

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, aad: ByteArray?, input: ByteArray, failure: String): ByteArray =
        try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
                if (aad != null) updateAAD(aad)
                doFinal(input)
            }
        } catch (error: GeneralSecurityException) {
            throw PcloFormatException(failure, error)
        }
}
