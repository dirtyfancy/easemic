package com.easemic.app.feature.providers

import java.util.Base64
import org.json.JSONObject

sealed interface ZaimanhuaSignInStep {
    data object KeepWaiting : ZaimanhuaSignInStep
    data class Verify(val token: String) : ZaimanhuaSignInStep
    data class Finish(val account: ZaimanhuaAccount) : ZaimanhuaSignInStep
}

/** Cookie capture and one-shot verification state for the 再漫画 WebView. */
class ZaimanhuaSignInFlow(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val mobileOrigin: String = "https://m.zaimanhua.com",
) {
    private var pendingToken: String? = null
    private val verifiedOrRejectedTokens = mutableSetOf<String>()

    fun onPageFinished(url: String?, cookieHeader: String?): ZaimanhuaSignInStep {
        val pageUrl = url?.trim().orEmpty()
        if (!isMobilePage(pageUrl)) {
            return ZaimanhuaSignInStep.KeepWaiting
        }
        val token = cookieHeader
            .orEmpty()
            .split(';')
            .asSequence()
            .map { it.trim() }
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0 || part.substring(0, separator).trim() != "token") null
                else part.substring(separator + 1).trim()
            }
            .firstOrNull()
            ?: return ZaimanhuaSignInStep.KeepWaiting
        val expSeconds = parseFreshExpiry(token) ?: return ZaimanhuaSignInStep.KeepWaiting
        if (token in verifiedOrRejectedTokens || token == pendingToken) return ZaimanhuaSignInStep.KeepWaiting
        pendingToken = token
        // The host reads the cookie only from the owned mobile origin.
        return if (expSeconds > 0L) ZaimanhuaSignInStep.Verify(token) else ZaimanhuaSignInStep.KeepWaiting
    }

    fun onVerification(token: String, errno: Int): ZaimanhuaSignInStep {
        if (pendingToken != token || token in verifiedOrRejectedTokens) return ZaimanhuaSignInStep.KeepWaiting
        pendingToken = null
        verifiedOrRejectedTokens += token
        if (errno != 0) return ZaimanhuaSignInStep.KeepWaiting
        val expSeconds = parseFreshExpiry(token) ?: return ZaimanhuaSignInStep.KeepWaiting
        return ZaimanhuaSignInStep.Finish(ZaimanhuaAccount(token, expSeconds))
    }

    private fun parseFreshExpiry(token: String): Long? {
        val parts = token.split('.')
        if (parts.size != 3 || parts.any { it.isEmpty() }) return null
        if (parts.take(2).any { runCatching { Base64.getUrlDecoder().decode(it) }.isFailure }) return null
        if (!parts[2].matches(Regex("[A-Za-z0-9_-]+"))) return null
        val payload = try {
            val bytes = Base64.getUrlDecoder().decode(parts[1])
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        if (!payload.has("exp") || payload.isNull("exp")) return null
        val exp = payload.optLong("exp", Long.MIN_VALUE)
        if (exp == Long.MIN_VALUE) return null
        val nowSeconds = nowMs() / 1000L
        return exp.takeIf { it > nowSeconds && it - nowSeconds > DAY_SECONDS }
    }

    private fun isMobilePage(url: String): Boolean =
        url == mobileOrigin || url.startsWith("$mobileOrigin/")

    companion object {
        const val START_URL = "https://m.zaimanhua.com/pages/user/user-login"
        const val USER_INFO_URL = "https://account-api.zaimanhua.com/v1/userInfo/get"
        const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val DAY_SECONDS = 24L * 60L * 60L
    }
}
