package com.easemic.app.feature.browse

import com.easemic.app.sync.CloudCollectionStore

/**
 * C34-c: which [CloudCollectionStore] the cloud reader opens through.
 *
 * The detail screen already resolved a store and its first preview thumb
 * loaded `pages.manifest.json` into that instance's memo. The memo is
 * per-instance, so a reader that builds its own store re-fetches the manifest
 * the user just waited for (~1.5 s of the first screen). Reuse the detail
 * screen's instance when there is one; build a fresh one only when the reader
 * was entered without a detail screen in front of it.
 */
object CloudReaderStoreChoice {
    fun resolve(
        supplied: CloudCollectionStore?,
        build: () -> CloudCollectionStore,
    ): CloudCollectionStore = supplied ?: build()
}
