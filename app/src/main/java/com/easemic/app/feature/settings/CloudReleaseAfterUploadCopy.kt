package com.easemic.app.feature.settings

import com.easemic.app.i18n.tr

/**
 * #C4a More-tab row copy for [CloudReleaseAfterUploadStore]. The row is only
 * rendered once a cloud library is bound, so nothing here has to explain
 * binding; it explains what happens to the file on the phone, which is the
 * part that costs the user data if they misread it.
 */
object CloudReleaseAfterUploadCopy {
    val TITLE: String
        get() = tr(
            "Keep only the cloud copy",
            "上传后释放本机空间",
            "上傳後釋放本機空間",
            "クラウドのコピーのみ残す",
        )

    /**
     * The one-time prompt shown when the setting is switched ON. Scoped
     * exactly to what [com.easemic.app.feature.collect.CloudReleaseCatchUpSweep]
     * actually does: comics that are on this device AND already uploaded. A
     * comic that was only ever downloaded is never touched, and this copy must
     * not imply otherwise.
     */
    val CATCH_UP_TITLE: String
        get() = tr(
            "Clear local copies too?",
            "同时清理已上传漫画的本机副本？",
            "同時清理已上傳漫畫的本機副本？",
            "アップロード済みの端末内コピーも消去しますか？",
        )
    val CATCH_UP_BODY: String
        get() = tr(
            "Comics already uploaded to your cloud library still have a copy on this phone. " +
                "Clear those local copies now? Comics you only downloaded are left alone.",
            "已上传到云库的漫画在这台手机上仍保留有本地副本。现在清理这些副本吗？仅下载到本地、未上传云库的漫画不会受影响。",
            "已上傳至雲端書庫的漫畫在這台手機上仍保留有本機副本。現在清理這些副本嗎？僅下載至本機、未上傳雲端的漫畫不會受影響。",
            "クラウドライブラリにアップロード済みのマンガがこの端末にも残っています。今すぐ端末内のコピーを消去しますか？ダウンロードのみのマンガはそのまま残ります。",
        )
    val CATCH_UP_CONFIRM: String
        get() = tr("Clear them", "立即清理", "立即清理", "消去する")
    val CATCH_UP_DISMISS: String
        get() = tr("Keep them", "保留本机副本", "保留本機副本", "そのまま残す")

    /** #D8: one line on the settings row. It does not restate on versus off. */
    val SUBTITLE: String
        get() = tr(
            "Frees phone space after upload",
            "上传到云库后自动释放手机空间",
            "上傳至雲端後自動釋放手機空間",
            "アップロード後に端末の空き容量を確保します",
        )

    /** Result line after the one-time catch-up run. */
    fun catchUpSummary(released: Int, failed: Int): String {
        if (released == 0 && failed == 0) {
            return tr("No local copies to clear.", "没有可清理的本机副本。", "沒有可清理的本機副本。", "消去できる端末内コピーはありません。")
        }
        val cleared = if (released == 1) {
            tr("Cleared 1 local copy", "已清理 1 部本机副本", "已清理 1 部本機副本", "端末内コピー 1 件を消去しました")
        } else {
            tr("Cleared $released local copies", "已清理 $released 部本机副本", "已清理 $released 部本機副本", "端末内コピー $released 件を消去しました")
        }
        return if (failed == 0) {
            "$cleared."
        } else {
            tr(
                "$cleared · $failed could not be cleared.",
                "$cleared · $failed 部未能清理。",
                "$cleared · $failed 部未能清理。",
                "$cleared · $failed 件は消去できませんでした。",
            )
        }
    }
}
