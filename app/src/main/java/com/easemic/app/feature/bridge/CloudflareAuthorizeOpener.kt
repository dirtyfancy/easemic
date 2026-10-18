package com.easemic.app.feature.bridge

import android.content.ActivityNotFoundException

/**
 * Opens the Cloudflare authorize URL. Custom Tabs first; plain VIEW if this
 * device has no Custom Tabs handler. Never throw — a missing browser must not
 * crash Cloud storage.
 */
object CloudflareAuthorizeOpener {
    fun open(
        uri: String,
        launchCustomTab: (String) -> Unit,
        launchView: (String) -> Unit,
        onUnavailable: () -> Unit = {},
    ) {
        try {
            launchCustomTab(uri)
        } catch (_: ActivityNotFoundException) {
            try {
                launchView(uri)
            } catch (_: ActivityNotFoundException) {
                onUnavailable()
            }
        }
    }
}
