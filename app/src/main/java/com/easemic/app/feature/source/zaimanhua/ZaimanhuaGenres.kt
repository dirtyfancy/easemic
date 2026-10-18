package com.easemic.app.feature.source.zaimanhua

import com.easemic.app.feature.browse.ZGenre
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.ui.PreferenceMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal class ZaimanhuaGenres(
    private val prefs: PreferenceMap,
    private val fetch: suspend () -> List<ZGenre>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val saved = readSaved()
    private val mutableChoices = MutableStateFlow(saved?.second ?: BUNDLED)
    val choices = mutableChoices.asStateFlow()

    suspend fun refreshIfDue() = lock.withLock {
        val now = clock()
        val success = readSaved()?.first
        val attempt = prefs.getString(ATTEMPT, null)?.toLongOrNull()
        val last = listOfNotNull(success, attempt).maxOrNull()
        if (last != null && (now < last || now - last < TTL)) return@withLock
        // An admitted attempt consumes the daily request budget, including failure/cancellation.
        prefs.putString(ATTEMPT, now.toString())
        try {
            val rows = fetch()
            require(rows.isNotEmpty() && rows.first() == ZGenre.ANY && rows.all { it.id >= 0 && it.label.isNotBlank() })
            require(rows.map { it.id }.distinct().size == rows.size)
            val json = JSONObject().put("fetchedAt", clock()).put("list", JSONArray().apply {
                rows.forEach { put(JSONObject().put("id", it.id).put("label", it.label)) }
            })
            prefs.putString(SAVED, json.toString())
            mutableChoices.value = rows
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the last successful list or the bundled copy.
        }
    }

    private fun readSaved(): Pair<Long, List<ZGenre>>? {
        val text = prefs.getString(SAVED, null) ?: return null
        return runCatching {
            val root = JSONObject(text)
            val at = root.getLong("fetchedAt")
            val list = root.getJSONArray("list")
            val rows = List(list.length()) { index ->
                list.getJSONObject(index).let { ZGenre(it.getLong("id"), it.getString("label")) }
            }
            require(at >= 0 && rows.isNotEmpty() && rows.first() == ZGenre.ANY)
            require(rows.all { it.id >= 0 && it.label.isNotBlank() } && rows.map { it.id }.distinct().size == rows.size)
            at to rows
        }.getOrNull()
    }

    companion object {
        const val TTL = 24 * 60 * 60 * 1000L
        private const val SAVED = "browse_filter.${GalleryIdentityV1.ZAIMANHUA}.genreChoices"
        private const val ATTEMPT = "browse_filter.${GalleryIdentityV1.ZAIMANHUA}.genreRefreshAttempt"
        // Protocol taxonomy checked by the controller 2026-10-06, in source order.
        val BUNDLED = listOf(ZGenre.ANY) + listOf(
            4L to "冒险", 5L to "欢乐向", 6L to "格斗", 7L to "科幻", 8L to "爱情", 9L to "侦探",
            10L to "竞技", 11L to "魔法", 12L to "神鬼", 13L to "校园", 14L to "惊悚", 16L to "其他",
            17L to "四格", 3242L to "亲情", 3243L to "ゆり", 3244L to "秀吉", 3245L to "悬疑",
            3246L to "纯爱", 3248L to "热血", 3249L to "泛爱", 3250L to "历史", 3251L to "战争",
            3252L to "萌系", 3253L to "宅系", 3254L to "治愈", 3255L to "励志", 3324L to "武侠",
            3325L to "机战", 3326L to "音乐舞蹈", 3327L to "美食", 3328L to "职场", 3365L to "西方魔幻",
            4459L to "高清单行", 4518L to "TS", 5077L to "东方", 5806L to "魔幻", 5848L to "奇幻",
            6219L to "节操", 6316L to "轻小说", 6437L to "颜艺", 7568L to "搞笑", 7900L to "仙侠",
            13627L to "舰娘", 17192L to "动画", 18522L to "AA", 23323L to "福瑞", 23388L to "生存",
            30788L to "日常", 31137L to "画集", 34093L to "2025冬", 36172L to "2026春",
        ).map { (id, label) -> ZGenre(id, label) }
    }
}
