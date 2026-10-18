package com.easemic.app.feature.bridge

/**
 * Pure gates for Library Cloud + catch-up after OAuth bind.
 *
 * OAuth-bound devices do not populate an S3 working binding; they need the
 * in-memory OAuth REST object store. Catch-up must not wait for navigation to
 * Cloud storage.
 */
object LibraryCloudReadiness {
    /**
     * True when cold-start / session unlock must refresh the OAuth REST store
     * so [ByoCloudIo] can leave BIND without visiting the bind screen.
     */
    fun needsOauthRestBootstrap(
        hasOauthDeviceSecrets: Boolean,
        workingBindingPresent: Boolean,
        oauthRestCacheReady: Boolean,
    ): Boolean =
        hasOauthDeviceSecrets &&
            !workingBindingPresent &&
            !oauthRestCacheReady

    /**
     * Catch-up LaunchedEffect eligibility. Cloud objects are plaintext, so a
     * Ready Library Cloud shelf is the whole gate.
     */
    fun catchUpEligible(libraryCloudReady: Boolean): Boolean = libraryCloudReady

    /**
     * Object IO is Ready once a bucket is bound on this device: an S3 working
     * binding, or the in-memory OAuth REST store — equivalent to
     * `productObjectStore` without Compose.
     */
    fun objectStoreReady(
        oauthRestStorePresent: Boolean,
        workingBindingPresent: Boolean = false,
    ): Boolean {
        if (workingBindingPresent) return true
        return oauthRestStorePresent
    }
}
