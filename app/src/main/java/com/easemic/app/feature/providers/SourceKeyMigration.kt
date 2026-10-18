package com.easemic.app.feature.providers

import com.easemic.app.ui.PreferenceMap

/**
 * One-time migration from the retired internal code names to the real source names (`ehentai`, `nhentai`) in the
 * app's private preferences file.
 *
 * Each migrated pair is dual-read, one-time-write: the first read after an
 * upgrade sees the legacy value, copies it forward under the new key, and
 * every later read comes from the new key. The legacy keys are deliberately
 * never deleted — they are this release's only evidence that an older
 * install existed, and re-reading them after the new key exists would
 * resurrect stale values the user already overwrote (dual-read only ever
 * wins when the new key is absent).
 *
 * `migrate(prefs)` covers the static set of legacy keys (enable flags,
 * session cookies). Browse's persisted source selection and per-source
 * filter keys are value-keyed by source id and are migrated lazily by
 * `BrowsePrefsStore` / `PreferenceMapSourceFilterStore` through
 * [migrateLegacySourceIdValue] and [migrateLegacySourceFilterState].
 */
object SourceKeyMigration {

    /** Legacy key → new key for every static (non source-id-templated) key. */
    val LEGACY_TO_CURRENT: Map<String, String> = mapOf(
        LegacyKeys.EHENTAI_ENABLED to EhentaiStoreKeys.EHENTAI_ENABLED_KEY,
        LegacyKeys.EHENTAI_COOKIE to EhentaiStoreKeys.EHENTAI_COOKIE_KEY,
        LegacyKeys.EHENTAI_USE_EXHENTAI to EhentaiStoreKeys.EHENTAI_USE_EXHENTAI_KEY,
        LegacyKeys.NHENTAI_ENABLED to EhentaiStoreKeys.NHENTAI_ENABLED_KEY,
        LegacyKeys.NHENTAI_CF_COOKIE to EhentaiStoreKeys.NHENTAI_CF_COOKIE_KEY,
    )

    /**
     * Reads [currentKey], migrating the legacy value forward once when the
     * new key has never been written. Returns [default] when neither key
     * exists.
     *
     * A per-pair sentinel (`migrated_from:$currentKey`) is written the first
     * time the legacy value is copied forward; once it exists, the legacy key
     * is never consulted again. This closes two holes the bare
     * "new key present?" check had:
     *  - a user who explicitly clears a value (and any later path that
     *    removes the new key) no longer gets the legacy value resurrected;
     *  - concurrent readers on different threads (Settings composition,
     *    CF banner, CF worker) cannot both observe "new key absent" and
     *    stamp a stale legacy value over a user's newer write.
     *
     * The whole read-migrate-write is serialized on this object's monitor,
     * with the new key re-checked inside the lock (double-checked), so a
     * legacy write can never land after the sentinel or after a user write.
     */
    fun readMigrated(prefs: PreferenceMap, currentKey: String, legacyKey: String, default: String?): String? {
        prefs.getString(currentKey, null)?.let { return it }
        if (prefs.getString(sentinelKey(currentKey), null) != null) {
            // Migration finished. The writer writes currentKey strictly before the
            // sentinel under the same monitor, so observing the sentinel guarantees
            // the currentKey write is visible on re-read; re-read it instead of
            // returning default (which would lose a value written just before the
            // sentinel to a concurrent fast-path reader). Absent => no legacy value
            // was migrated, so default is the correct answer.
            return prefs.getString(currentKey, null) ?: default
        }
        synchronized(this) {
            // Re-check inside the lock: another thread may have migrated or
            // the user may have written while we were waiting.
            prefs.getString(currentKey, null)?.let { return it }
            if (prefs.getString(sentinelKey(currentKey), null) != null) return default
            val legacy = prefs.getString(legacyKey, null)
            if (legacy != null) {
                prefs.putString(currentKey, legacy)
            }
            // Sentinel is written whether or not a legacy value existed: the
            // pair is considered migrated exactly once, and "absent" never
            // means "try the legacy key again".
            prefs.putString(sentinelKey(currentKey), SENTINEL_VALUE)
            return legacy ?: default
        }
    }

