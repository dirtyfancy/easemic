package com.easemic.app.feature.detail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.easemic.app.feature.browse.PREVIEW_BOTTOM_PADDING
import com.easemic.app.feature.browse.seriesSynopsisPlainText
import com.easemic.app.feature.detail.model.ComicDetail
import com.easemic.app.feature.detail.model.DetailPerson
import com.easemic.app.feature.detail.model.DetailTag
import com.easemic.app.feature.detail.model.DetailTags
import com.easemic.app.feature.detail.model.SourceDetailProfile
import com.easemic.app.feature.source.valueOrNull
import com.easemic.app.ui.theme.QuietShelfPalette

/**
 * One detail page for every source (skeleton spec §2.1 order, plan decision 1).
 * Reads [ComicDetail] plus caller state slots and stacks the sections — top bar
 * → header → actions → notices → synopsis → tags → content — in the spec's
 * order, with `DetailSkin`'s outer-Column numbers (16 dp sides, 12 dp top,
 * 14 dp spacing; the bottom gap is the shared preview constant). A section with
 * nothing to show is absent, never a placeholder.
 *
 * Presentational (decision 2): no fetching, no ViewModel, no storage. The
 * expansion memory of the sections that collapse is keyed on [memoryKey]
 * (`"<providerNamespace>:<canonicalSeriesOrItemId>"`) (decision 3). Provider names arrive through
 * [detail] and [profile] — never as literals here (DetailUiProviderGuardTest).
 */
@Composable
internal fun DetailSkeleton(
    detail: ComicDetail,
    profile: SourceDetailProfile,
    palette: QuietShelfPalette,
    memoryKey: String,
    cover: @Composable () -> Unit,
    topBar: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    notices: @Composable () -> Unit,
    peopleClickable: Boolean,
    onPersonClick: (DetailPerson) -> Unit,
    tagsClickable: Boolean,
    onTagClick: (DetailTag) -> Unit,
    content: @Composable () -> Unit,
    tagsLoading: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = PREVIEW_BOTTOM_PADDING)
            .testTag("detail-skeleton"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        topBar()
        DetailHeader(
            detail = detail,
            profile = profile,
            palette = palette,
            memoryKey = memoryKey,
            cover = cover,
            peopleClickable = peopleClickable,
            onPersonClick = onPersonClick,
        )
        actions()
        notices()
        SkeletonSynopsis(detail, palette, memoryKey)
        val tags = detail.tags.valueOrNull()
        if (tagsLoading || tags != null) {
            DetailTagBlock(
                tags = tags ?: DetailTags.Flat(emptyList()),
                palette = palette,
                memoryKey = memoryKey,
                loading = tagsLoading,
                clickable = tagsClickable,
                onTagClick = onTagClick,
            )
        }
        content()
    }
}

/**
 * §2.1.5: the source's own description as plain text, four lines, "Show more" /
 * "Show less" on overflow, with the title's remembered keying. Absent when the detail
 * carries no synopsis.
 */
@Composable
private fun SkeletonSynopsis(detail: ComicDetail, palette: QuietShelfPalette, memoryKey: String) {
    val plain = seriesSynopsisPlainText(detail.synopsis.valueOrNull()) ?: return
    // Same shape as HeaderTitle: identity inputs + "synopsis".
    var synopsisExpanded by rememberSaveable(memoryKey, "synopsis") { mutableStateOf(false) }
    var synopsisOverflow by rememberSaveable(memoryKey, "synopsis-overflow") { mutableStateOf(false) }
    Column {
        Text(
            text = plain,
            modifier = Modifier.testTag("detail-synopsis"),
            maxLines = if (synopsisExpanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!synopsisExpanded) synopsisOverflow = result.hasVisualOverflow
            },
        )
        if (synopsisOverflow || synopsisExpanded) {
            Text(
                if (synopsisExpanded) "Show less" else "Show more",
                modifier = Modifier
                    .testTag("detail-synopsis-more")
                    .clickable { synopsisExpanded = !synopsisExpanded }
                    .padding(vertical = 2.dp),
                color = palette.muted,
            )
        }
    }
}
