package com.easemic.app.feature.download

/**
 * #C18: an OPDS `canonicalItemId` is the WHOLE acquisition URL, and Kavita
 * percent-encodes a CJK title at 9 bytes per character, so one Chinese-titled
 * chapter produces a path segment past the 255-BYTE filesystem limit.
 *
 * THREE stores derive a file or directory name from that id, and every one of
 * them broke on the same comic:
 *  - [FileDownloadLocatorStore] wrote `<id>.locator` — `ENAMETOOLONG` escaped
 *    the Collect coroutine and killed the process.
 *  - [ReadyPageStore] created `download-ready-pages/<ns>/<id>/r<rev>/` — the
 *    page write failed, so the job burned its retries and ended
 *    `JITTER_EXHAUSTED` instead of crashing. Quieter, equally fatal.
 *  - `defaultDownloadLocationUri` builds `<ns>-downloads/<id>/r<rev>` — the
 *    shelf copy, which no long-id comic ever reached because the ready-page
 *    write above failed first.
 *
 * Fixing only the crashing site left the feature just as broken, so the rule
 * lives here once rather than in each store.
 */
internal object BoundedPathSegment {
    /**
     * Deliberately well under 255: some Android external volumes are
     * FAT-derived with shorter limits, and callers append a suffix such as
     * `.locator`.
     */
    const val MAX_NAME_BYTES = 160

    /** SHA-256 hex is 64 chars; keep enough prefix to stay human-readable. */
    private const val DIGEST_HEX_LENGTH = 64

    /**
     * Byte-bounded, collision-free, and deterministic across processes — a
     * later read must land on the path an earlier write produced, including
     * after restart.
     *
     * A segment already within budget is returned VERBATIM, so every path an
     * earlier build wrote keeps its location and nothing needs migrating. Only
     * an over-long segment degrades, to `<prefix>.<sha256 of the whole
     * segment>`: the digest covers the entire string, so two OPDS chapters
     * under one series that differ only in their last few characters still get
     * distinct paths. Plain truncation would have handed one comic the other's
     * pages.
     */
    fun of(raw: String): String {
        if (raw.toByteArray().size <= MAX_NAME_BYTES) return raw
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
        // Reserve the digest and its separator, then fill the rest with as much
        // of the prefix as fits WITHOUT splitting a code point (a supplementary
        // character is two UTF-16 units and four UTF-8 bytes; both are counted
        // whole). #C18 re-review item 3: a per-Char walk encoded each surrogate
        // half as '?' (1 byte), so the budget under-counted 2x on such text and
        // the prefix could end on half a code point.
        val prefixBudget = MAX_NAME_BYTES - DIGEST_HEX_LENGTH - 1
        val prefix = StringBuilder()
        var used = 0
        var index = 0
        while (index < raw.length) {
            val codePoint = raw.codePointAt(index)
            val piece = String(Character.toChars(codePoint))
            val width = piece.toByteArray().size // real UTF-8 width, 1..4
            if (used + width > prefixBudget) break
            prefix.append(piece)
            used += width
            index += Character.charCount(codePoint)
        }
        return "$prefix.$digest"
    }
}
