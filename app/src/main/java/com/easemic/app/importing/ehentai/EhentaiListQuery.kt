package com.easemic.app.importing.ehentai

import java.net.URLEncoder

/**
 * List/search query for E-Hentai gallery index pages.
 * [fCats] null means omit; 0 also omitted. [fSearch] null/blank means latest `/`
 * when [fCats] and advanced filters are also absent.
 */
data class EhentaiListQuery(
    val fSearch: String? = null,
    val fCats: Int? = null,
    val pageIndex: Int = 0,
    /** Current-site seek continuation (`/?next={gid}`). Prefer over [pageIndex] when set. */
    val seekNextGid: Long? = null,
    val popular: Boolean = false,
    val toplistCode: Int? = null,
    val onlyTorrents: Boolean = false,
    val showExpunged: Boolean = false,
    val minRating: Int? = null,
    val minPages: Int? = null,
    val maxPages: Int? = null,
    val jumpParam: String? = null,
) {
    fun toPath(): String {
        if (popular) return "/popular"
        if (toplistCode != null) {
            return if (pageIndex <= 0) {
                "/toplist.php?tl=$toplistCode"
            } else {
                "/toplist.php?tl=$toplistCode&p=$pageIndex"
            }
        }
        val search = fSearch?.trim().orEmpty()
        val cats = fCats?.takeIf { it != 0 }
        val seek = seekNextGid
        val rating = minRating?.takeIf { it in 2..5 }
        val hasPages = minPages != null || maxPages != null
        val advSearch = onlyTorrents || showExpunged || rating != null || hasPages
        val jump = jumpParam?.trim()?.takeIf { it.isNotEmpty() }

        if (search.isEmpty() && cats == null && !advSearch && jump == null) {
            return when {
                seek != null -> "/?next=$seek"
                pageIndex <= 0 -> "/"
                else -> "/?page=$pageIndex"
            }
        }
        return buildString {
            append("/?")
            var first = true
            fun appendParam(name: String, rawValue: String) {
                if (!first) append('&')
                append(name)
                append('=')
                append(rawValue)
                first = false
            }
            if (search.isNotEmpty()) {
                appendParam("f_search", URLEncoder.encode(search, Charsets.UTF_8.name()))
            }
            if (cats != null) {
                appendParam("f_cats", cats.toString())
            }
            if (advSearch) {
                appendParam("advsearch", "1")
                if (onlyTorrents) appendParam("f_sto", "on")
                if (showExpunged) appendParam("f_sh", "on")
                if (rating != null) {
                    appendParam("f_sr", "on")
                    appendParam("f_srdd", rating.toString())
                }
                if (hasPages) {
                    appendParam("f_sp", "on")
                    if (minPages != null) appendParam("f_spf", minPages.toString())
                    if (maxPages != null) appendParam("f_spt", maxPages.toString())
                }
            }
            if (jump != null) {
                appendParam("jump", jump)
            }
            if (seek != null) {
                appendParam("next", seek.toString())
            } else if (pageIndex > 0) {
                appendParam("page", pageIndex.toString())
            }
        }
    }
}
