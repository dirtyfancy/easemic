package com.easemic.app.feature.detail

/**
 * A30 §2.3: the only file-deleting verb in the app, and it only ever targets
 * the app's own catalog download -- never a folder the user imported.
 */
object DetailDownloadDeleteCopy {
    const val ACTION = "Delete download"
    const val CONFIRM = "Tap again to delete download."

    /**
     * J5: the delete was armed, confirmed, and did not happen. States that and
     * offers the one thing that might work, without an exclamation mark and
     * without guessing at a cause the user cannot act on.
     */
    const val FAILED = "Could not delete this download. Try again."
}
