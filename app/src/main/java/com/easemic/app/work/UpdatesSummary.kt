package com.easemic.app.work

data class SummaryChapter(
    val seriesTitle: String,
    val chapterLabel: String,
    val discoveredAtMillis: Long,
    val membersOnly: Boolean,
    val saved: Boolean,
)

data class UpdatesSummary(
    val title: String,
    val body: String,
)

/**
 * One silent summary for a background run.
 * N counts every other chapter found, members-only included.
 * M counts found readable chapters this run did not finish. Members-only never wait.
 * The named chapter is the newest discovery; ties break by series title, then chapter label.
 * A run that found nothing returns null.
 */
fun updatesSummary(chapters: List<SummaryChapter>): UpdatesSummary? {
    if (chapters.isEmpty()) return null
    val named = chapters.sortedWith(
        compareByDescending<SummaryChapter> { it.discoveredAtMillis }
            .thenBy { it.seriesTitle }
            .thenBy { it.chapterLabel },
    ).first()
    val others = chapters.size - 1
    val waiting = chapters.count { !it.membersOnly && !it.saved }
    val title = if (chapters.any { it.saved }) "New chapters saved" else "New chapters found"
    val body = buildString {
        append(named.seriesTitle)
        append(' ')
        append(named.chapterLabel)
        if (others >= 1) append(" and ").append(others).append(" more")
        if (waiting > 0) append(" · ").append(waiting).append(" waiting")
    }
    return UpdatesSummary(title, body)
}
