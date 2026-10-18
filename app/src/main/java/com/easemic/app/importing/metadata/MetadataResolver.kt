package com.easemic.app.importing.metadata

import com.easemic.app.importing.importedTitle
import com.easemic.app.importing.importedTitleFrom
import com.easemic.app.model.MetadataSnapshot
import com.easemic.app.model.ParsedComicInfo

data class ResolvedMetadata(
    val metadata: MetadataSnapshot,
    val actualPageCount: Int,
    val expectedPageCount: Int?,
    val provenance: Map<String, String>,
    val warnings: Set<String>,
)

class MetadataResolver {
    fun resolve(
        displayName: String,
        comicInfo: ParsedComicInfo?,
        marker: MarkerParseResult?,
        readablePageCount: Int,
    ): ResolvedMetadata {
        require(readablePageCount >= 0)
        val warnings = linkedSetOf<String>()
        if (comicInfo?.warning == true) warnings += "COMIC_INFO_MALFORMED"

        val supportedMarker = marker as? MarkerParseResult.Supported
        when (marker) {
            is MarkerParseResult.Malformed -> warnings += "EHVIEWER_MARKER_MALFORMED"
            is MarkerParseResult.Unsupported -> warnings += "EHVIEWER_MARKER_UNSUPPORTED"
            else -> Unit
        }
        supportedMarker?.warnings?.let(warnings::addAll)
        val expectedPageCount = supportedMarker?.marker?.expectedPageCount
        if (expectedPageCount != null && expectedPageCount != readablePageCount) {
            warnings += "PAGE_COUNT_MISMATCH"
        }

        return ResolvedMetadata(
            metadata = comicInfo?.metadata
                ?.copy(title = importedTitle(displayName, comicInfo.metadata.title))
                ?: MetadataSnapshot.fallback(importedTitleFrom(displayName)),
            actualPageCount = readablePageCount,
            expectedPageCount = expectedPageCount,
            provenance = buildMap {
                supportedMarker?.marker?.let { put("ehviewer.gallery_id", it.galleryId.toString()) }
                // A46 (:795): `Web` → provenance. Downloads keep their URLs in
                // locators/acquisition keys, never in provenance_claims, so there is no
                // download-side web claim dialect to copy; `comicinfo` names the format
                // the URL came from and `web` the field, the same self-describing shape
                // as the ehviewer gallery_id claim above.
                comicInfo?.webUrl?.let { put("comicinfo.web", it) }
            },
            warnings = warnings,
        )
    }
}
