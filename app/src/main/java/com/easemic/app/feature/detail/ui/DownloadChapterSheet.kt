package com.easemic.app.feature.detail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easemic.app.feature.browse.ChapterGroupPresentation
import com.easemic.app.feature.browse.DOWNLOAD_PRESET_NEXT_COUNT
import com.easemic.app.feature.browse.SerializedChapterCell
import com.easemic.app.feature.browse.chapterDisclosureLabel
import com.easemic.app.feature.browse.chapterTileHeight
import com.easemic.app.feature.browse.collapsedChapterRows
import com.easemic.app.feature.browse.displayedChapterRows
import com.easemic.app.feature.browse.downloadPickerAllIds
import com.easemic.app.feature.browse.downloadPickerCandidates
import com.easemic.app.feature.browse.downloadPickerClampCustom
import com.easemic.app.feature.browse.downloadPickerNextIds
import com.easemic.app.feature.browse.downloadPickerSelectable
import com.easemic.app.feature.browse.downloadPickerUnreadIds
import com.easemic.app.ui.components.PclButton
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.ui.theme.PclSpacing
import com.easemic.app.ui.theme.QuietShelfPalette

/**
 * #E73/#E104 spec §3 — the chapter-selection sheet behind the serialized
 * detail's Download switch. Modal bottom sheet in the SourceFilterSheet /
 * DetailHelperSheet idiom (`palette.surface` container per spec, skipped
 * partially-expanded). Local-only: the confirm hands the selected chapter ids
 * back and the caller enqueues `intentLocal` fetches (spec §4) — nothing here
 * follows the series or touches the cloud.
 *
 * Presets replace the selection; tapping cells then adjusts it. The grid
 * reuses [SerializedChapterCell] in selection mode: chapters already on this
 * phone render dimmed and unselectable, fee/members-only and sign-in-required
 * cells keep their glyphs and stay unselectable (`chapterCellSelection`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadChapterSheet(
    palette: QuietShelfPalette,
    groups: List<ChapterGroupPresentation>,
    /** Chapter ids with local or remote reading progress (spec §3 "Unread"). */
    progressChapterIds: Set<String>,
    /** The Continue target chapter; the anchor Next-K counts from (spec §3). */
    continueChapterId: String?,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = remember(groups, progressChapterIds) {
        downloadPickerCandidates(groups, progressChapterIds)
    }
    val allIds = remember(candidates) { downloadPickerAllIds(candidates) }
    val unreadIds = remember(candidates) { downloadPickerUnreadIds(candidates) }
    var selection by remember(groups, progressChapterIds) { mutableStateOf(emptySet<String>()) }
    var activePreset by remember(groups, progressChapterIds) { mutableStateOf<DownloadPresetChip?>(null) }
    var customOpen by remember(groups, progressChapterIds) { mutableStateOf(false) }
    var customCount by remember(groups, progressChapterIds) {
        mutableIntStateOf(downloadPickerClampCustom(DOWNLOAD_PRESET_NEXT_COUNT, allIds.size))
    }
    fun applyNext(count: Int) {
        selection = downloadPickerNextIds(candidates, continueChapterId, count).toSet()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .padding(horizontal = PclSpacing.Medium)
                .testTag("download-chapter-sheet"),
        ) {
            Text(
                tr("Download to this phone", "下载到本机", "下載到本機", "この端末にダウンロード"),
                style = MaterialTheme.typography.titleMedium,
                color = palette.ink,
            )
            Text(
                tr("Saved chapters stay on this phone · not uploaded", "已保存的章节会留在本机 · 不会上传", "已儲存的章節會留在本機 · 不會上傳", "保存したチャプターは端末に残ります · アップロードされません"),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = PclSpacing.Small, bottom = PclSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PresetChip(tr("All ${allIds.size}", "全部 ${allIds.size}", "全部 ${allIds.size}", "すべて ${allIds.size}"), selected = activePreset == DownloadPresetChip.ALL,
                    tag = "download-preset-all") {
                    activePreset = DownloadPresetChip.ALL
                    selection = allIds.toSet()
                }
                PresetChip(tr("Unread ${unreadIds.size}", "未读 ${unreadIds.size}", "未讀 ${unreadIds.size}", "未読 ${unreadIds.size}"), selected = activePreset == DownloadPresetChip.UNREAD,
                    tag = "download-preset-unread") {
                    activePreset = DownloadPresetChip.UNREAD
                    selection = unreadIds.toSet()
                }
                PresetChip(tr("Next $DOWNLOAD_PRESET_NEXT_COUNT", "接下来 $DOWNLOAD_PRESET_NEXT_COUNT", "接下來 $DOWNLOAD_PRESET_NEXT_COUNT", "次の $DOWNLOAD_PRESET_NEXT_COUNT"), selected = activePreset == DownloadPresetChip.NEXT,
                    tag = "download-preset-next") {
                    activePreset = DownloadPresetChip.NEXT
                    applyNext(DOWNLOAD_PRESET_NEXT_COUNT)
                }
                PresetChip(tr("Custom…", "自定义…", "自訂…", "カスタム…"), selected = customOpen, tag = "download-preset-custom") {
                    activePreset = DownloadPresetChip.CUSTOM
                    customOpen = !customOpen
                    if (customOpen) applyNext(customCount)
                }
            }
            if (customOpen) {
                CustomCountStepper(
                    count = customCount,
                    selectableCount = allIds.size,
                    palette = palette,
                    onChange = { value ->
                        customCount = downloadPickerClampCustom(value, allIds.size)
                        applyNext(customCount)
                    },
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                groups.forEachIndexed { index, group ->
                    DownloadPickerGroup(
                        group = group,
                        groupIndex = index,
                        palette = palette,
                        selection = selection,
                        onToggle = { chapterId ->
                            selection = if (chapterId in selection) selection - chapterId else selection + chapterId
                            activePreset = null
                        },
                        onSelectAll = { ids ->
                            selection = selection + ids
                            activePreset = null
                        },
                    )
                }
            }
            HorizontalDivider(thickness = .5.dp, color = palette.line)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = PclSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("${selection.size} selected", "已选择 ${selection.size} 项", "已選取 ${selection.size} 項", "${selection.size} 件を選択中"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.ink,
                    modifier = Modifier.testTag("download-selected-count"),
                )
                Spacer(Modifier.weight(1f))
                PclButton(
                    onClick = {
                        val ordered = candidates
                            .filter { it.chapterId in selection }
                            .sortedWith(compareBy({ it.groupOrder }, { it.readingOrderInGroup }))
                            .map { it.chapterId }
                        onConfirm(ordered)
                    },
                    enabled = selection.isNotEmpty(),
                    modifier = Modifier.testTag("download-confirm"),
                ) {
                    Text(if (selection.isEmpty()) tr("Download", "下载", "下載", "ダウンロード") else tr("Download ${selection.size}", "下载 ${selection.size}", "下載 ${selection.size}", "${selection.size} 件をダウンロード"))
                }
            }
        }
    }
}

