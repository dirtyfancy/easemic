package com.easemic.app.feature.bridge

import com.easemic.app.sync.WebDavBinding
import java.security.MessageDigest

/**
 * Stable identity for the currently-bound R2 bucket, used to scope the local
 * cloud mirror (rebinding to a different bucket wipes + re-hydrates). Bucket
 * NAME alone collides across accounts (fixed PREFERRED_BUCKET policy), so the
 * account/endpoint is folded in. No secrets in the fingerprint — only the
 * non-secret endpoint/accountId + bucket name.
 */
object BucketFingerprint {
    fun forS3(endpoint: String, bucket: String): String = sha256("s3|$endpoint|$bucket")
    fun forOauth(accountId: String, bucket: String): String = sha256("oauth|$accountId|$bucket")

    /** #C50: server + account + folder; the password (and its rotation) is not identity. */
    fun forWebDav(binding: WebDavBinding): String {
        val folder = binding.folder.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")
        return sha256("webdav|${binding.baseUrl.trim().trimEnd('/')}|${binding.username}|$folder")
    }

    /** #C50: drive + app-folder item; the token and the display account are not identity. */
    fun forOneDrive(binding: OneDriveBinding): String = sha256("onedrive|${binding.driveId}|${binding.rootItemId}")

    /** #C50: the library folder id (unique across accounts); the token and account label are not identity. */
    fun forGoogleDrive(binding: GoogleDriveBinding): String = sha256("gdrive|${binding.rootFolderId}")
    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Fingerprint of the currently-bound bucket from the durable binding stores;
 * null when none is bound. At most one of [s3]/[oauth]/[webDav] is normally
 * non-null — each bind path clears the others' stores — but [s3] wins, then
 * [oauth], if several are somehow present, matching
 * [com.easemic.app.AppGraph.resolveGatedObjectStore]'s own
 * working-S3-binding-first resolution order.
 */
fun currentBucketFingerprint(
    s3: BridgeR2Binding?,
    oauth: BridgeOAuthDeviceSecrets?,
    webDav: WebDavBinding? = null,
    oneDrive: OneDriveBinding? = null,
    googleDrive: GoogleDriveBinding? = null,
): String? = when {
    s3 != null -> BucketFingerprint.forS3(s3.endpoint, s3.bucket)
    oauth != null -> BucketFingerprint.forOauth(oauth.accountId, oauth.bucket)
    webDav != null -> BucketFingerprint.forWebDav(webDav)
    oneDrive != null -> BucketFingerprint.forOneDrive(oneDrive)
    googleDrive != null -> BucketFingerprint.forGoogleDrive(googleDrive)
    else -> null
}
