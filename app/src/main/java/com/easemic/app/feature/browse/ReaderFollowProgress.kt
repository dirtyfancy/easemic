package com.easemic.app.feature.browse

import com.easemic.app.feature.download.FollowProgress
import com.easemic.app.feature.reader.ReaderPageLoadPresentation
import com.easemic.app.i18n.tr

/**
 * Owner-approved reader copy for a page resolving through a download lane
 * (#E15 Task 3b, owner 2026-10-04, reader option A). Pure — no Compose, no I/O.
 */
fun readerFollowLabel(progress: FollowProgress): String {
    val expectedBytes = progress.expectedBytes
    if (expectedBytes == null || expectedBytes <= 0L) {
        return tr("Downloading…", "正在下载…", "正在下載…", "ダウンロード中…")
    }
    // 100% only shows once the page is actually ready to render.
    val percent = ((progress.bytes * 100L) / expectedBytes).coerceIn(0L, 99L)
    return tr(
        "Downloading · $percent%",
        "正在下载 · $percent%",
        "正在下載 · $percent%",
        "ダウンロード中 · $percent%",
    )
}

/**
 * The follow overlay: the determinate ring when the size is known, the
 * indeterminate ring otherwise; the words go in the existing label slot.
 */
fun readerFollowPresentation(progress: FollowProgress): ReaderPageLoadPresentation {
    val expectedBytes = progress.expectedBytes
    if (expectedBytes == null || expectedBytes <= 0L) {
        return ReaderPageLoadPresentation(
            visible = true,
            determinate = false,
            label = readerFollowLabel(progress),
        )
    }
    return ReaderPageLoadPresentation(
        visible = true,
        determinate = true,
        fraction = (progress.bytes.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 0.99f),
        label = readerFollowLabel(progress),
    )
}
