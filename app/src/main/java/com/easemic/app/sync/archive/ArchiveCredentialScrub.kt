package com.easemic.app.sync.archive

import com.easemic.app.feature.providers.OpdsProviderConfig
import com.easemic.app.importing.opds.OpdsCatalogClient

/**
 * #D7: the one place that decides what of a device's catalog setup may leave it inside an archive. The file can sit
 * in Downloads or a chat for years, so a sign-in never travels: an OPDS catalog goes as name + URL only, and a
 * catalog whose URL is itself the key (Kavita's `/api/opds/<key>`, a `?apikey=` query — anything the log scrubber
 * redacts) cannot be carried in any working form, so it stays behind by name for the user to add again.
 */
object ArchiveCredentialScrub {
    data class OpdsResult(val carried: List<ArchiveOpdsCatalog>, val leftBehind: List<String>)

    fun opdsCatalogs(providers: List<OpdsProviderConfig>): OpdsResult {
        val (keyFree, keyed) = providers.partition { isKeyFree(it.catalogUrl) }
        return OpdsResult(
            carried = keyFree.map { ArchiveOpdsCatalog(name = it.displayName, url = it.catalogUrl) },
            leftBehind = keyed.map { it.displayName },
        )
    }

    private fun isKeyFree(url: String): Boolean {
        if (url.isBlank()) return false
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
        if (uri.rawUserInfo != null) return false
        if (!uri.rawQuery.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()) return false
        if (url.contains("/api/opds/", ignoreCase = true)) return false
        return OpdsCatalogClient.redactUrl(url) == url
    }
}
