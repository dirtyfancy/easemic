package com.easemic.app.importing

import com.easemic.app.model.MetadataSnapshot
import com.easemic.app.model.ParsedComicInfo
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.SAXException

/**
 * A46 (ledger 2026-09-04-owner-bug-backlog-r2-model.md :795, owner ruling): local
 * imports speak the same `artist:`/`group:` creators and `female:`/`male:` tag
 * dialect the catalog download path already persists
 * ([com.easemic.app.feature.download.DownloadCommitMetadata.creators]),
 * so a Detail row renders identically whichever path produced it:
 *
 * - `<Writer>` → `group:<writer>`; `<Penciller>` → `artist:<penciller>`. `<Inker>` is
 *   deliberately dropped: the ruling covers only Writer/Penciller, and no inspected
 *   real-world ComicInfo (the Mihon cbz fixtures under artifacts/a47-fixture, the
 *   EhViewer tree-pull) carries an Inker at all — mapping it to `artist:` would be
 *   invented and would mislabel a distinct role.
 * - Every `<Genre>` entry is also routed into tags: `X ♀`→`female:X`, `X ♂`→`male:X`,
 *   plain entries verbatim. The raw `genres` list keeps the untouched split, so the
 *   Detail category line still carries the file's own vocabulary.
 * - `<Web>` is exposed as [ParsedComicInfo.webUrl]; MetadataResolver turns it into a
 *   `comicinfo.web` provenance claim.
 */
class ComicInfoParser {
    fun parse(bytes: ByteArray, fallbackFolderName: String): ParsedComicInfo =
        runCatching {
            require(bytes.size <= MAX_INPUT_BYTES)
            require(!containsDoctypeDeclaration(bytes))
            val factory = DocumentBuilderFactory.newInstance().apply {
                setSupportedFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setSupportedFeature("http://xml.org/sax/features/external-general-entities", false)
                setSupportedFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setSupportedFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setSupportedFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                runCatching { isXIncludeAware = false }
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ ->
                    throw SAXException("External XML entities are not allowed.")
                }
            }
            val root = builder.parse(ByteArrayInputStream(bytes)).documentElement
            require(root.nodeName == "ComicInfo")

            fun value(name: String): String? =
                root.getElementsByTagName(name).item(0)?.textContent?.trim()?.ifEmpty { null }

            fun split(name: String): List<String> =
                value(name)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

            val series = value("Series")
            val title = value("Title") ?: series ?: fallbackFolderName
            val genres = split("Genre")
            ParsedComicInfo(
                metadata = MetadataSnapshot(
                    title = title,
                    series = series,
                    alternateSeries = value("AlternateSeries"),
                    creators = listOfNotNull(
                        value("Writer")?.let { "group:$it" },
                        value("Penciller")?.let { "artist:$it" },
                    ),
                    genres = genres,
                    tags = (split("Tags") + genres.mapNotNull(::ehentaiTagFromGenreEntry)).distinct(),
                    language = value("LanguageISO"),
                ),
                warning = false,
                webUrl = value("Web"),
            )
        }.getOrElse {
            ParsedComicInfo(MetadataSnapshot.fallback(fallbackFolderName), warning = true)
        }

    /**
     * A46 (ledger 2026-09-04-owner-bug-backlog-r2-model.md :795, owner ruling): a Mihon
     * cbz carries `<Genre>sole male ♂, big breasts ♀ …</Genre>`, and the E-Hentai
     * dialect spells those tags `male:sole male` / `female:big breasts`. Genre entries
     * ending in `♀` become `female:<label>`, entries ending in `♂` become
     * `male:<label>`, and entries without a symbol pass through verbatim — the owner's
     * table routes every Genre entry into tags. A symbol entry with no label carries
     * no fact and is dropped.
     */
    private fun ehentaiTagFromGenreEntry(entry: String): String? =
        when {
            entry.endsWith(FEMALE_SYMBOL) -> "female:${entry.dropLast(FEMALE_SYMBOL.length).trim()}"
            entry.endsWith(MALE_SYMBOL) -> "male:${entry.dropLast(MALE_SYMBOL.length).trim()}"
            else -> entry
        }?.takeIf { it.isNotEmpty() && !it.endsWith(":") }

    private fun DocumentBuilderFactory.setSupportedFeature(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private fun containsDoctypeDeclaration(bytes: ByteArray): Boolean {
        val asciiWithoutNulls = buildString(bytes.size) {
            bytes.forEach { byte ->
                if (byte != 0.toByte()) append((byte.toInt() and 0xff).toChar())
            }
        }
        return asciiWithoutNulls.contains("<!DOCTYPE")
    }

    private companion object {
        const val MAX_INPUT_BYTES = 1_048_576
        const val FEMALE_SYMBOL = "♀"
        const val MALE_SYMBOL = "♂"
    }
}
