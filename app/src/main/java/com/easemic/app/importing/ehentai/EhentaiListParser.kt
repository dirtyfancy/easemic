package com.easemic.app.importing.ehentai

import com.easemic.app.importing.HtmlText
import com.easemic.app.importing.galleryLanguage

/**
 * Parses synthetic / independently authored front-catalog list HTML into
 * gallery rows. Does not copy third-party client parsers.
 */
class EhentaiListParser {
    fun parse(bytes: ByteArray): EhentaiListPage {
        val html = bytes.toString(Charsets.UTF_8)
        val thumbsByGalleryId = parseCurrentSiteThumbs(html)
        val fromLegacy = GALLERY_ROW_REGEX.findAll(html).mapNotNull { match ->
            val thumb = match.groupValues[1].ifBlank { null }
            val galleryId = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            val token = match.groupValues[3]
            val title = HtmlText.decode(match.groupValues[4].trim())
            if (title.isEmpty() || token.isEmpty()) return@mapNotNull null
            EhentaiListEntry(
                title = title,
                galleryId = galleryId,
                token = token,
                thumbUrl = thumb,
            )
        }.toList()
        val metaByGalleryId = parseRowMeta(html)
        val fromCurrent = GALLERY_LINK_REGEX.findAll(html).mapNotNull { match ->
            val galleryId = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val token = match.groupValues[2]
            val title = HtmlText.decode(match.groupValues[3].trim())
            if (title.isEmpty() || token.isEmpty()) return@mapNotNull null
            val meta = metaByGalleryId[galleryId]
            EhentaiListEntry(
                title = title,
                galleryId = galleryId,
                token = token,
                thumbUrl = thumbsByGalleryId[galleryId],
                category = meta?.category,
                rating = meta?.rating,
                postedEpochMillis = meta?.postedEpochMillis,
                pageCount = meta?.pageCount,
                uploader = meta?.uploader,
                language = meta?.language,
            )
        }.toList()
        // Prefer richer legacy matches when present; otherwise current site shape.
        val entries = if (fromLegacy.isNotEmpty()) {
            fromLegacy.map { entry ->
                val meta = metaByGalleryId[entry.galleryId] ?: return@map entry
                entry.copy(
                    category = meta.category,
                    rating = meta.rating,
                    postedEpochMillis = meta.postedEpochMillis,
                    pageCount = meta.pageCount,
                    uploader = meta.uploader,
                    language = meta.language,
                )
            }
        } else {
            fromCurrent
        }
        val nextSeekGid = parseNextSeekGid(html)
        val nextPageIndex = if (nextSeekGid != null) {
            null
        } else {
            NEXT_PAGE_REGEX.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: TOPLIST_NEXT_PAGE_REGEX.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        return EhentaiListPage(
            entries = entries,
            nextPageIndex = nextPageIndex,
            nextSeekGid = nextSeekGid,
        )
    }

    private data class RowMeta(
        val category: String?,
        val rating: Float?,
        val postedEpochMillis: Long?,
        val pageCount: Int?,
        val uploader: String? = null,
        val language: String? = null,
    )

    /**
     * Extracts the seek token from the searchnav `id="unext|dnext"` anchor.
     * Live e-hentai.org/exhentai.org markup HTML-escapes hrefs whenever the
     * URL carries more than one query param (`…&amp;next={gid}`), and the
     * `id`/`href` attribute order inside the <a> tag is not guaranteed — so
     * this anchors on the whole anchor tag and pulls the token out of its
     * href either way.
     */
    private fun parseNextSeekGid(html: String): Long? =
        NEXT_SEEK_ANCHOR_REGEX.findAll(html)
            .mapNotNull { anchor ->
                NEXT_SEEK_HREF_REGEX.find(anchor.groupValues[1])
                    ?.groupValues?.getOrNull(1)?.toLongOrNull()
            }
            .firstOrNull()

    /**
     * E-Hentai list pages carry category (`cs ct…` / `cn ct…` coloured cells),
     * rating (`ir irN` star classes), a posted timestamp (`title="YYYY-MM-DD HH:MM"`
     * tooltip or `data-timestamp` seconds) and a visible page count. We collect
     * them per gallery id when a `/g/{gid}` link sits in the same row (`<tr>`)
     * or the same gallery block (`gl1t`).
     */
    private fun parseRowMeta(html: String): Map<Long, RowMeta> {
        val meta = linkedMapOf<Long, RowMeta>()
        val chunks = sequence {
            for (m in TABLE_ROW_CHUNK_REGEX.findAll(html)) yield(m.groupValues[1])
            for (m in GL1T_CHUNK_REGEX.findAll(html)) yield(m.groupValues[1])
        }
        for (chunk in chunks) {
            val galleryId = GALLERY_ID_IN_ROW_REGEX.find(chunk)?.groupValues?.getOrNull(1)
                ?.toLongOrNull() ?: continue
            val category = CATEGORY_CELL_REGEX.find(chunk)?.groupValues?.getOrNull(1)
                ?.let(::normalizeCategory)
            val rating = RATING_CLASS_REGEX.find(chunk)?.groupValues?.getOrNull(1)
                ?.let(::ratingFromIrClass)
                ?: RATING_SPRITE_REGEX.find(chunk)?.let { m ->
                    ratingFromSprite(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                }
            val uploader = UPLOADER_LINK_REGEX.find(chunk)?.groupValues?.getOrNull(1)
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                ?.takeIf { it.isNotBlank() }
            val language = galleryLanguage(
                LANGUAGE_TAG_REGEX.findAll(chunk)
                    .map { HtmlText.decode(it.groupValues[1]) }.asIterable(),
            )
            val postedMillis = POSTED_TITLE_REGEX.find(chunk)?.groupValues?.getOrNull(1)
                ?.let(::parsePostedDateTime)
                ?: POSTED_DATA_TIMESTAMP_REGEX.find(chunk)?.groupValues?.getOrNull(1)
                    ?.toLongOrNull()?.let { it * 1000L }
            val pages = PAGE_COUNT_REGEX.find(chunk)?.let { m ->
                m.groupValues.getOrNull(1)?.toIntOrNull()
                    ?: m.groupValues.getOrNull(2)?.toIntOrNull()
            }
            if (category == null && rating == null && postedMillis == null && pages == null && language == null) continue
            meta.putIfAbsent(
                galleryId,
                RowMeta(
                    category = category,
                    rating = rating,
                    postedEpochMillis = postedMillis,
                    pageCount = pages,
                    uploader = uploader,
                    language = language,
                ),
            )
        }
        return meta
    }

    private fun normalizeCategory(raw: String): String = when (raw) {
        "Doujinshi", "Manga", "Artist CG", "Game CG", "Western",
        "Non-H", "Image Set", "Cosplay", "Asian Porn", "Misc",
        -> raw
        else -> "Misc"
    }

    /** EhViewer-style `ir irN` classes: `ira` adds nothing, `ir5` → 5.0. */
    /**
     * #D4: the live list draws the rating as one five-star sprite. Each star is 16 px wide; a negative x offset hides
     * whole stars from the right, and the second sprite row (y = -21) shows the last visible star as a half.
     */
    private fun ratingFromSprite(x: Int, y: Int): Float? {
        if (x > 0 || x % 16 != 0) return null
        val whole = 5f + x / 16
        val rating = if (y == -21) whole - 0.5f else whole
        return rating.takeIf { it in 0f..5f }
    }

    private fun ratingFromIrClass(raw: String): Float? {
        val digit = raw.trim().singleOrNull()?.digitToIntOrNull() ?: return null
        return digit.toFloat().coerceIn(0f, 5f)
    }

    /** Parses e-hentai posted tooltips like `2026-08-30 14:22` into epoch millis (UTC). */
    private fun parsePostedDateTime(raw: String): Long? {
        val match = POSTED_DATETIME_REGEX.matchEntire(raw.trim()) ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        val hour = match.groupValues[4].toInt()
        val minute = match.groupValues[5].toInt()
        return dateToEpochMillis(year, month, day, hour, minute)
    }

    private fun dateToEpochMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).run {
        set(year, month - 1, day, hour, minute, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private fun parseCurrentSiteThumbs(html: String): Map<Long, String> {
        val thumbs = linkedMapOf<Long, String>()
        for (match in GLTHUMB_REGEX.findAll(html)) {
            val galleryId = match.groupValues[1].toLongOrNull() ?: continue
            val imgAttrs = match.groupValues[2]
            val thumbUrl = thumbUrlFromImgAttrs(imgAttrs) ?: continue
            thumbs.putIfAbsent(galleryId, thumbUrl)
        }
        return thumbs
    }

    private fun thumbUrlFromImgAttrs(imgAttrs: String): String? {
        DATA_SRC_REGEX.find(imgAttrs)?.groupValues?.getOrNull(1)?.let { return it }
        return SRC_HTTPS_REGEX.find(imgAttrs)?.groupValues?.getOrNull(1)
    }

    private companion object {
        // Legacy fixture: thumb src, then relative /g/{gid}/{token}/ on glink anchor
        private val GALLERY_ROW_REGEX = Regex(
            """<img\s+src="([^"]*)"[^>]*>[\s\S]*?<a\s+href="/g/(\d+)/([0-9a-fA-F]+)/"[^>]*class="glink"[^>]*>([^<]*)</a>""",
            setOf(RegexOption.IGNORE_CASE),
        )
        // Current e-hentai.org: absolute (or relative) /g/ href wrapping <div class="glink">title</div>
        private val GALLERY_LINK_REGEX = Regex(
            """<a\s+href="(?:https?://[^"/]+)?/g/(\d+)/([0-9a-fA-F]+)/"[^>]*>\s*<div\s+class="glink">([^<]*)</div>""",
            setOf(RegexOption.IGNORE_CASE),
        )
        // Compact / Extended list: glthumb id="it{gid}" then cover <img> (src or lazy data-src).
        private val GLTHUMB_REGEX = Regex(
            """<div\s+class="glthumb"\s+id="it(\d+)"[^>]*>[\s\S]*?<img\b([^>]*)/?>""",
            setOf(RegexOption.IGNORE_CASE),
        )
        private val DATA_SRC_REGEX = Regex(
            """data-src="(https?://[^"]+)"""",
            setOf(RegexOption.IGNORE_CASE),
        )
        private val SRC_HTTPS_REGEX = Regex(
            """\ssrc="(https?://[^"]+)"""",
            setOf(RegexOption.IGNORE_CASE),
        )
        private val NEXT_PAGE_REGEX = Regex(
            """href="/\?page=(\d+)"[^>]*>\s*Next page\s*<""",
            setOf(RegexOption.IGNORE_CASE),
        )
        // E-Hentai /toplist.php pagination uses `<table class="ptb">` with a `&gt;` link to `toplist.php?tl=15&p=1`.
        private val TOPLIST_NEXT_PAGE_REGEX = Regex(
            """href="[^"]*toplist\.php\?tl=\d+&(?:amp;)?p=(\d+)"[^>]*>\s*(?:&gt;|>)\s*<""",
            setOf(RegexOption.IGNORE_CASE),
        )
        // Current e-hentai.org/exhentai.org: seek pagination via id=unext|dnext
        // anchors. Attribute order inside the tag is not guaranteed, so capture
        // the whole attribute blob and pull the href out of it.
        private val NEXT_SEEK_ANCHOR_REGEX = Regex(
            """<a\b([^>]*\bid="(?:unext|dnext)"[^>]*)>""",
            setOf(RegexOption.IGNORE_CASE),
        )
        // href value inside an unext/dnext anchor; `[?&](?:amp;)?next=` tolerates
        // the HTML-escaped ampersand (`&amp;next=`) that multi-param URLs render.
        private val NEXT_SEEK_HREF_REGEX = Regex(
            """href="[^"]*[?&](?:amp;)?next=(\d+)""",
            setOf(RegexOption.IGNORE_CASE),
        )
        // Per-row metadata chunks: table rows (itg/gtr) or thumbnail blocks (gl1t).
        private val TABLE_ROW_CHUNK_REGEX = Regex(
            """<tr\b[^>]*>([\s\S]*?)</tr>""",
            setOf(RegexOption.IGNORE_CASE),
        )
        private val GL1T_CHUNK_REGEX = Regex(
            """<div\s+class="gl1t[^"]*"[^>]*>([\s\S]*?)(?=<div\s+class="gl1t|$)""",
            setOf(RegexOption.IGNORE_CASE),
        )
        private val GALLERY_ID_IN_ROW_REGEX = Regex(
            """/g/(\d+)/""",
        )
        private val CATEGORY_CELL_REGEX = Regex(
            """class="(?:cs|cn)\s+ct[^"]*"[^>]*>\s*([^<]+?)\s*<""",
            setOf(RegexOption.IGNORE_CASE),
        )
        private val RATING_CLASS_REGEX = Regex(
            """ir\s+ir(\d)""",
        )
        private val RATING_SPRITE_REGEX = Regex(
            """class="ir[^"]*"\s+style="background-position:\s*(-?\d+)px\s+(-?\d+)px""",
        )
        private val UPLOADER_LINK_REGEX = Regex(
            """/uploader/([^"/?#]+)"""",
        )
        private val LANGUAGE_TAG_REGEX = Regex(
            """title="language:([^"]+)"""",
        )
        private val POSTED_TITLE_REGEX = Regex(
            """title="((?:19|20)\d{2}-\d{2}-\d{2}\s+\d{2}:\d{2})"""",
        )
        private val POSTED_DATA_TIMESTAMP_REGEX = Regex(
            """data-timestamp="(\d+)"""",
        )
        private val POSTED_DATETIME_REGEX = Regex(
            """(\d{4})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2})""",
        )
        private val PAGE_COUNT_REGEX = Regex(
            """(\d+)\s*pages?\b|(\d+)p\b""",
            setOf(RegexOption.IGNORE_CASE),
        )
    }
}
