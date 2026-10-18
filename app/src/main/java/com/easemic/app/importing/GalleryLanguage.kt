package com.easemic.app.importing

private val EDITION_LANGUAGE_TAGS = setOf("translated", "rewrite", "speechless", "text cleaned")

/** Values are decoded by the source parser; raw source tags remain separate. */
fun galleryLanguage(tagValues: Iterable<String>, fallback: String? = null): String? {
    val selected = tagValues.asSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && it.lowercase() !in EDITION_LANGUAGE_TAGS }
        ?: fallback?.trim()?.takeIf { it.isNotEmpty() }
    return selected?.replaceFirstChar { it.titlecase() }
}
