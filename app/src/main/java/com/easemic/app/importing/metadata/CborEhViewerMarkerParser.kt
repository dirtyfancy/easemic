package com.easemic.app.importing.metadata

class CborEhViewerMarkerParser : EhViewerMarkerParser {
    override fun parse(bytes: ByteArray): MarkerParseResult {
        if (bytes.size > MAX_INPUT_BYTES) return MarkerParseResult.Malformed("CBOR_INPUT_LIMIT")
        return try {
            val entries = BoundedCborReader(bytes).readMap().entries
            val duplicateRequired = REQUIRED_KEYS.any { required ->
                entries.count { it.first == required } > 1
            }
            if (duplicateRequired) return MarkerParseResult.Malformed("CBOR_DUPLICATE_KEY")
            val values = entries.toMap()
            if (!entries.all { (key, value) -> value.hasRegisteredShape(key) }) {
                return MarkerParseResult.Unsupported("CBOR_SHAPE")
            }
            val warnings = entries.asSequence()
                .map { it.first }
                .filterNot { it in RECOGNIZED_KEYS }
                .mapTo(linkedSetOf()) { "CBOR_UNKNOWN_KEY" }
            val galleryId = (values["gid"] as? BoundedCborReader.Value.Unsigned)?.value
                ?.takeIf { it > 0 } ?: return MarkerParseResult.Malformed("CBOR_GALLERY_ID")
            val pages = (values["pages"] as? BoundedCborReader.Value.Unsigned)?.value
                ?.takeIf { it <= MAX_PAGE_COUNT }?.toInt() ?: return MarkerParseResult.Malformed("CBOR_PAGE_COUNT")
            MarkerParseResult.Supported(EhViewerMarker(MarkerFormat.FOOIBAR_CBOR, galleryId, pages), warnings)
        } catch (error: BoundedCborReader.CborException) {
            if (error.unsupported) MarkerParseResult.Unsupported(error.code) else MarkerParseResult.Malformed(error.code)
        }
    }

    private companion object {
        const val MAX_INPUT_BYTES = 1_048_576
        const val MAX_PAGE_COUNT = 100_000L
        val REQUIRED_KEYS = setOf("gid", "pages")
        val RECOGNIZED_KEYS = REQUIRED_KEYS + setOf(
            "token",
            "page-token",
            "preview-per-page",
        )
    }

    private fun BoundedCborReader.Value.hasRegisteredShape(key: String): Boolean =
        when (key) {
            "gid", "pages", "preview-per-page" ->
                this is BoundedCborReader.Value.Unsigned ||
                    this is BoundedCborReader.Value.Negative
            "token" -> this is BoundedCborReader.Value.Text
            "page-token" -> this is BoundedCborReader.Value.MapValue &&
                entries.all { (page, token) ->
                    page.toLongOrNull()?.takeIf { it >= 0 } != null &&
                        token is BoundedCborReader.Value.Text
                }
            else -> this is BoundedCborReader.Value.Unsigned ||
                this is BoundedCborReader.Value.Text ||
                this is BoundedCborReader.Value.Negative
        }
}
