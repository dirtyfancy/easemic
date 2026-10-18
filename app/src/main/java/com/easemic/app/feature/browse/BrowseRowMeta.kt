package com.easemic.app.feature.browse

import com.easemic.app.i18n.tr

import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.providers.ExhentaiEnableStore
import com.easemic.app.feature.providers.NhentaiEnableStore
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Structured per-source subline model for the single-source Browse gallery list.
 * The row templates are deliberately source-shaped because each feed's field
 * density differs (see docs/research/2026-09-01-browse-list-row-metadata.md):
 *
 * - E-Hentai: category dot + category name, ★ rating, posted age.
 * - nhentai: pages and favorites, nothing else is available.
 * - Zaimanhua: status dot + status ("连载中" / "已完结"), authors, relative update age.
 * - JM: category dot + category ("韓漫" / "單本" / "同人" / ...), authors, relative update age.
 * - OPDS: author (fallback publisher), page count rides the thumbnail badge.
 *
 * Missing fields are omitted entirely — there are no placeholder tokens.
 */
sealed interface BrowseRowMeta {
    /** Subline segments, each already formatted for display. */
    fun sublineParts(): List<Part>

    data class Part(
        val text: String,
        /** Whether this segment renders with the leading category colour dot. */
        val showDot: Boolean = false,
    )

    data class Eh(
        val category: String?,
        val rating: Float?,
        val postedAge: String?,
        /** #D4: the gallery language (EhViewer's list rows show it too). */
        val language: String? = null,
    ) : BrowseRowMeta {
        override fun sublineParts(): List<Part> = buildList {
            category?.takeIf { it.isNotBlank() }?.let { add(Part(it, showDot = true)) }
            rating?.let { add(Part("★" + formatRating(it))) }
            language?.takeIf { it.isNotBlank() }?.let { add(Part(it)) }
            postedAge?.takeIf { it.isNotBlank() }?.let { add(Part(it)) }
        }
    }

    data class Nh(
        val pages: Int?,
        val favorites: Int?,
    ) : BrowseRowMeta {
        override fun sublineParts(): List<Part> = buildList {
            pages?.let { add(Part("$it pages")) }
            favorites?.takeIf { it > 0 }?.let { add(Part("❤ " + formatCount(it))) }
        }
    }

    data class Zaimanhua(
        val status: String?,
        val authors: String?,
        val postedAge: String?,
    ) : BrowseRowMeta {
        override fun sublineParts(): List<Part> = buildList {
            status?.takeIf { it.isNotBlank() }?.let { add(Part(it, showDot = true)) }
            authors?.takeIf { it.isNotBlank() }?.let { add(Part(it)) }
            postedAge?.takeIf { it.isNotBlank() }?.let { add(Part(it)) }
        }
    }

    data class Jm(
        val category: String?,
        val authors: String?,
        val postedAge: String?,
    ) : BrowseRowMeta {
        override fun sublineParts(): List<Part> = buildList {
            category?.takeIf { it.isNotBlank() }?.let { add(Part(it, showDot = true)) }
            authors?.takeIf { it.isNotBlank() }?.let { add(Part(it)) }
            postedAge?.takeIf { it.isNotBlank() }?.let { add(Part(it)) }
        }
    }

    data class Opds(
        val author: String?,
        val publisher: String?,
    ) : BrowseRowMeta {
        override fun sublineParts(): List<Part> = buildList {
            val name = author?.takeIf { it.isNotBlank() }
                ?: publisher?.takeIf { it.isNotBlank() }
            name?.let { add(Part(it)) }
        }
    }

    /** Rows from a source with no per-row template (e.g. Cloud collection). */
    data object Other : BrowseRowMeta {
        override fun sublineParts(): List<Part> = emptyList()
    }

    companion object {
        private val YEAR_MONTH_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM", Locale.US).withZone(ZoneOffset.UTC)

        private fun formatRating(rating: Float): String {
            val clamped = rating.coerceIn(0f, 5f)
            return if (clamped % 1f == 0f) {
                clamped.toInt().toString()
            } else {
                String.format(Locale.US, "%.1f", clamped)
            }
        }

        private fun formatCount(count: Int): String =
            String.format(Locale.US, "%,d", count)

        fun formatEpochRelativeAge(
            epochSeconds: Long?,
            nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
        ): String? {
            val ts = epochSeconds?.takeIf { it > 0L } ?: return null
            val deltaSeconds = (nowEpochSeconds - ts).coerceAtLeast(0L)
            return when {
                deltaSeconds < 3_600L -> (deltaSeconds / 60L).coerceAtLeast(1L).let { tr("${it}m ago", "$it 分钟前", "$it 分鐘前", "$it 分前") }
                deltaSeconds < 86_400L -> (deltaSeconds / 3_600L).let { tr("${it}h ago", "$it 小时前", "$it 小時前", "$it 時間前") }
                deltaSeconds < 60L * 86_400L -> (deltaSeconds / 86_400L).let { tr("${it}d ago", "$it 天前", "$it 天前", "$it 日前") }
                else -> runCatching { YEAR_MONTH_FORMATTER.format(Instant.ofEpochSecond(ts)) }.getOrNull()
            }
        }
    }
}

