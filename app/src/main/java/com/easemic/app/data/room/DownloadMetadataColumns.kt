package com.easemic.app.data.room

import com.easemic.app.feature.download.DownloadCommitMetadata
import org.json.JSONArray

/** The four metadata columns a download FINISH writes (A32 §2). */
data class DownloadMetadataColumns(
    val creators: String,
    val genres: String,
    val tags: String,
    val language: String?,
)

/**
 * A32 §2: what to write for a download commit, given the row's [existing]
 * snapshot.
 *
 * The keep rule is **per field**, not all-or-nothing. A FINISH re-commit reuses
 * the origin identity, and the locator it commits from often knows only some of
 * the facts — a cloud re-download carries the bare tag list with no creators and
 * no category, and an empty locator carries nothing at all. Overwriting a
 * persisted fact with nothing would destroy the only copy of it, so each column
 * falls back to what is already recorded when this commit has nothing to say
 * about it. Nothing is invented: with nothing in hand and nothing recorded, the
 * column stays empty.
 */
fun DownloadCommitMetadata.commitColumns(
    existing: MetadataSnapshotEntity?,
): DownloadMetadataColumns {
    val creators = creators()
    val genres = genres()
    val tags = normalizedTags()
    val language = language?.trim()?.takeIf { it.isNotEmpty() }
    return DownloadMetadataColumns(
        creators = creators.toJsonArray().orKept(creators.isEmpty(), existing?.creators),
        genres = genres.toJsonArray().orKept(genres.isEmpty(), existing?.genres),
        tags = tags.toJsonArray().orKept(tags.isEmpty(), existing?.tags),
        language = language ?: existing?.language,
    )
}

private fun String.orKept(nothingInHand: Boolean, persisted: String?): String =
    if (nothingInHand && persisted != null) persisted else this

private fun List<String>.toJsonArray(): String = JSONArray(this).toString()
