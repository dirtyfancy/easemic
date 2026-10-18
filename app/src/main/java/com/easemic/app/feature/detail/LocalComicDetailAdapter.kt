package com.easemic.app.feature.detail

import com.easemic.app.feature.browse.BrowseFeedItem
import com.easemic.app.feature.detail.model.*
import com.easemic.app.feature.source.DetailField

fun localComicDetail(subject: DetailSubject.Local, context: DetailAdapterContext): ComicDetail {
    val metadata = subject.metadata
    val imported = !subject.isCatalogDownload
    val catalogPeople = galleryDetailPeople(emptyList(), emptyList(), metadata.creators)
    val people = if (imported) metadata.creators.map(String::trim).filter(String::isNotEmpty).distinct()
        .map { DetailPerson(it, "creator", it) } else catalogPeople + metadata.creators.filter { raw ->
            !raw.startsWith("artist:", ignoreCase = true) && !raw.startsWith("group:", ignoreCase = true)
        }.map(String::trim).filter(String::isNotEmpty).map { DetailPerson(it, "creator", it) }
    val tags = if (imported) metadata.genres + metadata.tags else metadata.tags
    val actualContext = if (imported) context.copy(profile = DefaultSourceDetailProfile, sourceName = "On this phone") else context
    val base = galleryDetail(BrowseFeedItem(
        id = subject.item.id.value.toString(), title = metadata.title,
        pageCount = subject.item.pageCount, language = metadata.language,
        category = if (imported) null else metadata.genres.joinToString(" / ").takeIf(String::isNotEmpty), tags = tags,
    ), actualContext)
    val importCover = if (imported) subject.item.pages.firstOrNull()?.let { DetailCover(phone = DetailField.Value(DetailCoverRef.Page(it))) } else null
    return base.copy(people = DetailField.Value(people.distinct()),
        cover = DetailField.Value(context.covers.over(importCover ?: DetailCover())))
}
