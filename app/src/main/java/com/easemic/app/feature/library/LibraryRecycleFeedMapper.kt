package com.easemic.app.feature.library

import com.easemic.app.feature.bridge.RecentlyDeletedCardCopy
import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.browse.CloudCollectionFeedMapper
import com.easemic.app.sync.CloudCollectionCard

/**
 * Task 14 (browse-remaining §4): the Library Recycle segment is retired; this
 * mapper now feeds the Recently deleted card on the Cloud storage settings
 * screen. Rows are newest-first trash cards whose secondary line is the
 * deletion age (`Deleted <relative time>`).
 */
object LibraryRecycleFeedMapper {
    fun mapCards(
        cards: List<CloudCollectionCard>,
        nowEpochMs: Long,
        coverUrlFor: (CloudCollectionCard) -> String? = { null },
    ): List<BrowseFeedItem> {
        return cards
            .sortedWith(
                compareByDescending<CloudCollectionCard> { it.trashedAtEpochMs ?: Long.MIN_VALUE },
            )
            .map { card ->
                CloudCollectionFeedMapper.mapCard(card, coverUrl = coverUrlFor(card)).copy(
                    secondaryLine = RecentlyDeletedCardCopy.deletedLine(
                        trashedAtEpochMs = card.trashedAtEpochMs,
                        nowEpochMs = nowEpochMs,
                    ),
                )
            }
    }
}
