package com.easemic.app.feature.browse

/**
 * Session identity for the Browse gallery list. Tab switches and Detail overlays
 * are not part of this key; they must not refetch.
 */
data class BrowseFeedSessionKey(
    val prefs: BrowsePrefs,
    val query: String,
    val feedUrl: String?,
    val feedProviderId: String?,
    val opdsIds: List<String>,
    val frontEnabled: Boolean,
    /** W7: toggling the ExHentai source (or its session grant) reloads the feed. */
    val exhentaiEnabled: Boolean = false,
    val indexEnabled: Boolean,
    val unlockEpoch: Int,
    /**
     * The active source's (i.e. [prefs].sourceId's) current filter state, not
     * the whole per-source map — including every source's filter here would
     * make editing an inactive source's filter spuriously reload the feed
     * that's actually on screen. Applying a filter (or dismissing a filter
     * chip) on the active source must change this field so the feed reload
     * LaunchedEffect (keyed on this class) actually re-fires; before this
     * field existed, nothing in the key derived from the filter at all, so
     * "Apply" silently no-opped until some unrelated key field also changed.
     */
    val filterState: SourceFilterState?,
    val jmSearchScope: JmSearchScope? = null,
    val searchAwaitingSubmit: Boolean = false,
)

/** Where the list lands once a feed load succeeds. */
sealed interface BrowseFeedLanding {
    data object Top : BrowseFeedLanding
    data class Restore(val index: Int, val offset: Int) : BrowseFeedLanding
}

object BrowseFeedSession {
    /** Pending scoped edits keep the submitted results visible but do not issue a request. */
    fun canStartRequest(key: BrowseFeedSessionKey): Boolean = !key.searchAwaitingSubmit

    /**
     * A repeated metadata target is deliberately not a destructive navigation.
     * The caller may still invalidate a late continuation and scroll to the top,
     * but it must not clear rows when the feed key would remain equal.
     */
    fun shouldKeepLoadedResultsForRepeatedTarget(
        currentKey: BrowseFeedSessionKey,
        target: TagSearchTarget,
    ): Boolean {
        if (currentKey.feedUrl != null || currentKey.feedProviderId != null || currentKey.searchAwaitingSubmit) {
            return false
        }
        return when (target) {
            is TagSearchTarget.Text ->
                currentKey.prefs.sourceId == target.sourceId && currentKey.query == target.query
            is TagSearchTarget.Genre ->
                currentKey.prefs.sourceId == target.sourceId &&
                    currentKey.query.isEmpty() &&
                    currentKey.filterState?.zaimanhua == ZaimanhuaFilters(genre = target.genre)
            is TagSearchTarget.JmScoped ->
                currentKey.prefs.sourceId == target.sourceId &&
                    currentKey.query == target.query &&
                    currentKey.jmSearchScope == target.scope
        }
    }

    /**
     * #12: a feed the user descended from comes back at its remembered position; a new feed
     * (query, filter or source change) starts at the top. #D3: that includes a load with no
     * previous key — a source switch clears the key first (`resetBrowseFeed`), and leaving the
     * list alone then kept the other source's scroll, so its results opened part-way down.
     */
    fun landingAfterLoad(previousKey: BrowseFeedSessionKey?, savedPosition: Pair<Int, Int>?): BrowseFeedLanding =
        if (savedPosition != null) {
            BrowseFeedLanding.Restore(savedPosition.first, savedPosition.second)
        } else {
            BrowseFeedLanding.Top
        }

    fun shouldReload(
        previous: BrowseFeedSessionKey?,
        incoming: BrowseFeedSessionKey,
    ): Boolean = previous != incoming

    fun shouldApplyLoadMoreResult(
        requestGeneration: Int,
        currentGeneration: Int,
    ): Boolean = requestGeneration == currentGeneration

    fun acceptsResult(
        requestKey: BrowseFeedSessionKey,
        currentKey: BrowseFeedSessionKey,
        requestGeneration: Int,
        currentGeneration: Int,
    ): Boolean = requestKey == currentKey && shouldApplyLoadMoreResult(requestGeneration, currentGeneration)

    /**
     * #C15 re-review item 2: an effect's (feed load and Browse probe)
     * `finally` runs late for a cancelled generation (its blocking socket
     * returns after the re-key), so it may only release `browseLoading` when
     * it is still the current one.
     */
    fun shouldReleaseLoading(requestGeneration: Int, currentGeneration: Int): Boolean =
        requestGeneration == currentGeneration

    /**
     * #C15 re-review item 1 + r3 item 2: which OPDS provider a failed feed
     * page marks unreachable — the active source, when it is an OPDS
     * provider AND nothing answered. "Unreachable" is a TRANSPORT verdict:
     * a server that answered `OPDS_HTTP_<code>` (404 on a stale subsection,
     * 401 wrong password, 500, 503) is reachable, so its page error stays a
     * level-2 message and never a persisted health fact (which level 1 and
     * Settings → Content sources would repeat). Controller decision
     * 2026-09-19, recorded in the third-state spec for owner review.
     *
     * The ENTRY PROBE has a different, stricter rule on purpose: at the
     * catalog ROOT any non-feed answer (401/404/5xx/parse) IS unreachable
     * (`OpdsCatalogConnect.probeReachability`, pinned by
     * `OpdsCatalogProbeTest`); do not "unify" them — a 401'd catalog must
     * read Unreachable, a 404'd subsection must not dim it. The
     * `OPDS_HTTP_` prefix is the carrier of the typed
     * `OpdsCatalogException.Http.code`; Android's OkHttp-backed
     * HttpURLConnection never yields -1.
     */
    fun providerToMarkUnreachableAfterLoadFailure(
        activeSourceId: String,
        opdsProviderIds: Set<String>,
        failureMessage: String?,
    ): String? = activeSourceId.takeIf { it in opdsProviderIds && failureMessage?.startsWith("OPDS_HTTP_") != true }

    /**
     * A reachable probe re-arms the active source's feed when either the last
     * load FAILED (browseError) or the source WAS dimmed when the probe was
     * started (#C15 audit MEDIUM-1): in that second case the honest empty
     * state is already showing and the load may still be in flight, so
     * nothing else would ever reload a server that just came back.
     */
    fun shouldRetryAfterProbe(
        activeSourceId: String,
        providerId: String,
        reachable: Boolean,
        browseError: String?,
        wasDimmed: Boolean,
    ): Boolean = reachable && activeSourceId == providerId && (browseError != null || wasDimmed)
}
