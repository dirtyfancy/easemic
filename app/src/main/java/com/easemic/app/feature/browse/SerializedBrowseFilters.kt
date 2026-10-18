package com.easemic.app.feature.browse

import com.easemic.app.i18n.tr

interface FilterLabel { val label: String }
enum class ZSort(override val label: String) : FilterLabel { UPDATED("Recently updated"), POPULAR("Popular") }
enum class ZRegion(override val label: String) : FilterLabel {
    ANY("Any"), JAPAN("Japan"), KOREA("Korea"), EUROPE_NORTH_AMERICA("Europe & North America"),
    HONG_KONG_TAIWAN("Hong Kong & Taiwan"), MAINLAND_CHINA("Mainland China"), OTHER("Other")
}
enum class ZAudience(override val label: String) : FilterLabel {
    ANY("Any"), SHONEN("Shonen"), SHOJO("Shojo"), SEINEN("Seinen"), JOSEI("Josei")
}
enum class ZStatus(override val label: String) : FilterLabel {
    ANY("Any"), ONGOING("Ongoing"), COMPLETED("Completed"), SHORT_STORIES("Short stories")
}
enum class ZRanking(override val label: String) : FilterLabel { TODAY("Today"), WEEK("This week"), MONTH("This month") }
data class ZGenre(val id: Long, override val label: String) : FilterLabel {
    companion object { val ANY = ZGenre(0, "Any") }
}
data class ZaimanhuaFilters(
    val sort: ZSort = ZSort.UPDATED,
    val genre: ZGenre = ZGenre.ANY,
    val region: ZRegion = ZRegion.ANY,
    val audience: ZAudience = ZAudience.ANY,
    val status: ZStatus = ZStatus.ANY,
    val ranking: ZRanking? = null,
) {
    val hasCatalogFilters: Boolean get() = sort != ZSort.UPDATED || genre.id != 0L ||
        region != ZRegion.ANY || audience != ZAudience.ANY || status != ZStatus.ANY
}
enum class JCategory(override val label: String) : FilterLabel {
    ALL("All"), DOUJINSHI("Doujinshi"), STANDALONE("Standalone"), SHORT_STORIES("Short stories"),
    KOREAN("Korean comics"), WESTERN("Western comics"), OTHER("Other")
}
enum class JSort(override val label: String) : FilterLabel {
    RECENT("Recent"), VIEWS("Most viewed"), LIKES("Most liked"), PAGES("Most pages")
}
enum class JTime(override val label: String) : FilterLabel {
    ALL("All time"), TODAY("Today"), WEEK("This week"), MONTH("This month")
}
data class JmFilters(
    val category: JCategory = JCategory.ALL,
    val sort: JSort = JSort.RECENT,
    val time: JTime = JTime.ALL,
) {
    val effectiveTime: JTime get() = if (sort == JSort.VIEWS) time else JTime.ALL
}

fun ZSort.displayLabel(): String = when (this) {
    ZSort.UPDATED -> tr("Recently updated", "最近更新", "最近更新", "最近更新")
    ZSort.POPULAR -> tr("Popular", "热门", "熱門", "人気")
}
fun ZRegion.displayLabel(): String = when (this) {
    ZRegion.ANY -> tr("Any", "任意", "不限", "指定なし")
    ZRegion.JAPAN -> tr("Japan", "日本", "日本", "日本")
    ZRegion.KOREA -> tr("Korea", "韩国", "韓國", "韓国")
    ZRegion.EUROPE_NORTH_AMERICA -> tr("Europe & North America", "欧美", "歐美", "欧米")
    ZRegion.HONG_KONG_TAIWAN -> tr("Hong Kong & Taiwan", "港台", "港澳台", "香港・台湾")
    ZRegion.MAINLAND_CHINA -> tr("Mainland China", "中国大陆", "中國大陸", "中国本土")
    ZRegion.OTHER -> tr("Other", "其他", "其他", "その他")
}
fun ZAudience.displayLabel(): String = when (this) {
    ZAudience.ANY -> tr("Any", "任意", "不限", "指定なし")
    ZAudience.SHONEN -> tr("Shonen", "少年", "少年", "少年")
    ZAudience.SHOJO -> tr("Shojo", "少女", "少女", "少女")
    ZAudience.SEINEN -> tr("Seinen", "青年", "青年", "青年")
    ZAudience.JOSEI -> tr("Josei", "女性", "女性", "女性")
}
fun ZStatus.displayLabel(): String = when (this) {
    ZStatus.ANY -> tr("Any", "任意", "不限", "指定なし")
    ZStatus.ONGOING -> tr("Ongoing", "连载中", "連載中", "連載中")
    ZStatus.COMPLETED -> tr("Completed", "已完结", "已完結", "完結")
    ZStatus.SHORT_STORIES -> tr("Short stories", "短篇", "短篇", "短編")
}
fun ZRanking.displayLabel(): String = when (this) {
    ZRanking.TODAY -> tr("Today", "今天", "今天", "今日")
    ZRanking.WEEK -> tr("This week", "本周", "本週", "今週")
    ZRanking.MONTH -> tr("This month", "本月", "本月", "今月")
}
fun JCategory.displayLabel(): String = when (this) {
    JCategory.ALL -> tr("All", "全部", "全部", "すべて")
    JCategory.DOUJINSHI -> tr("Doujinshi", "同人志", "同人誌", "同人誌")
    JCategory.STANDALONE -> tr("Standalone", "单行本", "單行本", "単行本")
    JCategory.SHORT_STORIES -> tr("Short stories", "短篇", "短篇", "短編")
    JCategory.KOREAN -> tr("Korean comics", "韩国漫画", "韓國漫畫", "韓国マンガ")
    JCategory.WESTERN -> tr("Western comics", "欧美漫画", "歐美漫畫", "欧米マンガ")
    JCategory.OTHER -> tr("Other", "其他", "其他", "その他")
}
fun JSort.displayLabel(): String = when (this) {
    JSort.RECENT -> tr("Recent", "最近", "最近", "新着")
    JSort.VIEWS -> tr("Most viewed", "浏览最多", "瀏覽最多", "閲覧数順")
    JSort.LIKES -> tr("Most liked", "点赞最多", "按讚最多", "いいね順")
    JSort.PAGES -> tr("Most pages", "页数最多", "頁數最多", "ページ数順")
}
fun JTime.displayLabel(): String = when (this) {
    JTime.ALL -> tr("All time", "全部时间", "全部時間", "期間指定なし")
    JTime.TODAY -> tr("Today", "今天", "今天", "今日")
    JTime.WEEK -> tr("This week", "本周", "本週", "今週")
    JTime.MONTH -> tr("This month", "本月", "本月", "今月")
}
