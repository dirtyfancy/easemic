package com.easemic.app.feature.settings

import com.easemic.app.i18n.tr

/**
 * Settings "At the same time" row copy (owner bugs A7, A50b). The row shows the
 * number; this subtitle is no longer on the screen.
 */
object MaxConcurrentTransfersCopy {
    val TITLE: String
        get() = tr(
            "At the same time",
            "同时传输数",
            "同時傳輸數",
            "同時転送数",
        )
    const val DECREMENT = "-"
    const val INCREMENT = "+"

    fun subtitle(value: Int): String =
        if (value <= 1) {
            tr(
                "One download or upload at a time.",
                "每次进行 1 个下载或上传。",
                "每次進行 1 個下載或上傳。",
                "同時に1件ずつダウンロードまたはアップロードします。",
            )
        } else {
            tr(
                "Up to $value downloads or uploads at the same time.",
                "最多同时进行 $value 个下载或上传。",
                "最多同時進行 $value 個下載或上傳。",
                "同時に最大 $value 件までダウンロードまたはアップロードします。",
            )
        }
}
