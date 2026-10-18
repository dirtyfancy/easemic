package com.easemic.app.feature.collect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.easemic.app.feature.browse.BrowseCoverThumb
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.ui.theme.quietShelfPalette

@Composable
fun CloudSyncQueueScreen(
    rows: List<CloudSyncQueueItem>,
    blockedHint: String? = null,
) {
    val palette = quietShelfPalette()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("cloud-sync-queue-screen"),
    ) {
        Text(AppStrings.cloudSyncQueue, style = MaterialTheme.typography.titleLarge)
        Text(
            tr("Collects uploading to your bound R2. Finished syncs leave this list. Failures stay as Failed until you Sync again.", "正在上传到已绑定 R2 的收藏会显示在这里。同步完成后会离开列表。失败项会保持“失败”，直到再次同步。", "正在上傳至已綁定 R2 的收藏會顯示在這裡。同步完成後會離開列表。失敗項會保持「失敗」，直到再次同步。", "接続中の R2 にアップロードするコレクションを表示します。同期が完了すると一覧から消えます。失敗した項目は再同期するまで失敗のままです。"),
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        if (rows.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 36.dp)
                    .testTag("cloud-sync-queue-empty-state"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    CloudSyncQueueEmptyCopy.TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.ink,
                )
                Text(
                    blockedHint?.takeIf { it.isNotBlank() } ?: CloudSyncQueueEmptyCopy.SUBTITLE,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.id }) { row ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = palette.card,
                        border = BorderStroke(1.dp, palette.line),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .testTag("cloud-sync-queue-job-${row.id}"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BrowseCoverThumb(
                                coverUrl = row.coverUrl,
                                contentDescription = row.title,
                                placeholderTestTag = "cloud-sync-queue-cover-placeholder",
                                imageTestTag = "cloud-sync-queue-cover-image",
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    row.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    cloudSyncSecondaryLine(row),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.muted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
