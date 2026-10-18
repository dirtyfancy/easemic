package com.easemic.app.sync.archive

/**
 * #D7: entry names inside the archive ZIP. Every name is generated here from a sequence number and a validated page
 * file name — never from a title or an id — so reading can refuse anything else, and no name hits a path limit.
 */
internal object ArchiveLayout {
    const val MANIFEST = "manifest.json"
    const val STATE = "state.json"
    private val PAGE_FILE = Regex("""^[0-9]{1,6}\.[a-z0-9]{1,5}$""")
    private val COMIC = Regex("""^comics/([0-9]{1,7})/comic\.json$""")
    private val PAGE = Regex("""^comics/([0-9]{1,7})/pages/([^/]+)$""")
    private val SERIES = Regex("""^series/([0-9]{1,7})/series\.json$""")
    private val SERIES_COVER = Regex("""^series/([0-9]{1,7})/(cover\.[a-z0-9]{1,5})$""")
    private val CHAPTER = Regex("""^series/([0-9]{1,7})/chapters/([0-9]{1,7})/chapter\.json$""")
    private val CHAPTER_PAGE = Regex("""^series/([0-9]{1,7})/chapters/([0-9]{1,7})/pages/([^/]+)$""")

    fun comic(index: Int) = "comics/$index/comic.json"

    fun page(index: Int, file: String): String {
        require(isPageFile(file)) { "bad page file name" }
        return "comics/$index/pages/$file"
    }

    fun series(index: Int) = "series/$index/series.json"

    fun seriesCover(index: Int, file: String): String {
        require(isCoverFile(file)) { "bad cover file name" }
        return "series/$index/$file"
    }

    fun chapter(seriesIndex: Int, chapterIndex: Int) = "series/$seriesIndex/chapters/$chapterIndex/chapter.json"

    fun chapterPage(seriesIndex: Int, chapterIndex: Int, file: String): String {
        require(isPageFile(file)) { "bad page file name" }
        return "series/$seriesIndex/chapters/$chapterIndex/pages/$file"
    }

    fun isPageFile(name: String) = PAGE_FILE.matches(name)
    fun isCoverFile(name: String) = name.matches(Regex("^cover\\.[a-z0-9]{1,5}$"))

    sealed interface Entry {
        data object Manifest : Entry
        data object State : Entry
        data class Comic(val index: Int) : Entry
        data class Page(val index: Int, val file: String) : Entry
        data class Series(val index: Int) : Entry
        data class SeriesCover(val index: Int, val file: String) : Entry
        data class Chapter(val seriesIndex: Int, val index: Int) : Entry
        data class ChapterPage(val seriesIndex: Int, val chapterIndex: Int, val file: String) : Entry
    }

    fun parse(name: String): Entry {
        if (name == MANIFEST) return Entry.Manifest
        if (name == STATE) return Entry.State
        COMIC.matchEntire(name)?.let { return Entry.Comic(it.groupValues[1].toInt()) }
        PAGE.matchEntire(name)?.let {
            val file = it.groupValues[2]
            if (isPageFile(file)) return Entry.Page(it.groupValues[1].toInt(), file)
        }
        SERIES.matchEntire(name)?.let { return Entry.Series(it.groupValues[1].toInt()) }
        SERIES_COVER.matchEntire(name)?.let { return Entry.SeriesCover(it.groupValues[1].toInt(), it.groupValues[2]) }
        CHAPTER.matchEntire(name)?.let {
            return Entry.Chapter(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        CHAPTER_PAGE.matchEntire(name)?.let {
            val file = it.groupValues[3]
            if (isPageFile(file)) return Entry.ChapterPage(it.groupValues[1].toInt(), it.groupValues[2].toInt(), file)
        }
        throw ArchiveFormatException("unexpected entry in archive")
    }
}
