package com.easemic.app.feature.source

interface ComicSourceRegistry {
    fun source(namespace: String): ComicSource?
    fun all(): List<ComicSource>
}

class BuiltInComicSourceRegistry(
    sources: List<ComicSource>,
) : ComicSourceRegistry {
    private val orderedSources = sources.toList()
    private val byNamespace = orderedSources.associateBy { it.descriptor.namespace }.also { map ->
        require(map.size == orderedSources.size) { "duplicate ComicSource namespace" }
    }

    override fun source(namespace: String): ComicSource? = byNamespace[namespace]

    override fun all(): List<ComicSource> = orderedSources
}
