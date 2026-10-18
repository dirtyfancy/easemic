package com.easemic.app.feature.browse

import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.ui.library.ShelfBadge
import com.easemic.app.ui.library.ShelfBadgeTintAxis
import com.easemic.app.ui.library.shelfBadgeTintAxis

enum class ChapterCellGlyph { FAILED, DOWNLOADING, LOCAL, CLOUD, LOCK, SIGN_IN }
/** What a screen reader says for a chapter cell glyph. */
fun chapterCellGlyphDescription(glyph: ChapterCellGlyph): String = when (glyph) {
    ChapterCellGlyph.FAILED -> tr("Download failed", "下载失败", "下載失敗", "ダウンロード失敗")
    ChapterCellGlyph.DOWNLOADING -> tr("Downloading", "正在下载", "正在下載", "ダウンロード中")
    ChapterCellGlyph.LOCAL -> AppStrings.onThisPhone
    ChapterCellGlyph.CLOUD -> tr("In cloud", "在云端", "在雲端", "クラウド内")
    ChapterCellGlyph.LOCK -> tr("Members only", "仅限会员", "僅限會員", "会員限定")
    ChapterCellGlyph.SIGN_IN -> tr("Sign in to read", "登录后阅读", "登入後閱讀", "サインインして読む")
}

enum class ChapterCellAction { RETRY, DELETE_SAVED_COPY, READ_ONLINE }

enum class ChapterCellTap { READ, MENU, SIGN_IN }

data class ChapterCellPresentation(
    val glyph: ChapterCellGlyph?,
    val dashed: Boolean,
    val actions: List<ChapterCellAction>,
    val explanation: String?,
    val menuHeader: String,
    val description: String,
    val tap: ChapterCellTap,
    val progressFraction: Float?,
    /**
     * Face text for a chapter the source dropped. The dashed border alone
     * read as a mystery when the source re-uploaded the chapter under a new
     * id and the shelf showed two identical labels; the kept tile says what
     * happened where the label sits.
     */
    val removedLine: String? = null,
)

data class ChapterCellSelection(val selected: Boolean, val enabled: Boolean, val alpha: Float)

/**
 * Selection mode's cell state. #E73 spec §3: an already-on-phone chapter is
 * dimmed (.45, the selection mode's local treatment) and unselectable; a
 * fee/members-only or sign-in-required cell keeps its existing glyphs and
 * hints but is equally unselectable — only [downloadPickerSelectable] rows
 * toggle.
 */
fun chapterCellSelection(row: ChapterRowPresentation, selected: Boolean): ChapterCellSelection =
    when {
        row.localReadable -> ChapterCellSelection(false, false, 0.45f)
        !downloadPickerSelectable(row) -> ChapterCellSelection(false, false, 1f)
        else -> ChapterCellSelection(selected, true, 1f)
    }

