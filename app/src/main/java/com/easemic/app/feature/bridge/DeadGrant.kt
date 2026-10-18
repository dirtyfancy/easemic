package com.easemic.app.feature.bridge

/**
 * #C50: is the bound library's sign-in revoked? C37-g's rule for Cloudflare extended to the
 * drives: a dead token source only counts when its drive is the bound one. Each drive is
 * (bound, tokenSourceDead).
 */
object DeadGrant {
    fun any(
        cloudflareDead: Boolean = false,
        oneDrive: Pair<Boolean, Boolean> = false to false,
        googleDrive: Pair<Boolean, Boolean> = false to false,
    ): Boolean = cloudflareDead || (oneDrive.first && oneDrive.second) || (googleDrive.first && googleDrive.second)
}
