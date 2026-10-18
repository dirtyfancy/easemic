package com.easemic.app.feature.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.easemic.app.feature.browse.BrowseCoverThumb
import com.easemic.app.feature.browse.ComicTitleCleaner
import com.easemic.app.feature.browse.QUIET_SHELF_COVER_HEIGHT_DP
import com.easemic.app.feature.browse.QUIET_SHELF_COVER_WIDTH_DP
import com.easemic.app.i18n.AppStrings
import com.easemic.app.ui.theme.quietShelfPalette
import com.easemic.app.ui.fastscroll.FastScroller

data class HistoryEntry(
    val id: String,
    val title: String,
    val secondaryLine: String,
    val coverUrl: String? = null,
    val acquisitionUrl: String? = null,
    val providerId: String = "",
    val pageStreamUrl: String? = null,
    val pageCount: Int? = null,
    val sourceName: String = "",
    val visitedAtEpochMs: Long? = null,
    /**
     * For entries whose own [acquisitionUrl] does not identify the source
     * gallery (e.g. a cloud-shelf item keyed by `cloud:<uuid>`), the
     * upstream gallery's acquisition URL (e.g. `ehentai://<gid>/<token>`).
     * Lets [historyDedupKey] collapse a cloud copy against the same gallery
     * opened directly from Browse.
     */
    val upstreamAcquisitionUrl: String? = null,
    val lastReadChapterId: String? = null,
    val lastReadChapterLabel: String? = null,
    val lastReadPageOrdinal: Int? = null,
    /** Persisted marker for serialized sources; old rows infer it from providerId. */
    val serialized: Boolean = false,
)

@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    /**
     * A56: the list state is handed in by the app root so the History tab's
     * scroll position survives route changes (the root's `when (route)`
     * disposes this whole branch on tab switch). Defaults to a screen-local
     * state — the same convention as BrowseScreen's `listState` — so existing
     * callers and connected tests keep their own fresh scroll state.
     */
    listState: LazyListState = rememberLazyListState(),
    onOpenEntry: (HistoryEntry) -> Unit = {},
) {
    // #E34: a visit recorded while History is away moves its row to the top. A keyed list keeps its old first
    // row in place, which leaves the newest row above the viewport. A list left at the very top stays there;
    // any other position keeps A56's anchor. This runs once on entry, before the first measure re-anchors.
    remember(listState) {
        val leftAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        if (leftAtTop) listState.requestScrollToItem(0)
        leftAtTop
    }
    val palette = quietShelfPalette()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("history-screen"),
    ) {
        Text(AppStrings.historyTitle, style = MaterialTheme.typography.titleLarge)
        Text(
            // A41: no "membership" jargon, and no "from Browse" claim —
            // history rows record detail opens from Browse, Collected, the
            // cloud shelf, OPDS and Activity rows alike.
            AppStrings.historySubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 36.dp)
                    .testTag("history-empty-state"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    AppStrings.historyEmptyTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.ink,
                )
                Text(
                    AppStrings.historyEmptySubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
            }
        } else {
            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = ::historyScopedKey) { entry ->
                        val cleaned = remember(entry.title) { ComicTitleCleaner.clean(entry.title) }
                        val resolvedSource = remember(entry.sourceName, entry.providerId, entry.upstreamAcquisitionUrl) {
                            historyResolvedSourceName(entry)
                        }
                        val readingBadge = remember(entry.lastReadChapterLabel, entry.lastReadPageOrdinal) {
                            historyReadingPositionBadge(entry)
                        }
                        Surface(
                            onClick = { onOpenEntry(entry) },
                            shape = RoundedCornerShape(14.dp),
                            color = palette.card,
                            border = BorderStroke(1.dp, palette.line),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = QUIET_SHELF_COVER_HEIGHT_DP.dp)
                                .testTag("history-entry-${entry.id}"),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier.size(
                                        width = QUIET_SHELF_COVER_WIDTH_DP.dp,
                                        height = QUIET_SHELF_COVER_HEIGHT_DP.dp,
                                    ),
                                ) {
                                    BrowseCoverThumb(
                                        coverUrl = entry.coverUrl,
                                        contentDescription = entry.title,
                                        placeholderTestTag = "history-cover-placeholder",
                                        imageTestTag = "history-cover-image",
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    val thumbBadge = entry.pageCount?.takeIf { it > 0 }?.let { "${it}p" }
                                        ?: entry.lastReadChapterLabel?.takeIf { it.isNotBlank() }
                                    thumbBadge?.let { badge ->
                                        Text(
                                            text = badge,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.card,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(3.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(palette.ink.copy(alpha = 0.74f))
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = QUIET_SHELF_COVER_HEIGHT_DP.dp),
                                    verticalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Text(
                                            entry.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        cleaned.authorOrCircle?.let { author ->
                                            Text(
                                                author,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = palette.muted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        val metaLine = historyMetaLine(entry, System.currentTimeMillis())
                                        if (metaLine.isNotBlank()) {
                                            Text(
                                                metaLine,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = palette.muted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (resolvedSource.isNotBlank()) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(palette.ink.copy(alpha = 0.06f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            ) {
                                                Text(
                                                    text = resolvedSource,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = palette.ink.copy(alpha = 0.78f),
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                        if (readingBadge != null) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(palette.statusOk.copy(alpha = 0.13f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            ) {
                                                Text(
                                                    text = readingBadge,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = palette.statusOk,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                        cleaned.languageTag?.let { lang ->
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(palette.ink.copy(alpha = 0.06f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            ) {
                                                Text(
                                                    text = lang,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = palette.ink.copy(alpha = 0.78f),
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.weight(1f))
                                        HistoryRowChevron()
                                    }
                                }
                            }
                        }
                    }
                }
                FastScroller(
                    state = listState,
                    knownTotalItems = entries.size,
                    showPositionBubble = true,
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

/** Right-pointing row chevron (same stroke convention as the Browse source rows). */
@Composable
private fun HistoryRowChevron() {
    val muted = quietShelfPalette().muted
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .size(14.dp)
            .testTag("history-row-chevron"),
    ) {
        val strokeWidth = 1.75.dp.toPx()
        val midY = size.height / 2f
        val midX = size.width / 2f - 2.dp.toPx()
        drawLine(
            color = muted,
            start = androidx.compose.ui.geometry.Offset(midX, midY - 5.dp.toPx()),
            end = androidx.compose.ui.geometry.Offset(midX + 5.dp.toPx(), midY),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = muted,
            start = androidx.compose.ui.geometry.Offset(midX, midY + 5.dp.toPx()),
            end = androidx.compose.ui.geometry.Offset(midX + 5.dp.toPx(), midY),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}
