package com.easemic.app.importing.metadata

data class EhViewerMarker(
    val format: MarkerFormat,
    val galleryId: Long,
    val expectedPageCount: Int,
)

enum class MarkerFormat { LEGACY_V2, FOOIBAR_CBOR }

sealed interface MarkerParseResult {
    data class Supported(val marker: EhViewerMarker, val warnings: Set<String>) : MarkerParseResult
    data class Unsupported(val diagnostic: String) : MarkerParseResult
    data class Malformed(val diagnostic: String) : MarkerParseResult
}

interface EhViewerMarkerParser {
    fun parse(bytes: ByteArray): MarkerParseResult
}

class CompatibleEhViewerMarkerParser(
    private val legacy: EhViewerMarkerParser = LegacyEhViewerMarkerParser(),
    private val cbor: EhViewerMarkerParser = CborEhViewerMarkerParser(),
) : EhViewerMarkerParser {
    override fun parse(bytes: ByteArray): MarkerParseResult {
        val legacyResult = legacy.parse(bytes)
        if (legacyResult is MarkerParseResult.Supported) return legacyResult
        val cborResult = cbor.parse(bytes)
        return if (cborResult is MarkerParseResult.Supported) cborResult else legacyResult
    }
}
