package com.easemic.app.feature.browse

import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.importing.nhentai.NhentaiGalleryPlan

/**
 * Maps a fetched nhentai gallery plan onto a Browse detail row.
 */
object NhentaiDetailEnrichment {
    fun apply(
        item: BrowseFeedItem,
        plan: NhentaiGalleryPlan,
        previewUrls: List<String> = emptyList(),
    ): BrowseFeedItem {
        val pageCount = plan.pageCount
            ?: plan.imageUrls.size.takeIf { it > 0 }
            ?: plan.previewThumbUrls.size.takeIf { it > 0 }
        return item.copy(
            title = plan.title.ifBlank { item.title },
            pageCount = pageCount ?: item.pageCount,
            language = plan.language ?: item.language,
            artists = plan.artists.ifEmpty { item.artists },
            groups = plan.groups.ifEmpty { item.groups },
            category = plan.category ?: item.category,
            tags = plan.tags.ifEmpty { item.tags },
            uploadDateEpochSeconds = plan.uploadDateEpochSeconds ?: item.uploadDateEpochSeconds,
            favorites = plan.favorites,
            previewUrls = previewUrls.ifEmpty { item.previewUrls },
            secondaryLine = listOfNotNull(
                NhentaiEnableStore.DISPLAY_NAME,
                (pageCount ?: item.pageCount)?.let { "$it pages" },
                (plan.language ?: item.language)?.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
        )
    }
}
