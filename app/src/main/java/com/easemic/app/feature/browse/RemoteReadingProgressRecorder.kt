package com.easemic.app.feature.browse

import com.easemic.app.feature.download.SeriesIdentityV2

/**
 * A42 remainder (spec 2026-09-08-a42-remote-progress-design.md D2): the
 * write-side seam between a remote reader's settle callback and
 * [RemoteReadingProgressStore].
 *
 * The app root builds one per opened comic (via [forItem]) and hands it to the
 * reader as `onProgressSettled(ordinal, pageCount)`; a settle writes the
 * store under the comic's [RemoteProgressPolicy] key so the re-open path (the
 * SAME key) can resume. A comic with no recordable key yields a no-op
 * recorder, never a wildcard write.
 */
fun interface RemoteReadingProgressRecorder {
    fun onProgressSettled(ordinal: Int, pageCount: Int)

    companion object {
        fun forChapter(
            series: SeriesIdentityV2,
            chapterId: String,
            store: RemoteReadingProgressStore,
            clock: () -> Long = System::currentTimeMillis,
        ): RemoteReadingProgressRecorder {
            val key = RemoteProgressPolicy.keyForChapter(series, chapterId)
            return RemoteReadingProgressRecorder { ordinal, pageCount ->
                store.record(
                    key = key,
                    ordinal = ordinal,
                    pageCount = pageCount,
                    nowMillis = clock(),
                )
            }
        }

        fun forItem(
            item: BrowseFeedItem,
            store: RemoteReadingProgressStore,
            clock: () -> Long = System::currentTimeMillis,
        ): RemoteReadingProgressRecorder {
            val key = RemoteProgressPolicy.keyFor(item) ?: return RemoteReadingProgressRecorder { _, _ -> }
            return RemoteReadingProgressRecorder { ordinal, pageCount ->
                store.record(
                    key = key,
                    ordinal = ordinal,
                    pageCount = pageCount,
                    nowMillis = clock(),
                )
            }
        }
    }
}
