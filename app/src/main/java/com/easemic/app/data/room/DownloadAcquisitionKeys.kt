package com.easemic.app.data.room

import com.easemic.app.feature.download.acquisitionKey
import com.easemic.app.feature.download.parseDownloadOwnershipKey
import com.easemic.app.feature.download.parseSerializedOwnershipKey
import com.easemic.app.feature.download.serializedSeriesLedgerKey

/**
 * S2 Task 2 (item 1): pure map from persisted download ownership claim values
 * (itemId → `download:v1:<ns>:<canonicalItemId>`) to the token-free acquisition
 * key (`<ns>://<canonicalItemId>`) the [com.easemic.app.feature.library.LibraryShelfComposer]
 * dedups local items on. Entries whose claim value fails to parse are dropped so
 * a corrupt provenance row degrades dedup gracefully instead of poisoning it.
 */
fun downloadAcquisitionKeysFrom(ownershipClaimValues: Map<Long, String>): Map<Long, String> =
    ownershipClaimValues.mapNotNull { (itemId, ownership) ->
        (parseDownloadOwnershipKey(ownership) ?: parseSerializedOwnershipKey(ownership))
            ?.acquisitionKey()?.let { itemId to it }
    }.toMap()

/**
 * Serialized-series identities owned by local download rows, keyed by item ID.
 *
 * A series acquisition is a locator and may change between source refreshes;
 * the shelf therefore needs the provider identity from the ownership claim,
 * not a guess parsed out of that locator. Gallery claims are intentionally
 * excluded by [parseSerializedOwnershipKey].
 */
fun serializedSeriesLedgerKeysFrom(ownershipClaimValues: Map<Long, String>): Map<Long, String> =
    ownershipClaimValues.mapNotNull { (itemId, ownership) ->
        parseSerializedOwnershipKey(ownership)?.let { identity ->
            itemId to serializedSeriesLedgerKey(
                identity.providerNamespace,
                identity.canonicalItemId,
            )
        }
    }.toMap()
