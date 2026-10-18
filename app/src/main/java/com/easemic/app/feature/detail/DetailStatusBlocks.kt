package com.easemic.app.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.easemic.app.feature.library.LibraryUnavailableCopy
import com.easemic.app.feature.reader.ReaderGrantLossRecovery
import com.easemic.app.feature.reader.ReaderIncompleteCopy
import com.easemic.app.feature.reader.ReaderPageFailure
import com.easemic.app.model.GrantKind
import com.easemic.app.ui.components.PclOutlinedButton
import com.easemic.app.ui.components.PclTextButton
import com.easemic.app.ui.theme.PclSpacing
import com.easemic.app.i18n.AppStrings

/**
 * A32 §5: the honest status surfaces the retired local skin owned. These are
 * *state*, not chrome — grant loss, deleted files and incompleteness are real
 * conditions the unified Detail must still be able to say out loud — so they
 * survive the merge while the layout around them does not. Every string and
 * test tag is carried over from `DetailScreen.kt` unchanged (A30 §4 lock).
 */

@Composable
fun DetailLoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/** The row left the Library while its Detail was open. */
@Composable
fun DetailItemGoneScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PclSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(PclSpacing.Small),
    ) {
        PclOutlinedButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = AppStrings.back },
        ) {
            Text(AppStrings.back)
        }
        Text(LocalDetailCopy.display(LocalDetailCopy.ITEM_GONE))
    }
}

/**
 * A30 §2.2 (S4b/G2+H6): the folder's grant is gone. The files are presumed fine, so this
 * is the one case with a way out — and it is the same way out the reader and the shelf
 * name, the Folders screen. `grantKind` is still taken so a future archive-specific
 * vocabulary has somewhere to land; today every kind gets the same words on purpose.
 */
@Composable
fun DetailAccessLostBlock(
    grantKind: GrantKind,
    onOpenFolders: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PclSpacing.Tiny)) {
        Text(
            LibraryUnavailableCopy.statusTitle(grantKind),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            LibraryUnavailableCopy.statusHint(grantKind),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        PclTextButton(onClick = onOpenFolders) {
            Text(
                LibraryUnavailableCopy.recoveryLabel(grantKind),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * A30 §2.4 (S4b/I2): the comic's files are gone. The shelf already hides it; anyone who got
 * here from a stale route gets the same single sentence the shelf line uses, and Back.
 * Deliberately actionless — choosing the folder again cannot bring a deleted comic back.
 */
@Composable
fun DetailFilesGoneBlock() {
    Text(
        ReaderGrantLossRecovery.userMessage(ReaderPageFailure.FILES_GONE),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(DETAIL_FILES_GONE_TAG),
    )
}

/**
 * Some pages the folder used to have are missing from the current revision.
 *
 * A36: [onOpenFolders] is null for a catalog download. A download has no
 * watched folder, so "Rescan its folder…" and a button into the Folders screen
 * (which never lists the download tree) would both be instructions the user
 * cannot carry out. The title still renders — the shelf badges the row, and a
 * badge with no word anywhere is its own kind of dishonesty — and the
 * truncation note below it supplies the reason.
 */
@Composable
fun DetailIncompleteBlock(onOpenFolders: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(PclSpacing.Tiny)) {
        Text(ReaderIncompleteCopy.title(), style = MaterialTheme.typography.bodyMedium)
        if (onOpenFolders != null) {
            Text(
                // S4b/G7: one string, owned by ReaderIncompleteCopy (A30 §2.2 vocabulary:
                // folders, not "sources").
                ReaderIncompleteCopy.hint(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            PclTextButton(onClick = onOpenFolders) {
                // S4b/H5: "Sources" is a retired surface name (A30 §4); this navigates to
                // AppRoute.Sources, which *is* FoldersScreen.
                Text(ReaderIncompleteCopy.cta(), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Test tag for the files-gone statement (A30 §2.4 acceptance). */
const val DETAIL_FILES_GONE_TAG = "detail-files-gone-line"

/**
 * P-a — the download stopped short because the source withdrew the rest. Not a
 * failure block: there is nothing to retry and nothing the user did wrong, so
 * this is one muted sentence stating how far the fetch got and who decided it
 * stopped. It sits alongside (not instead of) the availability blocks above,
 * because "some pages never arrived" and "the files left the phone" are
 * different facts about the same row.
 */
@Composable
fun DetailTruncatedSealBlock(note: String) {
    Text(
        note,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        modifier = Modifier.testTag(DETAIL_TRUNCATED_SEAL_TAG),
    )
}

/** Test tag for the truncated-seal statement (P-a acceptance). */
const val DETAIL_TRUNCATED_SEAL_TAG = "detail-truncated-seal"
