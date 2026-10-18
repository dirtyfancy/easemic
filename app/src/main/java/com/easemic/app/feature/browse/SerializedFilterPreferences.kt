package com.easemic.app.feature.browse

import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.providers.JmEnableStore
import com.easemic.app.ui.PreferenceMap

private const val Z_PREFIX = "browse_filter.${GalleryIdentityV1.ZAIMANHUA}."
private const val J_PREFIX = "browse_filter.${JmEnableStore.SOURCE_ID}."

private inline fun <reified E : Enum<E>> PreferenceMap.enumValue(key: String, default: E): E =
    enumValues<E>().firstOrNull { it.name == getString(key, null) } ?: default

internal fun PreferenceMap.readZFilters(): ZaimanhuaFilters {
    val id = getString(Z_PREFIX + "genreId", null)?.toLongOrNull()
    val label = getString(Z_PREFIX + "genreLabel", null)
    val genre = if (id != null && id > 0 && !label.isNullOrBlank()) ZGenre(id, label) else ZGenre.ANY
    return ZaimanhuaFilters(
        sort = enumValue(Z_PREFIX + "sort", ZSort.UPDATED),
        genre = genre,
        region = enumValue(Z_PREFIX + "region", ZRegion.ANY),
        audience = enumValue(Z_PREFIX + "audience", ZAudience.ANY),
        status = enumValue(Z_PREFIX + "status", ZStatus.ANY),
        ranking = ZRanking.entries.firstOrNull { it.name == getString(Z_PREFIX + "ranking", null) },
    )
}

internal fun PreferenceMap.writeZFilters(f: ZaimanhuaFilters) {
    mapOf(
        "sort" to f.sort.name, "genreId" to f.genre.id.toString(), "genreLabel" to f.genre.label,
        "region" to f.region.name, "audience" to f.audience.name, "status" to f.status.name,
        "ranking" to f.ranking?.name.orEmpty(),
    ).forEach { (key, value) -> putString(Z_PREFIX + key, value) }
}

internal fun PreferenceMap.readJFilters(): JmFilters = JmFilters(
    enumValue(J_PREFIX + "category", JCategory.ALL),
    enumValue(J_PREFIX + "sort", JSort.RECENT),
    enumValue(J_PREFIX + "time", JTime.ALL),
)

internal fun PreferenceMap.writeJFilters(f: JmFilters) {
    mapOf("category" to f.category.name, "sort" to f.sort.name, "time" to f.time.name)
        .forEach { (key, value) -> putString(J_PREFIX + key, value) }
}
