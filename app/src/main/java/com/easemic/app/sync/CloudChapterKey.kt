package com.easemic.app.sync

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val CHAPTER_KEY_LABEL = "easemic.chapter-key.v1".toByteArray(StandardCharsets.UTF_8)

val PLAINTEXT_CHAPTER_KEY_MATERIAL: ByteArray = CHAPTER_KEY_LABEL.copyOf()

fun encryptedChapterKeyMaterial(dek: ByteArray): ByteArray =
    hmacSha256(dek, CHAPTER_KEY_LABEL)

fun safeChapterKey(
    seriesCloudId: String,
    providerChapterId: String,
    keyMaterial: ByteArray,
): String {
    val message = (seriesCloudId + "\u0000" + providerChapterId).toByteArray(StandardCharsets.UTF_8)
    return hmacSha256(keyMaterial, message)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        .take(32)
}

private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(key, "HmacSHA256"))
    }.doFinal(message)
