package com.easemic.app.sync

import org.json.JSONException
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Unwrap failed its GCM integrity check: the password is wrong, with certainty. */
class WrongLibraryPasswordException(cause: Throwable? = null) :
    Exception("WRONG_LIBRARY_PASSWORD", cause)

/** The keyslot object itself is unreadable — never the same thing as a wrong password. */
class DamagedLibraryLockException(cause: Throwable? = null) :
    Exception(MESSAGE, cause) {
    companion object {
        const val MESSAGE = "This library's key file is damaged."
    }
}

/** Not a data class: ByteArray equals/hashCode would be reference-based noise. */
class CreatedKeyslot(val dek: ByteArray, val lockJson: String)

/**
 * `library.lock` keyslot (spec §4.1): a random 256-bit DEK wrapped by
 * AES-256-GCM under a PBKDF2 KEK. Password changes re-wrap the DEK — one
 * object write — so per-device cached DEKs stay valid. The lock object is
 * plaintext JSON; its presence is what marks a library encrypted.
 */
object LibraryKeyslot {
    const val KIND = "pcl-keyslot"
    const val VERSION = CloudFormatVersions.KEYSLOT
    const val KDF = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 210_000

    /** Read-side sanity cap; leaves headroom for future write-side raises. */
    const val MAX_ITERATIONS = 2_000_000
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val DEK_LEN = 32
    private const val KEY_LEN_BITS = 256
    private const val GCM_TAG_BITS = 128

    fun create(password: CharArray): CreatedKeyslot {
        require(password.isNotEmpty()) { "password required" }
        val random = SecureRandom()
        val dek = ByteArray(DEK_LEN).also { random.nextBytes(it) }
        return CreatedKeyslot(dek = dek, lockJson = wrap(password, dek, random))
    }

    fun unlock(password: CharArray, lockJson: String): ByteArray {
        val slot = parse(lockJson)
        val kek = deriveKek(password, slot.salt, slot.iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, slot.wrapIv))
            cipher.doFinal(slot.wrappedDek)
        } catch (error: GeneralSecurityException) {
            throw WrongLibraryPasswordException(error)
        }
    }

    fun changePassword(
        oldPassword: CharArray,
        newPassword: CharArray,
        lockJson: String,
    ): String {
        require(newPassword.isNotEmpty()) { "password required" }
        val dek = unlock(oldPassword, lockJson)
        return wrap(newPassword, dek, SecureRandom())
    }

    /** Cheap parse-only integrity check — no KDF. Throws DamagedLibraryLockException. */
    fun validate(lockJson: String) { parse(lockJson) }

    /** Identity tag of a DEK, as stored in the lock's dek_id_b64. */
    fun dekId(dek: ByteArray): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(dek).copyOfRange(0, 8),
        )

    /** The lock's stored DEK identity. Throws DamagedLibraryLockException. */
    fun dekIdFrom(lockJson: String): String = parse(lockJson).dekId

    private fun wrap(password: CharArray, dek: ByteArray, random: SecureRandom): String {
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val kek = deriveKek(password, salt, ITERATIONS)
        val wrapped = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(dek)
        } catch (error: GeneralSecurityException) {
            throw DamagedLibraryLockException(error)
        }
        val b64 = Base64.getEncoder()
        return JSONObject()
            .put("v", VERSION)
            .put("kind", KIND)
            .put("kdf", KDF)
            .put("iterations", ITERATIONS)
            .put("salt_b64", b64.encodeToString(salt))
            .put("wrap_iv_b64", b64.encodeToString(iv))
            .put("wrapped_dek_b64", b64.encodeToString(wrapped))
            .put("dek_id_b64", dekId(dek))
            .toString()
    }

    private class Slot(
        val salt: ByteArray,
        val wrapIv: ByteArray,
        val wrappedDek: ByteArray,
        val iterations: Int,
        val dekId: String,
    )

    private fun parse(lockJson: String): Slot {
        val json = try {
            JSONObject(lockJson)
        } catch (error: JSONException) {
            throw DamagedLibraryLockException(error)
        }
        val v = json.optInt("v", 0)
        if (v > VERSION && json.optString("kind") == KIND) {
            throw NewerCloudFormatException("library lock", v)
        }
        if (v != VERSION || json.optString("kind") != KIND) {
            throw DamagedLibraryLockException()
        }
        val iterations = json.optInt("iterations", 0)
        // The iteration count comes from the (tamperable) lock object; an
        // absurd value would hang unlock() in PBKDF2 instead of failing fast.
        if (iterations < 1 || iterations > MAX_ITERATIONS) throw DamagedLibraryLockException()
        val dekId = json.optString("dek_id_b64")
        if (dekId.isEmpty()) throw DamagedLibraryLockException()
        val b64 = Base64.getDecoder()
        return try {
            Slot(
                salt = b64.decode(json.getString("salt_b64")),
                wrapIv = b64.decode(json.getString("wrap_iv_b64")),
                wrappedDek = b64.decode(json.getString("wrapped_dek_b64")),
                iterations = iterations,
                dekId = dekId,
            )
        } catch (error: Exception) {
            throw DamagedLibraryLockException(error)
        }
    }

    private fun deriveKek(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(KDF)
        val spec = PBEKeySpec(password, salt, iterations, KEY_LEN_BITS)
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
