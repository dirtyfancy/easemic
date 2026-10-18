package com.easemic.app.feature.bridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.ui.components.PclTextButton
import com.easemic.app.ui.theme.QuietShelfPalette
import com.easemic.app.ui.theme.quietShelfPalette

private val CoverShape = RoundedCornerShape(6.dp)

/**
 * Task 14 (browse-remaining §4): the Library Recycle segment retires into
 * this collapsible "Recently deleted · N" card on the bound Cloud storage
 * screen. Rows are the live R2 trash listing (no local mirror): cover thumb +
 * title + `Deleted <relative time>` + per-row Restore / Delete forever (the
 * latter arms on the first tap and only fires on the second). When the cloud
 * is unreachable the whole card collapses to one honest status line.
 */
@Composable
fun RecentlyDeletedCard(
    rows: List<BrowseFeedItem>,
    cloudUnreachable: Boolean,
    onRestore: (BrowseFeedItem) -> Unit,
    onDeleteForever: (BrowseFeedItem) -> Unit,
    // A16: ids with an in-flight Restore/Delete-forever — their row's buttons
    // disable so a slow round-trip doesn't invite duplicate taps.
    busyIds: Set<String> = emptySet(),
    /** #D16: the one line the collapsed card prints while unavailable. */
    unavailableLine: String = RecentlyDeletedCardCopy.UNREACHABLE,
) {
    val palette = quietShelfPalette()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recently-deleted-card"),
        shape = RoundedCornerShape(14.dp),
        color = palette.card,
        border = BorderStroke(1.dp, palette.line),
    ) {
        if (cloudUnreachable) {
            Text(
                unavailableLine,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .testTag("recently-deleted-unreachable"),
            )
            return@Surface
        }
        Column {
            var expanded by remember { mutableStateOf(false) }
            var armedDeleteId by remember { mutableStateOf<String?>(null) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("recently-deleted-header")
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    RecentlyDeletedCardCopy.title(rows.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "›",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.muted,
                    modifier = Modifier.rotate(if (expanded) 90f else 0f),
                )
            }
            if (expanded) {
                rows.forEach { item ->
                    RecentlyDeletedRow(
                        item = item,
                        palette = palette,
                        deleteArmed = armedDeleteId == item.id,
                        busy = item.id in busyIds,
                        onRestore = { onRestore(item) },
                        onDeleteForever = {
                            if (armedDeleteId == item.id) {
                                armedDeleteId = null
                                onDeleteForever(item)
                            } else {
                                armedDeleteId = item.id
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentlyDeletedRow(
    item: BrowseFeedItem,
    palette: QuietShelfPalette,
    deleteArmed: Boolean,
    busy: Boolean,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recently-deleted-row-${item.id}")
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 60.dp)
                .clip(CoverShape)
                .background(palette.ink.copy(alpha = 0.08f))
                .testTag("recently-deleted-cover-${item.id}"),
        ) {
            if (item.coverUrl != null) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                color = palette.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.secondaryLine,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
        // A62 device-run finding (owner phone, probe-measured size=0x53): the
        // armed confirm label ("Tap again to delete forever.") plus Restore
        // measured BEFORE the weighted title column and could consume the
        // whole row, starving the title to width zero. Both buttons now take
        // weight-without-fill: they compete for the leftover space instead
        // of claiming their measured width unconditionally, so the title
        // column always keeps a share.
        PclTextButton(
            onClick = onRestore,
            enabled = !busy,
            modifier = Modifier
                .weight(1f, fill = false)
                .testTag("recently-deleted-restore-${item.id}"),
        ) {
            Text(RecentlyDeletedCardCopy.RESTORE, style = MaterialTheme.typography.labelLarge)
        }
        PclTextButton(
            onClick = onDeleteForever,
            enabled = !busy,
            modifier = Modifier
                .weight(1f, fill = false)
                .testTag("recently-deleted-delete-${item.id}"),
        ) {
            Text(
                if (deleteArmed) {
                    RecentlyDeletedCardCopy.DELETE_FOREVER_CONFIRM
                } else {
                    RecentlyDeletedCardCopy.DELETE_FOREVER
                },
                style = MaterialTheme.typography.labelLarge,
                color = palette.statusBad,
                maxLines = 2,
            )
        }
    }
}
