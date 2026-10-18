package com.easemic.app.feature.bridge

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class CloudflareOAuthCallback(
    val code: String,
    val state: String,
)

/** App custom-scheme bounce. Never log the URI or query. */
sealed class CloudflareOAuthCallbackResult {
    data class Success(val code: String, val state: String) : CloudflareOAuthCallbackResult()
    data class Failure(val error: String, val description: String?) : CloudflareOAuthCallbackResult()
}

/** Parses the app custom-scheme bounce. Never log the URI or query. */
object CloudflareOAuthCallbackParser {
    const val SCHEME = "com.easemic.app"
    const val HOST = "cf"
    const val PATH = "/oauth/callback"

    fun parse(uri: String): CloudflareOAuthCallback? =
        when (val result = parseResult(uri)) {
            is CloudflareOAuthCallbackResult.Success ->
                CloudflareOAuthCallback(result.code, result.state)
            else -> null
        }

    fun parseResult(uri: String): CloudflareOAuthCallbackResult? {
        val parsed = try {
            URI(uri)
        } catch (_: Exception) {
            return null
        }
        if (parsed.scheme != SCHEME) return null
        if (parsed.host != HOST) return null
        if (parsed.path != PATH) return null
        val rawQuery = parsed.rawQuery ?: return null
        val params = rawQuery.split("&").mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@mapNotNull null
            val name = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8.name())
            val value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name())
            name to value
        }.toMap()
        val error = params["error"]?.trim().orEmpty()
        if (error.isNotEmpty()) {
            val description = params["error_description"]?.trim()?.takeIf { it.isNotEmpty() }
            return CloudflareOAuthCallbackResult.Failure(error = error, description = description)
        }
        val code = params["code"]?.trim().orEmpty()
        val state = params["state"]?.trim().orEmpty()
        if (code.isEmpty() || state.isEmpty()) return null
        return CloudflareOAuthCallbackResult.Success(code = code, state = state)
    }
}
