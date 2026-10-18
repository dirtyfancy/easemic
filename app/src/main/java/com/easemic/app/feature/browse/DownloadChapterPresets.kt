package com.easemic.app.feature.browse

import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.library.ChapterShelfRow

/**
 * #E73/#E104 spec §3 and §6 — the download picker's pure selection math.
 *
 * Nothing here touches Compose, the network or persistence: the sheet feeds it
 * [ChapterRowPresentation] rows and a progress-id set, and gets chapter ids
 * back. The JVM tests pin exactly the formulas the spec states:
 *
 * - selectable = readable at the source ∧ not fee ∧ not sign-in-blocked ∧ not
 *   already fully on this phone;
 * - `All N` / `Unread M` counts are the sizes of those two sets (Unread adds
 *   "no local or remote reading progress");
 * - `Next K` takes the K selectable chapters AFTER the Continue target in
 *   reading order, falling back to the first selectable ones when nothing is
 *   in progress;
 * - Custom applies the same "next K after Continue" rule with its own K,
 *   clamped to 1…N.
 *
 * A preset REPLACES the selection (the functions return the whole new set);
 * cell taps then adjust it (spec §3).
 */
data class DownloadPickerCandidate(
    val chapterId: String,
    val selectable: Boolean,
    /** Local or remote reading progress exists for this chapter (spec §3 "Unread"). */
    val hasProgress: Boolean,
    val groupOrder: Int,
    val readingOrderInGroup: Int,
)

/** Spec §3's `Next 10`: the fixed count the Next chip offers; Custom supplies any other. */
const val DOWNLOAD_PRESET_NEXT_COUNT = 10

/**
 * Spec §3's selectable clause, read off the same presentation facts the cells
 * render: `onlineReadable` already folds `canRead && !isFee`, [ChapterRowPresentation.membersOnly]
 * covers fee/permission blocks, and [ChapterRowPresentation.needsSignIn] the
 * sign-in blocks. A chapter already on this phone ([ChapterRowPresentation.localReadable])
 * is never re-downloadable.
 */
fun downloadPickerSelectable(row: ChapterRowPresentation): Boolean =
    row.onlineReadable && !row.localReadable && !row.membersOnly && !row.needsSignIn

/** Ids with any local or remote reading progress — the same "has read" reading as `serializedDetailReadLabel`. */
fun downloadPickerProgressIds(
    rows: List<ChapterRowPresentation>,
    shelfRows: List<ChapterShelfRow>,
    remoteProgress: Map<String, RemoteReadingProgress>,
    series: SeriesIdentityV2,
): Set<String> = rows.mapNotNullTo(mutableSetOf()) { row ->
    val shelf = shelfRows.firstOrNull { it.sourceChapterId == row.chapterId }
    val remote = remoteProgress[RemoteProgressPolicy.keyForChapter(series, row.chapterId)]
    val read = shelf?.let { it.progressOrdinal > 0 || it.completed || it.lastReadAtMillis > 0 } == true ||
        remote != null
    row.chapterId.takeIf { read }
}

fun downloadPickerCandidates(
    groups: List<ChapterGroupPresentation>,
    progressChapterIds: Set<String>,
): List<DownloadPickerCandidate> = groups.flatMap { group ->
    group.rows.map { row ->
        DownloadPickerCandidate(
            chapterId = row.chapterId,
            selectable = downloadPickerSelectable(row),
            hasProgress = row.chapterId in progressChapterIds,
            groupOrder = row.groupOrder,
            readingOrderInGroup = row.readingOrderInGroup,
        )
    }
}

/** Reading order = group order, then reading order inside the group. */
private fun List<DownloadPickerCandidate>.inReadingOrder(): List<DownloadPickerCandidate> =
    sortedWith(compareBy({ it.groupOrder }, { it.readingOrderInGroup }))

/** `All N` — every selectable chapter, in reading order. */
fun downloadPickerAllIds(candidates: List<DownloadPickerCandidate>): List<String> =
    candidates.inReadingOrder().filter { it.selectable }.map { it.chapterId }

/** `Unread M` — selectable ∧ no local or remote reading progress. */
fun downloadPickerUnreadIds(candidates: List<DownloadPickerCandidate>): List<String> =
    candidates.inReadingOrder().filter { it.selectable && !it.hasProgress }.map { it.chapterId }

/**
 * `Next 10` / Custom: the [count] selectable chapters after the Continue
 * target in reading order. With no Continue target ("nothing is in progress")
 * — or a target this list no longer carries — the first selectable chapters
 * are the fallback the spec names.
 */
fun downloadPickerNextIds(
    candidates: List<DownloadPickerCandidate>,
    continueChapterId: String?,
    count: Int,
): List<String> {
    if (count <= 0) return emptyList()
    val ordered = candidates.inReadingOrder()
    if (continueChapterId == null) {
        return ordered.filter { it.selectable }.take(count).map { it.chapterId }
    }
    val targetIndex = ordered.indexOfFirst { it.chapterId == continueChapterId }
    if (targetIndex < 0) {
        return ordered.filter { it.selectable }.take(count).map { it.chapterId }
    }
    return ordered.drop(targetIndex + 1).filter { it.selectable }.take(count).map { it.chapterId }
}

/** Custom's editable field range: 1…N (spec §3). N = selectable count. */
fun downloadPickerClampCustom(count: Int, selectableCount: Int): Int =
    count.coerceIn(1, maxOf(1, selectableCount))
