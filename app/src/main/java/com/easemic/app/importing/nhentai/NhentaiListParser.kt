package com.easemic.app.importing.nhentai

import com.easemic.app.importing.HtmlText
import org.json.JSONObject

class NhentaiListParser(
    private val thumbOrigin: String = NhentaiClient.DEFAULT_THUMB_ORIGIN,
) {
    /**
     * @param currentPageIndex 0-based Browse page index used to compute [NhentaiListPage.nextPageIndex].
     */
    fun parse(bytes: ByteArray, currentPageIndex: Int = 0): NhentaiListPage {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val results = root.getJSONArray("result")
        val entries = buildList {
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                // #D4: the API returns HTML-escaped titles (`&#128052;` for an emoji).
                val title = HtmlText.decode(
                    item.optString("english_title")
                        .ifBlank { item.optString("japanese_title") }
                        .ifBlank { "nhentai ${item.getLong("id")}" },
                )
                val thumbPath = item.optString("thumbnail").takeIf { it.isNotBlank() }
                val favorites = item.takeIf { it.has("num_favorites") }
                    ?.optInt("num_favorites")
                    ?.takeIf { it > 0 }
                add(
                    NhentaiListEntry(
                        title = title,
                        galleryId = item.getLong("id"),
                        thumbUrl = thumbPath?.let { absoluteUrl(thumbOrigin, it) },
                        pageCount = item.optInt("num_pages").takeIf { it > 0 },
                        favorites = favorites,
                    ),
                )
            }
        }
        val numPages = root.optInt("num_pages", 1).coerceAtLeast(1)
        val apiPage = currentPageIndex + 1
        val nextPageIndex = if (apiPage < numPages) currentPageIndex + 1 else null
        return NhentaiListPage(entries = entries, nextPageIndex = nextPageIndex)
    }

    companion object {
        fun absoluteUrl(origin: String, path: String): String {
            val base = origin.trimEnd('/')
            val rel = path.trimStart('/')
            return "$base/$rel"
        }
    }
}
