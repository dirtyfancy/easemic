package com.easemic.app.ui

/**
 * A40 (owner bug backlog, ledger 2026-09-06): Detail keeps the bottom bar.
 * Detail is reachable from Library, Browse, Activity and History, and the
 * single `route` state carries no origin, so the tab the user last stood on is
 * remembered and stays lit while Detail is open. The reader and the Settings
 * sub-routes keep hiding the bar.
 */
object AppBottomBarSelection {
    /**
     * The tab to light: a primary destination lights its own, Detail borrows
     * the remembered [detailOriginTab], every other bar-less route hides the
     * bar by returning null.
     */
    fun selected(
        routeDestination: AppBottomBarDestination?,
        onDetail: Boolean,
        detailOriginTab: AppBottomBarDestination,
    ): AppBottomBarDestination? = routeDestination
        ?: detailOriginTab.takeIf { onDetail }

    /**
     * Advances the remembered origin: a primary destination replaces it, a
     * bar-less route (Detail, then the reader on top of it) holds the previous
     * one so returning from either lands on the tab the user left.
     */
    fun originTab(
        routeDestination: AppBottomBarDestination?,
        current: AppBottomBarDestination,
    ): AppBottomBarDestination = routeDestination ?: current
}
