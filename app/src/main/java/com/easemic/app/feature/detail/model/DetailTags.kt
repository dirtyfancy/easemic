package com.easemic.app.feature.detail.model

import java.util.Locale

fun projectDetailTags(layout: DetailTagLayout, values: List<String>): DetailTags {
    val cleaned = values.map(String::trim).filter(String::isNotEmpty).distinct()
    if (layout is DetailTagLayout.Flat) return DetailTags.Flat(cleaned.map { DetailTag(it, it) })
    layout as DetailTagLayout.Namespaced
    val groups = linkedMapOf<String, MutableList<DetailTag>>()
    for (raw in cleaned) {
        val colon = raw.indexOf(':')
        val namespace = if (colon > 0) raw.substring(0, colon).trim().lowercase(Locale.ROOT) else "tag"
        val label = if (colon > 0) raw.substring(colon + 1).trim() else raw
        if (label.isEmpty() || namespace in layout.bylineNamespaces || namespace in layout.excludedNamespaces) continue
        if (!layout.includeUnlisted && namespace !in layout.order) continue
        groups.getOrPut(namespace) { mutableListOf() }.add(DetailTag(label, raw))
    }
    val order = if (layout.order.isEmpty()) groups.keys.toList() else
        (layout.order + groups.keys).distinct().filter { it in groups }
    return DetailTags.Namespaced(order.map { namespace ->
        val label = layout.labels[namespace] ?: namespace.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
        DetailTagRow(namespace, label, groups.getValue(namespace).distinctBy { it.label })
    })
}

fun galleryDetailPeople(artists: List<String>, groups: List<String>, tags: List<String>): List<DetailPerson> {
    fun person(raw: String, role: String): DetailPerson? {
        val name = raw.trim().removePrefix("$role:").trim().takeIf(String::isNotEmpty) ?: return null
        return DetailPerson(name, role, "$role:$name")
    }
    return (artists.mapNotNull { person(it, "artist") } + groups.mapNotNull { person(it, "group") } +
        tags.mapNotNull { raw ->
            val role = raw.substringBefore(':').trim().lowercase(Locale.ROOT)
            if (':' in raw && role in setOf("artist", "group")) person(raw.substringAfter(':'), role) else null
        }).distinctBy { it.role to it.name }
}

fun detailTagCount(tags: DetailTags): Int = when (tags) {
    is DetailTags.Flat -> tags.chips.size
    is DetailTags.Namespaced -> tags.rows.sumOf { it.chips.size }
}
