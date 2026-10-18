package com.easemic.app.importing.source

import com.easemic.app.model.AdapterEnumeration
import com.easemic.app.model.AdapterKind
import com.easemic.app.model.AuthorizedSource

interface SourceAdapter {
    val kind: AdapterKind

    suspend fun enumerate(source: AuthorizedSource): AdapterEnumeration
}
