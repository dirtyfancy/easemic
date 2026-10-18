package com.easemic.app.importing.source

import com.easemic.app.model.AdapterEnumeration
import com.easemic.app.model.AdapterKind
import com.easemic.app.model.AuthorizedSource
import com.easemic.app.model.CandidateSeed
import com.easemic.app.model.LocationKind

class ComicArchiveSourceAdapter : SourceAdapter {
    override val kind = AdapterKind.COMIC_ARCHIVE

    override suspend fun enumerate(source: AuthorizedSource): AdapterEnumeration =
        AdapterEnumeration.Complete(
            listOf(
                CandidateSeed(
                    providerDocumentId = source.providerIdentity.substringAfter(":document:"),
                    documentUri = source.contentUri,
                    displayName = source.displayLabel,
                    locationKind = LocationKind.ARCHIVE,
                    selectedDirectly = true,
                ),
            ),
        )
}
