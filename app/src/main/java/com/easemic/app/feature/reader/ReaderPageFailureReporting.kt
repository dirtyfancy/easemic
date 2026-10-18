package com.easemic.app.feature.reader

/**
 * HorizontalPager composes adjacent pages for swipe. Only the settled page
 * may report [ReaderScreen] open failures — prefetch/off-screen decode
 * errors stay silent (accepted Coil preload spec).
 */
object ReaderPageFailureReporting {
    fun shouldReportOpenFailure(pageIndex: Int, settledPage: Int): Boolean =
        pageIndex == settledPage
}
