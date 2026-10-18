package com.easemic.app.feature.browse

/**
 * #E116: Pure parser for gallery titles that pack release metadata into
 * leading/trailing brackets (e.g. `3635461-[Circle (Artist)] Title [Chinese] [Digital]`).
 *
 * Extracts:
 * - [cleanTitle]: the human-readable core title without leading numeric gallery ID,
 *   convention prefixes `(C83)` / `(COMIC1☆15)`, leading `[Circle (Artist)]`, or
 *   trailing release/language noise brackets (`[Chinese]`, `[Digital]`, `[DL版]`, etc.).
 *   Falls back to the trimmed original title if stripping would leave an empty string.
 * - [authorOrCircle]: formatted as `Artist (Circle)` when nested, or `Author` when flat.
 * - [languageTag]: normalized language label (`Chinese`, `English`, `Japanese`, `Korean`, `Russian`, `Spanish`)
 *   when a language bracket is present.
 */
data class CleanedComicTitle(
    val cleanTitle: String,
    val authorOrCircle: String? = null,
    val languageTag: String? = null,
)

object ComicTitleCleaner {
    private val LEADING_GALLERY_ID_PREFIX = Regex("""^\d{4,9}\s*-\s*(?=[\[(])""")
    private val LEADING_PAREN_EVENT = Regex("""^\(([^()]{1,40})\)\s*""")
    private val LEADING_BRACKET_AUTHOR = Regex("""^\[([^\[\]]{1,80})]\s*""")
    private val NESTED_CIRCLE_ARTIST = Regex("""^([^()]+?)\s*\(([^()]+)\)$""")
    private val TRAILING_BRACKET = Regex("""\s*\[([^\[\]]{1,60})]\s*$""")
    private val TRAILING_PAREN_NOISE = Regex("""\s*\((?:Original\s+Work|Parody|Various|Ongoing|Complete)\)\s*$""", RegexOption.IGNORE_CASE)

    fun clean(rawTitle: String): CleanedComicTitle {
        val trimmed = rawTitle.trim()
        if (trimmed.isEmpty()) return CleanedComicTitle(cleanTitle = rawTitle)

        var working = trimmed.replaceFirst(LEADING_GALLERY_ID_PREFIX, "").trim()

        // Strip leading event tag(s) like (C83), (COMIC1☆10), (Reitaisai 14)
        while (true) {
            val match = LEADING_PAREN_EVENT.find(working) ?: break
            val after = working.substring(match.range.last + 1).trim()
            if (after.isEmpty()) break
            working = after
        }

        // Extract leading [Circle (Artist)] or [Artist] only if something remains after it
        var authorOrCircle: String? = null
        val leadingBracket = LEADING_BRACKET_AUTHOR.find(working)
        if (leadingBracket != null) {
            val inside = leadingBracket.groupValues[1].trim()
            val remainder = working.substring(leadingBracket.range.last + 1).trim()
            if (remainder.isNotEmpty() && classifyTrailingLanguage(inside) == null && !isReleaseNoiseBracket(inside)) {
                authorOrCircle = formatAuthorOrCircle(inside)
                working = remainder
            }
        }

        // Strip trailing brackets: capture language if seen, drop release/group noise
        var languageTag: String? = null
        while (true) {
            val match = TRAILING_BRACKET.find(working) ?: break
            val inside = match.groupValues[1].trim()
            val detectedLang = classifyTrailingLanguage(inside)
            if (detectedLang != null && languageTag == null) {
                languageTag = detectedLang
            }
            val before = working.substring(0, match.range.first).trim()
            if (before.isEmpty()) break
            working = before
        }

        working = working.replace(TRAILING_PAREN_NOISE, "").trim()
        val finalTitle = working.ifBlank { trimmed }
        return CleanedComicTitle(
            cleanTitle = finalTitle,
            authorOrCircle = authorOrCircle,
            languageTag = languageTag,
        )
    }

    private fun formatAuthorOrCircle(raw: String): String {
        val nested = NESTED_CIRCLE_ARTIST.matchEntire(raw)
        if (nested != null) {
            val circle = nested.groupValues[1].trim()
            val artist = nested.groupValues[2].trim()
            if (circle.isNotEmpty() && artist.isNotEmpty()) {
                return if (circle.equals(artist, ignoreCase = true)) artist else "$artist ($circle)"
            }
        }
        return raw
    }

    private fun classifyTrailingLanguage(token: String): String? {
        val lower = token.lowercase()
        return when {
            lower == "chinese" || lower == "cn" || lower == "zh" ||
                token.contains("中国") || token.contains("中文") || token.contains("汉化") ||
                token.contains("漢化") || token.contains("中国翻訳") || token.contains("中國翻譯") -> "Chinese"
            lower == "english" || lower == "eng" || lower == "en" ||
                token.contains("英訳") || token.contains("英译") -> "English"
            lower == "japanese" || lower == "jp" || lower == "ja" || token.contains("日語") || token.contains("日语") -> "Japanese"
            lower == "korean" || lower == "kr" || lower == "ko" || token.contains("韓国翻訳") || token.contains("韩漫") -> "Korean"
            lower == "russian" || lower == "ru" || token.contains("露訳") -> "Russian"
            lower == "spanish" || lower == "es" || token.contains("西訳") -> "Spanish"
            else -> null
        }
    }

    private fun isReleaseNoiseBracket(token: String): Boolean {
        val lower = token.lowercase()
        return lower == "digital" ||
            lower == "dl版" ||
            lower == "uncensored" ||
            lower == "decensored" ||
            lower == "无修正" ||
            lower == "無修正" ||
            lower == "colorized" ||
            lower == "full color" ||
            lower == "全彩"
    }
}
