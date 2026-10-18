package com.easemic.app.sync

/**
 * #E48: what the source currently says about one chapter, after this device's
 * chapter reconciliation (Room's reading unit is the truth: an incomplete
 * list never marks a chapter removed).
 */
data class CloudChapterSourceFact(
    val providerChapterId: String,
    val label: String,
    val group: String?,
    val readingOrder: Int,
    val sourceUpdatedAtEpochMs: Long?,
    val sourceState: String,
)

internal const val CLOUD_CHAPTER_SOURCE_REMOVED = "SOURCE_REMOVED"

/**
 * The series summary's chapter descriptors with [facts] applied. Only chapters
 * already in the summary change (uploads add chapters; this never does), and
 * nothing about their stored pages moves. A chapter keeps the time it was
 * first seen removed; one the source lists again loses it.
 */
fun List<CloudSeriesChapterDescriptor>.withSourceFacts(
    facts: List<CloudChapterSourceFact>,
    nowEpochMs: Long,
): List<CloudSeriesChapterDescriptor> {
    val byId = facts.associateBy { it.providerChapterId }
    return map { descriptor ->
        val fact = byId[descriptor.providerChapterId] ?: return@map descriptor
        val removed = fact.sourceState == CLOUD_CHAPTER_SOURCE_REMOVED
        descriptor.copy(
            label = fact.label,
            group = fact.group,
            readingOrder = fact.readingOrder,
            sourceUpdatedAtEpochMs = fact.sourceUpdatedAtEpochMs ?: descriptor.sourceUpdatedAtEpochMs,
            sourceState = fact.sourceState,
            removedAtEpochMs = if (removed) descriptor.removedAtEpochMs ?: nowEpochMs else null,
        )
    }.sortedBy { it.readingOrder }
}
