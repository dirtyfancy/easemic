package com.easemic.app.feature.bridge

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

fun interface CloudflareOAuthHttp {
    fun postForm(url: String, fields: Map<String, String>): String
}

data class CloudflarePkceSession(
    val authorizationUri: String,
    val codeVerifier: String,
    val state: String,
) {
    override fun toString(): String =
        "CloudflarePkceSession(authorizationUri=$authorizationUri, " +
            "codeVerifier=<redacted>, state=$state)"
}

data class CloudflareOAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long?,
) {
    override fun toString(): String =
        "CloudflareOAuthTokens(accessToken=<redacted>, refreshToken=<redacted>, " +
            "expiresInSeconds=$expiresInSeconds)"
}

enum class CloudflareOAuthFailure {
    MISSING_CODE,
    STATE_MISMATCH,
    TOKEN_RESPONSE_INVALID,

    /** The server rejected the grant itself (`{"error":"invalid_grant"}`): the refresh token is revoked or spent. Terminal until a new bind. */
    INVALID_GRANT,
}

sealed interface CloudflareOAuthExchangeResult {
    data class Success(val tokens: CloudflareOAuthTokens) : CloudflareOAuthExchangeResult

    data class Failed(val failure: CloudflareOAuthFailure) : CloudflareOAuthExchangeResult
}

/**
 * Authorization Code + PKCE (RFC 7636 S256). HTTP is injected so JVM tests
 * never talk to Cloudflare. [clientId] is a constructor argument so tests
 * can pass a fixture id; production uses [CloudflareOAuthConfig.CLIENT_ID].
 */
class CloudflarePkceClient(
    private val clientId: String,
    private val http: CloudflareOAuthHttp = defaultHttp(),
    private val randomBytes: () -> ByteArray = { defaultEntropy() },
) {
    fun begin(): CloudflarePkceSession {
        val codeVerifier = base64Url(randomBytes())
        val state = base64Url(randomBytes())
        val challenge = s256Challenge(codeVerifier)
        val query = listOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to CloudflareOAuthConfig.REDIRECT_URI,
            "scope" to CloudflareOAuthConfig.SCOPES,
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        ).joinToString("&") { (name, value) ->
            "${formEncode(name)}=${formEncode(value)}"
        }
        return CloudflarePkceSession(
            authorizationUri = "${CloudflareOAuthConfig.AUTHORIZE_URL}?$query",
            codeVerifier = codeVerifier,
            state = state,
        )
    }

    fun exchange(
        code: String,
        state: String,
        session: CloudflarePkceSession,
    ): CloudflareOAuthExchangeResult {
        val trimmedCode = code.trim()
        if (trimmedCode.isEmpty()) {
            return CloudflareOAuthExchangeResult.Failed(CloudflareOAuthFailure.MISSING_CODE)
        }
        if (state != session.state) {
            return CloudflareOAuthExchangeResult.Failed(CloudflareOAuthFailure.STATE_MISMATCH)
        }
        val body = http.postForm(
            CloudflareOAuthConfig.TOKEN_URL,
            mapOf(
                "grant_type" to "authorization_code",
                "code" to trimmedCode,
                "redirect_uri" to CloudflareOAuthConfig.REDIRECT_URI,
                "client_id" to clientId,
                "code_verifier" to session.codeVerifier,
            ),
        )
        return parseTokenResponse(body, fallbackRefreshToken = null)
    }

    fun refresh(refreshToken: String): CloudflareOAuthExchangeResult {
        val trimmed = refreshToken.trim()
        if (trimmed.isEmpty()) {
            return CloudflareOAuthExchangeResult.Failed(CloudflareOAuthFailure.MISSING_CODE)
        }
        val body = http.postForm(
            CloudflareOAuthConfig.TOKEN_URL,
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to trimmed,
                "client_id" to clientId,
            ),
        )
        return parseTokenResponse(body, fallbackRefreshToken = trimmed)
    }

    private fun parseTokenResponse(
        body: String,
        fallbackRefreshToken: String?,
    ): CloudflareOAuthExchangeResult {
        return try {
            val json = JSONObject(body)
            if (json.optString("error") == "invalid_grant") {
                return CloudflareOAuthExchangeResult.Failed(CloudflareOAuthFailure.INVALID_GRANT)
            }
            val accessToken = json.optString("access_token").trim()
            val refreshToken = json.optString("refresh_token").trim()
                .ifEmpty { fallbackRefreshToken?.trim().orEmpty() }
            if (accessToken.isEmpty() || refreshToken.isEmpty()) {
                return CloudflareOAuthExchangeResult.Failed(CloudflareOAuthFailure.TOKEN_RESPONSE_INVALID)
            }
            val expiresInSeconds = if (json.has("expires_in") && !json.isNull("expires_in")) {
                json.getLong("expires_in")
            } else {
                null
            }
            CloudflareOAuthExchangeResult.Success(
                CloudflareOAuthTokens(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresInSeconds = expiresInSeconds,
                ),
            )
        } catch (_: Exception) {
            CloudflareOAuthExchangeResult.Failed(CloudflareOAuthFailure.TOKEN_RESPONSE_INVALID)
        }
    }

    companion object {
        fun defaultHttp(): CloudflareOAuthHttp = CloudflareOAuthHttp { url, fields ->
            val encoded = fields.entries.joinToString("&") { (name, value) ->
                "${formEncode(name)}=${formEncode(value)}"
            }.toByteArray(StandardCharsets.UTF_8)
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            try {
                connection.outputStream.use { it.write(encoded) }
                val status = connection.responseCode
                val stream = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: connection.inputStream
                }
                String(stream?.readBytes() ?: ByteArray(0), StandardCharsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }

        private fun defaultEntropy(): ByteArray {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return bytes
        }

        private fun s256Challenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
            return base64Url(digest)
        }

        private fun base64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        private fun formEncode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}
