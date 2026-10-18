package com.easemic.app.feature.browse

import com.easemic.app.feature.download.DownloadLocator

/**
 * A32 §2 — the one way a catalog row becomes a [DownloadLocator].
 *
 * The locator is the only carrier of a gallery's catalog facts between the tap
 * that enqueues it and the FINISH that writes the Library row, and the locator
 * store is last-write-wins per identity: a second path that rebuilds the
 * locator without these fields (Collect, after a download was already
 * enqueued) silently erases them. Building it in one place is what keeps the
 * download path and the collect path from disagreeing about the same comic.
 *
 * Nothing is invented — every field is the row's own value, dropped when the
 * row does not have it.
 */
fun BrowseFeedItem.toDownloadLocator(acquisitionUrl: String): DownloadLocator = DownloadLocator(
    acquisitionUrl = acquisitionUrl,
    title = title,
    coverUrl = coverUrl,
    // Both parsers route creators OUT of `tags` into artists/groups and keep
    // every other tag as namespace:label, including language edition tags.
    // Re-attaching the namespace here is what lets the commit lift them back
    // out as creators (DownloadCommitMetadata.creators). The detail Tag
    // projection also retains these namespaces, so the source's complete Tag
    // set remains visible without losing creator identity.
    tags = locatorCatalogTags(),
    language = language,
    category = category,
    feedUrl = feedUrl,
    browseSourceId = providerId.takeIf { it.isNotBlank() && it != com.easemic.app.feature.providers.CloudCollectionSource.SOURCE_ID },
)

/**
 * The raw `namespace:label` tag list a locator carries for this row — the one
 * place a feed item's catalog tags are assembled. Every locator writer that has
 * a feed item in hand must go through it (the upstream download builder above
 * and the cloud-routed branch's [
 * com.easemic.app.feature.download.CloudLocalDownloadIdentity.locator]),
 * because passing `item.tags` raw drops the creators the parsers and
 * `CloudCollectionFeedMapper` keep in the dedicated artists/groups fields.
 */
fun BrowseFeedItem.locatorCatalogTags(): List<String> =
    artists.namespacedAs("artist") + groups.namespacedAs("group") +
        tags.map { it.trim() }.filter { it.isNotEmpty() }

/** Already-namespaced values are left alone rather than namespaced twice. */
private fun List<String>.namespacedAs(namespace: String): List<String> = mapNotNull { raw ->
    val value = raw.trim()
    when {
        value.isEmpty() -> null
        value.substringBefore(':', "").trim().lowercase() == namespace ->
            value.substringAfter(':').trim().takeIf { it.isNotEmpty() }?.let { "$namespace:$it" }
        else -> "$namespace:$value"
    }
}
