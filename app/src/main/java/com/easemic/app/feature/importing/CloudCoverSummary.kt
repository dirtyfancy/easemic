package com.easemic.app.feature.importing

/** The source-series summary identity observed by the current bucket's mirror. */
data class CloudCoverSummary(
    val bucketFingerprint: String,
    val generation: Int,
    val title: String,
    val seriesMetaObjectKey: String,
    val chapterCount: Int = 0,
)
