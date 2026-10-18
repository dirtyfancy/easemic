package com.easemic.app.feature.browse

data class ChapterCellLabel(val number: String?, val title: String, val word: String?)

private val unitLabel = Regex("^(第?[0-9]+(?:\\.[0-9]+)?\\s*[话話卷回章集])(.*)$")
private val numericLabel = Regex("^([0-9]+(?:\\.[0-9]+)?)(?:\\s+(.*))?$")

fun splitChapterCellLabel(label: String): ChapterCellLabel {
    val text = label.trim()
    val match = unitLabel.matchEntire(text) ?: numericLabel.matchEntire(text)
    return if (match == null) ChapterCellLabel(null, "", text)
    else ChapterCellLabel(match.groupValues[1], match.groupValues[2].trim(), null)
}
