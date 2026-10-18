package com.easemic.app.feature.bridge

import com.easemic.app.sync.CloudHttpException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

/** #C50: one drive's OAuth endpoints. The client ids are public identifiers (public clients, no secret). */
data class DriveOAuthConfig(
    val authorizeUrl: String,
    val tokenUrl: String,
    val clientId: String,
    val redirectUri: String,
    val scope: String,
    val extraAuthorizeParams: List<Pair<String, String>> = emptyList(),
)

object DriveOAuthConfigs {
    /** App registration "Easemic" (personal Microsoft accounts, public client), created 2026-09-26, renamed 2026-10-01. */
    val ONEDRIVE = DriveOAuthConfig(
        authorizeUrl = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
        tokenUrl = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
        clientId = "48b81483-4782-4ab6-a4f6-290dc23308ca",
        redirectUri = "com.easemic.app://auth/onedrive",
        scope = "Files.ReadWrite.AppFolder offline_access User.Read",
        extraAuthorizeParams = listOf("prompt" to "select_account"),
    )

    /**
     * Google Drive: an iOS-type client in project private-comic-library (created 2026-09-26). Google
     * refuses custom-scheme/loopback redirects for Android-type clients, a Web client needs a secret
     * (cannot ship in an APK), and Play-services sign-in needs GMS (many users lack it — owner
     * 2026-09-26). The iOS type has no secret and redirects to its reverse-client-id scheme.
     */
    val GOOGLE_DRIVE = DriveOAuthConfig(
        authorizeUrl = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenUrl = "https://oauth2.googleapis.com/token",
        clientId = "227975952622-pq81regsksce5goav8ln8kpleb9nhj2l.apps.googleusercontent.com",
        redirectUri = "com.googleusercontent.apps.227975952622-pq81regsksce5goav8ln8kpleb9nhj2l:/oauth2redirect",
        scope = "https://www.googleapis.com/auth/drive.file openid email",
        extraAuthorizeParams = listOf("prompt" to "select_account"),
    )
}

data class DrivePkceSession(val authorizationUri: String, val codeVerifier: String, val state: String) {
    override fun toString(): String = "DrivePkceSession(authorizationUri=$authorizationUri, codeVerifier=<redacted>, state=$state)"
}

data class DriveOAuthTokens(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long?) {
    override fun toString(): String = "DriveOAuthTokens(accessToken=<redacted>, refreshToken=<redacted>, expiresInSeconds=$expiresInSeconds)"
}

enum class DriveOAuthFailure { MISSING_CODE, STATE_MISMATCH, TOKEN_RESPONSE_INVALID, INVALID_GRANT }

sealed interface DriveOAuthResult {
    data class Success(val tokens: DriveOAuthTokens) : DriveOAuthResult
    data class Failed(val failure: DriveOAuthFailure) : DriveOAuthResult
}

/**
 * #C50: authorization code + PKCE (RFC 7636, S256) for a drive — the same shape as
 * [CloudflarePkceClient], parameterised by [DriveOAuthConfig] so OneDrive and Google
 * Drive share it. HTTP is injected so JVM tests never talk to a provider.
 */
class DrivePkceClient(
    private val config: DriveOAuthConfig,
    private val http: CloudflareOAuthHttp = CloudflarePkceClient.defaultHttp(),
    private val randomBytes: () -> ByteArray = { ByteArray(32).also { SecureRandom().nextBytes(it) } },
) {
    fun begin(): DrivePkceSession {
        val verifier = base64Url(randomBytes())
        val state = base64Url(randomBytes())
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        val params = listOf(
            "response_type" to "code",
            "client_id" to config.clientId,
            "redirect_uri" to config.redirectUri,
            "scope" to config.scope,
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        ) + config.extraAuthorizeParams
        val query = params.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return DrivePkceSession("${config.authorizeUrl}?$query", verifier, state)
    }

    fun exchange(code: String, state: String, session: DrivePkceSession): DriveOAuthResult {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return DriveOAuthResult.Failed(DriveOAuthFailure.MISSING_CODE)
        if (state != session.state) return DriveOAuthResult.Failed(DriveOAuthFailure.STATE_MISMATCH)
        return parse(
            http.postForm(
                config.tokenUrl,
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to trimmed,
                    "redirect_uri" to config.redirectUri,
                    "client_id" to config.clientId,
                    "code_verifier" to session.codeVerifier,
                    "scope" to config.scope,
                ),
            ),
            fallbackRefreshToken = null,
        )
    }

    fun refresh(refreshToken: String): DriveOAuthResult {
        val trimmed = refreshToken.trim()
        if (trimmed.isEmpty()) return DriveOAuthResult.Failed(DriveOAuthFailure.MISSING_CODE)
        return parse(
            http.postForm(
                config.tokenUrl,
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to trimmed,
                    "client_id" to config.clientId,
                    "scope" to config.scope,
                ),
            ),
            fallbackRefreshToken = trimmed,
        )
    }

    private fun parse(body: String, fallbackRefreshToken: String?): DriveOAuthResult = try {
        val json = JSONObject(body)
        when {
            json.optString("error") == "invalid_grant" -> DriveOAuthResult.Failed(DriveOAuthFailure.INVALID_GRANT)
            else -> {
                val access = json.optString("access_token").trim()
                // Google does not return a new refresh token on refresh; Microsoft rotates it every time.
                val refresh = json.optString("refresh_token").trim().ifEmpty { fallbackRefreshToken.orEmpty() }
                if (access.isEmpty() || refresh.isEmpty()) {
                    DriveOAuthResult.Failed(DriveOAuthFailure.TOKEN_RESPONSE_INVALID)
                } else {
                    val expires = if (json.has("expires_in") && !json.isNull("expires_in")) json.getLong("expires_in") else null
                    DriveOAuthResult.Success(DriveOAuthTokens(access, refresh, expires))
                }
            }
        }
    } catch (_: Exception) {
        DriveOAuthResult.Failed(DriveOAuthFailure.TOKEN_RESPONSE_INVALID)
    }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

