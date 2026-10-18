package com.easemic.app.feature.library

import com.easemic.app.feature.source.ChapterListCompleteness
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.ChapterSummary

/** The durable fields needed to match an incoming chapter to a Room reading unit. */
data class ExistingChapterUnit(
    val readingUnitId: Long,
    val sourceChapterId: String,
    val ordinal: Int,
    val label: String,
    val sourceGroup: String?,
    val sourceState: String,
    val sourceUpdatedAtMillis: Long?,
)

/** The row shape the Room transaction writes after a chapter-list response. */
data class PlannedChapterUnit(
    val readingUnitId: Long?,
    val sourceChapterId: String,
    val ordinal: Int,
    val label: String,
    val sourceGroup: String?,
    val sourceState: String,
    val sourceUpdatedAtMillis: Long?,
    val readable: Boolean,
)

/** Stable-ID reconciliation output. [units] contains existing and newly discovered rows. */
data class ChapterReconciliationPlan(
    val units: List<PlannedChapterUnit>,
    val newChapterIds: Set<String>,
    val removedChapterIds: Set<String>,
)

private const val ACTIVE = "ACTIVE"
private const val SOURCE_PERMISSION = "SOURCE_PERMISSION"
private const val SOURCE_REMOVED = "SOURCE_REMOVED"

/**
 * Plans a chapter-list update without mutating Room or any other state.
 *
 * Provider chapter IDs are the identity. Source order is represented by a
 * non-negative ordinal, ordered by group order and then oldest-first order in
 * each group. A COMPLETE or EMPTY_CONFIRMED list removes an omitted unit. An incomplete response never removes an omitted unit.
 */
fun planChapterReconciliation(
    existing: List<ExistingChapterUnit>,
    incoming: ChapterListResult,
): ChapterReconciliationPlan {
    requireUnique(existing.map { it.sourceChapterId }, "existing sourceChapterId")
    val incomingIds = incoming.chapters.map { it.identity.canonicalChapterId }
    requireUnique(incomingIds, "incoming sourceChapterId")

    val incomingById = incoming.chapters.associateBy { it.identity.canonicalChapterId }
    val existingById = existing.associateBy { it.sourceChapterId }
    val orderedIncoming = incoming.chapters.sortedWith(
        compareBy<ChapterSummary> { it.groupOrder }
            .thenBy { it.readingOrderInGroup },
    )

    val incomingUnits = orderedIncoming.map { chapter ->
        PlannedChapterUnit(
            readingUnitId = existingById[chapter.identity.canonicalChapterId]?.readingUnitId,
            sourceChapterId = chapter.identity.canonicalChapterId,
            ordinal = -1,
            label = chapter.label,
            sourceGroup = chapter.group,
            sourceState = if (chapter.canRead && !chapter.isFee) ACTIVE else SOURCE_PERMISSION,
            sourceUpdatedAtMillis = chapter.sourceUpdatedAtMillis,
            readable = chapter.canRead && !chapter.isFee,
        )
    }

    // Keep source order for returned chapters, then put omitted known units
    // back into their old relative position. This preserves the old ordering
    // context for a chapter that disappeared while making the final ordinals
    // contiguous for Room's unique (itemId, ordinal) index.
    val merged = incomingUnits.toMutableList()
    val removedIds = mutableSetOf<String>()
    var frontInsertionCount = 0
    existing.sortedBy { it.ordinal }
        .filter { it.sourceChapterId !in incomingById }
        .forEach { old ->
            val removed = incoming.completeness == ChapterListCompleteness.COMPLETE ||
                incoming.completeness == ChapterListCompleteness.EMPTY_CONFIRMED
            val sourceState = if (removed) SOURCE_REMOVED else old.sourceState
            if (removed && old.sourceState != SOURCE_REMOVED) removedIds += old.sourceChapterId
            val unit = PlannedChapterUnit(
                readingUnitId = old.readingUnitId,
                sourceChapterId = old.sourceChapterId,
                ordinal = -1,
                label = old.label,
                sourceGroup = old.sourceGroup,
                sourceState = sourceState,
                sourceUpdatedAtMillis = old.sourceUpdatedAtMillis,
                readable = false,
            )
            val precedingIds = existing
                .asSequence()
                .filter { it.ordinal < old.ordinal }
                .sortedByDescending { it.ordinal }
                .map { it.sourceChapterId }
            val precedingIndex = precedingIds
                .mapNotNull { id -> merged.indexOfFirst { it.sourceChapterId == id }.takeIf { it >= 0 } }
                .firstOrNull()
            val insertionIndex = if (precedingIndex != null) {
                precedingIndex + 1
            } else {
                val index = frontInsertionCount
                frontInsertionCount++
                index
            }
            merged.add(insertionIndex.coerceAtMost(merged.size), unit)
        }

    val plannedUnits = merged.mapIndexed { ordinal, unit -> unit.copy(ordinal = ordinal) }

    return ChapterReconciliationPlan(
        units = plannedUnits,
        newChapterIds = incomingUnits
            .asSequence()
            .filter { it.readingUnitId == null }
            .map { it.sourceChapterId }
            .toSet(),
        removedChapterIds = removedIds,
    )
}

private fun requireUnique(ids: List<String>, description: String) {
    require(ids.size == ids.toSet().size) {
        "duplicate $description in chapter reconciliation"
    }
}
