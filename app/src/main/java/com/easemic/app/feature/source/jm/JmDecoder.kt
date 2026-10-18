package com.easemic.app.feature.source.jm

import com.easemic.app.feature.browse.BrowseFeedItem
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
import com.easemic.app.feature.source.valueOrNull
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl

internal data class JmChapterEntry(val id: String, val name: String, val sort: Long)
internal data class JmAlbum(
    val id: String,
    val name: String,
    val authors: List<String>,
    val tags: List<String>,
    val totalPhotos: Int,
    val addedSeconds: Long,
    val chapters: List<JmChapterEntry>,
    val metadata: SeriesDetailMetadata = SeriesDetailMetadata(),
    val status: SeriesPublicationStatus = SeriesPublicationStatus.UNKNOWN,
    val statusSupplied: Boolean = false,
) {
    fun chapterList(fetchedAt: Long): ChapterListResult {
        val series = SeriesIdentityV2(GalleryIdentityV1.JM, id)
        return ChapterListResult(
            chapters.mapIndexed { rank, chapter ->
                ChapterSummary(ChapterIdentityV2(series, chapter.id), chapter.name, null, 0, rank, null, true, false)
            },
            fetchedAt,
            ChapterListCompleteness.COMPLETE,
            seriesStatus = status,
            metadata = metadata,
            statusSupplied = statusSupplied,
        )
    }
}
internal data class JmChapter(val id: String, val parent: String, val name: String, val filenames: List<String>)
internal data class JmCatalogue(val items: List<BrowseFeedItem>, val next: String?, val total: Int, val redirect: String?)

