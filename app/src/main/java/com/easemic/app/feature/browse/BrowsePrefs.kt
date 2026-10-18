package com.easemic.app.feature.browse

import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.providers.CloudCollectionSource
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.source.zaimanhua.ZAIMANHUA_DISPLAY_NAME
import com.easemic.app.feature.providers.ExhentaiEnableStore
import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.feature.providers.JmEnableStore
import com.easemic.app.feature.source.ComicSourceRegistry
import com.easemic.app.feature.providers.OpdsProviderConfig

enum class BrowseCategory {
    DOUJINSHI,
    MANGA,
    ARTIST_CG,
    GAME_CG,
    WESTERN,
    NON_H,
    IMAGE_SET,
    COSPLAY,
    ASIAN_PORN,
    MISC,
}

enum class BrowseSearchMode {
    NORMAL,
    WATCHED,
    UPLOADER,
    TAG,
}

enum class BrowseTimelineSort {
    RECENT,
}

/**
 * Normalize a freeform / suggested language token for Browse filtering.
 * Empty or whitespace-only input yields null (ignored).
 */
fun normalizeLanguageTag(raw: String): String? {
    val normalized = raw.trim().lowercase().replace(Regex("\\s+"), "")
    return normalized.takeIf { it.isNotEmpty() }
}

/** Independently curated common language tokens (not copied from GPL sources). */
val SuggestedBrowseLanguageTags: List<String> = listOf(
    "chinese",
    "japanese",
    "english",
    "korean",
    "spanish",
    "french",
    "russian",
    "german",
    "portuguese",
    "thai",
    "vietnamese",
    "italian",
    "dutch",
    "polish",
    "hungarian",
    "swedish",
    "finnish",
    "norwegian",
    "danish",
    "turkish",
    "arabic",
    "czech",
    "greek",
    "hebrew",
)

data class BrowseSourceOption(
    val id: String,
    val label: String,
)

/**
 * Maps legacy / stale Browse source ids onto a source that can actually load.
 *
 * Rules:
 * - Enabled official sources keep their id.
 * - An official source id whose enable store is off (user disabled it after
 *   selecting it, or it was never enabled) falls back to [DEFAULT_BROWSE_SOURCE_ID]
 *   instead of surviving as a stale id.
 * - A configured OPDS provider id is kept (OPDS providers are first-class
 *   Browse sources now).
 * - Everything else — the removed "all" aggregate, legacy Library-owned ids,
 *   unknown/opaque ids — resets to [DEFAULT_BROWSE_SOURCE_ID].
 */
fun normalizeBrowseSourceId(
    raw: String,
    ehentaiEnabled: Boolean = true,
    exhentaiEnabled: Boolean = false,
    nhentaiEnabled: Boolean = true,
    zaimanhuaEnabled: Boolean = false,
    opdsProviderIds: Set<String> = emptySet(),
    jmEnabled: Boolean = false,
): String =
    when {
        raw == EhentaiEnableStore.SOURCE_ID && ehentaiEnabled -> raw
        raw == ExhentaiEnableStore.SOURCE_ID && exhentaiEnabled -> raw
        raw == NhentaiEnableStore.SOURCE_ID && nhentaiEnabled -> raw
        raw == GalleryIdentityV1.ZAIMANHUA && zaimanhuaEnabled -> raw
        raw == JmEnableStore.SOURCE_ID && jmEnabled -> raw
        opdsProviderIds.contains(raw) -> raw
        else -> DEFAULT_BROWSE_SOURCE_ID
    }

/** First available official Browse source. There is no "all" aggregate. */
val DEFAULT_BROWSE_SOURCE_ID: String = EhentaiEnableStore.SOURCE_ID

/**
 * Browse source chips: enabled official catalogs (E-Hentai, nhentai) plus an
 * option per configured OPDS provider — OPDS catalogs are first-class Browse
 * sources now. Cloud Collection stays omitted even when [cloudCollectionEnabled]
 * is true — that list lives under Library · Cloud and the flag remains only so
 * existing call sites keep compiling.
 */
