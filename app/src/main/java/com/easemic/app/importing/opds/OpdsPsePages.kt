package com.easemic.app.importing.opds

object OpdsPsePages {
    fun expand(
        template: String,
        pageCount: Int,
        maxWidth: Int?,
    ): List<String> {
        if (pageCount <= 0) return emptyList()
        val width = maxWidth?.toString() ?: "2048"
        return (0 until pageCount).map { page ->
            var url = template.replace("{pageNumber}", page.toString())
            if (url.contains("{maxWidth}")) {
                url = url.replace("{maxWidth}", width)
            }
            url
        }
    }
}
