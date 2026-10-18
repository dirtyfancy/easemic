package com.easemic.app.feature.detail.model

import com.easemic.app.feature.source.SeriesDate
import com.easemic.app.feature.source.valueOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * [dateLine] is the joined form ("Updated · Oct 4, 2026 · ★ 4.6") for consumers
 * that render one string. [date] and [numberTexts] are the same parts unjoined —
 * the date element alone ("Updated · Oct 4, 2026") and the number display texts
 * in the same order as [numberDescriptions] — so the detail header can put each
 * number on its own Text with its own contentDescription and still read as
 * [dateLine]. Both default so existing constructions keep compiling.
 */
data class DetailFacts(
    val line: String,
    val dateLine: String?,
    val numberDescriptions: List<String>,
    val date: String? = null,
    val numberTexts: List<String> = emptyList(),
)
object DetailFactsFormatter {
    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.ENGLISH)
    private fun grouped(value: Int): String = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(value)
    fun localDate(value: SeriesDate, zone: ZoneId = ZoneId.systemDefault()): LocalDate = when (value) {
        is SeriesDate.CalendarDate -> value.value
        is SeriesDate.EpochSeconds -> Instant.ofEpochSecond(value.value).atZone(zone).toLocalDate()
    }
    fun count(value: DetailCount): String = when (value) {
        is DetailCount.Pages -> {
            val base = "${grouped(value.total)} ${if (value.total == 1) "page" else "pages"}"
            val trimmed = value.trimmedAdPages?.takeIf { it > 0 }
            if (trimmed == null) {
                base
            } else {
                "$base · ${grouped(trimmed)} ${if (trimmed == 1) "ad" else "ads"} trimmed"
            }
        }
        is DetailCount.Chapters -> {
            val noun = if (value.total == 1) "chapter" else "chapters"
            val base = when (value.mode) {
                DetailCountMode.EXACT -> "${grouped(value.total)} $noun"
                DetailCountMode.LOADED -> "${grouped(value.total)} $noun loaded"
                DetailCountMode.SAVED -> "${grouped(value.total)} saved $noun"
            }
            val pages = value.totalPages?.takeIf { it > 0 && value.total == 1 && value.mode == DetailCountMode.EXACT }
            if (pages == null) base else "$base · ${count(DetailCount.Pages(pages))}"
        }
    }
    fun format(detail: ComicDetail, profile: SourceDetailProfile): DetailFacts {
        val line = listOfNotNull(detail.sourceName.valueOrNull(), detail.kind.valueOrNull(),
            detail.count.valueOrNull()?.let(::count), detail.language.valueOrNull())
            .filter(String::isNotBlank).joinToString(" · ")
        val date = detail.date.valueOrNull()?.let {
            "${if (it.kind == DetailDateKind.UPDATED) "Updated" else profile.publishedLabel} · ${dateFormat.format(it.value)}"
        }
        val numbers = detail.numbers.valueOrNull().orEmpty().map { number ->
            when (number.kind) {
                DetailNumberKind.RATING -> {
                    val text = BigDecimal(number.decimal).setScale(1, RoundingMode.HALF_UP).toPlainString()
                    "★ $text" to "Rating $text"
                }
                DetailNumberKind.FAVOURITES -> {
                    val text = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(BigDecimal(number.decimal))
                    "♥ $text" to "$text favourites"
                }
            }
        }
        return DetailFacts(
            line,
            (listOfNotNull(date) + numbers.map { it.first }).joinToString(" · ").takeIf(String::isNotEmpty),
            numbers.map { it.second },
            date = date,
            numberTexts = numbers.map { it.first },
        )
    }
}