private enum class DownloadPresetChip { ALL, UNREAD, NEXT, CUSTOM }

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.testTag(tag),
    )
}

/**
 * Mihon's DownloadCustomChaptersDialog shape: −10 −1 value ＋1 ＋10 with an
 * editable field, range 1…N where N is the selectable count (spec §3).
 */
@Composable
private fun CustomCountStepper(
    count: Int,
    selectableCount: Int,
    palette: QuietShelfPalette,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("download-custom-stepper")
            .padding(bottom = PclSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StepperButton("−10", "download-custom-minus10", enabled = selectableCount > 0) { onChange(count - 10) }
        StepperButton("−1", "download-custom-minus1", enabled = selectableCount > 0) { onChange(count - 1) }
        BasicTextField(
            value = count.toString(),
            onValueChange = { text ->
                text.filter(Char::isDigit).toIntOrNull()?.let(onChange)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.ink),
            modifier = Modifier
                .width(48.dp)
                .testTag("download-custom-value")
                .semantics { contentDescription = tr("Chapters to download", "要下载的章节", "要下載的章節", "ダウンロードするチャプター") },
        )
        StepperButton("＋1", "download-custom-plus1", enabled = selectableCount > 0) { onChange(count + 1) }
        StepperButton("＋10", "download-custom-plus10", enabled = selectableCount > 0) { onChange(count + 10) }
        Text(
            tr("next $count after Continue", "从“继续”开始的接下来 $count 个", "從「繼續」開始的接下來 $count 個", "「続き」から次の $count 件"),
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StepperButton(
    label: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag(tag),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
    ) {
        Text(label, fontSize = 13.sp)
    }
}

/**
 * One chapter group: label + count + the spec's per-group `Select all` link
 * (additive — it selects every selectable chapter in the group and leaves the
 * rest of the selection alone), then the same four-column cells and the same
 * 16-cell disclosure as the detail grid.
 */
@Composable
private fun DownloadPickerGroup(
    group: ChapterGroupPresentation,
    groupIndex: Int,
    palette: QuietShelfPalette,
    selection: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: (List<String>) -> Unit,
) {
    val secondary = palette.ink.copy(alpha = .68f)
    var expanded by remember(group) { mutableStateOf(false) }
    val displayRows = displayedChapterRows(group, newestFirst = false)
    val rows = collapsedChapterRows(displayRows, expanded)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!group.label.isNullOrBlank()) {
                    Text(
                        group.label, color = palette.ink, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    group.rows.size.toString(), color = secondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = {
                    onSelectAll(group.rows.filter { downloadPickerSelectable(it) }.map { it.chapterId })
                },
                modifier = Modifier.testTag("download-select-all-$groupIndex"),
            ) {
                Text(AppStrings.selectAll, fontSize = 12.sp)
            }
        }
        rows.chunked(4).forEach { cells ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cells.forEach { cell ->
                    val selected = cell.chapterId in selection
                    SerializedChapterCell(
                        row = cell,
                        palette = palette,
                        menuOpen = false,
                        onMenu = {},
                        onRead = {},
                        onReadOnline = {},
                        onRetry = {},
                        onDeleteSavedCopy = {},
                        onSignIn = {},
                        modifier = Modifier.weight(1f),
                        selected = selected,
                        onSelect = { onToggle(cell.chapterId) },
                    )
                }
                repeat(4 - cells.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (displayRows.size > 16) {
            Column(Modifier.padding(top = 2.dp)) {
                HorizontalDivider(thickness = .5.dp, color = palette.line)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(chapterTileHeight())
                        .testTag("download-chapter-disclosure-$groupIndex")
                        .clickable(role = Role.Button) { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                ) {
                    Text(chapterDisclosureLabel(displayRows.size, expanded), color = palette.ink, fontSize = 12.5.sp)
                }
            }
        }
    }
}