    /** Migrates every static legacy key pair. Safe to call repeatedly. */
    fun migrate(prefs: PreferenceMap) {
        LEGACY_TO_CURRENT.forEach { (legacy, current) ->
            readMigrated(prefs, current, legacy, default = null)
        }
    }

    internal fun sentinelKey(currentKey: String): String = "migrated_from:$currentKey"

    private const val SENTINEL_VALUE = "1"

    /**
     * Maps a persisted source-id *value* (e.g. the `browse_prefs_source_id`
     * entry) from a retired code name onto the current id. Ids that already
     * use the current names pass through unchanged.
     */
    fun migrateLegacySourceIdValue(raw: String): String = when (raw) {
        LegacySourceIds.EHENTAI -> EhentaiEnableStore.SOURCE_ID
        LegacySourceIds.NHENTAI -> NhentaiEnableStore.SOURCE_ID
        else -> raw
    }

    /**
     * Copies one source's persisted filter state from the legacy id-scoped
     * keys (scoped by the legacy source id) to the current id-scoped keys
     * (`browse_filter.nhentai.*`) when the target has no state yet. Old
     * entries are left in place — only the known legacy pairs are ever read.
     */
    fun migrateLegacySourceFilterState(prefs: PreferenceMap, currentSourceId: String) {
        val templateFields = listOf(
            "languageTags",
            "categories",
            "searchTitle",
            "searchTags",
            "searchDescription",
            "onlyTorrents",
        )
        val legacySourceId = when (currentSourceId) {
            EhentaiEnableStore.SOURCE_ID -> LegacySourceIds.EHENTAI
            NhentaiEnableStore.SOURCE_ID -> LegacySourceIds.NHENTAI
            else -> return
        }
        templateFields.forEach { field ->
            readMigrated(
                prefs,
                currentKey = "browse_filter.$currentSourceId.$field",
                legacyKey = "browse_filter.$legacySourceId.$field",
                default = null,
            )
        }
    }
}

/**
 * Current (post-migration) preference keys, exposed so stores and tests share
 * exactly one definition. The *values* of these constants are the only new
 * keys; legacy strings exist solely inside [LegacyKeys] below.
 */
object EhentaiStoreKeys {
    const val EHENTAI_ENABLED_KEY = "ehentai_enabled_v1"
    const val EHENTAI_COOKIE_KEY = "ehentai_cookie_v1"
    const val EHENTAI_USE_EXHENTAI_KEY = "ehentai_use_exhentai_v1"

    /** Last [ExhentaiAccessVerdict] observed for the stored session. */
    const val EXHENTAI_ACCESS_KEY = "exhentai_access_v1"

    /**
     * User's own enable switch for the ExHentai source. Necessary but not
     * sufficient — [EXHENTAI_ACCESS_KEY] must also read `granted`, so a user
     * cannot switch on a source the server refuses to serve.
     */
    const val EXHENTAI_ENABLED_KEY = "exhentai_enabled_v1"

    const val NHENTAI_ENABLED_KEY = "nhentai_enabled_v1"
    const val NHENTAI_CF_COOKIE_KEY = "nhentai_cf_cookie_v1"
}

/** The only place in `app/src/main` where the retired code names appear. */
internal object LegacyKeys {
    const val EHENTAI_ENABLED = "front_catalog_enabled_v1"
    const val EHENTAI_COOKIE = "front_catalog_cookie_v1"
    const val EHENTAI_USE_EXHENTAI = "front_catalog_use_exhentai_v1"
    const val NHENTAI_ENABLED = "index_catalog_enabled_v1"
    const val NHENTAI_CF_COOKIE = "index_catalog_cf_cookie_v1"
}

internal object LegacySourceIds {
    const val EHENTAI = "front_catalog"
    const val NHENTAI = "index_catalog"
}
