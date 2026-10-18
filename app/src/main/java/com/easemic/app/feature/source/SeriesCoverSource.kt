package com.easemic.app.feature.source

import java.nio.file.Path

/** Optional source capability for bounded serialized-series cover transfers. */
internal interface SeriesCoverSource {
    suspend fun downloadSeriesCoverToFile(
        locator: String,
        destination: Path,
        maxBytes: Long,
    ): Long
}
