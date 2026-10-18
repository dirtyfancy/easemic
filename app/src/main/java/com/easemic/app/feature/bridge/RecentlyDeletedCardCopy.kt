package com.easemic.app.feature.bridge

import com.easemic.app.i18n.tr

/**
 * Task 14 (browse-remaining §4): copy for the collapsible
 * "Recently deleted · N" card inside Settings → Cloud storage. The list is a
 * live R2 trash listing with no local mirror, so the only honest degraded
 * state is the single [UNREACHABLE] status line — never a fabricated count
 * or a cached grid.
 */
object RecentlyDeletedCardCopy {
    const val UNREACHABLE = "Cloud unreachable — recently deleted list is unavailable."
    const val RESTORE = "Restore"
    const val DELETE_FOREVER = "Delete forever"
    const val DELETE_FOREVER_CONFIRM = "Tap again to delete forever."

    fun title(count: Int): String = tr("Recently deleted · $count", "最近删除 · $count", "最近刪除 · $count", "最近削除 · $count")

    /**
     * English-only relative deletion age (dev stage), same bucketing as the
     * Browse E-Hentai subline. A trash card always carries its tombstone
     * stamp; null is defensive and stays honest ("recently", no fake time).
     */
    fun deletedLine(trashedAtEpochMs: Long?, nowEpochMs: Long): String {
        val at = trashedAtEpochMs ?: return tr("Deleted recently", "最近删除", "最近刪除", "最近削除")
        val delta = nowEpochMs - at
        val seconds = delta / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        val relative = when {
            delta < 0 -> tr("just now", "刚刚", "剛剛", "たった今")
            seconds < 60 -> tr("just now", "刚刚", "剛剛", "たった今")
            minutes < 60 -> tr("$minutes min ago", "$minutes 分钟前", "$minutes 分鐘前", "$minutes 分前")
            hours < 24 -> tr("$hours hours ago", "$hours 小时前", "$hours 小時前", "$hours 時間前")
            days < 30 -> tr("$days days ago", "$days 天前", "$days 天前", "$days 日前")
            else -> {
                val months = days / 30
                if (months < 12) tr("$months months ago", "$months 个月前", "$months 個月前", "$months か月前") else tr("${days / 365} years ago", "${days / 365} 年前", "${days / 365} 年前", "${days / 365} 年前")
            }
        }
        return "Deleted $relative"
    }
}
