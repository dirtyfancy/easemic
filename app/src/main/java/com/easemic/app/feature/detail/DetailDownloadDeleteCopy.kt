package com.easemic.app.feature.detail

import com.easemic.app.i18n.tr

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

    fun display(raw: String): String = when (raw) {
        ACTION -> tr(raw, "删除下载", "刪除下載", "ダウンロードを削除")
        CONFIRM -> tr(raw, "再次点击以删除下载。", "再次點擊以刪除下載。", "もう一度タップするとダウンロードを削除します。")
        FAILED -> tr(raw, "无法删除此下载，请重试。", "無法刪除此下載，請重試。", "このダウンロードを削除できませんでした。再試行してください。")
        else -> raw
    }
}
