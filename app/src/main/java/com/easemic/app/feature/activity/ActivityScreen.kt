package com.easemic.app.feature.activity

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.easemic.app.feature.bridge.ByoCloudIoCopy
import com.easemic.app.feature.browse.BrowseCoverThumb
import com.easemic.app.feature.collect.CloudSyncQueueItem
import com.easemic.app.feature.collect.CloudSyncStage
import com.easemic.app.feature.collect.CollectPreflightStatus
import com.easemic.app.feature.download.DownloadJobState
import com.easemic.app.feature.download.DownloadQueueRow
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.SourceDelistedCopy
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.feature.download.downloadFailureReason
import com.easemic.app.feature.download.downloadRowParked
import com.easemic.app.feature.download.downloadRowWillAutoRevive
import com.easemic.app.feature.download.GALLERY_THEN_CLOUD_SUFFIX
import com.easemic.app.feature.download.galleryActivityLine
import com.easemic.app.feature.download.galleryStageLabel
import com.easemic.app.feature.download.serializedActivitySummaryText
import com.easemic.app.model.RunProblem
import com.easemic.app.model.SourceId
import com.easemic.app.work.BackgroundPauseState
import com.easemic.app.feature.browse.QUIET_SHELF_COVER_HEIGHT_DP
import com.easemic.app.ui.components.PclTextButton
import com.easemic.app.ui.components.QuietIndeterminateProgressBar
import com.easemic.app.ui.components.QuietProgressBar
import com.easemic.app.ui.theme.quietShelfPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * E15 Task 3a: how often the Activity screen re-reads the slow-page counts
 * while any download page is in flight (review 3: 1–5 s). Idle — nothing in
 * flight — schedules no ticker at all.
 */
internal const val SLOW_PAGE_TICK_MS: Long = 2_000L

/**
 * Task 16 (browse-remaining-surfaces §6) copy. English-only during development.
 */
object ActivityCopy {
    val TITLE: String get() = AppStrings.activityTitle
    // A41: "Nothing moving." kept the vibe but said less than the subtitle;
    // "Nothing running." is plain and true when all three feeds are empty.
    val EMPTY_TITLE: String get() = AppStrings.activityEmptyTitle
    // A53: names all three sections that can put a row here — downloads,
    // imports (A52), cloud sync — so an empty screen describes exactly the
    // things it is empty of.
    val EMPTY_SUBTITLE: String get() = AppStrings.activityEmptySubtitle
    val RETRYING_LINE: String get() = AppStrings.activityRetryingLine
    val PAUSED_LINE: String get() = AppStrings.activityPausedLine
    // C43: a transfer pump was denied its foreground service (Android 12+
    // background restart) or stopped by the system while the app was in the
    // background; opening the app re-kicks it. Shown until a pump starts
    // protected again.
    val BACKGROUND_PAUSED_LINE: String get() = AppStrings.activityBackgroundPausedLine
    // A20: a FAILED download the auto-revive sweep will NOT pick up (capped,
    // or local-only intent) — the only way forward is the Retry button.
    val FAILED_LINE: String get() = AppStrings.activityFailedLine
    // A27 (owner bug backlog, ledger 2026-09-05): every sync row while no
    // cloud storage is bound at all -- distinct from FAILED_LINE/RETRYING_LINE
    // because Retry would do nothing here; only binding a bucket helps.
    val UNBOUND_LINE: String get() = AppStrings.activityUnboundLine
    // C37-g: a bucket IS bound, but its OAuth grant was revoked server-side
    // (ByoCloudIoCopy.RECONNECT). Distinct from FAILED_LINE because Retry is
    // useless here — the row only moves again once the user reconnects cloud
    // storage, which AppGraph.onBucketBound then revives it from.
    val RECONNECT_LINE: String get() = AppStrings.activityReconnectLine
    // #C81: the drive/bucket said it is full (507 / quotaLimitReached / storageQuotaExceeded).
    val QUOTA_FULL_LINE: String get() = AppStrings.activityQuotaFullLine

    // Queue diagnostics stay internal. Repair-required inputs explain why replay cannot help.
    fun syncAttentionLine(errorMessage: String?): String =
        com.easemic.app.feature.collect.CloudSyncFailurePolicy.attentionLine(errorMessage) ?: when {
            errorMessage == CollectPreflightStatus.LOCAL_PAGES_INCOMPLETE ||
                errorMessage == CollectPreflightStatus.LOCAL_COPY_NOT_DROPPED -> errorMessage
            CollectPreflightStatus.isPreflightFailure(errorMessage) ->
                tr("Cloud check failed · tap Retry", "云端检查失败 · 点击重试", "雲端檢查失敗 · 點擊重試", "クラウド確認失敗 · 再試行をタップ")
            errorMessage == com.easemic.app.sync.WebDavObjectStore.QUOTA_FULL -> QUOTA_FULL_LINE
            else -> FAILED_LINE
        }

    // A11: manual Retry on FAILED/parked download rows and FAILED sync rows.
    val RETRY: String get() = AppStrings.retry

