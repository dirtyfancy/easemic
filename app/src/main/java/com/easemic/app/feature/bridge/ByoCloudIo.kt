package com.easemic.app.feature.bridge

import com.easemic.app.sync.ObjectStore

object ByoCloudIoCopy {
    const val BIND = "Connect Cloudflare or paste S3 credentials under More → Cloud storage."

    /**
     * Owner bug A3/C5 fix (T3a): distinct from [BIND] — this covers a
     * binding that IS persisted on this device but whose store could not be
     * resolved THIS attempt (e.g. a momentary OAuth-refresh network hiccup
     * right after app reopen). [BIND] tells the user to connect cloud
     * storage, which is wrong and misleading when they already have; this is
     * never shown as a terminal FAILED-row message (see
     * [com.easemic.app.feature.collect.CloudIngestOutcome.Deferred]),
     * only logged, since the row itself stays WAITING for the pump to retry.
     */
    const val STORE_TEMPORARILY_UNAVAILABLE = "Cloud storage temporarily unavailable — will retry automatically."

    /** C37-g: the OAuth grant was revoked or spent on the server; nothing retries this — only Reconnect in More → Cloud storage. */
    const val RECONNECT = "Cloud storage needs reconnecting — open More → Cloud storage and Connect Cloudflare again."
}

sealed interface ByoCloudIoResult {
    data class Ready(val store: ObjectStore) : ByoCloudIoResult
    data class Blocked(val message: String) : ByoCloudIoResult
}

object ByoCloudIo {
    fun resolve(
        working: BridgeR2Binding?,
        storeFor: (BridgeR2Binding) -> ObjectStore,
        oauthStore: ObjectStore? = null,
        /** #C50: the bound WebDAV drive, already built; last in the same order as [currentBucketFingerprint]. */
        webDavStore: ObjectStore? = null,
        /** #C50: the bound OneDrive, after WebDAV in the same order as [currentBucketFingerprint]. */
        oneDriveStore: ObjectStore? = null,
        /** #C50: the bound Google Drive, last in the same order as [currentBucketFingerprint]. */
        googleDriveStore: ObjectStore? = null,
    ): ByoCloudIoResult {
        // Accountless: readiness is purely "is a bucket bound on this device".
        // Ready when an S3 workingBinding OR the in-memory OAuth REST store is
        // present; no sign-in gate.
        if (working == null && oauthStore == null) {
            (webDavStore ?: oneDriveStore ?: googleDriveStore)?.let { return ByoCloudIoResult.Ready(it) }
        }
        if (
            !LibraryCloudReadiness.objectStoreReady(
                oauthRestStorePresent = oauthStore != null,
                workingBindingPresent = working != null,
            )
        ) {
            return ByoCloudIoResult.Blocked(ByoCloudIoCopy.BIND)
        }
        if (working != null) return ByoCloudIoResult.Ready(storeFor(working))
        return ByoCloudIoResult.Ready(checkNotNull(oauthStore))
    }
}
