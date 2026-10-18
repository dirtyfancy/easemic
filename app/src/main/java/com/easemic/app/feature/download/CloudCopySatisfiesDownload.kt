package com.easemic.app.feature.download

/**
 * #C70: true when the bound bucket already satisfies [DownloadWorkerLoop]'s
 * job for one of its intents, so the worker must not spend bandwidth
 * re-fetching it — this is exactly the loop the spec's evidence traced
 * (rebind re-arms a WAIT/DOWNLOAD job whose gallery the CURRENT bucket's
 * ledger already has, and the release sweep deletes the re-fetched pages
 * right after upload).
 *
 * Requires [intentCloud]: a pure-local job (no cloud intent) is never
 * satisfied by the bucket regardless of sync state. When [intentLocal] is
 * also set, the device copy is wanted UNLESS [releaseAfterUpload] ("keep
 * only the cloud copy") is ON — in that case a dual-intent job's pages are
 * released right after upload anyway, so fetching them first is exactly the
 * waste this gate exists to prevent. [knownSyncedToCurrentBucket] is the
 * caller's ledger read for the CURRENTLY bound bucket (never a stale or
 * previously-bound one).
 */
fun cloudCopySatisfiesDownload(
    intentCloud: Boolean,
    intentLocal: Boolean,
    releaseAfterUpload: Boolean,
    knownSyncedToCurrentBucket: Boolean,
): Boolean {
    if (!intentCloud) return false
    if (intentLocal && !releaseAfterUpload) return false
    return knownSyncedToCurrentBucket
}
