package com.easemic.app.feature.collect

import com.easemic.app.feature.source.SeriesPublicationStatus

private const val SIX_HOURS_MS = 6L * 60 * 60 * 1000
private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
private const val DAY_MS = 24L * 60 * 60 * 1000

/** Median gap of the distinct dated chapter times, clamped to 1–28 days. Null when fewer than three dates. */
fun expectedIntervalMs(sourceUpdatedAtMillis: List<Long?>): Long? {
    val dates = sourceUpdatedAtMillis.filterNotNull().distinct().sorted()
    if (dates.size < 3) return null
    val gaps = dates.zipWithNext { a, b -> b - a }.filter { it > 0L }.sorted()
    if (gaps.isEmpty()) return null
    val middle = gaps.size / 2
    val median = if (gaps.size % 2 == 1) gaps[middle] else (gaps[middle - 1] + gaps[middle]) / 2
    return median.coerceIn(DAY_MS, 28L * DAY_MS)
}

/**
 * Whether a followed series should be checked at [nowMs].
 *
 * Completed series are weekly. Ongoing and unknown series with at least three
 * dated chapters use the median interval and the grid point S: expectedNext
 * when the last check is still before it, otherwise the first
 * `expectedNext + k·interval` strictly after the last check. Fewer dated
 * chapters stay on the six-hour cadence. A missing or future last check is due.
 */
fun smartRefreshDue(
    nowMs: Long,
    lastCheckMs: Long?,
    status: SeriesPublicationStatus,
    sourceUpdatedAtMillis: List<Long?>,
    forced: Boolean = false,
): Boolean {
    if (forced || lastCheckMs == null || lastCheckMs > nowMs) return true
    return when (status) {
        SeriesPublicationStatus.COMPLETED -> nowMs - lastCheckMs >= WEEK_MS
        SeriesPublicationStatus.ONGOING,
        SeriesPublicationStatus.UNKNOWN,
        -> {
            val interval = expectedIntervalMs(sourceUpdatedAtMillis)
            if (interval == null) {
                nowMs - lastCheckMs >= SIX_HOURS_MS
            } else {
                val newest = sourceUpdatedAtMillis.filterNotNull().maxOrNull() ?: return true
                val expectedNext = newest + interval
                val scheduled = if (lastCheckMs < expectedNext) {
                    expectedNext
                } else {
                    val steps = ((lastCheckMs - expectedNext) / interval) + 1L
                    expectedNext + steps * interval
                }
                nowMs >= scheduled
            }
        }
    }
}
