package com.easemic.app.feature.browse

import com.easemic.app.i18n.tr

import com.easemic.app.i18n.AppStrings

import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.reader.ChapterReaderCoordinator
import com.easemic.app.feature.reader.ReaderChapter
import com.easemic.app.feature.reader.ReaderOpenTarget
import com.easemic.app.feature.download.DownloadJob
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.library.ChapterShelfRow
import com.easemic.app.feature.source.ChapterListResult
import com.easemic.app.feature.source.ChapterListCompleteness
import com.easemic.app.feature.source.ChapterSummary

private const val SOURCE_REMOVED = "SOURCE_REMOVED"
private const val SOURCE_PERMISSION = "SOURCE_PERMISSION"
private const val MEMBERS_ONLY_BADGE = "Members only · not saved"

/**
 * The chapter grid's needs-sign-in hint line. The sentence names a provider, so
 * it lives here and not in `feature/detail/ui` (that directory must stay free of
 * provider literals — DetailUiProviderGuardTest); the moved grid renders it
 * verbatim through this constant.
 */
internal val SERIALIZED_SIGN_IN_HINT: String get() = AppStrings.serializedSignInHint

data class ChapterRowPresentation(
    val chapterId: String,
    val readingUnitId: Long?,
    val jobId: String?,
    val label: String,
    val group: String?,
    val groupOrder: Int,
    val readingOrderInGroup: Int,
    val stateText: String?,
    val localReadable: Boolean,
    val onlineReadable: Boolean,
    val cloudReadable: Boolean = false,
    val failed: Boolean = false,
    val transferring: Boolean = false,
    val membersOnly: Boolean = false,
    val removedAtSource: Boolean = false,
    val needsSignIn: Boolean = false,
    val downloadState: DownloadJobState? = null,
    val readyPageCount: Int = 0,
    val expectedPageCount: Int? = null,
    /**
     * #E103: this chapter's cloud upload row is stranded (retry budget spent;
     * nothing re-drives it without a user action). The row says so and offers
     * the cloud retry, instead of the series switch claiming "Uploading" forever.
     */
    val cloudUploadFailed: Boolean = false,
)

data class CloudChapterRow(
    val chapterId: String,
    val label: String,
    val group: String?,
    val groupOrder: Int,
    val readingOrderInGroup: Int,
    val pageCount: Int,
    val sourceState: String = "ACTIVE",
)

data class ChapterGroupPresentation(
    val label: String?,
    val rows: List<ChapterRowPresentation>,
)

/** Supplies the persisted-only projection when a serialized source could not be reached. */
fun serializedChapterResultForDetail(
    fetched: ChapterListResult?,
    sourceUnavailable: Boolean,
): ChapterListResult? = fetched ?: if (sourceUnavailable) {
    ChapterListResult(
        chapters = emptyList(),
        fetchedAtMillis = 0L,
        completeness = ChapterListCompleteness.UNKNOWN,
    )
} else {
    null
}

/** Select from the same source/local/cloud union the detail renders, using the latest persisted progress. */
fun serializedDetailReadButtonTarget(
    series: SeriesIdentityV2,
    result: ChapterListResult?,
    shelfRows: List<ChapterShelfRow>,
    jobs: List<DownloadJob>,
    cloudRows: List<CloudChapterRow>,
    remoteProgress: Map<String, RemoteReadingProgress>,
    signedIn: Boolean = false,
): ReaderOpenTarget? {
    val detailResult = result ?: ChapterListResult(emptyList(), 0L, ChapterListCompleteness.UNKNOWN)
    val shelfById = shelfRows.associateBy { it.sourceChapterId }
    val chapters = serializedChapterGroups(detailResult, shelfRows, jobs, cloudRows, signedIn)
        .flatMapIndexed { groupOrder, group ->
            group.rows.map { row ->
                val shelf = shelfById[row.chapterId]?.takeIf { it.pageCount > 0 }
                val remote = remoteProgress[RemoteProgressPolicy.keyForChapter(series, row.chapterId)]
                val latestRemote = remote?.takeIf { shelf == null || it.updatedAtMillis > shelf.lastReadAtMillis }
                ReaderChapter(
                    chapterId = row.chapterId,
                    groupOrder = groupOrder,
                    readingOrderInGroup = row.readingOrderInGroup,
                    // A chapter a sign-in unlocks is still where Read leads; the pill asks for sign-in first
                    // (SerializedContinueText.signInChapterId), so no reader opens on a chapter that cannot load.
                    canRead = row.localReadable || row.cloudReadable || row.onlineReadable || row.needsSignIn,
                    progressOrdinal = latestRemote?.ordinal ?: shelf?.progressOrdinal ?: 0,
                    completed = latestRemote?.let { it.ordinal == it.pageCount - 1 } ?: shelf?.completed ?: false,
                    lastReadAtMillis = latestRemote?.updatedAtMillis ?: shelf?.lastReadAtMillis ?: 0L,
                )
            }
        }
    return ChapterReaderCoordinator(chapters).readButtonTarget().takeIf { it.chapterId.isNotBlank() }
}

