package com.easemic.app.importing.ehentai

import com.easemic.app.importing.HtmlText
import com.easemic.app.importing.galleryLanguage
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

class EhentaiGalleryParser {
    fun parseGallery(
        bytes: ByteArray,
        galleryId: Long,
        token: String,
    ): EhentaiGalleryPlan {
        val html = bytes.toString(Charsets.UTF_8)
        val title = TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?.let { HtmlText.decode(it).trim() }.orEmpty()
        val pageViewPaths = PAGE_VIEW_REGEX.findAll(html).map { it.groupValues[1] }.distinct().toList()
        val declaredPageCount = LENGTH_REGEX.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it > 0 }
        val highestThumbPageIndex = THUMB_PAGE_INDEX_REGEX.findAll(html)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()
            ?.coerceAtLeast(0)
            ?: 0
        val category = CATEGORY_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?.let { HtmlText.decode(it).trim() }?.takeIf { it.isNotEmpty() }
        val languageRow = LANGUAGE_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?.let { HtmlText.decode(it).trim() }?.takeIf { it.isNotEmpty() }
        val artists = mutableListOf<String>()
        val groups = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val languageTags = mutableListOf<String>()
        for (match in TAG_ANCHOR_REGEX.findAll(html)) {
            val namespace = match.groupValues[1].lowercase()
            val label = HtmlText.decode(match.groupValues[2]).trim()
            if (label.isEmpty()) continue
            when (namespace) {
                "artist" -> artists += label
                "group" -> groups += label
                "language" -> {
                    languageTags += label
                    tags += "$namespace:$label"
                }
                else -> tags += "$namespace:$label"
            }
        }
        val language = galleryLanguage(languageTags, fallback = languageRow)
        val previewThumbs = PREVIEW_THUMB_REGEX.findAll(html).mapNotNull { match ->
            val width = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val height = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val sheetUrl = match.groupValues[3].trim()
            val offsetX = match.groupValues[4].toIntOrNull() ?: return@mapNotNull null
            if (sheetUrl.isEmpty() || width <= 0 || height <= 0 || offsetX < 0) {
                return@mapNotNull null
            }
            EhentaiPreviewThumb(
                sheetUrl = sheetUrl,
                offsetX = offsetX,
                width = width,
                height = height,
            )
        }.toList()
        return EhentaiGalleryPlan(
            galleryId = galleryId,
            token = token,
            title = title,
            pageViewPaths = pageViewPaths,
            tags = tags.distinct(),
            artists = artists.distinct(),
            groups = groups.distinct(),
            category = category,
            language = language,
            previewThumbs = previewThumbs,
            declaredPageCount = declaredPageCount,
            highestThumbPageIndex = highestThumbPageIndex,
            postedEpochSeconds = POSTED_REGEX.find(html)?.groupValues?.getOrNull(1)?.let { raw ->
                runCatching {
                    LocalDateTime.parse(HtmlText.decode(raw).trim(), POSTED_FORMAT)
                        .toEpochSecond(ZoneOffset.UTC)
                }.getOrNull()
            },
            rating = RATING_LABEL_REGEX.find(html)?.groupValues?.getOrNull(1)?.let { raw ->
                AVERAGE_RATING_REGEX.matchEntire(HtmlText.decode(raw).trim())
                    ?.groupValues?.getOrNull(1)?.toFloatOrNull()?.takeIf { it in 0f..5f }
            },
        )
    }

    fun parsePageImage(bytes: ByteArray, pageNumber: Int): EhentaiPageImage {
        val html = bytes.toString(Charsets.UTF_8)
        val imageUrl = PAGE_IMG_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: error("EHENTAI_PAGE_IMAGE")
        return EhentaiPageImage(
            pageNumber = pageNumber,
            imageUrl = imageUrl,
            reloadToken = parseEhentaiReloadToken(html),
            showKey = SHOWKEY_REGEX.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The `api.php` `showpage` response (verified live 2026-09-10): `i3` holds
     * the `<img id="img" src="…hath.network…">` tag, `i6` the "Reload broken
     * image" link whose `nl('<token>')` is the H@H node-reload token, and an
     * `error` field (e.g. `"Key mismatch"`) when the showkey/imgkey pair is
     * rejected — callers fall back to the HTML page view on that.
     */
    fun parsePageImageFromApi(json: String, pageNumber: Int): EhentaiPageImage {
        val root = org.json.JSONObject(json)
        val error = root.optString("error").takeIf { it.isNotBlank() }
        if (error != null) error("EHENTAI_API_$error")
        val i3 = root.optString("i3")
        val imageUrl = API_IMG_REGEX.find(i3)?.groupValues?.getOrNull(1)
            ?: error("EHENTAI_API_IMAGE")
        return EhentaiPageImage(
            pageNumber = pageNumber,
            imageUrl = imageUrl,
            reloadToken = parseEhentaiReloadToken(root.optString("i6")),
        )
    }

    private companion object {
        private val POSTED_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)
        private val POSTED_REGEX = Regex(
            """<td\s+class="gdt1">Posted:</td>\s*<td\s+class="gdt2">([^<]+)</td>""",
            RegexOption.IGNORE_CASE,
        )
        private val RATING_LABEL_REGEX = Regex(
            """<[^>]+\bid="rating_label"[^>]*>([^<]*)</[^>]+>""",
            RegexOption.IGNORE_CASE,
        )
        private val AVERAGE_RATING_REGEX = Regex("""Average:\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val TITLE_REGEX = Regex("""<h1\s+id="gn"[^>]*>([^<]*)</h1>""", RegexOption.IGNORE_CASE)
        private val PAGE_VIEW_REGEX = Regex(
            """href="(?:https?://[^"/]+)?(/s/[0-9a-zA-Z]+/\d+-\d+)"""",
            RegexOption.IGNORE_CASE,
        )
        private val PAGE_IMG_REGEX = Regex("""<img\s+id="img"\s+src="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val SHOWKEY_REGEX = Regex("""var\s+showkey\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        private val API_IMG_REGEX = Regex("""<img[^>]*\ssrc="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val CATEGORY_REGEX = Regex(
            """<div\s+id="gdc"[^>]*>[\s\S]*?<div\s+class="cs\s+[^"]*"[^>]*>([^<]+)</div>""",
            RegexOption.IGNORE_CASE,
        )
        private val LANGUAGE_REGEX = Regex(
            """<td\s+class="gdt1">Language:</td>\s*<td\s+class="gdt2">([^<]+)</td>""",
            RegexOption.IGNORE_CASE,
        )
        private val LENGTH_REGEX = Regex(
            """<td\s+class="gdt1">Length:</td>\s*<td\s+class="gdt2">(\d+)\s*pages?</td>""",
            RegexOption.IGNORE_CASE,
        )
        private val THUMB_PAGE_INDEX_REGEX = Regex(
            """/g/\d+/[0-9a-fA-F]+/\?p=(\d+)""",
            RegexOption.IGNORE_CASE,
        )
        private val TAG_ANCHOR_REGEX = Regex(
            """<a\s+id="ta_([a-z0-9_]+):[^"]+"[^>]*>([^<]+)</a>""",
            RegexOption.IGNORE_CASE,
        )
        // Compact gdt cells: CSS sprite sheet + horizontal offset per page thumb.
        private val PREVIEW_THUMB_REGEX = Regex(
            """style="[^"]*width:(\d+)px;height:(\d+)px;background:transparent url\((https?://[^)]+)\)\s+-(\d+)px\s+0\s+no-repeat[^"]*"""",
            RegexOption.IGNORE_CASE,
        )
    }
}
