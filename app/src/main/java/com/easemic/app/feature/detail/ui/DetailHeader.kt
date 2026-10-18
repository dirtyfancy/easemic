package com.easemic.app.feature.detail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.easemic.app.feature.detail.model.ComicDetail
import com.easemic.app.feature.detail.model.DetailFacts
import com.easemic.app.feature.detail.model.DetailFactsFormatter
import com.easemic.app.feature.detail.model.DetailPerson
import com.easemic.app.feature.detail.model.SourceDetailProfile
import com.easemic.app.feature.source.valueOrNull
import com.easemic.app.i18n.AppStrings
import com.easemic.app.i18n.tr
import com.easemic.app.ui.theme.QuietShelfPalette

/**
 * Detail header (skeleton spec §2.1.2): the caller's 96×128 cover slot and,
 * beside it, the title, the "By <names>" line, the facts line and the
 * date/numbers line — one section per source, each absent when it has nothing
 * to show (never a placeholder).
 *
 * Title: at most four lines with "Show more"/"Show less" on overflow — today's
 * serialized behaviour, now for every source. "By <names>": each name its own
 * tap target firing [onPersonClick] with that [DetailPerson] (one
 * `LinkAnnotation.Clickable` per name), plain text when the caller marks the source not clickable (§2.4). Both are
 * remembered per source + series through [memoryKey]
 * (`"<providerNamespace>:<canonicalSeriesOrItemId>"`).
 *
 * Facts and date/numbers come from [DetailFactsFormatter.format] — no new
 * formatting here. The date line renders the date text plus one Text per number
 * carrying that number's contentDescription ("Rating 4.6", "1,204 favourites"),
 * joined by " · " so it reads exactly as the formatter's joined dateLine. The
 * line is date-anchored: no date, no line.
 */
@Composable
internal fun DetailHeader(
    detail: ComicDetail,
    profile: SourceDetailProfile,
    palette: QuietShelfPalette,
    memoryKey: String,
    cover: @Composable () -> Unit,
    peopleClickable: Boolean = true,
    onPersonClick: (DetailPerson) -> Unit = {},
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(width = 96.dp, height = 128.dp).testTag("detail-header-cover")) { cover() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val title = detail.title.valueOrNull()?.takeIf(String::isNotBlank)
            if (title != null) HeaderTitle(title, palette, memoryKey)
            HeaderByline(detail, palette, memoryKey, peopleClickable, onPersonClick)
            val facts = DetailFactsFormatter.format(detail, profile)
            if (facts.line.isNotBlank()) {
                Text(
                    facts.line,
                    Modifier.testTag("detail-header-facts"),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                )
            }
            if (facts.date != null) HeaderDateLine(facts, palette)
        }
    }
}

@Composable
private fun HeaderTitle(title: String, palette: QuietShelfPalette, memoryKey: String) {
    var titleExpanded by rememberSaveable(memoryKey, "title") { mutableStateOf(false) }
    var titleOverflow by rememberSaveable(memoryKey, "title-overflow") { mutableStateOf(false) }
    Text(
        title,
        Modifier.testTag("detail-header-title"),
        style = MaterialTheme.typography.titleMedium,
        maxLines = if (titleExpanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!titleExpanded) titleOverflow = it.hasVisualOverflow },
    )
    if (titleOverflow || titleExpanded) {
        Text(
            if (titleExpanded) AppStrings.showLess else AppStrings.showMore,
            Modifier.testTag("detail-header-title-more").clickable { titleExpanded = !titleExpanded }.padding(vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted,
        )
    }
}

@Composable
private fun HeaderByline(
    detail: ComicDetail,
    palette: QuietShelfPalette,
    memoryKey: String,
    peopleClickable: Boolean,
    onPersonClick: (DetailPerson) -> Unit,
) {
    val people = detail.people.valueOrNull().orEmpty().filter { it.name.isNotBlank() }
    if (people.isEmpty()) return
    var peopleExpanded by rememberSaveable(memoryKey, "authors") { mutableStateOf(false) }
    var peopleOverflow by rememberSaveable(memoryKey, "authors-overflow") { mutableStateOf(false) }
    val byline = buildAnnotatedString {
        append(tr("By ", "作者：", "作者：", "著者："))
        people.forEachIndexed { index, person ->
            if (index > 0) append(", ")
            val name = person.name.trim()
            if (peopleClickable) {
                withLink(LinkAnnotation.Clickable("person-$index") { onPersonClick(person) }) { append(name) }
            } else {
                append(name)
            }
        }
    }
    Text(
        byline,
        Modifier.testTag("detail-header-people"),
        style = MaterialTheme.typography.bodySmall,
        color = palette.muted,
        maxLines = if (peopleExpanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!peopleExpanded) peopleOverflow = it.hasVisualOverflow },
    )
    if (peopleOverflow || peopleExpanded) {
        Text(
            if (peopleExpanded) AppStrings.showLess else AppStrings.showMore,
            Modifier.testTag("detail-header-people-more").clickable { peopleExpanded = !peopleExpanded }.padding(vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted,
        )
    }
}

/** Date + numbers as separate Texts — the numbers each carry their own contentDescription. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderDateLine(facts: DetailFacts, palette: QuietShelfPalette) {
    FlowRow(
        Modifier.testTag("detail-header-date-line"),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            checkNotNull(facts.date),
            Modifier.testTag("detail-header-date"),
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted,
        )
        facts.numberTexts.forEachIndexed { index, number ->
            Text(
                " · ",
                Modifier.testTag("detail-header-sep-$index"),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
            val description = facts.numberDescriptions.getOrNull(index)
            Text(
                number,
                Modifier
                    .testTag("detail-header-number-$index")
                    .semantics { if (description != null) contentDescription = description },
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
            )
        }
    }
}
