package com.easemic.app.feature.download

/**
 * Commits / removes Library local-availability for finished downloads (spec §4.6).
 *
 * Must not Collect, check entitlement, or mutate Cloud Collection. Must not
 * silently merge Import/SAF ownership into a [GalleryIdentityV1] association.
 */
interface DownloadLibraryCommitter {
    fun commitOnFinish(
        identity: GalleryIdentityV1,
        downloadLocationUri: String,
        downloadRevision: Int,
    ): DownloadLibraryAssociation

    /** Removes download-owned association only. Returns false if nothing removed. */
    fun removeDownloadOwned(identity: GalleryIdentityV1): Boolean

    fun associationFor(identity: GalleryIdentityV1): DownloadLibraryAssociation?

    fun onDeviceAssociationCount(identity: GalleryIdentityV1): Int

    fun isLocallyAvailable(identity: GalleryIdentityV1): Boolean =
        associationFor(identity)?.locallyAvailable == true
}

/**
 * JVM / in-process committer used by [DownloadCore] unit tests and as the
 * default when Room is not wired. Tracks Import ownership separately so overlap
 * tests prove no silent merge.
 */
class InMemoryDownloadLibraryCommitter(
    private val onBookmarkMutation: () -> Unit = {},
    private val onCloudMutation: () -> Unit = {},
    private val onHistoryMutation: () -> Unit = {},
) : DownloadLibraryCommitter {
    private val downloadAssociations = mutableListOf<DownloadLibraryAssociation>()
    private val importOwnership = mutableMapOf<String, String>() // key → locationUri

    /**
     * Seeds an Import/SAF-owned Library card. Distinct ownership from download
     * associations even when [locationUri] overlaps finished download paths.
     */
    fun seedImportOwnership(locationUri: String, ownershipKey: String): String {
        importOwnership[ownershipKey] = locationUri
        return ownershipKey
    }

    fun importOwnershipIntact(ownershipKey: String): Boolean =
        importOwnership.containsKey(ownershipKey)

    fun totalAssociationCards(): Int =
        downloadAssociations.count { it.locallyAvailable } + importOwnership.size

    override fun commitOnFinish(
        identity: GalleryIdentityV1,
        downloadLocationUri: String,
        downloadRevision: Int,
    ): DownloadLibraryAssociation {
        // Never adopt Import ownership — always mint a download-scoped key.
        val association = DownloadLibraryAssociation(
            identity = identity,
            downloadLocationUri = downloadLocationUri,
            downloadRevision = downloadRevision,
            locallyAvailable = true,
            ownershipKey = downloadOwnershipKey(identity),
        )
        downloadAssociations.removeAll { it.identity == identity }
        downloadAssociations += association
        return association
    }

    override fun removeDownloadOwned(identity: GalleryIdentityV1): Boolean {
        // Download delete must not touch bookmarks / cloud / history.
        // Intentionally do not invoke side-effect probes as mutations — callers
        // that wrongly touch them will be caught by tests wiring counters.
        val removed = downloadAssociations.removeAll { it.identity == identity }
        // Prove we never mutate Import ownership on download delete.
        return removed
    }

    override fun associationFor(identity: GalleryIdentityV1): DownloadLibraryAssociation? =
        downloadAssociations.firstOrNull { it.identity == identity }

    override fun onDeviceAssociationCount(identity: GalleryIdentityV1): Int =
        downloadAssociations.count { it.identity == identity && it.locallyAvailable }

    /** Test hooks: call only when deliberately asserting non-touch. */
    fun touchBookmark() = onBookmarkMutation()
    fun touchCloud() = onCloudMutation()
    fun touchHistory() = onHistoryMutation()
}
