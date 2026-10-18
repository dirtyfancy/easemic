package com.easemic.app.feature.providers

import com.easemic.app.ui.PreferenceMap
import org.json.JSONObject

/**
 * WebView-minted nhentai Cloudflare `cf_clearance` cookie. The cookie is bound
 * to the exact User-Agent that solved the challenge — sending it back with a
 * different UA gets it rejected by Cloudflare — so the minting UA travels
 * alongside the cookie value rather than being reconstructed at send time.
 */
data class NhentaiCookie(
    val cfClearance: String,
    val userAgent: String,
    /** When to consider this stale. 0 = no expiry check (WebView can't always read Max-Age). */
    val expiresAtMs: Long,
) {
    fun isValid(nowMs: Long): Boolean =
        cfClearance.isNotBlank() && (expiresAtMs == 0L || nowMs < expiresAtMs)

    /** Cookie header value to send. */
    fun cookieHeader(): String =
        if (cfClearance.startsWith("cf_clearance=")) cfClearance else "cf_clearance=$cfClearance"
}

/**
 * Persists the single on-device nhentai `cf_clearance` cookie minted by the
 * (separate, not-yet-built) WebView challenge-solve flow. [read] auto-forgets
 * a stale or blank record so every caller gets a clean null instead of having
 * to re-check validity itself.
 */
class NhentaiCookieStore(
    private val prefs: PreferenceMap,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun read(): NhentaiCookie? {
        val raw = SourceKeyMigration.readMigrated(prefs, KEY, LegacyKeys.NHENTAI_CF_COOKIE, null)
            ?.trim()
            .orEmpty()
        if (raw.isEmpty()) return null
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return null
        }
        val cookie = NhentaiCookie(
            cfClearance = json.optString("cfClearance"),
            userAgent = json.optString("userAgent"),
            expiresAtMs = json.optLong("expiresAtMs", 0L),
        )
        return cookie.takeIf { it.isValid(clock()) }
    }

    fun write(cookie: NhentaiCookie) {
        val json = JSONObject()
            .put("cfClearance", cookie.cfClearance)
            .put("userAgent", cookie.userAgent)
            .put("expiresAtMs", cookie.expiresAtMs)
        prefs.putString(KEY, json.toString())
    }

    fun clear() {
        prefs.putString(KEY, "")
    }

    companion object {
        private const val KEY = EhentaiStoreKeys.NHENTAI_CF_COOKIE_KEY
    }
}
