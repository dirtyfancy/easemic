package com.easemic.app.feature.download

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Stable series identity. Display titles, URLs, cookies, and locators are not identity. */
data class SeriesIdentityV2(
    val providerNamespace: String,
    val canonicalSeriesId: String,
)

/**
 * Stable local key for serialized-series ledger and cloud-id state.
 *
 * This key is derived only from the provider identity.  A series acquisition
 * URL is a locator and may change between devices, so it must never be used as
 * the ledger or cloud-id key, and this value must never be normalized through
 * [com.easemic.app.feature.library.galleryDedupKey].
 */
fun serializedSeriesLedgerKey(providerId: String, providerSeriesId: String): String {
    val provider = providerId.trim()
    val series = providerSeriesId.trim()
    require(provider.isNotEmpty()) { "providerId required" }
    require(series.isNotEmpty()) { "providerSeriesId required" }
    // Length delimiters keep the key safe for Preferences/XML while remaining
    // injective even when provider ids contain punctuation.
    return "serialized-series:${provider.length}:$provider:${series.length}:$series"
}

fun serializedSeriesLedgerKey(identity: SeriesIdentityV2): String =
    serializedSeriesLedgerKey(identity.providerNamespace, identity.canonicalSeriesId)

/** Stable provider chapter identity scoped by [series]. */
data class ChapterIdentityV2(
    val series: SeriesIdentityV2,
    val canonicalChapterId: String,
)

/** One queue/download unit: one series identity plus one provider chapter. */
data class DownloadIdentityV2(
    val series: SeriesIdentityV2,
    val chapter: ChapterIdentityV2,
)

object GalleryIdentityV2 {
    /** Only existing gallery/legacy rows use this synthetic chapter ID. */
    const val LEGACY_GALLERY_CHAPTER_ID: String = "legacy:0"

    /** Namespace prefix of [serializedChapterStorageIdentity]; never a provider namespace. */
    const val SERIALIZED_STORAGE_NAMESPACE_PREFIX: String = "serialized:"
}

/**
 * Stable app-owned locator for a serialized chapter before a source supplies a
 * real acquisition URL. The value is deliberately a URI-shaped key rather
 * than the series URL: source liveness must never mistake a live series for a
 * chapter that disappeared from its list.
 */
fun serializedChapterLocatorKey(
    series: SeriesIdentityV2,
    chapterId: String,
): String = buildString {
    append("serialized-chapter://")
    append(encodeLocatorSegment(series.providerNamespace))
    append('/')
    append(encodeLocatorSegment(series.canonicalSeriesId))
    append('/')
    append(encodeLocatorSegment(chapterId))
}

/**
 * Inverse of [serializedChapterLocatorKey]. Null for another scheme, a segment
 * count other than three, an empty segment, or a malformed `%` escape.
 */
fun parseSerializedChapterLocatorKey(value: String): Pair<SeriesIdentityV2, String>? {
    val prefix = "serialized-chapter://"
    if (!value.startsWith(prefix)) return null
    val parts = value.substring(prefix.length).split('/')
    if (parts.size != 3) return null
    val decoded = parts.map { decodeLocatorSegment(it) ?: return null }
    if (decoded.any { it.isEmpty() }) return null
    return SeriesIdentityV2(decoded[0], decoded[1]) to decoded[2]
}

private fun decodeLocatorSegment(segment: String): String? {
    if (segment.isEmpty()) return null
    val bytes = ByteArrayOutputStream(segment.length)
    var index = 0
    while (index < segment.length) {
        val character = segment[index]
        if (character == '%') {
            if (index + 2 >= segment.length) return null
            val hex = segment.substring(index + 1, index + 3)
            val value = hex.toIntOrNull(16) ?: return null
            bytes.write(value)
            index += 3
        } else {
            if (character.code > 0x7f) return null
            bytes.write(character.code)
            index += 1
        }
    }
    return try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

private fun encodeLocatorSegment(value: String): String = buildString {
    value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        val character = unsigned.toChar()
        if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
            character == '-' || character == '.' || character == '_' || character == '~'
        ) {
            append(character)
        } else {
            append('%')
            append("0123456789ABCDEF"[unsigned ushr 4])
            append("0123456789ABCDEF"[unsigned and 0x0f])
        }
    }
}

/** The V1 key a serialized chapter's page adapter receives. */
fun serializedChapterAdapterIdentity(
    series: SeriesIdentityV2,
    chapterId: String,
): GalleryIdentityV1 = GalleryIdentityV1(
    providerNamespace = series.providerNamespace,
    canonicalItemId = series.canonicalSeriesId + "\u0000" + chapterId,
)

/**
 * The V1 key serialized chapter bytes use for locators, READY pages, and
 * catalog trees. The digest keeps provider identifiers out of filenames.
 */
fun serializedChapterStorageIdentity(
    series: SeriesIdentityV2,
    chapterId: String,
): GalleryIdentityV1 {
    val bytes = (series.canonicalSeriesId + "\u0000" + chapterId)
        .toByteArray(StandardCharsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return GalleryIdentityV1(
        providerNamespace = GalleryIdentityV2.SERIALIZED_STORAGE_NAMESPACE_PREFIX + series.providerNamespace,
        canonicalItemId = digest.take(32),
    )
}

fun GalleryIdentityV1.asSeriesIdentityV2(): SeriesIdentityV2 =
    SeriesIdentityV2(
        providerNamespace = providerNamespace,
        canonicalSeriesId = canonicalItemId,
    )

fun GalleryIdentityV1.asChapterIdentityV2(): ChapterIdentityV2 {
    val series = asSeriesIdentityV2()
    return ChapterIdentityV2(
        series = series,
        canonicalChapterId = GalleryIdentityV2.LEGACY_GALLERY_CHAPTER_ID,
    )
}

fun GalleryIdentityV1.asDownloadIdentityV2(): DownloadIdentityV2 {
    val chapter = asChapterIdentityV2()
    return DownloadIdentityV2(series = chapter.series, chapter = chapter)
}
