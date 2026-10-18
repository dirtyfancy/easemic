package com.easemic.app.importing

import com.easemic.app.model.PageDescriptor

object ReaderSupportPolicy {
    // Reversible reader default below the import validator's 150M-pixel ceiling.
    private const val MAX_DIMENSION = 32_768
    private const val MAX_PIXELS = 100_000_000L

    fun supports(page: PageDescriptor): Boolean =
        supports(page.width, page.height)

    fun supportsPersistedPage(width: Int, height: Int): Boolean =
        (width == 0 && height == 0) || supports(width, height)

    fun supports(width: Int, height: Int): Boolean =
        width > 0 &&
            height > 0 &&
            width <= MAX_DIMENSION &&
            height <= MAX_DIMENSION &&
            width.toLong() * height.toLong() <= MAX_PIXELS
}
