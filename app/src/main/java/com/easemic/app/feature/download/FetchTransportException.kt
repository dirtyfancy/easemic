package com.easemic.app.feature.download

/**
 * Carries the raw inputs [classifyFetchFailure] needs, thrown by the fetch/adapter
 * layer so [DownloadWorkerLoop] can classify without the status/URL/headers being
 * laundered into an opaque message. Pure data — classification happens in the loop,
 * which knows the provider namespace.
 */
class FetchTransportException(
    val statusCode: Int,          // 0 = network-layer failure (no HTTP response)
    val resolvedUrl: String?,     // final URL after redirects (for /509.gif detection)
    val bodySample: ByteArray,    // up to a few KB of body (for ban-page / cf-challenge text); empty ok
    val headers: Map<String, String> = emptyMap(),
    override val message: String? = null,
    override val cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        /** Cap body retained for classification (ban pages / cf HTML are small). */
        const val BODY_SAMPLE_LIMIT = 8 * 1024
    }
}
