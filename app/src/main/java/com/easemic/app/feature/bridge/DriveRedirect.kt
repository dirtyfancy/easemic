package com.easemic.app.feature.bridge

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

enum class DriveKind(val path: String) { ONEDRIVE("/onedrive"), GOOGLE_DRIVE("/oauth2redirect") }

data class DriveRedirect(val kind: DriveKind, val result: CloudflareOAuthCallbackResult)

/**
 * #C50: `com.easemic.app://auth/<drive>?code=…&state=…` — the drives' sign-in
 * bounces (the Cloudflare one stays `…://cf/oauth/callback`). Never log the URI or query.
 */
object DriveRedirectParser {
    const val HOST: String = "auth"

    /** Google's reverse-client-id scheme for [DriveOAuthConfigs.GOOGLE_DRIVE] (`<scheme>:/oauth2redirect`). */
    val GOOGLE_SCHEME: String = DriveOAuthConfigs.GOOGLE_DRIVE.redirectUri.substringBefore(':')

    fun parse(uri: String): DriveRedirect? {
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
        val kind = when {
            parsed.scheme == GOOGLE_SCHEME && parsed.path == DriveKind.GOOGLE_DRIVE.path -> DriveKind.GOOGLE_DRIVE
            parsed.scheme == CloudflareOAuthCallbackParser.SCHEME && parsed.host == HOST ->
                DriveKind.entries.firstOrNull { it != DriveKind.GOOGLE_DRIVE && it.path == parsed.path } ?: return null
            else -> return null
        }
        val params = parsed.rawQuery.orEmpty().split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@mapNotNull null
            URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8.name()) to
                URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name())
        }.toMap()
        val error = params["error"]?.trim().orEmpty()
        if (error.isNotEmpty()) {
            return DriveRedirect(kind, CloudflareOAuthCallbackResult.Failure(error, params["error_description"]?.trim()?.takeIf { it.isNotEmpty() }))
        }
        val code = params["code"]?.trim().orEmpty()
        val state = params["state"]?.trim().orEmpty()
        if (code.isEmpty() || state.isEmpty()) return null
        return DriveRedirect(kind, CloudflareOAuthCallbackResult.Success(code, state))
    }
}

/**
 * Same hold-until-listening contract as [OAuthRedirectBus], for drive sign-ins — per drive
 * kind: a OneDrive bounce that lands while the Google Drive screen is up stays held for the
 * next OneDrive screen instead of being handed to (and dropped by) the wrong one.
 */
object DriveRedirectBus {
    private class Listener(val kind: DriveKind, val onRedirect: (DriveRedirect) -> Unit)

    private val lock = Any()
    private var listener: Listener? = null
    private val pending = HashMap<DriveKind, DriveRedirect>()

    /** Returns a handle for [clearListener]; delivers any bounce already held for [kind]. */
    fun setListener(kind: DriveKind, onRedirect: (DriveRedirect) -> Unit): Any {
        val registered = Listener(kind, onRedirect)
        val held = synchronized(lock) {
            listener = registered
            pending.remove(kind)
        }
        held?.let(onRedirect)
        return registered
    }

    /** Unhooks [handle] only if it is still the listener — a newer screen's stays. */
    fun clearListener(handle: Any) {
        synchronized(lock) { if (listener === handle) listener = null }
    }

    fun dispatch(redirect: DriveRedirect) {
        val target = synchronized(lock) {
            val current = listener?.takeIf { it.kind == redirect.kind }
            if (current == null) pending[redirect.kind] = redirect
            current
        }
        target?.onRedirect?.invoke(redirect)
    }
}
