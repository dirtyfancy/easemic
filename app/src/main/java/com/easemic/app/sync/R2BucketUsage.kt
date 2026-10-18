package com.easemic.app.sync

import com.easemic.app.i18n.tr

import java.util.Locale

data class R2BucketUsage(
    val objectCount: Int,
    val totalBytes: Long,
)

object R2BucketUsageCopy {
    val MEASURING: String get() = tr("Measuring storage…", "正在统计存储用量…", "正在統計儲存用量…", "ストレージ使用量を計測中…")
    val LIST_FAILED: String get() = tr("Could not list storage used.", "无法统计已用存储。", "無法統計已用儲存空間。", "使用中のストレージを取得できませんでした。")
    val HELPER: String get() = tr(
        "Counts every object in the bucket, recycle bin included. Not your plan quota.",
        "统计存储桶里的全部对象（含回收站），不是你的套餐额度。",
        "統計儲存桶中的全部物件（含資源回收筒），不是你的方案額度。",
        "ゴミ箱を含むバケット内の全オブジェクトの合計です。プランの上限ではありません。",
    )

    private fun scaled(bytes: Long): Pair<String, String> {
        val normalized = bytes.coerceAtLeast(0L)
        return when {
            normalized < 1024L -> normalized.toString() to "B"
            normalized < 1024L * 1024L ->
                String.format(Locale.US, "%.1f", normalized / 1024.0) to "KB"
            normalized < 1024L * 1024L * 1024L ->
                String.format(Locale.US, "%.1f", normalized / (1024.0 * 1024.0)) to "MB"
            else ->
                String.format(Locale.US, "%.2f", normalized / (1024.0 * 1024.0 * 1024.0)) to "GB"
        }
    }

    /** Unit-scaled number alone ("48.3") for the stat-led bound display. */
    fun statValue(bytes: Long): String = scaled(bytes).first

    fun statUnit(bytes: Long): String = scaled(bytes).second

    /** Prefix only: the screen appends the bucket name with its own styling. */
    fun objectsPrefix(count: Int): String = "$count objects in "
}
