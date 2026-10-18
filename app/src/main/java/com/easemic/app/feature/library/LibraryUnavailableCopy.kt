package com.easemic.app.feature.library

import com.easemic.app.i18n.AppStrings
import com.easemic.app.model.GrantKind

/**
 * What Detail says when a comic's folder is no longer reachable.
 *
 * A30 §2.2 / §4 (S4b/H6): one vocabulary, one destination, for every grant kind. The old
 * copy said "Original folder/archive unavailable" (a banned word, and a noun the user never
 * chose) and offered "Choose folder" / "Choose the archive again" -- a second, different way
 * of describing the same problem the reader now calls "Access lost". Access is repaired in
 * exactly one place, so that is where this points.
 */
object LibraryUnavailableCopy {
    val TITLE: String get() = AppStrings.accessLost
    val HINT: String get() = AppStrings.folderAccessLost
    val RECOVERY_LABEL: String get() = AppStrings.foldersOnThisPhone

    @Suppress("UNUSED_PARAMETER")
    fun coverBadge(grantKind: GrantKind): String = TITLE

    @Suppress("UNUSED_PARAMETER")
    fun statusTitle(grantKind: GrantKind): String = TITLE

    @Suppress("UNUSED_PARAMETER")
    fun statusHint(grantKind: GrantKind): String = HINT

    @Suppress("UNUSED_PARAMETER")
    fun recoveryLabel(grantKind: GrantKind): String = RECOVERY_LABEL
}
