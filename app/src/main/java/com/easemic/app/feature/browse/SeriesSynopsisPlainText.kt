package com.easemic.app.feature.browse

import androidx.core.text.HtmlCompat

internal fun seriesSynopsisPlainText(
    raw: String?,
    decodeHtml: (String) -> String = {
        HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    },
): String? {
    val input = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val withoutExecutableText = input
        .replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>"), "")
        .replace(Regex("(?is)<img\\b[^>]*>"), "")
    val hasMarkup = Regex("<[/!]?[A-Za-z][^>]*>").containsMatchIn(withoutExecutableText)
    val normalized = if (hasMarkup || '&' in withoutExecutableText) {
        val html = if (hasMarkup) {
            // Html collapses literal newlines as whitespace. Keep block breaks
            // as elements until decoding, then normalize the resulting text.
            withoutExecutableText.replace(
                Regex("(?i)</?(p|div|li|h[1-6])\\b[^>]*>"),
                "<br><br>",
            )
        } else {
            withoutExecutableText.replace("\r\n", "\n").replace("\n", "<br>")
        }
        decodeHtml(html)
    } else {
        withoutExecutableText
    }
    return normalized.replace("\r\n", "\n").replace('\u00a0', ' ')
        .lines().joinToString("\n") { it.trimEnd() }
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
        .takeIf { it.isNotEmpty() }
}