fun chapterCellPresentation(row: ChapterRowPresentation): ChapterCellPresentation {
    val saved = row.localReadable || row.cloudReadable
    val glyph = when {
        row.membersOnly && !saved -> ChapterCellGlyph.LOCK
        row.needsSignIn && !saved -> ChapterCellGlyph.SIGN_IN
        row.failed && !row.removedAtSource -> ChapterCellGlyph.FAILED
        // #E103: a stranded cloud upload is the same attention shape as a failed
        // download — dashed tile, retry in the menu — with its own honest state.
        row.cloudUploadFailed && !row.removedAtSource && !row.transferring -> ChapterCellGlyph.FAILED
        row.transferring && !row.removedAtSource -> ChapterCellGlyph.DOWNLOADING
        row.localReadable -> ChapterCellGlyph.LOCAL
        row.cloudReadable -> ChapterCellGlyph.CLOUD
        else -> null
    }
    val blocked = glyph == ChapterCellGlyph.LOCK || glyph == ChapterCellGlyph.SIGN_IN
    val online = row.onlineReadable && !row.removedAtSource && !blocked
    val explanation = when {
        row.removedAtSource -> if (saved) AppStrings.chapterStateText("Removed at source · saved copy kept") else AppStrings.chapterStateText("Removed at source")
        glyph == ChapterCellGlyph.LOCK -> AppStrings.membersOnlyNotSaved
        glyph == ChapterCellGlyph.SIGN_IN -> tr("Sign in to read · not saved", "登录后阅读 · 未保存", "登入後閱讀 · 未儲存", "サインインして読む · 未保存")
        else -> null
    }
    val state = when {
        row.removedAtSource -> if (saved) "Removed at source, saved copy kept" else "Removed at source"
        row.cloudUploadFailed && !row.removedAtSource && !row.transferring -> "Cloud copy failed"
        glyph == ChapterCellGlyph.DOWNLOADING && row.downloadState == DownloadJobState.WAIT -> tr("Waiting to download", "等待下载", "等待下載", "ダウンロード待機中")
        glyph != null -> chapterCellGlyphDescription(glyph)
        online -> tr("Read online", "在线阅读", "線上閱讀", "オンラインで読む")
        else -> tr("Unavailable", "不可用", "無法使用", "利用不可")
    }
    val expected = row.expectedPageCount
    val progress = if (glyph == ChapterCellGlyph.DOWNLOADING &&
        row.downloadState == DownloadJobState.DOWNLOAD && expected != null && expected > 0) {
        (row.readyPageCount.toFloat() / expected).coerceIn(0f, 1f)
    } else null
    return ChapterCellPresentation(
        glyph = glyph,
        dashed = row.removedAtSource && saved,
        actions = buildList {
            if (glyph == ChapterCellGlyph.FAILED && (row.cloudUploadFailed || row.jobId != null)) add(ChapterCellAction.RETRY)
            if (row.localReadable) add(ChapterCellAction.DELETE_SAVED_COPY)
            if (online) add(ChapterCellAction.READ_ONLINE)
        },
        explanation = explanation,
        menuHeader = "${explanation ?: AppStrings.chapterStateText(state)} · ${row.label}",
        description = "${row.label}, ${AppStrings.chapterStateText(state)}",
        tap = when {
            saved -> ChapterCellTap.READ
            glyph == ChapterCellGlyph.LOCK -> ChapterCellTap.MENU
            glyph == ChapterCellGlyph.SIGN_IN -> ChapterCellTap.SIGN_IN
            online -> ChapterCellTap.READ
            else -> ChapterCellTap.MENU
        },
        progressFraction = progress,
        // Tile width fits ~10 latin chars at 11sp; "Removed at source" truncated
        // to "Removed a…". The short form fits, the full story stays in the menu.
        removedLine = if (row.removedAtSource) tr("Removed", "已移除", "已移除", "削除済み") else null,
    )
}

internal fun chapterCellTintAxis(glyph: ChapterCellGlyph): ShelfBadgeTintAxis? = when (glyph) {
    ChapterCellGlyph.FAILED -> ShelfBadgeTintAxis.STATUS_BAD
    ChapterCellGlyph.DOWNLOADING -> shelfBadgeTintAxis(ShelfBadge.DownloadQueued)
    ChapterCellGlyph.LOCAL -> shelfBadgeTintAxis(ShelfBadge.Downloaded)
    ChapterCellGlyph.CLOUD -> shelfBadgeTintAxis(ShelfBadge.CloudStored)
    ChapterCellGlyph.LOCK, ChapterCellGlyph.SIGN_IN -> null
}

fun displayedChapterRows(group: ChapterGroupPresentation, newestFirst: Boolean): List<ChapterRowPresentation> =
    if (newestFirst) group.rows.asReversed() else group.rows

fun collapsedChapterRows(rows: List<ChapterRowPresentation>, expanded: Boolean): List<ChapterRowPresentation> =
    if (!expanded && rows.size > 16) rows.take(16) else rows

fun chapterSortDescription(newestFirst: Boolean): String = if (newestFirst) {
    tr("Sort: newest first. Tap for oldest first.", "排序：最新在前。点击切换为最旧在前。", "排序：最新在前。點擊切換為最舊在前。", "並び順：新しい順。タップで古い順に切り替え")
} else {
    tr("Sort: oldest first. Tap for newest first.", "排序：最旧在前。点击切换为最新在前。", "排序：最舊在前。點擊切換為最新在前。", "並び順：古い順。タップで新しい順に切り替え")
}

fun chapterDisclosureLabel(total: Int, expanded: Boolean): String =
    if (expanded) AppStrings.showFewerChapters else AppStrings.showAllChapters(total)
