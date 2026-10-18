package com.easemic.app.sync.archive

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import com.easemic.app.feature.source.SeriesDetailSnapshot
import com.easemic.app.feature.source.decodeSeriesSnapshot
import com.easemic.app.feature.source.encodeSeriesSnapshot

/**
 * #D7: the device-to-device archive's JSON records (spec `2026-09-27-d7-device-export-restore-design.md` §3).
 * Nothing here is a credential: sign-ins, cloud bindings and OPDS passwords never enter these types.
 */
class ArchiveFormatException(message: String) : Exception(message)

private inline fun <T> parse(json: String, what: String, block: (JSONObject) -> T): T =
    try {
        block(JSONObject(json))
    } catch (e: JSONException) {
        throw ArchiveFormatException("$what is not readable: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw ArchiveFormatException("$what is not readable: ${e.message}")
    } catch (e: ClassCastException) {
        throw ArchiveFormatException("$what is not readable: ${e.message}")
    }

private fun JSONObject.requireString(name: String): String =
    optString(name, "").takeIf { has(name) && it.isNotEmpty() } ?: throw ArchiveFormatException("missing $name")

private fun JSONObject.stringOrNull(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotEmpty() } else null

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

data class ArchiveManifest(
    val formatVersion: Int,
    val appVersion: String,
    val createdAtMs: Long,
    val comicCount: Int,
    val pageBytes: Long,
    val includesDownloads: Boolean,
    /** Which galleries the archive holds, in order — so a restore preview never has to read past the head. */
    val comicIds: List<ArchiveComicId> = emptyList(),
    /** Format 2 only: serialized series carried by this archive. */
    val seriesCount: Int = 0,
    val seriesIds: List<ArchiveSeriesId> = emptyList(),
) {
    fun encode(): String {
        val json = JSONObject()
        .put("formatVersion", formatVersion)
        .put("appVersion", appVersion)
        .put("createdAtMs", createdAtMs)
        .put("comicCount", comicCount)
        .put("pageBytes", pageBytes)
        .put("includesDownloads", includesDownloads)
        .put("comicIds", JSONArray(comicIds.map { JSONObject().put("ns", it.providerNamespace).put("id", it.canonicalItemId) }))
        if (formatVersion >= FORMAT_VERSION_V2) {
            json.put("seriesCount", seriesCount)
            json.put("seriesIds", JSONArray(seriesIds.map { JSONObject().put("providerId", it.providerId).put("id", it.providerSeriesId) }))
        }
        return json.toString()
    }

    companion object {
        /** Current archive format. Format 1 remains readable for old gallery-only exports. */
        const val FORMAT_VERSION: Int = 2
        const val FORMAT_VERSION_V1: Int = 1
        const val FORMAT_VERSION_V2: Int = 2

        fun decode(json: String): ArchiveManifest = parse(json, "manifest") { o ->
            val version = o.optInt("formatVersion", -1)
            if (version < 1) throw ArchiveFormatException("manifest has no format version")
            if (version > FORMAT_VERSION) {
                throw ArchiveFormatException("made by a newer app version (format $version)")
            }
            ArchiveManifest(
                formatVersion = version,
                appVersion = o.optString("appVersion", ""),
                createdAtMs = o.optLong("createdAtMs", 0L),
                comicCount = o.optInt("comicCount", 0),
                pageBytes = o.optLong("pageBytes", 0L),
                includesDownloads = o.optBoolean("includesDownloads", false),
                comicIds = o.optJSONArray("comicIds")?.let { ids ->
                    (0 until ids.length()).map { i ->
                        val id = ids.getJSONObject(i)
                        ArchiveComicId(id.requireString("ns"), id.requireString("id"))
                    }
                }.orEmpty(),
                seriesCount = o.optInt("seriesCount", 0),
                seriesIds = o.optJSONArray("seriesIds")?.let { ids ->
                    (0 until ids.length()).map { i ->
                        val id = ids.getJSONObject(i)
                        ArchiveSeriesId(id.requireString("providerId"), id.requireString("id"))
                    }
                }.orEmpty(),
            )
        }
    }
}

data class ArchiveComicId(val providerNamespace: String, val canonicalItemId: String)

data class ArchiveSeriesId(val providerId: String, val providerSeriesId: String)

/** One downloaded comic. [pageFiles] are the entry names under `comics/<n>/pages/`, in reading order. */
data class ArchiveComic(
    val providerNamespace: String,
    val canonicalItemId: String,
    val title: String,
    val tags: List<String>,
    val language: String?,
    val category: String?,
    val pageFiles: List<String>,
    /** Null for a complete comic; the pinned page count for a truncated one (fewer pages than the gallery has). */
    val expectedPageCount: Int?,
    val progressOrdinal: Int,
) {
    fun encode(): String = JSONObject()
        .put("providerNamespace", providerNamespace)
        .put("canonicalItemId", canonicalItemId)
        .put("title", title)
        .put("tags", JSONArray(tags))
        .put("language", language ?: JSONObject.NULL)
        .put("category", category ?: JSONObject.NULL)
        .put("pageFiles", JSONArray(pageFiles))
        .put("expectedPageCount", expectedPageCount ?: JSONObject.NULL)
        .put("progressOrdinal", progressOrdinal)
        .toString()

    companion object {
        fun decode(json: String): ArchiveComic = parse(json, "comic") { o ->
            ArchiveComic(
                providerNamespace = o.requireString("providerNamespace"),
                canonicalItemId = o.requireString("canonicalItemId"),
                title = o.optString("title", ""),
                tags = o.optJSONArray("tags").strings(),
                language = o.stringOrNull("language"),
                category = o.stringOrNull("category"),
                pageFiles = o.optJSONArray("pageFiles").strings(),
                expectedPageCount = if (o.isNull("expectedPageCount")) null else o.optInt("expectedPageCount"),
                progressOrdinal = o.optInt("progressOrdinal", 0),
            )
        }
    }
}

/** Metadata for one serialized series carried by an archive format-2 export. */
data class ArchiveSeries(
    val providerId: String,
    val providerSeriesId: String,
    val title: String,
    val coverFileName: String? = null,
    val coverLocator: String? = null,
    val chapters: List<ArchiveChapter> = emptyList(),
    val detailSnapshot: SeriesDetailSnapshot? = null,
) {
    fun encode(): String = JSONObject()
        .put("providerId", providerId)
        .put("providerSeriesId", providerSeriesId)
        .put("title", title)
        .put("coverFileName", coverFileName ?: JSONObject.NULL)
        .put("coverLocator", coverLocator ?: JSONObject.NULL)
        .put("detailSnapshot", detailSnapshot?.let { JSONObject(encodeSeriesSnapshot(it)) } ?: JSONObject.NULL)
        .put("chapters", JSONArray(chapters.map { JSONObject(it.encode()) }))
        .toString()

    companion object {
        fun decode(json: String): ArchiveSeries = parse(json, "series") { o ->
            val chapters = o.optJSONArray("chapters")
            ArchiveSeries(
                providerId = o.requireString("providerId"),
                providerSeriesId = o.requireString("providerSeriesId"),
                title = o.optString("title", ""),
                coverFileName = o.stringOrNull("coverFileName"),
                coverLocator = o.stringOrNull("coverLocator"),
                detailSnapshot = o.opt("detailSnapshot")?.takeUnless { it == JSONObject.NULL }?.let {
                    decodeSeriesSnapshot(it.toString())
                },
                chapters = if (chapters == null) emptyList() else (0 until chapters.length()).map { i ->
                    ArchiveChapter.decode(chapters.getJSONObject(i).toString())
                },
            )
        }
    }
}

/** Metadata and saved pages for one serialized chapter. */
data class ArchiveChapter(
    val providerChapterId: String,
    val label: String,
    val group: String?,
    val readingOrder: Int,
    val sourceState: String,
    val pageFiles: List<String>,
    val groupOrder: Int = 0,
    val sourceUpdatedAtMillis: Long? = null,
    val expectedPageCount: Int? = null,
    val progressOrdinal: Int = 0,
    val completed: Boolean = false,
    val lastReadAtMillis: Long = 0L,
) {
    fun encode(): String = JSONObject()
        .put("providerChapterId", providerChapterId)
        .put("label", label)
        .put("group", group ?: JSONObject.NULL)
        .put("readingOrder", readingOrder)
        .put("sourceState", sourceState)
        .put("pageFiles", JSONArray(pageFiles))
        .put("groupOrder", groupOrder)
        .put("sourceUpdatedAtMillis", sourceUpdatedAtMillis ?: JSONObject.NULL)
        .put("expectedPageCount", expectedPageCount ?: JSONObject.NULL)
        .put("progressOrdinal", progressOrdinal)
        .put("completed", completed)
        .put("lastReadAtMillis", lastReadAtMillis)
        .toString()

    companion object {
        fun decode(json: String): ArchiveChapter = parse(json, "chapter") { o ->
            ArchiveChapter(
                providerChapterId = o.requireString("providerChapterId"),
                label = o.optString("label", ""),
                group = o.stringOrNull("group"),
                readingOrder = o.optInt("readingOrder", 0),
                sourceState = o.optString("sourceState", "ACTIVE"),
                pageFiles = o.optJSONArray("pageFiles").strings(),
                groupOrder = o.optInt("groupOrder", 0),
                sourceUpdatedAtMillis = if (o.isNull("sourceUpdatedAtMillis")) null else o.optLong("sourceUpdatedAtMillis"),
                expectedPageCount = if (o.isNull("expectedPageCount")) null else o.optInt("expectedPageCount"),
                progressOrdinal = o.optInt("progressOrdinal", 0),
                completed = o.optBoolean("completed", false),
                lastReadAtMillis = o.optLong("lastReadAtMillis", 0L),
            )
        }
    }
}

/** A saved reading position. [key] is the `galleryDedupKey` form, or empty for a row without an identity. */
data class ArchiveProgress(val key: String, val title: String, val pageCount: Int, val ordinal: Int)

/** An OPDS catalog without credentials (see ArchiveCredentialScrub). */
data class ArchiveOpdsCatalog(val name: String, val url: String)

/**
 * Settings a new phone takes over. ExHentai's switch is not here: it only turns on with a signed-in session, and a
 * session never travels, so the new phone turns it on after signing in.
 */
data class ArchiveSettings(
    val keepScreenOn: Boolean,
    val volumeKeys: Boolean,
    val rtl: Boolean,
    val libraryLayout: String,
    val ehentaiBrowse: Boolean,
    val nhentaiBrowse: Boolean,
    val cacheMb: Int,
    val keepOnlyCloudCopy: Boolean,
    val maxTransfers: Int,
    val jmBrowse: Boolean = false,
) {
    internal fun toJson(): JSONObject = JSONObject()
        .put("keepScreenOn", keepScreenOn)
        .put("volumeKeys", volumeKeys)
        .put("rtl", rtl)
        .put("libraryLayout", libraryLayout)
        .put("ehentaiBrowse", ehentaiBrowse)
        .put("nhentaiBrowse", nhentaiBrowse)
        .put("jmBrowse", jmBrowse)
        .put("cacheMb", cacheMb)
        .put("keepOnlyCloudCopy", keepOnlyCloudCopy)
        .put("maxTransfers", maxTransfers)

    internal companion object {
        fun fromJson(o: JSONObject): ArchiveSettings = ArchiveSettings(
            keepScreenOn = o.optBoolean("keepScreenOn", true),
            volumeKeys = o.optBoolean("volumeKeys", true),
            rtl = o.optBoolean("rtl", false),
            libraryLayout = o.optString("libraryLayout", "GRID"),
            ehentaiBrowse = o.optBoolean("ehentaiBrowse", false),
            nhentaiBrowse = o.optBoolean("nhentaiBrowse", false),
            jmBrowse = o.optBoolean("jmBrowse", false),
            cacheMb = o.optInt("cacheMb", 0),
            keepOnlyCloudCopy = o.optBoolean("keepOnlyCloudCopy", false),
            maxTransfers = o.optInt("maxTransfers", 0),
        )
    }
}

data class ArchiveState(
    /** The collect bookmark store's own JSON (`CollectBookmarkStore.exportRaw`). */
    val bookmarksJson: String,
    /** The browse history store's own JSON (`BrowseHistoryStore.exportRaw`). */
    val historyJson: String,
    val progress: List<ArchiveProgress>,
    val settings: ArchiveSettings,
    val opdsCatalogs: List<ArchiveOpdsCatalog>,
    /** Display names of key-bearing OPDS catalogs that stayed behind. Never a URL. Absent in older archives. */
    val opdsLeftBehind: List<String> = emptyList(),
    /** The remote reading progress store's own JSON array. Absent in older archives. */
    val remoteProgressJson: String = "[]",
) {
    fun encode(): String {
        val json = JSONObject()
            .put("bookmarksJson", bookmarksJson)
            .put("historyJson", historyJson)
            .put(
                "progress",
                JSONArray(
                    progress.map {
                        JSONObject().put("key", it.key).put("title", it.title)
                            .put("pageCount", it.pageCount).put("ordinal", it.ordinal)
                    },
                ),
            )
            .put("settings", settings.toJson())
            .put("opdsCatalogs", JSONArray(opdsCatalogs.map { JSONObject().put("name", it.name).put("url", it.url) }))
        if (opdsLeftBehind.isNotEmpty()) json.put("opdsLeftBehind", JSONArray(opdsLeftBehind))
        json.put("remoteProgressJson", remoteProgressJson)
        return json.toString()
    }

    companion object {
        fun decode(json: String): ArchiveState = parse(json, "state") { o ->
            val progress = o.optJSONArray("progress")
            val catalogs = o.optJSONArray("opdsCatalogs")
            ArchiveState(
                bookmarksJson = o.optString("bookmarksJson", "[]"),
                historyJson = o.optString("historyJson", "[]"),
                progress = if (progress == null) emptyList() else (0 until progress.length()).map { i ->
                    val p = progress.getJSONObject(i)
                    ArchiveProgress(p.optString("key", ""), p.optString("title", ""), p.optInt("pageCount"), p.optInt("ordinal"))
                },
                settings = ArchiveSettings.fromJson(o.optJSONObject("settings") ?: JSONObject()),
                opdsCatalogs = if (catalogs == null) emptyList() else (0 until catalogs.length()).map { i ->
                    val c = catalogs.getJSONObject(i)
                    ArchiveOpdsCatalog(c.optString("name", ""), c.optString("url", ""))
                },
                opdsLeftBehind = o.optJSONArray("opdsLeftBehind").strings(),
                remoteProgressJson = o.optString("remoteProgressJson", "[]"),
            )
        }
    }
}
