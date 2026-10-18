package com.easemic.app.importing

/**
 * Process-local spacing between third-party catalog GETs.
 * Not a CDN proxy — credentials and fetches stay on device.
 */
class CatalogRequestGate(
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MS,
    private val sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var lastAcquireAt = 0L

    fun acquire() {
        synchronized(lock) {
            val now = nowMillis()
            val wait = lastAcquireAt + minIntervalMillis - now
            if (wait > 0) sleeper(wait)
            lastAcquireAt = nowMillis()
        }
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 750L

        val Shared: CatalogRequestGate by lazy { CatalogRequestGate() }

        /** Read-online GETs. Independent of [Shared] so ingest cannot starve the reader. */
        val Interactive: CatalogRequestGate by lazy { CatalogRequestGate() }
    }
}
