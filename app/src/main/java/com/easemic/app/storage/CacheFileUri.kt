package com.easemic.app.storage

import java.io.File
import java.net.URI

/**
 * Cache-backed file URIs are leases, not durable content identities. A cache
 * eviction or explicit clear can remove the file while a UI still holds it.
 */
fun isReadableCacheUri(uri: String): Boolean {
    if (!uri.startsWith("file:", ignoreCase = true)) return true
    return runCatching {
        val file = File(URI(uri))
        file.isFile && file.canRead()
    }.getOrDefault(false)
}