    // A45: the second attention-row action. Retry moves a stuck row forward;
    // Dismiss is the exit that used to be missing entirely, so a permanently
    // failed row (dead source, deleted gallery, dead credentials) can be
    // cleared instead of sitting in Needs attention forever. Destructive but
    // cheap, so it takes no confirm dialog: it clears the queue row only,
    // never the comic, the downloaded pages, or anything in the bucket.
    val DISMISS: String get() = AppStrings.dismiss

    // A52 (owner bug backlog, ledger 2026-09-04): the failed-import row's
    // action — reopens the SAF picker for that source (the existing
    // reauth path, the same words as FoldersScreen's access-lost repair).
    val CHOOSE_FOLDER_AGAIN: String get() = AppStrings.activityChooseFolderAgain

    fun downloadingHeader(count: Int): String = AppStrings.activityDownloadingHeader(count)
    fun syncingHeader(count: Int): String = AppStrings.activitySyncingHeader(count)
    // A29: a FAILED row (download or sync) that only Retry can move renders
    // here, first, ahead of both ordinary sections — see ActivitySections.kt.
    fun needsAttentionHeader(count: Int): String = AppStrings.activityNeedsAttentionHeader(count)
    fun pageProgress(ready: Int, expected: Int): String = AppStrings.activityPageProgress(ready, expected)
    /** #C22 MEDIUM-1: a download that will hand its pages to the cloud says so. */
    // E15 Task 3a: the literal moved into the one gallery formatter
    // (galleryActivityLine); this alias keeps the copy object self-describing.
    val THEN_CLOUD_SUFFIX = GALLERY_THEN_CLOUD_SUFFIX
    fun downloadLine(ready: Int, expected: Int?, stageLabel: String, cloudBound: Boolean): String =
        galleryActivityLine(ready, expected, stageLabel, cloudBound)

    /** The bare stage word, shared by [syncWaiting] and the row's stage label. */
    val WAITING: String get() = AppStrings.activityWaiting

    /** #C46 D1: every page is up and the tail (thumbnails, manifest) is running. */
    val FINISHING: String get() = AppStrings.activityFinishing

    /**
     * C36: a cloud sync row's counter means a different thing in each running
     * phase — pages fetched, then pages uploaded — and the bare
     * [pageProgress] dropped that distinction, so a comic already on the
     * device read "31 of 31" the instant it was tapped (the FETCH count) with
     * nothing saying not one page had gone to the bucket yet. Naming the
     * phase is what makes the number readable. WAITING/FAILED name no phase
     * on purpose: nothing is running, and a FAILED row's count is the true
     * partial it died on.
     */
    fun syncProgress(
        stage: CloudSyncStage,
        ready: Int,
        expected: Int,
        pausedRemainingSec: Int? = null,
        /** #C46 D1: every page is up and the tail is running (upload stage only). */
        finishing: Boolean = false,
    ): String =
        when (stage) {
            CloudSyncStage.FETCHING -> "${AppStrings.fetching} · ${pageProgress(ready, expected)}"
            CloudSyncStage.UPLOADING -> if (finishing) FINISHING else "${AppStrings.uploading} · ${pageProgress(ready, expected)}"
            CloudSyncStage.WAITING -> syncWaiting(ready, expected, pausedRemainingSec)
            // A FAILED row's number is the true partial it died on; its reason
            // is the amber attention line above it, not this counter.
            CloudSyncStage.FAILED -> pageProgress(ready, expected)
        }

    /**
     * C35-c: what a queued row says while nothing is running. `page 0 of 31`
     * was the whole of it, which reads as a stalled transfer rather than a
     * queue position — the owner reported exactly that ("进度不变化"). Three
     * honest cases:
     *  - sitting out Cloudflare's account-wide 429 window (the only WAITING
     *    state with a known end): name it and count it down;
     *  - nothing banked yet: there is no true number to show, so the word is
     *    the whole message;
     *  - parked mid-flight (interrupted, auto-resume): keep the real count
     *    under the word, because that many pages really are already up.
     */
    fun syncWaiting(ready: Int, expected: Int?, pausedRemainingSec: Int? = null): String = when {
        pausedRemainingSec != null && pausedRemainingSec > 0 ->
            tr("Rate-limited · resumes in ${pausedRemainingSec}s", "受限速 · 将于 ${pausedRemainingSec} 秒后继续", "受限速 · 將於 ${pausedRemainingSec} 秒後繼續", "速度制限中 · ${pausedRemainingSec}秒後に再開")
        ready > 0 && expected != null -> "${AppStrings.waiting} · ${pageProgress(ready, expected)}"
        else -> WAITING
    }

    // A52: import rows fed by scan runs (see ActivityImportRow.kt).
    fun importingHeader(count: Int): String = AppStrings.activityImportingHeader(count)
    fun importProgress(found: Int, added: Int): String =
        tr("$found found · $added added", "发现 $found 部 · 已添加 $added 部", "發現 $found 部 · 已新增 $added 部", "$found 件検出 · $added 件追加")
    fun addedComics(count: Int, folder: String): String {
        val noun = if (count == 1) "comic" else "comics"
        return tr(
            "Added $count $noun from $folder",
            "从 $folder 添加了 $count 部漫画",
            "從 $folder 新增了 $count 部漫畫",
            "$folder から $count 件のマンガを追加しました",
        )
    }

