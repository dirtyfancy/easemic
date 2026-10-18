package com.easemic.app.importing

/**
 * Decodes HTML character references in text scraped from a source page — one pass, so `&amp;quot;` becomes the
 * literal `&quot;`; unknown names are left as-is. #D4: E-Hentai titles showed `&quot;`, nhentai `&#128052;`.
 */
object HtmlText {
    private val REFERENCE = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")
    private val NAMED = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
    )

    fun decode(raw: String): String {
        if ('&' !in raw) return raw
        return REFERENCE.replace(raw) { match ->
            val body = match.groupValues[1]
            val codePoint = when {
                body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)
                body.startsWith("#") -> body.substring(1).toIntOrNull()
                else -> null
            }
            when {
                codePoint != null && Character.isValidCodePoint(codePoint) -> String(Character.toChars(codePoint))
                codePoint == null -> NAMED[body] ?: match.value
                else -> match.value
            }
        }
    }
}
