package com.easemic.app.feature.bridge

/**
 * OAuth identifiers compiled into the APK (not secrets).
 *
 * [CLIENT_ID] is the private OAuth client. Visibility stays private until
 * Custom Tabs smoke; do not PATCH public or add publisher DNS TXT yet.
 * Tests inject a fixture `clientId` into [CloudflarePkceClient]; production
 * uses [CLIENT_ID]. There is no client secret:
 * `token_endpoint_auth_method=none`.
 */
object CloudflareOAuthConfig {
    const val AUTHORIZE_URL = "https://dash.cloudflare.com/oauth2/auth"
    const val TOKEN_URL = "https://dash.cloudflare.com/oauth2/token"
    const val REDIRECT_URI = "https://oauth.easemic.com/cf/callback"
    const val SCOPES = "workers-r2.write workers-r2.read user-details.read offline_access"
    const val CLIENT_ID = "a2b934a1ccd4f5bb8204aec9cab20eeb"
}
