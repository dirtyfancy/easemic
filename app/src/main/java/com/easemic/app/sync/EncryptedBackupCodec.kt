package com.easemic.app.sync

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class EncryptedBackupException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Passphrase-sealed backup package: PCLB / v1 / salt / iv / AES-GCM ciphertext.
 * Key material never persisted; callers clear char arrays when done.
 */
object EncryptedBackupCodec {
    private const val MAGIC0 = 'P'.code
    private const val MAGIC1 = 'C'.code
    private const val MAGIC2 = 'L'.code
    private const val MAGIC3 = 'B'.code
    const val FORMAT_VERSION: Byte = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val KEY_LEN_BITS = 256
    private const val ITERATIONS = 210_000
    private const val GCM_TAG_BITS = 128
    private const val HEADER_LEN = 5 + SALT_LEN + IV_LEN

    fun seal(passphrase: CharArray, plaintext: ByteArray): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase required" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val ciphertext = cipher.doFinal(plaintext)
            ByteArray(HEADER_LEN + ciphertext.size).also { out ->
                out[0] = MAGIC0.toByte()
                out[1] = MAGIC1.toByte()
                out[2] = MAGIC2.toByte()
                out[3] = MAGIC3.toByte()
                out[4] = FORMAT_VERSION
                System.arraycopy(salt, 0, out, 5, SALT_LEN)
                System.arraycopy(iv, 0, out, 5 + SALT_LEN, IV_LEN)
                System.arraycopy(ciphertext, 0, out, HEADER_LEN, ciphertext.size)
            }
        } catch (error: GeneralSecurityException) {
            throw EncryptedBackupException("BACKUP_SEAL_FAILED", error)
        }
    }

    fun open(passphrase: CharArray, sealed: ByteArray): ByteArray {
        if (sealed.size <= HEADER_LEN) {
            throw EncryptedBackupException("BACKUP_TRUNCATED")
        }
        if (
            sealed[0] != MAGIC0.toByte() ||
            sealed[1] != MAGIC1.toByte() ||
            sealed[2] != MAGIC2.toByte() ||
            sealed[3] != MAGIC3.toByte()
        ) {
            throw EncryptedBackupException("BACKUP_BAD_MAGIC")
        }
        if (sealed[4] != FORMAT_VERSION) {
            throw EncryptedBackupException("BACKUP_UNSUPPORTED_VERSION")
        }
        val salt = sealed.copyOfRange(5, 5 + SALT_LEN)
        val iv = sealed.copyOfRange(5 + SALT_LEN, HEADER_LEN)
        val ciphertext = sealed.copyOfRange(HEADER_LEN, sealed.size)
        val key = deriveKey(passphrase, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (error: GeneralSecurityException) {
            throw EncryptedBackupException("BACKUP_OPEN_FAILED", error)
        }
    }

    fun sealToFile(passphrase: CharArray, plaintextFile: File, sealedFile: File) {
        require(passphrase.isNotEmpty()) { "passphrase required" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        try {
            FileOutputStream(sealedFile).use { fos ->
                fos.write(byteArrayOf(
                    MAGIC0.toByte(),
                    MAGIC1.toByte(),
                    MAGIC2.toByte(),
                    MAGIC3.toByte(),
                    FORMAT_VERSION,
                ))
                fos.write(salt)
                fos.write(iv)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                CipherOutputStream(fos, cipher).use { cos ->
                    FileInputStream(plaintextFile).use { input -> input.copyTo(cos) }
                }
            }
        } catch (error: GeneralSecurityException) {
            throw EncryptedBackupException("BACKUP_SEAL_FAILED", error)
        }
    }

    fun openFromFile(passphrase: CharArray, sealedFile: File, plaintextFile: File) {
        require(passphrase.isNotEmpty()) { "passphrase required" }
        try {
            FileInputStream(sealedFile).use { fis ->
                val header = ByteArray(HEADER_LEN)
                val read = fis.read(header)
                if (read < HEADER_LEN) {
                    throw EncryptedBackupException("BACKUP_TRUNCATED")
                }
                if (
                    header[0] != MAGIC0.toByte() ||
                    header[1] != MAGIC1.toByte() ||
                    header[2] != MAGIC2.toByte() ||
                    header[3] != MAGIC3.toByte()
                ) {
                    throw EncryptedBackupException("BACKUP_BAD_MAGIC")
                }
                if (header[4] != FORMAT_VERSION) {
                    throw EncryptedBackupException("BACKUP_UNSUPPORTED_VERSION")
                }
                val salt = header.copyOfRange(5, 5 + SALT_LEN)
                val iv = header.copyOfRange(5 + SALT_LEN, HEADER_LEN)
                val key = deriveKey(passphrase, salt)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                CipherInputStream(fis, cipher).use { cis ->
                    FileOutputStream(plaintextFile).use { out -> cis.copyTo(out) }
                }
            }
        } catch (error: EncryptedBackupException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw EncryptedBackupException("BACKUP_OPEN_FAILED", error)
        } catch (error: java.io.IOException) {
            throw EncryptedBackupException("BACKUP_OPEN_FAILED", error)
        }
    }

    /** #D7: shared with `sync/archive/ArchiveCipher` so both files derive their key the same way. */
    internal fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_LEN_BITS)
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
