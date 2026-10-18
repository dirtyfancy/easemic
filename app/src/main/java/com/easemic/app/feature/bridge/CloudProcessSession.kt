package com.easemic.app.feature.bridge

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.sync.CloudCollectionCard
import com.easemic.app.sync.ObjectStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped Cloud Collection I/O. Compose `remember` is wiped when the
 * Activity is recreated (rotation, locale, …); this holder is not.
 */
class CloudProcessSession {
    /**
     * Single source of truth for the cached OAuth REST store: a
     * [MutableStateFlow] so Compose can observe it directly
     * (`oauthRestCacheFlow.collectAsStateWithLifecycle()`) instead of
     * mirroring it into a separate compose-local `remember` var that could
     * desync from this session's copy.
     */
    private val _oauthRestCache = MutableStateFlow<Pair<String, ObjectStore>?>(null)
    val oauthRestCacheFlow: StateFlow<Pair<String, ObjectStore>?> = _oauthRestCache.asStateFlow()
    var oauthRestCache: Pair<String, ObjectStore>?
        get() = _oauthRestCache.value
        set(value) { _oauthRestCache.value = value }
    var libraryCloudItems: List<BrowseFeedItem> = emptyList()
    /**
     * C29 audit MEDIUM-1: the RAW mirror rows behind [libraryCloudItems].
     * Without them the shelf's seed was destroyed on first composition by the
     * effect that re-maps cards to items — it ran with an empty card list
     * before Room's flow had emitted, so a rotation blanked the shelf and
     * refilled it.
     */
    var libraryCloudCards: List<CloudCollectionCard> = emptyList()
    /**
     * C29 review MEDIUM-1: the bucket [libraryCloudCards] was captured for. A
     * process memo belongs to exactly ONE bucket — a direct rebind A -> B moves
     * the fingerprint with no unbound step in between, so without this the
     * shelf (and now the memo) would keep painting A's tiles on B.
     */
    var libraryCloudFingerprint: String? = null
    var libraryCloudTrashCards: List<CloudCollectionCard> = emptyList()
    var libraryRecycleItems: List<BrowseFeedItem> = emptyList()
    var libraryCloudReady: Boolean = false
    var libraryCloudBlocked: String? = null
    /** #D16: the shelf's cloud check settled on "the drive refused the saved sign-in". */
    var libraryCloudSignInRejected: Boolean = false
    var bridgeR2BucketLabel: String? = null

    fun reset() {
        oauthRestCache = null
        libraryCloudItems = emptyList()
        libraryCloudCards = emptyList()
        libraryCloudFingerprint = null
        libraryCloudTrashCards = emptyList()
        libraryRecycleItems = emptyList()
        libraryCloudReady = false
        libraryCloudBlocked = null
        libraryCloudSignInRejected = false
        bridgeR2BucketLabel = null
    }
}
