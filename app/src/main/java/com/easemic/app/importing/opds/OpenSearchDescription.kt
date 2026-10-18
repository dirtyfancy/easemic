package com.easemic.app.importing.opds

import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Minimal reader for an OpenSearch description document — the tiny XML a catalog
 * points at from its `rel="search"` link. We only need the acquisition-feed URL
 * template that carries `{searchTerms}` (Kavita:
 * `/api/opds/<key>/series?query={searchTerms}`), and how to fill it in.
 *
 * Pure/offline: no network, no Android. The caller fetches the bytes and the
 * resulting feed URL.
 */
object OpenSearchDescription {
    private const val SEARCH_TERMS = "{searchTerms}"
    private val TOKEN = Regex("""\{[^}]*}""")

    /**
     * The best OPDS search URL template in [bytes], or null when the document
     * has none. Prefers a `<Url>` whose type names an OPDS acquisition feed;
     * otherwise the first templated URL that carries `{searchTerms}`.
     */
    fun parseTemplate(bytes: ByteArray): String? {
        val document = runCatching {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(bytes.inputStream())
        }.getOrNull() ?: return null
        val root = document.documentElement ?: return null
        val urls = collectUrlElements(root)
            .filter { it.getAttribute("template").contains(SEARCH_TERMS) }
        if (urls.isEmpty()) return null
        val acquisition = urls.firstOrNull { it.getAttribute("type").contains("acquisition") }
        return (acquisition ?: urls.first()).getAttribute("template").trim().ifEmpty { null }
    }

    /**
     * Substitute [query] (URL-encoded) into a template's `{searchTerms}` and
     * neutralize any other optional OpenSearch tokens (`{startIndex?}`, …) so a
     * server never receives a literal `{...}`.
     */
    fun applyTemplate(template: String, query: String): String {
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val filled = template.replace(SEARCH_TERMS, encoded)
        return TOKEN.replace(filled, "")
    }

    private fun collectUrlElements(root: Element): List<Element> {
        val out = mutableListOf<Element>()
        var node: Node? = root.firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE) {
                val el = node as Element
                val local = el.localName?.takeIf { it.isNotEmpty() } ?: el.nodeName.substringAfter(':')
                if (local == "Url") out += el
            }
            node = node.nextSibling
        }
        return out
    }
}
