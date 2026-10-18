package com.easemic.app.feature.history

import com.easemic.app.feature.browse.CloudCollectionFeedMapper
import com.easemic.app.feature.browse.CloudThumbCache
import java.io.File

/**
 * Cache-cover convention shared with the Cloud shelf's cover fill in
 * `PrivateComicLibraryApp` (`coverUrlFromCacheOrNull`): [CloudThumbCache.coverFile]
 * (`<cacheDir>/cloud-thumbs/<id>-cover.bin`, the full-res page-0 body — never
 * the strip's ordinal-0 cell, C5 audit BLOCKING-1).
 */
fun cloudThumbCachePath(cacheDir: File, cloudComicId: String): String =
    CloudThumbCache.coverFile(CloudThumbCache.root(cacheDir), cloudComicId).path

/**
 * Durable-cover convention shared with AppGraph's ingest/hydration cover
 * writers (`cloudCoversRoot`) and the shelf's fallback fill in
 * `PrivateComicLibraryApp` (`File(File(context.filesDir, "cloud-covers"), "<id>.jpg")`).
 * Used by the History host when the mirror has not (yet) emitted a
 * `coverPath` for this id — cold start before the first Room emission, or a
 * pruned/discarded card whose file still exists on disk.
 */
fun cloudDurableCoverPath(filesDir: File, cloudComicId: String): String =
    File(File(filesDir, "cloud-covers"), "$cloudComicId.jpg").path

/**
 * A21 (owner report "[MM] Yuutousei" — cloud History row renders the grey
 * placeholder): a History row's cover for a cloud comic is resolved from its
 * id at RENDER time, never from the `coverUrl` snapshot taken when the visit
 * was recorded. That snapshot (`openRemoteDetail` → `coverUrlFromCacheOrNull`)
 * was null whenever the thumb had not been cached yet, and stayed null.
 *
 * Fallback order for a cloud row (`acquisitionUrl` = `cloud:<id>`):
 * 1. the S4-T1 durable cover ([durableCoverPath] = `CloudCollectionCard.coverPath`
 *    or the [cloudDurableCoverPath] convention),
 * 2. the session cache thumb ([cacheThumbPath], see [cloudThumbCachePath]),
 * 3. null → placeholder.
 * A non-cloud row keeps its own snapshot unchanged. [fileExists] is injected
 * so this stays a pure, JVM-testable decision with no File I/O of its own.
 */
fun historyRowCoverUrl(
    entry: HistoryEntry,
    durableCoverPath: String?,
    cacheThumbPath: String?,
    fileExists: (String) -> Boolean,
): String? {
    if (CloudCollectionFeedMapper.parseCloudComicId(entry.acquisitionUrl) == null) return entry.coverUrl
    val durable = durableCoverPath?.takeIf(fileExists)
    if (durable != null) return File(durable).toURI().toString()
    val cached = cacheThumbPath?.takeIf(fileExists)
    if (cached != null) return File(cached).toURI().toString()
    return null
}
