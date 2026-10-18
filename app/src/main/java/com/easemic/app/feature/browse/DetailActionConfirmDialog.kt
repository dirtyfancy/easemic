package com.easemic.app.feature.browse

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * Reverse-proxy cancel/delete confirmation (spec §2.3, §9). Three variants:
 * cancel collection, cancel download, delete download. Confirm/dismiss tags
 * are shared so tests and callers address the active dialog generically.
 */
@Composable
fun DetailActionConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("detail-action-confirm"),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("detail-action-dismiss"),
            ) {
                Text(dismissLabel)
            }
        },
    )
}

/** Copy locked per spec §9. */
enum class DetailActionConfirmKind(
    val title: String,
    val message: String,
) {
    /**
     * A53: this string's "30-day" figure must track
     * [com.easemic.app.sync.CloudCollectLifecycle.PURGE_AFTER_MS].
     * That constant has no copy-layer day-count formatter to derive from (see
     * [RemoteDetailHelperCopy], which hardcodes the same figure for the same
     * reason) — if the constant's value ever changes, update this string and
     * those alongside it.
     */
    CANCEL_COLLECTION(
        title = "Cancel collection?",
        message = "This removes the bookmark. The cloud copy moves to a 30-day recycle " +
            "bin; collecting again restores it.",
    ),
    CANCEL_DOWNLOAD(
        title = "Cancel download?",
        // A53: `RoomDownloadJobStore.cancel` drops only in-flight `.part`
        // temps; published READY pages stay on disk and are re-admitted on
        // the next run (the "A18 resume" admission in DownloadWorkerLoop), so
        // the old "Partially downloaded pages will be removed." claimed a
        // deletion the code does not perform.
        message = "Stops the download. Saved pages stay and resume next time.",
    ),
    DELETE_DOWNLOAD(
        title = "Delete download?",
        message = "Removes pages from this device. Bookmarks and cloud copy stay.",
    ),
}

/**
 * F4/A53: the un-collect confirm only promises a recoverable cloud copy when
 * there is one. The recycle-bin sentence is true only while a bucket is
 * bound; with R2 unbound there is nothing in the cloud to survive the
 * un-collect, so the confirm drops the second sentence rather than claiming a
 * copy that does not exist.
 */
fun cancelCollectionConfirmMessage(cloudBound: Boolean): String =
    if (cloudBound) {
        DetailActionConfirmKind.CANCEL_COLLECTION.message
    } else {
        "This removes the bookmark."
    }
