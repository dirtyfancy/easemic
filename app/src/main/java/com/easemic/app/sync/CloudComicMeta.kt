package com.easemic.app.sync

import org.json.JSONArray
import org.json.JSONObject

data class CloudComicMeta(
    val cloudComicId: String,
    val title: String,
    val tags: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
    val category: String? = null,
    val language: String? = null,
    val pageCount: Int,
    val providerId: String,
    val acquisitionRef: String,
    val collectedAtEpochMs: Long,
    val contentRevision: Int = 1,
    /**
     * Pages actually fetched when the source download job sealed via
     * truncation (source vanished mid-fetch) before upload. Null means this
     * comic is complete — [pageCount] is the full gallery. When non-null,
     * [truncatedFetchedPages] == [pageCount]: "this is all there is, source
     * was gone" (spec §1.3 option A — honest truncation, no silent gaps).
     */
    val truncatedFetchedPages: Int? = null,
    /**
     * #E118: Number of trailing commercial sponsor ad pages stripped by the
     * TypeSafe (Jev) extraneous-ads filter before uploading to the cloud.
     * Null or <= 0 means no pages were trimmed.
     */
    val trimmedAdPages: Int? = null,
) {
    companion object {
        fun encode(meta: CloudComicMeta): ByteArray {
            require(meta.cloudComicId.isNotBlank()) { "cloudComicId required" }
            require(meta.pageCount >= 0) { "pageCount must be >= 0" }
            val json = JSONObject()
                .put("cloudComicId", meta.cloudComicId)
                .put("title", meta.title)
                .put("tags", JSONArray(meta.tags))
                .put("artists", JSONArray(meta.artists))
                .put("groups", JSONArray(meta.groups))
                .put("category", meta.category)
                .put("language", meta.language)
                .put("pageCount", meta.pageCount)
                .put("providerId", meta.providerId)
                .put("acquisitionRef", meta.acquisitionRef)
                .put("collectedAtEpochMs", meta.collectedAtEpochMs)
                .put("contentRevision", meta.contentRevision)
                .put("truncatedFetchedPages", meta.truncatedFetchedPages)
                .put("trimmedAdPages", meta.trimmedAdPages?.takeIf { it > 0 })
            return json.toString().toByteArray(Charsets.UTF_8)
        }

        fun decode(bytes: ByteArray): CloudComicMeta {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return CloudComicMeta(
                cloudComicId = json.getString("cloudComicId"),
                title = json.getString("title"),
                tags = json.optJSONArray("tags").toStringList(),
                artists = json.optJSONArray("artists").toStringList(),
                groups = json.optJSONArray("groups").toStringList(),
                category = json.nullableString("category"),
                language = json.nullableString("language"),
                pageCount = json.getInt("pageCount"),
                providerId = json.getString("providerId"),
                acquisitionRef = json.getString("acquisitionRef"),
                collectedAtEpochMs = json.getLong("collectedAtEpochMs"),
                contentRevision = json.optInt("contentRevision", 1),
                truncatedFetchedPages = json.nullableInt("truncatedFetchedPages"),
                trimmedAdPages = json.nullableInt("trimmedAdPages")?.takeIf { it > 0 },
            )
        }

        private fun JSONObject.nullableString(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key).takeIf { it.isNotBlank() }
        }

        private fun JSONObject.nullableInt(key: String): Int? {
            if (!has(key) || isNull(key)) return null
            return optInt(key)
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return buildList {
                for (i in 0 until length()) {
                    val value = optString(i)
                    if (value.isNotBlank()) add(value)
                }
            }
        }
    }
}
