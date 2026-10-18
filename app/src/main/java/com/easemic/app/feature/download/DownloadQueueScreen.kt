package com.easemic.app.feature.download

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
import com.easemic.app.ui.theme.quietShelfPalette

/**
 * More → Download queue: WAIT + DOWNLOAD only, FIFO by enqueue time (spec §4.3).
 */
@Composable
fun DownloadQueueScreen(
    rows: List<DownloadQueueRow>,
) {
    val palette = quietShelfPalette()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("download-queue-screen"),
    ) {
        Text(AppStrings.downloadQueue, style = MaterialTheme.typography.titleLarge)
        Text(
            AppStrings.downloadsQueueSubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        if (rows.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 36.dp)
                    .testTag("download-queue-empty-state"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    DownloadQueueEmptyCopy.TITLE,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.ink,
                )
                Text(
                    DownloadQueueEmptyCopy.SUBTITLE,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.jobId }) { row ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = palette.card,
                        border = BorderStroke(1.dp, palette.line),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .testTag("download-queue-job-${row.jobId}"),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BrowseCoverThumb(
                                coverUrl = row.coverUrl,
                                contentDescription = row.title,
                                placeholderTestTag = "download-queue-cover-placeholder",
                                imageTestTag = "download-queue-cover-image",
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
                                    downloadQueueSecondaryLine(row),
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
