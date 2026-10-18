package com.easemic.app.feature.browse

import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.SeriesDetailSnapshot
import com.easemic.app.feature.source.mergeSeriesSnapshot

/** Saved entries show durable text first and fetch fresh chapters; source entries may use a redirect handoff. */
enum class DetailEntryOrigin { SOURCE, SAVED }

data class SerializedDetailOpenToken(val series: SeriesIdentityV2, val generation: Long)

data class SerializedDetailOpenState(
    val token: SerializedDetailOpenToken,
    val snapshot: SeriesDetailSnapshot?,
    val chapters: ChapterListResult?,
    val loading: Boolean,
    val sourceUnavailable: Boolean = false,
)

fun completeSerializedDetail(
    current: SerializedDetailOpenState,
    token: SerializedDetailOpenToken,
    result: ChapterListResult?,
): SerializedDetailOpenState {
    if (current.token != token) return current
    return if (result == null) {
        current.copy(loading = false, sourceUnavailable = true)
    } else {
        current.copy(
            snapshot = mergeSeriesSnapshot(current.snapshot, result),
            chapters = result,
            loading = false,
            sourceUnavailable = false,
        )
    }
}
