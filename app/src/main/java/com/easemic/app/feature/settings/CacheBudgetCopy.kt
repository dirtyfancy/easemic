package com.easemic.app.feature.settings

import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.storage.CacheBudget

object CacheBudgetCopy {
    val CLEAR: String get() = AppStrings.clearCacheAction
    val RESTART_NOTE: String
        get() = tr(
            "Image cache has its own limit and takes effect after the next app restart.",
            "图片缓存上限将在下次启动应用时生效。",
            "圖片快取上限將在下次啟動應用程式時生效。",
            "画像キャッシュの上限は次回アプリ起動時から適用されます。",
        )

    /** Whole megabytes, rounded up, so a partial megabyte still counts. */
    fun usageLabel(usageBytes: Long): String {
        val usageMb = (usageBytes + CacheBudget.BYTES_PER_MB - 1) / CacheBudget.BYTES_PER_MB
        return "$usageMb MB"
    }

    fun ofCache(limitMb: Int): String = tr(
        "of $limitMb MB cache",
        "/ $limitMb MB 缓存上限",
        "/ $limitMb MB 快取上限",
        "/ $limitMb MB キャッシュ上限",
    )
}
