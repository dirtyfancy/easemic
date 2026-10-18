package com.easemic.app.feature.detail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.easemic.app.ui.theme.PclSpacing
import com.easemic.app.ui.theme.QuietShelfPalette

/**
 * Skeleton spec §2.1.4 (Part 4 Chunk D, decision 7): the muted one-line notices
 * above the synopsis, in the spec's order — delisted sentence → status message
 * → state statements ("Stored on this device.", the recycle-bin sentences, the
 * serialized cloud line) → "N removed chapters kept" → the helper link line. A
 * slot with nothing to say is absent; there is never a placeholder line.
 *
 * The four statement slots keep the test tags they carry today in `DetailSkin`
 * (`RemoteCatalogDetailScreen.kt` :802-833) verbatim, so every detail device
 * suite that pins those strings stays a regression net (plan decision 9):
 * [delistedSentence]→remote-local-download-unavailable-hint,
 * [statusMessage]→remote-detail-status, [stateStatements]→remote-detail-helper
 * (today's helper slot — several statements share that one tag exactly as
 * several texts share the slot), [removedChaptersLine]→serialized-removed-facts.
 *
 * The link line is new (#E92/§2.1.4): its presence is the CALLER's decision —
 * it shows only while a Collect or Download switch shows, and a state with no
 * explanation passes null. Tapping it is also the caller's business
 * ([onHelperClick] opens the helper sheet); nothing is decided here.
 */
@Composable
internal fun DetailNotices(
    palette: QuietShelfPalette,
    /** `SourceDelistedCopy.SENTENCE`-shaped statement, today's download-unavailable hint slot. */
    delistedSentence: String? = null,
    /** Today's remote-detail-status text ("Source unavailable · showing saved chapters" and the like). */
    statusMessage: String? = null,
    /** Stored-on-device / tombstone sentences / the serialized cloud line — today's helper slot. */
    stateStatements: List<String> = emptyList(),
    /** "N removed chapters kept". */
    removedChaptersLine: String? = null,
    /** "What Collect and Download do ›" / "What Collect does ›"; null = no link line. */
    helperLink: String? = null,
    onHelperClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PclSpacing.Tiny),
    ) {
        delistedSentence?.let {
            NoticeLine(it, palette, Modifier.testTag("remote-local-download-unavailable-hint"))
        }
        statusMessage?.let {
            NoticeLine(it, palette, Modifier.testTag("remote-detail-status"))
        }
        stateStatements.forEach { statement ->
            NoticeLine(statement, palette, Modifier.testTag("remote-detail-helper"))
        }
        removedChaptersLine?.let {
            NoticeLine(it, palette, Modifier.testTag("serialized-removed-facts"))
        }
        helperLink?.let { link ->
            Text(
                link,
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier
                    .clickable(onClick = onHelperClick)
                    .testTag("detail-helper-link"),
            )
        }
    }
}

@Composable
private fun NoticeLine(text: String, palette: QuietShelfPalette, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = palette.muted,
        modifier = modifier,
    )
}
