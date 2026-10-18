package com.easemic.app.feature.bridge

import java.net.URI
import java.net.URISyntaxException

/**
 * Save-time shape check for pasted endpoints. A binding with a non-URL
 * endpoint is unusable and only fails later inside request signing, so Save
 * rejects it up front (Slice-2 smoke finding 1).
 */
object R2EndpointFormat {
    fun isValid(raw: String): Boolean {
        val uri = try {
            URI(raw.trim())
        } catch (_: URISyntaxException) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false
        return !uri.host.isNullOrBlank()
    }
}