fun browseSourceOptions(
    ehentaiEnabled: Boolean = false,
    exhentaiEnabled: Boolean = false,
    nhentaiEnabled: Boolean = false,
    @Suppress("UNUSED_PARAMETER") cloudCollectionEnabled: Boolean = false,
    zaimanhuaEnabled: Boolean = false,
    opdsProviders: List<OpdsProviderConfig> = emptyList(),
    jmEnabled: Boolean = false,
): List<BrowseSourceOption> =
    buildList {
        // Library · Cloud owns collected comics; do not offer a Browse source chip.
        if (ehentaiEnabled) {
            add(
                BrowseSourceOption(
                    EhentaiEnableStore.SOURCE_ID,
                    EhentaiEnableStore.DISPLAY_NAME,
                ),
            )
        }
        // W7: the ExHentai sibling chip — same slot shape, only while the
        // session-gated enable store allows it.
        if (exhentaiEnabled) {
            add(
                BrowseSourceOption(
                    ExhentaiEnableStore.SOURCE_ID,
                    ExhentaiEnableStore.DISPLAY_NAME,
                ),
            )
        }
        if (nhentaiEnabled) {
            add(
                BrowseSourceOption(
                    NhentaiEnableStore.SOURCE_ID,
                    NhentaiEnableStore.DISPLAY_NAME,
                ),
            )
        }
        if (zaimanhuaEnabled) {
            add(BrowseSourceOption(GalleryIdentityV1.ZAIMANHUA, ZAIMANHUA_DISPLAY_NAME))
        }
        if (jmEnabled) {
            add(BrowseSourceOption(JmEnableStore.SOURCE_ID, JmEnableStore.DISPLAY_NAME))
        }
        opdsProviders.forEach { provider ->
            add(BrowseSourceOption(provider.id, provider.displayName))
        }
    }

/**
 * #E31: one label rule for the Level-2 header, the search hint and the source
 * list, so they cannot disagree. Built from every official source's display
 * name regardless of its enable toggle (the header of an open source keeps
 * its name when the toggle is off), then the configured OPDS providers. An
 * unknown id shows itself.
 */
fun browseSourceLabel(
    sourceId: String,
    opdsProviders: List<OpdsProviderConfig> = emptyList(),
    sourceRegistry: ComicSourceRegistry? = null,
): String =
    sourceRegistry?.source(browseSourceNamespace(sourceId))?.descriptor?.displayName ?: buildMap {
        put(EhentaiEnableStore.SOURCE_ID, EhentaiEnableStore.DISPLAY_NAME)
        put(ExhentaiEnableStore.SOURCE_ID, ExhentaiEnableStore.DISPLAY_NAME)
        put(NhentaiEnableStore.SOURCE_ID, NhentaiEnableStore.DISPLAY_NAME)
        put(CloudCollectionSource.SOURCE_ID, CloudCollectionSource.DISPLAY_NAME)
        put(GalleryIdentityV1.ZAIMANHUA, ZAIMANHUA_DISPLAY_NAME)
        put(JmEnableStore.SOURCE_ID, JmEnableStore.DISPLAY_NAME)
        opdsProviders.forEach { put(it.id, it.displayName) }
    }[sourceId] ?: sourceId

/**
 * Browse-shell navigation preferences (which source is selected, list chrome).
 * The filter-shaped fields live in [SourceFilterState], persisted per source —
 * a global copy here could never express "E-Hentai languages differ from
 * nhentai's".
 */
data class BrowsePrefs(
    val sourceId: String = DEFAULT_BROWSE_SOURCE_ID,
    val advancedEnabled: Boolean = false,
) {
    fun summaryChip(sourceLabel: String? = null, languageTags: Set<String> = emptySet()): String =
        buildString {
            append(sourceLabel ?: sourceId)
            if (languageTags.isNotEmpty()) {
                append(" · ")
                append(languageTags.sorted().joinToString("+"))
            }
        }
}
