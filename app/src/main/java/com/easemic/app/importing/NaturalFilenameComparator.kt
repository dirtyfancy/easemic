package com.easemic.app.importing

import com.easemic.app.model.DocumentRef

object NaturalFilenameComparator : Comparator<String> {
    override fun compare(left: String, right: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftChar = left[leftIndex]
            val rightChar = right[rightIndex]
            if (leftChar.isDigit() && rightChar.isDigit()) {
                val leftEnd = leftIndex + left.substring(leftIndex).takeWhile(Char::isDigit).length
                val rightEnd = rightIndex + right.substring(rightIndex).takeWhile(Char::isDigit).length
                val leftDigits = left.substring(leftIndex, leftEnd)
                val rightDigits = right.substring(rightIndex, rightEnd)
                val leftValue = leftDigits.trimStart('0').ifEmpty { "0" }
                val rightValue = rightDigits.trimStart('0').ifEmpty { "0" }
                val numeric = leftValue.length.compareTo(rightValue.length).takeIf { it != 0 }
                    ?: leftValue.compareTo(rightValue)
                if (numeric != 0) return numeric
                val zeroPadding = leftDigits.length.compareTo(rightDigits.length)
                if (zeroPadding != 0) return zeroPadding
                leftIndex = leftEnd
                rightIndex = rightEnd
            } else {
                val comparison = leftChar.lowercaseChar().compareTo(rightChar.lowercaseChar())
                if (comparison != 0) return comparison
                leftIndex += 1
                rightIndex += 1
            }
        }
        return left.length.compareTo(right.length)
    }
}

fun isSupportedImageFilename(displayName: String): Boolean =
    displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase() in
        setOf("jpg", "jpeg", "png", "gif", "webp")

val documentNaturalComparator: Comparator<DocumentRef> =
    Comparator { left, right ->
        NaturalFilenameComparator.compare(left.displayName, right.displayName)
            .takeIf { it != 0 }
            ?: left.documentId.compareTo(right.documentId)
    }
