package com.easemic.app.feature.collect

import com.easemic.app.feature.download.DownloadLocator
import com.easemic.app.sync.CloudComicMeta

/**
 * #C30: the cloud meta a sealed-local upload carries — the locator's own
 * facts, split the way
 * [com.easemic.app.feature.download.DownloadCommitMetadata.creators]
 * / `DetailSubject.Local.toFeedItem` split them, so a comic downloaded to the
 * device and then uploaded shows in the Cloud detail exactly what it shows in
 * the Local detail.
 *
 * Before this, [CloudIngestRunner] filled
 * [com.easemic.app.sync.CloudCollectionIngestRequest.preservedMeta]
 * ONLY on the r2-migration branch, so the ordinary sealed-local path wrote
 * `comics/<id>/meta.json` with EMPTY `tags`/`artists`/`groups`/`category`/
 * `language` even though the enqueue had already recorded all five in the
 * locator ([com.easemic.app.feature.download.putKeepingCatalogFacts]).
 *
 * Nothing is invented: [pageCount] and [CloudComicMeta.collectedAtEpochMs] are
 * the ingest's to fill (it overwrites both from the pages it actually
 * uploads), and a locator with no facts — pre-A32, or an OPDS entry, which
 * carries none — yields exactly the empty meta today's path writes.
 */
fun locatorCloudMeta(
    cloudComicId: String,
    providerId: String,
    acquisitionUrl: String,
    fallbackTitle: String,
    locator: DownloadLocator,
): CloudComicMeta {
    val artists = mutableListOf<String>()
    val groups = mutableListOf<String>()
    val tags = mutableListOf<String>()
    locator.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { raw ->
        val colon = raw.indexOf(':')
        val namespace = if (colon > 0) raw.substring(0, colon).trim().lowercase() else ""
        val label = if (colon > 0) raw.substring(colon + 1).trim() else raw
        when {
            namespace == "artist" && label.isNotEmpty() -> artists += label
            namespace == "group" && label.isNotEmpty() -> groups += label
            // A creator namespace with no label is not a creator; keeping it
            // verbatim as a tag loses nothing and invents nothing.
            else -> tags += raw
        }
    }
    return CloudComicMeta(
        cloudComicId = cloudComicId,
        title = locator.title?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle,
        tags = tags,
        // Review LOW-3: `DownloadCommitMetadata.creators()` distincts the
        // NORMALISED `namespace:label` strings, so two raw spellings that trim
        // to the same creator collapse there. The raw-tag `distinct()` above
        // cannot see that (the raw strings differ), so dedupe the labels here
        // to match — otherwise the duplicate rides into meta.json and the
        // Room mirror. Case stays significant, exactly as `creators()` keeps it.
        artists = artists.distinct(),
        groups = groups.distinct(),
        category = locator.category?.trim()?.takeIf { it.isNotEmpty() },
        language = locator.language?.trim()?.takeIf { it.isNotEmpty() },
        pageCount = 0,
        providerId = locator.browseSourceId ?: providerId,
        acquisitionRef = acquisitionUrl,
        collectedAtEpochMs = 0L,
    )
}
