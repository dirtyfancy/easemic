package com.easemic.app.feature.library

/**
 * The shelf honors one shared Grid/List view mode (pushed in from the
 * Settings "Library layout" store since Task 7 — the Library tab itself no
 * longer renders a toggle for it).
 */
object LibraryShelfChrome {
    fun honorsSharedViewMode(segment: LibrarySegment): Boolean =
        segment in LibrarySegment.entries
}
