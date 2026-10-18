package com.easemic.app.feature.source.zaimanhua

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.BrowseFeedResult
import com.easemic.app.feature.browse.ZGenre
import com.easemic.app.feature.browse.browseOk
import com.easemic.app.feature.download.ChapterIdentityV2
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.source.ChapterListCompleteness
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.ChapterSummary
import com.easemic.app.feature.source.DetailField
import com.easemic.app.feature.source.SeriesDate
import com.easemic.app.feature.source.SeriesDetailMetadata
import com.easemic.app.feature.source.SeriesPublicationStatus
import java.time.Instant
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Pure 再漫画 JSON decoding. No HTTP. */
internal object ZaimanhuaDecoder {
    fun classify(body: JSONObject): List<ZGenre> = try {
        if (!body.has("errno") || body.getInt("errno") != 0) zaimanhuaFailure("classify", "decode")
        val groups = body.getJSONObject("data").getJSONArray("classifyList")
        val genres = (0 until groups.length()).map { groups.getJSONObject(it) }
            .singleOrNull { it.getInt("id") == 1 } ?: zaimanhuaFailure("classify", "decode")
        val rows = genres.getJSONArray("list")
        val choices = List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val id = row.getLong("tagId")
            val label = row.getString("tagName")
            if (id < 0 || label.isBlank()) zaimanhuaFailure("classify", "decode")
            if (id == 0L) ZGenre.ANY else ZGenre(id, label)
        }
        if (choices.isEmpty() || choices.first() != ZGenre.ANY || choices.map { it.id }.distinct().size != choices.size) {
            zaimanhuaFailure("classify", "decode")
        }
        choices
    } catch (_: JSONException) {
        zaimanhuaFailure("classify", "decode")
    }

    fun filtered(body: JSONObject, page: Int): BrowseFeedResult.Ok = try {
        requireBrowseEnvelope(body, "filter")
        val data = body.getJSONObject("data")
        val rows = data.getJSONArray("comicList")
        val total = data.getInt("totalNum")
        if (page <= 0 || total < 0 || rows.length() > 20) zaimanhuaFailure("filter", "decode")
        val items = List(rows.length()) { index -> browseRow(rows.getJSONObject(index), "id", "name", null) }
        browseOk(
            items,
            if (items.isNotEmpty() && page.toLong() * 20 < total && page < Int.MAX_VALUE) (page + 1).toString() else null,
            total,
        )
    } catch (_: JSONException) {
        zaimanhuaFailure("filter", "decode")
    }

    fun ranking(body: JSONObject, page: Int): BrowseFeedResult.Ok = try {
        requireBrowseEnvelope(body, "ranking")
        val rows = body.getJSONArray("data")
        if (page <= 0 || rows.length() > 10) zaimanhuaFailure("ranking", "decode")
        val items = List(rows.length()) { index ->
            browseRow(rows.getJSONObject(index), "comic_id", "title", (page.toLong() - 1) * 10 + index + 1)
        }
        browseOk(items, if (rows.length() == 10 && page < Int.MAX_VALUE) (page + 1).toString() else null)
    } catch (_: JSONException) {
        zaimanhuaFailure("ranking", "decode")
    }

    private fun requireBrowseEnvelope(body: JSONObject, call: String) {
        if (!body.has("errno") || body.getInt("errno") != 0) zaimanhuaFailure(call, "decode")
    }

    private fun browseRow(row: JSONObject, idKey: String, titleKey: String, rank: Long?): BrowseFeedItem {
        val id = row.getLong(idKey)
        val title = row.getString(titleKey)
        if (id <= 0 || title.isBlank()) zaimanhuaFailure("browse", "decode")
        fun text(key: String) = if (row.isNull(key)) "" else row.optString(key)
        val rawAuthors = text("authors").trim()
        val authors = rawAuthors.splitSourceList()
        val genres = text("types").splitSourceList()
        val statusLabel = text("status").trim().takeIf(String::isNotBlank)
        val latestChapter = text("last_update_chapter_name").trim().takeIf(String::isNotBlank)
        val cover = text("cover").takeIf(String::isNotBlank)
        val updated = row.longOrZero("last_updatetime").takeIf { it > 0 }
        val subline = listOf(text("authors"), text("last_update_chapter_name"))
            .filter(String::isNotBlank).joinToString(" · ")
        return BrowseFeedItem(
            id = id.toString(),
            title = title,
            secondaryLine = if (rank == null) subline else "#$rank · $subline",
            providerId = GalleryIdentityV1.ZAIMANHUA,
            acquisitionUrl = "zaimanhua://comic/$id",
            coverUrl = cover,
            category = statusLabel,
            author = rawAuthors.takeIf(String::isNotBlank),
            tags = genres,
            latestChapterLabel = latestChapter,
            rankBadge = rank?.let { "#$it" },
            uploadDateEpochSeconds = updated,
            seriesMetadata = SeriesDetailMetadata(
                title = DetailField.Value(title),
                cover = DetailField.Value(cover),
                authors = DetailField.Value(authors),
                genres = DetailField.Value(genres),
                updated = updated?.let { DetailField.Value(SeriesDate.EpochSeconds(it)) }
                    ?: DetailField.Omitted,
            ),
        )
    }

    sealed class Opened {
        data class Ready(val result: ChapterListResult) : Opened()
        data class PcFallback(
            val metadata: SeriesDetailMetadata,
            val status: SeriesPublicationStatus,
            val statusSupplied: Boolean,
        ) : Opened()
    }

    fun detail(raw: String, seriesId: Long, fetchedAtMillis: Long): ChapterListResult = try {
        detail(JSONObject(raw), seriesId, fetchedAtMillis)
    } catch (error: JSONException) {
        zaimanhuaFailure("detail", "decode")
    }

    fun detail(body: JSONObject, seriesId: Long, fetchedAtMillis: Long): ChapterListResult =
        when (val opened = openDetail(body, seriesId, fetchedAtMillis)) {
            is Opened.Ready -> opened.result
            is Opened.PcFallback -> zaimanhuaFailure("detail", "decode")
        }

    fun openDetail(body: JSONObject, seriesId: Long, fetchedAtMillis: Long): Opened = try {
        decodeV4(body, seriesId, fetchedAtMillis)
    } catch (error: JSONException) {
        zaimanhuaFailure("detail", "decode")
    }

    fun pcDetail(body: JSONObject, seriesId: Long, fetchedAtMillis: Long): ChapterListResult = try {
        decodePc(body, seriesId, fetchedAtMillis)
    } catch (error: JSONException) {
        zaimanhuaFailure("pc", "decode")
    }

    fun latest(body: JSONObject, page: Int): BrowseFeedResult.Ok = try {
        if (body.has("errno") && body.optInt("errno") != 0) {
            zaimanhuaFailure("latest", "errno ${body.optInt("errno")}")
        }
        val data = body.optJSONArray("data") ?: zaimanhuaFailure("latest", "decode")
        val next = if (data.length() > 0) (page + 1).toString() else null
        browseOk(mapCatalogue(data), nextContinuation = next)
    } catch (error: JSONException) {
        zaimanhuaFailure("latest", "decode")
    }

    sealed class PageList {
        data class Ready(val title: String, val picnum: Int, val urls: List<String>) : PageList()
        data object MissingChapter : PageList()
        data object MembersOnly : PageList()
        data object Invalid : PageList()
    }

    fun pageList(raw: String, seriesId: Long, chapterId: Long): PageList {
        val root = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return PageList.Invalid
        }
        if (!root.has("errno")) return PageList.Invalid
        val errno = root.optInt("errno")
        if (errno == 3) {
            val data = if (root.has("data") && !root.isNull("data")) root.optJSONObject("data") else null
            return if (data == null || data.length() == 0) PageList.MissingChapter else PageList.Invalid
        }
        if (errno != 0) return PageList.Invalid
        val record = root.optJSONObject("data")?.optJSONObject("data") ?: return PageList.Invalid
        if (!record.has("canRead") || record.isNull("canRead")) return PageList.Invalid
        if (!record.optBoolean("canRead")) return PageList.MembersOnly
        if (record.optLong("chapter_id") != chapterId || record.optLong("comic_id") != seriesId) {
            return PageList.Invalid
        }
        val picnum = record.optInt("picnum")
        if (picnum <= 0) return PageList.Invalid
        val urls = choosePageUrls(record) ?: return PageList.Invalid
        if (urls.isEmpty() || urls.any { it.isBlank() }) return PageList.Invalid
        return PageList.Ready(title = record.optString("title"), picnum = picnum, urls = urls)
    }

    private fun choosePageUrls(record: JSONObject): List<String>? {
        val hd = stringList(record, "page_url_hd")
        val plain = stringList(record, "page_url")
        return when {
            hd == null -> plain
            plain == null -> hd
            hd.size < plain.size -> plain
            else -> hd
        }
    }

    private fun stringList(record: JSONObject, key: String): List<String>? {
        if (!record.has(key) || record.isNull(key)) return null
        val array = record.optJSONArray(key) ?: return null
        return List(array.length()) { index -> array.optString(index) }
    }

    fun search(body: JSONObject, page: Int): BrowseFeedResult.Ok = try {
        if (!body.has("errno") || body.optInt("errno") != 0) {
            zaimanhuaFailure("search", "errno ${body.optInt("errno", -1)}")
        }
        val data = body.optJSONObject("data") ?: zaimanhuaFailure("search", "decode")
        val list = data.optJSONArray("list") ?: zaimanhuaFailure("search", "decode")
        if (!data.has("page") || !data.has("size") || !data.has("total")) {
            zaimanhuaFailure("search", "decode")
        }
        val size = data.optInt("size")
        val total = data.optInt("total")
        val next = if (page.toLong() * size.toLong() < total.toLong()) (page + 1).toString() else null
        browseOk(mapCatalogue(list), nextContinuation = next, totalItems = total)
    } catch (error: JSONException) {
        zaimanhuaFailure("search", "decode")
    }

    private fun decodeV4(body: JSONObject, seriesId: Long, fetchedAtMillis: Long): Opened {
        if (!body.has("errno")) zaimanhuaFailure("detail", "decode")
        val errno = body.optInt("errno")
        val envelope = if (body.has("data") && !body.isNull("data")) body.optJSONObject("data") else null
        if (errno == 2 && (envelope == null || envelope.length() == 0)) {
            return Opened.Ready(missing(fetchedAtMillis))
        }
        if (errno != 0) zaimanhuaFailure("detail", "errno $errno")
        val record = envelope?.optJSONObject("data") ?: zaimanhuaFailure("detail", "decode")
        val recordId = record.longOrZero("id")
        val titleBlank = record.optString("title").isBlank()
        val chapters = if (!record.has("chapters") || record.isNull("chapters")) null else record.optJSONArray("chapters")
        if (recordId == 0L && titleBlank && chapters == null) {
            return Opened.Ready(missing(fetchedAtMillis))
        }
        if (recordId != seriesId) zaimanhuaFailure("detail", "decode")
        val status = publicationStatus(record.optJSONArray("status"), "tag_name")
        val statusSupplied = record.has("status")
        val metadata = detailMetadata(record)
        if (record.optInt("isHideChapter") == 1) {
            if (record.optBoolean("canRead", false)) return Opened.PcFallback(metadata, status, statusSupplied)
            zaimanhuaFailure("detail", "hidden")
        }
        if (chapters == null || chapterCount(chapters) == 0) zaimanhuaFailure("detail", "decode")
        return Opened.Ready(
            mapGroups(
                groups = chapters,
                seriesId = seriesId,
                fetchedAtMillis = fetchedAtMillis,
                seriesCanRead = record.optBoolean("canRead", false),
                status = status,
                statusSupplied = statusSupplied,
                metadata = metadata,
                preferChapterName = true,
                call = "detail",
            ),
        )
    }

    private fun decodePc(body: JSONObject, seriesId: Long, fetchedAtMillis: Long): ChapterListResult {
        if (!body.has("errno") || body.optInt("errno") != 0) {
            zaimanhuaFailure("pc", "errno ${body.optInt("errno", -1)}")
        }
        val info = body.optJSONObject("data")?.optJSONObject("comicInfo") ?: zaimanhuaFailure("pc", "decode")
        if (info.longOrZero("id") != seriesId) zaimanhuaFailure("pc", "decode")
        val groups = if (!info.has("chapterList") || info.isNull("chapterList")) {
            null
        } else {
            info.optJSONArray("chapterList")
        }
        if (groups == null || chapterCount(groups) == 0) zaimanhuaFailure("pc", "decode")
        val status = publicationStatus(info.optJSONArray("statusTagList"), "tagName")
        return mapGroups(
            groups = groups,
            seriesId = seriesId,
            fetchedAtMillis = fetchedAtMillis,
            seriesCanRead = info.optBoolean("canRead", false),
            status = status,
            statusSupplied = info.has("statusTagList"),
            metadata = SeriesDetailMetadata(
                title = info.optionalText("title"),
                updated = info.optionalEpoch("lastUpdateTime"),
            ),
            preferChapterName = false,
            call = "pc",
        )
    }

    private fun mapGroups(
        groups: JSONArray,
        seriesId: Long,
        fetchedAtMillis: Long,
        seriesCanRead: Boolean,
        status: SeriesPublicationStatus,
        statusSupplied: Boolean,
        metadata: SeriesDetailMetadata,
        preferChapterName: Boolean,
        call: String,
    ): ChapterListResult {
        val series = SeriesIdentityV2(GalleryIdentityV1.ZAIMANHUA, seriesId.toString())
        val seen = HashSet<Long>()
        val chapters = mutableListOf<ChapterSummary>()
        for (groupIndex in 0 until groups.length()) {
            val group = groups.optJSONObject(groupIndex) ?: zaimanhuaFailure(call, "decode")
            val groupTitle = group.optString("title").takeIf { it.isNotBlank() }
            val rows = group.optJSONArray("data") ?: zaimanhuaFailure(call, "decode")
            val sorted = (0 until rows.length())
                .map { rows.getJSONObject(it) }
                .sortedWith(compareBy<JSONObject> { it.longOrZero("chapter_order") }.thenBy { it.longOrZero("chapter_id") })
            sorted.forEachIndexed { rank, row ->
                val chapterId = if (row.has("chapter_id") && !row.isNull("chapter_id")) row.optLong("chapter_id") else 0L
                if (chapterId <= 0L || !seen.add(chapterId)) zaimanhuaFailure(call, "decode")
                val named = if (preferChapterName) row.optString("chapter_name") else ""
                val label = named.ifBlank { row.optString("chapter_title") }
                val updated = row.longOrZero("updatetime")
                chapters += ChapterSummary(
                    identity = ChapterIdentityV2(series, chapterId.toString()),
                    label = label,
                    group = groupTitle,
                    groupOrder = groupIndex,
                    readingOrderInGroup = rank,
                    sourceUpdatedAtMillis = updated.takeIf { it > 0L }?.times(1000L),
                    canRead = row.chapterCanRead(seriesCanRead),
                    isFee = row.feeFlag(),
                    needsSignIn = !row.chapterCanRead(seriesCanRead) && !row.feeFlag(),
                )
            }
        }
        return ChapterListResult(
            chapters = chapters,
            fetchedAtMillis = fetchedAtMillis,
            completeness = ChapterListCompleteness.COMPLETE,
            seriesStatus = status,
            metadata = metadata,
            statusSupplied = statusSupplied,
        )
    }

    private fun mapCatalogue(rows: JSONArray): List<BrowseFeedItem> {
        val items = mutableListOf<BrowseFeedItem>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val comicId = row.longOrZero("comic_id")
            val plainId = row.longOrZero("id")
            val realId = when {
                comicId > 0L -> comicId
                plainId > 0L -> plainId
                else -> continue
            }
            val title = row.optString("title")
            if (title.isBlank()) continue
            val author = row.optString("authors").trim()
            val genres = row.optString("types").splitSourceList()
            val statusLabel = if (row.isNull("status")) null else row.optString("status").trim().takeIf { it.isNotBlank() }
            val latestChapter = if (row.isNull("last_update_chapter_name")) {
                null
            } else {
                row.optString("last_update_chapter_name").trim().takeIf { it.isNotBlank() }
            }
            val updated = row.longOrZero("last_updatetime").takeIf { it > 0L }
            val secondary = listOf(author, row.optString("last_update_chapter_name"))
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            items += BrowseFeedItem(
                id = realId.toString(),
                title = title,
                secondaryLine = secondary,
                providerId = GalleryIdentityV1.ZAIMANHUA,
                acquisitionUrl = "zaimanhua://comic/$realId",
                coverUrl = row.optString("cover").takeIf { it.isNotBlank() },
                category = statusLabel,
                author = author.takeIf { it.isNotBlank() },
                tags = genres,
                latestChapterLabel = latestChapter,
                uploadDateEpochSeconds = updated,
                seriesMetadata = SeriesDetailMetadata(
                    title = DetailField.Value(title),
                    authors = DetailField.Value(author.splitSourceList()),
                    genres = DetailField.Value(genres),
                    updated = updated?.let { DetailField.Value(SeriesDate.EpochSeconds(it)) }
                        ?: DetailField.Omitted,
                ),
            )
        }
        return items
    }

    private fun detailMetadata(record: JSONObject): SeriesDetailMetadata = SeriesDetailMetadata(
        title = record.optionalText("title"),
        cover = record.optionalText("cover"),
        authors = record.optionalNames("authors", "tag_name"),
        synopsis = record.optionalText("description"),
        genres = record.optionalNames("types", "tag_name"),
        updated = record.optionalEpoch("last_updatetime"),
    )

    private fun JSONObject.optionalText(key: String): DetailField<String> = when {
        !has(key) -> DetailField.Omitted
        isNull(key) -> DetailField.Value(null)
        else -> optString(key).trim().let { DetailField.Value(it.takeIf(String::isNotEmpty)) }
    }

    private fun JSONObject.optionalNames(key: String, member: String): DetailField<List<String>> = when {
        !has(key) -> DetailField.Omitted
        isNull(key) -> DetailField.Value(null)
        else -> {
            val array = optJSONArray(key) ?: return DetailField.Omitted
            DetailField.Value((0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.optString(member)?.trim()?.takeIf(String::isNotEmpty)
            }.distinct())
        }
    }

    private fun JSONObject.optionalEpoch(key: String): DetailField<SeriesDate> = when {
        !has(key) -> DetailField.Omitted
        isNull(key) -> DetailField.Value(null)
        else -> longOrZero(key).takeIf { it > 0L && runCatching { Instant.ofEpochSecond(it) }.isSuccess }
            ?.let { DetailField.Value(SeriesDate.EpochSeconds(it)) }
            ?: DetailField.Omitted
    }

    private fun String.splitSourceList(): List<String> = split(',', '，')
        .map(String::trim).filter(String::isNotEmpty).distinct()

    private fun publicationStatus(tags: JSONArray?, nameKey: String): SeriesPublicationStatus {
        if (tags == null) return SeriesPublicationStatus.UNKNOWN
        for (index in 0 until tags.length()) {
            val name = tags.optJSONObject(index)?.optString(nameKey).orEmpty()
            when (name) {
                "连载中" -> return SeriesPublicationStatus.ONGOING
                "已完结" -> return SeriesPublicationStatus.COMPLETED
            }
        }
        return SeriesPublicationStatus.UNKNOWN
    }

    private fun chapterCount(groups: JSONArray): Int {
        var count = 0
        for (index in 0 until groups.length()) {
            count += groups.optJSONObject(index)?.optJSONArray("data")?.length() ?: 0
        }
        return count
    }

    private fun missing(fetchedAtMillis: Long) = ChapterListResult(
        chapters = emptyList(),
        fetchedAtMillis = fetchedAtMillis,
        completeness = ChapterListCompleteness.EMPTY_CONFIRMED,
        seriesStatus = SeriesPublicationStatus.UNKNOWN,
    )

    private fun JSONObject.longOrZero(key: String): Long =
        if (has(key) && !isNull(key)) optLong(key) else 0L

    private fun JSONObject.chapterCanRead(seriesCanRead: Boolean): Boolean {
        if (!has("canRead") || isNull("canRead")) return seriesCanRead
        return optBoolean("canRead")
    }

    private fun JSONObject.feeFlag(): Boolean {
        if (!has("is_fee") || isNull("is_fee")) return false
        return when (val value = get("is_fee")) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> false
        }
    }
}
