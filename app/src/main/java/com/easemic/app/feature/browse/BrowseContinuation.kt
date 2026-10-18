package com.easemic.app.feature.browse

/**
 * Opaque Browse list continuation tokens for infinite scroll.
 * Front uses EH seek (`?next=gid`) or legacy `?page=`; index uses 0-based page indices.
 * There is no aggregate "all" mode — a token always belongs to one source.
 */
object BrowseContinuation {
    private const val FRONT_NEXT = "front:next:"
    private const val FRONT_PAGE = "front:page:"
    private const val INDEX_PAGE = "index:page:"

    // OPDS servers do not expose page numbers we can compute; they hand back an
    // opaque `rel="next"` href per page (Kavita: `?pageNumber=N`, Komga: a page
    // cursor). The token therefore carries the provider id plus that resolved
    // absolute href, separated by a space — providers ids cannot contain one.
    private const val OPDS_NEXT = "opds:next:"
    // A server-side OpenSearch results page. Distinct from OPDS_NEXT so the
    // continuation loader knows NOT to re-apply the client-side title filter —
    // Kavita already searched (titles, tags, summary), and filtering by title
    // substring here would drop legitimate matches (#10).
    private const val OPDS_SEARCH_NEXT = "opds:search-next:"

    fun frontNext(gid: Long): String = "$FRONT_NEXT$gid"

    fun frontPage(pageIndex: Int): String = "$FRONT_PAGE$pageIndex"

    fun indexPage(pageIndex: Int): String = "$INDEX_PAGE$pageIndex"

    fun opdsNext(providerId: String, href: String): String = "$OPDS_NEXT$providerId $href"

    fun opdsSearchNext(providerId: String, href: String): String =
        "$OPDS_SEARCH_NEXT$providerId $href"

    fun isOpdsSearchNext(token: String): Boolean = token.startsWith(OPDS_SEARCH_NEXT)

    fun parseOpdsProviderId(token: String): String? =
        opdsPayload(token)?.substringBefore(' ')?.takeIf { it.isNotEmpty() }

    fun parseOpdsNextHref(token: String): String? =
        opdsPayload(token)
            ?.substringAfter(' ', missingDelimiterValue = "")
            ?.takeIf { it.isNotEmpty() }

    private fun opdsPayload(token: String): String? =
        token.removePrefix(OPDS_NEXT).takeIf { token.startsWith(OPDS_NEXT) }

    fun parseOpdsSearchProviderId(token: String): String? =
        opdsSearchPayload(token)?.substringBefore(' ')?.takeIf { it.isNotEmpty() }

    fun parseOpdsSearchNextHref(token: String): String? =
        opdsSearchPayload(token)
            ?.substringAfter(' ', missingDelimiterValue = "")
            ?.takeIf { it.isNotEmpty() }

    private fun opdsSearchPayload(token: String): String? =
        token.removePrefix(OPDS_SEARCH_NEXT).takeIf { token.startsWith(OPDS_SEARCH_NEXT) }

    fun parseFrontNextGid(token: String): Long? =
        token.removePrefix(FRONT_NEXT).takeIf { token.startsWith(FRONT_NEXT) }?.toLongOrNull()

    fun parseFrontPageIndex(token: String): Int? =
        token.removePrefix(FRONT_PAGE).takeIf { token.startsWith(FRONT_PAGE) }?.toIntOrNull()

    fun parseIndexPageIndex(token: String): Int? =
        token.removePrefix(INDEX_PAGE).takeIf { token.startsWith(INDEX_PAGE) }?.toIntOrNull()
}