    // A52: the done row's quiet body line under its title; the failed row's
    // amber reason. Grant loss is the common failure (the row's action is
    // the reauth picker); other problems name themselves honestly.
    val IMPORT_DONE_LINE: String get() = AppStrings.activityImportFinished
    fun importProblem(problem: RunProblem): String = when (problem) {
        RunProblem.GRANT_LOST -> tr("access lost · choose the folder again", "目录授权已失效 · 请重新选择文件夹", "目錄授權已失效 · 請重新選擇資料夾", "アクセス権が失われました · フォルダを再選択してください")
        RunProblem.PROVIDER_UNAVAILABLE -> tr("storage did not respond", "存储提供方无响应", "儲存提供方無回應", "ストレージが応答しませんでした")
        RunProblem.SOURCE_CHANGED -> tr("folder changed since the last scan", "文件夹内容在上次扫描后发生变动", "資料夾內容在上次掃描後發生變動", "前回のスキャン以降にフォルダが変更されました")
        RunProblem.UNSAFE_ARCHIVE -> tr("an archive could not be opened safely", "压缩包无法安全打开", "壓縮檔無法安全開啟", "アーカイブを安全に開けませんでした")
        RunProblem.INSUFFICIENT_SPACE -> tr("not enough space to import", "可用空间不足，无法导入", "可用空間不足，無法匯入", "空き容量が不足しているためインポートできません")
        RunProblem.PLATFORM_STOPPED -> tr("the scan was stopped by the system", "扫描被系统终止", "掃描被系統終止", "スキャンがシステムにより停止されました")
        RunProblem.UNKNOWN -> tr("the scan could not finish", "扫描未能完成", "掃描未能完成", "スキャンを完了できませんでした")
    }

    // A52 (finding 4, 2026-09-08 audit): the notice the ImportViewModel
    // already records when reauthorizeSource fails. The Folders screen has
    // always rendered it; Activity's "Choose folder again" is the same
    // action and now surfaces the same sentence here.
    val NOTICE_DISMISS: String get() = AppStrings.dismiss
}

/**
 * Activity tab: the local download queue, the cloud sync queue, and the
 * import/scan feed (A52) in one list. Failed rows get an honest amber
 * "retrying · resumes automatically" line and a throttle-parked download gets
 * "paused · resumes automatically" instead of a frozen "Downloading" bar.
 * Rows are tappable → the item's Detail (import rows → Folders).
 *
 * [nowMs] is passed from the caller (recomposed each transfer-meter tick) so the
 * parked/active distinction stays fresh without an impure clock read here.
 *
 * A52 (owner bug backlog, ledger 2026-09-04): [importRows] feeds the same
 * feed from scan runs — see [ImportActivityTracker]. Live rows render under
 * "Importing · N", done rows under it with "Added N comics from <folder>"
 * (the tracker's one-visit receipt contract handles their lifetime), and
 * failed rows — terminal FAILED or parked PAUSED-with-failure — inside the
 * existing "Needs attention" section with a "Choose folder again" action
 * (the reauth picker path; its failure notice renders at the bottom, see
 * [notice]).
 */
