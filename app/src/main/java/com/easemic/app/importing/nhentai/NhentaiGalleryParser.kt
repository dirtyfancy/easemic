package com.easemic.app.importing.nhentai

import com.easemic.app.importing.galleryLanguage
import org.json.JSONArray
import org.json.JSONObject

class NhentaiGalleryParser(
    private val imageOrigin: String = NhentaiClient.DEFAULT_IMAGE_ORIGIN,
    private val thumbOrigin: String = NhentaiClient.DEFAULT_THUMB_ORIGIN,
) {
    fun parse(bytes: ByteArray): NhentaiGalleryPlan {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val galleryId = root.getLong("id")
        val titleObj = root.optJSONObject("title")
        val title = when {
            titleObj != null -> titleObj.optString("english")
                .ifBlank { titleObj.optString("japanese") }
                .ifBlank { titleObj.optString("pretty") }
            else -> root.optString("english_title")
        }.ifBlank { "nhentai $galleryId" }

        val pages = root.getJSONArray("pages")
        val imageUrls = mutableListOf<String>()
        val previewThumbUrls = mutableListOf<String>()
        for (i in 0 until pages.length()) {
            val page = pages.getJSONObject(i)
            val path = page.getString("path")
            imageUrls += NhentaiListParser.absoluteUrl(imageOrigin, path)
            val thumbPath = page.optString("thumbnail").takeIf { it.isNotBlank() }
            if (thumbPath != null) {
                previewThumbUrls += NhentaiListParser.absoluteUrl(thumbOrigin, thumbPath)
            }
        }
        val tagArrays = partitionTags(root.optJSONArray("tags"))
        return NhentaiGalleryPlan(
            galleryId = galleryId,
            title = title,
            imageUrls = imageUrls,
            pageCount = root.optInt("num_pages").takeIf { it > 0 } ?: imageUrls.size.takeIf { it > 0 },
            uploadDateEpochSeconds = root.optLong("upload_date").takeIf { it > 0L },
            language = galleryLanguage(tagArrays.languages),
            artists = tagArrays.artists,
            groups = tagArrays.groups,
            category = tagArrays.categories.firstOrNull(),
            tags = tagArrays.tags,
            previewThumbUrls = previewThumbUrls,
            favorites = root.optInt("num_favorites").takeIf { it > 0 },
        )
    }

    private data class TagPartitions(
        val languages: List<String>,
        val artists: List<String>,
        val groups: List<String>,
        val categories: List<String>,
        val tags: List<String>,
    )

    private fun partitionTags(tags: JSONArray?): TagPartitions {
        if (tags == null) {
            return TagPartitions(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        val languages = mutableListOf<String>()
        val artists = mutableListOf<String>()
        val groups = mutableListOf<String>()
        val categories = mutableListOf<String>()
        val namespaced = mutableListOf<String>()
        for (i in 0 until tags.length()) {
            val tag = tags.optJSONObject(i) ?: continue
            val name = tag.optString("name").trim()
            val namespace = tag.optString("type").trim()
            if (name.isEmpty() || namespace.isEmpty()) continue
            when (namespace) {
                "artist" -> artists += name
                "group" -> groups += name
                "category" -> categories += name
                else -> {
                    if (namespace == "language") languages += name
                    namespaced += "$namespace:$name"
                }
            }
        }
        return TagPartitions(languages, artists, groups, categories, namespaced)
    }
}
