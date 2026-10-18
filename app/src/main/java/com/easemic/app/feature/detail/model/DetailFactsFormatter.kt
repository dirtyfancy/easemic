package com.easemic.app.feature.detail.model

import com.easemic.app.feature.source.SeriesDate
import com.easemic.app.feature.source.valueOrNull
import com.easemic.app.i18n.AppI18n
import com.easemic.app.i18n.AppLanguage
import com.easemic.app.i18n.tr
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
    private val englishDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.ENGLISH)
    private fun dateFormat(): DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(activeLocale())
    private fun activeLocale(): Locale = when (AppI18n.currentLanguage) {
        AppLanguage.EN -> Locale.ENGLISH
        AppLanguage.ZH_HANS -> Locale.SIMPLIFIED_CHINESE
        AppLanguage.ZH_HANT -> Locale.TRADITIONAL_CHINESE
        AppLanguage.JA -> Locale.JAPANESE
    }
    private fun grouped(value: Int): String = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(value)
    fun localDate(value: SeriesDate, zone: ZoneId = ZoneId.systemDefault()): LocalDate = when (value) {
        is SeriesDate.CalendarDate -> value.value
        is SeriesDate.EpochSeconds -> Instant.ofEpochSecond(value.value).atZone(zone).toLocalDate()
    }
    fun count(value: DetailCount): String = when (value) {
        is DetailCount.Pages -> {
            val baseEnglish = "${grouped(value.total)} ${if (value.total == 1) "page" else "pages"}"
            val base = baseEnglish
            val localizedBase = tr(
                base,
                "${grouped(value.total)} 页",
                "${grouped(value.total)} 頁",
                "${grouped(value.total)} ページ",
            )
            val trimmed = value.trimmedAdPages?.takeIf { it > 0 }
            if (trimmed == null) {
                localizedBase
            } else {
                tr(
                    "$base · ${grouped(trimmed)} ${if (trimmed == 1) "ad" else "ads"} trimmed",
                    "$localizedBase · 已裁剪 ${grouped(trimmed)} 个广告页",
                    "$localizedBase · 已裁剪 ${grouped(trimmed)} 個廣告頁",
                    "$localizedBase · 広告ページ ${grouped(trimmed)} 枚を削除",
                )
            }
        }
        is DetailCount.Chapters -> {
            val noun = if (value.total == 1) "chapter" else "chapters"
            val base = when (value.mode) {
                DetailCountMode.EXACT -> tr("${grouped(value.total)} $noun", "${grouped(value.total)} 章", "${grouped(value.total)} 章", "${grouped(value.total)} チャプター")
                DetailCountMode.LOADED -> tr("${grouped(value.total)} $noun loaded", "已加载 ${grouped(value.total)} 章", "已載入 ${grouped(value.total)} 章", "${grouped(value.total)} チャプターを読み込み済み")
                DetailCountMode.SAVED -> tr("${grouped(value.total)} saved $noun", "已保存 ${grouped(value.total)} 章", "已儲存 ${grouped(value.total)} 章", "保存済み ${grouped(value.total)} チャプター")
            }
            val pages = value.totalPages?.takeIf { it > 0 && value.total == 1 && value.mode == DetailCountMode.EXACT }
            if (pages == null) base else "$base · ${count(DetailCount.Pages(pages))}"
        }
    }
    fun format(detail: ComicDetail, profile: SourceDetailProfile): DetailFacts {
        val sourceName = detail.sourceName.valueOrNull()?.let {
            if (it == "On this phone") tr(it, "在本机", "在本機", "この端末") else it
        }
        val kind = when (val raw = detail.kind.valueOrNull()) {
            "Ongoing" -> tr(raw, "连载中", "連載中", "連載中")
            "Completed" -> tr(raw, "已完结", "已完結", "完結")
            else -> raw
        }
        val line = listOfNotNull(sourceName, kind,
            detail.count.valueOrNull()?.let(::count), detail.language.valueOrNull())
            .filter(String::isNotBlank).joinToString(" · ")
        val date = detail.date.valueOrNull()?.let {
            val englishDate = "${if (it.kind == DetailDateKind.UPDATED) "Updated" else profile.publishedLabel} · ${englishDateFormat.format(it.value)}"
            if (AppI18n.currentLanguage == AppLanguage.EN) {
                englishDate
            } else {
                val label = if (it.kind == DetailDateKind.UPDATED) {
                    tr("Updated", "更新于", "更新於", "更新")
                } else {
                    when (profile.publishedLabel) {
                        "Posted" -> tr("Posted", "发布于", "發佈於", "投稿")
                        "Uploaded" -> tr("Uploaded", "上传于", "上傳於", "アップロード")
                        else -> tr("Published", "发布于", "發佈於", "公開")
                    }
                }
                "$label · ${dateFormat().format(it.value)}"
            }
        }
        val numbers = detail.numbers.valueOrNull().orEmpty().map { number ->
            when (number.kind) {
                DetailNumberKind.RATING -> {
                    val text = BigDecimal(number.decimal).setScale(1, RoundingMode.HALF_UP).toPlainString()
                    "★ $text" to tr("Rating $text", "评分 $text", "評分 $text", "評価 $text")
                }
                DetailNumberKind.FAVOURITES -> {
                    val text = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(BigDecimal(number.decimal))
                    "♥ $text" to tr("$text favourites", "$text 个收藏", "$text 個收藏", "お気に入り $text 件")
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
