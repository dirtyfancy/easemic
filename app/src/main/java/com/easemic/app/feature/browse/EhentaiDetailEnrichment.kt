package com.easemic.app.feature.browse

import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.importing.ehentai.EhentaiGalleryPlan

/**
 * Maps a fetched E-Hentai gallery plan onto a Browse detail row.
 */
object EhentaiDetailEnrichment {
    fun apply(
        item: BrowseFeedItem,
        plan: EhentaiGalleryPlan,
        previewUrls: List<String> = emptyList(),
    ): BrowseFeedItem {
        val pageCount = plan.declaredPageCount
            ?: plan.pageViewPaths.size.takeIf { it > 0 }
            ?: plan.previewThumbs.size.takeIf { it > 0 }
        return item.copy(
            title = plan.title.ifBlank { item.title },
            pageCount = pageCount ?: item.pageCount,
            language = plan.language ?: item.language,
            artists = plan.artists.ifEmpty { item.artists },
            groups = plan.groups.ifEmpty { item.groups },
            category = plan.category ?: item.category,
            tags = plan.tags.ifEmpty { item.tags },
            uploadDateEpochSeconds = plan.postedEpochSeconds,
            rating = plan.rating,
            previewUrls = previewUrls.ifEmpty { item.previewUrls },
            secondaryLine = listOfNotNull(
                EhentaiEnableStore.DISPLAY_NAME,
                (pageCount ?: item.pageCount)?.let { "$it pages" },
                (plan.language ?: item.language)?.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
        )
    }
}
