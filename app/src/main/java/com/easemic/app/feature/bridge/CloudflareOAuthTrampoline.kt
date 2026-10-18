package com.easemic.app.feature.bridge

import android.content.Intent

data class OAuthMainForward(
    val extras: Map<String, String>,
    val flags: Int,
)

/**
 * Custom Tabs bounce extras. Never log [code], [state], or error payloads.
 */
object CloudflareOAuthTrampoline {
    const val EXTRA_CODE = "com.easemic.app.oauth.CODE"
    const val EXTRA_STATE = "com.easemic.app.oauth.STATE"
    const val EXTRA_ERROR = "com.easemic.app.oauth.ERROR"
    const val EXTRA_ERROR_DESCRIPTION =
        "com.easemic.app.oauth.ERROR_DESCRIPTION"

    private val clearTopFlags =
        Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP

    fun forwardOAuthToMain(code: String, state: String): OAuthMainForward =
        OAuthMainForward(
            extras = mapOf(EXTRA_CODE to code, EXTRA_STATE to state),
            flags = clearTopFlags,
        )

    fun forwardOAuthFailureToMain(error: String, description: String?): OAuthMainForward {
        val extras = mutableMapOf(EXTRA_ERROR to error)
        if (!description.isNullOrBlank()) {
            extras[EXTRA_ERROR_DESCRIPTION] = description
        }
        return OAuthMainForward(extras = extras, flags = clearTopFlags)
    }

    fun parseFromExtras(code: String?, state: String?): CloudflareOAuthCallback? {
        val trimmedCode = code?.trim().orEmpty()
        val trimmedState = state?.trim().orEmpty()
        if (trimmedCode.isEmpty() || trimmedState.isEmpty()) return null
        return CloudflareOAuthCallback(trimmedCode, trimmedState)
    }

    fun consumeExtras(extras: MutableMap<String, String>): CloudflareOAuthCallback? {
        val parsed = parseFromExtras(extras[EXTRA_CODE], extras[EXTRA_STATE])
        extras.remove(EXTRA_CODE)
        extras.remove(EXTRA_STATE)
        return parsed
    }

    fun consumeResultExtras(extras: MutableMap<String, String>): CloudflareOAuthCallbackResult? {
        val error = extras[EXTRA_ERROR]?.trim().orEmpty()
        if (error.isNotEmpty()) {
            val description = extras[EXTRA_ERROR_DESCRIPTION]?.trim()?.takeIf { it.isNotEmpty() }
            extras.remove(EXTRA_ERROR)
            extras.remove(EXTRA_ERROR_DESCRIPTION)
            extras.remove(EXTRA_CODE)
            extras.remove(EXTRA_STATE)
            return CloudflareOAuthCallbackResult.Failure(error = error, description = description)
        }
        val success = parseFromExtras(extras[EXTRA_CODE], extras[EXTRA_STATE])
        extras.remove(EXTRA_CODE)
        extras.remove(EXTRA_STATE)
        return success?.let {
            CloudflareOAuthCallbackResult.Success(code = it.code, state = it.state)
        }
    }

    fun consume(intent: Intent): CloudflareOAuthCallback? {
        val result = consumeResult(intent)
        return when (result) {
            is CloudflareOAuthCallbackResult.Success ->
                CloudflareOAuthCallback(result.code, result.state)
            else -> null
        }
    }

    fun consumeResult(intent: Intent): CloudflareOAuthCallbackResult? {
        val error = intent.getStringExtra(EXTRA_ERROR)?.trim().orEmpty()
        if (error.isNotEmpty()) {
            val description =
                intent.getStringExtra(EXTRA_ERROR_DESCRIPTION)?.trim()?.takeIf { it.isNotEmpty() }
            intent.removeExtra(EXTRA_ERROR)
            intent.removeExtra(EXTRA_ERROR_DESCRIPTION)
            intent.removeExtra(EXTRA_CODE)
            intent.removeExtra(EXTRA_STATE)
            return CloudflareOAuthCallbackResult.Failure(error = error, description = description)
        }
        val fromExtras = parseFromExtras(
            intent.getStringExtra(EXTRA_CODE),
            intent.getStringExtra(EXTRA_STATE),
        )
        if (fromExtras != null) {
            intent.removeExtra(EXTRA_CODE)
            intent.removeExtra(EXTRA_STATE)
            return CloudflareOAuthCallbackResult.Success(fromExtras.code, fromExtras.state)
        }
        if (intent.action != Intent.ACTION_VIEW) return null
        val data = intent.dataString ?: return null
        val parsed = CloudflareOAuthCallbackParser.parseResult(data) ?: return null
        intent.data = null
        return parsed
    }
}
