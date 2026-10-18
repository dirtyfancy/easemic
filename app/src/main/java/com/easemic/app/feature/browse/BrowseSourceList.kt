package com.easemic.app.feature.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.source.zaimanhua.ZAIMANHUA_DISPLAY_NAME
import com.easemic.app.feature.providers.ExhentaiEnableStore
import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.feature.providers.JmEnableStore
import com.easemic.app.feature.providers.OpdsProviderConfig
import com.easemic.app.i18n.AppStrings
import com.easemic.app.ui.theme.quietShelfPalette
import java.util.Locale

/** Reachability of one Browse source row (spec §1: dot + word, always paired; 2026-09-19 spec: three states). */
enum class BrowseSourceHealth {
    HEALTHY,
    UNREACHABLE,
    /** This source's probe is in flight: the app is finding out, not reporting. Never dimmed. */
    CHECKING,
}

/** Per-source list of rows currently dimmed as unreachable (no persisted OPDS health yet). */

/** One Level-1 source-list row model. */
data class BrowseSourceListItem(
    val sourceId: String,
    val name: String,
    val health: BrowseSourceHealth,
    /**
     * Total item count, only when the source truly knows one (OPDS servers
     * report their catalog size). Null renders no count line — official
     * sources (E-Hentai / nhentai) are open-ended gallery streams and never
     * show one.
     */
    val itemCount: Int? = null,
)

/** True while [sourceId]'s list row is dimmed (unreachable). UI-state helper, pure. */
fun browseSourceRowDimmed(sourceId: String, dimmedIds: Set<String>): Boolean = sourceId in dimmedIds

/**
 * Builds the Level-1 row models from the app's current source configuration
 * (spec §1 order: E-Hentai when enabled, ExHentai when its session-gated
 * enable store allows it, nhentai when enabled, then each configured OPDS
 * provider). Official sources render Healthy (they carry no persisted health
 * yet — no probe loop invented here); an OPDS provider renders
 * [BrowseSourceHealth.UNREACHABLE] when the caller passes its id in
 * [unreachableProviderIds], which the app derives from the persisted
 * `isDimmed` flag the health probe writes.
 *
 * Callers must pass that set. It defaults to empty so tests can build a
 * healthy fixture in one line, and that default silently reported every
 * unreachable catalog as Healthy for as long as the app omitted the argument
 * (#C15).
 *
 * [checkingProviderIds] — OPDS providers whose probe is in flight right now
 * (`Checking…`); it wins over the persisted verdict for as long as the probe
 * runs (2026-09-19 spec §5). Official sources never check: they have no probe.
 *
 * W7: ExHentai sits between its siblings — [exhentaiEnabled] is the
 * [ExhentaiEnableStore] verdict (granted session + user opt-in), so the row
 * disappears the moment the session loses access, exactly like the E-Hentai
 * switch's row does for its own enable.
 */
fun browseSourceListItems(
    ehentaiEnabled: Boolean,
    exhentaiEnabled: Boolean = false,
    nhentaiEnabled: Boolean,
    zaimanhuaEnabled: Boolean = false,
    opdsProviders: List<OpdsProviderConfig>,
    unreachableProviderIds: Set<String> = emptySet(),
    checkingProviderIds: Set<String> = emptySet(),
    jmEnabled: Boolean = false,
): List<BrowseSourceListItem> = buildList {
    if (ehentaiEnabled) {
        add(
            BrowseSourceListItem(
                sourceId = EhentaiEnableStore.SOURCE_ID,
                name = EhentaiEnableStore.DISPLAY_NAME,
                health = BrowseSourceHealth.HEALTHY,
            ),
        )
    }
    if (exhentaiEnabled) {
        add(
            BrowseSourceListItem(
                sourceId = ExhentaiEnableStore.SOURCE_ID,
                name = ExhentaiEnableStore.DISPLAY_NAME,
                health = BrowseSourceHealth.HEALTHY,
            ),
        )
    }
    if (nhentaiEnabled) {
        add(
            BrowseSourceListItem(
                sourceId = NhentaiEnableStore.SOURCE_ID,
                name = NhentaiEnableStore.DISPLAY_NAME,
                health = BrowseSourceHealth.HEALTHY,
            ),
        )
    }
    if (zaimanhuaEnabled) {
        add(
            BrowseSourceListItem(
                sourceId = GalleryIdentityV1.ZAIMANHUA,
                name = ZAIMANHUA_DISPLAY_NAME,
                health = BrowseSourceHealth.HEALTHY,
            ),
        )
    }
    if (jmEnabled) {
        add(BrowseSourceListItem(JmEnableStore.SOURCE_ID, JmEnableStore.DISPLAY_NAME, BrowseSourceHealth.HEALTHY))
    }
    opdsProviders.forEach { provider ->
        add(
            BrowseSourceListItem(
                sourceId = provider.id,
                name = provider.displayName,
                health = when (provider.id) {
                    in checkingProviderIds -> BrowseSourceHealth.CHECKING
                    in unreachableProviderIds -> BrowseSourceHealth.UNREACHABLE
                    else -> BrowseSourceHealth.HEALTHY
                },
            ),
        )
    }
}

