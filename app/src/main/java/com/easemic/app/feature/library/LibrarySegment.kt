package com.easemic.app.feature.library

/**
 * Library top chips: On device, Collected, Downloads, and Cloud. Task 14
 * (browse-remaining §4) retired the fifth Cloud-trash segment: recently
 * deleted items now live on the Settings → Cloud storage screen.
 */
enum class LibrarySegment {
    ON_DEVICE,
    COLLECTED,
    DOWNLOADS,
    CLOUD,
}

fun isCatalogDownloadUri(uri: String?): Boolean =
    com.easemic.app.feature.download.isCatalogDownloadUri(uri)
