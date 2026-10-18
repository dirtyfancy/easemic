package com.easemic.app.feature.library

import com.easemic.app.i18n.tr

/** User-visible copy for Library → Cloud segment (Cloud Collection layer 0). */
object LibraryCloudSegmentCopy {
    val segmentLabel: String get() = tr("Cloud", "云端", "雲端", "クラウド")
    val LIST_FAILED: String get() = tr("Cloud collection could not be listed.", "无法读取云端收藏。", "無法讀取雲端收藏。", "クラウドコレクションを読み込めませんでした。")

    /**
     * Owner bug #3 fix: shown only after the adaptive grace loop
     * ([com.easemic.app.feature.bridge.CloudProbeGrace])
     * has exhausted every retry without a single reachable R2 round-trip —
     * distinct from [LIST_FAILED], which covers a listing call that failed
     * while the bucket was presumed reachable.
     */
    val CLOUD_UNREACHABLE_AFTER_RETRIES: String get() = tr("Cloud is unreachable right now. Will keep retrying.", "云端暂时无法连接，将继续重试。", "雲端目前無法連線，將繼續重試。", "現在クラウドに接続できません。再試行を続けます。")

    /**
     * A non-blank [blocked] always wins, even when [ready] is true: a
     * bound-but-still-locked bucket is `libraryCloudReady = true` (a bucket is
     * bound), but it is not actually empty — it just hasn't been unlocked yet.
     * Claiming "no comics yet" there would lie about the library's contents.
     */
    fun emptyTitle(ready: Boolean, blocked: String? = null): String =
        if (ready && blocked.isNullOrBlank()) {
            tr("No Cloud collection comics yet.", "云端收藏中还没有漫画。", "雲端收藏中還沒有漫畫。", "クラウドコレクションにマンガはありません。")
        } else {
            tr("Cloud collection is not ready.", "云端收藏尚未就绪。", "雲端收藏尚未就緒。", "クラウドコレクションはまだ準備中です。")
        }

    fun emptyHint(ready: Boolean, blocked: String? = null): String =
        blocked?.takeIf { it.isNotBlank() } ?: if (ready) {
            tr("Collect from Browse to add comics here.", "在浏览页收藏漫画后会显示在这里。", "在瀏覽頁收藏漫畫後會顯示在這裡。", "ブラウズでマンガをコレクションするとここに表示されます。")
        } else {
            ""
        }
}
