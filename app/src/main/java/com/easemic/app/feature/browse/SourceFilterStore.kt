package com.easemic.app.feature.browse

import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.providers.JmEnableStore
import com.easemic.app.ui.PreferenceMap

/**
 * The three real nhentai categories (tagged, not the legacy ten-value
 * [BrowseCategory] enum reused from E-Hentai). See `NhentaiQueryMapper` doc
 * for why only these three exist server-side.
 */
enum class NhCategory {
    DOUJINSHI,
    MANGA,
    MISC,
}

/** nhentai's `language:` dimension — single-select only (search has no OR). */
enum class NhLanguage {
    ENGLISH,
    JAPANESE,
    CHINESE,
}

/**
 * nhentai's `sort` URL query param (separate from the `query=` DSL).
 * [RECENT] is the default and maps to no param at all (nhentai's own
 * default ordering already is upload-recency order).
 */
enum class NhSort {
    RECENT,
    POPULAR,
    POPULAR_TODAY,
    POPULAR_WEEK,
    POPULAR_MONTH,
}

/**
 * E-Hentai / ExHentai feed mode (#E113):
 * - [LATEST]: standard `/?...` index/search feed.
 * - [POPULAR]: `/popular` single-page ranking.
 * - [TOP_YESTERDAY] / [TOP_MONTH] / [TOP_YEAR] / [TOP_ALL_TIME]: `/toplist.php?tl=15|13|12|11&p=N`.
 */
enum class EhFeedMode(override val label: String, val toplistCode: Int? = null) : FilterLabel {
    LATEST("Latest"),
    POPULAR("Popular"),
    TOP_YESTERDAY("Top · Yesterday", toplistCode = 15),
    TOP_MONTH("Top · Month", toplistCode = 13),
    TOP_YEAR("Top · Year", toplistCode = 12),
    TOP_ALL_TIME("Top · All-time", toplistCode = 11),
}

/**
 * E-Hentai / ExHentai time-jump (`jump=`) presets (#E113), combinable with
 * `f_search` and `f_cats`.
 */
enum class EhJumpPreset(val param: String, override val label: String) : FilterLabel {
    ONE_DAY("1d", "−1d"),
    THREE_DAYS("3d", "−3d"),
    ONE_WEEK("1w", "−1w"),
    ONE_MONTH("1m", "−1m"),
    SIX_MONTHS("6m", "−6m"),
    ONE_YEAR("1y", "−1y"),
}

/**
 * One source's persisted filter state. Changing E-Hentai's filters must never
 * touch nhentai's, so each source id owns an independent copy of this data.
 *
 * [languageTags] empty means all content languages; [categories] defaults to
 * every BrowseCategory (no category filtering). The searchTitle/searchTags/
 * searchDescription/onlyTorrents switches are source-capability dependent:
 * sources that do not support them simply never read them.
 *
 * The nh* / include/exclude/min/max/uploadedWithinDays fields below are
 * nhentai-specific (A19): they are all optional/empty by default so that
 * E-Hentai and OPDS state — and this data class's `equals`, which gates
 * `BrowseFeedSessionKey` reloads — are unaffected by their mere existence.
 * `NhentaiQueryMapper` only switches onto the nh-field path once at least one
 * of them is away from its default; see its doc for the legacy fallback.
 */
data class SourceFilterState(
    val languageTags: Set<String> = emptySet(),
    val categories: Set<BrowseCategory> = BrowseCategory.entries.toSet(),
    val searchTitle: Boolean = true,
    val searchTags: Boolean = true,
    val searchDescription: Boolean = false,
    val onlyTorrents: Boolean = false,
    val ehFeedMode: EhFeedMode = EhFeedMode.LATEST,
    val ehIncludeTags: List<String> = emptyList(),
    val ehExcludeTags: List<String> = emptyList(),
    val ehMinRating: Int? = null,
    val ehMinPages: Int? = null,
    val ehMaxPages: Int? = null,
    val ehJump: EhJumpPreset? = null,
    val ehShowExpunged: Boolean = false,
    /** Empty means all three nhentai categories (unfiltered on this dimension). */
    val nhCategories: Set<NhCategory> = emptySet(),
    /** Legacy single-select nhentai language field kept for compatibility; see [nhLanguages]. */
    val nhLanguage: NhLanguage? = null,
    /**
     * Multi-select nhentai languages (#E113). Empty or all three selected means
     * unfiltered; 1 selected emits `language:x`; 2 selected emits `-language:<missing>`.
     */
    val nhLanguages: Set<NhLanguage> = if (nhLanguage != null) setOf(nhLanguage) else emptySet(),
    val includeTags: List<String> = emptyList(),
    val excludeTags: List<String> = emptyList(),
    val minPages: Int? = null,
    val maxPages: Int? = null,
    val nhSort: NhSort = NhSort.RECENT,
    val uploadedWithinDays: Int? = null,
    val zaimanhua: ZaimanhuaFilters = ZaimanhuaFilters(),
    val jm: JmFilters = JmFilters(),
)