/**
 * #C50: the ONE owner of a drive's tokens in this process. Blocking — callers hop to IO.
 *
 * The access token is reused until [MARGIN_MS] before it expires; concurrent callers share
 * one refresh; a rotated refresh token (Microsoft rotates on every refresh) is written
 * synchronously BEFORE the new access token is handed out, so a process death can never
 * lose the rotation. `invalid_grant` is terminal: every later call fails with
 * [REAUTH_REQUIRED] without touching the network, until the user signs in again.
 */
class DriveTokenSource(
    private val loadRefreshToken: () -> String?,
    private val saveRefreshTokenSync: (String) -> Unit,
    private val refresh: (String) -> DriveOAuthResult,
    private val clockMs: () -> Long = System::currentTimeMillis,
    /** Spec addendum 2026-09-27: a still-valid token survives a cold start (a refresh is ~1 s per launch). */
    private val accessCache: DriveAccessTokenCache = DriveAccessTokenCache.NONE,
) {
    private val lock = Any()
    private var accessToken: String? = null
    private var expiresAtMs: Long = 0L
    private var loadedPersisted = false
    @Volatile private var dead = false

    fun isDead(): Boolean = dead

    fun accessToken(): String = synchronized(lock) {
        if (dead) throw CloudHttpException(REAUTH_REQUIRED)
        if (accessToken == null && !loadedPersisted) {
            loadedPersisted = true
            accessCache.load()?.let { accessToken = it.accessToken; expiresAtMs = it.expiresAtMs }
        }
        accessToken?.takeIf { clockMs() < expiresAtMs - MARGIN_MS }?.let { return it }
        val refreshToken = loadRefreshToken()?.takeIf { it.isNotBlank() } ?: throw CloudHttpException(REAUTH_REQUIRED)
        when (val result = refresh(refreshToken)) {
            is DriveOAuthResult.Failed -> {
                if (result.failure == DriveOAuthFailure.INVALID_GRANT) {
                    dead = true
                    throw CloudHttpException(REAUTH_REQUIRED)
                }
                throw CloudHttpException("CLOUD_TOKEN_REFRESH_FAILED")
            }
            is DriveOAuthResult.Success -> {
                if (result.tokens.refreshToken != refreshToken) saveRefreshTokenSync(result.tokens.refreshToken)
                remember(result.tokens)
                result.tokens.accessToken
            }
        }
    }

    /** A request came back 401 with [rejected]: forget it (unless another lane already replaced it). */
    fun invalidate(rejected: String) = synchronized(lock) {
        if (accessToken == rejected || accessCache.load()?.accessToken == rejected) {
            accessToken = null
            expiresAtMs = 0L
            accessCache.clear()
        }
    }

    /** The sign-in's own token exchange already produced an access token: use it rather than refreshing again. */
    fun seed(tokens: DriveOAuthTokens) = synchronized(lock) {
        loadedPersisted = true
        remember(tokens)
    }

    /** A fresh sign-in replaced the grant. */
    fun reset() = synchronized(lock) {
        accessToken = null
        expiresAtMs = 0L
        loadedPersisted = true
        accessCache.clear()
        dead = false
    }

    private fun remember(tokens: DriveOAuthTokens) {
        accessToken = tokens.accessToken
        expiresAtMs = clockMs() + (tokens.expiresInSeconds ?: DEFAULT_LIFETIME_S) * 1_000L
        accessCache.save(PersistedAccessToken(tokens.accessToken, expiresAtMs))
    }

    companion object {
        const val REAUTH_REQUIRED: String = "CLOUD_REAUTH_REQUIRED"
        private const val MARGIN_MS = 60_000L
        private const val DEFAULT_LIFETIME_S = 3_600L
    }
}

/** #C50: an access token and the wall-clock instant it expires. */
data class PersistedAccessToken(val accessToken: String, val expiresAtMs: Long) {
    override fun toString(): String = "PersistedAccessToken(<redacted>, expiresAtMs=$expiresAtMs)"
}

/** Where a [DriveTokenSource] keeps its access token between launches: inside the drive's binding record. */
interface DriveAccessTokenCache {
    fun load(): PersistedAccessToken?
    fun save(token: PersistedAccessToken)
    fun clear()

    companion object {
        val NONE: DriveAccessTokenCache = object : DriveAccessTokenCache {
            override fun load(): PersistedAccessToken? = null
            override fun save(token: PersistedAccessToken) = Unit
            override fun clear() = Unit
        }
    }
}
