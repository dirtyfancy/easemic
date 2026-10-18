package com.easemic.app.feature.source

import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.providers.OpdsProviderConfig

/**
 * The app's one source registry (#E2 Slice 2), owned by AppGraph for the life of the process. Built-in sources are
 * single instances that read their changing inputs (account, enable switches, bucket) per call. OPDS sources follow
 * the provider list: one assembly per list snapshot, so the snapshot's shared Browse delegate — and its OpenSearch
 * template cache — lives as long as the providers are unchanged. Browse, the download lanes and liveness checks look
 * sources up concurrently, hence the lock.
 */
internal class AppGraphComicSourceRegistry(
    private val builtIns: List<ComicSource>,
    private val opdsProviders: () -> List<OpdsProviderConfig>,
    private val opdsSources: (List<OpdsProviderConfig>) -> List<ComicSource>,
) : ComicSourceRegistry {
    private val lock = Any()
    private var opdsSnapshot: List<OpdsProviderConfig>? = null
    private var opdsMemo: List<ComicSource> = emptyList()

    override fun source(namespace: String): ComicSource? =
        if (namespace.startsWith(GalleryIdentityV1.OPDS_PREFIX)) {
            currentOpds().firstOrNull { it.descriptor.namespace == namespace }
        } else {
            builtIns.firstOrNull { it.descriptor.namespace == namespace }
        }

    override fun all(): List<ComicSource> = builtIns + currentOpds()

    private fun currentOpds(): List<ComicSource> {
        val providers = opdsProviders()
        synchronized(lock) {
            if (providers != opdsSnapshot) {
                opdsMemo = opdsSources(providers)
                opdsSnapshot = providers
            }
            return opdsMemo
        }
    }
}

