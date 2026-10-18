package com.easemic.app

import com.easemic.app.model.AdapterKind
import com.easemic.app.model.SourceId
import com.easemic.app.storage.GrantedUri

class TreeSourceAuthorization(
    private val authorize: suspend (GrantedUri, AdapterKind, String) -> SourceId?,
    private val importLegacy: (String) -> Unit,
    private val displayName: suspend (String) -> String,
) {
    suspend fun accept(grant: GrantedUri): Boolean {
        val label = try {
            displayName(grant.uri.toString())
        } catch (_: Exception) {
            fallbackLabel(grant.uri.lastPathSegment)
        }
        if (authorize(grant, AdapterKind.GENERIC_DIRECTORY, label) == null) return false
        importLegacy(grant.uri.toString())
        return true
    }

    private fun fallbackLabel(lastPathSegment: String?): String =
        lastPathSegment
            ?.substringAfterLast(':')
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
            ?: "Selected folder"
}
