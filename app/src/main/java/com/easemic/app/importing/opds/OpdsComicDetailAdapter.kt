package com.easemic.app.importing.opds

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.detail.model.*

fun opdsComicDetail(item: BrowseFeedItem, context: DetailAdapterContext): ComicDetail = galleryDetail(item, context, feedAuthor = true)
