package com.easemic.app.feature.browse

import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.providers.ExhentaiEnableStore
import com.easemic.app.feature.providers.JmEnableStore
import com.easemic.app.feature.source.SourceDetailProfiles
import com.easemic.app.feature.source.builtInDetailProfiles

enum class DetailSearchKind { TAG, AUTHOR }
enum class JmSearchScope { TAG, AUTHOR }

sealed interface TagSearchTarget {
    val sourceId: String

    data class Text(override val sourceId: String, val query: String) : TagSearchTarget

    data class Genre(val genre: ZGenre) : TagSearchTarget {
        override val sourceId: String = GalleryIdentityV1.ZAIMANHUA
    }

    data class JmScoped(val query: String, val scope: JmSearchScope) : TagSearchTarget {
        override val sourceId: String = JmEnableStore.SOURCE_ID
    }
}

/**
 * Resolves a metadata value against the source that originally supplied the
 * detail. Availability is deliberately decided here from the current Browse
 * options; storage and canonical download identities are not fallbacks.
 */
fun tagSearchTarget(
    providerId: String?,
    kind: DetailSearchKind,
    rawText: String,
    offeredSources: List<BrowseSourceOption>,
    genres: List<ZGenre>,
    profiles: SourceDetailProfiles = builtInDetailProfiles,
): TagSearchTarget? {
    val text = rawText.trim().takeIf(String::isNotEmpty) ?: return null
    val offered = offeredSources.mapTo(mutableSetOf()) { it.id }
    val provider = providerId?.trim()
    val destination = if (provider == ExhentaiEnableStore.SOURCE_ID && provider !in offered) {
        EhentaiEnableStore.SOURCE_ID
    } else provider
    if (destination == null || destination !in offered) return null
    return profiles.profile(destination).searchFor(text, kind, destination, genres)
}

fun filtersAfterTagSearch(current: SourceFilterState, target: TagSearchTarget): SourceFilterState =
    if (target is TagSearchTarget.Genre) {
        SourceFilterState(zaimanhua = ZaimanhuaFilters(genre = target.genre))
    } else {
        current
    }
