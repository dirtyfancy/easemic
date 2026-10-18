package com.easemic.app.feature.reader

import com.easemic.app.i18n.AppStrings

object ReaderIncompleteCopy {
    fun title(): String = AppStrings.incomplete

    /** A30 §2.2: folders, not "sources" -- that screen is retired. */
    fun hint(): String = AppStrings.rescanFolderHint

    /**
     * Extra E4: the destination is the Folders screen (A30 §2.2); "View
     * sources" named a screen that no longer exists.
     */
    fun cta(): String = AppStrings.foldersOnThisPhone

    /**
     * A36 — why a CATALOG DOWNLOAD is short. [hint] and [cta] are about a
     * watched folder: rescanning one is the repair when a folder lost pages.
     * A download has no folder to rescan and nothing to repair — the source
     * withdrew the pages — so offering that hint (or a button to the Folders
     * screen, which does not even list the download tree) would be an
     * instruction the user cannot carry out.
     *
     * This is the same clause [com.easemic.app.feature.detail.LocalDetailCopy.truncatedSealLine]
     * ends with, kept in one place so the reader and the Detail can never
     * drift into two different accounts of the same fact; the Detail sentence
     * lowercases it because it follows the counts.
     */
    fun downloadHint(): String = AppStrings.sourceRestGone
}
