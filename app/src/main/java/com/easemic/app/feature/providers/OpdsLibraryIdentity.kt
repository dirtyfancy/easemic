package com.easemic.app.feature.providers

import java.net.URI

object OpdsLibraryIdentity {
    private val KAVITA_AUTH_KEY = Regex("(?i)/api/opds/[^/]+")
    private val AUTH_QUERY_KEYS = setOf("apikey", "access_token", "token")

    fun of(catalogUrl: String, username: String = ""): String {
        val user = username.trim().lowercase()
        val trimmed = catalogUrl.trim()
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
                return "${trimmed.lowercase()}|$user"
            }
            val port = when {
                uri.port < 0 -> ""
                scheme == "http" && uri.port == 80 -> ""
                scheme == "https" && uri.port == 443 -> ""
                else -> ":${uri.port}"
            }
            var path = (uri.rawPath ?: "").replace(KAVITA_AUTH_KEY, "/api/opds")
            if (path.length > 1) path = path.trimEnd('/')
            val query = remainingQuery(uri.rawQuery)
            val pathAndQuery = if (query.isEmpty()) path else "$path?$query"
            "$scheme://$host$port$pathAndQuery|$user"
        } catch (_: Exception) {
            "${trimmed.lowercase()}|$user"
        }
    }

    private fun remainingQuery(rawQuery: String?): String {
        if (rawQuery.isNullOrBlank()) return ""
        return rawQuery.split("&")
            .filter { part ->
                val key = part.substringBefore("=").lowercase()
                key.isNotEmpty() && key !in AUTH_QUERY_KEYS
            }
            .joinToString("&")
    }
}
