package com.easemic.app.feature.source

import com.easemic.app.feature.download.SeriesIdentityV2

/** Optional handoff used only when a browse redirect already fetched a detail response. */
interface SerializedDetailSeedSource {
    fun takeDetailSeed(series: SeriesIdentityV2): ChapterListResult?
}