/** Validates entire results before returning a COMPLETE list; never drops malformed entries. */
internal object JmDecoder {
    fun envelope(text: String, seconds: Long, endpoint: JmEndpoint, role: JmRole = JmRole.API): JmPayload {
        val root = JmCrypto.objectJson(text)
        val code = integer(root.opt("code"))
        if (code != 200L) {
            if (root.has("data")) JmFailure.errorEnvelope()
            JmFailure.invalid()
        }
        val data = root.opt("data")
        if (data is JSONArray && data.length() == 0 && root.length() == 2 && role == JmRole.API &&
            endpoint in listOf(JmEndpoint.ALBUM, JmEndpoint.CHAPTER)) return JmPayload.Missing
        if (data !is String) JmFailure.invalid()
        val json = JmCrypto.objectJson(JmCrypto.decrypt(data, JmCrypto.key(seconds)))
        return JmPayload.Present(json)
    }
    fun album(json: JSONObject, expectedId: String): JmAlbum {
        val albumId = id(json.opt("id"))
        if (albumId != expectedId) JmFailure.invalid()
        val title = text(json, "name").takeIf { it.isNotBlank() } ?: JmFailure.invalid()
        val rows = entries(array(json, "series"))
        val chapters = if (rows.isEmpty()) listOf(JmChapterEntry(albumId, "第1話", 0)) else rows.sortedBy { it.sort }
        val total = integer(json.opt("total_photos"))
        if (total <= 0 || total > Int.MAX_VALUE) JmFailure.invalid()
        // Required live shapes. Optional detail text is presence-aware so null can clear a saved value.
        val authorsField = optionalStrings(json, "author")
        val tagsField = optionalStrings(json, "tags")
        val authors = authorsField.valueOrEmpty()
        val rawTags = tagsField.valueOrEmpty()
        id(json.opt("series_id"))
        val added = digits(json.opt("addtime"))
        val description = optionalText(json, "description")
        val status = rawTags.firstNotNullOfOrNull { statusOf(it) } ?: SeriesPublicationStatus.UNKNOWN
        val tags = rawTags.filterNot { statusOf(it) != null }
        if (array(json, "images").length() != 0) JmFailure.invalid()
        return JmAlbum(
            albumId,
            title,
            authors,
            tags,
            total.toInt(),
            added,
            chapters,
            metadata = SeriesDetailMetadata(
                title = DetailField.Value(title),
                authors = authorsField,
                synopsis = description,
                tags = when (tagsField) {
                    DetailField.Omitted -> DetailField.Omitted
                    is DetailField.Value -> DetailField.Value(tagsField.value?.filterNot { tag -> statusOf(tag) != null })
                },
                published = DetailField.Value(SeriesDate.EpochSeconds(added)),
                totalPages = DetailField.Value(total.toInt()),
            ),
            status = status,
            statusSupplied = json.has("tags"),
        )
    }
    fun chapter(json: JSONObject, series: String, id: String): JmChapter {
        if (this.id(json.opt("id")) != id) JmFailure.invalid()
        val parent = this.id(json.opt("series_id"))
        val rows = entries(array(json, "series"))
        if (parent == "0") {
            if (id != series || rows.isNotEmpty()) JmFailure.invalid()
        } else if (parent != series || rows.none { it.id == id }) JmFailure.invalid()
        val files = strings(array(json, "images"))
        if (files.isEmpty() || files.distinct().size != files.size || files.any { !filename(it) }) JmFailure.invalid()
        digits(json.opt("addtime"))
        text(json, "tags") // Chapter tags are a string, unlike album tags.
        return JmChapter(id, parent, text(json, "name"), files)
    }
    fun catalogue(json: JSONObject, page: Int, image: HttpUrl): JmCatalogue {
        if (page <= 0) JmFailure.invalid()
        val total = integer(json.opt("total"))
        if (total !in 0..Int.MAX_VALUE.toLong()) JmFailure.invalid()
        val rows = array(json, "content")
        if (rows.length() > 80) JmFailure.invalid()
        val redirect = if (json.has("redirect_aid")) id(json.opt("redirect_aid")) else null
        if (redirect != null && (rows.length() != 0 || total != 1L)) JmFailure.invalid()
        val items = List(rows.length()) { index ->
            val row = rows.optJSONObject(index) ?: JmFailure.invalid()
            val itemId = id(row.opt("id"))
            val title = text(row, "name").takeIf { it.isNotBlank() } ?: JmFailure.invalid()
            val rawAuthor = text(row, "author").trim()
            val authorsList = rawAuthor.split(",").map(String::trim).filter(String::isNotEmpty).distinct()
            val authorDisplay = authorsList.joinToString(", ").takeIf { it.isNotBlank() } ?: rawAuthor.takeIf { it.isNotBlank() }
            val catTitle = row.optJSONObject("category")?.nonNullText("title")
            val subCatTitle = row.optJSONObject("category_sub")?.nonNullText("title")
            val addDateLabel = (row.opt("adddate") as? String)?.trim()
                ?.takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) }
                ?.substring(0, 7)
                ?.let { "$it 首发" }
            val cardTags = buildList {
                if (subCatTitle != null && subCatTitle != catTitle) add(subCatTitle)
                if (catTitle == "韓漫") add("条漫")
                if (addDateLabel != null) add(addDateLabel)
            }.distinct()
            val updated = integer(row.opt("update_at")).takeIf { it > 0 }
            BrowseFeedItem(
                id = itemId,
                title = title,
                providerId = GalleryIdentityV1.JM,
                acquisitionUrl = "jm://album/$itemId",
                secondaryLine = text(row, "author"),
                coverUrl = cover(image, itemId),
                category = catTitle,
                author = authorDisplay,
                tags = cardTags,
                uploadDateEpochSeconds = updated,
                seriesMetadata = SeriesDetailMetadata(
                    title = DetailField.Value(title),
                    authors = DetailField.Value(authorsList),
                    updated = updated
                        ?.let { DetailField.Value(SeriesDate.EpochSeconds(it)) } ?: DetailField.Omitted,
                ),
            )
        }
        val next = if (items.isNotEmpty() && redirect == null && page.toLong() * 80 < total.coerceAtMost(10000) && page < Int.MAX_VALUE) (page + 1).toString() else null
        return JmCatalogue(items, next, total.toInt(), redirect)
    }
    fun cover(image: HttpUrl, id: String): String = image.newBuilder().addPathSegments("media/albums/${id}_3x4.jpg").build().toString()
    fun template(html: String): Long {
        val assignments = Regex("""\bvar\s+scramble_id\s*=\s*([0-9]+)\s*;""").findAll(html).toList()
        if (assignments.size != 1) JmFailure.invalid()
        return assignments.single().groupValues[1].toLongOrNull() ?: JmFailure.invalid()
    }
    fun id(value: Any?): String {
        val raw = when (value) {
            is String -> value
            is Int, is Long -> value.toString()
            else -> JmFailure.invalid()
        }
        if (!Regex("[0-9]+").matches(raw)) JmFailure.invalid()
        return raw
    }
    private fun entries(array: JSONArray): List<JmChapterEntry> {
        val seen = HashSet<String>()
        return List(array.length()) { index ->
            val row = array.optJSONObject(index) ?: JmFailure.invalid()
            val chapterId = id(row.opt("id"))
            if (!seen.add(chapterId)) JmFailure.invalid()
            val sort = (row.opt("sort") as? String)?.takeIf { Regex("-?[0-9]+").matches(it) }?.toLongOrNull()
                ?: JmFailure.invalid()
            val name = when (val value = row.opt("name")) {
                null, JSONObject.NULL -> ""
                is String -> value
                else -> JmFailure.invalid()
            }
            JmChapterEntry(chapterId, name.ifBlank { "第${if (sort > 0) sort else index + 1L}話" }, sort)
        }
    }
    private fun filename(value: String): Boolean = Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*\\.(?i:jpg|jpeg|png|webp|gif)").matches(value)
    private fun digits(value: Any?): Long = (value as? String)?.takeIf { Regex("[0-9]+").matches(it) }?.toLongOrNull() ?: JmFailure.invalid()
    private fun integer(value: Any?): Long = when (value) { is Int -> value.toLong(); is Long -> value; else -> JmFailure.invalid() }
    private fun array(json: JSONObject, key: String): JSONArray = json.optJSONArray(key) ?: JmFailure.invalid()
    private fun text(json: JSONObject, key: String): String = json.opt(key) as? String ?: JmFailure.invalid()
    private fun strings(array: JSONArray): List<String> = List(array.length()) { array.opt(it) as? String ?: JmFailure.invalid() }

    private fun optionalText(json: JSONObject, key: String): DetailField<String> = when {
        !json.has(key) -> DetailField.Omitted
        json.isNull(key) -> DetailField.Value(null)
        else -> (json.opt(key) as? String)?.trim()?.let { DetailField.Value(it.takeIf(String::isNotEmpty)) }
            ?: DetailField.Omitted
    }

    private fun optionalStrings(json: JSONObject, key: String): DetailField<List<String>> = when {
        !json.has(key) -> DetailField.Omitted
        json.isNull(key) -> DetailField.Value(null)
        else -> {
            val array = json.optJSONArray(key) ?: JmFailure.invalid()
            DetailField.Value(strings(array).map(String::trim).filter(String::isNotEmpty).distinct())
        }
    }

    private fun DetailField<List<String>>.valueOrEmpty(): List<String> = valueOrNull().orEmpty()

    private fun statusOf(tag: String): SeriesPublicationStatus? = when (tag.trim()) {
        "已完结", "完结" -> SeriesPublicationStatus.COMPLETED
        "连载中", "连载" -> SeriesPublicationStatus.ONGOING
        else -> null
    }

    private fun JSONObject.nonNullText(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = (opt(key) as? String)?.trim().orEmpty()
        return value.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }
}
