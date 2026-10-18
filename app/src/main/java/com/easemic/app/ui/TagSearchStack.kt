package com.easemic.app.ui

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.BrowseFeedNotice
import com.easemic.app.feature.browse.BrowseFeedSessionKey
import com.easemic.app.feature.browse.BrowsePrefs
import com.easemic.app.feature.browse.JmSearchSession
import com.easemic.app.feature.browse.SourceFilterState
import com.easemic.app.feature.browse.TagSearchTarget
import com.easemic.app.feature.browse.filtersAfterTagSearch

internal data class TagSearchBrowseSnapshot(
    val prefs: BrowsePrefs,
    val filterStates: Map<String, SourceFilterState>,
    val query: String,
    val jmSearchSession: JmSearchSession,
    val feedUrl: String?,
    val feedProviderId: String?,
    val sourceListVisible: Boolean,
    val loadedKey: BrowseFeedSessionKey?,
    val results: List<BrowseFeedItem>,
    val nextContinuation: String?,
    val knownTotalItems: Int?,
    val loadMoreError: String?,
    val error: String?,
    val notice: BrowseFeedNotice?,
    val scroll: Pair<Int, Int>?,
)

internal sealed interface TagSearchDetailSnapshot {
    val token: String

    data class Remote(
        override val token: String,
        val item: BrowseFeedItem,
        val route: AppRoute,
        val originAcquisition: String?,
        val originProviderId: String?,
        val serializedReaderOriginRoute: AppRoute?,
    ) : TagSearchDetailSnapshot

    data class Local(
        override val token: String,
        val route: AppRoute.Detail,
    ) : TagSearchDetailSnapshot
}

internal sealed interface TagSearchSurface {
    data class Results(
        val browse: TagSearchBrowseSnapshot,
        val target: TagSearchTarget,
        /** The layer's own session as it stood when a result detail was opened
         * above it. Back must reveal this layer's results, query, and filter —
         * not a feed rebuilt from the root snapshot captured at push time. */
        val transient: TransientBrowseSession,
    ) : TagSearchSurface

    data class Detail(val snapshot: TagSearchDetailSnapshot) : TagSearchSurface
}

internal data class TagSearchStack(
    val surfaces: List<TagSearchSurface> = emptyList(),
) {
    fun pushSearch(
        detail: TagSearchDetailSnapshot,
        browse: TagSearchBrowseSnapshot,
        target: TagSearchTarget,
        transient: TransientBrowseSession,
    ): TagSearchStack = copy(
        surfaces = surfaces + if (surfaces.lastOrNull() == TagSearchSurface.Detail(detail)) {
            listOf(TagSearchSurface.Results(browse, target, transient))
        } else {
            listOf(TagSearchSurface.Detail(detail), TagSearchSurface.Results(browse, target, transient))
        },
    )

    fun pushResultDetail(detail: TagSearchDetailSnapshot): TagSearchStack =
        copy(surfaces = surfaces + TagSearchSurface.Detail(detail))

    fun pop(): TagSearchStack =
        copy(surfaces = surfaces.dropLast(1))

    fun top(): TagSearchSurface? = surfaces.lastOrNull()
}

/**
 * Browse state owned by a metadata-search layer. It deliberately has no
 * persistence hooks: leaving the layer discards it and the captured root
 * snapshot is restored instead.
 */
internal data class TransientBrowseSession(
    val prefs: BrowsePrefs,
    val query: String,
    val filterState: SourceFilterState,
    val jmSearchSession: JmSearchSession,
    val feedUrl: String? = null,
    val feedProviderId: String? = null,
    val results: List<BrowseFeedItem> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val nextContinuation: String? = null,
    val knownTotalItems: Int? = null,
    val loadMoreError: String? = null,
    val error: String? = null,
    val notice: BrowseFeedNotice? = null,
    val loadedKey: BrowseFeedSessionKey? = null,
    val generation: Int = 0,
    val scroll: Pair<Int, Int>? = null,
    /** False until the saved filter of [prefs.sourceId] is known. A layer pushed
     * from a History, Library, or Updates detail cannot read it from the root
     * filter map, and loading with assumed defaults would hand the source
     * adapters a wrong filter (an ordinary JM search instead of the saved
     * sort/time), so the layer holds its load until the resolution read. */
    val filterResolved: Boolean = true,
) {
    companion object {
        fun fromTarget(
            target: TagSearchTarget,
            savedFilters: SourceFilterState,
            filterResolved: Boolean,
        ): TransientBrowseSession {
            val query = when (target) {
                is TagSearchTarget.Text -> target.query
                is TagSearchTarget.JmScoped -> target.query
                is TagSearchTarget.Genre -> ""
            }
            val scope = (target as? TagSearchTarget.JmScoped)?.scope
            return TransientBrowseSession(
                prefs = BrowsePrefs(sourceId = target.sourceId),
                query = query,
                filterState = filtersAfterTagSearch(savedFilters, target),
                jmSearchSession = JmSearchSession(scope = scope),
                filterResolved = filterResolved,
            )
        }
    }
}
