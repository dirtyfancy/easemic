package com.easemic.app.feature.providers

import com.easemic.app.importing.opds.OpdsCatalogClient
import com.easemic.app.importing.opds.OpdsCatalogException
import com.easemic.app.importing.opds.OpdsFeed
import com.easemic.app.importing.opds.OpdsHttpResponse
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * A48: the owner pastes a server address and, at most, a username and a
 * password. Real OPDS servers hide their catalog behind three different
 * shapes, and the app used to fetch exactly the pasted string and give up:
 *
 *  - anonymous: the pasted URL already is the feed (works, must keep working)
 *  - HTTP Basic: the server answers 401 and wants the credentials (Komga)
 *  - token exchange: a login endpoint trades the credentials for a key that
 *    then belongs in the feed path (Kavita, whose key lives in the URL)
 *
 * plus entry-point discovery, because the address a human pastes is the
 * server root far more often than the catalog path.
 *
 * The flavor is detected from the RESPONSE, never from a user-visible picker.
 * Every candidate is validated by PARSING the body: measured against the three
 * live lab servers, both Kavita and Ubooquity answer unknown paths with
 * HTTP 200 text/html, so a status code alone would happily "discover" a web
 * page and store it as a catalog.
 */
fun interface OpdsLoginPost {
    fun post(url: String, jsonBody: String, authorizationHeader: String?): OpdsHttpResponse
}

sealed class OpdsFeedResolution {
    /** [catalogUrl] is the URL that actually served the feed; persist that one. */
    data class Resolved(val catalogUrl: String, val feed: OpdsFeed) : OpdsFeedResolution()
    data class Failed(val failure: OpdsConnectResult.Failure) : OpdsFeedResolution()
}

object OpdsConnectDiscovery {

    /**
     * Conventional catalog entry points, in the order they are tried. Ordering
     * is by specificity: a path that only one server flavor publishes is safer
     * to accept than a bare `/opds`, which several flavors reuse.
     */
    val CANDIDATE_PATHS: List<String> = listOf(
        "/opds/v1.2/catalog", // Komga
        "/opds-comics", // Ubooquity comics root
        "/opds/v1.2", // Komga variants
        "/opds", // Ubooquity root, generic
        "/api/opds", // Kavita without a key: 404s, but cheap to confirm
    )

    private const val KAVITA_LOGIN_PATH = "/api/Account/login"
    private const val KAVITA_OPDS_PREFIX = "/api/opds/"

    fun resolve(
        catalogUrl: String,
        username: String,
        password: String,
        client: OpdsCatalogClient,
        loginPost: OpdsLoginPost = defaultLoginPost(),
    ): OpdsFeedResolution {
        val pasted = catalogUrl.trim().trimEnd('/').ifEmpty { catalogUrl.trim() }
        if (pasted.isEmpty()) return OpdsFeedResolution.Failed(OpdsConnectResult.Unreachable)
        val user = username.trim()
        val attempts = Attempts()

        attempts.tryFeed(pasted, user, password, client)?.let { return it }
        // A server demanding credentials we do not have is a dead end: no other
        // path can be unlocked by material the owner never supplied. Say so now
        // instead of hammering five more paths to reach the same conclusion.
        if (attempts.refusedWithNothingToRetry(user)) {
            return OpdsFeedResolution.Failed(attempts.verdict(credentialsSupplied = false))
        }

        // Token exchange before path guessing: Kavita's catalog does not exist
        // at any fixed path, it exists at /api/opds/<key from the login call>.
        if (user.isNotEmpty()) {
            exchangeToken(pasted, user, password, loginPost, attempts)?.let { key ->
                val keyed = origin(pasted) + KAVITA_OPDS_PREFIX + key
                attempts.tryFeed(keyed, user, password, client)?.let { return it }
            }
        }

        for (base in bases(pasted)) {
            for (path in CANDIDATE_PATHS) {
                val candidate = base + path
                if (candidate == pasted) continue
                attempts.tryFeed(candidate, user, password, client)?.let { return it }
            }
        }
        return OpdsFeedResolution.Failed(attempts.verdict(credentialsSupplied = user.isNotEmpty()))
    }

    /**
     * Accumulates what each probe proved, so the final message names the real
     * cause instead of the last thing that happened to fail.
     */
    private class Attempts {
        private var authRefusedCode: Int? = null
        private var notAFeedCode: Int? = null
        private var serverErrorCode: Int? = null
        private var anythingAnswered = false
        var loginRefusedCode: Int? = null
            private set

