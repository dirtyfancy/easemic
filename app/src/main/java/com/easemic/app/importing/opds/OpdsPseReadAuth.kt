package com.easemic.app.importing.opds

import com.easemic.app.feature.providers.OpdsProviderConfig

/**
 * Resolves HTTP Basic for OPDS-PSE Read online.
 * A non-blank provider id with no matching catalog is unavailable (fail closed).
 * A blank id is still allowed so Auth-Key-in-URL catalogs can fetch.
 */
sealed class OpdsPseReadAuth {
    data class Ready(val authorizationHeader: String?) : OpdsPseReadAuth()
    data object Unavailable : OpdsPseReadAuth()

    companion object {
        fun resolve(
            providerId: String,
            providers: List<OpdsProviderConfig>,
        ): OpdsPseReadAuth {
            val id = providerId.trim()
            if (id.isEmpty()) {
                return Ready(authorizationHeader = null)
            }
            val provider = providers.firstOrNull { it.id == id } ?: return Unavailable
            return Ready(
                authorizationHeader = OpdsCatalogClient.basicAuthorizationHeader(
                    provider.username,
                    provider.password,
                ),
            )
        }
    }
}
