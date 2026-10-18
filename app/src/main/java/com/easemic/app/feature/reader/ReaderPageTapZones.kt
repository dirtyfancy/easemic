package com.easemic.app.feature.reader

/**
 * HorizontalPager preloads adjacent pages. Only the settled page may expose
 * previous / center / next tap-zone semantics, otherwise TalkBack and tests
 * see three copies of each zone on wide foldables.
 */
object ReaderPageTapZones {
    fun interactive(pageIndex: Int, settledPage: Int): Boolean = pageIndex == settledPage
}
