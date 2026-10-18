package com.easemic.app.sync

/**
 * Owner 2026-09-09: OPDS is a first-class source like E-Hentai, so its comics
 * CAN be collected into the user's own cloud library. What remains true is
 * that the cloud ingest cannot fetch an http(s) OPDS acquisition by itself —
 * only the download pipeline (OpdsDownloadPageAdapter) knows how to pull and
 * extract it. So an http(s) acquisition must reach the cloud through the
 * fetch-job route (collect → cloud-intent download job → SEALED → upload from
 * local pages), never through the legacy direct-from-network ingest.
 */
object OpdsCloudIngestGuard {
    /** True for an OPDS-shaped (http/https) acquisition or page stream. */
    fun isHttpAcquisition(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return false
        return raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
    }

    /**
     * The direct (no local pages) ingest path cannot fetch OPDS. Kept as the
     * one remaining refusal so a stray legacy queue row fails honestly instead
     * of pretending to upload.
     */
    fun mustRefuseDirectIngest(url: String?, hasLocalPages: Boolean): Boolean =
        !hasLocalPages && isHttpAcquisition(url)

    @Deprecated("OPDS collects are allowed; use isHttpAcquisition / mustRefuseDirectIngest")
    fun mustRefuse(url: String?): Boolean = isHttpAcquisition(url)
}
