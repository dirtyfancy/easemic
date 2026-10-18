package com.easemic.app.feature.detail

import com.easemic.app.model.PageRef

/**
 * A32 §1: a Detail image is a *source*, not a URL. A local page cannot be an
 * http string, so the one skin takes this instead of `String?`/`List<String>`.
 * The [Url] branch renders exactly what the catalog path rendered before.
 */
sealed interface DetailImage {
    data class Url(val url: String) : DetailImage

    data class Page(val page: PageRef) : DetailImage

    /** C41: the ordinal's cloud grid thumb is missing or unreadable. */
    data object Missing : DetailImage
}
