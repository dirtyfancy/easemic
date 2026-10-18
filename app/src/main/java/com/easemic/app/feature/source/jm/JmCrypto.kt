package com.easemic.app.feature.source.jm

import android.annotation.SuppressLint
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.json.JSONTokener

/** Public protocol constants, separate from the independently tested AES primitive. */
internal object JmCrypto {
    const val DATA_SECRET = "185Hcomic3PAPP7R"
    const val TEMPLATE_SECRET = "18comicAPPContent"
    const val PUBLICATION_SECRET = "diosfjckwpqpdfjkvnqQjsik"
    const val VERSION = "2.1.7"
    fun md5Hex(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun token(seconds: Long, template: Boolean = false): String =
        md5Hex((seconds.toString() + if (template) TEMPLATE_SECRET else DATA_SECRET).toByteArray(Charsets.UTF_8))
    fun key(seconds: Long): ByteArray = token(seconds).toByteArray(Charsets.US_ASCII)
    // The source encrypts its responses with AES-ECB; this only reads them. Nothing of ours is encrypted this way.
    @SuppressLint("GetInstance")
    fun decryptBlock(key: ByteArray, ciphertext: ByteArray): ByteArray = try {
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(ciphertext)
        }
    } catch (_: Exception) { JmFailure.invalid() }

    fun decrypt(ciphertext: String, key: ByteArray): String = try {
        // No interior whitespace, MIME alphabet or partial block tolerance.
        if (ciphertext.isEmpty() || !Regex("[A-Za-z0-9+/]*={0,2}").matches(ciphertext) || ciphertext.length % 4 != 0) JmFailure.invalid()
        val bytes = Base64.getDecoder().decode(ciphertext)
        if (bytes.isEmpty() || bytes.size % 16 != 0) JmFailure.invalid()
        val padded = decryptBlock(key, bytes)
        val padding = padded.last().toInt() and 255
        if (padding !in 1..16 || padded.takeLast(padding).any { (it.toInt() and 255) != padding }) JmFailure.invalid()
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(padded, 0, padded.size - padding)).toString()
    } catch (_: Exception) { JmFailure.invalid() }

    fun publication(text: String): JSONObject {
        val base64 = text.trim().removePrefix("\uFEFF").trim()
        val key = md5Hex(PUBLICATION_SECRET.toByteArray(Charsets.UTF_8)).toByteArray(Charsets.US_ASCII)
        return objectJson(decrypt(base64, key))
    }
    fun objectJson(text: String): JSONObject = try {
        StrictJson(text).validate()
        val tokener = JSONTokener(text)
        val parsed = tokener.nextValue()
        if (parsed !is JSONObject || tokener.nextClean() != '\u0000') JmFailure.invalid()
        parsed
    } catch (_: Exception) { JmFailure.invalid() }

    /** org.json differs between Android and the JVM and accepts JavaScript syntax; validate RFC JSON first. */
    private class StrictJson(private val input: String) {
        private var at = 0
        fun validate() { value(0); whitespace(); if (at != input.length) JmFailure.invalid() }
        private fun whitespace() { while (at < input.length && input[at] in " \r\n\t") at++ }
        private fun peek(): Char = input.getOrNull(at) ?: '\u0000'
        private fun consume(char: Char) { whitespace(); if (peek() != char) JmFailure.invalid(); at++ }
        private fun value(depth: Int) {
            if (depth > 64) JmFailure.invalid()
            whitespace()
            when (peek()) {
                '{' -> {
                    at++; whitespace()
                    val keys = HashSet<String>()
                    if (peek() != '}') while (true) {
                        whitespace()
                        val start = at
                        string()
                        val key = JSONTokener(input.substring(start, at)).nextValue() as String
                        if (!keys.add(key)) JmFailure.invalid()
                        consume(':'); value(depth + 1); whitespace()
                        if (peek() != ',') break
                        at++
                    }
                    consume('}')
                }
                '[' -> {
                    at++; whitespace()
                    if (peek() != ']') while (true) {
                        value(depth + 1); whitespace()
                        if (peek() != ',') break
                        at++
                    }
                    consume(']')
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> JmFailure.invalid()
            }
        }
        private fun string() {
            consume('"')
            while (at < input.length) {
                when (val char = input[at++]) {
                    '"' -> return
                    '\\' -> when (input.getOrNull(at++)) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) {
                            val hex = input.getOrNull(at++) ?: JmFailure.invalid()
                            if (hex !in "0123456789abcdefABCDEF") JmFailure.invalid()
                        }
                        else -> JmFailure.invalid()
                    }
                    else -> if (char.code < 32) JmFailure.invalid()
                }
            }
            JmFailure.invalid()
        }
        private fun literal(value: String) {
            if (!input.startsWith(value, at)) JmFailure.invalid()
            at += value.length
        }
        private fun number() {
            if (peek() == '-') at++
            if (peek() == '0') at++ else digits()
            if (peek() == '.') { at++; digits() }
            if (peek() == 'e' || peek() == 'E') {
                at++
                if (peek() == '+' || peek() == '-') at++
                digits()
            }
        }
        private fun digits() {
            val start = at
            while (peek() in '0'..'9') at++
            if (start == at) JmFailure.invalid()
        }
    }
}
