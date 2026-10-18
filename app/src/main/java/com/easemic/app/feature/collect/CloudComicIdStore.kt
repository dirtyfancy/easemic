package com.easemic.app.feature.collect

import com.easemic.app.ui.PreferenceMap
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.serializedSeriesLedgerKey
import java.util.UUID

/** Stable cloudComicId per gallery acquisition or serialized identity key. */
class CloudComicIdStore(
    private val prefs: PreferenceMap,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun getOrCreate(acquisitionUrl: String): String {
        val existing = peek(acquisitionUrl)
        if (existing != null) return existing
        val minted = idFactory().trim()
        require(minted.isNotEmpty()) { "cloudComicId required" }
        prefs.putString(KEY_PREFIX + acquisitionUrl.trim(), minted)
        return minted
    }

    /** Serialized-series IDs are keyed by provider identity, never by URL. */
    fun getOrCreate(identity: SeriesIdentityV2): String =
        getOrCreate(serializedSeriesLedgerKey(identity))

    fun peek(acquisitionUrl: String): String? {
        val key = KEY_PREFIX + acquisitionUrl.trim()
        return prefs.getString(key, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val KEY_PREFIX = "cloud_comic_id_v1:"
    }
}
