package com.easemic.app.ui

import com.easemic.app.feature.collect.CollectedBookmark
import com.easemic.app.feature.collect.isSerializedBookmark

/**
 * Compose tests share the device process with keep-data installs. Live Cloud
 * I/O and bookmark/cloud shelf lists must stay off that composition so
 * instrumentation does not wipe [CloudProcessSession] or start OAuth on Main.
 */
object AppLiveDeviceBindings {
    fun <T> shelfItems(bindLiveDeviceSession: Boolean, live: List<T>): List<T> =
        if (bindLiveDeviceSession) live else emptyList()

    fun shouldRunCloudIo(bindLiveDeviceSession: Boolean): Boolean = bindLiveDeviceSession

    /**
     * The Library's collected cards. A live session shows every bookmark (MainActivity also passes its own graph as
     * the override, so the override alone must never change this). An override-graph device test shows only local
     * serialized follows, which need no cloud I/O; any other test composition shows none.
     */
    fun collectedShelfBookmarks(
        bindLiveDeviceSession: Boolean,
        overrideGraph: Boolean,
        bookmarks: List<CollectedBookmark>,
    ): List<CollectedBookmark> = when {
        bindLiveDeviceSession -> bookmarks
        overrideGraph -> bookmarks.filter(::isSerializedBookmark)
        else -> emptyList()
    }
}
