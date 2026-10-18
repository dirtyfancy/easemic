package com.easemic.app.feature.library

import com.easemic.app.model.LibraryItem
import com.easemic.app.model.SourceId

/**
 * A30 §2.1 -- the single shelf visibility rule.
 *
 * On the shelf: the item's folder is still watched AND still readable, and its
 * location is PRESENT or INCOMPLETE (that is exactly [LibraryItem.isAvailable]).
 * Everything else is hidden, never deleted: records and reading progress stay,
 * and the structural signature re-links the comic when its files return.
 *
 * A folder is "hidden" when it is tombstoned (Stop watching) or when Android
 * took its grant away (`NEEDS_REAUTHORIZATION`). S4b/B3: access loss hides the
 * comics silently while the Folders row states it once -- the comics come back
 * the moment the grant does, with no MISSING round trip, because nothing about
 * their locations was ever changed.
 */
object ShelfVisibility {
    fun isVisible(item: LibraryItem, hiddenSourceIds: Set<SourceId>): Boolean =
        item.isAvailable && item.sourceId !in hiddenSourceIds
}
