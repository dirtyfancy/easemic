package com.easemic.app.sync.adfilter

import com.easemic.app.sync.ObjectStore
import com.easemic.app.ui.PreferenceMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * #E118: Grow-only set (CRDT union) of 64-bit dHash fingerprints for known
 * commercial sponsor ad pages.
 *
 * - Stored locally in [PreferenceMap] under [KEY_LOCAL_HASHES] for sub-millisecond
 *   checks.
 * - Synced with the bound cloud bucket at [CLOUD_OBJECT_KEY]
 *   (`meta/ad-fingerprints.json`). Merging local and remote sets is a pure set
 *   union (`local ∪ remote`), so multiple devices sharing a bucket never
 *   conflict and a newly connected phone inherits all previously identified
 *   ad fingerprints immediately.
 */
class AdFingerprintStore(
    private val prefs: PreferenceMap,
    private val maxHammingDistance: Int = PerceptualImageHash.DEFAULT_MAX_HAMMING_DISTANCE,
) {
    private val lock = Any()

    fun readAllHex(): Set<String> = synchronized(lock) {
        val raw = prefs.getString(KEY_LOCAL_HASHES, null) ?: return@synchronized emptySet()
        decodeHexSet(raw)
    }

    fun count(): Int = readAllHex().size

    /**
     * Returns true when [candidateHex] is within [maxHammingDistance] bits of
     * any known sponsor ad fingerprint in the store.
     */
    fun matchesKnownAd(candidateHex: String): Boolean {
        val candidate = PerceptualImageHash.parseHexOrNull(candidateHex) ?: return false
        val known = readAllHex()
        for (hex in known) {
            val target = PerceptualImageHash.parseHexOrNull(hex) ?: continue
            if (PerceptualImageHash.hammingDistance(candidate, target) <= maxHammingDistance) {
                return true
            }
        }
        return false
    }

    /**
     * Adds [newHashesHex] to the local store and returns true if the set grew.
     */
    fun addAll(newHashesHex: Collection<String>): Boolean = synchronized(lock) {
        val valid = newHashesHex.mapNotNull { hex ->
            PerceptualImageHash.parseHexOrNull(hex)?.let(PerceptualImageHash::formatHex)
        }.toSet()
        if (valid.isEmpty()) return@synchronized false
        val existing = readAllHex()
        val merged = existing + valid
        if (merged.size == existing.size) return@synchronized false
        prefs.putString(KEY_LOCAL_HASHES, encodeHexSetToJson(merged))
        true
    }

    /**
     * Pulls `meta/ad-fingerprints.json` from [cloudStore] (if present), unions
     * it with the local set, and persists the union back to [cloudStore] when
     * [extraNewHashes] or local-only hashes expand the remote set.
     *
     * Never throws: cloud fingerprint sync is best-effort acceleration and must
     * never fail a comic upload.
     */
    fun syncWithCloudBestEffort(
        cloudStore: ObjectStore?,
        extraNewHashes: Collection<String> = emptyList(),
    ): Set<String> {
        if (extraNewHashes.isNotEmpty()) {
            addAll(extraNewHashes)
        }
        if (cloudStore == null) return readAllHex()
        val remoteHashes = runCatching {
            val bytes = cloudStore.download(CLOUD_OBJECT_KEY)
            decodeCloudPayload(bytes)
        }.getOrDefault(emptySet())
        if (remoteHashes.isNotEmpty()) {
            addAll(remoteHashes)
        }
        val union = readAllHex()
        if (union.isNotEmpty() && union != remoteHashes) {
            runCatching {
                cloudStore.upload(
                    objectKey = CLOUD_OBJECT_KEY,
                    bytes = encodeCloudPayload(union),
                    mime = ObjectStore.JSON_MIME,
                )
            }
        }
        return union
    }

    companion object {
        const val KEY_LOCAL_HASHES = "ad_fingerprint_dhashes_v1"
        const val CLOUD_OBJECT_KEY = "meta/ad-fingerprints.json"
        private const val FORMAT_VERSION = 1

        fun encodeCloudPayload(hashes: Set<String>): ByteArray {
            val sorted = hashes
                .mapNotNull { PerceptualImageHash.parseHexOrNull(it)?.let(PerceptualImageHash::formatHex) }
                .sorted()
            val obj = JSONObject()
                .put("version", FORMAT_VERSION)
                .put("dhashes", JSONArray(sorted))
            return obj.toString().toByteArray(Charsets.UTF_8)
        }

        fun decodeCloudPayload(bytes: ByteArray): Set<String> {
            if (bytes.isEmpty()) return emptySet()
            return runCatching {
                val text = String(bytes, Charsets.UTF_8).trim()
                if (text.startsWith("[")) {
                    decodeHexSet(text)
                } else {
                    val obj = JSONObject(text)
                    val arr = obj.optJSONArray("dhashes") ?: return@runCatching emptySet()
                    buildSet {
                        for (i in 0 until arr.length()) {
                            val raw = arr.optString(i)
                            val parsed = PerceptualImageHash.parseHexOrNull(raw)
                            if (parsed != null) add(PerceptualImageHash.formatHex(parsed))
                        }
                    }
                }
            }.getOrDefault(emptySet())
        }

        private fun encodeHexSetToJson(hashes: Set<String>): String =
            JSONArray(hashes.sorted()).toString()

        private fun decodeHexSet(rawJson: String): Set<String> = runCatching {
            val arr = JSONArray(rawJson)
            buildSet {
                for (i in 0 until arr.length()) {
                    val parsed = PerceptualImageHash.parseHexOrNull(arr.optString(i))
                    if (parsed != null) add(PerceptualImageHash.formatHex(parsed))
                }
            }
        }.getOrDefault(emptySet())
    }
}