@Composable
fun ActivityScreen(
    downloadRows: List<DownloadQueueRow>,
    syncRows: List<CloudSyncQueueItem>,
    nowMs: Long,
    // A27 (owner bug backlog, ledger 2026-09-05): the same "is a bucket
    // bound" signal the Cloud tab already observes. Defaults to true so
    // every pre-existing call site (and every other test) keeps behaving
    // exactly as before.
    cloudBound: Boolean = true,
    // A52: import/scan rows (ImportActivityTracker output). Defaults to
    // empty so every pre-existing call site and test keeps compiling and
    // behaving exactly as before.
    importRows: List<ImportActivityRow> = emptyList(),
    onOpenDownload: (DownloadQueueRow) -> Unit = {},
    onOpenSync: (CloudSyncQueueItem) -> Unit = {},
    // A11: manual Retry for a FAILED/parked download row and a FAILED sync
    // row respectively. Never invoked for any other row state (WAIT, DOWNLOAD-not-parked,
    // WAITING/FETCHING/UPLOADING sync). #C9: a GONE row renders under
    // Needs attention with Dismiss ONLY — it never gets a Retry button.
    onRetryDownload: (DownloadQueueRow) -> Unit = {},
    onRetrySync: (CloudSyncQueueItem) -> Unit = {},
    // A45: clear a row that Retry can never fix (source gone, gallery deleted,
    // credentials dead). #C9: on a GONE row this is the only action; the
    // caller marks/drops the queue row and nothing else.
    onDismissDownload: (DownloadQueueRow) -> Unit = {},
    onDismissSync: (CloudSyncQueueItem) -> Unit = {},
    // A52: tapping a live/done import row navigates to the Folders screen
    // where the folder row lives (AppRoute.Sources at the app root).
    onOpenImport: (SourceId) -> Unit = {},
    // A52: the failed-import row's "Choose folder again" action — reopens
    // the SAF picker for that source (the existing reauth path).
    onChooseFolderAgain: (SourceId) -> Unit = {},
    // A52 (finding 4, 2026-09-08 audit): the ImportViewModel notice (only
    // the ReauthorizationFailed copy ever lands here in practice — the other
    // notices belong to Folders-only actions). Rendered only when present,
    // mirroring the Folders screen's bottom QuietShelfLine. Defaults keep
    // every pre-existing call site and test compiling.
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
    // C43: non-null when a transfer pump was denied its foreground service or
    // stopped by the system since its last protected start — the honest
    // replacement for a progress bar that silently froze in the background.
    backgroundPause: BackgroundPauseState? = null,
    /** Shared chapter identities from download jobs, the current-bucket ledger and the cloud queue. */
    serializedPopulations: Map<SeriesIdentityV2, SerializedChapterPopulation> = emptyMap(),
    /** Titles keyed by provider namespace and canonical serialized-series id. */
    seriesTitles: Map<Pair<String, String>, String> = emptyMap(),
    /** Opens the matching serialized-series detail. */
    onOpenSeries: (SerializedActivitySummary) -> Unit = {},
    /** Retries only the failed chapter jobs represented by a series row. */
    onRetrySeries: (List<String>) -> Unit = {},
    /** Retries only the failed cloud chapter rows represented by a series row. */
    onRetryCloudSeries: (List<CloudSyncQueueItem>) -> Unit = {},
    /**
     * E15 Task 3a: true while at least one download page is in flight anywhere
     * (feature/download DownloadProgressState). Drives the slow-page ticker
     * below — and nothing else; idle screens schedule no ticker.
     */
    anyPageInFlight: Boolean = false,
    /**
     * E15 Task 3a: re-read on the slow-page ticker; jobId → how many of that
     * job's in-flight pages have been transferring for at least
     * DownloadProgressState.SLOW_PAGE_AFTER_MS. Time alone flips a page
     * slow, so the caller's snapshot is re-read every [SLOW_PAGE_TICK_MS]
     * while [anyPageInFlight] — no other state change is needed for the row
     * to update.
     */
    slowPages: () -> Map<String, Int> = { emptyMap() },
) {
    val palette = quietShelfPalette()
    // E15 Task 3a: a page crossing SLOW_PAGE_AFTER_MS must change its row on
    // the clock alone. While any page is in flight, tick every
    // [SLOW_PAGE_TICK_MS] and re-read the slow counts; when idle, no ticker.
    var slowTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(anyPageInFlight) {
        if (!anyPageInFlight) return@LaunchedEffect
        while (isActive) {
            delay(SLOW_PAGE_TICK_MS)
            slowTick++
        }
    }
    val slowPagesByJobId = remember(slowTick, anyPageInFlight) { slowPages() }
    // Serialized GONE rows have no series-level Dismiss action in 4b, so the
    // section builder deliberately omits them. Do not let one such durable
    // trace suppress the honest empty state while leaving an empty list.
    val renderableDownloadRows = downloadRows.any { row ->
        row.seriesCanonicalId == null || row.chapterId == null || row.state != DownloadJobState.GONE
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("activity-screen"),
        ) {
            Text(ActivityCopy.TITLE, style = MaterialTheme.typography.titleLarge)
            if (!renderableDownloadRows && syncRows.isEmpty() && importRows.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 36.dp)
                        .testTag("activity-empty-state"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        ActivityCopy.EMPTY_TITLE,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.ink,
                    )
                    Text(
                        ActivityCopy.EMPTY_SUBTITLE,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                }
            } else {
                // A29: split into "Needs attention" (only Retry can move it) →
                // "Downloading" → "Syncing to cloud", in that order. See
                // ActivitySections.kt for the partition rule. A52: failed
                // imports join the attention section so the header count stays
                // honest.
                val sections = activitySections(
                    downloadRows = downloadRows,
                    syncRows = syncRows,
                    nowMs = nowMs,
                    cloudBound = cloudBound,
                    serializedPopulations = serializedPopulations,
                    seriesTitles = seriesTitles,
                )
                val attentionImports = importRows.filterIsInstance<ImportActivityRow.Failed>()
                val liveImports = importRows.filterIsInstance<ImportActivityRow.Live>()
                val doneImports = importRows.filterIsInstance<ImportActivityRow.Done>()
                val attentionCount = sections.attentionCount + attentionImports.size
                LazyColumn(
                    modifier = Modifier.padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (backgroundPause != null) {
                        item(key = "background-paused") {
                            Text(
                                ActivityCopy.BACKGROUND_PAUSED_LINE,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.muted,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("activity-background-paused"),
                            )
                        }
                    }
                    if (attentionCount > 0) {
                        item(key = "attention-header") {
                            ActivitySectionHeader(
                                text = ActivityCopy.needsAttentionHeader(attentionCount),
                                testTag = "activity-attention-header",
                            )
                        }
                        items(
                            sections.serializedAttention,
                            key = { "download-series-attention:${it.providerId}:${it.seriesId}" },
                        ) { summary ->
                            ActivitySerializedRow(
                                summary = summary,
                                sectionTag = "download",
                                attention = true,
                                onClick = { onOpenSeries(summary) },
                                onRetry = { onRetrySeries(summary.failedJobIds) },
                            )
                        }
                        items(
                            sections.serializedSyncAttention,
                            key = { "sync-series-attention:${it.providerId}:${it.seriesId}" },
                        ) { summary ->
                            ActivitySerializedRow(
                                summary = summary,
                                sectionTag = "sync",
                                attention = true,
                                onClick = { onOpenSeries(summary) },
                                onRetry = if (cloudBound && summary.retryableCloudRows.isNotEmpty()) {
                                    { onRetryCloudSeries(summary.retryableCloudRows) }
                                } else {
                                    null
                                },
                            )
                        }
                        items(sections.attentionDownloads, key = { "dl-${it.jobId}" }) { row ->
                            ActivityDownloadRow(
                                row,
                                nowMs,
                                slowPages = slowPagesByJobId[row.jobId] ?: 0,
                                onClick = { onOpenDownload(row) },
                                onRetry = { onRetryDownload(row) },
                                onDismiss = { onDismissDownload(row) },
                            )
                        }
                        items(sections.attentionSyncs, key = { "sync-${it.id}" }) { row ->
                            ActivitySyncRow(
                                row,
                                cloudBound = cloudBound,
                                onClick = { onOpenSync(row) },
                                onRetry = { onRetrySync(row) },
                                onDismiss = { onDismissSync(row) },
                            )
                        }
                        items(attentionImports, key = { "import-${it.sourceId.value}" }) { row ->
                            ActivityImportRowFailed(
                                row = row,
                                onClick = { onOpenImport(row.sourceId) },
                                onChooseFolderAgain = { onChooseFolderAgain(row.sourceId) },
                            )
                        }
                    }
                    if (liveImports.isNotEmpty()) {
                        item(key = "importing-header") {
                            ActivitySectionHeader(
                                text = ActivityCopy.importingHeader(liveImports.size),
                                testTag = "activity-importing-header",
                            )
                        }
                        items(liveImports, key = { "import-${it.sourceId.value}" }) { row ->
                            ActivityImportRowLive(
                                row = row,
                                onClick = { onOpenImport(row.sourceId) },
                            )
                        }
                    }
                // A52: done rows render below live imports with no header of
                // their own — the tracker limits their lifetime to one
                // visit, so a header would outlive its content.
                    items(doneImports, key = { "import-done-${it.sourceId.value}" }) { row ->
                        ActivityImportRowDone(
                            row = row,
                            onClick = { onOpenImport(row.sourceId) },
                        )
                    }
                    if (sections.downloading.isNotEmpty() || sections.serializedDownloading.isNotEmpty()) {
                        item(key = "downloading-header") {
                            ActivitySectionHeader(
                                text = ActivityCopy.downloadingHeader(
                                    sections.downloading.size + sections.serializedDownloading.size,
                                ),
                                testTag = "activity-downloading-header",
                            )
                        }
                        items(
                            sections.serializedDownloading,
                            key = { "download-series:${it.providerId}:${it.seriesId}" },
                        ) { summary ->
                            ActivitySerializedRow(
                                summary = summary,
                                sectionTag = "download",
                                attention = false,
                                onClick = { onOpenSeries(summary) },
                            )
                        }
                        items(sections.downloading, key = { "dl-${it.jobId}" }) { row ->
                            ActivityDownloadRow(
                                row,
                                nowMs,
                                slowPages = slowPagesByJobId[row.jobId] ?: 0,
                                onClick = { onOpenDownload(row) },
                                onRetry = { onRetryDownload(row) },
                                onDismiss = { onDismissDownload(row) },
                            )
                        }
                    }
                    if (sections.syncing.isNotEmpty() || sections.serializedSyncing.isNotEmpty()) {
                        item(key = "syncing-header") {
                            ActivitySectionHeader(
                                text = ActivityCopy.syncingHeader(
                                    sections.syncing.size + sections.serializedSyncing.size,
                                ),
                                testTag = "activity-syncing-header",
                            )
                        }
                        items(
                            sections.serializedSyncing,
                            key = { "sync-series:${it.providerId}:${it.seriesId}" },
                        ) { summary ->
                            ActivitySerializedRow(
                                summary = summary,
                                sectionTag = "sync",
                                attention = false,
                                onClick = { onOpenSeries(summary) },
                            )
                        }
                        items(sections.syncing, key = { "sync-${it.id}" }) { row ->
                            ActivitySyncRow(
                                row,
                                cloudBound = cloudBound,
                                onClick = { onOpenSync(row) },
                                onRetry = { onRetrySync(row) },
                                onDismiss = { onDismissSync(row) },
                            )
                        }
                    }
                }
            }
        }
        // A52 (finding 4): the reauthorization-failure notice, rendered only
        // when present — the same bottom-line treatment the Folders screen
        // gives it (QuietShelfLine styling: card surface, polite live region,
        // a Dismiss action). No snackbar infrastructure exists app-wide, so
        // an in-surface line is the lightest honest mirror. The alignment
        // modifier is built here because Modifier.align needs this BoxScope.
        if (notice != null) {
            ActivityNoticeLine(
                text = notice,
                onDismiss = onDismissNotice,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * A52 (finding 4): Activity's local mirror of the Folders screen's bottom
 * notice line — same card/border/typography and polite live region as
 * [QuietShelfLine], without adding a cross-feature dependency for one call
 * site.
 */
@Composable
private fun ActivityNoticeLine(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = quietShelfPalette()
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = palette.card,
        border = BorderStroke(1.dp, palette.line),
        modifier = modifier
            .testTag("activity-notice-line")
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = palette.ink,
                modifier = Modifier.weight(1f),
            )
            PclTextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("activity-notice-dismiss"),
            ) {
                Text(ActivityCopy.NOTICE_DISMISS, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ActivitySectionHeader(text: String, testTag: String) {
    val palette = quietShelfPalette()
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = palette.muted,
        modifier = Modifier
            .padding(top = 6.dp, bottom = 2.dp)
            .testTag(testTag),
    )
}

/**
 * Task C: one Activity row for all active jobs belonging to a serialized
 * series. It deliberately reuses [ActivityRowShell] so serialized and
 * gallery rows keep the same cover, spacing, progress-bar, and nested-action
 * behavior. A serialized series has no dismiss action in this slice.
 */
@Composable
private fun ActivitySerializedRow(
    summary: SerializedActivitySummary,
    sectionTag: String,
    attention: Boolean,
    onClick: () -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    val line = serializedActivitySummaryText(summary, includeTitle = false, attention = attention)
    ActivityRowShell(
        testTag = "activity-series-$sectionTag-${summary.seriesId}",
        coverUrl = summary.coverUrl,
        title = summary.title,
        attention = attention,
        attentionLine = line.takeIf { attention },
        attentionTestTag = "activity-series-$sectionTag-attention-${summary.seriesId}",
        progressText = line,
        progressFraction = summary.totalChapters
            .takeIf { it > 0 }
            ?.let { total -> (summary.savedChapters.toFloat() / total).coerceIn(0f, 1f) },
        onClick = onClick,
        retryTestTag = if (attention) "activity-series-$sectionTag-retry-${summary.seriesId}" else null,
        onRetry = if (attention) onRetry else null,
    )
}

@Composable
private fun ActivityDownloadRow(
    row: DownloadQueueRow,
    nowMs: Long,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit = {},
    /** E15 Task 3a: this job's in-flight pages already slow; 0 appends nothing. */
    slowPages: Int = 0,
) {
    val failed = row.state == DownloadJobState.FAILED
    // C9: the source delisted it — a Dismiss-only trace (owner ruling 2026-09-16).
    val delisted = row.state == DownloadJobState.GONE
    val parked = downloadRowParked(row, nowMs)
    // A20: only promise an auto-resume the sweep will actually perform.
    val willAutoRevive = failed && downloadRowWillAutoRevive(row, nowMs)
    val reason = if (failed) downloadFailureReason(row) else null
    val attentionLine = when {
        delisted -> SourceDelistedCopy.CLAUSE
        // S3 Task 8, owner bug #6: append the short human reason (when one
        // was recorded) so a failed job explains itself instead of just
        // saying "retrying"/"failed" with no clue why.
        failed && willAutoRevive -> reason?.let { "${ActivityCopy.RETRYING_LINE} · $it" }
            ?: ActivityCopy.RETRYING_LINE
        failed -> reason?.let { "${ActivityCopy.FAILED_LINE} · $it" }
            ?: ActivityCopy.FAILED_LINE
        parked -> ActivityCopy.PAUSED_LINE
        else -> null
    }
    val attentionTestTag = when {
        delisted -> "activity-row-delisted"
        failed && !willAutoRevive -> "activity-row-failed"
        parked && !failed -> "activity-row-paused"
        else -> "activity-row-retrying"
    }
    val stageLabel = galleryStageLabel(row.state)
    ActivityRowShell(
        testTag = "activity-download-row-${row.jobId}",
        coverUrl = row.coverUrl,
        title = row.title,
        attention = attentionLine != null,
        attentionLine = attentionLine,
        attentionTestTag = attentionTestTag,
        progressText = galleryActivityLine(
            ready = row.readyPages,
            expected = row.expectedPages,
            stageLabel = stageLabel,
            cloudBound = row.intentCloud && (row.state == DownloadJobState.WAIT || row.state == DownloadJobState.DOWNLOAD),
            slowPages = slowPages,
        ),
        progressFraction = row.expectedPages
            ?.takeIf { it > 0 }
            ?.let { expected -> (row.readyPages.toFloat() / expected).coerceIn(0f, 1f) }
            ?.takeUnless { delisted },
        onClick = onClick,
        retryTestTag = if (delisted) null else "activity-retry-${row.jobId}",
        onRetry = if (delisted) null else onRetry,
        dismissTestTag = "activity-dismiss-${row.jobId}",
        onDismiss = onDismiss,
    )
}

@Composable
private fun ActivitySyncRow(
    row: CloudSyncQueueItem,
    cloudBound: Boolean = true,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit = {},
) {
    val failed = row.stage == CloudSyncStage.FAILED
    // A29: a FAILED row whose auto-resume has permanently given up (attempt
    // cap hit) must say "failed · tap Retry", not the "retrying" line — see
    // syncRowNeedsAttention's KDoc for why a row still inside its backoff
    // window does NOT count as needing attention.
    val needsAttention = failed && syncRowNeedsAttention(row)
    // A27 (owner bug backlog, ledger 2026-09-05): with no cloud storage
    // bound at all, EVERY row (any stage) renders the amber "connect cloud
    // storage" line and gets no Retry button at all -- Retry would do
    // nothing until a bucket is bound, and AppGraph.onBucketBound already
    // revives/re-drives everything the instant one is.
    // C37-g: the dead-grant row. CloudIngestRunner parks it FAILED with
    // ByoCloudIoCopy.RECONNECT and an exhausted retry budget on purpose, so
    // without its own line it rendered "failed · tap Retry" — an instruction
    // that cannot work, since no amount of retrying revives a revoked grant.
    val reconnect = needsAttention && row.errorMessage == ByoCloudIoCopy.RECONNECT
    val attention = !cloudBound || failed
    val attentionLine = when {
        !cloudBound -> ActivityCopy.UNBOUND_LINE
        reconnect -> ActivityCopy.RECONNECT_LINE
        needsAttention -> ActivityCopy.syncAttentionLine(row.errorMessage)
        failed -> ActivityCopy.RETRYING_LINE
        else -> null
    }
    val attentionTestTag = when {
        !cloudBound -> "activity-row-unbound"
        reconnect -> "activity-row-reconnect"
        needsAttention -> "activity-row-failed"
        else -> "activity-row-retrying"
    }
    val stageLabel = when (row.stage) {
        CloudSyncStage.WAITING -> ActivityCopy.WAITING
        CloudSyncStage.FETCHING -> AppStrings.fetching
        CloudSyncStage.UPLOADING -> AppStrings.uploading
        CloudSyncStage.FAILED -> AppStrings.failed
    }
    // C35-c: whole seconds left on the account-wide 429 window CloudIngestRunner
    // stamped on this row. Recomputed on recomposition rather than driven by a
    // 1 s ticker on purpose — the number is a promise that something is
    // scheduled, not a stopwatch, and a per-second recomposition of every sync
    // row would cost far more than it tells the user. An elapsed window reads
    // as no window at all.
    val pausedRemainingSec = row.pausedUntilMs
        ?.minus(System.currentTimeMillis())
        ?.takeIf { it > 0L }
        ?.let { ((it + 999L) / 1_000L).toInt() }
    ActivityRowShell(
        testTag = "activity-sync-row-${row.id}",
        coverUrl = row.coverUrl,
        title = row.title,
        attention = attention,
        attentionLine = attentionLine,
        attentionTestTag = attentionTestTag,
        progressText = when {
            row.preflightPending -> AppStrings.checkingCloud
            // Routed through syncWaiting directly (not syncProgress) so a row
            // with no known page total still gets the pause countdown instead
            // of falling through to the bare stage word.
            row.stage == CloudSyncStage.WAITING ->
                ActivityCopy.syncWaiting(row.readyPages, row.expectedPages, pausedRemainingSec)
            else -> row.expectedPages?.let { expected ->
                ActivityCopy.syncProgress(row.stage, row.readyPages, expected, finishing = row.finishing)
            } ?: stageLabel
        },
        // #C46 D1: while the tail runs there is no page fraction to draw —
        // null renders ActivityRowShell's indeterminate bar.
        progressFraction = if (row.finishing) null else row.expectedPages
            ?.takeIf { it > 0 }
            ?.let { expected -> (row.readyPages.toFloat() / expected).coerceIn(0f, 1f) },
        onClick = onClick,
        // C37-g: same shape as the !cloudBound gate above — a row whose grant
        // is dead keeps Dismiss (see syncRowDismissible) but loses Retry.
        retryTestTag = if (syncRowRetryable(row, cloudBound)) "activity-retry-${row.id}" else null,
        onRetry = if (syncRowRetryable(row, cloudBound)) onRetry else null,
        // A45: gated with Retry. While no bucket is bound every row is amber
        // and no per-row action helps -- offering Dismiss there would invite
        // throwing away a backlog that binding a bucket is about to drain.
        dismissTestTag = if (cloudBound) "activity-dismiss-${row.id}" else null,
        onDismiss = if (cloudBound) onDismiss else null,
    )
}

/**
 * A52: shared row chrome for import rows — the same card/paddings/typography
 * as [ActivityRowShell] minus the cover thumb (a scan run has no comic cover;
 * the folder name is the whole identity). The failed variant swaps the
 * progress line for its amber reason + "Choose folder again"; live rows get
 * an indeterminate progress bar (a scan has no known total yet).
 */
@Composable
private fun ActivityImportRowShell(
    testTag: String,
    title: String,
    bodyText: String,
    attention: Boolean,
    bodyTestTag: String,
    indeterminateProgress: Boolean,
    onClick: () -> Unit,
    // A52: the failed row's repair action — reopens the SAF picker for the
    // source. Nested inside the row's clickable Surface (same rule as
    // Retry/Dismiss) so it consumes its own tap.
    chooseAgainTestTag: String? = null,
    onChooseFolderAgain: (() -> Unit)? = null,
) {
    val palette = quietShelfPalette()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = palette.card,
        border = BorderStroke(1.dp, palette.line),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    bodyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (attention) palette.statusWarn else palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(bodyTestTag),
                )
                if (indeterminateProgress) {
                    QuietIndeterminateProgressBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                        color = palette.statusOk,
                        trackColor = palette.ink.copy(alpha = 0.09f),
                        height = 4.dp,
                    )
                }
            }
            if (attention && chooseAgainTestTag != null && onChooseFolderAgain != null) {
                PclTextButton(
                    onClick = onChooseFolderAgain,
                    modifier = Modifier.testTag(chooseAgainTestTag),
                ) {
                    Text(ActivityCopy.CHOOSE_FOLDER_AGAIN, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun ActivityImportRowLive(row: ImportActivityRow.Live, onClick: () -> Unit) {
    ActivityImportRowShell(
        testTag = "activity-import-row-${row.sourceId.value}",
        title = row.folderName,
        bodyText = ActivityCopy.importProgress(row.discovered, row.committed),
        attention = false,
        bodyTestTag = "activity-import-progress-${row.sourceId.value}",
        indeterminateProgress = true,
        onClick = onClick,
    )
}

@Composable
private fun ActivityImportRowDone(row: ImportActivityRow.Done, onClick: () -> Unit) {
    ActivityImportRowShell(
        // A52 re-audit: distinct tag from the Live/Failed row — a lingering
        // receipt and a newer live run of the same source coexist mid-visit.
        testTag = "activity-import-done-row-${row.sourceId.value}",
        title = ActivityCopy.addedComics(row.committed, row.folderName),
        bodyText = ActivityCopy.IMPORT_DONE_LINE,
        attention = false,
        bodyTestTag = "activity-import-done-${row.sourceId.value}",
        indeterminateProgress = false,
        onClick = onClick,
    )
}

@Composable
private fun ActivityImportRowFailed(
    row: ImportActivityRow.Failed,
    onClick: () -> Unit,
    onChooseFolderAgain: () -> Unit,
) {
    ActivityImportRowShell(
        testTag = "activity-import-row-${row.sourceId.value}",
        title = row.folderName,
        bodyText = ActivityCopy.importProblem(row.problem),
        attention = true,
        bodyTestTag = "activity-import-problem-${row.sourceId.value}",
        indeterminateProgress = false,
        onClick = onClick,
        chooseAgainTestTag = "activity-import-rechoose-${row.sourceId.value}",
        onChooseFolderAgain = onChooseFolderAgain,
    )
}

/**
 * Shared row chrome: tappable cover thumb + title + progress line + bar. An
 * "attention" row (failed, or a throttle-parked download) swaps the progress
 * line for its amber [attentionLine] and never renders an indeterminate
 * (dead-looking) bar — it keeps the deterministic checkpoint bar when pages are
 * known, and no bar at all when they are not.
 */
@Composable
private fun ActivityRowShell(
    testTag: String,
    coverUrl: String?,
    title: String,
    attention: Boolean,
    attentionLine: String?,
    attentionTestTag: String,
    progressText: String,
    progressFraction: Float?,
    onClick: () -> Unit,
    // A11: only rendered while [attention] is true — the same condition that
    // already gates every current attention state (FAILED/parked download,
    // FAILED sync). GONE and any other non-attention row never gets one.
    retryTestTag: String? = null,
    onRetry: (() -> Unit)? = null,
    // A45: rendered under the same [attention] gate as Retry, so the two
    // always appear together: a way forward and a way out.
    dismissTestTag: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val palette = quietShelfPalette()
    val hasAttentionActions = attention && (
        (retryTestTag != null && onRetry != null) ||
            (dismissTestTag != null && onDismiss != null)
        )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = palette.card,
        border = BorderStroke(1.dp, palette.line),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = QUIET_SHELF_COVER_HEIGHT_DP.dp)
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowseCoverThumb(
                coverUrl = coverUrl,
                contentDescription = title,
                placeholderTestTag = "activity-cover-placeholder",
                imageTestTag = "activity-cover-image",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = QUIET_SHELF_COVER_HEIGHT_DP.dp),
                verticalArrangement = if (hasAttentionActions) {
                    Arrangement.spacedBy(4.dp)
                } else {
                    Arrangement.SpaceBetween
                },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (attention && attentionLine != null) {
                        // #C81 + #E116: the attention line owns the full body column width
                        // because Retry + Dismiss live on the bottom footer row, never stealing
                        // horizontal space from the reason text.
                        Text(
                            attentionLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.statusWarn,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(attentionTestTag),
                        )
                    } else {
                        Text(
                            progressText,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (progressFraction != null) {
                        QuietProgressBar(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = if (attention) palette.statusWarn else palette.statusOk,
                            trackColor = palette.ink.copy(alpha = 0.09f),
                            height = 4.dp,
                        )
                    } else if (!attention) {
                        QuietIndeterminateProgressBar(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = palette.statusOk,
                            trackColor = palette.ink.copy(alpha = 0.09f),
                            height = 4.dp,
                        )
                    }
                    if (hasAttentionActions) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // A11: nested inside the row's clickable Surface — tapping it
                            // consumes its own click and never bubbles into [onClick].
                            if (retryTestTag != null && onRetry != null) {
                                PclTextButton(
                                    onClick = onRetry,
                                    modifier = Modifier.testTag(retryTestTag),
                                ) {
                                    Text(ActivityCopy.RETRY, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            // A45: same nesting rule as Retry above -- consumes its own click,
                            // so dismissing never also opens Detail.
                            if (dismissTestTag != null && onDismiss != null) {
                                PclTextButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.testTag(dismissTestTag),
                                ) {
                                    Text(ActivityCopy.DISMISS, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
