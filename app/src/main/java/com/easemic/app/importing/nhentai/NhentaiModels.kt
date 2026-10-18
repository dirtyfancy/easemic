package com.easemic.app.importing.nhentai

data class NhentaiListPage(
    val entries: List<NhentaiListEntry>,
    val nextPageIndex: Int?,
)

data class NhentaiListEntry(
    val title: String,
    val galleryId: Long,
    val thumbUrl: String?,
    val pageCount: Int? = null,
    /** nhentai num_favorites favourite count; 0/blank → null. */
    val favorites: Int? = null,
)

data class NhentaiGalleryPlan(
    val galleryId: Long,
    val title: String,
    val imageUrls: List<String>,
    val pageCount: Int? = null,
    val uploadDateEpochSeconds: Long? = null,
    val language: String? = null,
    val artists: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
    val category: String? = null,
    /** Every non-artist/group/category tag as namespace:value, in source order. */
    val tags: List<String> = emptyList(),
    /** Per-page thumbnail URLs aligned with [imageUrls] ordinals. */
    val previewThumbUrls: List<String> = emptyList(),
    /** Gallery num_favorites; zero, absent or invalid values are unknown. */
    val favorites: Int? = null,
)
