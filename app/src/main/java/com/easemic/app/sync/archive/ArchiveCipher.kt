package com.easemic.app.sync.archive

import com.easemic.app.sync.EncryptedBackupCodec
import com.easemic.app.sync.EncryptedBackupException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * #D7: the `.pclarchive` seal. A multi-GB archive cannot be one AES-GCM stream: measured 2026-09-27, GCM decrypt via
 * `CipherInputStream` holds the whole ciphertext before it returns a byte (JDK `GaloisCounterMode`, and Conscrypt's
 * AEAD waits for `doFinal`), so restore would need the whole file in memory. Instead the plaintext is sealed in
 * [CHUNK] pieces (the STREAM construction PCLO v2 already uses):
 *
 * `"PCLA" | v1 | salt 16 | nonce prefix 7 |` then per chunk `AES-256-GCM(chunk) ‖ tag 16`, nonce = prefix ‖ 4-byte
 * big-endian chunk index ‖ final flag, AAD = the 28-byte header. Every chunk is full except the last, which carries the
 * final flag (an empty plaintext is one empty final chunk). A cut at a chunk boundary fails because the last chunk
 * read is not marked final; reordering fails on the index; a changed byte fails its tag. Key: PBKDF2-HMAC-SHA256 210k
 * over the user's password, the same derivation as [EncryptedBackupCodec].
 */
object ArchiveCipher {
    const val CHUNK: Int = 1 shl 20
    const val TAG: Int = 16
    private const val VERSION: Byte = 1
    private const val SALT_LEN = 16
    private const val PREFIX_LEN = 7
    private const val HEADER_LEN = 5 + SALT_LEN + PREFIX_LEN
    private val MAGIC = "PCLA".toByteArray(Charsets.US_ASCII)

    private fun nonce(prefix: ByteArray, index: Int, final: Boolean): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(index).put(if (final) 1 else 0).array()

    fun sealingStream(passphrase: CharArray, out: OutputStream): OutputStream {
        require(passphrase.isNotEmpty()) { "passphrase required" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val prefix = ByteArray(PREFIX_LEN).also { random.nextBytes(it) }
        val header = MAGIC + byteArrayOf(VERSION) + salt + prefix
        val key = EncryptedBackupCodec.deriveKey(passphrase, salt)
        out.write(header)
        return SealingStream(out, key, prefix, header)
    }

    fun openingStream(passphrase: CharArray, input: InputStream): InputStream {
        require(passphrase.isNotEmpty()) { "passphrase required" }
        val header = ByteArray(HEADER_LEN)
        if (readFully(input, header, 0, HEADER_LEN) < HEADER_LEN) throw EncryptedBackupException("BACKUP_TRUNCATED")
        if (!header.copyOf(4).contentEquals(MAGIC)) throw EncryptedBackupException("BACKUP_BAD_MAGIC")
        if (header[4] != VERSION) throw EncryptedBackupException("BACKUP_UNSUPPORTED_VERSION")
        val salt = header.copyOfRange(5, 5 + SALT_LEN)
        val prefix = header.copyOfRange(5 + SALT_LEN, HEADER_LEN)
        return OpeningStream(input, EncryptedBackupCodec.deriveKey(passphrase, salt), prefix, header)
    }

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var got = 0
        while (got < len) {
            val n = input.read(buf, off + got, len - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    private class SealingStream(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
        private val header: ByteArray,
    ) : OutputStream() {
        private val buf = ByteArray(CHUNK)
        private var filled = 0
        private var index = 0
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "closed" }
            var o = off
            var remaining = len
            while (remaining > 0) {
                // A full buffer is sealed only once more data arrives, so the LAST chunk is always the one close() marks final.
                if (filled == CHUNK) emit(final = false)
                val n = minOf(remaining, CHUNK - filled)
                System.arraycopy(b, o, buf, filled, n)
                filled += n
                o += n
                remaining -= n
            }
        }

        private fun emit(final: Boolean) {
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(prefix, index, final)))
                cipher.updateAAD(header)
                out.write(cipher.doFinal(buf, 0, filled))
            } catch (e: GeneralSecurityException) {
                throw IOException("BACKUP_SEAL_FAILED", e)
            }
            index += 1
            filled = 0
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            try {
                emit(final = true)
                out.flush()
            } finally {
                closed = true
                out.close()
            }
        }
    }

    private class OpeningStream(
        private val input: InputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
        private val header: ByteArray,
    ) : InputStream() {
        // One sealed chunk plus one byte of look-ahead: a chunk is final exactly when nothing follows it.
        private val sealed = ByteArray(CHUNK + TAG + 1)
        private var sealedLen = 0
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var done = false

        private fun nextChunk(): Boolean {
            if (done) return false
            val need = CHUNK + TAG + 1
            sealedLen += readFully(input, sealed, sealedLen, need - sealedLen)
            val final = sealedLen < need
            val chunkLen = if (final) sealedLen else CHUNK + TAG
            if (chunkLen < TAG) throw EncryptedBackupException("BACKUP_TRUNCATED")
            plain = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG * 8, nonce(prefix, index, final)))
                cipher.updateAAD(header)
                cipher.doFinal(sealed, 0, chunkLen)
            } catch (e: GeneralSecurityException) {
                throw EncryptedBackupException(if (index == 0) "BACKUP_OPEN_FAILED" else "BACKUP_DAMAGED", e)
            }
            if (final) {
                done = true
                sealedLen = 0
            } else {
                // Keep the look-ahead byte as the start of the next chunk.
                sealed[0] = sealed[CHUNK + TAG]
                sealedLen = 1
            }
            index += 1
            pos = 0
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) {
                if (!nextChunk()) return -1
            }
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = input.close()
    }
}
