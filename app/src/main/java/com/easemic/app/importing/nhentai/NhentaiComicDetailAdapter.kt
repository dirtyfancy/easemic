package com.easemic.app.importing.nhentai

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.detail.model.*

fun nhentaiComicDetail(item: BrowseFeedItem, context: DetailAdapterContext): ComicDetail = galleryDetail(item, context).copy(
    date = galleryDate(item, context),
    numbers = observedDetailField(item.favorites?.takeIf { it >= 0 }?.let {
        listOf(DetailNumber(DetailNumberKind.FAVOURITES, it.toString()))
    }.orEmpty(), context.observation),
)