/** Per-source persisted filter state machine. */
interface SourceFilterStore {
    suspend fun get(sourceId: String): SourceFilterState
    suspend fun put(sourceId: String, state: SourceFilterState)
}

/**
 * [PreferenceMap]-backed [SourceFilterStore] — the same preference storage the
 * app's other stores use (DataStore-backed in the app, fake in JVM tests).
 * Keys are split by source id: `browse_filter.{sourceId}.languageTags` etc.
 */
class PreferenceMapSourceFilterStore(
    private val prefs: PreferenceMap,
) : SourceFilterStore {
    override suspend fun get(sourceId: String): SourceFilterState {
        if (sourceId == GalleryIdentityV1.ZAIMANHUA) return SourceFilterState(zaimanhua = prefs.readZFilters())
        if (sourceId == JmEnableStore.SOURCE_ID) return SourceFilterState(jm = prefs.readJFilters())
        com.easemic.app.feature.providers.SourceKeyMigration
            .migrateLegacySourceFilterState(prefs, sourceId)
        val defaults = SourceFilterState()
        val legacyNhLanguage = readNhLanguage(sourceId, defaults.nhLanguage)
        val nhLanguages = readNhLanguages(sourceId, legacyNhLanguage)
        return SourceFilterState(
            languageTags = readLanguageTags(sourceId, defaults.languageTags),
            categories = prefs.getString(key(sourceId, KEY_CATEGORIES), null)?.toBrowseCategorySet()
                ?: defaults.categories,
            searchTitle = readBooleanFlag(key(sourceId, KEY_SEARCH_TITLE), defaults.searchTitle),
            searchTags = readBooleanFlag(key(sourceId, KEY_SEARCH_TAGS), defaults.searchTags),
            searchDescription = readBooleanFlag(
                key(sourceId, KEY_SEARCH_DESCRIPTION),
                defaults.searchDescription,
            ),
            onlyTorrents = readBooleanFlag(key(sourceId, KEY_ONLY_TORRENTS), defaults.onlyTorrents),
            ehFeedMode = readEhFeedMode(sourceId, defaults.ehFeedMode),
            ehIncludeTags = readTagList(key(sourceId, KEY_EH_INCLUDE_TAGS), defaults.ehIncludeTags),
            ehExcludeTags = readTagList(key(sourceId, KEY_EH_EXCLUDE_TAGS), defaults.ehExcludeTags),
            ehMinRating = readOptionalInt(key(sourceId, KEY_EH_MIN_RATING), defaults.ehMinRating),
            ehMinPages = readOptionalInt(key(sourceId, KEY_EH_MIN_PAGES), defaults.ehMinPages),
            ehMaxPages = readOptionalInt(key(sourceId, KEY_EH_MAX_PAGES), defaults.ehMaxPages),
            ehJump = readEhJump(sourceId, defaults.ehJump),
            ehShowExpunged = readBooleanFlag(key(sourceId, KEY_EH_SHOW_EXPUNGED), defaults.ehShowExpunged),
            nhCategories = readNhCategories(sourceId, defaults.nhCategories),
            nhLanguage = nhLanguages.singleOrNull(),
            nhLanguages = nhLanguages,
            includeTags = readTagList(key(sourceId, KEY_INCLUDE_TAGS), defaults.includeTags),
            excludeTags = readTagList(key(sourceId, KEY_EXCLUDE_TAGS), defaults.excludeTags),
            minPages = readOptionalInt(key(sourceId, KEY_MIN_PAGES), defaults.minPages),
            maxPages = readOptionalInt(key(sourceId, KEY_MAX_PAGES), defaults.maxPages),
            nhSort = readNhSort(sourceId, defaults.nhSort),
            uploadedWithinDays = readOptionalInt(
                key(sourceId, KEY_UPLOADED_WITHIN_DAYS),
                defaults.uploadedWithinDays,
            ),
        )
    }

    override suspend fun put(sourceId: String, state: SourceFilterState) {
        if (sourceId == GalleryIdentityV1.ZAIMANHUA) {
            prefs.writeZFilters(state.zaimanhua)
            return
        }
        if (sourceId == JmEnableStore.SOURCE_ID) {
            prefs.writeJFilters(state.jm)
            return
        }
        prefs.putString(
            key(sourceId, KEY_LANGUAGE_TAGS),
            state.languageTags.mapNotNull { normalizeLanguageTag(it) }.sorted().joinToString(","),
        )
        prefs.putString(
            key(sourceId, KEY_CATEGORIES),
            state.categories.joinToString(separator = ",") { it.name },
        )
        prefs.putString(key(sourceId, KEY_SEARCH_TITLE), state.searchTitle.toStorageValue())
        prefs.putString(key(sourceId, KEY_SEARCH_TAGS), state.searchTags.toStorageValue())
        prefs.putString(key(sourceId, KEY_SEARCH_DESCRIPTION), state.searchDescription.toStorageValue())
        prefs.putString(key(sourceId, KEY_ONLY_TORRENTS), state.onlyTorrents.toStorageValue())
        prefs.putString(key(sourceId, KEY_EH_FEED_MODE), state.ehFeedMode.name)
        prefs.putString(
            key(sourceId, KEY_EH_INCLUDE_TAGS),
            state.ehIncludeTags.joinToString(TAG_LIST_SEPARATOR),
        )
        prefs.putString(
            key(sourceId, KEY_EH_EXCLUDE_TAGS),
            state.ehExcludeTags.joinToString(TAG_LIST_SEPARATOR),
        )
        prefs.putString(key(sourceId, KEY_EH_MIN_RATING), state.ehMinRating?.toString().orEmpty())
        prefs.putString(key(sourceId, KEY_EH_MIN_PAGES), state.ehMinPages?.toString().orEmpty())
        prefs.putString(key(sourceId, KEY_EH_MAX_PAGES), state.ehMaxPages?.toString().orEmpty())
        prefs.putString(key(sourceId, KEY_EH_JUMP), state.ehJump?.name.orEmpty())
        prefs.putString(key(sourceId, KEY_EH_SHOW_EXPUNGED), state.ehShowExpunged.toStorageValue())
        prefs.putString(
            key(sourceId, KEY_NH_CATEGORIES),
            state.nhCategories.joinToString(separator = ",") { it.name },
        )
        val effectiveNhLanguages = when {
            state.nhLanguages.isNotEmpty() -> state.nhLanguages
            state.nhLanguage != null -> setOf(state.nhLanguage)
            else -> emptySet()
        }
        prefs.putString(
            key(sourceId, KEY_NH_LANGUAGES),
            effectiveNhLanguages.joinToString(separator = ",") { it.name },
        )
        prefs.putString(
            key(sourceId, KEY_NH_LANGUAGE),
            effectiveNhLanguages.singleOrNull()?.name.orEmpty(),
        )
        prefs.putString(
            key(sourceId, KEY_INCLUDE_TAGS),
            state.includeTags.joinToString(TAG_LIST_SEPARATOR),
        )
        prefs.putString(
            key(sourceId, KEY_EXCLUDE_TAGS),
            state.excludeTags.joinToString(TAG_LIST_SEPARATOR),
        )
        prefs.putString(key(sourceId, KEY_MIN_PAGES), state.minPages?.toString().orEmpty())
        prefs.putString(key(sourceId, KEY_MAX_PAGES), state.maxPages?.toString().orEmpty())
        prefs.putString(key(sourceId, KEY_NH_SORT), state.nhSort.name)
        prefs.putString(
            key(sourceId, KEY_UPLOADED_WITHIN_DAYS),
            state.uploadedWithinDays?.toString().orEmpty(),
        )
    }

    private fun readLanguageTags(sourceId: String, default: Set<String>): Set<String> {
        val raw = prefs.getString(key(sourceId, KEY_LANGUAGE_TAGS), null) ?: return default
        if (raw.isEmpty()) return emptySet()
        return raw.split(",").mapNotNull { normalizeLanguageTag(it) }.toSet()
    }

    private fun readBooleanFlag(storageKey: String, default: Boolean): Boolean =
        when (prefs.getString(storageKey, null)) {
            TRUE_VALUE -> true
            FALSE_VALUE -> false
            else -> default
        }

    private fun readEhFeedMode(sourceId: String, default: EhFeedMode): EhFeedMode {
        val raw = prefs.getString(key(sourceId, KEY_EH_FEED_MODE), null) ?: return default
        return EhFeedMode.entries.firstOrNull { it.name == raw } ?: default
    }

    private fun readEhJump(sourceId: String, default: EhJumpPreset?): EhJumpPreset? {
        val raw = prefs.getString(key(sourceId, KEY_EH_JUMP), null) ?: return default
        if (raw.isEmpty()) return null
        return EhJumpPreset.entries.firstOrNull { it.name == raw }
    }

    private fun readNhCategories(sourceId: String, default: Set<NhCategory>): Set<NhCategory> {
        val raw = prefs.getString(key(sourceId, KEY_NH_CATEGORIES), null) ?: return default
        if (raw.isEmpty()) return emptySet()
        return raw.split(",").mapNotNull { name -> NhCategory.entries.firstOrNull { it.name == name } }.toSet()
    }

    private fun readNhLanguage(sourceId: String, default: NhLanguage?): NhLanguage? {
        val raw = prefs.getString(key(sourceId, KEY_NH_LANGUAGE), null) ?: return default
        if (raw.isEmpty()) return null
        return NhLanguage.entries.firstOrNull { it.name == raw }
    }

    private fun readNhLanguages(sourceId: String, legacyLanguage: NhLanguage?): Set<NhLanguage> {
        val raw = prefs.getString(key(sourceId, KEY_NH_LANGUAGES), null)
            ?: return if (legacyLanguage != null) setOf(legacyLanguage) else emptySet()
        if (raw.isEmpty()) return emptySet()
        return raw.split(",").mapNotNull { name -> NhLanguage.entries.firstOrNull { it.name == name } }.toSet()
    }

    private fun readTagList(storageKey: String, default: List<String>): List<String> {
        val raw = prefs.getString(storageKey, null) ?: return default
        if (raw.isEmpty()) return emptyList()
        return raw.split(TAG_LIST_SEPARATOR).filter { it.isNotEmpty() }
    }

    private fun readOptionalInt(storageKey: String, default: Int?): Int? {
        val raw = prefs.getString(storageKey, null) ?: return default
        if (raw.isEmpty()) return null
        return raw.toIntOrNull()
    }

    private fun readNhSort(sourceId: String, default: NhSort): NhSort {
        val raw = prefs.getString(key(sourceId, KEY_NH_SORT), null) ?: return default
        return NhSort.entries.firstOrNull { it.name == raw } ?: default
    }

    private fun Boolean.toStorageValue(): String = if (this) TRUE_VALUE else FALSE_VALUE

    private fun String.toBrowseCategorySet(): Set<BrowseCategory> =
        if (isEmpty()) {
            emptySet()
        } else {
            split(",").mapNotNull { name -> BrowseCategory.entries.firstOrNull { it.name == name } }.toSet()
        }

    companion object {
        private const val KEY_LANGUAGE_TAGS = "languageTags"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_SEARCH_TITLE = "searchTitle"
        private const val KEY_SEARCH_TAGS = "searchTags"
        private const val KEY_SEARCH_DESCRIPTION = "searchDescription"
        private const val KEY_ONLY_TORRENTS = "onlyTorrents"
        private const val KEY_EH_FEED_MODE = "ehFeedMode"
        private const val KEY_EH_INCLUDE_TAGS = "ehIncludeTags"
        private const val KEY_EH_EXCLUDE_TAGS = "ehExcludeTags"
        private const val KEY_EH_MIN_RATING = "ehMinRating"
        private const val KEY_EH_MIN_PAGES = "ehMinPages"
        private const val KEY_EH_MAX_PAGES = "ehMaxPages"
        private const val KEY_EH_JUMP = "ehJump"
        private const val KEY_EH_SHOW_EXPUNGED = "ehShowExpunged"
        private const val KEY_NH_CATEGORIES = "nhCategories"
        private const val KEY_NH_LANGUAGE = "nhLanguage"
        private const val KEY_NH_LANGUAGES = "nhLanguages"
        private const val KEY_INCLUDE_TAGS = "includeTags"
        private const val KEY_EXCLUDE_TAGS = "excludeTags"
        private const val KEY_MIN_PAGES = "minPages"
        private const val KEY_MAX_PAGES = "maxPages"
        private const val KEY_NH_SORT = "nhSort"
        private const val KEY_UPLOADED_WITHIN_DAYS = "uploadedWithinDays"
        private const val TRUE_VALUE = "true"
        private const val FALSE_VALUE = "false"

        /**
         * Separator for freeform tag lists ([KEY_INCLUDE_TAGS] / [KEY_EXCLUDE_TAGS]).
         * Unlike [KEY_LANGUAGE_TAGS]'s comma join, these are user-typed tag
         * strings that may themselves legitimately contain a comma (nhentai
         * tags are often multi-word), so a control character that can never
         * appear in typed text is used instead of ",".
         */
        private const val TAG_LIST_SEPARATOR = ""

        internal fun key(sourceId: String, field: String) = "browse_filter.$sourceId.$field"
    }
}
