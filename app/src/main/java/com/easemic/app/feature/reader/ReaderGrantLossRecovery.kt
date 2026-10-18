package com.easemic.app.feature.reader

import com.easemic.app.i18n.AppStrings

/**
 * What the reader says, and where it sends the user, when a page cannot be opened.
 *
 * A30 §2.4 / §4 (S4b/G8): one vocabulary for both grant kinds. The old copy told archive
 * users to "Choose the archive again" and described a lost archive grant as "Folder access"
 * -- a retired verb and the wrong noun. Access problems now name the surface that fixes
 * them ("Folders on this phone") and nothing else.
 */
object ReaderGrantLossRecovery {
    val PRIMARY_LABEL: String get() = AppStrings.backToLibrary

    /** The one place access is repaired (A30 §2.2); also [ReaderIncompleteCopy.cta]. */
    val FOLDERS_LABEL: String get() = AppStrings.foldersOnThisPhone

    /**
     * True when the way out is the Folders screen rather than an in-place retry or a plain
     * return to the shelf.
     */
    fun usesFolderRecovery(failure: ReaderPageFailure): Boolean = when (failure) {
        ReaderPageFailure.REAUTHORIZE,
        ReaderPageFailure.SOURCE_CHANGED,
        -> true
        // A30 §2.4: files gone never renders a banner at all -- the reader leaves and the
        // shelf states it once. There is nothing to rebind and nothing to retry.
        ReaderPageFailure.FILES_GONE,
        ReaderPageFailure.PROVIDER_UNAVAILABLE,
        ReaderPageFailure.INSUFFICIENT_SPACE,
        ReaderPageFailure.UNSUPPORTED_PAGE,
        ReaderPageFailure.RENDER_FAILED,
        -> false
    }

    /** First line: what happened, stated once, with no cause the user cannot act on. */
    fun userMessage(failure: ReaderPageFailure): String = when (failure) {
        ReaderPageFailure.REAUTHORIZE -> AppStrings.accessLost
        ReaderPageFailure.SOURCE_CHANGED -> AppStrings.sourceChangedOnPhone
        ReaderPageFailure.PROVIDER_UNAVAILABLE -> AppStrings.storageNotResponding
        ReaderPageFailure.FILES_GONE -> AppStrings.filesGoneFromPhone
        ReaderPageFailure.INSUFFICIENT_SPACE ->
            AppStrings.insufficientTemporarySpace
        ReaderPageFailure.UNSUPPORTED_PAGE -> AppStrings.unsupportedPageFormat
        ReaderPageFailure.RENDER_FAILED -> AppStrings.pageCouldNotRender
    }

    /** Second line: the instruction, in the imperative, or nothing when there is none. */
    fun secondaryHint(failure: ReaderPageFailure): String? = when (failure) {
        ReaderPageFailure.REAUTHORIZE ->
            AppStrings.folderAccessLost
        ReaderPageFailure.SOURCE_CHANGED -> AppStrings.rescanThenOpen
        ReaderPageFailure.PROVIDER_UNAVAILABLE -> AppStrings.tryAgainMoment
        ReaderPageFailure.INSUFFICIENT_SPACE -> AppStrings.freeDeviceSpace
        ReaderPageFailure.UNSUPPORTED_PAGE -> AppStrings.openAnotherLibraryPage
        ReaderPageFailure.FILES_GONE,
        ReaderPageFailure.RENDER_FAILED,
        -> null
    }
}
