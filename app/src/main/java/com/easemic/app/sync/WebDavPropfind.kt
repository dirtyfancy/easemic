package com.easemic.app.sync

import java.net.URLDecoder

/**
 * #C50: a WebDAV `207 Multi-Status` body reduced to (href, size, isCollection).
 *
 * Deliberately a namespace-prefix-agnostic scanner, not a DOM: the measured servers
 * spell the same DAV: elements `D:href` (hacdias, 123pan), `lp1:getcontentlength` /
 * `g0:` (Synology Apache) and unprefixed under a default namespace, and a bound-prefix
 * DOM parse would have to resolve all three. Only `href`, `getcontentlength` and a
 * `collection` marker inside `resourcetype` are read.
 */
object WebDavPropfind {
    data class Entry(val href: String, val sizeBytes: Long, val isCollection: Boolean)

    private val RESPONSE = Regex("""<(?:[A-Za-z0-9_.-]+:)?response\b[^>]*>(.*?)</(?:[A-Za-z0-9_.-]+:)?response>""", RegexOption.DOT_MATCHES_ALL)
    private val HREF = Regex("""<(?:[A-Za-z0-9_.-]+:)?href\b[^>]*>(.*?)</(?:[A-Za-z0-9_.-]+:)?href>""", RegexOption.DOT_MATCHES_ALL)
    private val LENGTH = Regex("""<(?:[A-Za-z0-9_.-]+:)?getcontentlength\b[^>]*>\s*(\d+)\s*<""")
    private val COLLECTION = Regex("""<(?:[A-Za-z0-9_.-]+:)?collection\b""")

    fun parse(xml: String): List<Entry> = RESPONSE.findAll(xml).mapNotNull { match ->
        val body = match.groupValues[1]
        val rawHref = HREF.find(body)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
        Entry(
            href = decodeHref(unescapeXml(rawHref)),
            sizeBytes = LENGTH.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: -1L,
            isCollection = COLLECTION.containsMatchIn(body),
        )
    }.toList()

    /** An href is either an absolute URL or an absolute path; either way return the decoded PATH. */
    private fun decodeHref(href: String): String {
        val path = if (href.startsWith("http://") || href.startsWith("https://")) {
            href.substringAfter("://").let { rest -> rest.indexOf('/').let { if (it < 0) "/" else rest.substring(it) } }
        } else {
            href
        }
        return URLDecoder.decode(path.replace("+", "%2B"), Charsets.UTF_8.name())
    }

    private fun unescapeXml(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