/** Picks the per-source template for this feed row. */
fun BrowseFeedItem.rowMeta(
    nowEpochSeconds: Long = System.currentTimeMillis() / 1_000L,
): BrowseRowMeta = when (providerId) {
    // #D4: ExHentai serves the same list markup, so it shares the E-Hentai row (it used to fall to Other: empty).
    EhentaiEnableStore.SOURCE_ID, ExhentaiEnableStore.SOURCE_ID -> BrowseRowMeta.Eh(
        category = category,
        rating = rating,
        postedAge = postedAge,
        language = language,
    )
    NhentaiEnableStore.SOURCE_ID -> BrowseRowMeta.Nh(
        pages = pageCount,
        favorites = favorites,
    )
    GalleryIdentityV1.ZAIMANHUA -> BrowseRowMeta.Zaimanhua(
        status = category,
        authors = author,
        postedAge = postedAge ?: BrowseRowMeta.formatEpochRelativeAge(uploadDateEpochSeconds, nowEpochSeconds),
    )
    GalleryIdentityV1.JM -> BrowseRowMeta.Jm(
        category = category,
        authors = author,
        postedAge = postedAge ?: BrowseRowMeta.formatEpochRelativeAge(uploadDateEpochSeconds, nowEpochSeconds),
    )
    else -> if (author != null || publisher != null) {
        BrowseRowMeta.Opds(author = author, publisher = publisher)
    } else {
        BrowseRowMeta.Other
    }
}

/**
 * Thumbnail bottom-right badge:
 * - Page count (`24p`) for gallery/OPDS rows when present;
 * - Latest chapter label (`第321话`) for Zaimanhua rows;
 * - Main category (`韓漫` / `單本` / `同人`) for JM catalogue rows.
 */
val BrowseFeedItem.thumbBadge: String?
    get() = when {
        pageCount != null && pageCount > 0 -> "${pageCount}p"
        providerId == GalleryIdentityV1.ZAIMANHUA -> latestChapterLabel?.takeIf { it.isNotBlank() }
        providerId == GalleryIdentityV1.JM -> category?.takeIf { it.isNotBlank() }
        else -> null
    }

/** Up to 4 Quiet Shelf micro-chips rendered on Browse list cards. */
fun BrowseFeedItem.cardMicroChips(maxChips: Int = 4): List<String> = when (providerId) {
    GalleryIdentityV1.ZAIMANHUA, GalleryIdentityV1.JM ->
        tags.map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            .distinct()
            .take(maxChips)
    EhentaiEnableStore.SOURCE_ID, ExhentaiEnableStore.SOURCE_ID, NhentaiEnableStore.SOURCE_ID -> {
        val cleaned = ComicTitleCleaner.clean(title)
        buildList {
            val lang = language?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?: cleaned.languageTag
            lang?.let { add(it) }
            cleaned.authorOrCircle?.let { add(it) }
            tags.map { it.trim().substringAfter(':') }
                .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                .forEach { add(it) }
        }.distinct().take(maxChips.coerceAtMost(3))
    }
    else -> emptyList()
}

/**
 * #E115: Direction-aware collapsible Browse header policy.
 * Collapses the Search Pill + Active Filter Chips block when scrolling down past the top
 * of the list, and immediately expands it when scrolling up or when near the top.
 */
object BrowseHeaderScrollPolicy {
    private const val TOP_EXPANDED_OFFSET_PX = 80
    private const val DIRECTION_HYSTERESIS_PX = 12

    fun nextExpandedState(
        currentlyExpanded: Boolean,
        hasResults: Boolean,
        loading: Boolean,
        previousIndex: Int,
        previousOffset: Int,
        currentIndex: Int,
        currentOffset: Int,
    ): Boolean {
        if (!hasResults || loading) return true
        if (currentIndex == 0 && currentOffset < TOP_EXPANDED_OFFSET_PX) return true
        return when {
            currentIndex > previousIndex -> false
            currentIndex < previousIndex -> true
            currentOffset - previousOffset > DIRECTION_HYSTERESIS_PX -> false
            previousOffset - currentOffset > DIRECTION_HYSTERESIS_PX -> true
            else -> currentlyExpanded
        }
    }
}
