package com.easemic.app.feature.library

import com.easemic.app.feature.providers.OpdsProviderConfig
import java.net.URI

object LibraryChips {
    fun all(opds: List<OpdsProviderConfig>): List<LibraryChip> =
        listOf(
            LibraryChip.OnDevice,
            LibraryChip.Collected,
            LibraryChip.Downloads,
            LibraryChip.Cloud,
        ) + opds.map { provider ->
            LibraryChip.Opds(providerId = provider.id, label = labelFor(provider))
        }

    internal fun labelFor(provider: OpdsProviderConfig): String =
        provider.displayName.trim().ifBlank { hostFromCatalogUrl(provider.catalogUrl) }

    private fun hostFromCatalogUrl(catalogUrl: String): String {
        val host = runCatching { URI(catalogUrl).host }.getOrNull()
        return host?.takeIf { it.isNotBlank() } ?: "OPDS library"
    }
}
