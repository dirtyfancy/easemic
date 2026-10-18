package com.easemic.app.feature.bridge

/**
 * Delivers the Custom Tabs bounce into the bind screen without logging
 * authorization codes or error payloads.
 */
object OAuthRedirectBus {
    @Volatile
    private var listener: ((CloudflareOAuthCallbackResult) -> Unit)? = null

    @Volatile
    private var pending: CloudflareOAuthCallbackResult? = null

    fun setListener(listener: ((CloudflareOAuthCallbackResult) -> Unit)?) {
        this.listener = listener
        val held = pending
        if (listener != null && held != null) {
            pending = null
            listener(held)
        }
    }

    fun dispatch(code: String, state: String) {
        dispatch(CloudflareOAuthCallbackResult.Success(code = code, state = state))
    }

    fun dispatch(result: CloudflareOAuthCallbackResult) {
        val current = listener
        if (current != null) {
            current(result)
        } else {
            pending = result
        }
    }
}
