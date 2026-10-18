package com.easemic.app.feature.bridge

import com.easemic.app.sync.R2BearerTokens
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * The one owner of the OAuth access token in this process (C37-c).
 *
 * Cloudflare invalidates a grant's previous access token the moment a new
 * one is issued, and rotates the refresh token with a ~1 minute grace before
 * revoking the family. So: exactly one exchange at a time (single-flight),
 * the rotated refresh token hits disk BEFORE the new access token is handed
 * out, a stale caller is handed the live token instead of exchanging again,
 * and expiry is refreshed proactively so no request ever has to eat a 401.
 * Every `R2RestObjectStore` in the process reads through this object.
 *
 * Threading: blocking. [current] and [afterUnauthorized] may park the caller
 * for one token exchange (measured 0.5-8 s). Never call on the main thread;
 * every caller goes through Dispatchers.IO or a worker thread.
 */
class OauthAccessTokenSource(
    private val secrets: BridgeOAuthDeviceSecretStore,
    private val exchange: (refreshToken: String) -> CloudflareOAuthExchangeResult,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var accessToken: String? = null
    private var expiresAtMs: Long = 0L
    private var dead: Boolean = false

    /** True after the server answered `invalid_grant`: only a new bind ([reset]) revives it. */
    fun isDead(): Boolean {
        synchronized(lock) {
            return dead
        }
    }

    /** Bind/rebind/disconnect: forget everything cached. */
    fun reset() {
        synchronized(lock) {
            accessToken = null
            expiresAtMs = 0L
            dead = false
        }
    }

    /**
     * A usable access token, refreshed proactively; null when unbound, dead,
     * the exchange failed, or the exchange threw an I/O error.
     *
     * A still-valid held token survives a failed or throwing PROACTIVE
     * refresh (I2): it is only dropped once it is hard-expired.
     */
    fun current(): String? {
        synchronized(lock) {
            if (dead) return null
            val held = accessToken
            if (held != null && nowMs() < expiresAtMs - PROACTIVE_REFRESH_MARGIN_MS) return held
            val keepHeldOnFailure = held != null && nowMs() < expiresAtMs
            return exchangeLocked(keepHeldOnFailure)
        }
    }

    /**
     * A request using [usedToken] got 401. If another caller already replaced
     * that token, hand back the live one without exchanging; otherwise
     * exchange once. Null when the source is dead, the exchange failed, or
     * the exchange threw an I/O error.
     */
    fun afterUnauthorized(usedToken: String): String? {
        synchronized(lock) {
            if (dead) return null
            val held = accessToken
            if (held != null && held != usedToken) return held
            // The token that just 401'd cannot be trusted, however "valid"
            // its cached expiry looks: never hand it back on failure.
            return exchangeLocked(keepHeldOnFailure = false)
        }
    }

    /** Adapter for the REST store's bearer contract (same blocking semantics). */
    fun asBearerTokens(): R2BearerTokens = object : R2BearerTokens {
        override fun current(): String? = this@OauthAccessTokenSource.current()
        override fun afterUnauthorized(usedToken: String): String? =
            this@OauthAccessTokenSource.afterUnauthorized(usedToken)
    }

    /** Must be called with [lock] held. */
    private fun exchangeLocked(keepHeldOnFailure: Boolean): String? {
        val current = secrets.load() ?: return null
        val result = try {
            exchange(current.refreshToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // C1: production `exchange` is CloudflarePkceClient.refresh, whose
            // defaultHttp lets connect/read/timeout IOException escape; only
            // JSON parsing is caught there. Treat it exactly like a failed
            // exchange. Error/InterruptedException are not caught here and
            // propagate untouched.
            CloudflareOAuthExchangeResult.Failed(CloudflareOAuthFailure.TOKEN_RESPONSE_INVALID)
        } catch (e: android.os.NetworkOnMainThreadException) {
            // A programming error, not a failed exchange: swallowing it into
            // Failed(TOKEN_RESPONSE_INVALID) would disguise "someone called
            // current() on the main thread" as a dead-looking Cloudflare.
            throw e
        } catch (e: RuntimeException) {
            CloudflareOAuthExchangeResult.Failed(CloudflareOAuthFailure.TOKEN_RESPONSE_INVALID)
        }
        return when (result) {
            is CloudflareOAuthExchangeResult.Success -> {
                // Rotation lands on disk first: a process death between here
                // and the return must not leave a spent refresh token behind.
                persistRotatedRefresh(secrets, current, result.tokens.refreshToken, sync = true)
                val ttlSeconds = (result.tokens.expiresInSeconds ?: DEFAULT_TTL_SECONDS)
                    .coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
                accessToken = result.tokens.accessToken
                expiresAtMs = nowMs() + ttlSeconds * 1000L
                result.tokens.accessToken
            }
            is CloudflareOAuthExchangeResult.Failed -> {
                if (result.failure == CloudflareOAuthFailure.INVALID_GRANT) dead = true
                if (!keepHeldOnFailure) accessToken = null
                if (keepHeldOnFailure) accessToken else null
            }
        }
    }

    companion object {
        const val PROACTIVE_REFRESH_MARGIN_MS: Long = 120_000L
        const val DEFAULT_TTL_SECONDS: Long = 3600L

        /** Must exceed [PROACTIVE_REFRESH_MARGIN_MS] (120 s) or every token would be born already due for refresh. */
        const val MIN_TTL_SECONDS: Long = 300L
        const val MAX_TTL_SECONDS: Long = 86_400L
    }
}
