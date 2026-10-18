package com.easemic.app.feature.detail.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.easemic.app.ui.theme.PclSpacing
import com.easemic.app.ui.theme.QuietShelfPalette

/**
 * Skeleton spec §2.1.4 (Part 4 Chunk D, decision 6): the helper bottom sheet
 * behind the "What Collect and Download do ›" link. One sheet, one paragraph —
 * the caller has already picked the wording (`RemoteDetailHelperCopy` via
 * `DetailActionMatrix.present` / the serialized copy) and passes the final
 * string in; this composable decides nothing and writes no copy.
 *
 * Sheet idiom follows `SourceFilterSheet` / `LibraryFilterSheet` (Material3
 * [ModalBottomSheet], skipped partially-expanded state, sibling-sheet content
 * padding); the container is the app's card token like the other quiet-shelf
 * chrome. Swipe-down and scrim tap both dismiss through [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailHelperSheet(
    palette: QuietShelfPalette,
    body: String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.card,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PclSpacing.Medium)
                .padding(bottom = PclSpacing.Large)
                .testTag("detail-helper-sheet"),
        ) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.ink,
            )
        }
    }
}
