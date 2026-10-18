package com.easemic.app.sync

/**
 * Transport-agnostic "object does not exist" heuristic shared by the
 * collection store and the library gate (S3 XML 404s and OAuth REST both
 * surface as message text, not typed statuses).
 */
internal fun looksLikeMissingObject(error: Exception): Boolean {
    val message = error.message.orEmpty().lowercase()
    return message.contains("missing") ||
        message.contains("404") ||
        message.contains("not found") ||
        message.contains("http_404") ||
        message.contains("file not found")
}
