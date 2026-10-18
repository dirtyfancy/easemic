package com.easemic.app.feature.download

/**
 * Acquisition URL scheme for slice-C2 old-bucket migration rows — mirrors
 * [com.easemic.app.feature.browse.BrowseFeedRepository]'s
 * `ehentai://`/`nhentai://` convention exactly, scoped by old
 * bucket fingerprint so the acquisition string (and therefore the minted
 * `cloudComicId`, see `CloudComicIdStore.getOrCreate`) is stable and
 * collision-free across different retired buckets that happen to reuse the
 * same old `cloudComicId` value.
 */
object R2SourceAcquisition {
    const val SCHEME: String = "r2source"

    fun url(oldFingerprint: String, oldCloudComicId: String): String =
        "$SCHEME://$oldFingerprint/$oldCloudComicId"

    /** Returns (oldFingerprint, oldCloudComicId), or null if [url] doesn't match the scheme. */
    fun parse(url: String): Pair<String, String>? {
        if (!url.startsWith("$SCHEME://")) return null
        val rest = url.removePrefix("$SCHEME://")
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val fingerprint = rest.substring(0, slash)
        val oldCloudComicId = rest.substring(slash + 1)
        if (fingerprint.isBlank() || oldCloudComicId.isBlank()) return null
        return fingerprint to oldCloudComicId
    }
}
