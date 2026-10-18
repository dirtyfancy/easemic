package com.easemic.app

import com.easemic.app.model.AuthorizedSource
import com.easemic.app.model.GrantKind

class SourceStartupValidation(
    private val validateSources: suspend (
        (String) -> Boolean,
        suspend (AuthorizedSource) -> Boolean,
    ) -> Unit,
    private val hasGrant: (String) -> Boolean,
    private val canReadTree: suspend (String) -> Boolean,
    private val canReadDocument: suspend (String) -> Boolean,
) {
    suspend fun run() {
        validateSources(hasGrant) { source ->
            when (source.grantKind) {
                GrantKind.TREE -> canReadTree(source.contentUri)
                GrantKind.DOCUMENT -> canReadDocument(source.contentUri)
            }
        }
    }
}
