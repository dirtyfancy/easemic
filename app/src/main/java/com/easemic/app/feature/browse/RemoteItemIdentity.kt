package com.easemic.app.feature.browse

/**
 * UI/list identity for a remote row. This is deliberately not a source id,
 * acquisition locator, ledger key, or persisted progress key.
 */
fun remoteItemKey(providerId: String, id: String): String =
    "remote-item:${providerId.length}:$providerId:${id.length}:$id"

fun sameRemoteItem(left: BrowseFeedItem?, right: BrowseFeedItem?): Boolean =
    left != null && right != null && remoteItemKey(left.providerId, left.id) == remoteItemKey(right.providerId, right.id)
