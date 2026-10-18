package com.easemic.app.importing.ehentai

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.detail.model.*

fun ehentaiComicDetail(item: BrowseFeedItem, context: DetailAdapterContext): ComicDetail = galleryDetail(item, context).copy(
    date = galleryDate(item, context),
    numbers = observedDetailField(item.rating?.takeIf { it.isFinite() && it in 0f..5f }?.let {
        listOf(DetailNumber(DetailNumberKind.RATING, it.toString()))
    }.orEmpty(), context.observation),
)
