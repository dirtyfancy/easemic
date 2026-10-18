package com.easemic.app.importing.source

import com.easemic.app.model.AdapterKind

sealed interface AdapterLookup {
    data class Found(val adapter: SourceAdapter) : AdapterLookup
    data class Missing(val kind: AdapterKind) : AdapterLookup
}

class SourceAdapterRegistry(adapters: List<SourceAdapter>) {
    private val byKind = adapters.associateBy(SourceAdapter::kind)

    /**
     * A30: local import has exactly one folder adapter. `EHVIEWER` survives as a
     * persisted `authorized_sources.adapterKind` value on devices that imported
     * before the merge, so it resolves onto the tree adapter instead of failing
     * the run. The `.ehviewer` marker itself is read by ImageDirectoryScanner.
     */
    fun adapter(kind: AdapterKind): AdapterLookup {
        val resolved = if (kind == AdapterKind.EHVIEWER) AdapterKind.GENERIC_DIRECTORY else kind
        return byKind[resolved]?.let(AdapterLookup::Found) ?: AdapterLookup.Missing(kind)
    }
}
