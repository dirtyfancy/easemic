package com.easemic.app.sync

import com.easemic.app.feature.bridge.DriveTokenSource

/**
 * #D16: the drive refused the saved sign-in — a WebDAV password it no longer accepts (123pan voids app passwords
 * without notice, 2026-09-29), a bearer the store could not refresh, or a OneDrive / Google Drive grant that is gone
 * (#C50). Every surface asks this one predicate. 403 is deliberately not part of it: on S3 it also means B2's daily
 * download cap or a phone clock that is off, and on WebDAV a folder permission (spec §5).
 */
object CloudSignIn {
    /** A queue row's message when an upload or Collect's cloud check was refused; revived on renew or reconnect. */
    const val KEY: String = "CLOUD_SIGN_IN_REJECTED"

    private val REFUSED = Regex("""CLOUD_(?:HTTP|COLLECTION_UPLOAD)_401(?!\d)""")

    fun rejected(message: String?): Boolean =
        message != null &&
            (REFUSED.containsMatchIn(message) || message.contains(DriveTokenSource.REAUTH_REQUIRED) || message.contains(KEY))

    fun rejected(error: Throwable?): Boolean = generateSequence(error, Throwable::cause).any { rejected(it.message) }
}
