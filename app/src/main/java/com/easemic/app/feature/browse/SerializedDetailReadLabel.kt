package com.easemic.app.feature.browse

import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.library.ChapterShelfRow
import com.easemic.app.feature.reader.ReaderOpenTarget

/**
 * The serialized detail's Read pill. [signInChapterId] is set when the chapter the pill leads to needs a source
 * account this phone is not signed in to: tapping then asks for sign-in instead of opening a reader that cannot load.
 */
data class SerializedContinueText(
    val verb: String,
    val chapter: String?,
    val pageSuffix: String?,
    val signInChapterId: String? = null,
) {
    val text: String get() = listOfNotNull(verb, chapter, pageSuffix?.removePrefix(" · ")).joinToString(" · ")
}

fun serializedContinueText(
    series: SeriesIdentityV2,
    target: ReaderOpenTarget?,
    rows: List<ChapterRowPresentation>,
    shelfRows: List<ChapterShelfRow>,
    remoteProgress: Map<String, RemoteReadingProgress>,
): SerializedContinueText {
    val targetRow = target?.let { rows.firstOrNull { row -> row.chapterId == it.chapterId } }
    val signInChapterId = targetRow?.takeIf { it.needsSignIn }?.chapterId
    val verb = serializedDetailReadLabel(series, target, shelfRows, remoteProgress)
    if (verb == "Read" || target == null) return SerializedContinueText("Read", null, null, signInChapterId)
    val label = targetRow?.label?.takeIf(String::isNotBlank)
        ?: return SerializedContinueText("Continue", null, null, signInChapterId)
    return SerializedContinueText(
        verb = "Continue",
        chapter = label,
        pageSuffix = (target.initialOrdinal + 1).takeIf { it > 1 }?.let { " · page $it" },
        signInChapterId = signInChapterId,
    )
}


/** A readable series with any chapter history continues, including into an unread next chapter. */
fun serializedDetailReadLabel(
    series: SeriesIdentityV2,
    target: ReaderOpenTarget?,
    shelfRows: List<ChapterShelfRow>,
    remoteProgress: Map<String, RemoteReadingProgress>,
): String {
    target ?: return "Read"
    val chapterKeyPrefix = RemoteProgressPolicy.keyForChapter(series, "")
    val hasRead = shelfRows.any {
        it.progressOrdinal > 0 || it.completed || it.lastReadAtMillis > 0
    } || remoteProgress.keys.any { it.startsWith(chapterKeyPrefix) }
    return if (hasRead) "Continue" else "Read"
}
