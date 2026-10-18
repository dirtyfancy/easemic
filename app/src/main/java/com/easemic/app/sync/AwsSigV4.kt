package com.easemic.app.sync

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class AwsSigV4Signed(
    val authorization: String,
    val headers: Map<String, String>,
)

/**
 * AWS Signature Version 4 for S3-compatible storage. Does not persist secrets.
 */
object AwsSigV4 {
    const val UNSIGNED_PAYLOAD: String = "UNSIGNED-PAYLOAD"

    fun sign(
        method: String,
        url: String,
        accessKeyId: String,
        secretAccessKey: String,
        region: String,
        service: String,
        amzDate: String,
        payloadHash: String,
    ): AwsSigV4Signed {
        val uri = URI(url)
        val host = requireNotNull(uri.host) { "url host required" }
        val port = uri.port
        val hostHeader = if (port >= 0 && port != uri.toURL().defaultPort) "$host:$port" else host
        val path = uri.rawPath.ifBlank { "/" }
        // Query values in a URL are already escaped. Normalize components
        // once, then sort; encoding raw '%' again invalidates S3 signatures.
        val query = canonicalQuery(uri.rawQuery.orEmpty())
        val dateStamp = amzDate.take(8)
        val canonicalHeaders = buildString {
            append("host:").append(hostHeader).append('\n')
            append("x-amz-content-sha256:").append(payloadHash).append('\n')
            append("x-amz-date:").append(amzDate).append('\n')
        }
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalRequest = listOf(
            method.uppercase(),
            path,
            query,
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")
        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf(
            "AWS4-HMAC-SHA256",
            amzDate,
            credentialScope,
            hexSha256(canonicalRequest.toByteArray(Charsets.UTF_8)),
        ).joinToString("\n")
        val signingKey = hmac(
            hmac(
                hmac(
                    hmac("AWS4$secretAccessKey".toByteArray(Charsets.UTF_8), dateStamp),
                    region,
                ),
                service,
            ),
            "aws4_request",
        )
        val signature = hex(hmac(signingKey, stringToSign))
        val authorization =
            "AWS4-HMAC-SHA256 Credential=$accessKeyId/$credentialScope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"
        return AwsSigV4Signed(
            authorization = authorization,
            headers = mapOf(
                "host" to hostHeader,
                "x-amz-content-sha256" to payloadHash,
                "x-amz-date" to amzDate,
            ),
        )
    }

    private fun canonicalQuery(rawQuery: String): String {
        if (rawQuery.isEmpty()) return ""
        return rawQuery.split('&')
            .map { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                uriEncode(URLDecoder.decode(name, Charsets.UTF_8.name())) to
                    uriEncode(URLDecoder.decode(value, Charsets.UTF_8.name()))
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (name, value) -> "$name=$value" }
    }

    private fun uriEncode(value: String): String =
        buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt().toChar()
                if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') ||
                    c == '-' || c == '_' || c == '.' || c == '~'
                ) {
                    append(c)
                } else {
                    append('%')
                    append(String.format("%02X", byte))
                }
            }
        }

    /** Lowercase hex SHA-256, for callers that sign a real payload hash. */
    fun sha256Hex(bytes: ByteArray): String = hexSha256(bytes)

    /** SHA-256 of a file's bytes, streamed — a comic's pages never all sit in memory at once. */
    fun sha256HexOfFile(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hexSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return hex(digest)
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { b -> "%02x".format(b) }
}
