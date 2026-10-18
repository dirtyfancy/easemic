package com.easemic.app.feature.browse

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.easemic.app.i18n.tr

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
    val displayTitle = detailActionDisplay(title)
    val displayMessage = detailActionDisplay(message)
    val displayConfirm = detailActionDisplay(confirmLabel)
    val displayDismiss = detailActionDisplay(dismissLabel)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(displayTitle) },
        text = { Text(displayMessage) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("detail-action-confirm"),
            ) {
                Text(displayConfirm)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("detail-action-dismiss"),
            ) {
                Text(displayDismiss)
            }
        },
    )
}

/** Translate stable dialog copy at the render boundary; raw values remain state/test contracts. */
private fun detailActionDisplay(raw: String): String = when (raw) {
    "Cancel collection?" -> tr(raw, "取消收藏？", "取消收藏？", "コレクションを解除しますか？")
    "This removes the bookmark." -> tr(raw, "这会删除收藏标记。", "這會刪除收藏標記。", "ブックマークを削除します。")
    "This removes the bookmark. The cloud copy moves to a 30-day recycle bin; collecting again restores it." -> tr(raw, "这会删除收藏标记。云端副本会移入 30 天回收站；再次收藏即可恢复。", "這會刪除收藏標記。雲端副本會移入 30 天回收站；再次收藏即可恢復。", "ブックマークを削除します。クラウドのコピーは30日間ごみ箱に移動し、もう一度コレクションすると復元できます。")
    "The cloud copy moves to a 30-day recycle bin; collecting again restores it." -> tr(raw, "云端副本会移入 30 天回收站；再次收藏即可恢复。", "雲端副本會移入 30 天回收站；再次收藏即可恢復。", "クラウドのコピーは30日間ごみ箱に移動します。もう一度コレクションすると復元できます。")
    "Cancel download?" -> tr(raw, "取消下载？", "取消下載？", "ダウンロードをキャンセルしますか？")
    "Stops the download. Saved pages stay and resume next time." -> tr(raw, "停止下载。已保存的页面会保留，下次继续。", "停止下載。已儲存的頁面會保留，下次繼續。", "ダウンロードを停止します。保存済みページは残り、次回再開します。")
    "Delete download?" -> tr(raw, "删除下载？", "刪除下載？", "ダウンロードを削除しますか？")
    "Removes pages from this device. Bookmarks and cloud copy stay." -> tr(raw, "会从此设备删除页面；收藏标记和云端副本会保留。", "會從此裝置刪除頁面；收藏標記和雲端副本會保留。", "この端末からページを削除します。ブックマークとクラウドのコピーは残ります。")
    "Confirm" -> tr(raw, "确认", "確認", "確認")
    "Cancel" -> tr(raw, "取消", "取消", "キャンセル")
    "Delete" -> tr(raw, "删除", "刪除", "削除")
    "Sign in" -> tr(raw, "登录", "登入", "サインイン")
    "Not now" -> tr(raw, "暂不", "暫不", "今はしない")
    else -> raw
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