/**
 * Browse Level 1 (spec §1): the root of the Browse tab — `Browse` title, one
 * row per source (glyph block + name + health dot paired with a word, optional
 * `N items` count, chevron), and a dashed `+ Add a catalog source` card at the
 * bottom that deep-links to Settings → Content sources.
 *
 * [dimmedSourceIds] drives the dimmed visual: rows whose source id is in it
 * render faded (unreachable). Kept as a separate argument so the model's
 * `dimmed` computation stays observable through [browseSourceRowDimmed].
 */
@Composable
fun BrowseSourceList(
    sources: List<BrowseSourceListItem>,
    onSourceClick: (String) -> Unit,
    onAddCatalogClick: () -> Unit,
    dimmedSourceIds: Set<String> = sources
        .filter { it.health == BrowseSourceHealth.UNREACHABLE }
        .map { it.sourceId }
        .toSet(),
) {
    val palette = quietShelfPalette()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                AppStrings.browseTitle,
                style = MaterialTheme.typography.titleLarge,
                color = palette.ink,
                modifier = Modifier
                    .weight(1f)
                    .testTag("browse-source-list-title"),
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("browse-source-list"),
        ) {
            items(sources, key = { it.sourceId }) { source ->
                BrowseSourceRow(
                    source = source,
                    dimmed = browseSourceRowDimmed(source.sourceId, dimmedSourceIds),
                    onClick = { onSourceClick(source.sourceId) },
                )
            }
            item(key = "browse-add-catalog") {
                AddCatalogCard(onClick = onAddCatalogClick)
            }
        }
    }
}

@Composable
private fun BrowseSourceRow(
    source: BrowseSourceListItem,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val palette = quietShelfPalette()
    val (dotColor, healthWord) = when (source.health) {
        BrowseSourceHealth.HEALTHY -> palette.statusOk to AppStrings.statusHealthy
        BrowseSourceHealth.UNREACHABLE -> palette.statusBad to AppStrings.statusUnreachable
        // 2026-09-19 spec §3: amber = "finding out"; the word carries the meaning alone.
        BrowseSourceHealth.CHECKING -> palette.statusWarn to AppStrings.statusChecking
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = palette.card,
        border = BorderStroke(1.dp, palette.line),
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("browse-source-row-${source.sourceId}")
            .semantics { contentDescription = AppStrings.openSourceNamed(source.name) },
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .alpha(if (dimmed) 0.55f else 1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceGlyphBlock(sourceId = source.sourceId, name = source.name)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    source.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(dotColor)
                            .testTag("browse-source-health-dot"),
                    )
                    Text(
                        healthWord,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                }
                source.itemCount?.let { count ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "%,d items".format(Locale.US, count),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                }
            }
            SourceChevronGlyph()
        }
    }
}

@Composable
internal fun SourceGlyphBlock(sourceId: String, name: String, size: Dp = 40.dp, tagPrefix: String = "browse") {
    val palette = quietShelfPalette()
    if (sourceLogoFor(sourceId) != null) {
        SourceLogoTile(sourceId, size = size, testTag = "$tagPrefix-source-logo-$sourceId")
        return
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(palette.statusOk.copy(alpha = 0.14f))
            .testTag("$tagPrefix-source-logo-$sourceId"),
        contentAlignment = Alignment.Center,
    ) {
        run {
            Text(
                name.firstOrNull()?.uppercase(Locale.US) ?: "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.statusOk,
                modifier = Modifier.testTag("$tagPrefix-source-initial-$sourceId"),
            )
        }
    }
}

@Composable
private fun SourceChevronGlyph() {
    val muted = quietShelfPalette().muted
    androidx.compose.foundation.Canvas(modifier = Modifier.size(14.dp)) {
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

/** Dashed bottom card (spec §1): the OPDS add entry. */
@Composable
private fun AddCatalogCard(onClick: () -> Unit) {
    val palette = quietShelfPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                BorderStroke(1.dp, palette.line),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 14.dp)
            .testTag("browse-add-catalog-card")
            .semantics { contentDescription = AppStrings.addCatalogSourceA11y },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            AppStrings.addCatalogSource,
            style = MaterialTheme.typography.titleSmall,
            color = palette.statusOk,
        )
    }
}