/** The status line for a Read tap with nothing to open. */
fun serializedNothingToReadLine(chaptersLoaded: Boolean, sourceUnavailable: Boolean, seriesEmptyLine: String?): String = when {
    sourceUnavailable -> "Source unavailable · showing saved chapters".let { AppStrings.chapterStateText(it) }
    seriesEmptyLine != null -> seriesEmptyLine
    // Loaded, yet nothing opens (members only, removed at source): never claim the list is still loading.
    chaptersLoaded -> com.easemic.app.i18n.tr("No chapter here can be read.", "这里没有可阅读的章节。", "這裡沒有可閱讀的章節。", "ここには読めるチャプターがありません。")
    else -> com.easemic.app.i18n.tr("Loading chapters…", "正在加载章节…", "正在載入章節…", "チャプターを読み込み中…")
}

/** Keeps the uncollected serialized detail path source-compatible. */
fun serializedChapterGroups(result: ChapterListResult): List<ChapterGroupPresentation> =
    serializedChapterGroups(result, emptyList(), emptyList())

/**
 * #E21 tail 3: the one line a cloud series detail shows in place of its
 * chapter list while the series summary has never hydrated. Only a missing
 * summary with nothing in the local mirror qualifies — mirror rows render as
 * chapters, and a present summary renders normally.
 */
fun seriesDetailEmptyLine(manifestMissing: Boolean, hasMirrorRows: Boolean): String? =
    if (manifestMissing && !hasMirrorRows) {
        com.easemic.app.i18n.tr("Series info isn't available yet. It will appear after the next sync.", "作品信息暂不可用，将在下次同步后显示。", "作品資訊目前無法使用，將在下次同步後顯示。", "作品情報はまだ利用できません。次回の同期後に表示されます。")
    } else {
        null
    }

/** Combines the current source list with persisted local rows and live chapter jobs. */
fun serializedChapterGroups(
    result: ChapterListResult,
    shelfRows: List<ChapterShelfRow>,
    jobs: List<DownloadJob>,
    cloudRows: List<CloudChapterRow> = emptyList(),
    signedIn: Boolean = false,
    cloudStrandedChapterIds: Set<String> = emptySet(),
): List<ChapterGroupPresentation> {
    val sourceById = result.chapters.associateBy { it.identity.canonicalChapterId }
    val shelfById = shelfRows.associateBy { it.sourceChapterId }
    val jobById = jobs.associateBy { it.chapterIdentity }
    val cloudById = cloudRows.associateBy { it.chapterId }
    val ids = buildList {
        addAll(result.chapters.map { it.identity.canonicalChapterId })
        addAll(shelfRows.map { it.sourceChapterId }.filterNot(sourceById::containsKey))
        addAll(cloudRows.map { it.chapterId }.filterNot(sourceById::containsKey).filterNot(shelfById::containsKey))
    }

    val rowsByGroup = ids.map { chapterId ->
        chapterRowPresentation(
            sourceById[chapterId], shelfById[chapterId], jobById[chapterId], cloudById[chapterId], signedIn,
            cloudUploadFailed = chapterId in cloudStrandedChapterIds,
        )
    }.groupBy(ChapterRowPresentation::group)
    val liveGroups = result.chapters.sortedBy { it.groupOrder }.map { it.group }.distinct()
    val persistedGroups = shelfRows.sortedBy { it.ordinal }.map { it.sourceGroup } +
        cloudRows.sortedWith(compareBy<CloudChapterRow> { it.groupOrder }.thenBy { it.readingOrderInGroup }).map { it.group }
    val groups = (liveGroups + persistedGroups + rowsByGroup.keys).distinct()
    return groups.mapIndexedNotNull { groupOrder, group ->
        val rows = rowsByGroup[group] ?: return@mapIndexedNotNull null
        val byId = rows.associateBy { it.chapterId }
        val orderedIds = result.chapters.filter { it.group == group }
            .sortedBy { it.readingOrderInGroup }.map { it.identity.canonicalChapterId }.toMutableList()
        insertMissingChapterIds(orderedIds, shelfRows.filter { it.sourceChapterId in byId }.sortedBy { it.ordinal }.map { it.sourceChapterId })
        insertMissingChapterIds(orderedIds, cloudRows.filter { it.chapterId in byId }.sortedBy { it.readingOrderInGroup }.map { it.chapterId })
        ChapterGroupPresentation(group, orderedIds.mapIndexed { rank, id ->
            checkNotNull(byId[id]).copy(groupOrder = groupOrder, readingOrderInGroup = rank)
        })
    }
}

