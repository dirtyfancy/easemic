package com.easemic.app.feature.bridge

/**
 * Name shown on the bound cloud library card.
 * WebDAV labels are a host or a URL; a 123pan host maps to the short name.
 */
object CloudProviderName {
    fun of(kind: BoundKind?, label: String): String = when (kind) {
        BoundKind.ONEDRIVE -> "OneDrive"
        BoundKind.GOOGLE_DRIVE -> "Google Drive"
        BoundKind.WEBDAV -> webDavName(label)
        else -> label
    }

    private fun webDavName(label: String): String {
        val host = hostOf(label)
        val key = host.lowercase()
        val pan = key == "123pan.cn" || key.endsWith(".123pan.cn") ||
            key == "123pan.com" || key.endsWith(".123pan.com")
        return if (pan) "123pan" else host
    }

    private fun hostOf(label: String): String {
        val trimmed = label.trim()
        val host = runCatching { java.net.URI(trimmed).host }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (host != null) return host
        return trimmed.substringBefore('/').ifEmpty { trimmed }
    }
}
