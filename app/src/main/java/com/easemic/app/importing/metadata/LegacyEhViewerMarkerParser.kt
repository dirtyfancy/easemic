package com.easemic.app.importing.metadata

class LegacyEhViewerMarkerParser : EhViewerMarkerParser {
    override fun parse(bytes: ByteArray): MarkerParseResult {
        if (bytes.size > MAX_INPUT_BYTES) return MarkerParseResult.Malformed("LEGACY_INPUT_LIMIT")
        val text = bytes.decodeUtf8OrNull() ?: return MarkerParseResult.Malformed("LEGACY_UTF8")
        val fields = mutableListOf<String>()
        val warnings = linkedSetOf<String>()
        val lines = text.split('\n')
        if (lines.size > MAX_LINES) return MarkerParseResult.Malformed("LEGACY_LINE_COUNT")
        for (rawLine in lines) {
            val line = rawLine.removeSuffix("\r")
            if (line.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) {
                return MarkerParseResult.Malformed("LEGACY_LINE_LIMIT")
            }
            if (line.isBlank()) continue
            if (fields.size < REQUIRED_FIELDS) {
                fields += line
            } else {
                if (PAGE_TOKEN_RECORD.matches(line)) {
                    Unit
                } else {
                    warnings += "LEGACY_UNKNOWN_LINE"
                }
            }
        }

        if (fields.size < REQUIRED_FIELDS) return MarkerParseResult.Malformed("LEGACY_REQUIRED_FIELDS")
        if (!isSupportedLegacyVersion(fields[VERSION])) {
            return MarkerParseResult.Unsupported("LEGACY_VERSION")
        }
        if (parseNonNegativeField(fields[START_PAGE]) == null) {
            return MarkerParseResult.Malformed("LEGACY_STRUCTURE")
        }
        val galleryId = fields[GALLERY_ID].parsePositiveLong() ?: return MarkerParseResult.Malformed("LEGACY_GALLERY_ID")
        if (parseNonNegativeField(fields[MODE]) == null) {
            return MarkerParseResult.Malformed("LEGACY_STRUCTURE")
        }
        if (parseNonNegativeField(fields[PREVIEW_PER_PAGE]) == null) {
            return MarkerParseResult.Malformed("LEGACY_STRUCTURE")
        }
        val pageCount = fields[PAGE_COUNT].parsePageCount() ?: return MarkerParseResult.Malformed("LEGACY_PAGE_COUNT")
        return MarkerParseResult.Supported(
            EhViewerMarker(MarkerFormat.LEGACY_V2, galleryId, pageCount),
            warnings,
        )
    }

    private fun ByteArray.decodeUtf8OrNull(): String? =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(this))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }

    private fun String?.parsePositiveLong(): Long? =
        this?.toLongOrNull()?.takeIf { it > 0 }

    private fun String?.parsePageCount(): Int? =
        this?.toLongOrNull()?.takeIf { it in 0..MAX_PAGE_COUNT }?.toInt()

    private companion object {
        const val MAX_INPUT_BYTES = 64 * 1024
        const val MAX_LINES = 4_096
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_PAGE_COUNT = 100_000
        const val REQUIRED_FIELDS = 8
        const val VERSION = 0
        const val START_PAGE = 1
        const val GALLERY_ID = 2
        const val MODE = 4
        const val PREVIEW_PER_PAGE = 6
        const val PAGE_COUNT = 7
        val PAGE_TOKEN_RECORD = Regex("\\d+\\s+\\S+")

        fun isSupportedLegacyVersion(raw: String): Boolean {
            if (raw == "2" || raw == "VERSION2") return true
            if (!raw.startsWith("VERSION")) return false
            return raw.removePrefix("VERSION").toIntOrNull() == 2
        }

        fun parseNonNegativeField(raw: String): Long? =
            raw.toLongOrNull()?.takeIf { it >= 0 }
                ?: raw.toLongOrNull(16)?.takeIf { it >= 0 }
    }
}