/** Keep live source order, inserting missing persisted rows after their nearest stable predecessor. */
private fun insertMissingChapterIds(ordered: MutableList<String>, persisted: List<String>) {
    var preceding: String? = null
    var frontCount = 0
    persisted.forEach { id ->
        if (id !in ordered) {
            val anchor = preceding?.let { ordered.indexOf(it) } ?: -1
            val index = if (anchor >= 0) anchor + 1 else frontCount++
            ordered.add(index.coerceAtMost(ordered.size), id)
        }
        preceding = id
    }
}

internal fun chapterRowPresentation(
    source: ChapterSummary?,
    shelf: ChapterShelfRow?,
    job: DownloadJob?,
    cloud: CloudChapterRow? = null,
    signedIn: Boolean = false,
    cloudUploadFailed: Boolean = false,
): ChapterRowPresentation {
    val localReadable = shelf?.pageCount?.let { it > 0 } == true
    val cloudReadable = cloud?.pageCount?.let { it > 0 } == true
    val onlineReadable = source?.let { it.canRead && !it.isFee } == true
    val accountRequired = source?.needsSignIn == true && !signedIn && !localReadable && !cloudReadable
    val permissionBlocked = !localReadable && !cloudReadable && (
        accountRequired || source?.let { !it.canRead || it.isFee || (it.needsSignIn && signedIn) } == true ||
            (source == null && shelf?.sourceState == SOURCE_PERMISSION)
        )
    val removed = shelf?.sourceState == SOURCE_REMOVED || cloud?.sourceState == SOURCE_REMOVED ||
        job?.state == DownloadJobState.GONE
    val stateText = when {
        removed -> if (localReadable || cloudReadable) "Removed at source · saved copy kept" else "Removed at source"
        accountRequired -> "Sign in to read"
        permissionBlocked -> MEMBERS_ONLY_BADGE
        job?.state == DownloadJobState.FAILED -> "Failed · tap to retry"
        job?.state == DownloadJobState.DOWNLOAD -> {
            val expected = job.expectedPageCount?.toString() ?: "?"
            "Downloading ${job.readyPageCount} / $expected"
        }
        job?.state == DownloadJobState.WAIT -> "Waiting"
        cloudUploadFailed -> "Cloud copy failed · tap to retry"
        localReadable && shelf.progressOrdinal in 1 until (shelf.pageCount - 1) ->
            "Saved · p.${shelf.progressOrdinal + 1} / ${shelf.pageCount}"
        localReadable || cloudReadable -> "Saved"
        else -> null
    }
    return ChapterRowPresentation(
        chapterId = source?.identity?.canonicalChapterId
            ?: shelf?.sourceChapterId
            ?: checkNotNull(cloud).chapterId,
        readingUnitId = shelf?.readingUnitId,
        jobId = job?.id,
        label = source?.label ?: shelf?.label ?: checkNotNull(cloud).label,
        group = source?.group ?: shelf?.sourceGroup ?: cloud?.group,
        groupOrder = source?.groupOrder ?: shelf?.ordinal ?: checkNotNull(cloud).groupOrder,
        readingOrderInGroup = source?.readingOrderInGroup ?: shelf?.ordinal ?: checkNotNull(cloud).readingOrderInGroup,
        stateText = stateText,
        localReadable = localReadable,
        onlineReadable = onlineReadable,
        cloudReadable = cloudReadable,
        failed = job?.state == DownloadJobState.FAILED,
        transferring = job?.state == DownloadJobState.WAIT || job?.state == DownloadJobState.DOWNLOAD,
        membersOnly = permissionBlocked && !accountRequired,
        removedAtSource = removed,
        needsSignIn = accountRequired,
        downloadState = job?.state,
        readyPageCount = job?.readyPageCount ?: 0,
        expectedPageCount = job?.expectedPageCount,
        cloudUploadFailed = cloudUploadFailed,
    )
}
