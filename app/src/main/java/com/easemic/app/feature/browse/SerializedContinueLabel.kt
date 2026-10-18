package com.easemic.app.feature.browse

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.easemic.app.ui.components.PclButton

internal fun serializedContinueFontSize(
    value: SerializedContinueText,
    normal: androidx.compose.ui.unit.TextUnit,
    minimum: androidx.compose.ui.unit.TextUnit,
    maxWidth: Int,
    measure: (String, androidx.compose.ui.unit.TextUnit) -> Int,
): androidx.compose.ui.unit.TextUnit {
    val fixedRuns = listOfNotNull(value.verb, value.chapter?.let { " · " }, value.pageSuffix)
    var size = normal
    // Each drawn Text rounds its own width. Measuring their concatenation can
    // underestimate the space the label needs and squeeze the final page run.
    while (size.value > minimum.value && fixedRuns.sumOf { measure(it, size) } > maxWidth) {
        size = androidx.compose.ui.unit.TextUnit((size.value - 1f).coerceAtLeast(minimum.value), size.type)
    }
    return size
}

internal fun serializedContinueFixedRunConstraints(parent: Constraints, intrinsicWidth: Int): Constraints =
    parent.copy(minWidth = 0, minHeight = 0, maxWidth = intrinsicWidth)

/** Reserve the complete suffix before giving the chapter its remaining single-line width. */
@Composable
fun SerializedContinueLabel(value: SerializedContinueText, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val normal = MaterialTheme.typography.labelLarge
    val minimum = MaterialTheme.typography.bodySmall.fontSize
    BoxWithConstraints(modifier.testTag("serialized-continue-label").semantics { text = AnnotatedString(value.text) }) {
        val size = serializedContinueFontSize(value, normal.fontSize, minimum, constraints.maxWidth) { text, fontSize ->
            measurer.measure(text, normal.copy(fontSize = fontSize), softWrap = false).size.width
        }
        val style = normal.copy(fontSize = size)
        Layout(content = {
            Text(value.verb, style = style, maxLines = 1, softWrap = false)
            if (value.chapter != null) {
                Text(" · ", style = style, maxLines = 1, softWrap = false)
                Text(value.chapter, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            value.pageSuffix?.let {
                Text(it, Modifier.testTag("serialized-continue-page"), style = style, maxLines = 1, softWrap = false)
            }
        }) { measurables, labelConstraints ->
            val natural = labelConstraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity)
            // Text's semantics reconstruct a MultiParagraph from these constraints.
            // Infinity makes that paragraph wider than the drawn natural-width run.
            fun fixedRun(index: Int) = measurables[index].let { run ->
                run.measure(serializedContinueFixedRunConstraints(
                    labelConstraints, run.maxIntrinsicWidth(Constraints.Infinity),
                ))
            }
            val suffix = if (value.pageSuffix != null) fixedRun(measurables.lastIndex) else null
            val verb = fixedRun(0)
            val separator = if (value.chapter != null) fixedRun(1) else null
            val fixedWidth = verb.width + (separator?.width ?: 0) + (suffix?.width ?: 0)
            val chapter = if (value.chapter != null) measurables[2].measure(
                natural.copy(maxWidth = (labelConstraints.maxWidth - fixedWidth).coerceAtLeast(0)),
            ) else null
            val runs = listOfNotNull(verb, separator, chapter, suffix)
            layout(labelConstraints.constrainWidth(runs.sumOf { it.width }),
                labelConstraints.constrainHeight(runs.maxOf { it.height })) {
                var x = 0
                runs.forEach { run -> run.placeRelative(x, 0); x += run.width }
            }
        }
    }
}

/** Put the switch on a trailing row if it would crowd the fixed Continue text. */
@Composable
internal fun SerializedDetailActionRow(
    value: SerializedContinueText,
    enabled: Boolean,
    onRead: () -> Unit,
    switches: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val fixedWidth = listOfNotNull(value.verb, value.chapter?.let { " · " }, value.pageSuffix)
        .sumOf { measurer.measure(it, style, softWrap = false).size.width }
    SubcomposeLayout(modifier) { constraints ->
        val gap = 10.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val switch = subcompose("switch") { switches?.invoke() }.map { it.measure(loose) }
        val switchWidth = switch.sumOf { it.width }
        val padding = ButtonDefaults.ContentPadding.calculateLeftPadding(layoutDirection).roundToPx() +
            ButtonDefaults.ContentPadding.calculateRightPadding(layoutDirection).roundToPx()
        val stacked = switchWidth > 0 && fixedWidth + padding + gap + switchWidth > constraints.maxWidth
        val buttonWidth = if (stacked || switchWidth == 0) constraints.maxWidth else constraints.maxWidth - switchWidth - gap
        val button = subcompose("read") {
            PclButton(onClick = onRead, enabled = enabled, modifier = Modifier.testTag("remote-read")) {
                SerializedContinueLabel(value)
            }
        }.single().measure(Constraints.fixedWidth(buttonWidth))
        val switchHeight = switch.maxOfOrNull { it.height } ?: 0
        val height = if (stacked) button.height + gap + switchHeight else maxOf(button.height, switchHeight)
        layout(constraints.maxWidth, height) {
            button.placeRelative(0, if (stacked) 0 else (height - button.height) / 2)
            var x = constraints.maxWidth - switchWidth
            switch.forEach { it.placeRelative(x, if (stacked) button.height + gap else (height - it.height) / 2); x += it.width }
        }
    }
}