        fun noteLoginRefused(code: Int) {
            anythingAnswered = true
            loginRefusedCode = loginRefusedCode ?: code
        }

        fun tryFeed(
            url: String,
            username: String,
            password: String,
            client: OpdsCatalogClient,
        ): OpdsFeedResolution.Resolved? = try {
            val feed = client.fetchFeed(url, username.ifBlank { null }, password.ifBlank { null })
            OpdsFeedResolution.Resolved(url, feed)
        } catch (error: OpdsCatalogException) {
            anythingAnswered = true
            when (error) {
                is OpdsCatalogException.Parse -> notAFeedCode = notAFeedCode ?: 200
                is OpdsCatalogException.Http -> when (error.code) {
                    401, 403 -> authRefusedCode = authRefusedCode ?: error.code
                    404, 410 -> notAFeedCode = notAFeedCode ?: error.code
                    else -> serverErrorCode = serverErrorCode ?: error.code
                }
            }
            null
        } catch (_: Exception) {
            null
        }

        /** True when the server asked for credentials and we have none to offer. */
        fun refusedWithNothingToRetry(username: String): Boolean =
            username.isEmpty() && authRefusedCode != null

        fun verdict(credentialsSupplied: Boolean): OpdsConnectResult.Failure {
            authRefusedCode?.let { return OpdsConnectResult.AuthRejected(it) }
            // A login endpoint refusing the credentials only counts when the
            // owner actually supplied some; Komga answers 401 on that path
            // simply because it is not a login endpoint there at all.
            if (credentialsSupplied) {
                loginRefusedCode?.let { return OpdsConnectResult.AuthRejected(it) }
            }
            notAFeedCode?.let { return OpdsConnectResult.NotAFeed(it) }
            serverErrorCode?.let { return OpdsConnectResult.ServerError(it) }
            return if (anythingAnswered) {
                OpdsConnectResult.NotAFeed(200)
            } else {
                OpdsConnectResult.Unreachable
            }
        }
    }

    /**
     * Trades username/password for a server-issued key. The status code is not
     * trusted: Ubooquity answers this exact POST with HTTP 200 and an HTML
     * page, so only a body carrying a usable key counts as an exchange.
     */
    private fun exchangeToken(
        pasted: String,
        username: String,
        password: String,
        loginPost: OpdsLoginPost,
        attempts: Attempts,
    ): String? {
        val loginUrl = origin(pasted) + KAVITA_LOGIN_PATH
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()
        val response = try {
            loginPost.post(loginUrl, body, null)
        } catch (_: Exception) {
            return null
        }
        if (response.code == 401 || response.code == 403) {
            attempts.noteLoginRefused(response.code)
            return null
        }
        if (response.code !in 200..299) return null
        return apiKeyOf(response.body)
    }

    private fun apiKeyOf(body: ByteArray): String? {
        val text = String(body, StandardCharsets.UTF_8).trim()
        if (!text.startsWith("{")) return null
        return try {
            val json = JSONObject(text)
            sequenceOf("apiKey", "api_key")
                .mapNotNull { key -> json.optString(key, "").trim().ifEmpty { null } }
                .firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** Bases to append candidate paths to: the pasted URL, and its origin. */
    internal fun bases(pasted: String): List<String> {
        val origin = origin(pasted)
        val trimmed = pasted.trimEnd('/')
        return if (trimmed.equals(origin, ignoreCase = true)) {
            listOf(origin)
        } else {
            listOf(origin, trimmed)
        }
    }

    /** scheme://host[:port] of the pasted URL, or the pasted URL unchanged. */
    internal fun origin(url: String): String = try {
        val uri = URI(url.trim())
        val scheme = uri.scheme
        val host = uri.host
        if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
            url.trim().trimEnd('/')
        } else {
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        }
    } catch (_: Exception) {
        url.trim().trimEnd('/')
    }

    fun defaultLoginPost(): OpdsLoginPost = OpdsLoginPost { url, jsonBody, authorizationHeader ->
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        authorizationHeader?.let { connection.setRequestProperty("Authorization", it) }
        connection.doOutput = true
        try {
            connection.outputStream.use { it.write(jsonBody.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            OpdsHttpResponse(code = code, body = stream?.readBytes() ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }
}
