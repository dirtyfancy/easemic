package com.easemic.app.feature.importing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.easemic.app.feature.library.QuietShelfLine
import com.easemic.app.feature.library.ShelfLine
import com.easemic.app.model.SourceId
import com.easemic.app.ui.components.PclButton
import com.easemic.app.ui.components.PclTextButton
import com.easemic.app.ui.theme.PclSpacing
import com.easemic.app.ui.theme.quietShelfPalette
import kotlinx.coroutines.delay

/**
 * A30 §2.2. Rows, not cards: name, one status line, one overflow. Pull to
 * refresh rescans every folder; opening the screen rescans the due ones.
 * Nothing here deletes a file, so no verb on this screen says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    rows: List<FolderRow>,
    modifier: Modifier = Modifier,
    onRescanNow: (SourceId) -> Unit = {},
    onStopWatching: (SourceId) -> Unit = {},
    onChooseFolderAgain: (SourceId) -> Unit = {},
    onImportFolder: () -> Unit = {},
    onRefreshAll: () -> Unit = {},
    // SEND-BACK #2 item C: ViewModel-owned so the spinner reflects a refresh
    // that is genuinely in flight, even across navigation, instead of a
    // local edge-detector that never fires when the refresh enqueues nothing.
    isRefreshing: Boolean = false,
    // SEND-BACK #2 item F: carries which folder's line this is, so Undo
    // always targets the folder it was rendered for, not whatever the
    // ViewModel's stoppedFolder happens to hold by the time the click lands.
    stoppedFolder: StoppedFolder? = null,
    onUndoStopWatching: (SourceId) -> Unit = {},
    // Review HIGH-4: ImportUiState.notice used to be rendered nowhere; the
    // Folders screen is its home now.
    notice: String? = null,
    onDismissNotice: () -> Unit = {},
) {
    val palette = quietShelfPalette()
    // SEND-BACK #2 item J: a single live clock read shared by every row's
    // "scanned N ago" line, ticking while this screen is on-screen instead
    // of running for the ViewModel's whole lifetime.
    val nowMillis by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefreshAll,
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize().testTag("folders-list")) {
                item {
                    Column(
                        modifier = Modifier.padding(
                            start = PclSpacing.Medium,
                            end = PclSpacing.Medium,
                            top = PclSpacing.Large,
                            bottom = PclSpacing.Small,
                        ),
                        verticalArrangement = Arrangement.spacedBy(PclSpacing.Tiny),
                    ) {
                        Text(
                            FolderRowCopy.TITLE,
                            style = MaterialTheme.typography.headlineMedium,
                            color = palette.ink,
                        )
                        Text(
                            FolderRowCopy.SUBTITLE,
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.muted,
                        )
                    }
                }
                if (rows.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = PclSpacing.Medium, vertical = PclSpacing.Large)
                                .testTag("folders-empty"),
                            verticalArrangement = Arrangement.spacedBy(PclSpacing.ExtraSmall),
                        ) {
                            Text(
                                FolderRowCopy.EMPTY_TITLE,
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.ink,
                            )
                            Text(
                                FolderRowCopy.EMPTY_BODY,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.muted,
                            )
                            PclButton(onClick = onImportFolder) {
                                Text(
                                    FolderRowCopy.IMPORT_FOLDER,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
                items(rows, key = { it.sourceId.value }) { row ->
                    FolderListRow(
                        row = row,
                        nowMillis = nowMillis,
                        onRescanNow = onRescanNow,
                        onStopWatching = onStopWatching,
                        onChooseFolderAgain = onChooseFolderAgain,
                    )
                    HorizontalDivider(color = palette.line, thickness = 1.dp)
                }
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(PclSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(PclSpacing.ExtraSmall),
        ) {
            if (notice != null) {
                QuietShelfLine(
                    line = ShelfLine(
                        text = notice,
                        testTag = "folders-notice-line",
                        actionLabel = FolderRowCopy.DISMISS,
                        actionTestTag = "folders-notice-dismiss",
                    ),
                    onAction = onDismissNotice,
                )
            }
            if (stoppedFolder != null) {
                QuietShelfLine(
                    line = ShelfLine(
                        text = FolderRowCopy.stoppedLine(stoppedFolder.displayName),
                        testTag = "folders-stopped-line",
                        actionLabel = FolderRowCopy.UNDO,
                        actionTestTag = "folders-stopped-undo",
                    ),
                    onAction = { onUndoStopWatching(stoppedFolder.sourceId) },
                )
            }
        }
    }
}

@Composable
private fun FolderListRow(
    row: FolderRow,
    nowMillis: Long,
    onRescanNow: (SourceId) -> Unit,
    onStopWatching: (SourceId) -> Unit,
    onChooseFolderAgain: (SourceId) -> Unit,
) {
    val palette = quietShelfPalette()
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            // Review LOW-2: only access-lost rows are actionable by tapping
            // the row itself; a clickable modifier on every row (even a
            // no-op enabled=false one) still consumes a11y/ripple semantics.
            .then(
                if (row.needsPicker) {
                    Modifier.clickable(onClickLabel = FolderRowCopy.CHOOSE_FOLDER_AGAIN) {
                        onChooseFolderAgain(row.sourceId)
                    }
                } else {
                    Modifier
                },
            )
            .padding(start = PclSpacing.Medium, end = PclSpacing.ExtraSmall)
            .testTag("folders-row-${row.sourceId.value}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(vertical = PclSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(PclSpacing.Tiny),
        ) {
            Text(
                row.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                FolderRowCopy.status(row.status, nowMillis),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            PclTextButton(
                onClick = { menuOpen = true },
                modifier = Modifier
                    .testTag("folders-row-menu-${row.sourceId.value}")
                    .semantics {
                        contentDescription = "More options for ${row.displayName}"
                    },
            ) {
                Text("···", style = MaterialTheme.typography.titleMedium)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (row.needsPicker) {
                    DropdownMenuItem(
                        text = { Text(FolderRowCopy.CHOOSE_FOLDER_AGAIN) },
                        onClick = {
                            menuOpen = false
                            onChooseFolderAgain(row.sourceId)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(FolderRowCopy.RESCAN_NOW) },
                    onClick = {
                        menuOpen = false
                        onRescanNow(row.sourceId)
                    },
                )
                DropdownMenuItem(
                    text = { Text(FolderRowCopy.STOP_WATCHING) },
                    onClick = {
                        menuOpen = false
                        onStopWatching(row.sourceId)
                    },
                )
            }
        }
    }
}
