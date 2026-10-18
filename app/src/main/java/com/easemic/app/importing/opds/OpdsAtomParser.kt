package com.easemic.app.importing.opds

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

class OpdsAtomParser {
    fun parse(bytes: ByteArray): OpdsFeed {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(bytes.inputStream())
        val feed = document.documentElement
        require(localName(feed) == "feed") { "OPDS_ROOT" }
        return OpdsFeed(
            id = childText(feed, "id"),
            title = childText(feed, "title").orEmpty(),
            entries = childElements(feed, "entry").map(::readEntry),
            links = childElements(feed, "link").mapNotNull(::readLink),
            totalResults = childText(feed, "totalResults")?.toIntOrNull()?.takeIf { it >= 0 },
        )
    }

    private fun readEntry(entry: Element): OpdsEntry =
        OpdsEntry(
            id = childText(entry, "id"),
            title = childText(entry, "title").orEmpty(),
            summary = childText(entry, "summary"),
            links = childElements(entry, "link").mapNotNull(::readLink),
            author = readAuthor(entry),
            publisher = readPublisher(entry),
        )

    private fun readAuthor(entry: Element): String? {
        val author = childElements(entry, "author").firstOrNull() ?: return null
        return childText(author, "name")
            ?: author.textContent?.trim()?.ifEmpty { null }
    }

    private fun readPublisher(entry: Element): String? =
        childText(entry, "publisher")
            ?: childText(entry, "dcterms:publisher")

    private fun readLink(link: Element): OpdsLink? {
        val href = link.getAttribute("href").trim()
        if (href.isEmpty()) return null
        return OpdsLink(
            rel = mapRel(link.getAttribute("rel")),
            href = href,
            type = link.getAttribute("type").ifBlank { null },
            count = readPseCount(link),
        )
    }

    private fun readPseCount(link: Element): Int? {
        val namespaced = link.getAttributeNS(PSE_NS, "count")
        val raw = namespaced.ifBlank { link.getAttribute("count") }
        return raw.trim().toIntOrNull()
    }

    /**
     * #C12 audit M3: RFC 4287 §4.2.7.2 makes @rel a space-separated list of
     * link relation types. Split it and pick the most actionable relation —
     * an acquisition/open-access link that also carries `preview` is still an
     * acquisition. A whole-string compare read such a link as OTHER, which hid
     * the acquisition from the existence check's feed walk.
     */
    private fun mapRel(raw: String?): OpdsLinkRel {
        val mapped = raw?.trim()
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotEmpty() }
            ?.map(::mapOneRel)
            ?: return OpdsLinkRel.OTHER
        return mapped.firstOrNull { it == OpdsLinkRel.ACQUISITION_OPEN_ACCESS }
            ?: mapped.firstOrNull { it == OpdsLinkRel.ACQUISITION }
            ?: mapped.firstOrNull { it != OpdsLinkRel.OTHER }
            ?: OpdsLinkRel.OTHER
    }

    private fun mapOneRel(raw: String): OpdsLinkRel = when (raw) {
        "subsection" -> OpdsLinkRel.SUBSECTION
        "next" -> OpdsLinkRel.NEXT
        "self" -> OpdsLinkRel.SELF
        "search" -> OpdsLinkRel.SEARCH
        IMAGE_REL -> OpdsLinkRel.IMAGE
        IMAGE_THUMB_REL -> OpdsLinkRel.IMAGE_THUMBNAIL
        ACQUISITION_REL -> OpdsLinkRel.ACQUISITION
        ACQUISITION_OPEN_ACCESS_REL -> OpdsLinkRel.ACQUISITION_OPEN_ACCESS
        PSE_STREAM_REL -> OpdsLinkRel.PSE_STREAM
        else -> OpdsLinkRel.OTHER
    }

    private fun childText(parent: Element, name: String): String? =
        childElements(parent, name).firstOrNull()?.textContent?.trim()?.ifEmpty { null }

    private fun childElements(parent: Element, name: String): List<Element> {
        val out = mutableListOf<Element>()
        var node = parent.firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE && localName(node) == name) {
                out += node as Element
            }
            node = node.nextSibling
        }
        return out
    }

    private fun localName(node: Node): String =
        node.localName?.takeIf { it.isNotEmpty() } ?: node.nodeName.substringAfter(':')

    private companion object {
        const val IMAGE_REL = "http://opds-spec.org/image"
        const val IMAGE_THUMB_REL = "http://opds-spec.org/image/thumbnail"
        const val ACQUISITION_REL = "http://opds-spec.org/acquisition"
        const val ACQUISITION_OPEN_ACCESS_REL = "http://opds-spec.org/acquisition/open-access"
        const val PSE_STREAM_REL = "http://vaemendis.net/opds-pse/stream"
        const val PSE_NS = "http://vaemendis.net/opds-pse/ns"
    }
}
