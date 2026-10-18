package com.easemic.app.sync

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

data class S3ListedObject(
    val key: String,
    val sizeBytes: Long,
)

data class S3ListPage(
    val objects: List<S3ListedObject>,
    val isTruncated: Boolean,
    val nextContinuationToken: String?,
)

object S3ListObjectsV2 {
    fun parseKeys(xml: String): List<String> =
        parsePage(xml).objects.map { it.key }

    fun parsePage(xml: String): S3ListPage {
        val doc = newDocumentBuilder()
            .parse(xml.byteInputStream(Charsets.UTF_8))
        val contentsNodes = doc.getElementsByTagNameNS("*", "Contents")
        val objects = ArrayList<S3ListedObject>(contentsNodes.length)
        for (index in 0 until contentsNodes.length) {
            val contents = contentsNodes.item(index) as Element
            val key = childText(contents, "Key") ?: continue
            if (key.isEmpty()) continue
            val sizeBytes = childText(contents, "Size")?.toLongOrNull() ?: 0L
            objects.add(S3ListedObject(key, sizeBytes))
        }
        return S3ListPage(
            objects = objects,
            isTruncated = readIsTruncated(doc),
            nextContinuationToken = readNextContinuationToken(doc),
        )
    }

    private fun newDocumentBuilder() = newDocumentBuilderFactory().newDocumentBuilder()

    private fun newDocumentBuilderFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory
    }

    private fun childText(parent: Element, localName: String): String? {
        val nodes = parent.getElementsByTagNameNS("*", localName)
        if (nodes.length == 0) return null
        return nodes.item(0).textContent?.trim().orEmpty()
    }

    private fun readIsTruncated(doc: Node): Boolean {
        val nodes = (doc as org.w3c.dom.Document).getElementsByTagNameNS("*", "IsTruncated")
        if (nodes.length == 0) return false
        return nodes.item(0).textContent?.trim().equals("true", ignoreCase = true)
    }

    private fun readNextContinuationToken(doc: org.w3c.dom.Document): String? {
        val nodes = doc.getElementsByTagNameNS("*", "NextContinuationToken")
        if (nodes.length == 0) return null
        val token = nodes.item(0).textContent?.trim().orEmpty()
        return token.ifEmpty { null }
    }
}
