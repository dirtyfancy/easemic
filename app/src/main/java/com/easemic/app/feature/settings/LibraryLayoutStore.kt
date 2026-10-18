package com.easemic.app.feature.settings

import com.easemic.app.ui.LibraryViewMode
import com.easemic.app.ui.PreferenceMap

/**
 * Durable home for the Library grid/list display preference (Quiet Shelf spec
 * §7): the toggle no longer lives in the Library tab — this store is the single
 * source of truth that both the Settings "Library layout" Display row and the
 * Library shelf read. Unknown/absent values fall back to GRID.
 */
class LibraryLayoutStore(
    private val prefs: PreferenceMap,
) {
    /**
     * One-shot migration (audit F3): the T7 rename (`library_view_mode` →
     * `library_layout`) would silently revert LIST users to GRID on upgrade.
     * On first read after upgrade, the old key's value is copied under the new
     * key; thereafter only the new key is consulted.
     */
    fun read(): LibraryViewMode {
        val current = prefs.getString(KEY, null)
        if (current != null) return decode(current)
        val legacy = prefs.getString(LEGACY_KEY, null)
        if (legacy != null) {
            val migrated = decode(legacy)
            prefs.putString(KEY, migrated.name)
            return migrated
        }
        return LibraryViewMode.GRID
    }

    fun write(mode: LibraryViewMode) {
        prefs.putString(KEY, mode.name)
    }

    private fun decode(raw: String): LibraryViewMode =
        when (raw) {
            LibraryViewMode.LIST.name -> LibraryViewMode.LIST
            else -> LibraryViewMode.GRID
        }

    companion object {
        const val KEY = "library_layout"

        /** Pre-T7 key written by the retired `LibraryViewModeStore`. */
        const val LEGACY_KEY = "library_view_mode"
    }
}
