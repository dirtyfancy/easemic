package com.easemic.app.feature.browse

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

data class RemoteDetailTagGroup(
    val title: String,
    val chips: List<String>,
    /**
     * Raw tag strings parallel to [chips] (namespace included when the source
     * supplied one, e.g. "parody:alpha-tag"). Empty for groups that never had a
     * raw form; [RemoteDetailMetadataLayout.from] always fills it.
     */
    val rawChips: List<String> = chips,
)

data class RemoteDetailMetadataLayout(
    val metaLine: String?,
    val groups: List<RemoteDetailTagGroup>,
) {
    companion object {
        /**
         * [pageCountLabel] formats the leading "{n} pages" part. The default is the
         * catalog path's own wording, unchanged (2026-09-01 chrome freeze); A32's Local
         * branch passes `LocalDetailCopy.pageCountLabel`, which says "1 page" for a
         * one-page comic instead of "1 pages".
         */
        fun from(
            item: BrowseFeedItem,
            pageCountLabel: (Int) -> String = { "$it pages" },
            zone: ZoneId = ZoneId.systemDefault(),
            locale: Locale = Locale.getDefault(),
        ): RemoteDetailMetadataLayout {
            val creators = (item.artists + item.groups).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val metaParts = buildList {
                item.pageCount?.let { add(pageCountLabel(it)) }
                item.language?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                item.category?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                if (creators.isNotEmpty()) add("by ${creators.joinToString(", ")}")
                item.uploadDateEpochSeconds?.let { epoch ->
                    add("Uploaded · ${uploadDateLabel(epoch, zone, locale)}")
                }
            }
            val byNamespace = linkedMapOf<String, MutableList<Pair<String, String>>>()
            // `artists`/`groups` are compatibility fields from sources and
            // local metadata. They are still part of the source Tag system;
            // synthesize their namespace only when the raw list did not carry
            // it, then deduplicate the combined stream by canonical tag.
            val rawTags = buildList {
                addAll(item.tags)
                addAll(item.artists.namespacedAs("artist"))
                addAll(item.groups.namespacedAs("group"))
            }
            val seenCanonicalTags = mutableSetOf<String>()
            for (raw in rawTags) {
                val tag = raw.trim()
                if (tag.isEmpty()) continue
                val colon = tag.indexOf(':')
                val namespace: String
                val label: String
                if (colon > 0) {
                    namespace = tag.substring(0, colon).trim().lowercase()
                    label = tag.substring(colon + 1).trim()
                } else {
                    namespace = "other"
                    label = tag
                }
                if (label.isEmpty()) continue
                val canonical = "$namespace:${label.lowercase()}"
                if (!seenCanonicalTags.add(canonical)) continue
                byNamespace.getOrPut(namespace) { mutableListOf() }.add(label to tag)
            }
            val groups = mutableListOf<RemoteDetailTagGroup>()
            val namespaceOrder = listOf("artist", "group")
            val insertionOrder = byNamespace.keys.toList()
            val orderedNamespaces = insertionOrder.sortedWith(
                compareBy<String> {
                    namespaceOrder.indexOf(it).let { index -> if (index == -1) Int.MAX_VALUE else index }
                }.thenBy { insertionOrder.indexOf(it) },
            )
            for (namespace in orderedNamespaces) {
                val chips = byNamespace.getValue(namespace)
                val distinct = chips.distinct()
                if (distinct.isEmpty()) continue
                groups += RemoteDetailTagGroup(
                    titleCaseNamespace(namespace),
                    distinct.map { it.first },
                    distinct.map { it.second },
                )
            }

            return RemoteDetailMetadataLayout(
                metaLine = metaParts.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                groups = groups,
            )
        }

        /**
         * #E31: the upload date renders as the device-local calendar date in
         * the device locale's medium style (e.g. "Oct 5, 2025") — no time of
         * day, never the raw ISO instant. Same idiom as `updateDayGroups`.
         */
        private fun uploadDateLabel(epochSeconds: Long, zone: ZoneId, locale: Locale): String =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate())

        private fun titleCaseNamespace(namespace: String): String =
            namespace.split('_', '-', ' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ") { part ->
                    part.replaceFirstChar { ch -> ch.titlecase() }
                }
                .ifBlank { "Other" }

        private fun List<String>.namespacedAs(namespace: String): List<String> =
            mapNotNull { raw ->
                val value = raw.trim()
                if (value.isEmpty()) return@mapNotNull null
                val prefix = value.substringBefore(':', "").trim().lowercase()
                if (prefix == namespace) value else "$namespace:$value"
            }

    }
}
